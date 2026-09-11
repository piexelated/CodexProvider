import test from 'node:test';
import assert from 'node:assert/strict';
import { createBackend, codexEnvironment, bundledCli } from '../src/codex.js';
import { loadConfig } from '../src/config.js';
import { parseRequest } from '../src/protocol.js';
import { access, readFile } from 'node:fs/promises';

const request = () => parseRequest({ model: 'codex-bridge', messages: [{ role: 'user', content: 'Return replacement code.' }] }, { 'codex-bridge': 'chosen-model' });

test('SDK receives subscription-only config, read-only sandbox, schema and cancellation', async () => {
  let options, threadOptions, turnOptions, prompt;
  class FakeCodex {
    constructor(value) { options = value; }
    startThread(value) {
      threadOptions = value;
      return { async runStreamed(input, turn) {
        prompt = input; turnOptions = turn;
        return { events: (async function* () {
          yield { type: 'item.completed', item: { type: 'agent_message', text: 'Intermediate commentary' } };
          yield { type: 'item.completed', item: { type: 'agent_message', text: '{"ok":true}' } };
          yield { type: 'turn.completed', usage: { input_tokens: 10, output_tokens: 4 } };
        })() };
      } };
    }
  }
  const config = loadConfig({});
  const backend = await createBackend(config, FakeCodex);
  const signal = new AbortController().signal;
  const input = { ...request(), jsonOutput: true, outputSchema: { type: 'object' } };
  try {
    const result = await backend.generate(input, signal);
    assert.equal(result.text, '{"ok":true}');
    assert.equal(options.config.forced_login_method, 'chatgpt');
    assert.equal(options.config.model_provider, 'openai');
    assert.equal(options.config.features.shell_tool, false);
    assert.equal(options.config.features.multi_agent, false);
    assert.equal(threadOptions.sandboxMode, 'read-only');
    assert.equal(threadOptions.approvalPolicy, 'never');
    assert.equal(threadOptions.model, 'chosen-model');
    assert.equal(turnOptions.signal, signal);
    assert.deepEqual(turnOptions.outputSchema, input.outputSchema);
    assert.match(prompt, /Return replacement code/);
    assert.match(await readFile(options.config.model_instructions_file, 'utf8'), /Never run tools/);
  } finally { await backend.close(); }
  await assert.rejects(access(threadOptions.workingDirectory));
});

test('failed, incomplete, oversized, or invalid JSON generations fail closed', async () => {
  for (const [events, overrides] of [
    [[{ type: 'turn.failed', error: { message: 'secret' } }], {}],
    [[{ type: 'error', message: 'secret' }], {}],
    [[{ type: 'item.completed', item: { type: 'agent_message', text: 'truncated' } }], {}],
    [[{ type: 'item.completed', item: { type: 'agent_message', text: 'not json' } }, { type: 'turn.completed' }], { jsonOutput: true }],
    [[{ type: 'item.completed', item: { type: 'agent_message', text: '[]' } }, { type: 'turn.completed' }], { jsonOutput: true }],
    [[{ type: 'item.completed', item: { type: 'agent_message', text: 'x'.repeat(100) } }, { type: 'turn.completed' }], {}],
  ]) {
    class FakeCodex {
      startThread() { return { async runStreamed() { return { events: (async function* () { yield* events; })() }; } }; }
    }
    const backend = await createBackend({ ...loadConfig({}), maxOutputBytes: 50 }, FakeCodex);
    try {
      await assert.rejects(backend.generate({ ...request(), ...overrides }, new AbortController().signal), (error) => {
        assert.equal(error.status, 502);
        assert.ok(!error.message.includes('secret'));
        return true;
      });
    } finally { await backend.close(); }
  }
});

test('Codex environment excludes API billing and inherited provider overrides', () => {
  const env = codexEnvironment({ codexHome: '/dedicated' }, {
    HOME: '/home/test', PATH: '/bin', OPENAI_API_KEY: 'secret', CODEX_API_KEY: 'secret',
    OPENAI_BASE_URL: 'https://example.invalid', CODEX_HOME: '/other', BRIDGE_TOKEN: 'secret',
    CODEX_INTERNAL_ORIGINATOR_OVERRIDE: 'something',
  });
  assert.deepEqual(env, { HOME: '/home/test', PATH: '/bin', CODEX_HOME: '/dedicated' });
});

test('bundled login CLI resolves and config rejects invalid limits', async () => {
  await access(bundledCli());
  assert.throws(() => loadConfig({ BRIDGE_PORT: '0' }));
  assert.throws(() => loadConfig({ BRIDGE_MAX_CONCURRENT: '999' }));
  assert.throws(() => loadConfig({ BRIDGE_TIMEOUT_MS: 'NaN' }));
  assert.throws(() => loadConfig({ BRIDGE_REASONING_EFFORT: 'invalid' }));
});
