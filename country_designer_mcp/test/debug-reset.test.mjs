import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

test('D4 reset is dynamically config-gated, advanced-only, and cannot be enabled by tool arguments', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'geomantia-debug-'));
  const config = join(dir, 'mcp_server.json');
  const calls = [];
  const game = createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    calls.push({ path: req.url, args: JSON.parse(Buffer.concat(chunks)), role: req.headers['x-geomantia-planning-role'], token: req.headers['x-geomantia-planning-token'] });
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify(req.url === '/planning/lobby' ? {worldName:'测试',canResetD4:true,d4ResetVersion:'version'}
      : { ok: true, leaseToken: 'reset-lease', nextCall: 'planning_next' }));
  });
  game.listen(0, '127.0.0.1'); await once(game, 'listening');
  process.env.GEOMANTIA_MC_API_URL = `http://127.0.0.1:${game.address().port}`;
  process.env.GEOMANTIA_MCP_CONFIG = config;
  const { startMcpHttpServer } = await import('../dist/src/http-server.js');
  const services = [await startMcpHttpServer(0, 'ADVANCED'), await startMcpHttpServer(0, 'FLASH')];
  const clients = services.map((s,i) => new Client({name:`debug-${i}`,version:'1'}));
  const transports = services.map(s => new StreamableHTTPClientTransport(new URL(s.url)));
  const hasReset = async c => (await c.listTools()).tools.some(t => t.name === 'planning_reset_d4');
  const args = {runId:'run',citySeedId:'city',resetVersion:'version',reason:'从零重做'};
  try {
    await Promise.all(clients.map((c,i) => c.connect(transports[i])));
    assert.equal(await hasReset(clients[0]), false);
    let result = await clients[0].callTool({name:'planning_reset_d4',arguments:{...args,debug:true}});
    assert.equal(result.isError, true); assert.equal(calls.length, 0);
    await writeFile(config, JSON.stringify({debug:'true'})); assert.equal(await hasReset(clients[0]), false);
    await writeFile(config, JSON.stringify({debug:true}));
    assert.equal(await hasReset(clients[0]), true); assert.equal(await hasReset(clients[1]), false);
    result = await clients[1].callTool({name:'planning_reset_d4',arguments:args});
    assert.equal(result.isError, true); assert.equal(calls.length, 0);
    const schema = (await clients[0].listTools()).tools.find(t => t.name === 'planning_reset_d4').inputSchema;
    assert.equal(schema.additionalProperties, false); assert.equal('debug' in schema.properties, false);
    assert.match(JSON.stringify(await clients[0].callTool({name:'geomantia_lobby',arguments:{}})), /从零重做 D4/);
    assert.doesNotMatch(JSON.stringify(await clients[1].callTool({name:'geomantia_lobby',arguments:{}})), /从零重做 D4|d4ResetVersion/);
    result = await clients[0].callTool({name:'planning_reset_d4',arguments:args});
    assert.equal(result.isError, false);
    assert.equal(calls.at(-1).path, '/planning/reset_d4'); assert.equal(calls.at(-1).role, 'ADVANCED');
    assert.equal(calls.at(-1).args.resetVersion, 'version');
    assert.ok(calls.at(-1).args.ownerId); assert.doesNotMatch(JSON.stringify(result), /reset-lease/);
    await clients[0].callTool({name:'planning_next',arguments:{timeoutSeconds:0}});
    assert.equal(calls.at(-1).token, 'reset-lease');
    const count = calls.length;
    await writeFile(config, JSON.stringify({debug:false})); assert.equal(await hasReset(clients[0]), false);
    result = await clients[0].callTool({name:'planning_reset_d4',arguments:args});
    assert.equal(result.isError, true); assert.equal(calls.length, count);
    await writeFile(config, '{invalid'); assert.equal(await hasReset(clients[0]), false);
  } finally {
    await Promise.allSettled(clients.map(c => c.close()));
    await Promise.allSettled(services.map(s => s.close()));
    await new Promise(resolve => game.close(resolve));
    delete process.env.GEOMANTIA_MCP_CONFIG;
    await rm(dir, {recursive:true,force:true});
  }
});
