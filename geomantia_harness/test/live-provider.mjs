// Explicit opt-in: two small requests to the configured provider. Never loads a game world.
import { readFile, mkdtemp, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { tmpdir } from 'node:os';
import { randomInt } from 'node:crypto';
import sharp from 'sharp';
import assert from 'node:assert/strict';
const root = resolve(process.argv[2]);
const config = JSON.parse(await readFile(join(root, 'provider.json'), 'utf8'));
const apiKey = (await readFile(join(root, 'provider-secret.txt'), 'utf8')).trim();
const home = await mkdtemp(join(tmpdir(), 'geomantia-live-harness-'));
const colors = ['red', 'blue', 'green', 'yellow'];
const initial = colors[randomInt(colors.length)], second = colors.filter(c => c !== initial)[randomInt(3)];
async function image(color) {
  const buffer = await sharp({ create: { width: 256, height: 256, channels: 3, background: color } }).png().toBuffer();
  return { type: 'input_image', image_url: `data:image/png;base64,${buffer.toString('base64')}` };
}
const next = await image(second), evidence = [];
const child = spawn(process.execPath, ['dist/main.mjs']);
let done, toolColor, stderr = '';
child.stderr.on('data', chunk => stderr += chunk);
createInterface({ input: child.stdout }).on('line', line => {
  const event = JSON.parse(line);
  if (event.type === 'request_evidence') evidence.push(event);
  if (event.type === 'tool') {
    toolColor = event.arguments.initialColor;
    child.stdin.write(JSON.stringify({ type: 'tool_result', id: event.id, finished: false,
      output: [{ type: 'input_text', text: 'Inspect this second image, then answer.' }, next] }) + '\n');
  }
  if (event.type === 'done') done = event;
});
child.stdin.write(JSON.stringify({ ...config, type: 'run', home, cwd: home, sessionId: 'live-native-images',
  apiKey, affinity: 'geomantia-harness-native-image-smoke',
  instructions: 'Read the dominant color from the initial image. Call preview exactly once with initialColor. Then read the second image returned by preview and output ONLY JSON {"initialColor":"...","toolColor":"..."}, using lowercase English color names. Do not guess from text; inspect both images.',
  tools: [{ name: 'preview', description: 'Returns a second image to inspect.', parameters: { type: 'object', properties: { initialColor: { type: 'string', enum: colors } }, required: ['initialColor'] } }],
  content: [{ type: 'input_text', text: 'Start the visual check.' }, await image(initial)] }) + '\n');
const timeout = setTimeout(() => child.kill(), 180000);
await new Promise((resolve, reject) => { child.once('exit', resolve); child.once('error', reject); });
clearTimeout(timeout);
assert.equal(done?.ok, true, JSON.stringify(done) + stderr.replaceAll(apiKey, '[redacted]'));
const match = done.text.match(/\{[^{}]*"toolColor"[^{}]*\}/s);
const result = JSON.parse(match?.[0] ?? '{}');
const passed = toolColor?.toLowerCase() === initial && result.initialColor?.toLowerCase() === initial && result.toolColor?.toLowerCase() === second
  && evidence.length >= 2 && evidence[0].images.length >= 1 && evidence[1].images.length >= 2;
const report = { passed, provider: config.baseUrl, model: config.model, expected: { initial, second }, result,
  requests: evidence.map(e => ({ imageCount: e.images.length, hashes: e.images.map(i => i.sha256) })) };
await writeFile(join(home, 'smoke-result.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify({ ...report, evidenceDirectory: home }, null, 2));
assert.ok(passed, 'Live image recognition failed; retained evidence above');
