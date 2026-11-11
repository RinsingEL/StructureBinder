"""小克的开场配乐试作。

思路（和 Rin 商量好的）：
- 底乐一直在，给这一段提供舞台：尼龙吉他分解和弦、原声贝斯、Warm Pad、刷子鼓。
- 谁说话，谁的乐器就在底乐上面唱主旋律；六个人唱的是同一首歌，只是各有各的唱法。
- 签到盖章的那一下，自己的乐器"叮"一声往上扬。
- 六个人全部签完、GLM 说完，所有人一起唱最后两小节，算是升华。

用法（在仓库根目录）：
    .tools/devlog-audio/.venv/bin/python devlog/音频/claude_trial/compose.py

读 开场.json（export_timeline.js 导出的时间表），输出 开场.mid 和 开场.wav。
"""
import json, math, pathlib, wave
import numpy as np
import mido
import tinysoundfont

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SF2 = sorted((ROOT / '.tools/devlog-audio/soundfonts').glob('*.sf2'))[0]
SR = 44100
BPM = 96
BEAT = 60 / BPM
BAR = 4 * BEAT
TAIL = 4.0  # 视频结束后让最后一个和弦自然消失

tl = json.loads((HERE / '开场.json').read_text(encoding='utf-8'))
DUR = tl['duration']
END = DUR + TAIL

# ---------- 和声：F 大调，王道进行 + 回到主和弦 ----------
# 每个和弦写成 (根音 MIDI, 和弦音 pitch class 偏移)
CH = {
    'F': (53, [0, 4, 7]), 'Bb': (58, [0, 4, 7]), 'C': (48, [0, 4, 7]),
    'Am': (57, [0, 3, 7]), 'Dm': (50, [0, 3, 7]), 'Gm': (55, [0, 3, 7]),
}
CYCLE = ['Bb', 'C', 'Am', 'Dm', 'Gm', 'C', 'F', 'F']
INTRO_BARS = 1  # 第一小节是 F，等封面淡入


# 升华从 GLM 说完前后最近的小节线开始，最后两小节都落在 F 上
FIN_BAR = math.ceil((tl['cards'][-1]['end'] - BEAT) / BAR)


def chord_at_bar(b):
    if b < INTRO_BARS or b >= FIN_BAR:
        return 'F'
    return CYCLE[(b - INTRO_BARS) % 8]


def theme_index(b):
    return None if b < INTRO_BARS else (b - INTRO_BARS) % 8


# ---------- 主旋律：8 小节，(拍, 时值, 音高) ----------
THEME = [
    [(0, 1, 77), (1, .5, 74), (1.5, .5, 72), (2, 1, 74), (3, 1, 77)],       # Bb
    [(0, 1.5, 76), (1.5, .5, 74), (2, 1, 72), (3, 1, 67)],                   # C
    [(0, 1, 72), (1, .5, 69), (1.5, .5, 72), (2, 1, 76), (3, 1, 74)],        # Am
    [(0, 2, 74), (2.5, .5, 69), (3, 1, 72)],                                 # Dm
    [(0, 1, 70), (1, 1, 74), (2, 1, 79), (3, .5, 77), (3.5, .5, 76)],        # Gm
    [(0, 1, 76), (1, .5, 74), (1.5, .5, 72), (2, 2, 67)],                    # C
    [(0, 1, 69), (1, 1, 72), (2, 1, 77), (3, .5, 76), (3.5, .5, 77)],        # F
    [(0, 3, 77)],                                                            # F
]

# ---------- 乐器（Astra 的分配，GeneralUser GS 编号） ----------
PROG = {  # channel: (bank, preset, 名字)
    0: (0, 0, 'Rin 钢琴'), 1: (0, 73, 'Gemini 长笛'), 2: (0, 71, 'Claude 单簧管'),
    3: (0, 4, 'Astra 电钢琴'), 4: (0, 45, 'DeepSeek 拨弦'), 5: (0, 11, 'GLM 颤音琴'),
    6: (0, 24, '尼龙吉他'), 7: (0, 32, '原声贝斯'), 8: (0, 89, 'Warm Pad'), 9: (128, 40, '刷子鼓'),
}
WHO_CH = {'rin': 0, 'gemini': 1, 'claude': 2, 'astra': 3, 'deepseek': 4, 'glm': 5}
PAN = {0: 64, 1: 84, 2: 44, 3: 74, 4: 54, 5: 90, 6: 50, 7: 64, 8: 64, 9: 64}

events = []  # (秒, 类型, channel, note, velocity)


def note(t, dur, ch, n, v):
    if t >= END - 0.05:
        return
    v = int(max(1, min(127, v)))
    events.append((t, 1, ch, n, v))
    events.append((min(t + dur, END), 0, ch, n, 0))


def chord_tones(name, low, high):
    root, iv = CH[name]
    out = []
    for n in range(low, high + 1):
        if (n - root) % 12 in iv:
            out.append(n)
    return out


