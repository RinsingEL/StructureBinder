export const name = 'geomantia';
export const inject = ['tools', 'systemPrompt', 'attachments'];
import { pruneImageHistory } from './image-history.mjs';

// A business plugin: the official Harness loop owns model calls and history;
// the Minecraft host remains authoritative for tool scope and committed state.
export async function apply(ctx, config) {
  ctx.systemPrompt.section({ name: 'geomantia:planning', order: 0, text: () => config.prompt, complete: true });
  let steps = 0;
  ctx.on('agent/pre-step', async ({agent, messages}, next) => {
    if (config.finished()) return { kind: 'reject' };
    if (++steps > 24) throw new Error('HARNESS_TURN_LIMIT');
    pruneImageHistory(agent.session, messages.some(message => message.source?.kind === 'user'));
    return next();
  });
  for (const tool of config.tools) {
    ctx.tools.register({
      name: tool.name, description: tool.description, parameters: tool.parameters,
      isConcurrencySafe: () => false,
      output: { schema: {}, render: (_, value) => value.content },
      async execute(args, execution) {
        const result = await config.execute(tool.name, args, execution.signal);
        const content = await convertContent(ctx.attachments, result);
        return { content };
      },
    });
  }
}

export async function convertContent(attachments, value) {
  const parts = Array.isArray(value) ? value : [{ type: 'input_text', text: typeof value === 'string' ? value : JSON.stringify(value) }];
  return attachments.admitPromptContent(parts.map(part => {
    if (part.type === 'input_image' || part.type === 'image_url') {
      const url = typeof part.image_url === 'string' ? part.image_url : part.image_url?.url;
      const match = /^data:(image\/(?:png|jpeg|webp|gif));base64,(.+)$/s.exec(url ?? '');
      if (!match) throw new Error('HARNESS_IMAGE_INVALID');
      return { type: 'image', mediaType: match[1], data: match[2] };
    }
    return { type: 'text', text: part.text ?? '' };
  }));
}
