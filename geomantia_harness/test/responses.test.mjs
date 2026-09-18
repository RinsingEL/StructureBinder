import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import sharp from 'sharp';

test('Responses configuration sends native images using the official multi-provider plugin', { timeout: 30000 }, async () => {
  let received, route;
  const server = createServer(async (req, res) => {
    let body = ''; for await (const chunk of req) body += chunk;
    route = req.url; received = JSON.parse(body);
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    const response = { id: 'resp_test', object: 'response', status: 'completed', model: 'vision-model', output: [],
      usage: { input_tokens: 20, output_tokens: 1, total_tokens: 21 } };
    res.write('event: response.created\ndata: ' + JSON.stringify({ type: 'response.created', response: { ...response, status: 'in_progress' } }) + '\n\n');
    const item = { id: 'msg_test', type: 'message', role: 'assistant', status: 'in_progress', content: [] };
    for (const event of [
      { type: 'response.output_item.added', output_index: 0, item },
      { type: 'response.content_part.added', item_id: item.id, output_index: 0, content_index: 0, part: { type: 'output_text', text: '', annotations: [] } },
      { type: 'response.output_text.delta', item_id: item.id, output_index: 0, content_index: 0, delta: 'red' },
      { type: 'response.output_item.done', output_index: 0, item: { ...item, status: 'completed', content: [{ type: 'output_text', text: 'red', annotations: [] }] } },
    ]) res.write(`event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`);
    res.end('event: response.completed\ndata: ' + JSON.stringify({ type: 'response.completed', response }) + '\n\n');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const home = await mkdtemp(join(tmpdir(), 'geomantia-harness-responses-'));
  const png = await sharp({ create: { width: 24, height: 24, channels: 3, background: 'red' } }).png().toBuffer();
  const child = spawn(process.execPath, [process.env.GEOMANTIA_HARNESS_ENTRY || 'src/main.mjs']);
  let errors = '', done; const evidence = [];
  child.stderr.on('data', chunk => errors += chunk);
  createInterface({ input: child.stdout }).on('line', line => {
    const event = JSON.parse(line);
    if (event.type === 'done') done = event;
    if (event.type === 'request_evidence') evidence.push(event);
  });
  try {
    child.stdin.write(JSON.stringify({ type: 'run', home, cwd: home, sessionId: 'responses-test',
      baseUrl: `http://127.0.0.1:${server.address().port}/v1`, model: 'vision-model', apiProtocol: 'responses',
      apiKey: 'test-only', timeoutSeconds: 5, instructions: 'Inspect image.', tools: [],
      content: [{ type: 'input_text', text: 'Describe.' }, { type: 'input_image', image_url: `data:image/png;base64,${png.toString('base64')}` }] }) + '\n');
    await new Promise((resolve, reject) => { child.once('exit', resolve); child.once('error', reject); });
    assert.equal(done?.ok, true, JSON.stringify(done) + errors);
    assert.equal(route, '/v1/responses');
    assert.ok(received.input.some(m => Array.isArray(m.content) && m.content.some(p => p.type === 'input_image')));
    assert.equal(evidence[0].images.length, 1);
  } finally { child.kill(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(home, { recursive: true, force: true }); }
});
