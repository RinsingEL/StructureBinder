// 导出某一段的时间表（每屏字幕、谁在说、签到/画面事件），给作曲脚本对拍子用
//   node export_timeline.js ../../视频素材/开场/opening.html 开场.json
const { chromium } = require('playwright');
const path = require('path'); const fs = require('fs'); const { pathToFileURL } = require('url');
(async () => {
  const [page, out] = process.argv.slice(2);
  const b = await chromium.launch(); const p = await b.newPage();
  await p.goto(pathToFileURL(path.resolve(page)).href + '?static'); await p.waitForTimeout(1500);
  const data = await p.evaluate(() => ({
    duration: window.DURATION,
    cards: (window.TIMELINE || []).map(c => ({ id: c.id, who: c.who, face: c.face, text: c.text, start: c.start, end: c.end, lineStart: c.lineStart, react: c.react, fx: c.fx })),
    stamps: window.STAMPS || {}, marks: window.MARKS || {}, lines: window.LINES || {},
  }));
  fs.writeFileSync(out, JSON.stringify(data, null, 1)); console.log(out, data.duration.toFixed(1), data.cards.length, 'cards');
  await b.close();
})();
