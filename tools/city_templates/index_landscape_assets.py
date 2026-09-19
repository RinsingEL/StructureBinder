"""Inspect landscape litematics and build a candidate browser, without installing assets."""
import argparse
import ast
import hashlib
import html
import json
from collections import Counter
from pathlib import Path

import nbtlib
import numpy as np
from PIL import Image, ImageDraw, ImageFont

GUIDANCE = {
    '梯田农场': ('通用农田', '各风格共享；使用连片阵列，作物与形状作为独立变体'),
    '自然喷泉': ('公园水景', '用于公园的独立装饰水景'),
    '游泳池': ('公园人工湖', '按公园人工湖使用，保留原素材名；材质匹配城市风格'),
    '码头灯塔': ('海岸建筑', '用于海岸建筑与滨水景观，按材质匹配城市风格'),
}


def style_terms(family, name):
    if family in ['梯田农场', '自然喷泉']:
        return []  # No invented civilization tag for shared scenery.
    mappings = [
        ('砂岩', ['沙漠']), ('石英', ['古典', '宫廷', '现代']),
        ('海晶石', ['海洋', '奇幻']), ('诡异木', ['奇幻']),
        ('铜', ['蒸汽朋克']), ('深板岩', ['中世纪', '哥特']),
        ('闪长岩', ['中世纪', '古典']), ('灰白色', ['中世纪', '古典']),
        ('石砖', ['中世纪']), ('苔石', ['中世纪', '遗迹']),
        ('橡木', ['中世纪', '北欧']), ('棕色', ['中世纪', '北欧'])]
    result = next((list(styles) for material, styles in mappings if material in name), [])
    if '爬满藤蔓' in name and '遗迹' not in result:
        result.append('遗迹')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--preview-helper', required=True, type=Path)
    args = parser.parse_args()
    out = args.output.resolve()
    if out.exists():
        raise ValueError('Use a fresh output directory')
    (out / 'previews').mkdir(parents=True)
    tree = ast.parse(args.preview_helper.read_text(encoding='utf8'))
    functions = ast.Module(body=[n for n in tree.body if isinstance(n, ast.FunctionDef)
                                and n.name in {'unpack', 'color', 'render'}], type_ignores=[])
    ns = dict(np=np, Image=Image, ImageDraw=ImageDraw)
    exec(compile(functions, str(args.preview_helper), 'exec'), ns)
    rows, seen = [], {}
    for index, p in enumerate(sorted(args.source.rglob('*.litematic')), 1):
        rel = p.relative_to(args.source)
        family = rel.parts[0]
        if family not in GUIDANCE:
            continue
        raw = nbtlib.load(p)
        regions = raw['Regions']
        bundle = '全部' in p.stem
        blocks = sorted({str(b['Name']) for region in regions.values() for b in region['BlockStatePalette']})
        category, usage = GUIDANCE[family]
        priority = category
        styles = style_terms(family, p.stem)
        size = None
        preview = None
        nonair = None
        counts = {}
        duplicate = None
        if not bundle and len(regions) == 1:
            region = next(iter(regions.values()))
            decoded = ns['unpack'](region)
            size = list(decoded.shape)
            states = [str(s['Name']) for s in region['BlockStatePalette']]
            counter = Counter()
            for state, count in zip(*np.unique(decoded, return_counts=True)):
                counter[states[int(state)]] += int(count)
            counts = dict(counter)
            nonair = sum(v for k, v in counter.items() if k not in ['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'])
            palette = [json.dumps(s.unpack(), sort_keys=True) for s in region['BlockStatePalette']]
            canonical_palette = sorted(set(palette))
            mapping = np.array([canonical_palette.index(s) for s in palette], dtype=np.int32)
            fingerprint = hashlib.sha256(json.dumps(size).encode() + ''.join(canonical_palette).encode() + mapping[decoded].tobytes()).hexdigest()
            duplicate = seen.get(fingerprint)
            seen.setdefault(fingerprint, f'L{index:03}')
            preview = f'previews/L{index:03}.png'
            ns['render'](decoded, region['BlockStatePalette'], out / preview)
        row = dict(id=f'L{index:03}', name=p.stem, family=family, series='/'.join(rel.parts[:-1]),
            category=category, priority=priority, suggestedUse=usage, styleTerms=styles,
            functionTerms=['农田' if family == '梯田农场' else '海岸灯塔' if family == '码头灯塔' else category], source=str(p), relativePath=rel.as_posix(),
            sourceSha256=hashlib.sha256(p.read_bytes()).hexdigest(), dataVersion=int(raw['MinecraftDataVersion']),
            regionCount=len(regions), sizeXYZ=size, nonAirBlocks=nonair, blocks=blocks, blockCounts=counts,
            entityCount=sum(len(r.get('Entities', [])) for r in regions.values()),
            blockEntityCount=sum(len(r.get('TileEntities', [])) for r in regions.values()),
            pendingTicks=sum(len(r.get('PendingBlockTicks', []))+len(r.get('PendingFluidTicks', [])) for r in regions.values()),
            bundle=bundle, preview=preview, blockGeometryDuplicateOf=duplicate,
            runtimeReady=False, entranceReviewed=not bundle, noRoadEntrance=not bundle, roadEntrances=[], seamReviewed=False)
        rows.append(row)
        if index % 50 == 0:
            print(f'Inspected and previewed {index} files', flush=True)
    singles = [r for r in rows if not r['bundle']]
    summary = dict(source=str(args.source), total=len(rows), individual=len(singles),
        bundles=sum(r['bundle'] for r in rows), byFamily=dict(Counter(r['family'] for r in singles)),
        versions=dict(Counter(r['dataVersion'] for r in rows)), byPriority=dict(Counter(r['priority'] for r in singles)),
        blockGeometryDuplicates=[dict(id=r['id'], duplicateOf=r['blockGeometryDuplicateOf']) for r in singles if r['blockGeometryDuplicateOf']],
        converted=False, installed=False, gameplayValidated=False)
    (out / 'assets.jsonl').write_text(''.join(json.dumps(r, ensure_ascii=False)+'\n' for r in rows), encoding='utf8')
    (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
    header = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>景观素材候选目录</title><style>body{font:16px system-ui;background:#eef0e8;color:#263d32;margin:24px}header{max-width:1100px}input,select{padding:10px;margin:5px;border:1px solid #acbbaa;border-radius:6px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(290px,1fr));gap:14px}article{background:#fffef8;border-radius:10px;padding:12px}img{width:100%;height:255px;object-fit:contain}h3{margin:6px 0}small{display:block;overflow-wrap:anywhere;color:#52665a}a{color:#236749}button{padding:8px}p{line-height:1.6}article[hidden]{display:none}</style><header><h1>景观素材候选目录</h1><p>保留通用农田、公园水景、公园人工湖和海岸建筑。温室、铁轨、齿轮已从本清单移出。预览来自原投影方块，使用近似材质色；205 个单件已由用户确认无道路入口；未转换成正式配置，接缝和游戏效果待验收。</p><p>CONTIGUOUS 只保证模板外框贴边，不保证内部田埂、水流和高差连续。整包展示文件保存在清单中，不作为单件候选。</p><input id="q" placeholder="搜索名称、作物、材质…"><select id="family"><option value="">全部类别</option>'''
    header += ''.join(f'<option>{html.escape(f)}</option>' for f in GUIDANCE)
    header += '</select><select id="priority"><option value="">全部用途</option>' + ''.join(f'<option>{html.escape(p)}</option>' for p in dict.fromkeys(r['priority'] for r in singles)) + '</select><span id="count"></span><p><a href="README.md">整理说明</a> · <a href="assets.jsonl">完整数据清单</a></p></header><div class="grid">'
    cards = []
    for r in singles:
        text = ' '.join([r['name'], r['family'], r['series'], r['priority']] + r['styleTerms'])
        image = f'<img loading="lazy" src="{r["preview"]}" alt="{html.escape(r["name"])}">' if r['preview'] else '<p>多区域素材，待独立预览</p>'
        dims = ' × '.join(map(str, r['sizeXYZ'])) if r['sizeXYZ'] else '多区域'
        cards.append(f'<article data-family="{html.escape(r["family"])}" data-priority="{r["priority"]}" data-search="{html.escape(text,quote=True)}">{image}<h3>{r["id"]} {html.escape(r["name"])}</h3><small>{r["priority"]} · {dims}（X/Y/Z）</small><p>{r["suggestedUse"]}</p><small>风格：{" / ".join(r["styleTerms"]) or "通用"} · 已确认无道路入口</small><small>{html.escape(r["relativePath"])}</small></article>')
    tail = '''</div><script>function filter(){let count=0;const q=document.getElementById('q').value.toLowerCase(),f=document.getElementById('family').value,p=document.getElementById('priority').value;document.querySelectorAll('article').forEach(a=>{a.hidden=!!((f&&a.dataset.family!==f)||(p&&a.dataset.priority!==p)||!a.dataset.search.toLowerCase().includes(q));if(!a.hidden)count++});document.getElementById('count').textContent=count+' 项'}['q','family','priority'].forEach(id=>document.getElementById(id).oninput=filter);filter();</script></html>'''
    (out / 'index.html').write_text(header + ''.join(cards) + tail, encoding='utf8')
    lines = ['# 景观素材整理', '', f'共 {len(rows)} 份，单件候选 {len(singles)} 份，整包展示 {summary["bundles"]} 份。原文件不修改，现有 93 件活动库不变。', '', '| 类型 | 单件数 | 用法 |', '| --- | ---: | --- |']
    for family, (_, usage) in GUIDANCE.items():
        lines.append(f'| {family} | {summary["byFamily"][family]} | {usage} |')
    lines += ['', '## 用户确认的用途', '',
        '- 35 个农田模块供各风格共享，作物与形状分别选择，不再按文明排除。',
        '- 自然喷泉用于公园装饰水景；泳池可以承担公园人工湖，原素材名保留便于追溯。',
        '- 码头灯塔用于海岸建筑，按材质匹配风格。',
        '- 温室、铁轨和齿轮从活动整理清单移出，原始文件不删除。', '',
        '## 材质与风格', '',
        '按用户确认方案使用现有 styleTerms 与 functionTerms；材质保留在原名中，不新增材质字段。风格依据名称初分，可多选；仅有颜色时不强行指定文明。农田和自然喷泉不限定风格，不新增通用文明标签。搜索框可检索沙漠、中世纪、北欧等风格。', '',
        '## 接入状态', '',
        '本轮只更新选材目录和用途标签，没有写入现有93件配置。CONTIGUOUS保证模板外框贴边；205 个单件统一 noRoadEntrance=true、roadEntrances=[]，入口状态已由用户确认，不再安排逐件入口标注。4 个合集仍不进入单件接入；素材接缝与地形另行核对。所有源DataVersion为3465，版本字段与简化预览不替代游戏落地验证。', '']
    (out / 'README.md').write_text('\n'.join(lines), encoding='utf8')
    # Compact shape sheet for the initial contiguous-array decision.
    featured = [r for r in singles if '/小麦/' in '/'+r['relativePath']]
    featured += [r for r in singles if r['family']=='自然喷泉']
    featured += [r for r in singles if r['family']=='游泳池' and r['name']=='a 砂岩游泳池1']
    sheet = Image.new('RGB', (1520, 1110), '#eef0e8')
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 18)
    for i, r in enumerate(featured):
        x,y=i%4*380,i//4*370
        sheet.paste(Image.open(out/r['preview']), (x,y))
        draw.text((x+8,y+332), r['id']+' '+r['name'], font=font, fill='#263d32')
    sheet.save(out/'previews/first_review.jpg')
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
