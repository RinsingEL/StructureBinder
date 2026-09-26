// Runs the existing Harness against the Flash-only MCP listener. Credentials stay in stdin.
import { readFile, mkdir, appendFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const configDir = resolve(process.argv[2] ?? 'config/geomantia');
const endpoint = process.argv[3] ?? 'http://127.0.0.1:5002/mcp';
const once = process.argv.includes('--once');
const home = resolve(process.env.GEOMANTIA_FLASH_HOME ?? '.flash-harness');
const cfg = JSON.parse(await readFile(join(configDir, 'provider.json'), 'utf8'));
const apiKey = (await readFile(join(configDir, 'provider-secret.txt'), 'utf8')).trim();
if (!apiKey) throw new Error('PROVIDER_API_KEY_MISSING');
await mkdir(home, { recursive: true });
let session = '', rpcId = 0, child, stopped = false;
const trace = async (tool, args, result) => appendFile(join(home, 'mcp-calls.jsonl'), JSON.stringify({ time: new Date().toISOString(), tool, args, result }) + '\n');
async function rpc(method, params = {}) {
  const r = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...(session ? { 'mcp-session-id': session } : {}) }, body: JSON.stringify({ jsonrpc: '2.0', id: ++rpcId, method, params }), signal: AbortSignal.timeout(700000) });
  session = r.headers.get('mcp-session-id') ?? session;
  if (!r.ok) throw new Error(`MCP_HTTP_${r.status}`);
  const body = await r.json(); if (body.error) throw new Error(body.error.message); return body.result;
}
async function call(name, args = {}) {
  const result = await rpc('tools/call', { name, arguments: args });
  await trace(name, args, result);
  if (result.isError) throw new Error(result.content?.filter(p => p.type === 'text').map(p => p.text).join('\n'));
  return result;
}
function data(result) {
  for (const p of result.content ?? []) if (p.type === 'text') { try { return JSON.parse(p.text); } catch {} }
  throw new Error('PLANNING_RESPONSE_MISSING');
}
async function runTask(task, evidence) {
  if (task.requiredRole !== 'FLASH') throw new Error('WRONG_ROLE_ENDPOINT');
  const definitions = (task.tools ?? []).map(t => t.function ?? ({ name: t.name, description: t.description, parameters: t.parameters ?? t.inputSchema }));
  definitions.push({ name: 'planning_escalate', description: 'No suitable candidates or conflicting requirements: save an explanation and hand this task to the advanced agent.', parameters: { type: 'object', properties: { reason: { type: 'string' } }, required: ['reason'], additionalProperties: false } });
  const content = [{ type: 'input_text', text: JSON.stringify(task.state) }, ...evidence.filter(i => i.type === 'image').map(i => ({ type: 'input_image', image_url: `data:${i.mimeType};base64,${i.data}` }))];
  child = spawn(process.execPath, [fileURLToPath(new URL('../dist/main.mjs', import.meta.url))], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
  let done, failure, completed = false;
  const send = value => { if (!child.stdin.destroyed) child.stdin.write(JSON.stringify(value) + '\n'); };
  const reader = createInterface({ input: child.stdout });
  let pending = Promise.resolve();
  reader.on('line', line => { pending = pending.then(async () => {
    const e = JSON.parse(line);
    if (e.type === 'tool') {
      let result;
      try {
        if (!e.arguments || typeof e.arguments !== 'object' || Array.isArray(e.arguments))
          throw new Error('TOOL_ARGUMENT_INVALID: arguments must be a JSON object, not a JSON-encoded string. Use the tool schema fields directly.');
        result = e.name === 'planning_escalate' ? await call('planning_escalate', { taskId: task.taskId, reason: e.arguments.reason }) : await call('planning_action', { taskId: task.taskId, actionId: randomUUID(), tool: e.name, arguments: e.arguments });
      }
      catch (error) { result = { content: [{ type: 'text', text: String(error.message) }], isError: true }; }
      let finished = false; try { finished = data(result).taskFinished === true; } catch {}
      if(e.name === 'planning_escalate' && !result.isError) finished = true;
      completed ||= finished;
      const output = result.content.map(p => p.type === 'image' ? { type: 'input_image', image_url: `data:${p.mimeType};base64,${p.data}` } : { type: 'input_text', text: p.text });
      send({ type: 'tool_result', id: e.id, output, finished });
    } else if (e.type === 'done') { done = e; await appendFile(join(home,'turn-results.jsonl'),JSON.stringify(e).replaceAll(apiKey,'[redacted]')+'\n'); }
    else if (e.type === 'request_evidence') await appendFile(join(home, 'request-evidence.jsonl'), JSON.stringify(e) + '\n');
  }).catch(error => { failure = error; child.kill(); }); });
  // Do not persist provider diagnostics which may contain request metadata.
  let diagnostics = ''; child.stderr.on('data', chunk => { diagnostics = (diagnostics + chunk).slice(-16000); });
  const timer = setInterval(() => { call('planning_wait', { cursor: '', timeoutSeconds: 0 }).catch(error => { failure = error; child.kill(); }); }, 30000);
  const deadline = setTimeout(() => { failure = new Error('FLASH_TASK_TIMEOUT'); child.kill(); }, 1200000);
  send({ ...cfg, type: 'run', home, cwd: home, sessionId: task.taskId, apiKey, affinity: task.taskId, instructions: task.instructions + '\n本客户端已经把宿主 planning_action 包装成当前工具，直接调用题单中的工具名即可。必须提交到宿主确认 taskFinished，或说明原因调用 planning_escalate；仅输出文字不算完成。', tools: definitions, content });
  await new Promise((ok, fail) => { child.once('exit', ok); child.once('error', fail); });
  clearInterval(timer); clearTimeout(deadline); await pending;
  if (failure) throw failure;
  if (!done?.ok) {
    await appendFile(join(home,'runtime-errors.txt'),diagnostics.replaceAll(apiKey,'[redacted]'));
    throw new Error('HARNESS_TASK_FAILED: '+(done?.error ?? diagnostics.slice(-2000)));
  }
  if (!completed) throw new Error('HARNESS_RETURNED_WITHOUT_TASK_COMPLETION');
  console.log(JSON.stringify({ taskId: task.taskId, model: cfg.model, status: 'turn_finished', evidence: home }));
}
process.once('SIGINT', () => { stopped = true; child?.kill(); });
try {
  await rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'geomantia-flash-harness', version: '1' } });
  while (!stopped) {
    const response = await call('planning_resume'); const task = data(response);
    if (task.status === 'complete' || task.status === 'blocked') { console.log(JSON.stringify({ status: task.status, error: task.error })); break; }
    if (task.taskId && !task.taskFinished) { await runTask(task, response.content); if (once) break; }
    else { if (once && task.status === 'waiting_for_role') { console.log(JSON.stringify({ status: task.status, requiredRole: task.requiredRole })); break; } await call('planning_wait', { cursor: task.cursor, timeoutSeconds: 20 }); }
  }
} catch (error) { console.error(String(error.message).replaceAll(apiKey, '[redacted]')); process.exitCode = 1; }
finally {
  await call('planning_release').catch(() => {});
  if (session) await fetch(endpoint, { method: 'DELETE', headers: { 'mcp-session-id': session, Accept: 'application/json, text/event-stream' } }).catch(() => {});
}
