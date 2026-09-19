"""Prepare six medieval gap-filling assets for manual entrance review; never install them."""
import argparse
import array
import ast
import base64
import gzip
import hashlib
import json
import shutil
from pathlib import Path

import nbtlib
import numpy as np
from litemapy import Region
from PIL import Image, ImageDraw, ImageFont


def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf8')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    if out.exists():
        raise ValueError('Output must be a new directory; existing annotations are never overwritten')
    common = '城市,房屋/中世纪房屋素材 构建/中世纪构建包1/'
    selection = [
        ('行政', '城市,房屋/各式各样的房屋2/中世纪/bg33 城镇市政厅.litematic'),
        ('教育文化', common + '中世纪小屋12 图书馆.litematic'),
        ('餐饮', common + '中世纪小屋11 酒馆.litematic'),
        ('住宿', '城市,房屋/中世纪房屋素材 构建/中世纪构建包3/a8餐馆+旅店.litematic'),
        ('宗教', common + '中世纪小屋14 教堂.litematic'),
        ('防御', common + '中世纪小屋13 瞭望塔.litematic'),
    ]
    old = json.loads((args.baseline / 'manifest.json').read_text(encoding='utf8'))
    old_hashes = {r['sourceSha256'] for r in old}
    old_names = {r['name'] for r in old}
    sources = []
    for category, relative in selection:
        path = args.source / relative
        raw = nbtlib.load(path)
        assert int(raw['MinecraftDataVersion']) <= 3465, path
        assert len(raw['Regions']) == 1, path
        assert digest(path) not in old_hashes and path.stem not in old_names, path
        sources.append((category, path, raw))
    # Reuse only the independently checked decoder and renderer, not the old builder's mutations.
    legacy = args.baseline / 'tools/prepare.py'
    tree = ast.parse(legacy.read_text(encoding='utf8'))
    helpers = ast.Module(body=[n for n in tree.body if isinstance(n, ast.FunctionDef)
                              and n.name in {'color', 'render', 'unpack'}], type_ignores=[])
    namespace = dict(np=np, Image=Image, ImageDraw=ImageDraw)
    exec(compile(helpers, str(legacy), 'exec'), namespace)
    (out / 'previews').mkdir(parents=True)
    cfg = out / 'city_config'
    cfg.mkdir()
    rows, templates, editor = [], [], []
    for index, (category, path, raw) in enumerate(sources, 121):
        ident = str(index)
        region = next(iter(raw['Regions'].values()))
        source = namespace['unpack'](region)
        nbt = Region.from_nbt(region).to_structure_nbt(mc_version=int(raw['MinecraftDataVersion']))
        original = out / 'litematic' / path.relative_to(args.source)
        target = out / 'nbt' / (ident + '_' + path.stem + '.nbt')
        original.parent.mkdir(parents=True, exist_ok=True)
        target.parent.mkdir(exist_ok=True)
        shutil.copy2(path, original)
        nbt.save(target)
        loaded = nbtlib.load(target)
        size = tuple(map(int, loaded['size']))
        assert size == source.shape
        src_keys = [json.dumps(s.unpack(), sort_keys=True) for s in region['BlockStatePalette']]
        dst_keys = [json.dumps(s.unpack(), sort_keys=True) for s in loaded['palette']]
        mapping = [src_keys.index(s) for s in dst_keys]
        restored = np.full(size, -1, dtype=np.int32)
        for block in loaded['blocks']:
            pos = tuple(map(int, block['pos']))
            assert restored[pos] == -1
            restored[pos] = mapping[int(block['state'])]
        assert np.array_equal(source, restored)
        assert len(loaded['entities']) == len(region['Entities'])
        assert sum('nbt' in b for b in loaded['blocks']) == len(region['TileEntities'])
        preview = out / 'previews' / (ident + '.png')
        namespace['render'](source, region['BlockStatePalette'], preview)
        ref = 'city_assets:selected_20260919/asset_' + ident
        row = dict(id=ident, name=path.stem, category=category, source=str(path),
                   sourceSha256=digest(path), nbtSha256=digest(target), dataVersion=int(raw['MinecraftDataVersion']),
                   sizeXYZ=size, nbt=target.relative_to(out).as_posix(),
                   original=original.relative_to(out).as_posix(), preview=preview.relative_to(out).as_posix(),
                   blockStateRoundtrip='PASS', entityCount=len(loaded['entities']),
                   blockEntityCount=len(region['TileEntities']),
                   pendingTicks=len(region.get('PendingBlockTicks', [])) + len(region.get('PendingFluidTicks', [])))
        rows.append(row)
        functions = [category, '餐饮'] if category == '住宿' else [category]
        templates.append(dict(id=ident, displayName=path.stem, templateRef=ref,
            sourceNbt='../' + row['nbt'], sourceOriginal='../' + row['original'],
            sourceSha256=row['nbtSha256'], sourceDataVersion=row['dataVersion'],
            rawSize=dict(width=size[0], height=size[1], depth=size[2]), buildingSemantic=category,
            functionTerms=functions, styleTerms=['中世纪'], categoryConfirmedByUser=False,
            styleConfirmedByUser=False, entrancesConfirmedByUser=False, roadEntrances=[],
            noRoadEntrance=False, runtimeValidated=False, preview='../' + row['preview'],
            pendingFields=['category_review', 'style_review', 'entrances', 'runtime_metadata']))
        w, h, d = size
        voxels = array.array('H', [0]) * (w * h * d)
        palette = [{'name': 'minecraft:air', 'props': {}}] + [
            {'name': str(s['Name']), 'props': {str(k): str(v) for k, v in s.get('Properties', {}).items()}}
            for s in loaded['palette']]
        for b in loaded['blocks']:
            x, y, z = map(int, b['pos'])
            voxels[(y * d + z) * w + x] = int(b['state']) + 1
        editor.append(dict(id=ident, name=path.stem, category=category, templateRef=ref,
            sha=row['nbtSha256'], size=size, preview='../' + row['preview'], palette=palette,
            voxels=base64.b64encode(gzip.compress(voxels.tobytes(), mtime=0)).decode(), candidates=[]))
        print(ident, path.stem, size, 'PASS', flush=True)
    write(out / 'manifest.json', rows)
    write(cfg / 'city_assets.json', dict(schema='city_asset_integration_source.v0.1',
        status='pending_manual_review', targetMinecraftVersion='1.20.1', selectedCount=len(templates), templates=templates))
    (cfg / 'entrance-editor-data.js').write_text('window.ASSETS=' + json.dumps(editor, ensure_ascii=False) + ';\n', encoding='utf8')
    js = (args.baseline / 'city_config/entrance-editor.js').read_text(encoding='utf8')
    js = js.replace('city-entrances-selected-20260912-v1', 'city-entrances-selected-20260919-additions')
    js = js.replace("name='城市素材_入口标注_'", "name='中世纪补充6_入口标注_'")
    (cfg / 'entrance-editor.js').write_text(js, encoding='utf8')
    page = (args.baseline / 'city_config/入口标注.html').read_text(encoding='utf8')
    page = page.replace('中世纪素材 · 入口标注', '中世纪补充 6 件 · 入口标注')
    (cfg / '入口标注.html').write_text(page, encoding='utf8')
    (out / 'index.html').write_text('<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=city_config/入口标注.html"><a href="city_config/入口标注.html">开始标注中世纪补充 6 件</a>', encoding='utf8')
    sheet = Image.new('RGB', (1140, 740), '#eeeee5')
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 18)
    for i, row in enumerate(rows):
        x, y = i % 3 * 380, i // 3 * 370
        sheet.paste(Image.open(out / row['preview']), (x, y))
        draw.text((x + 8, y + 330), row['id'] + ' ' + row['name'], font=font, fill='#263e36')
    sheet.save(out / 'previews/contact.jpg')
    write(out / 'verification.json', dict(selected=6, blockStateRoundtripPassed=6,
        payloadCountsPassed=6, newerDataVersionCount=0, duplicateSourceCount=0,
        gameplayValidated=False, installed=False, annotationsReviewed=False))
    (out / 'README.md').write_text('# 中世纪补充 6 件\n\n打开 city_config/入口标注.html，逐件标记后导出“中世纪补充6_入口标注”JSON。原 87 件及其进度保持不变；本批使用 121—126 独立 ID 和独立浏览器存储。\n\n补充行政、图书文化、餐饮、住宿、宗教和瞭望功能。瞭望塔为独立建筑，不是城墙模块。原投影保留；逐格方块状态核对通过，保留尺寸、空气与实体/方块实体数据，计划刻不能写入 structure NBT。数据版本均为 3465，但仍待游戏读取和实际落地验证。\n\n当前只是待审核素材，不会自动加入活动 87 件配置。分类是名称依据的建议，标注时如用途或风格不适合可在备注中说明。预览使用简化方块，不代表真实纹理。\n', encoding='utf8')


if __name__ == '__main__':
    main()
