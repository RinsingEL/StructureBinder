// 直接用 Node 从各段 HTML 计算并导出 108 BPM 时间表（无需启动浏览器）。
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HERE = __dirname;
const VIDEO_DIR = path.resolve(HERE, '../../视频素材');

function extractTimeline(htmlPath) {
  const dir = path.dirname(htmlPath);
  const html = fs.readFileSync(htmlPath, 'utf8');
  const scripts = [...html.matchAll(/<script(?: src="([^"]+)")?>([\s\S]*?)<\/script>/g)];
  const noop = () => {};
  const mkEl = () => {
    const el = {
      style: {},
      dataset: {},
      classList: { toggle: noop },
      appendChild: noop,
      insertAdjacentHTML: noop,
      prepend: noop,
      append: noop,
      replaceChildren: noop,
      setAttribute: noop,
      remove: noop,
      querySelectorAll: () => [],
      querySelector: () => null,
      getTotalLength: () => 100,
      getPointAtLength: () => ({ x: 0, y: 0 }),
      getBoundingClientRect: () => ({ width: 100 }),
      offsetWidth: 100,
      innerHTML: '',
      children: [],
      childNodes: [],
    };
    Object.defineProperty(el, 'firstChild', { get: () => mkEl() });
    return el;
  };
  const ctx = {
    window: {},
    document: {
      getElementById: mkEl,
      createElement: mkEl,
      createElementNS: mkEl,
      createRange: () => ({ selectNodeContents: noop, getClientRects: () => [{ width: 100 }] }),
      head: { appendChild: noop },
    },
    location: { search: '?static' },
    performance: { now: () => 0 },
    requestAnimationFrame: noop,
    addEventListener: noop,
    Set, Math, Object, Array, JSON, encodeURI, RegExp,
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  for (const [, src, body] of scripts) {
    const code = src ? fs.readFileSync(path.resolve(dir, src), 'utf8') : body;
    vm.runInContext(code, ctx);
  }
  return {
    duration: ctx.DURATION,
    bpm: ctx.GRID ? ctx.GRID.bpm : 108,
    cards: (ctx.TIMELINE || []).map(c => ({
      id: c.id,
      who: c.who,
      face: c.face,
      text: c.text,
      start: c.start,
      end: c.end,
      lineStart: c.lineStart,
      beat: c.beat,
    })),
    beats: (ctx.BEATSTARTS || []).map(s => ({ index: s.beat, t: s.t })),
    stamps: ctx.STAMPS || {},
    marks: ctx.MARKS || {},
  };
}

const targets = [
  ['开场/opening.html', 'timeline_开场.json'],
  ['第二段/act2.html', 'timeline_第二段.json'],
  ['第三段/act3.html', 'timeline_第三段.json'],
  ['第四段/act4.html', 'timeline_第四段.json'],
  ['第五段/act5.html', 'timeline_第五段.json'],
];

for (const [relHtml, outJson] of targets) {
  const data = extractTimeline(path.join(VIDEO_DIR, relHtml));
  fs.writeFileSync(path.join(HERE, outJson), JSON.stringify(data, null, 1), 'utf8');
  console.log(`${outJson}: ${data.duration.toFixed(2)}s (${Math.round(data.duration / (60 / 108))} beats), ${data.cards.length} cards`);
}
