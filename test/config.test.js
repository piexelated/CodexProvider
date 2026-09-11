import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { loadConfig, prepareConfig } from '../src/config.js';

test('generated provider token is private, persistent, and independent from Codex auth', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'bridge-config-test-'));
  const config = { ...loadConfig({}), stateDir: dir, tokenFile: path.join(dir, 'token'), codexHome: path.join(dir, 'codex') };
  try {
    const first = await prepareConfig({ ...config });
    const second = await prepareConfig({ ...config });
    assert.equal(first.token, second.token);
    assert.equal(first.token.length, 64);
    if (process.platform !== 'win32') assert.equal((await stat(config.tokenFile)).mode & 0o777, 0o600);
    assert.equal((await stat(config.codexHome)).isDirectory(), true);
    await assert.rejects(prepareConfig({ ...config, token: 'too short' }));
  } finally { await rm(dir, { recursive: true, force: true }); }
});
