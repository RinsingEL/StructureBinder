// 截几张定格图，几秒钟就好，适合检查版式。
//
//   node still.js <页面.html> 时间点 时间点 ...
//
// 时间点可以写秒数（120），也可以写"画面事件+秒数"（poly+2，即 poly 出现后 2 秒），
// 画面事件就是 make_scripts.py 里 MARKS 的名字；写 @3-13+1 就是第 3-13 句开始后 1 秒。图存在 tools/截图/ 下。
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const { pathToFileURL } = require('url');
(async () => {
  const [pageArg, ...times] = process.argv.slice(2);
  if (!pageArg || !times.length) { console.log(fs.readFileSync(__filename, 'utf8').split('\n').slice(0, 7).join('\n')); process.exit(1); }
  const page = path.resolve(pageArg), outDir = path.join(__dirname, '截图');
  fs.mkdirSync(outDir, { recursive: true });
  const browser = await chromium.launch();
  const p = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = []; p.on('pageerror', e => errors.push(e.message));
  await p.goto(pathToFileURL(page).href + '?static');
  await p.evaluate(() => document.fonts.ready);
  await p.waitForTimeout(3000);
  for (const spec of times) {
    const [name, off] = spec.split('+');
    const t = await p.evaluate(([n, o]) => (n.startsWith('@') ? (window.LINES || {})[n.slice(1)] : isNaN(+n) ? (window.MARKS || {})[n] : +n) + (+o || 0), [name, off]);
    if (t == null || isNaN(t)) { console.log(`找不到画面事件：${spec}`); continue; }
    await p.evaluate(t => window.render(t), t);
    const file = path.join(outDir, `${path.basename(page, '.html')}_${spec}.png`);
    await p.screenshot({ path: file });
    console.log(`${spec}（${t.toFixed(1)} 秒）→ ${file}`);
  }
  if (errors.length) console.log('页面报错：', errors);
  await browser.close();
})();
