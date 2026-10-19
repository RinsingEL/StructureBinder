// Focused evidence for interior revisions: all exterior views, floors, rooms and markers.
import {chromium} from 'playwright';
import {mkdir, writeFile} from 'node:fs/promises';
import path from 'node:path';

const ids = process.argv.slice(2);
if (!ids.length) throw new Error('Pass authored asset IDs');
const base = process.env.STUDIO_URL ?? 'http://127.0.0.1:8765';
const browser = await chromium.launch({headless: true, executablePath: process.env.STUDIO_CHROMIUM,
  args: ['--enable-webgl', '--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
try {
  for (const id of ids) {
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}, deviceScaleFactor: 1});
    const errors = [];
    page.on('pageerror', e => errors.push(String(e)));
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    const url = new URL(base); url.searchParams.set('asset', id);
    await page.goto(url.toString());
    await page.waitForFunction(() => window.studio?.telemetry.ready || window.studio?.telemetry.renderErrors.length,
      undefined, {timeout: 60000});
    const telemetry = await page.evaluate(() => window.studio.telemetry);
    if (!telemetry.ready || telemetry.renderErrors.length || telemetry.missingTextures.length || errors.length)
      throw new Error(JSON.stringify({id, telemetry, errors}));
    const out = path.resolve('runtime/captures', id); await mkdir(out, {recursive: true});
    const shots = [];
    async function shot(name, fn, arg) {
      await page.evaluate(fn, arg);
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      await page.locator('#viewport').screenshot({path: path.join(out, name + '.png')});
      shots.push(name);
    }
    for (const view of ['front', 'back', 'left', 'right', 'top'])
      await shot(view, v => window.studio.preset(v), view);
    await shot('roof-off', () => { window.studio.preset('front'); window.studio.hideRoof(); });
    const author = await page.evaluate(() => window.studio.model.author);
    for (let i = 0; i < author.floors.length; i++)
      await shot(`floor-${i + 1}`, n => { window.studio.floor(n); window.studio.preset('top'); }, i);
    for (const room of author.rooms)
      await shot('room-' + room.id, r => window.studio.focusRoom(r), room);
    const points = author.points.filter(p => p.approach && ['work', 'seat', 'bed'].includes(p.kind));
    for (const kind of ['work', 'seat', 'bed']) {
      const point = points.find(p => p.kind === kind);
      if (point) await shot('use-' + kind, p => window.studio.enterWalk(p), point);
    }
    await shot('annotations', () => { window.studio.reset(); window.studio.setMarkers(true); });
    const final = await page.evaluate(() => window.studio.telemetry);
    if (errors.length || final.renderErrors.length || final.missingTextures.length)
      throw new Error(JSON.stringify({id, final, errors}));
    await writeFile(path.join(out, 'capture.json'), JSON.stringify({schema: 'structure-studio.capture.v1', id,
      nbt_sha256: telemetry.sha256, author_sha256: telemetry.authorSha256, shots,
      render_errors: errors, missing_textures: final.missingTextures, visual_review: 'pending'}, null, 2) + '\n');
    console.log(JSON.stringify({id, shots: shots.length}));
    await page.close();
  }
} finally { await browser.close(); }
