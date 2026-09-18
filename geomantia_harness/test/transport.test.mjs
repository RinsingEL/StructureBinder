import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import sharp from 'sharp';

for (const model of ['deepseek-v4.1-flash', 'custom-vision'])
test(`real Harness loop forwards images, stops and resumes history (${model})`, { timeout: 60000 }, async () => {
  const png = await sharp({ create: { width: 24, height: 24, channels: 3, background: '#d02070' } }).png().toBuffer();
  const url = `data:image/png;base64,${png.toString('base64')}`;
  const requests = [];
  const server = createServer(async (req, res) => {
    let body = ''; for await (const chunk of req) body += chunk;
    if (!req.url.endsWith('/chat/completions')) { res.writeHead(404); res.end(); return; }
    requests.push(JSON.parse(body));
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    const tool = requests.length === 1;
    res.write('data: ' + JSON.stringify({ id: 'test', choices: [{ index: 0, delta: tool
      ? { role: 'assistant', tool_calls: [{ index: 0, id: 'call_one', type: 'function', function: { name: 'preview', arguments: '{}' } }] }
      : { role: 'assistant', content: 'done' }, finish_reason: null }] }) + '\n\n');
    res.write('data: ' + JSON.stringify({ id: 'test', choices: [{ index: 0, delta: {}, finish_reason: tool ? 'tool_calls' : 'stop' }] }) + '\n\n');
    res.end('data: [DONE]\n\n');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const home = await mkdtemp(join(tmpdir(), 'geomantia-harness-test-'));
  const message = { type: 'run', home, cwd: home, sessionId: 'test-session', baseUrl: `http://127.0.0.1:${server.address().port}/v1`,
    model, timeoutSeconds: 20, apiKey: 'test-only', affinity: 'test-session', instructions: 'Use preview then finish.',
    tools: [{ name: 'preview', description: 'Get preview', parameters: { type: 'object', properties: {} } }],
    content: [{ type: 'input_text', text: 'inspect' }, { type: 'input_image', image_url: url }] };
  async function run(finished = false) {
    const child = spawn(process.execPath, [process.env.GEOMANTIA_HARNESS_ENTRY || 'src/main.mjs'], { stdio: ['pipe', 'pipe', 'pipe'] });
    let errors = '', done;
    child.stderr.on('data', chunk => errors += chunk);
    const lines = createInterface({ input: child.stdout });
    lines.on('line', line => {
      const event = JSON.parse(line);
      if (event.type === 'tool') child.stdin.write(JSON.stringify({ type: 'tool_result', id: event.id,
        output: [{ type: 'input_text', text: 'x'.repeat(180000) }, { type: 'input_image', image_url: url }], finished }) + '\n');
      if (event.type === 'done') done = event;
    });
    child.stdin.write(JSON.stringify(message) + '\n');
    await new Promise((resolve, reject) => { child.once('exit', resolve); child.once('error', reject); });
    assert.equal(done?.ok, true, JSON.stringify(done) + errors);
  }
  try {
    await run();
    assert.equal(requests.length, 2);
    const count = body => body.messages.flatMap(m => Array.isArray(m.content) ? m.content : []).filter(p => p.type === 'image_url').length;
    assert.equal(count(requests[0]), 1);
    assert.equal(count(requests[1]), 1, "identical images are deduplicated without dropping text");
    const sent = requests[0].messages.flatMap(m => Array.isArray(m.content) ? m.content : []).find(p => p.type === 'image_url');
    const stats = await sharp(Buffer.from(sent.image_url.url.split(',')[1], 'base64')).stats();
    assert.ok(Math.abs(stats.channels[0].mean - 208) < 5 && Math.abs(stats.channels[1].mean - 32) < 5,
      'image encoding must preserve actual pixel colors');
    assert.ok(JSON.stringify(requests[1]).includes('x'.repeat(180000)));
    await run();
    assert.equal(requests.length, 3);
    assert.ok(JSON.stringify(requests[2]).includes('done'));
    requests.length = 0;
    message.sessionId = 'host-finished';
    await run(true);
    assert.equal(requests.length, 1, 'host completion must stop before another model request');
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(home, { recursive: true, force: true }); }
});
