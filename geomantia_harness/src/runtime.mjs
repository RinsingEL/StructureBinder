import { Context } from '@deepseek-ai/cordis';
import Session from '@deepseek-ai/dsh-session';
import Projections from '@deepseek-ai/dsh-session-projection';
import Agents from '@deepseek-ai/dsh-agent';
import AgentLoop from '@deepseek-ai/dsh-agent-loop';
import Llm, { createUserMessage } from '@deepseek-ai/dsh-llm';
import * as DeepSeek from '@deepseek-ai/dsh-llm-deepseek';
import Tools from '@deepseek-ai/dsh-tools';
import SystemPrompt from '@deepseek-ai/dsh-system-prompt';
import Persistence from '@deepseek-ai/dsh-session-persistence-jsonl';
import Attachments from '@deepseek-ai/dsh-attachment-local';
import TokenMeter from '@deepseek-ai/dsh-token-meter';
import Compaction from '@deepseek-ai/dsh-compaction-basic';
import * as Retry from '@deepseek-ai/dsh-llm-retry';
import * as Checkpoints from '@deepseek-ai/dsh-session-checkpoint-policy';
import { join } from 'node:path';

export function nativeDeepSeek(config) {
  return config.model.toLowerCase().startsWith('deepseek')
    && (config.apiProtocol !== 'responses' || new URL(config.baseUrl).hostname === 'opencode.ai');
}

export async function createRuntime(config) {
  const ctx = new Context();
  await ctx.plugin(Session);
  await ctx.plugin(Projections);
  await ctx.plugin(Agents);
  await ctx.plugin(Llm);
  await ctx.plugin(SystemPrompt, { includeHarnessIdentity: false, includeRuntimeContext: false });
  await ctx.plugin(Tools, { mode: 'native' });
  await ctx.plugin(Attachments, { dshHome: config.home });
  await ctx.plugin(Persistence, { root: join(config.home, 'sessions'), compression: 'none' });
  if (!nativeDeepSeek(config)) {
    const Pi = await import('@deepseek-ai/dsh-llm-pi-ai');
    await ctx.plugin(Pi, { providers: { geomantia: {
      apiKeyEnv: 'GEOMANTIA_PROVIDER_API_KEY', baseURL: config.baseUrl,
      api: config.apiProtocol === 'responses' ? 'openai-responses' : 'openai-completions',
      models: [{ id: config.model, input: ['text', 'image'] }],
      retryPolicy: { mode: 'normal', maxRetries: 2 },
      streamIdleTimeoutMs: config.timeoutSeconds * 1000,
    } } });
  } else await ctx.plugin(DeepSeek, {
    baseURL: config.baseUrl, apiKeyEnv: 'GEOMANTIA_PROVIDER_API_KEY',
    maxTokens: 32768,
    retryPolicy: { mode: 'normal', maxRetries: 2 },
    models: [{ id: config.model, inputModalities: ['text', 'image'], contextWindow: 1000000 }],
    streamIdleTimeoutMs: config.timeoutSeconds * 1000,
  });
  await ctx.plugin(TokenMeter);
  await ctx.plugin(Compaction, { thresholdRatio: 0.6, retainRatio: 0.16, maxTokens: 8192 });
  await ctx.plugin(Retry);
  await ctx.plugin(Checkpoints);
  await ctx.plugin(AgentLoop, { maxParallelToolCalls: 1, agents: [] });
  return ctx;
}

export { createUserMessage };
