import http from 'node:http';
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { once } from 'node:events';
import { HttpError, errorBody, parseRequest, usageForOpenAI } from './protocol.js';

function json(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(body));
}

async function readBody(request, maxBytes) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] || '')) {
    throw new HttpError(415, 'Use Content-Type: application/json.');
  }
  if (request.headers['content-encoding'] && request.headers['content-encoding'] !== 'identity') {
    throw new HttpError(415, 'Compressed request bodies are not supported.');
  }
  if (Number(request.headers['content-length']) > maxBytes) {
    throw new HttpError(413, 'Request body is too large.', 'request_too_large');
  }
  // Avoid destroying the socket on an early async-iterator return: the client
  // should receive the actual 413 response for a chunked oversized request.
  return new Promise((resolve, reject) => {
    const chunks = [];
    let bytes = 0;
    const cleanup = () => {
      request.off('data', data);
      request.off('end', end);
      request.off('error', fail);
      request.off('aborted', aborted);
    };
    const fail = (error) => { cleanup(); reject(error); };
    const aborted = () => fail(new HttpError(400, 'Request body was interrupted.'));
    const data = (chunk) => {
      bytes += chunk.length;
      if (bytes > maxBytes) {
        fail(new HttpError(413, 'Request body is too large.', 'request_too_large'));
        request.resume();
      } else chunks.push(chunk);
    };
    const end = () => {
      cleanup();
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch { reject(new HttpError(400, 'Request body is not valid JSON.')); }
    };
    request.on('data', data);
    request.once('end', end);
    request.once('error', fail);
    request.once('aborted', aborted);
  });
}

