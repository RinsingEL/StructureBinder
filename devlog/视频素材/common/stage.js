// 第三、四、五段共用的舞台：六个小人、字幕、表情切换、反应、小特效和推近镜头。
// 用法：页面里先放好 #stage（含活动室背景和白板 #board），再依次引入 actN_script.js、本文件、本段的画面脚本。
// 本段画面脚本定义 window.drawBoard(time)，用 since('事件名', time) 取某个画面事件已经过去了几秒。
//
// 字幕卡片（由 make_scripts.py 生成）里可以有：
//   mark   这一屏开始时触发的白板画面事件
//   fx     说话人身上的小特效：flowers 小红花、sweat 汗滴、shy 害羞、stars 冒星星
//   react  不说话的人这一屏的反应，如 { rin: 'wry+shy' }：表情，加号后面是特效
//
// window.STAGE_CONFIG.SHOTS = [['事件或@行号', '事件或@行号'], ...]：这些时间段里镜头推近白板，
// 白板铺满画面，小人换成底下的泡泡头像（和第二段一样）。
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
  // 立绘缩到 300px 时脸的位置，用来裁泡泡头像、放特效
  const HEAD = { rin: [125, 95], gemini: [130, 100], claude: [130, 100], astra: [150, 100], deepseek: [145, 100], glm: [148, 95] };
  const SLOT = { rin: 120, gemini: 290, claude: 460, astra: 1460, deepseek: 1630, glm: 1800 };
  const states = ['listen', 'greet', 'd4', 'talk_smile', 'think', 'moved', 'wry'];
  const FALLBACK = { think: ['d4', 'talk_smile'], talk_smile: ['d4'], d4: ['talk_smile'], greet: ['listen'], moved: ['talk_smile'], wry: ['d4'] };
  const cfg = Object.assign({ INTRO: 0.8, OUTRO: 2.4, GAP: 0.25, SHOTS: [] }, window.STAGE_CONFIG || {});

  const css = document.createElement('style');
  css.textContent = `
  #cam { position: absolute; inset: 0; transform-origin: 0 0; }
  .member { position: absolute; width: 420px; height: 420px; bottom: -64px; transform-origin: 50% 100%; }
  .member img { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0;
    filter: drop-shadow(3px 0 0 #fff) drop-shadow(-3px 0 0 #fff) drop-shadow(0 3px 0 #fff) drop-shadow(0 -3px 0 #fff) drop-shadow(0 6px 8px rgba(60,40,20,.25)); }
  #mat { position: absolute; inset: 0; overflow: hidden; opacity: 0; z-index: 30; pointer-events: none; }
  #win { position: absolute; border-radius: 10px; box-shadow: 0 0 0 3000px #efe6d6; }
  #winline { position: absolute; border-radius: 12px; border: 2px solid rgba(60,40,20,.14); margin: -2px; }
  #band { position: absolute; inset: 0; z-index: 31; pointer-events: none; opacity: 0; }
  .av { position: absolute; width: 170px; height: 170px; margin: -85px 0 0 -85px; }
  .av .face { position: absolute; inset: 0; border-radius: 50%; overflow: hidden; background: #fffdf8;
    box-shadow: 0 0 0 5px #fff, 0 0 0 11px rgba(190,220,240,.45), 0 6px 16px rgba(60,40,20,.22); }
  .av .face img { position: absolute; width: 340px; height: 340px; opacity: 0; }
  .av .gloss { position: absolute; inset: -9px; border-radius: 50%; border: 4px solid transparent; border-top-color: rgba(255,255,255,.9); transform: rotate(-45deg); }
  .av .burst { position: absolute; inset: 0; border-radius: 50%; border: 4px solid rgba(190,220,240,.9); opacity: 0; }
  #fx { position: absolute; inset: 0; z-index: 32; pointer-events: none; overflow: visible; }
  #sub { position: absolute; left: 590px; right: 590px; top: 740px; height: 300px; z-index: 33;
    display: flex; flex-direction: column; align-items: center; justify-content: center; }
  #name { font-size: 26px; font-weight: 700; color: #fff; padding: 4px 18px; border-radius: 999px; margin-bottom: 14px; letter-spacing: 2px; }
  #line { font-size: 30px; line-height: 1.5; color: #3a3631; text-align: center; background: rgba(255,253,247,.94); padding: 16px 30px;
    border-radius: 14px; box-shadow: 0 4px 14px rgba(60,40,20,.12); max-width: 680px; text-wrap: balance; white-space: pre-line; }`;
  document.head.appendChild(css);

  // 活动室、白板和小人都放进 #cam，推近镜头时一起放大
  const stage = document.getElementById('stage');
  // 带 data-overlay 的元素（例如第五段的定格白框）留在最上面，不跟着镜头动
  const cam = document.createElement('div'); cam.id = 'cam';
  const overlays = [...stage.children].filter(e => e.dataset.overlay !== undefined);
  for (const e of [...stage.childNodes]) if (!overlays.includes(e)) cam.appendChild(e);
  stage.prepend(cam);
  const box = document.createElement('div'); box.id = 'members'; cam.appendChild(box);
  stage.insertAdjacentHTML('beforeend', '<div id="mat"><div id="win"></div><div id="winline"></div></div><div id="band"></div>' +
    '<svg id="fx" width="1920" height="1080"></svg><div id="sub"><div id="name"></div><div id="line"></div></div>');
  for (const e of overlays) stage.appendChild(e);
  for (const m of members) {
    const el = document.createElement('div');
    el.className = 'member'; el.style.left = m.x + 'px'; el.id = 'm-' + m.id;
    const av = document.createElement('div');
    av.className = 'av'; av.id = 'av-' + m.id; av.style.left = SLOT[m.id] + 'px'; av.style.top = '920px';
    av.innerHTML = '<div class="face"></div><div class="gloss"></div><div class="burst"></div>';
    const [hx, hy] = HEAD[m.id];
    for (const s of states) {
      for (const [parent, size] of [[el, 0], [av.firstChild, 340]]) {
        const img = document.createElement('img');
        img.dataset.state = s; img.src = SPRITES + m.id + '/' + s + '.png';
        if (size) { img.style.left = (85 - hx * size / 300) + 'px'; img.style.top = (85 - hy * size / 300) + 'px'; }
        img.onerror = () => img.remove();
        parent.appendChild(img);
      }
    }
    box.appendChild(el);
    document.getElementById('band').appendChild(av);
  }

  // 时间线：每屏字幕按每秒约 6 字计时；mark 记下这一屏开始时触发的画面事件
  const timeline = [], marks = {}, lines = {};
  let t = cfg.INTRO;
  for (const line of window.SCRIPT.lines) {
    const start = t; lines[line.id] = t;
    for (const c of line.cards) {
      const dur = Math.max(1.8, c.text.replace(/\n/g, '').length / 6 + 0.7) + (c.hold || 0);
      if (c.mark) marks[c.mark] = t + 0.2;
      const react = {};
      for (const [id, v] of Object.entries(c.react || {})) { const [face, fx] = v.split('+'); react[id] = { face, fx }; }
      timeline.push({ id: line.id, who: line.who, face: c.face, fx: c.fx, react, text: c.text, start: t, end: t + dur, lineStart: start });
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
  const backOut = x => { x = clamp(x); const c = 1.9; return 1 + (c + 1) * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2); };
  const since = (name, time) => name in marks ? time - marks[name] : -1e9;
  const sinceLine = (id, time) => id in lines ? time - lines[id] : -1e9;
  // 从事件 a 开始淡入，到事件 b 开始淡出（b 可省略）
  const between = (a, b, time, fin = 0.5, fout = 0.5, delayIn = 0, delayOut = 0) =>
    ease((since(a, time) - delayIn) / fin) * (b ? 1 - ease((since(b, time) - delayOut) / fout) : 1);
  Object.assign(window, { $, clamp, ease, inout, since, sinceLine, between });

  const SWAP = 0.15, HOLD = 2.0, STAY = 10, ZD = 1.1;
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
    if (c && c.react[id]) return available(id, c.react[id].face);
    let last = null;
    for (const x of timeline) { if (x.start > time) break; if (speaks(x, id) && x.end <= time) last = x; }
    if (last && (time - last.end < HOLD || (cfg.HOLD_LAST && last === timeline[timeline.length - 1]))) return available(id, last.face);
    return 'listen';
  }
  // 0 不变暗；有反应的人只暗一半
  function dimAt(id, time) { const c = cueAt(time); return !c || speaks(c, id) ? 0 : c.react[id] ? 0.4 : 1; }
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
  function faceImages(container, id, time) {
    const face = faceAt(id, time), changedAt = lastChange(x => faceAt(id, x), time, SWAP);
    const k = changedAt === null ? 1 : (time - changedAt) / SWAP;
    const prev = changedAt === null ? face : faceAt(id, changedAt - 0.001);
    for (const img of container.querySelectorAll('img')) {
      const s = img.dataset.state;
      img.style.opacity = s === face ? k : (s === prev && k < 1 ? 1 - k : 0);
    }
    return k;
  }

  // 推近镜头：白板内侧 (122, 41, 1677×656) 放大到 (40, 20)，宽 1840
  const B = { x: 122, y: 41, w: 1677, h: 656 }, K = 1840 / B.w, W = { x: 40, y: 20, w: 1840, h: B.h * K };
  Object.assign($('win').style, { left: W.x + 'px', top: W.y + 'px', width: W.w + 'px', height: W.h + 'px' });
  Object.assign($('winline').style, { left: W.x + 'px', top: W.y + 'px', width: W.w + 'px', height: W.h + 'px' });
  const at = name => name.startsWith('@') ? lines[name.slice(1)] : marks[name];
  const shots = cfg.SHOTS.map(([a, b]) => [at(a), at(b)]).filter(([a, b]) => a !== undefined && b !== undefined);
  function camAt(time) {
    let c = 0;
    for (const [a, b] of shots) c = Math.max(c, inout((time - a + 0.3) / ZD) * (1 - inout((time - b + 0.3) / ZD)));
    return c;
  }
  function shownAt(id, time, fullFrom) {
    const c = cueAt(time);
    if (c && speaks(c, id)) return true;
    let e = null; for (const x of timeline) { if (x.start > time) break; if (speaks(x, id) && x.end <= time) e = x.end; }
    return e !== null && e > fullFrom && time - e < STAY;
  }

  // 小特效：画在说话或反应的人头上（推近时画在泡泡头像上）
  function headPos(id, full, lift) {
    if (full > 0.5) return [SLOT[id], 920, 0.62];
    const m = members.find(x => x.id === id), [hx, hy] = HEAD[id];
    return [m.x + hx * 1.4, 1080 + 64 - 420 + hy * 1.4 - lift, 1];
  }
  function fxSvg(kind, x, y, s, age, seed) {
    let out = '';
    const fade = clamp(age / 0.2) * (1 - clamp((age - 2.4) / 0.4));
    if (fade <= 0) return '';
    if (kind === 'flowers') {
      for (let i = 0; i < 6; i++) {
        const a = -Math.PI / 2 + (i - 2.5) * 0.55, p = ease((age - i * 0.12) / 0.6);
        if (p <= 0) continue;
        const r = (120 + (i % 2) * 40) * s * p, fx = x + Math.cos(a) * r, fy = y + Math.sin(a) * r * 0.8 - 30 * s - age * 8;
        const rot = age * 60 + i * 40, sz = 15 * s * backOut((age - i * 0.12) / 0.4);
        let petals = '';
        for (let k = 0; k < 5; k++) petals += `<ellipse cx="0" cy="${-sz * 0.7}" rx="${sz * 0.45}" ry="${sz * 0.7}" fill="#e8707a" transform="rotate(${k * 72})"/>`;
        out += `<g transform="translate(${fx} ${fy}) rotate(${rot})" opacity="${fade}">${petals}<circle r="${sz * 0.35}" fill="#f6d36b"/></g>`;
      }
    } else if (kind === 'sweat') {
      const dy = 26 * s * ease(age / 1.2);
      out += `<path d="M0 -22 C 10 -6 14 4 0 14 C -14 4 -10 -6 0 -22Z" transform="translate(${x + 95 * s} ${y - 70 * s + dy}) scale(${1.5 * s})" fill="#8fd0f2" stroke="#3a6fc4" stroke-width="2" opacity="${fade}"/>`;
    } else if (kind === 'shy') {
      for (const side of [-1, 1]) {
        out += `<g transform="translate(${x + side * 62 * s} ${y + 22 * s})" opacity="${fade * 0.9}">` +
          [0, 1, 2].map(k => `<line x1="${(k - 1) * 11 * s - 6 * s}" y1="${8 * s}" x2="${(k - 1) * 11 * s + 6 * s}" y2="${-8 * s}" stroke="#e8707a" stroke-width="${3.5 * s}" stroke-linecap="round"/>`).join('') + '</g>';
      }
      const hy = y - 120 * s - age * 18;
      out += `<path d="M0 6 C -10 -2 -10 -10 -4 -11 C 0 -12 0 -8 0 -7 C 0 -8 0 -12 4 -11 C 10 -10 10 -2 0 6Z" transform="translate(${x + 80 * s} ${hy}) scale(${2.2 * s})" fill="#e8707a" opacity="${fade}"/>`;
    } else if (kind === 'stars') {
      for (let i = 0; i < 3; i++) {
        const a = age * 4 + i * 2.09, sx = x + Math.cos(a) * 70 * s, sy = y - 135 * s + Math.sin(a) * 16 * s;
        out += `<path d="M0 -10 L3 -3 L10 -3 L4 2 L6 10 L0 5 L-6 10 L-4 2 L-10 -3 L-3 -3Z" transform="translate(${sx} ${sy}) scale(${1.6 * s})" fill="#f6d36b" stroke="#c9902a" stroke-width="1" opacity="${fade}"/>`;
      }
    }
    return out;
  }

  window.render = function (time) {
    if (window.drawBoard) window.drawBoard(time);
    const cue = cueAt(time);
    const c = camAt(time), full = clamp((c - 0.8) / 0.2);
    $('cam').style.transform = `translate(${(W.x - B.x * K) * c}px, ${(W.y - B.y * K) * c}px) scale(${1 + (K - 1) * c})`;
    $('mat').style.opacity = full; $('band').style.opacity = full;
    $('members').style.opacity = 1 - clamp(c / 0.6); // 推近时活动室里的小人退出画面，免得头顶露在白板边上
    let fullFrom = -1e9;
    for (const [a] of shots) if (a - 0.3 <= time) fullFrom = Math.max(fullFrom, a - 0.3);
    const lifts = {};
    members.forEach((m, i) => {
      const el = $('m-' + m.id);
      const speaking = cue && speaks(cue, m.id);
      const k = faceImages(el, m.id, time);
      const squash = k < 1 ? 1 - 0.04 * Math.sin(k * Math.PI) : 1;
      const dimNow = dimAt(m.id, time), dimChanged = lastChange(x => dimAt(m.id, x), time, 0.25);
      let d = dimNow;
      if (dimChanged !== null) { const q = (time - dimChanged) / 0.25, before = dimAt(m.id, dimChanged - 0.001); d = before + (dimNow - before) * q; }
      el.style.filter = `brightness(${1 - 0.28 * d}) saturate(${1 - 0.2 * d})`;
      let lift = 0, scale = 1;
      if (speaking) {
        // 全员一起说时错开一点点，像一起跳起来
        const s = time - cue.lineStart - (cue.who === 'all' ? i * 0.06 : 0);
        lift = 26 * Math.sin(clamp(s / 0.35) * Math.PI) * (s < 0.35 ? 1 : 0) + 10 + 3 * Math.sin(time * 6 + i);
        scale = 1.08;
      } else if (cue && cue.react[m.id]) {
        // 有反应的人轻轻跳一下
        const s = time - cue.start;
        lift = 14 * Math.sin(clamp(s / 0.3) * Math.PI) * (s < 0.3 ? 1 : 0);
      }
      // RISE：开场时六人从下面依次升起
      if (cfg.RISE !== undefined) lift -= 300 * (1 - ease((time - cfg.RISE - i * 0.08) / 0.6));
      lifts[m.id] = lift;
      el.style.transform = `translateY(${-lift}px) scale(${scale * (2 - squash)}, ${scale * squash})`;
      el.style.zIndex = speaking ? 10 + m.z : cue && cue.react[m.id] ? 6 + m.z : m.z; // 有反应的人站到前面一点

      // 推近时的泡泡头像
      if (full > 0) {
        const av = $('av-' + m.id);
        let sc = 0, burst = -1;
        if (shownAt(m.id, time, fullFrom)) {
          sc = 1;
          if (speaking && !shownAt(m.id, cue.lineStart - 0.001, fullFrom)) { sc = backOut((time - cue.lineStart) / 0.4); burst = (time - cue.lineStart) / 0.5; }
        } else if (cue && cue.react[m.id]) sc = backOut((time - cue.start) / 0.4);
        const big = speaking ? 1 : cue && cue.react[m.id] ? 0.5 : 0;
        const hop = speaking ? 18 * Math.sin(Math.PI * clamp((time - cue.lineStart) / 0.35)) : 0;
        const float = (1 - big) * 5 * Math.sin(time * 2.2 + SLOT[m.id]);
        av.style.opacity = sc > 0 ? 1 : 0;
        av.style.transform = `translateY(${-hop + (1 - big) * 22 + float}px) scale(${Math.max(0, sc) * (0.72 + 0.28 * big)})`;
        av.style.filter = `brightness(${0.86 + 0.14 * big})`;
        av.style.zIndex = speaking ? 2 : 1;
        const bu = av.querySelector('.burst');
        bu.style.opacity = burst >= 0 && burst < 1 ? 1 - burst : 0;
        bu.style.transform = `scale(${1 + 0.45 * clamp(burst)})`;
        faceImages(av, m.id, time);
      }
    });

    // 特效：这一屏和上一屏的都画，让特效能比字幕多停一会儿
    let fx = '';
    for (const x of timeline) {
      if (x.start > time) break;
      if (time - x.start > 3) continue;
      if (x.fx && x.who !== 'all') { const [px, py, s] = headPos(x.who, full, lifts[x.who] || 0); fx += fxSvg(x.fx, px, py, s, time - x.start); }
      for (const [id, r] of Object.entries(x.react)) if (r.fx) { const [px, py, s] = headPos(id, full, lifts[id] || 0); fx += fxSvg(r.fx, px, py, s, time - x.start); }
    }
    $('fx').innerHTML = fx;

    const name = $('name'), line = $('line');
    if (cue) {
      if (cue.who === 'all') {
        name.textContent = '全员';
        name.style.background = 'linear-gradient(90deg,' + members.map(m => m.color).join(',') + ')';
      } else {
        const who = members.find(m => m.id === cue.who);
        name.textContent = who.name; name.style.background = who.color;
      }
      if (line.textContent !== cue.text) {
        // 字幕框收紧到文字的实际宽度，多留几像素免得标点挤到下一行
        line.style.width = ''; line.textContent = cue.text;
        const r = document.createRange(); r.selectNodeContents(line);
        line.style.width = Math.ceil(Math.max(...[...r.getClientRects()].map(x => x.width))) + 8 + 'px';
      }
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
