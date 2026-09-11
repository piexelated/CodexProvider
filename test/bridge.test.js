import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createServer } from '../src/server.js';
import { loadConfig } from '../src/config.js';
import { HttpError } from '../src/protocol.js';

const token = 'test-provider-token-not-a-real-secret';
const usage = { input_tokens: 20, cached_input_tokens: 5, output_tokens: 7, reasoning_output_tokens: 2 };
const body = { model: 'codex-bridge', messages: [{ role: 'user', content: 'Suggest a name.' }] };

async function start(t, generate = async () => ({ text: 'regionCount', usage }), overrides = {}) {
  const config = { ...loadConfig({}), token, port: 0, ...overrides };
  const logs = [];
  const bridge = createServer(config, { generate }, (entry) => logs.push(entry));
  const address = await bridge.listen();
  t.after(() => bridge.close());
  const url = `http://127.0.0.1:${address.port}`;
  return {
    bridge, url, logs,
    async request(data = body, route = '/v1/chat/completions', headers = {}) {
      return fetch(url + route, {
        method: data === null ? 'GET' : 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...headers },
        ...(data === null ? {} : { body: typeof data === 'string' ? data : JSON.stringify(data) }),
      });
    },
  };
}

test('model discovery and non-streamed responses have the OpenAI wire shape', async (t) => {
  const app = await start(t);
  const list = await (await app.request(null, '/v1/models')).json();
  assert.deepEqual(list.data.map((x) => x.id), ['codex-bridge', 'codex-bridge-fast']);
  assert.equal((await app.request(null, '/models')).status, 200);
  assert.equal((await app.request(null, '/v1/models/codex-bridge')).status, 200);
  assert.equal((await app.request(null, '/v1/models/missing')).status, 404);
  const response = await app.request();
  const result = await response.json();
  assert.equal(response.status, 200);
  assert.equal(result.choices[0].message.content, 'regionCount');
  assert.equal(result.choices[0].finish_reason, 'stop');
  assert.equal(result.usage.total_tokens, 27);
  assert.equal(result.usage.prompt_tokens_details.cached_tokens, 5);
  assert.equal(result.usage.completion_tokens_details.reasoning_tokens, 2);
  assert.equal((await app.request(body, '/chat/completions')).status, 200);
});

test('SSE returns role, final content, finish, usage and DONE in order', async (t) => {
  const app = await start(t);
  const response = await app.request({ ...body, stream: true, stream_options: { include_usage: true } });
  assert.match(response.headers.get('content-type'), /text\/event-stream/);
  const frames = (await response.text()).trim().split('\n\n').map((x) => x.slice(6));
  assert.equal(frames.pop(), '[DONE]');
  const chunks = frames.map(JSON.parse);
  assert.equal(chunks[0].choices[0].delta.role, 'assistant');
  assert.equal(chunks[1].choices[0].delta.content, 'regionCount');
  assert.equal(chunks[2].choices[0].finish_reason, 'stop');
  assert.deepEqual(chunks[3].choices, []);
  assert.equal(chunks[3].usage.total_tokens, 27);
  assert.equal(new Set(chunks.map((x) => x.id)).size, 1);
});

test('invalid requests never invoke Codex', async (t) => {
  let calls = 0;
  const app = await start(t, async () => { calls++; });
  for (const [data, expected] of [
    ['{broken', 400], [{}, 404], [{ ...body, model: 'gpt-made-up' }, 404],
    [{ ...body, messages: [] }, 400], [{ ...body, stream: 'true' }, 400],
    [{ ...body, tools: [{ type: 'function' }] }, 400],
    [{ ...body, messages: [{ role: 'user', content: [{ type: 'image_url', image_url: { url: 'file:///secret' } }] }] }, 400],
    [{ ...body, messages: [{ role: 'tool', content: 'tool result' }] }, 400],
    [{ ...body, response_format: { type: 'json_schema' } }, 400],
    [{ ...body, stop: ['END'] }, 400], [{ ...body, n: 2 }, 400],
  ]) {
    const response = await app.request(data);
    assert.equal(response.status, expected);
    assert.equal(typeof (await response.json()).error.message, 'string');
  }
  for (const endpoint of ['completions', 'responses', 'embeddings']) {
    assert.equal((await app.request(body, `/v1/${endpoint}`)).status, 501);
  }
  assert.equal(calls, 0);
});

