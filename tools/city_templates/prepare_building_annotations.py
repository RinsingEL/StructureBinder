"""Prepare reviewed-style building candidates for entrance annotation; never install them."""
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
    parser.add_argument('--candidates', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    if out.exists():
        raise ValueError('Output must be a new directory; existing annotations are never overwritten')
    candidates = [json.loads(line) for line in args.candidates.read_text(encoding='utf8').splitlines()]
    selected = [r for r in candidates if r['status'] == '可进入选材审核']
    sources = []
    for r in selected:
        path = Path(r['source'])
        raw = nbtlib.load(path)
        assert int(raw['MinecraftDataVersion']) <= 3465 and len(raw['Regions']) == 1
        assert digest(path) == r['sourceSha256']
        sources.append((r, path, raw))
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
    for candidate, path, raw in sources:
        ident = candidate['id']
        category = candidate['functionTerms'][0] if candidate['functionTerms'] else '待定'
        styles = candidate['styleTerms']
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
        ref = 'city_assets:buildings_20260919/asset_' + ident.lower()
        row = dict(id=ident, name=path.stem, category=category, source=str(path),
                   sourceSha256=digest(path), nbtSha256=digest(target), dataVersion=int(raw['MinecraftDataVersion']),
                   sizeXYZ=size, nbt=target.relative_to(out).as_posix(),
                   original=original.relative_to(out).as_posix(), preview=preview.relative_to(out).as_posix(),
                   blockStateRoundtrip='PASS', entityCount=len(loaded['entities']),
                   blockEntityCount=len(region['TileEntities']),
                   pendingTicks=len(region.get('PendingBlockTicks', [])) + len(region.get('PendingFluidTicks', [])))
        rows.append(row)
        functions = candidate['functionTerms'] or [category]
        templates.append(dict(id=ident, displayName=path.stem, templateRef=ref,
            sourceNbt='../' + row['nbt'], sourceOriginal='../' + row['original'],
            sourceSha256=row['nbtSha256'], sourceDataVersion=row['dataVersion'],
            rawSize=dict(width=size[0], height=size[1], depth=size[2]), buildingSemantic=category,
            functionTerms=functions, styleTerms=styles, categoryConfirmedByUser=False,
            styleConfirmedByUser=True, entrancesConfirmedByUser=False, roadEntrances=[],
            noRoadEntrance=False, runtimeValidated=False, preview='../' + row['preview'],
            pendingFields=['category_review', 'entrances', 'runtime_metadata']))
        w, h, d = size
        voxels = array.array('H', [0]) * (w * h * d)
        palette = [{'name': 'minecraft:air', 'props': {}}] + [
            {'name': str(s['Name']), 'props': {str(k): str(v) for k, v in s.get('Properties', {}).items()}}
            for s in loaded['palette']]
        for b in loaded['blocks']:
            x, y, z = map(int, b['pos'])
            voxels[(y * d + z) * w + x] = int(b['state']) + 1
        editor.append(dict(id=ident, name=path.stem, category=styles[0] + ' / ' + category, styleTerms=styles, templateRef=ref,
            sha=row['nbtSha256'], size=size, preview='../' + row['preview'], palette=palette,
            voxels=base64.b64encode(gzip.compress(voxels.tobytes(), mtime=0)).decode(), candidates=[]))
        print(ident, path.stem, size, 'PASS', flush=True)
    write(out / 'manifest.json', rows)
    write(cfg / 'city_assets.json', dict(schema='city_asset_integration_source.v0.1',
        status='pending_manual_review', targetMinecraftVersion='1.20.1', selectedCount=len(templates), templates=templates))
    (cfg / 'entrance-editor-data.js').write_text('window.ASSETS=' + json.dumps(editor, ensure_ascii=False) + ';\n', encoding='utf8')
    js = (args.baseline / 'city_config/entrance-editor.js').read_text(encoding='utf8')
    js = js.replace('city-entrances-selected-20260912-v1', 'city-entrances-buildings-20260919')
    js = js.replace("name='城市素材_入口标注_'", "name='建筑补充173_入口标注_'")
    js = js.replace("styleTerms:['中世纪']", "styleTerms:[...new Set(assets.flatMap(a=>a.styleTerms))]")
    js = js.replace("a.category+' · 中世纪 · NBT", "a.category+' · NBT")
    (cfg / 'entrance-editor.js').write_text(js, encoding='utf8')
    page = (args.baseline / 'city_config/入口标注.html').read_text(encoding='utf8')
    page = page.replace('中世纪素材 · 入口标注', '建筑补充 173 件 · 入口标注')
    (cfg / '入口标注.html').write_text(page, encoding='utf8')
    (out / 'index.html').write_text('<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=city_config/入口标注.html"><a href="city_config/入口标注.html">开始标注建筑补充 173 件</a>', encoding='utf8')
    write(out / 'verification.json', dict(selected=len(rows), blockStateRoundtripPassed=len(rows),
        payloadCountsPassed=len(rows), gameplayValidated=False, installed=False, annotationsReviewed=False))
    write(out / 'deferred_candidates.json', [r for r in candidates if r['status'] != '可进入选材审核'])
    (out / 'README.md').write_text('# 建筑补充入口标注\n\n本批173件：中式81、日式21、东南亚1、沙漠70。23件新版留待适配，1件已收录不重复标记。打开 city_config/入口标注.html，逐件标注并定期导出JSON。已有298件及标注不变，本批使用独立存储。\n\n分类依据名字，功能不合适可在备注说明；风格按用户反馈分类。保留原投影和转换NBT，逐格核对方块与实体数量；游戏内读取与效果仍待验证，未加入正式配置。\n', encoding='utf8')



if __name__ == '__main__':
    main()
