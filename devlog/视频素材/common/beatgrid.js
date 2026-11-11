// 节拍表：画面往音乐的拍子上靠。
// 每段视频都从第 0 拍开始，时长取整到小节，所以几段接起来拍子也是连着的。
// 字幕的开始、PPT 翻页、盖章这些事件挪到拍上；重要的事件挪到重拍（每小节第 1、3 拍）。
window.BeatGrid = function (bpm) {
  const beat = 60 / bpm, eps = 1e-6;
  return {
    bpm, beat, bar: 4 * beat,
    // t 之后（含 t）最近的格子；unit 是几拍一格：1 = 每拍，2 = 重拍，4 = 小节
    next: (t, unit = 1) => Math.ceil(t / (beat * unit) - eps) * beat * unit,
    // 字幕至少要读 minDur 秒：结束时间取最近的一拍，但不少于 minDur - slack
    endAfter: (start, minDur, slack = 0.3) => Math.ceil((start + minDur - slack) / beat - eps) * beat,
    beatOf: t => t / beat,
  };
};
