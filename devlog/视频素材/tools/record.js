// 开放日视频录制：一帧一帧截图，直接送进 ffmpeg 合成，分几路同时录，最后拼起来。
//
//   node record.js <页面.html> [输出.mp4] [选项]     （不写输出文件名就存到 tools/输出/）
//
//   --parts 6        同时录几路（默认按 CPU 核数估）
//   --from 120       从第几秒开始录（默认 0）
//   --to 180         录到第几秒（默认到结尾）
//   --fps 24         帧率
//   --crf 24         画质，越小越清晰、文件越大（第二段用过 28）
//   --preview        草稿：半分辨率、12 帧、画质低一点，快很多
//
// 第二段（页面里有 window.beatAt）会自动另开一个页面播第二幕 PPT，按拍定格后贴到白板上。
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { pathToFileURL } = require('url');

const FFMPEG = process.env.FFMPEG || 'ffmpeg';

function parseArgs(argv) {
  const opt = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const k = a.slice(2), v = argv[i + 1];
      if (v === undefined || v.startsWith('--')) opt[k] = true; else { opt[k] = v; i++; }
    } else opt._.push(a);
  }
  return opt;
}

async function openScene(browser, page, preview) {
  const scale = preview ? 0.5 : 1;
  const ctx = await browser.newContext({ viewport: { width: 1920 * scale, height: 1080 * scale } });
  const p = await ctx.newPage();
  await p.goto(pathToFileURL(page).href + '?static');
  if (preview) await p.addStyleTag({ content: 'body { zoom: 0.5; }' });
  await p.evaluate(() => document.fonts.ready);
  await p.waitForTimeout(3000);
  return p;
}

// 第二段：第二幕 PPT 页面，按"第几拍、播到几成"定格
async function openAtlas(browser, page) {
  const atlasPath = path.resolve(path.dirname(page), '../../act2/wtd-atlas.html');
  const atlas = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await atlas.goto(pathToFileURL(atlasPath).href);
  await atlas.waitForTimeout(3000);
  await atlas.evaluate(() => {
    window.__seek = (i, pr) => {
      cancelAnimationFrame(frame); frame = 0;
      const prevT = i > 0 ? beats[i - 1][4] : base;
      index = i; scene = legacyScene(beats[i][0]); displayMeta();
      const target = beats[i][4], t = ease(Math.max(0, Math.min(1, pr)));
      current = {}; for (const k in target) current[k] = mix(prevT[k] ?? target[k], target[k], t);
      draw(current, pr);
    };
  });
  return atlas;
}

