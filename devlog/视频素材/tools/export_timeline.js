// 导出一段的时间表（每屏字幕、谁在说、PPT 翻页、签到和画面事件），给配乐、配音脚本对时间用。
//   node export_timeline.js ../第二段/act2.html 输出.json
const { chromium } = require('playwright');
const path = require('path'); const fs = require('fs'); const { pathToFileURL } = require('url');
(async () => {
  const [page, out] = process.argv.slice(2);
  if (!page || !out) { console.log('node export_timeline.js <页面.html> <输出.json>'); process.exit(1); }
  const b = await chromium.launch(); const p = await b.newPage();
  await p.goto(pathToFileURL(path.resolve(page)).href + '?static'); await p.waitForTimeout(1500);
  const data = await p.evaluate(() => ({
    duration: window.DURATION,
    bpm: window.GRID ? window.GRID.bpm : (window.BeatGrid ? 108 : null),
    cards: (window.TIMELINE || []).map(c => ({ id: c.id, who: c.who, face: c.face, text: c.text, start: c.start, end: c.end, lineStart: c.lineStart, beat: c.beat, react: c.react, fx: c.fx })),
    beats: (window.BEATSTARTS || []).map(s => ({ index: s.beat, t: s.t })),
    stamps: window.STAMPS || {}, marks: window.MARKS || {}, lines: window.LINES || {},
  }));
  fs.writeFileSync(out, JSON.stringify(data, null, 1));
  console.log(path.basename(out), data.duration.toFixed(2) + ' 秒', data.cards.length, '屏');
  await b.close();
})();