NBARS = math.ceil(END / BAR)

# ---------- 底乐 ----------
def bass_on(b):  # 贝斯从 Rin 盖章那一小节进来
    return b * BAR >= tl['stamps']['rin'] - BAR / 2


def drums_on(b):  # 刷子鼓从 Gemini 开口进来
    return b * BAR >= tl['stamps']['gemini'] - BAR / 2


for b in range(min(NBARS, FIN_BAR + 2)):
    t0 = b * BAR
    last = b == FIN_BAR + 1
    name = chord_at_bar(b)
    root, iv = CH[name]
    fade = 0.75 if b == 0 else 1.0
    # Pad：整小节和弦，轻轻铺着
    for n in chord_tones(name, 60, 72)[:3]:
        note(t0, BAR * (2 if last else 1), 8, n, 38 * fade)
    # 尼龙吉他：八分音符分解和弦
    low = root - 12 if root >= 55 else root
    third = low + iv[1] + 12
    fifth = low + iv[2]
    pattern = [low, fifth, third, fifth, low + 12, third, fifth, third]
    if last:  # 最后一小节：吉他扫一下就停
        for i, n in enumerate(sorted(set(pattern))):
            note(t0 + i * 0.03, BAR * 1.5, 6, n, 58)
    else:
        for i, n in enumerate(pattern):
            note(t0 + i * BEAT / 2, BEAT * 0.9, 6, n, (56 if i % 2 == 0 else 46) * fade)
    # 贝斯
    if bass_on(b) and last:
        note(t0, BAR * 1.5, 7, (root - 12 if root >= 50 else root), 66)
    elif bass_on(b):
        r = root - 12 if root >= 50 else root
        nxt = CH[chord_at_bar(b + 1)][0]
        nxt = nxt - 12 if nxt >= 50 else nxt
        approach = nxt - 1 if nxt > r else nxt + 2
        note(t0, BEAT * 1.4, 7, r, 68)
        note(t0 + BEAT * 1.5, BEAT * 0.45, 7, r + 7, 52)
        note(t0 + BEAT * 2, BEAT * 0.9, 7, r, 60)
        note(t0 + BEAT * 3, BEAT * 0.9, 7, approach, 54)
    # 刷子鼓
    if drums_on(b) and last:
        note(t0, BEAT, 9, 36, 44); note(t0, BAR, 9, 51, 40)
    elif drums_on(b):
        note(t0, BEAT, 9, 36, 40)                      # 轻踩
        for i in range(8):
            note(t0 + i * BEAT / 2, BEAT / 2, 9, 51, 30 if i % 2 else 38)  # Ride
        note(t0 + BEAT, BEAT, 9, 38, 42)               # 刷子拍在 2、4 拍
        note(t0 + BEAT * 3, BEAT, 9, 38, 46)
        if b % 2 == 0:
            note(t0, BAR, 9, 40, 26)                   # 刷子画圈

# ---------- 每个人的唱法 ----------
def sing(who, bar_notes, chord):
    """把主旋律的一小节换成这个人的唱法。返回 (拍, 时值, 音高, 力度)。"""
    out = []
    if who == 'rin':        # 钢琴：原样唱，第一拍加一个轻的和弦音
        out = [(b, d, n, 74) for b, d, n in bar_notes]
        for n in chord_tones(chord, 60, 67)[:2]:
            out.append((0, 1.5, n, 46))
    elif who == 'gemini':   # 长笛：高八度，蹦蹦跳跳，长音拆成八分音符加邻音
        for b, d, n in bar_notes:
            if d >= 1:
                out += [(b, .5, n + 12, 72), (b + .5, d - .5, n + 12 + (2 if (n % 12) not in (4, 11) else 1), 64)]
            else:
                out.append((b, d, n + 12, 70))
    elif who == 'claude':   # 单簧管：低八度，连起来，温温的
        merged = []
        for b, d, n in bar_notes:
            if merged and d < 1:
                continue
            merged.append((b, max(d, 1.0), n - 12))
        for i, (b, d, n) in enumerate(merged):
            nb = merged[i + 1][0] if i + 1 < len(merged) else 4
            out.append((b, nb - b, n, 66))
    elif who == 'astra':    # 电钢琴：稳，主旋律只在每拍开头出现，2、4 拍补和弦
        for b, d, n in bar_notes:
            if b == int(b):
                out.append((b, min(d, 1), n, 66))
        for beat in (1, 3):
            for n in chord_tones(chord, 62, 70)[:3]:
                out.append((beat + .5, .4, n, 44))
    elif who == 'deepseek':  # 拨弦：八分音符一路往上跳，抄近路似的快
        tones = chord_tones(chord, 67, 84)
        head = bar_notes[0][2]
        out.append((0, .5, head, 74))
        for i in range(1, 8):
            out.append((i * .5, .45, tones[(i * 2) % len(tones)], 62 if i % 2 else 68))
    elif who == 'glm':      # 颤音琴：慢悠悠，每半小节一个音，留很长的余音
        for half in (0, 2):
            cand = [x for x in bar_notes if half <= x[0] < half + 2]
            if cand:
                out.append((half, 2, cand[0][2] + 12, 62))
    return out