async function worker(opt) {
  const page = path.resolve(opt.page), preview = !!opt.preview;
  const fps = +opt.fps, a = +opt.a, z = +opt.z;
  const browser = await chromium.launch();
  const p = await openScene(browser, page, preview);
  const isAct2 = await p.evaluate(() => typeof window.beatAt === 'function');
  let atlas = null, lastKey = '';
  if (isAct2) { atlas = await openAtlas(browser, page); await p.evaluate(() => { window.externalPpt = true; }); }
  const ff = spawn(FFMPEG, ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'mjpeg', '-i', '-',
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', String(opt.crf), '-preset', preview ? 'veryfast' : 'medium', opt.out], { stdio: ['pipe', 'inherit', 'inherit'] });
  for (let i = a; i < z; i++) {
    const t = i / fps;
    if (atlas) {
      const bt = await p.evaluate(t => window.beatAt(t), t);
      if (bt) {
        const key = bt.index + ':' + (bt.progress < 1 ? bt.progress.toFixed(4) : 'done');
        if (key !== lastKey) {
          await atlas.evaluate(([idx, pr]) => window.__seek(idx, pr), [bt.index, bt.progress]);
          const buf = await atlas.screenshot({ type: 'jpeg', quality: 92, clip: { x: 0, y: 0, width: 1600, height: 712 } });
          await p.evaluate(async src => { const im = document.getElementById('ppt'); im.src = src; await im.decode(); }, 'data:image/jpeg;base64,' + buf.toString('base64'));
          lastKey = key;
        }
      }
    }
    await p.evaluate(t => window.render(t), t);
    const buf = await p.screenshot({ type: 'jpeg', quality: preview ? 80 : 92 });
    if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
    if ((i - a) % 48 === 0) process.stdout.write(`progress ${i - a}\n`);
  }
  ff.stdin.end();
  const code = await new Promise(r => ff.on('close', r));
  await browser.close();
  process.stdout.write(`progress ${z - a}\n`);
  if (code !== 0) { console.error('ffmpeg 出错，退出码', code); process.exit(1); }
}

async function main(opt) {
  if (!opt._[0]) { console.log(fs.readFileSync(__filename, 'utf8').split('\n').slice(0, 13).join('\n')); process.exit(1); }
  const page = path.resolve(opt._[0]);
  const preview = !!opt.preview;
  const out = path.resolve(opt._[1] || path.join(__dirname, '输出', path.basename(page, '.html') + (preview ? '_草稿.mp4' : '_录制.mp4')));
  fs.mkdirSync(path.dirname(out), { recursive: true });
  const fps = +(opt.fps || (preview ? 12 : 24));
  const crf = +(opt.crf || (preview ? 30 : 24));
  const parts = Math.max(1, +(opt.parts || Math.max(1, Math.floor(os.cpus().length / 2) - 2)));

  const browser = await chromium.launch();
  const p = await openScene(browser, page, false);
  const D = await p.evaluate(() => window.DURATION);
  await browser.close();
  const from = +(opt.from || 0), to = Math.min(D, +(opt.to || D));
  const i0 = Math.floor(from * fps), i1 = Math.ceil(to * fps), n = i1 - i0;
  console.log(`${path.basename(page)}：全长 ${D.toFixed(1)} 秒，录 ${from}–${to.toFixed(1)} 秒，共 ${n} 帧，${parts} 路同时录${preview ? '（草稿）' : ''}`);

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kaifangri-'));
  const done = new Array(parts).fill(0), started = Date.now();
  const jobs = [];
  for (let k = 0; k < parts; k++) {
    const a = i0 + Math.floor(n * k / parts), z = i0 + Math.floor(n * (k + 1) / parts);
    const partOut = path.join(tmp, `p${k}.mp4`);
    const args = [__filename, '--worker', '--page', page, '--out', partOut, '--a', a, '--z', z, '--fps', fps, '--crf', crf].map(String);
    if (preview) args.push('--preview');
    const child = spawn(process.execPath, args, { stdio: ['ignore', 'pipe', 'inherit'] });
    child.stdout.on('data', d => {
      for (const m of String(d).matchAll(/progress (\d+)/g)) done[k] = +m[1];
      const sum = done.reduce((x, y) => x + y, 0), sec = (Date.now() - started) / 1000;
      const left = sum ? sec / sum * (n - sum) : 0;
      process.stdout.write(`\r  ${(100 * sum / n).toFixed(1)}%  ${sum}/${n} 帧  已用 ${Math.round(sec / 60)} 分，约剩 ${Math.round(left / 60)} 分   `);
    });
    jobs.push(new Promise((res, rej) => child.on('close', c => c === 0 ? res(partOut) : rej(new Error(`第 ${k + 1} 路失败`)))));
  }
  const files = await Promise.all(jobs);
  const list = path.join(tmp, 'list.txt');
  fs.writeFileSync(list, files.map(f => `file '${f.replace(/\\/g, '/').replace(/'/g, "'\\''")}'`).join('\n'));
  await new Promise((res, rej) => spawn(FFMPEG, ['-y', '-loglevel', 'error', '-f', 'concat', '-safe', '0', '-i', list, '-c', 'copy', out], { stdio: 'inherit' })
    .on('close', c => c === 0 ? res() : rej(new Error('拼接失败'))));
  fs.rmSync(tmp, { recursive: true, force: true });
  console.log(`\n完成：${out}（${(fs.statSync(out).size / 1048576).toFixed(1)} MB，用时 ${((Date.now() - started) / 60000).toFixed(1)} 分）`);
}

const opt = parseArgs(process.argv.slice(2));
(opt.worker ? worker(opt) : main(opt)).catch(e => { console.error(e); process.exit(1); });
