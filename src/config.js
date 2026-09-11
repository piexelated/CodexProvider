import { randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const projectRoot = fileURLToPath(new URL('../', import.meta.url));

function integer(env, name, fallback, min, max) {
  const value = env[name] === undefined ? fallback : Number(env[name]);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}.`);
  }
  return value;
}

export function loadConfig(env = process.env) {
  const stateDir = path.join(projectRoot, '.bridge');
  const model = env.BRIDGE_MODEL?.trim() || undefined;
  const helperModel = env.BRIDGE_HELPER_MODEL?.trim() || model;
  const effort = env.BRIDGE_REASONING_EFFORT || 'low';
  if (!['minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'].includes(effort)) {
    throw new Error('BRIDGE_REASONING_EFFORT is not a supported reasoning effort.');
  }
  return {
    host: '127.0.0.1',
    port: integer(env, 'BRIDGE_PORT', 31337, 1, 65535),
    timeoutMs: integer(env, 'BRIDGE_TIMEOUT_MS', 120000, 1000, 600000),
    maxConcurrent: integer(env, 'BRIDGE_MAX_CONCURRENT', 2, 1, 8),
    maxBodyBytes: 2 * 1024 * 1024,
    maxOutputBytes: 4 * 1024 * 1024,
    stateDir,
    codexHome: path.resolve(env.BRIDGE_CODEX_HOME || path.join(stateDir, 'codex')),
    codexPath: env.BRIDGE_CODEX_PATH || undefined,
    tokenFile: path.join(stateDir, 'provider-token'),
    token: env.BRIDGE_TOKEN,
    effort,
    models: { 'codex-bridge': model, 'codex-bridge-fast': helperModel },
  };
}

export async function prepareConfig(config) {
  await mkdir(config.stateDir, { recursive: true, mode: 0o700 });
  await mkdir(config.codexHome, { recursive: true, mode: 0o700 });
  if (!config.token) {
    try {
      await writeFile(config.tokenFile, randomBytes(32).toString('hex'), { flag: 'wx', mode: 0o600 });
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
    }
    config.token = (await readFile(config.tokenFile, 'utf8')).trim();
  }
  if (config.token.length < 24 || /\s/.test(config.token)) {
    throw new Error('The local provider token must have at least 24 characters and no whitespace.');
  }
  return config;
}