def next_grid(t, step):
    return math.ceil((t - 1e-6) / step) * step


# 谁在说话，谁唱：从开口后最近的一拍开始，说完那一拍结束
for c in tl['cards']:
    ch = WHO_CH[c['who']]
    start, end = next_grid(c['start'], BEAT), c['end']
    b = int(start // BAR)
    while b * BAR < end and b < FIN_BAR:
        ti = theme_index(b)
        if ti is not None:
            for beat, d, n, v in sing(c['who'], THEME[ti], chord_at_bar(b)):
                at = b * BAR + beat * BEAT
                if start - 1e-6 <= at < end:
                    note(at, d * BEAT * 0.95, ch, n, v)
        b += 1

# 签到盖章：自己的乐器"叮"地往上扬（卡在最近的八分音符上）
for who, ts in tl['stamps'].items():
    ch = WHO_CH[who]
    at = next_grid(ts, BEAT / 2)
    tones = chord_tones(chord_at_bar(int(at // BAR)), 72, 96)
    for i, n in enumerate(tones[:4]):
        note(at + i * BEAT / 4, BEAT * 1.2, ch, n, 84 - i * 4)

# 升华：GLM 说完以后，六个人一起唱主旋律最后两小节（F 和弦落地）
fin = FIN_BAR * BAR
for who, ch in WHO_CH.items():
    shift = {'rin': 0, 'gemini': 12, 'claude': -12, 'astra': 0, 'deepseek': 12, 'glm': 12}[who]
    for k in (6, 7):
        for beat, d, n in THEME[k]:
            note(fin + (k - 6) * BAR + beat * BEAT, d * BEAT * (1.6 if k == 7 else 0.95), ch, n + shift, 80)

# ---------- 写 MIDI ----------
mid = mido.MidiFile(ticks_per_beat=480)
track = mido.MidiTrack(); mid.tracks.append(track)
track.append(mido.MetaMessage('set_tempo', tempo=mido.bpm2tempo(BPM)))
track.append(mido.MetaMessage('track_name', name='Opening - Claude trial'))
for ch, (bank, preset, name) in PROG.items():
    if ch != 9:
        track.append(mido.Message('control_change', channel=ch, control=0, value=bank))
    track.append(mido.Message('program_change', channel=ch, program=preset))
    track.append(mido.Message('control_change', channel=ch, control=10, value=PAN[ch]))
evs = sorted(events, key=lambda e: (e[0], e[1]))
last_tick = 0
for t, kind, ch, n, v in evs:
    tick = int(round(t / BEAT * 480))
    msg = mido.Message('note_on' if kind else 'note_off', channel=ch, note=n, velocity=v)
    msg.time = tick - last_tick; last_tick = tick
    track.append(msg)
mid.save(HERE / '开场.mid')

# ---------- 渲染 ----------
syn = tinysoundfont.Synth(samplerate=SR, gain=-3)
sfid = syn.sfload(str(SF2))
for ch, (bank, preset, name) in PROG.items():
    syn.program_select(ch, sfid, bank, preset, ch == 9)
    syn.control_change(ch, 10, PAN[ch])
    syn.control_change(ch, 7, 100)
chunks, now = [], 0
for t, kind, ch, n, v in evs:
    s = int(t * SR)
    if s > now:
        chunks.append(np.frombuffer(syn.generate(s - now), dtype=np.float32).copy()); now = s
    if kind:
        syn.noteon(ch, n, v)
    else:
        syn.noteoff(ch, n)
total = int(END * SR)
if total > now:
    chunks.append(np.frombuffer(syn.generate(total - now), dtype=np.float32).copy())
audio = np.concatenate(chunks).reshape(-1, 2)

# 一点房间混响：衰减的噪声当冲激响应
rng = np.random.default_rng(7)
ir_len = int(1.6 * SR)
ir = rng.standard_normal((ir_len, 2)) * np.exp(-np.linspace(0, 7, ir_len))[:, None]
ir /= np.abs(ir).sum(axis=0) / 3
from scipy.signal import fftconvolve
wet = np.stack([fftconvolve(audio[:, i], ir[:, i])[:len(audio)] for i in range(2)], axis=1)
mix = audio * 0.85 + wet * 0.22
# 结尾淡出
fade = int(TAIL * SR)
mix[-fade:] *= np.linspace(1, 0, fade)[:, None] ** 1.5
mix /= np.abs(mix).max() / 0.7  # 约 -3 dBFS
pcm = (mix * 32767).astype(np.int16)
with wave.open(str(HERE / '开场.wav'), 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
print('ok', f'{END:.1f}s', len(evs) // 2, 'notes', '升华从', f'{fin:.1f}s')
