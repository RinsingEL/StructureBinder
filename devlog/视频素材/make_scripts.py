"""从逐句配画面表生成第三、四、五段的动画脚本（actN_script.js）。

只用留 ✓ 的行；字幕按"／"分屏；表情按标准名映射到立绘文件。
白板上的画面事件（mark）写在下面的 MARKS 里：行号 → {第几屏: 事件名}，
事件名由各段的 html 解释。改了配画面表或 MARKS 以后重新运行本脚本，不要手改生成的文件。
"""
import json, pathlib

HERE = pathlib.Path(__file__).resolve().parent
TABLE = HERE / '逐句配画面表.md'
WHO = {'Rin': 'rin', 'Gemini': 'gemini', 'Claude': 'claude', 'Astra': 'astra', 'DeepSeek': 'deepseek', 'GLM': 'glm', '全员': 'all'}
FACE = {'招呼': 'greet', '开心': 'talk_smile', '讲解': 'talk_smile', '反应': 'd4', '吐槽': 'd4',
        '自嘲': 'wry', '感动': 'moved', '提问': 'think', '认真': 'think', '默认': 'listen'}

MARKS = {
    3: {
        '3-1': {0: 'sea', 1: 'aier'},
        '3-2': {0: 'others', 1: 'fill', 3: 'erase'},
        '3-4': {0: 'tl', 1: 'n2601'},
        '3-5': {0: 'v52', 1: 'methods'},
        '3-9': {1: 'n0409'},
        '3-10': {1: 'early'},
        '3-12': {1: 'fix'},
        '3-13': {0: 'note'},
        '3-15': {0: 'poly'},
        '3-16': {0: 'polyfill', 2: 'polyfail'},
        '3-19': {0: 'jigsaw'},
        '3-20': {0: 'paint', 2: 'shatter'},
        '3-21': {2: 'three'},
        '3-22': {0: 'n2606', 1: 'n1020'},
        '3-23': {0: 'n2609'},
        '3-24': {0: 'ds'},
        '3-25': {0: 'glmtest'},
        '3-26': {0: 'n2610'},
        '3-27': {1: 'd4old', 2: 'd4marked'},
        '3-28': {0: 'manual'},
        '3-29': {0: 'rewrite', 2: 'compare'},
        '3-31': {0: 'life'},
        '3-32': {0: 'tlall'},
        '3-33': {1: 'end'},
    },
    4: {
        '4-1': {0: 'title'},
        '4-3': {0: 'harbor'},
        '4-4': {0: 'fields'},
        '4-10': {0: 'stamps'},
        '4-13': {0: 'day'},
        '4-14': {0: 'caravan'},
        '4-15': {0: 'dayback', 1: 'courtyard'},
        '4-19': {0: 'night'},
        '4-21': {0: 'wish'},
        '4-22': {0: 'gears'},
        '4-23': {0: 'config', 1: 'machine'},
        '4-24': {0: 'ladder', 1: 'notice', 2: 'race'},
        '4-25': {0: 'another'},
        '4-27': {0: 'valley', 2: 'mods'},
        '4-30': {0: 'sketch'},
        '4-33': {0: 'clues'},
        '4-35': {1: 'rings', 2: 'merchant'},
        '4-36': {0: 'traveler'},
        '4-37': {0: 'finale', 1: 'finale2'},
    },
    5: {
        '5-1': {0: 'cover'},
        '5-7': {0: 'bye'},
    },
}
SECTION = {3: ('## 第三段', '## 第四段'), 4: ('## 第四段', '## 第五段'), 5: ('## 第五段', None)}


def build(act):
    text = TABLE.read_text(encoding='utf-8')
    start, end = SECTION[act]
    part = text[text.index(start):text.index(end) if end else len(text)]
    lines = []
    for row in part.splitlines():
        if not row.startswith(f'| {act}-'):
            continue
        cols = [c.strip() for c in row.strip('|').split('|')]
        num, who, sub, face, pic, keep = cols[:6]
        if keep != '✓' or who not in WHO:
            continue
        cards = [c.strip() for c in sub.split('／') if c.strip()]
        faces = [FACE.get(f.strip(), 'talk_smile') for f in face.split('→')]
        marks = MARKS.get(act, {}).get(num, {})
        out = []
        for k, c in enumerate(cards):
            card = {'text': c, 'face': faces[-1] if (len(faces) > 1 and k == len(cards) - 1) else faces[0]}
            if k in marks:
                card['mark'] = marks[k]
            out.append(card)
        lines.append({'id': num, 'who': WHO[who], 'cards': out})
    return lines


for act in (3, 4, 5):
    lines = build(act)
    folder = HERE / {3: '第三段', 4: '第四段', 5: '第五段'}[act]
    folder.mkdir(exist_ok=True)
    (folder / f'act{act}_script.js').write_text(
        '// 由 ../make_scripts.py 从逐句配画面表生成，不要手改\nwindow.SCRIPT = ' +
        json.dumps({'lines': lines}, ensure_ascii=False, indent=1) + ';\n', encoding='utf-8')
    marks = [c['mark'] for l in lines for c in l['cards'] if 'mark' in c]
    print(act, len(lines), 'lines', sum(len(l['cards']) for l in lines), 'cards; marks:', ' '.join(marks))
