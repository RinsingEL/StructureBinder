// 第三、四、五段共用的舞台：六个小人、字幕、表情切换和计时。
// 用法：页面里先放好 #stage（含活动室背景和白板内容），再依次引入 actN_script.js、本文件、本段的画面脚本。
// 本段画面脚本定义 window.drawBoard(time)，用 since('事件名', time) 取某个画面事件已经过去了几秒。
(function () {
  const SPRITES = '../../act2/assets/avatars/sprites/compact/';
  const members = [
    { id: 'rin',      name: 'Rin',      color: '#8a6a4f', x: -40,  z: 1 },
    { id: 'gemini',   name: 'Gemini',   color: '#8a63d2', x: 125,  z: 2 },
    { id: 'claude',   name: 'Claude',   color: '#d9772b', x: 290,  z: 3 },
    { id: 'astra',    name: 'Astra',    color: '#7f8db5', x: 1225, z: 3 },
    { id: 'deepseek', name: 'DeepSeek', color: '#3a6fc4', x: 1380, z: 2 },
    { id: 'glm',      name: 'GLM',      color: '#3d3d46', x: 1520, z: 1 },
  ];
  const states = ['listen', 'greet', 'd4', 'talk_smile', 'think', 'moved', 'wry'];
  const FALLBACK = { think: ['d4', 'talk_smile'], talk_smile: ['d4'], d4: ['talk_smile'], greet: ['listen'], moved: ['talk_smile'], wry: ['d4'] };
  const cfg = Object.assign({ INTRO: 0.8, OUTRO: 2.4, GAP: 0.25 }, window.STAGE_CONFIG || {});

  const css = document.createElement('style');
  css.textContent = `
  .member { position: absolute; width: 420px; height: 420px; bottom: -64px; transform-origin: 50% 100%; }
  .member img { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0;
    filter: drop-shadow(3px 0 0 #fff) drop-shadow(-3px 0 0 #fff) drop-shadow(0 3px 0 #fff) drop-shadow(0 -3px 0 #fff) drop-shadow(0 6px 8px rgba(60,40,20,.25)); }
  #sub { position: absolute; left: 590px; right: 590px; top: 740px; height: 300px; z-index: 20;
    display: flex; flex-direction: column; align-items: center; justify-content: center; }
  #name { font-size: 26px; font-weight: 700; color: #fff; padding: 4px 18px; border-radius: 999px; margin-bottom: 14px; letter-spacing: 2px; }
  #line { font-size: 30px; line-height: 1.5; color: #3a3631; text-align: center; background: rgba(255,253,247,.94); padding: 16px 30px;
    border-radius: 14px; box-shadow: 0 4px 14px rgba(60,40,20,.12); max-width: 680px; text-wrap: balance; white-space: pre-line; }`;
  document.head.appendChild(css);

  const stage = document.getElementById('stage');
  const box = document.createElement('div'); box.id = 'members'; stage.appendChild(box);
  const sub = document.createElement('div'); sub.id = 'sub'; sub.innerHTML = '<div id="name"></div><div id="line"></div>'; stage.appendChild(sub);
  for (const m of members) {
    const el = document.createElement('div');
    el.className = 'member'; el.style.left = m.x + 'px'; el.id = 'm-' + m.id;
    for (const s of states) {
      const img = document.createElement('img');
      img.dataset.state = s; img.src = SPRITES + m.id + '/' + s + '.png';
      img.onerror = () => img.remove();
      el.appendChild(img);
    }
    box.appendChild(el);
  }

  // 时间线：每屏字幕按每秒约 6 字计时；mark 记下这一屏开始时触发的画面事件
  const timeline = [], marks = {}, lines = {};
  let t = cfg.INTRO;
  for (const line of window.SCRIPT.lines) {
    const start = t; lines[line.id] = t;
    for (const c of line.cards) {
      const dur = Math.max(1.8, c.text.replace(/\n/g, '').length / 6 + 0.7) + (c.hold || 0);
      if (c.mark) marks[c.mark] = t + 0.2;
      timeline.push({ who: line.who, face: c.face, text: c.text, start: t, end: t + dur, lineStart: start });
      t += dur;
    }
    t += cfg.GAP;
  }
  window.DURATION = t + cfg.OUTRO;
  window.MARKS = marks; window.LINES = lines;

  const $ = id => document.getElementById(id);
  const clamp = x => Math.max(0, Math.min(1, x));
  const ease = x => 1 - Math.pow(1 - clamp(x), 3);
  const inout = x => { x = clamp(x); return x < .5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; };
  const since = (name, time) => name in marks ? time - marks[name] : -1e9;
  const sinceLine = (id, time) => id in lines ? time - lines[id] : -1e9;
  // 从事件 a 开始淡入，到事件 b 开始淡出（b 可省略）
  const between = (a, b, time, fin = 0.5, fout = 0.5, delayIn = 0, delayOut = 0) =>
    ease((since(a, time) - delayIn) / fin) * (b ? 1 - ease((since(b, time) - delayOut) / fout) : 1);
  Object.assign(window, { $, clamp, ease, inout, since, sinceLine, between });

  const SWAP = 0.15, HOLD = 2.0;
  const cueAt = time => timeline.find(c => time >= c.start && time < c.end);
  const speaks = (c, id) => c.who === id || c.who === 'all';
  function available(id, state) {
    const el = $('m-' + id);
    if (el.querySelector(`img[data-state="${state}"]`)) return state;
    for (const alt of FALLBACK[state] || []) if (el.querySelector(`img[data-state="${alt}"]`)) return alt;
    return 'listen';
  }
  function faceAt(id, time) {
    const c = cueAt(time);
    if (c && speaks(c, id)) return available(id, c.face);
    let last = null;
    for (const x of timeline) { if (x.start > time) break; if (speaks(x, id) && x.end <= time) last = x; }
    if (last && (time - last.end < HOLD || (cfg.HOLD_LAST && last === timeline[timeline.length - 1]))) return available(id, last.face);
    return 'listen';
  }
  function dimAt(id, time) { const c = cueAt(time); return !!c && !speaks(c, id); }
  const boundaries = [...new Set(timeline.flatMap(c => [c.start, c.end, c.end + HOLD]))].sort((a, b) => a - b);
  function lastChange(fn, time, dur) {
    for (let i = boundaries.length - 1; i >= 0; i--) {
      const b = boundaries[i];
      if (b > time) continue;
      if (time - b >= dur) return null;
      if (fn(b - 0.001) !== fn(b)) return b;
    }
    return null;
  }

  window.render = function (time) {
    if (window.drawBoard) window.drawBoard(time);
    const cue = cueAt(time);
    members.forEach((m, i) => {
      const el = $('m-' + m.id);
      const speaking = cue && speaks(cue, m.id);
      const face = faceAt(m.id, time);
      const changedAt = lastChange(x => faceAt(m.id, x), time, SWAP);
      const k = changedAt === null ? 1 : (time - changedAt) / SWAP;
      const prev = changedAt === null ? face : faceAt(m.id, changedAt - 0.001);
      for (const img of el.querySelectorAll('img')) {
        const s = img.dataset.state;
        img.style.opacity = s === face ? k : (s === prev && k < 1 ? 1 - k : 0);
      }
      const squash = k < 1 ? 1 - 0.04 * Math.sin(k * Math.PI) : 1;
      const dimNow = dimAt(m.id, time), dimChanged = lastChange(x => dimAt(m.id, x), time, 0.25);
      let d = dimNow ? 1 : 0;
      if (dimChanged !== null) { const q = (time - dimChanged) / 0.25; d = dimNow ? q : 1 - q; }
      el.style.filter = `brightness(${1 - 0.28 * d}) saturate(${1 - 0.2 * d})`;
      let lift = 0, scale = 1;
      if (speaking) {
        // 全员一起说时错开一点点，像一起跳起来
        const s = time - cue.lineStart - (cue.who === 'all' ? i * 0.06 : 0);
        lift = 26 * Math.sin(clamp(s / 0.35) * Math.PI) * (s < 0.35 ? 1 : 0) + 10 + 3 * Math.sin(time * 6 + i);
        scale = 1.08;
      }
      // RISE：开场时六人从下面依次升起
      if (cfg.RISE !== undefined) lift -= 300 * (1 - ease((time - cfg.RISE - i * 0.08) / 0.6));
      el.style.transform = `translateY(${-lift}px) scale(${scale * (2 - squash)}, ${scale * squash})`;
      el.style.zIndex = speaking ? 10 + m.z : m.z;
    });
    const name = $('name'), line = $('line');
    if (cue) {
      if (cue.who === 'all') {
        name.textContent = '全员';
        name.style.background = 'linear-gradient(90deg,' + members.map(m => m.color).join(',') + ')';
      } else {
        const who = members.find(m => m.id === cue.who);
        name.textContent = who.name; name.style.background = who.color;
      }
      line.textContent = cue.text;
      const fade = ease((time - cue.start) / 0.2);
      name.style.opacity = fade; line.style.opacity = fade;
    } else { name.style.opacity = 0; line.style.opacity = 0; }
  };

  window.addEventListener('load', () => {
    if (location.search.includes('static')) return;
    const m = location.search.match(/[?&]t=([\d.]+)/);
    const t0 = performance.now() - (m ? +m[1] * 1000 : 0);
    (function loop() { window.render(((performance.now() - t0) / 1000) % window.DURATION); requestAnimationFrame(loop); })();
  });
})();
