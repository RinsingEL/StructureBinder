import { createInterface } from 'node:readline';
import { createHash } from 'node:crypto';
import { createRuntime, createUserMessage, nativeDeepSeek } from './runtime.mjs';
import * as Geomantia from './geomantia-plugin.mjs';
import { artifactTools } from './artifact-tools.mjs';

const output = value => process.stdout.write(JSON.stringify(value) + '\n');
// Stdout is exclusively the host protocol; upstream diagnostics go to stderr.
console.log = (...args) => console.error(...args);
let running = false, agent, stopped = false, cancelled = false;
const pending = new Map();
let nextCall = 0;
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
input.on('close', () => { agent?.cancel({ kind: 'user' }); process.exit(0); });
input.on('line', line => {
  try {
    const msg = JSON.parse(line);
    if (msg.type === 'tool_result') {
      stopped ||= msg.finished === true;
      pending.get(msg.id)?.resolve(msg.output); pending.delete(msg.id);
    } else if (msg.type === 'cancel') {
      stopped = true; cancelled = true; agent?.cancel({ kind: 'user' });
    } else if (msg.type === 'run' && !running) {
      running = true;
      run(msg).catch(error => output({ type: 'done', ok: false, error: String(error.message) }))
        .finally(() => process.exit(0));
    }
  } catch (error) { output({ type: 'protocol_error', error: error.message }); }
});

async function run(msg) {
  process.env.GEOMANTIA_PROVIDER_API_KEY = msg.apiKey;
  const nativeFetch = globalThis.fetch;
  const endpoint = new URL(msg.baseUrl);
  globalThis.fetch = (url, options = {}) => {
    const target = new URL(typeof url === 'string' || url instanceof URL ? url : url.url);
    if (target.origin === endpoint.origin && target.hostname === 'opencode.ai') {
      const headers = new Headers(options.headers);
      headers.set('x-opencode-session', msg.affinity);
      options = { ...options, headers };
    }
    // Capture metadata, never credentials or the full image payload.
    if (target.origin === endpoint.origin && /\/(chat\/completions|responses)$/.test(target.pathname) && typeof options.body === 'string') {
      const body = JSON.parse(options.body);
      const images = [];
      for (const message of body.messages ?? body.input ?? []) for (const part of Array.isArray(message.content) ? message.content : []) {
        if (part.type === 'image_url' || part.type === 'input_image') images.push({ role: message.role, sha256: createHash('sha256').update(typeof part.image_url === 'string' ? part.image_url : part.image_url.url).digest('hex') });
      }
      output({ type: 'request_evidence', images, model: body.model, maxOutputTokens: body.max_tokens ?? body.max_output_tokens });
    }
    return nativeFetch(url, options);
  };
  const ctx = await createRuntime(msg);
  ctx.on('session/event', (_, event) => {
    if (event.type === 'compaction/start') output({ type: 'activity', kind: 'system', text: '上下文接近预算，正在压缩较早历史；保留近期设计与图片。' });
    if (event.type === 'compaction/end') output({ type: 'activity', kind: 'system', text: '历史压缩处理结束，继续检查上下文预算。' });
    if (event.type === 'llm/retry') output({ type: 'activity', kind: 'waiting', text: '模型请求暂时失败，正在按有限重试策略恢复当前步骤。' });
  });
  let calls = 0, finalText = '', failure = '';
  ctx.on('agent/error', ({ error }) => { failure = error.message ?? String(error); });
  ctx.on('agent/assistant-stream', ({ frame }) => {
    if (frame.type !== 'chunk') return;
    const chunk = frame.chunk;
    if (chunk.type === 'text-delta' || chunk.type === 'reasoning-delta') {
      output({ type: 'activity', kind: chunk.type === 'reasoning-delta' ? 'reasoning' : 'model_delta', text: chunk.text });
      if (chunk.type === 'text-delta') finalText += chunk.text;
    }
    if (chunk.type === 'finish') failure = chunk.reason.failure?.message ?? '';
  });
  const setup = async agentCtx => {
    const localTools = msg.artifactRoot ? await artifactTools(msg.artifactRoot) : [];
    await agentCtx.plugin(Geomantia, {
      prompt: msg.instructions + (msg.artifactRoot ? `\nRead-only artifact root: ${msg.artifactRoot}. Browse/search/read images as needed using artifact_* tools. Essential previews still arrive with planning results.` : ''),
      tools: [...msg.tools, ...localTools], finished: () => stopped,
      async execute(name, args, signal) {
        const local = localTools.find(tool => tool.name === name);
        if (local) {
          output({ type: 'activity', kind: 'tool', text: `读取规划资料 ${name}: ${args.path ?? args.query ?? '.'}` });
          return local.execute(args);
        }
        const id = String(++nextCall); calls++;
        return new Promise((resolve, reject) => {
          const abort = () => { pending.delete(id); reject(new Error('HARNESS_CANCELLED')); };
          if (signal.aborted) return abort();
          signal.addEventListener('abort', abort, { once: true });
          pending.set(id, { resolve: value => { signal.removeEventListener('abort', abort); resolve(value); } });
          output({ type: 'tool', id, name, arguments: args });
        });
      },
    });
  };
  const options = { provider: nativeDeepSeek(msg) ? 'deepseek-official' : 'geomantia', model: msg.model, maxTokens: 32768 };
  const exists = await ctx.sessionPersistence.stat(msg.sessionId);
  const handle = exists
    ? await ctx.agents.resume({ resumeSessionId: msg.sessionId, agentOptions: options, setup })
    : await ctx.agents.create({ sessionId: msg.sessionId, meta: { cwd: msg.cwd }, agentOptions: options, setup });
  agent = handle.agent;
  try {
    const content = await Geomantia.convertContent(ctx.attachments, msg.content);
    agent.followup(createUserMessage({ source: { kind: 'user' }, content }));
    await agent.whenIdle();
  } finally {
    await handle.dispose(); await ctx.fiber.dispose();
  }
  output({ type: 'done', ok: !failure && !cancelled, error: cancelled ? 'HARNESS_CANCELLED' : failure, calls, text: finalText });
}
