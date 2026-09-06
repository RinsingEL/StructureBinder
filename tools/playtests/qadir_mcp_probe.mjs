// Recorded calls to the real MCP server. Usage: node ... '<JSON calls>' <log-basename>
import { Client } from '../../country_designer_mcp/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '../../country_designer_mcp/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const calls = JSON.parse(process.argv[2] ?? '[]');
const basename = process.argv[3] ?? `qadir-${Date.now()}`;
if (!/^[a-zA-Z0-9_-]+$/.test(basename)) throw new Error('Invalid evidence basename');
const root = resolve(import.meta.dirname, '../..');
const client = new Client({ name: 'qadir-real-playtest', version: '1.0' });
const transport = new StdioClientTransport({ command: process.execPath,
  args: [resolve(root, 'country_designer_mcp/dist/index.js')], cwd: root, stderr: 'pipe',
  env: Object.fromEntries(['GEOMANTIA_MC_API_URL', 'MC_API_URL']
    .filter(key => process.env[key] !== undefined).map(key => [key, process.env[key]])) });
const records = [];
try {
  await client.connect(transport);
  if (!calls.length) {
    const result = await client.listTools();
    console.log(JSON.stringify(result.tools.filter(t => /debug_command|realm_status|execute_d7/.test(t.name))));
    records.push({ tool: 'tools/list', result });
  }
  for (const call of calls) {
    const start = new Date().toISOString();
    const result = await client.callTool(call, undefined, { timeout: 600000 });
    records.push({ start, end: new Date().toISOString(), ...call, result });
    console.log(JSON.stringify({ tool: call.name, result }));
  }
} finally {
  await mkdir(resolve(root, 'run/codex_logs'), { recursive: true });
  await writeFile(resolve(root, `run/codex_logs/${basename}.json`), JSON.stringify(records, null, 2));
  await client.close();
}
