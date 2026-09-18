import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdtemp, rm, readdir, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

for (const cancel of [false, true]) test(`transient retry, durable tool dispatch, cancellation=${cancel}`, { timeout: 15000 }, async () => {
  const home = await mkdtemp(join(tmpdir(), 'geomantia-resilience-'));
  let requests = 0, done, toolCheckpoint = false, stderr = '';
  const errors = [], activities = [];
  const server = createServer(async (req, res) => {
    for await (const _ of req) { }
    requests++;
    if (requests === 1) { res.writeHead(503, { 'content-type': 'application/json' }); res.end(JSON.stringify({ error: { message: 'Temporary outage', type: 'server_error' } })); return; }
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.end('data: ' + JSON.stringify({ choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: 'c1', type: 'function', function: { name: 'preview', arguments: '{}' } }] }, finish_reason: 'tool_calls' }] }) + '\n\ndata: [DONE]\n\n');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const child = spawn(process.execPath, [process.env.GEOMANTIA_HARNESS_ENTRY || 'src/main.mjs']);
  child.stderr.on('data', chunk => stderr += chunk);
  createInterface({ input: child.stdout }).on('line', line => {
    const event = JSON.parse(line);
    if (event.type === 'done') done = event;
    if (event.type === 'activity') {
      activities.push(event);
      if (cancel && event.kind === 'waiting') child.stdin.write('{"type":"cancel"}\n');
    }
    if (event.type === 'tool') (async () => {
      const files = await readdir(join(home, 'sessions'), { recursive: true });
      const log = await readFile(join(home, 'sessions', files.find(p => p.endsWith('session.v3.jsonl'))), 'utf8');
      toolCheckpoint = log.includes('"type":"tool/call"') && log.includes('"type":"request/header"');
      child.stdin.write(JSON.stringify({ type: 'tool_result', id: event.id, output: { ok: true }, finished: true }) + '\n');
    })().catch(error => { errors.push(error.message); child.kill(); });
  });
  try {
    child.stdin.write(JSON.stringify({ type: 'run', home, cwd: home, sessionId: 'retry-test',
      baseUrl: `http://127.0.0.1:${server.address().port}/v1`, model: 'deepseek-v4.1-flash', timeoutSeconds: 5,
      apiKey: 'test-only', instructions: 'Call preview.', tools: [{ name: 'preview', description: 'Preview', parameters: { type: 'object', properties: {} } }],
      content: [{ type: 'input_text', text: 'Start.' }] }) + '\n');
    await new Promise((resolve, reject) => { child.once('exit', resolve); child.once('error', reject); });
    assert.deepEqual(errors, []);
    assert.ok(activities.some(e => e.kind === 'waiting'), stderr);
    assert.equal(done?.ok, !cancel, JSON.stringify(done) + stderr);
    assert.equal(requests, cancel ? 1 : 2);
    if (cancel) assert.equal(done.error, 'HARNESS_CANCELLED');
    else assert.ok(toolCheckpoint, 'tool effect must be preceded by a durable tool-call checkpoint');
  } finally { child.kill(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(home, { recursive: true, force: true }); }
});