test('authorization, browser origin and host validation protect even model discovery', async (t) => {
  const app = await start(t);
  assert.equal((await app.request(null, '/v1/models', { Authorization: 'Bearer wrong' })).status, 401);
  assert.equal((await app.request(null, '/v1/models', { Origin: 'https://evil.example' })).status, 403);
  const badHostStatus = await new Promise((resolve, reject) => {
    const req = http.get(app.url + '/v1/models', {
      headers: { Host: 'evil.example', Authorization: `Bearer ${token}` },
    }, (res) => { res.resume(); resolve(res.statusCode); });
    req.on('error', reject);
  });
  assert.equal(badHostStatus, 403);
  assert.equal((await app.request(null, '/health')).status, 200);
});

test('body size and content type limits work before upstream generation', async (t) => {
  const app = await start(t, undefined, { maxBodyBytes: 256 });
  assert.equal((await app.request('x'.repeat(257))).status, 413);
  assert.equal((await app.request(body, undefined, { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await app.request(body, undefined, { 'Content-Encoding': 'gzip' })).status, 415);
  const response = await new Promise((resolve, reject) => {
    const req = http.request(app.url + '/v1/chat/completions', {
      method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    }, (res) => { res.resume(); resolve(res.statusCode); });
    req.on('error', reject);
    req.write('x'.repeat(300));
    req.end();
  });
  assert.equal(response, 413);
});

test('request translation preserves text parts, ordered history, and JSON schema', async (t) => {
  let received;
  const app = await start(t, async (request) => { received = request; return { text: '{"names":["regionCount"]}' }; });
  const schema = { type: 'object', properties: { names: { type: 'array', items: { type: 'string' } } }, required: ['names'], additionalProperties: false };
  await app.request({
    ...body, model: 'codex-bridge-fast', temperature: 0, max_tokens: 256,
    messages: [{ role: 'system', content: 'Return names as JSON.' }, { role: 'user', content: [{ type: 'text', text: 'int n' }] }],
    response_format: { type: 'json_schema', json_schema: { name: 'names', schema, strict: true } },
  });
  assert.equal(received.messages[0].role, 'system');
  assert.equal(received.messages[1].content, 'int n');
  assert.deepEqual(received.outputSchema, schema);
});

test('timeout cancels upstream and releases the concurrency slot', async (t) => {
  let aborted = false;
  let calls = 0;
  const app = await start(t, async (_request, signal) => {
    if (++calls > 1) return { text: 'ok' };
    await new Promise((_resolve, reject) => signal.addEventListener('abort', () => { aborted = true; reject(signal.reason); }, { once: true }));
  }, { timeoutMs: 30, maxConcurrent: 1 });
  const response = await app.request();
  assert.equal(response.status, 504);
  assert.equal(aborted, true);
  assert.equal((await app.request()).status, 200);
});

test('busy requests return 429 and client disconnect aborts the active turn', async (t) => {
  const began = Promise.withResolvers();
  const ended = Promise.withResolvers();
  const app = await start(t, async (_request, signal) => {
    began.resolve();
    await new Promise((_resolve, reject) => signal.addEventListener('abort', () => { ended.resolve(); reject(signal.reason); }, { once: true }));
  }, { maxConcurrent: 1 });
  const req = http.request(app.url + '/v1/chat/completions', {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  });
  req.on('error', () => {});
  req.end(JSON.stringify({ ...body, stream: true }));
  await began.promise;
  const busy = await app.request();
  assert.equal(busy.status, 429);
  assert.equal(busy.headers.get('retry-after'), '2');
  req.destroy();
  await ended.promise;
});

test('stream failures are errors, not successful truncated completions', async (t) => {
  const app = await start(t, async () => { throw new HttpError(502, 'Upstream failed.', 'codex_error'); });
  const response = await app.request({ ...body, stream: true });
  const text = await response.text();
  assert.match(text, /"error"/);
  assert.doesNotMatch(text, /\[DONE\]|"finish_reason":"stop"/);
});

test('unexpected upstream errors and logs never expose prompt or credentials', async (t) => {
  const secret = 'private-source-code-and-secret-token';
  const app = await start(t, async () => { throw new Error(secret); });
  const response = await app.request({ ...body, messages: [{ role: 'user', content: secret }] });
  assert.equal(response.status, 502);
  assert.ok(!(await response.text()).includes(secret));
  assert.ok(!JSON.stringify(app.logs).includes(secret));
});
