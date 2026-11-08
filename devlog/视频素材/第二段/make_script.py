"""从逐句配画面表生成第二段的动画脚本 act2_script.js。

只读"第二段 · 看流程"那张表：留 ✓ 的行才用；字幕按"／"分屏；
表情按标准名映射到立绘文件；画面一栏里出现的第二幕拍名决定翻页。
"""
import json, re, pathlib

HERE = pathlib.Path(__file__).resolve().parent
TABLE = HERE.parent / '逐句配画面表.md'
WHO = {'Rin': 'rin', 'Gemini': 'gemini', 'Claude': 'claude', 'Astra': 'astra', 'DeepSeek': 'deepseek', 'GLM': 'glm'}
FACE = {'招呼': 'greet', '开心': 'talk_smile', '讲解': 'talk_smile', '反应': 'd4', '吐槽': 'd4',
        '自嘲': 'wry', '感动': 'moved', '提问': 'think', '认真': 'think', '默认': 'listen'}
BEATS = ['W1', 'W2', 'W3a', 'W3b', 'W3c', 'T1', 'T2', 'T3', 'T3-end', 'T4', 'D1', 'D1b', 'D1c', 'D1d', 'D1e',
         'D2', 'D3', 'D4', 'D5a', 'D5b', 'D6', 'D7', 'D-photos']
DURATION = [0, 6.5, 1.5, 1.5, 1.5, 3.0, 16.0, 16.0, 1.0, 3.8, 3.0, 2.2, 5.5, 4.5, 22.0, 4.2, 6.5, 4.5, 4.5, 6.5, 6.5, 24.0, 12.0]

def beat_tokens(cell):
    if 'W3' in cell and '高度' in cell:
        return ['W3a', 'W3b', 'W3c']
    found = re.findall(r'D-photos|T3-end|D1[b-e]|D5[ab]|[WTD]\d', cell)
    return [b for b in found if b in BEATS]

text = TABLE.read_text(encoding='utf-8')
part = text[text.index('## 第二段'):text.index('## 第三段')]
lines = []
for row in part.splitlines():
    if not row.startswith('| 2-'):
        continue
    cols = [c.strip() for c in row.strip('|').split('|')]
    num, who, sub, face, pic, keep = cols[:6]
    if keep != '✓' or who not in WHO:
        continue
    cards = [c.strip() for c in sub.split('／') if c.strip()]
    faces = [FACE.get(f.strip(), 'talk_smile') for f in face.split('→')]
    toks = beat_tokens(pic)
    out = []
    for k, c in enumerate(cards):
        card = {'text': c}
        # "A → B"：前面几屏用 A，最后一屏用 B
        card['face'] = faces[-1] if (len(faces) > 1 and k == len(cards) - 1) else faces[0]
        if toks:
            card['beat'] = toks[min(k * len(toks) // len(cards), len(toks) - 1)]
        out.append(card)
    lines.append({'id': num, 'who': WHO[who], 'cards': out})

(HERE / 'act2_script.js').write_text(
    '// 由 make_script.py 从逐句配画面表生成，不要手改\nwindow.ACT2 = ' +
    json.dumps({'beats': BEATS, 'durations': DURATION, 'lines': lines}, ensure_ascii=False, indent=1) + ';\n',
    encoding='utf-8')
print(len(lines), 'lines,', sum(len(l['cards']) for l in lines), 'cards')
