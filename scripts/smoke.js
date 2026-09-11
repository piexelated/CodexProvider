import assert from 'node:assert/strict';
import { loadConfig, prepareConfig } from '../src/config.js';

const config = await prepareConfig(loadConfig());
const baseUrl = `http://${config.host}:${config.port}/v1`;
const headers = { Authorization: `Bearer ${config.token}`, 'Content-Type': 'application/json' };

async function request(path, body) {
  const response = await fetch(baseUrl + path, {
    method: body ? 'POST' : 'GET', headers,
    ...(body ? { body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(config.timeoutMs + 5000),
  });
  if (!response.ok) throw new Error(`${response.status}: ${await response.text()}`);
  return response;
}

try {
  console.log('This makes three small Codex requests using your subscription. Keep npm start running.');
  const models = await (await request('/models')).json();
  assert.ok(models.data.some((model) => model.id === 'codex-bridge'));
  console.log('Model discovery: OK');
  const names = await (await request('/chat/completions', {
    model: 'codex-bridge-fast',
    messages: [
      { role: 'system', content: 'Suggest exactly three Java variable names for the supplied declaration. Return JSON only.' },
      { role: 'user', content: 'int n = regions.size();' },
    ],
    response_format: { type: 'json_schema', json_schema: {
      name: 'names', strict: true,
      schema: { type: 'object', properties: { names: { type: 'array', items: { type: 'string' }, minItems: 3, maxItems: 3 } }, required: ['names'], additionalProperties: false },
    } },
  })).json();
  const parsed = JSON.parse(names.choices[0].message.content);
  assert.equal(parsed.names.length, 3);
  assert.ok(parsed.names.every((name) => /^[A-Za-z_$][\w$]*$/.test(name)));
  console.log('Structured name suggestions: OK');

  const edit = await request('/chat/completions', {
    model: 'codex-bridge', stream: true,
    messages: [
      { role: 'system', content: 'Return only replacement Java code. No Markdown fences or commentary.' },
      { role: 'user', content: 'Change subtraction to addition in: int sum(int a, int b) { return a - b; }' },
    ],
  });
  const sse = await edit.text();
  let output = '';
  for (const line of sse.split('\n')) {
    if (!line.startsWith('data: ') || line === 'data: [DONE]') continue;
    const event = JSON.parse(line.slice(6));
    if (event.error) throw new Error(event.error.message);
    output += event.choices?.[0]?.delta?.content || '';
  }
  assert.match(sse, /data: \[DONE\]/);
  assert.match(output, /return\s+a\s*\+\s*b\s*;/);
  assert.doesNotMatch(output, /```/);
  console.log('Streamed inline replacement: OK');

  const history = await (await request('/chat/completions', {
    model: 'codex-bridge',
    messages: [
      { role: 'system', content: 'Answer with a single integer only.' },
      { role: 'user', content: 'Remember the number 17.' },
      { role: 'assistant', content: '17' },
      { role: 'user', content: 'Add 5 to the number I gave you.' },
    ],
  })).json();
  assert.equal(history.choices[0].message.content.trim(), '22');
  console.log('Follow-up context: OK');
  console.log('Bridge inference checks passed. Verify the actual editor actions in JetBrains next.');
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
