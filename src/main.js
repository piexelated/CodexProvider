import { loadConfig, prepareConfig } from './config.js';
import { createBackend, checkAuth, runAuth } from './codex.js';
import { createServer } from './server.js';

async function main() {
  const command = process.argv[2] || 'serve';
  if (!['serve', 'login', 'doctor', 'token'].includes(command)) {
    throw new Error('Usage: npm start | npm run login | npm run doctor | npm run token');
  }
  const config = await prepareConfig(loadConfig());
  if (command === 'token') {
    console.log(config.token);
    return;
  }
  if (command === 'login') {
    console.log(`Sign in with ChatGPT. Codex home: ${config.codexHome}`);
    const result = await runAuth(config, [], { inherit: true });
    process.exitCode = result.code === 0 ? 0 : 1;
    return;
  }
  console.log(`Codex home: ${config.codexHome}`);
  await checkAuth(config);
  if (command === 'doctor') {
    console.log('ChatGPT authentication: OK (no inference request made).');
    console.log(`Models: ${JSON.stringify(config.models, (_key, value) => value ?? '(Codex default)')}`);
    console.log(`Provider URL: http://${config.host}:${config.port}/v1`);
    console.log(`Local token: ${config.tokenFile} (or BRIDGE_TOKEN if configured)`);
    console.log('Run npm run smoke with the bridge running to check inference and model access.');
    return;
  }
  const backend = await createBackend(config);
  const bridge = createServer(config, backend);
  try { await bridge.listen(); }
  catch (error) { await backend.close(); throw error; }
  console.log(`Provider URL: http://${config.host}:${config.port}/v1`);
  console.log('Run npm run token for the local API Key. Assign codex-bridge to Core features and codex-bridge-fast to Instant helpers.');
  console.log('Disable Tool calling. Automatic completion and next-edit suggestions are not supported.');
  let stopping = false;
  const stop = async () => {
    if (stopping) return;
    stopping = true;
    await bridge.close();
    await backend.close();
  };
  process.once('SIGINT', () => { void stop(); });
  process.once('SIGTERM', () => { void stop(); });
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
