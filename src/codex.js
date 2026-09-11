import { Codex } from '@openai/codex-sdk';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import path from 'node:path';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { buildPrompt, HttpError } from './protocol.js';

export function codexEnvironment(config, source = process.env) {
  // Do not pass API keys, alternate endpoints, or the IDE provider token to Codex.
  const env = {};
  for (const key of ['PATH', 'HOME', 'USERPROFILE', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'TMPDIR', 'LANG', 'LC_ALL', 'SSL_CERT_FILE', 'SSL_CERT_DIR']) {
    if (source[key] !== undefined) env[key] = source[key];
  }
  env.CODEX_HOME = config.codexHome;
  return env;
}

export function bundledCli() {
  const sdkRequire = createRequire(import.meta.resolve('@openai/codex-sdk'));
  const packageFile = sdkRequire.resolve('@openai/codex/package.json');
  return path.join(path.dirname(packageFile), 'bin', 'codex.js');
}

export async function runAuth(config, args, { inherit = false } = {}) {
  const command = config.codexPath || process.execPath;
  const prefix = config.codexPath ? [] : [bundledCli()];
  return new Promise((resolve, reject) => {
    const child = spawn(command, [...prefix, '-c', 'forced_login_method="chatgpt"', 'login', ...args], {
      env: codexEnvironment(config),
      stdio: inherit ? 'inherit' : ['ignore', 'pipe', 'pipe'],
      timeout: inherit ? undefined : 15000,
    });
    let output = '';
    if (!inherit) {
      child.stdout.on('data', (data) => { output += data; });
      child.stderr.on('data', (data) => { output += data; });
    }
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, output }));
  });
}

export async function checkAuth(config) {
  const result = await runAuth(config, ['status']);
  if (result.code !== 0 || !/logged in using chatgpt/i.test(result.output)) {
    throw new Error('ChatGPT sign-in is required. Run npm run login, then npm run doctor. API-key login is not accepted.');
  }
}

export async function createBackend(config, CodexClass = Codex) {
  // A neutral directory prevents project AGENTS.md files from influencing a
  // rename or inline edit. The IDE supplies all code context in the request.
  const workDir = await mkdtemp(path.join(tmpdir(), 'codex-jetbrains-'));
  const instructions = path.join(workDir, 'instructions.md');
  await writeFile(instructions,
    'You are a text transformation backend for an IDE. Answer the supplied conversation. ' +
    'Follow its requested output format exactly. Never run tools or change files. ' +
    'Do not add progress messages. Return your answer as the final response.\n', { mode: 0o600 });
  let client;
  try {
    client = new CodexClass({
      codexPathOverride: config.codexPath,
      env: codexEnvironment(config),
      config: {
        forced_login_method: 'chatgpt',
        model_provider: 'openai',
        model_instructions_file: instructions,
        project_doc_max_bytes: 0,
        history: { persistence: 'none' },
        features: {
          shell_tool: false, unified_exec: false, shell_snapshot: false,
          multi_agent: false, apps: false, hooks: false, code_mode: false,
          browser_use: false, computer_use: false, image_generation: false,
        },
      },
    });
  } catch (error) {
    await rm(workDir, { recursive: true, force: true });
    throw error;
  }
  return {
    async generate(request, signal) {
      const thread = client.startThread({
        model: request.codexModel,
        modelReasoningEffort: request.effort || config.effort,
        workingDirectory: workDir,
        skipGitRepoCheck: true,
        sandboxMode: 'read-only',
        approvalPolicy: 'never',
        networkAccessEnabled: false,
        webSearchMode: 'disabled',
      });
      const { events } = await thread.runStreamed(buildPrompt(request), { signal, outputSchema: request.outputSchema });
      let finalText;
      let usage;
      let completed = false;
      for await (const event of events) {
        signal.throwIfAborted();
        if (event.type === 'error' || event.type === 'turn.failed') {
          // Upstream errors can contain request text or account information.
          throw new HttpError(502, 'Codex could not complete this request. Check sign-in, model access, and subscription limits with npm run doctor.', 'codex_error');
        }
        if (event.type === 'item.completed' && event.item.type === 'agent_message') {
          finalText = event.item.text;
          if (Buffer.byteLength(finalText) > config.maxOutputBytes) {
            throw new HttpError(502, 'Codex response exceeded the bridge output limit.', 'output_too_large');
          }
        }
        if (event.type === 'turn.completed') {
          usage = event.usage;
          completed = true;
        }
      }
      if (!completed || typeof finalText !== 'string' || finalText.length === 0) {
        throw new HttpError(502, 'Codex ended without a complete answer.', 'incomplete_response');
      }
      if (request.jsonOutput) {
        try {
          const parsed = JSON.parse(finalText);
          if (!request.outputSchema && (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed))) throw new Error();
        } catch {
          throw new HttpError(502, 'Codex returned invalid JSON for the requested response format.', 'invalid_model_output');
        }
      }
      return { text: finalText, usage };
    },
    async close() { await rm(workDir, { recursive: true, force: true }); },
  };
}