export function createServer(config, backend, log = (entry) => console.log(JSON.stringify(entry))) {
  let active = 0;
  const controllers = new Set();
  const pending = new Set();
  const expectedToken = createHash('sha256').update(`Bearer ${config.token}`).digest();
  const models = Object.keys(config.models).map((id) => ({ id, object: 'model', created: 0, owned_by: 'codex-bridge' }));

  const server = http.createServer({ requestTimeout: 15000, headersTimeout: 10000, maxHeaderSize: 16384 }, async (req, res) => {
    const id = `chatcmpl-${randomUUID()}`;
    const started = Date.now();
    let controller;
    let timeout;
    let heartbeat;
    let onClose;
    let admitted = false;
    let finished;
    let settled;
    let model;
    try {
      const address = req.socket.localAddress;
      const port = req.socket.localPort;
      const allowedHosts = [`127.0.0.1:${port}`, `localhost:${port}`];
      if (address !== '127.0.0.1' || !allowedHosts.includes(req.headers.host) || req.headers.origin !== undefined) {
        throw new HttpError(403, 'Only local non-browser clients are allowed.', 'forbidden');
      }
      const suppliedToken = createHash('sha256').update(req.headers.authorization || '').digest();
      if (!timingSafeEqual(expectedToken, suppliedToken)) {
        res.setHeader('WWW-Authenticate', 'Bearer');
        throw new HttpError(401, 'Invalid local provider token.', 'invalid_api_key');
      }
      const url = new URL(req.url, `http://${req.headers.host}`);
      if (req.method === 'GET' && url.pathname === '/health') {
        json(res, 200, { status: 'ok', active_requests: active });
        return;
      }
      if (req.method === 'GET' && ['/models', '/v1/models'].includes(url.pathname)) {
        json(res, 200, { object: 'list', data: models });
        return;
      }
      if (req.method === 'GET' && /^\/(v1\/)?models\//.test(url.pathname)) {
        const modelId = decodeURIComponent(url.pathname.split('/').at(-1));
        const found = models.find((entry) => entry.id === modelId);
        if (!found) throw new HttpError(404, 'Unknown model.', 'model_not_found');
        json(res, 200, found);
        return;
      }
      if (['/v1/completions', '/completions', '/v1/responses', '/responses', '/v1/embeddings', '/embeddings'].includes(url.pathname)) {
        throw new HttpError(501, 'This bridge supports text Chat Completions only. Use it for Core features and Instant helpers, not AI Completion.', 'unsupported_endpoint');
      }
      if (!['/chat/completions', '/v1/chat/completions'].includes(url.pathname)) {
        throw new HttpError(404, 'Endpoint not found.', 'not_found');
      }
      if (req.method !== 'POST') throw new HttpError(405, 'Use POST for chat completions.', 'method_not_allowed');
      if (active >= config.maxConcurrent) {
        res.setHeader('Retry-After', '2');
        throw new HttpError(429, 'The bridge is busy. Retry shortly.', 'bridge_busy');
      }
      active++;
      admitted = true;
      settled = new Promise((resolve) => { finished = resolve; });
      pending.add(settled);
      const request = parseRequest(await readBody(req, config.maxBodyBytes), config.models);
      model = request.model;
      controller = new AbortController();
      controllers.add(controller);
      onClose = () => { if (!res.writableFinished) controller.abort(new Error('Client disconnected')); };
      res.once('close', onClose);
      if (res.destroyed) controller.abort(new Error('Client disconnected'));
      timeout = setTimeout(() => controller.abort(new HttpError(504, 'Codex request timed out.', 'timeout')), config.timeoutMs);
      const write = async (data) => {
        controller.signal.throwIfAborted();
        if (!res.write(data)) await once(res, 'drain', { signal: controller.signal });
      };
      const created = Math.floor(Date.now() / 1000);
      const chunk = (delta, finishReason = null) => ({
        id, object: 'chat.completion.chunk', created, model,
        choices: [{ index: 0, delta, finish_reason: finishReason }],
      });
      if (request.stream) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache, no-transform', 'X-Accel-Buffering': 'no' });
        await write(`data: ${JSON.stringify(chunk({ role: 'assistant' }))}\n\n`);
        heartbeat = setInterval(() => {
          if (!res.destroyed && !res.writableNeedDrain) res.write(': waiting for Codex\n\n');
        }, 5000);
      }
      // SDK events are not token deltas. Buffer until a successful completed turn
      // so intermediate agent messages can never corrupt an editor replacement.
      const result = await backend.generate(request, controller.signal);
      controller.signal.throwIfAborted();
      const usage = usageForOpenAI(result.usage);
      if (request.stream) {
        await write(`data: ${JSON.stringify(chunk({ content: result.text }))}\n\n`);
        await write(`data: ${JSON.stringify(chunk({}, 'stop'))}\n\n`);
        if (request.includeUsage && usage) {
          await write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', created, model, choices: [], usage })}\n\n`);
        }
        await write('data: [DONE]\n\n');
        res.end();
      } else {
        json(res, 200, {
          id, object: 'chat.completion', created, model,
          choices: [{ index: 0, message: { role: 'assistant', content: result.text }, finish_reason: 'stop' }],
          ...(usage ? { usage } : {}),
        });
      }
      log({ request: id, model, status: 'completed', duration_ms: Date.now() - started });
    } catch (cause) {
      const reason = controller?.signal.aborted ? controller.signal.reason : cause;
      const error = reason instanceof HttpError ? reason : new HttpError(502, 'Codex bridge request failed. Run npm run doctor to check configuration.', 'codex_error');
      if (!res.destroyed && !res.writableEnded) {
        if (res.headersSent) res.end(`data: ${JSON.stringify(errorBody(error))}\n\n`);
        else json(res, error.status, errorBody(error));
      }
      log({ request: id, ...(model ? { model } : {}), status: res.destroyed ? 'cancelled' : error.code, duration_ms: Date.now() - started });
    } finally {
      clearTimeout(timeout);
      clearInterval(heartbeat);
      if (controller) controllers.delete(controller);
      if (onClose) res.off('close', onClose);
      if (admitted) active--;
      if (settled) { pending.delete(settled); finished(); }
    }
  });
  server.on('clientError', (_error, socket) => {
    if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
  });
  return {
    server,
    async listen() {
      server.listen(config.port, config.host);
      await once(server, 'listening');
      return server.address();
    },
    async close() {
      for (const controller of controllers) controller.abort(new HttpError(503, 'The bridge is shutting down.', 'shutting_down'));
      const closed = new Promise((resolve) => server.close(resolve));
      server.closeAllConnections();
      await closed;
      await Promise.allSettled([...pending]);
    },
  };
}
