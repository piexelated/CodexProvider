import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { tmpdir } from 'node:os';
import { mkdtemp, copyFile, chmod, rm } from 'node:fs/promises';
import { createBackend, checkAuth } from '../src/codex.js';
import { loadConfig } from '../src/config.js';
import { createServer } from '../src/server.js';

test('real SDK subprocess transport serves HTTP, structured output, and cancellation offline', { skip: process.platform === 'win32' }, async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'bridge-sdk-test-'));
  const codexPath = path.join(dir, 'codex-fixture');
  await copyFile(new URL('../fixtures/codex-cli.fixture', import.meta.url), codexPath);
  await chmod(codexPath, 0o700);
  const config = { ...loadConfig({}), codexPath, codexHome: dir, port: 0, token: 'offline-transport-provider-token', timeoutMs: 500 };
  const backend = await createBackend(config);
  const bridge = createServer(config, backend, () => {});
  try {
    await checkAuth(config);
    const address = await bridge.listen();
    const call = (body) => fetch(`http://127.0.0.1:${address.port}/v1/chat/completions`, {
      method: 'POST', headers: { Authorization: `Bearer ${config.token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    const request = { model: 'codex-bridge', messages: [{ role: 'user', content: 'Suggest a Java name.' }] };
    const response = await call(request);
    assert.equal(response.status, 200);
    assert.equal((await response.json()).choices[0].message.content, 'regionCount');
    const structured = await call({ ...request, response_format: { type: 'json_schema', json_schema: { name: 'names', schema: { type: 'object' } } } });
    assert.equal((await structured.json()).choices[0].message.content, '{"names":["regionCount"]}');
    const stream = await call({ ...request, stream: true });
    const sse = await stream.text();
    assert.match(sse, /regionCount/);
    assert.doesNotMatch(sse, /progress message/);
    const timedOut = await call({ ...request, messages: [{ role: 'user', content: 'WAIT_FOR_CANCELLATION' }] });
    assert.equal(timedOut.status, 504);
    assert.equal((await call(request)).status, 200);
  } finally {
    await bridge.close();
    await backend.close();
    await rm(dir, { recursive: true, force: true });
  }
});
