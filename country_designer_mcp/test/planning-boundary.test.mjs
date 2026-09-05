import assert from 'node:assert/strict';
import test from 'node:test';
import { realmTools } from '../dist/src/realm/tools.js';
import { planningResult } from '../dist/src/shared/types.js';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

test('T1 publishes exact nested design choices without draft fallbacks', () => {
  const schema = realmTools.find(t => t.name === 'realm_t1_prepare').inputSchema;
  assert.ok(schema.required.includes('realmProfiles'));
  assert.equal(schema.properties.allowAiDraftProfile, undefined);
  const profile = schema.properties.realmProfiles.items;
  for (const key of ['theme', 'cultureTags', 'targetContinentId', 'scalePlan', 'expansionStyle']) assert.ok(profile.required.includes(key));
  assert.equal(profile.additionalProperties, false);
  assert.equal(profile.properties.scalePlan.additionalProperties, false);
});

test('real image contents are separate from compact decision text', () => {
  const input = { ok: true, contextId: 'ctx', imageEvidence: [{type:'image',mimeType:'image/png',data:'AQID'}] };
  const result = planningResult(input);
  assert.equal(result.content.length, 2);
  assert.equal(result.content[1].data, 'AQID');
  assert.deepEqual(JSON.parse(result.content[0].text), {ok:true,contextId:'ctx'});
  assert.ok(input.imageEvidence);
  assert.equal(planningResult({ok:false,reasonCode:'INVALID'}).isError, true);
});

test('real MCP stdio routes sidecar calls through the host bridge and preserves images', { timeout: 15000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), 'geomantia-bridge-test-'));
  let received;
  const bridge = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    received = { key: request.headers['x-geomantia-bridge-key'], ...JSON.parse(Buffer.concat(chunks).toString()) };
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ content: [
      { type: 'text', text: '{"ok":true,"contextId":"host-owned"}' },
      { type: 'image', mimeType: 'image/png', data: 'AQID' },
    ], isError: false }));
  });
  bridge.listen(0, '127.0.0.1');
  await once(bridge, 'listening');
  const client = new Client({ name: 'geomantia-offline-smoke', version: '1.0.0' });
  try {
    await client.connect(new StdioClientTransport({ command: process.execPath,
      args: [fileURLToPath(new URL('../dist/src/index.js', import.meta.url))], cwd: directory,
      env: { ...process.env, GEOMANTIA_PROVIDER_TOOL_URL: `http://127.0.0.1:${bridge.address().port}/execute`,
        GEOMANTIA_PROVIDER_TOOL_KEY: 'offline-test-capability', GEOMANTIA_MC_API_URL: 'http://127.0.0.1:1' },
    }));
    const result = await client.callTool({ name: 'city_prepare_d4_blueprint_context', arguments: { runId: 'run', citySeedId: 'city' } });
    assert.deepEqual(received, { key: 'offline-test-capability', name: 'city_prepare_d4_blueprint_context', arguments: { runId: 'run', citySeedId: 'city' } });
    assert.equal(JSON.parse(result.content[0].text).contextId, 'host-owned');
    assert.deepEqual(result.content[1], { type: 'image', mimeType: 'image/png', data: 'AQID' });
  } finally {
    await client.close();
    await new Promise(resolve => bridge.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
});
