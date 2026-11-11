"""v5：从卡好拍子的画面时间表生成 hit_points.json。

画面已经吸附在 108 BPM 的拍子上（devlog/视频素材/common/beatgrid.js），
所以这里的速度是固定的，每个落点都正好在拍上，不再需要来回调速度。
先运行 devlog/视频素材/tools/export_timeline.js 导出两段的时间表。
"""
import json, pathlib
HERE = pathlib.Path(__file__).resolve().parent
BPM = 108; BEAT = 60 / BPM
op = json.loads((HERE / 'timeline_开场.json').read_text(encoding='utf-8'))
a2 = json.loads((HERE / 'timeline_第二段.json').read_text(encoding='utf-8'))
BEATS = ['W1', 'W2', 'W3a', 'W3b', 'W3c', 'T1', 'T2', 'T3', 'T3-end', 'T4', 'D1', 'D1b', 'D1c', 'D1d', 'D1e',
         'D2', 'D3', 'D4', 'D5a', 'D5b', 'D6', 'D7', 'D-photos']
TOPIC = {'W1': 'blank', 'W2': 'scan', 'W3a': 'terrain', 'T1': 'nations', 'T2': 'sites'}
END_AT = 'T3'  # 这一版先配到 T3 翻页之前，和 v4 覆盖的内容一样

b = lambda t: round(t / BEAT, 6)
events = []
seen = set()
for c in op['cards']:
    if c['who'] not in seen:
        seen.add(c['who'])
        events.append({'id': 'intro_' + c['who'], 'who': c['who'], 'speech': c['start'], 'hit': c['start'], 'beat': b(c['start']), 'kind': 'intro'})
off = op['duration']
end = None
for s in a2['beats']:
    if BEATS[s['index']] == END_AT:
        end = off + s['t']
prev = None
for c in a2['cards']:
    t = off + c['start']
    if end is not None and t >= end:
        break
    kind = TOPIC.get(c.get('beat'))
    first = abs(c['start'] - c['lineStart']) < 1e-6
    if kind or (first and c['who'] != prev):
        events.append({'id': c['id'], 'who': c['who'], 'speech': t, 'hit': t, 'beat': b(t), 'kind': kind or 'speaker'})
    if first:
        prev = c['who']
ev = {e['kind'] if e['kind'] != 'intro' else e['id']: e['beat'] for e in events}
first_speaker = next(e['beat'] for e in events if e['kind'] == 'speaker')
sections = [['title', 0], ['welcome', ev['intro_rin']], ['intros_warm', ev['intro_claude']], ['intros_playful', ev['intro_deepseek']],
            ['handoff', first_speaker]] + [[k, ev[k]] for k in ['blank', 'scan', 'terrain', 'nations', 'sites']]
nb = round(end / BEAT)
out = {'bpm': BPM, 'duration': nb * BEAT, 'events': events, 'anchors': [[0, 0], [nb, nb * BEAT]], 'sections': sections}
(HERE / 'hit_points.json').write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')
print(f"{out['duration']:.2f} 秒，{len(events)} 个落点；段落：", ' '.join(f'{k}@{v:.0f}' for k, v in sections))
