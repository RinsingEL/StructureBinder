import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm, cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import sharp from 'sharp';
import { createRuntime, createUserMessage } from '../src/runtime.mjs';
import * as Geomantia from '../src/geomantia-plugin.mjs';

async function fixture() {
  const requests = [];
  const server = createServer(async (req, res) => {
    if (!req.url.endsWith('/chat/completions')) { res.writeHead(404); res.end(); return; }
    let body = ''; for await (const chunk of req) body += chunk;
    const request = JSON.parse(body); requests.push(request);
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.write('data: ' + JSON.stringify({ choices: [{ index: 0, delta: { content: request.max_tokens === 8192 ? 'Older city decisions preserved. Continue from the latest authoritative city state and native preview.' : 'done' }, finish_reason: null }] }) + '\n\n');
    res.end('data: ' + JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] }) + '\n\ndata: [DONE]\n\n');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const home = await mkdtemp(join(tmpdir(), 'geomantia-context-budget-'));
  process.env.GEOMANTIA_PROVIDER_API_KEY = 'test-only';
  const config = { home, baseUrl: `http://127.0.0.1:${server.address().port}/v1`, model: 'deepseek-v4.1-flash', timeoutSeconds: 10 };
  return { home, requests, config, async close() { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(home, { recursive: true, force: true }); } };
}

test('automatic compaction bounds repeated full-state turns and preserves latest actual image', { timeout: 60000 }, async () => {
  const f = await fixture(); const ctx = await createRuntime(f.config);
  const options = { provider: 'deepseek-official', model: f.config.model, maxTokens: 32768 };
  const failures = [], events = [];
  ctx.on('agent/error', ({ error }) => failures.push(error.message));
  ctx.on('session/event', (_, event) => events.push(event.type));
  const setup = async c => { await c.plugin(Geomantia, { prompt: 'Design from current state.', tools: [], finished: () => false }); };
  let handle = await ctx.agents.create({ sessionId: 'growing-city', meta: { cwd: f.home }, agentOptions: options, setup });
  const png = await sharp({ create: { width: 24, height: 24, channels: 3, background: 'red' } }).png().toBuffer();
  try {
    for (let turn = 0; turn < 18; turn++) {
      const content = await Geomantia.convertContent(ctx.attachments, [
        { type: 'input_text', text: `LATEST_STATE_${turn}:` + '城市设计坐标建筑阵列'.repeat(36000) },
        { type: 'input_image', image_url: `data:image/png;base64,${png.toString('base64')}` },
      ]);
      handle.agent.followup(createUserMessage({ source: { kind: 'user' }, content }));
      await handle.agent.whenIdle();
    }
    assert.deepEqual(failures, []);
    assert.ok(events.includes('compaction/end'), `pressure ${ctx.tokenMeter.measure(handle.agent.session).totalTokens}`);
    assert.ok(f.requests.some(r => r.max_tokens === 8192));
    const final = f.requests.at(-1);
    assert.equal(final.max_tokens, 32768);
    assert.ok(JSON.stringify(final).includes('LATEST_STATE_17:'));
    assert.ok(!JSON.stringify(final).includes('LATEST_STATE_0:'));
    assert.ok(final.messages.some(m => Array.isArray(m.content) && m.content.some(p => p.type === 'image_url')));
    const before = JSON.stringify(handle.agent.session.deriveMessages());
    await handle.dispose();
    handle = await ctx.agents.resume({ resumeSessionId: 'growing-city', agentOptions: options, setup });
    assert.equal(JSON.stringify(handle.agent.session.deriveMessages()), before, 'compacted surface must survive restart');
  } finally { await handle.dispose(); await ctx.fiber.dispose(); await f.close(); }
});

test('existing saturated player session can resume and compact before its next design request', {
  skip: !process.env.GEOMANTIA_HARNESS_SESSION_FIXTURE, timeout: 60000,
}, async () => {
  const f = await fixture();
  await cp(process.env.GEOMANTIA_HARNESS_SESSION_FIXTURE, f.home, { recursive: true });
  const ctx = await createRuntime(f.config); const errors = [];
  ctx.on('agent/error', ({ error }) => errors.push(error.message));
  const handle = await ctx.agents.resume({ resumeSessionId: 'geomantia-design-3c43ee98-fbda-351c-985a-963470318ef5',
    agentOptions: { provider: 'deepseek-official', model: f.config.model, maxTokens: 32768 },
    setup: async c => { await c.plugin(Geomantia, { prompt: 'Continue current city.', tools: [], finished: () => false }); },
  });
  try {
    const original = ctx.tokenMeter.measure(handle.agent.session).totalTokens;
    const messages = handle.agent.session.deriveMessages();
    const latest = messages.filter(m => m.role === 'user').at(-1);
    handle.agent.followup(createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: 'Resume the latest full city state.' }] }));
    await handle.agent.whenIdle();
    assert.deepEqual(errors, []);
    assert.ok(f.requests.some(r => r.max_tokens === 8192));
    assert.equal(f.requests.at(-1).max_tokens, 32768);
    const after = ctx.tokenMeter.measure(handle.agent.session).totalTokens;
    assert.ok(after < original / 2, `${original} -> ${after}`);
    const latestText = latest.content.filter(p => p.type === 'text').map(p => p.text).join('');
    assert.ok(JSON.stringify(handle.agent.session.deriveMessages()).includes(JSON.stringify(latestText).slice(1,-1)));
    assert.ok(!f.requests.at(-1).messages.some(m => Array.isArray(m.content) && m.content.some(p => p.type === 'image_url')));
    console.log(`Saturated fixture pressure: ${original} -> ${after}; latest city text retained; previous image payload retired`);
  } finally { await handle.dispose(); await ctx.fiber.dispose(); await f.close(); }
});
