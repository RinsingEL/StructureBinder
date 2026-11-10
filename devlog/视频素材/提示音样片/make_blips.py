# 生成 Rin 台词的"提示音"音轨：每两三个字响一声，标点处停一下；三种音色
import json, wave, sys, numpy as np, subprocess
SR = 48000
spans = json.load(open('spans.json'))
durs = [float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',f'clip{i}.mp4'])) for i in range(len(spans))]
total = sum(durs)
PUNCT = set('，。！？、：；～…,.!?:;~"“”（）() ')
SCALE = [0, 2, 4, 7, 9, 12]  # 五声音阶，听起来像说话的起伏

def tone(kind, f, gentle):
    if kind == 'marimba':   # 木琴：短、圆
        d = 0.16; t = np.arange(int(SR*d))/SR
        y = np.sin(2*np.pi*f*t) + 0.25*np.sin(2*np.pi*f*4*t)*np.exp(-t*60)
        env = np.minimum(1, t/0.003) * np.exp(-t*(14 if gentle else 22))
    elif kind == 'musicbox':  # 八音盒：亮、带一点余音
        d = 0.4; t = np.arange(int(SR*d))/SR; f = f*2
        y = np.sin(2*np.pi*f*t) + 0.35*np.sin(2*np.pi*f*2.76*t)*np.exp(-t*20) + 0.15*np.sin(2*np.pi*f*5.4*t)*np.exp(-t*35)
        env = np.minimum(1, t/0.002) * np.exp(-t*(6 if gentle else 9))
    else:  # bubble：软软的"啵"，音高往下滑
        d = 0.12; t = np.arange(int(SR*d))/SR
        glide = f*(1 + 0.6*np.exp(-t*60))
        y = np.sin(2*np.pi*np.cumsum(glide)/SR)
        env = np.minimum(1, t/0.006) * np.exp(-t*(20 if gentle else 30))
    return y*env

def track(kind):
    out = np.zeros(int(SR*(total+0.5)))
    off = 0.0
    for i, sp in enumerate(spans):
        gentle = sp['id'] == '4-21'          # 说心愿：慢一点、低一点、轻一点
        question = sp['text'].rstrip().endswith('？')
        text = sp['text']; card0 = off + 0.4
        n = len(text.replace('\n', ''))
        step = 1/6
        every = 3 if gentle else 2
        k = 0; count = 0
        base = 330 if gentle else 392       # Rin 的基准音
        for j, ch in enumerate(text):
            tt = card0 + 0.08 + j*step
            if ch in PUNCT: count = 0; continue
            if count % every == 0:
                semi = SCALE[(ord(ch)*7 + j) % len(SCALE)] - 2
                if question and j > n - 6: semi += 4 + (j - (n-6))   # 问句句尾往上扬
                f = base * 2**(semi/12)
                y = tone(kind, f, gentle) * (0.16 if gentle else 0.22)
                a = int(tt*SR); out[a:a+len(y)] += y[:max(0, len(out)-a)]
            count += 1
        off += durs[i]
    # 一点点房间混响，让声音不那么干
    rev = out.copy()
    for dl, g in [(0.031, .25), (0.047, .18), (0.071, .12)]:
        s = int(dl*SR); rev[s:] += out[:-s]*g
    rev /= max(1e-9, np.abs(rev).max()) / 0.5
    return rev

for kind in ['marimba', 'musicbox', 'bubble']:
    y = (track(kind) * 32767).astype(np.int16)
    with wave.open(f'{kind}.wav', 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(y.tobytes())
print('ok', total)
