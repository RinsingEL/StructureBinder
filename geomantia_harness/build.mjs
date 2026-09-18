import { build } from 'esbuild';
import { mkdir, cp, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
process.chdir(dirname(fileURLToPath(import.meta.url)));
const require = createRequire(import.meta.url);
await mkdir('dist', { recursive: true });
await build({ absWorkingDir: process.cwd(), entryPoints: ['src/main.mjs'], outfile: 'dist/main.mjs', bundle: true, platform: 'node', format: 'esm',
  target: 'node22', external: ['sharp', 'koffi', '@deepseek-ai/node-addon-system/*'],
  plugins: [{ name: 'bundle-package-version', setup(builder) {
    builder.onLoad({ filter: /dsh-llm[\\/]lib[\\/]index\.js$/ }, async ({ path }) => {
      const source = await readFile(path, 'utf8');
      const pkg = JSON.parse(await readFile(join(dirname(path), '../package.json'), 'utf8'));
      // The upstream version lookup uses createRequire, which esbuild cannot relocate automatically.
      const lookup = 'createRequire(import.meta.url)("../package.json")';
      if (!source.includes(lookup)) throw new Error('Upstream version lookup changed; review bundle packaging');
      return { contents: source.replace(lookup, JSON.stringify({ version: pkg.version })), loader: 'js' };
    });
  } }],
  banner: { js: "import {createRequire as __createRequire} from 'node:module';const require=__createRequire(import.meta.url);" },
  metafile: true }).then(result => writeFile('dist/metafile.json', JSON.stringify(result.metafile)));
const copied = new Set();
async function copyPackage(name) {
  if (copied.has(name)) return;
  let file;
  try { file = require.resolve(`${name}/package.json`); }
  catch {
    // sharp hides package.json through exports; it still must ship with its native dependencies.
    const candidate = join(process.cwd(), 'node_modules', name, 'package.json');
    try { await readFile(candidate); file = candidate; } catch { return; }
  }
  copied.add(name);
  await cp(dirname(file), join('dist/node_modules', name), { recursive: true });
  const pkg = JSON.parse(await readFile(file, 'utf8'));
  for (const dep of Object.keys({ ...pkg.dependencies, ...pkg.optionalDependencies })) await copyPackage(dep);
}
await copyPackage('sharp');
await copyPackage('koffi');
await copyPackage('@deepseek-ai/node-addon-system');
// Preserve license notices for every package whose code is included by the bundler.
const lock = JSON.parse(await readFile('package-lock.json', 'utf8'));
const notices = [];
for (const path of Object.keys(lock.packages).filter(p => p.startsWith('node_modules/'))) {
  for (const filename of ['LICENSE', 'LICENSE.md', 'LICENSE.txt']) {
    try { notices.push(`\n===== ${path} =====\n` + await readFile(join(path, filename), 'utf8')); break; } catch { }
  }
}
await writeFile('dist/THIRD_PARTY_NOTICES.txt', notices.join('\n'));
const inputs = {};
for (const file of ['build.mjs', 'package.json', 'package-lock.json', 'src/runtime.mjs', 'src/main.mjs', 'src/geomantia-plugin.mjs', 'src/artifact-tools.mjs', 'src/image-history.mjs'])
  inputs[file] = createHash('sha256').update(await readFile(file)).digest('hex');
await writeFile('dist/build-inputs.json', JSON.stringify(inputs, null, 2));
