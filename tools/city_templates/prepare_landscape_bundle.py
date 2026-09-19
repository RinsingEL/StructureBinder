"""Convert the user-reviewed landscape selection without editing source blocks or worlds."""
import argparse
import ast
import hashlib
import json
import shutil
from pathlib import Path
import nbtlib
import numpy as np
from litemapy import Region


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf8')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['catalog', 'output', 'decoder']:
        p.add_argument('--'+name, type=Path, required=True)
    a = p.parse_args()
    rows = [json.loads(s) for s in a.catalog.read_text(encoding='utf8').splitlines()]
    rows = [r for r in rows if not r['bundle']]
    assert len(rows) == 205 and all(r['entranceReviewed'] and r['noRoadEntrance'] and not r['roadEntrances'] for r in rows)
    assert not a.output.exists()
    (a.output/'city_config').mkdir(parents=True)
    (a.output/'nbt').mkdir()
    helpers = ast.parse(a.decoder.read_text(encoding='utf8'))
    code = ast.Module(body=[n for n in helpers.body if isinstance(n, ast.FunctionDef) and n.name=='unpack'], type_ignores=[])
    ns = dict(np=np)
    exec(compile(code,str(a.decoder),'exec'),ns)
    # Use existing multi-valued styles: shared assets participate in every configured style.
    all_styles = ['中世纪'] + sorted(({t for r in rows for t in r['styleTerms']} | {'东方','日式','北欧'}) - {'中世纪'})
    templates, annotations, manifest = [], [], []
    for i,r in enumerate(rows,1):
        source = Path(r['source'])
        assert hashlib.sha256(source.read_bytes()).hexdigest() == r['sourceSha256']
        raw = nbtlib.load(source)
        assert int(raw['MinecraftDataVersion']) <= 3465 and len(raw['Regions'])==1
        region = next(iter(raw['Regions'].values()))
        decoded = ns['unpack'](region)
        converted = Region.from_nbt(region).to_structure_nbt(mc_version=int(raw['MinecraftDataVersion']))
        ident = r['id'].lower()
        target = a.output/'nbt'/(ident+'.nbt')
        converted.save(target)
        loaded = nbtlib.load(target)
        assert tuple(map(int,loaded['size'])) == decoded.shape
        original_keys = [json.dumps(s.unpack(),sort_keys=True) for s in region['BlockStatePalette']]
        mapping = [original_keys.index(json.dumps(s.unpack(),sort_keys=True)) for s in loaded['palette']]
        restored = np.full(decoded.shape,-1,dtype=np.int32)
        for b in loaded['blocks']:
            pos=tuple(map(int,b['pos']))
            assert restored[pos]==-1
            restored[pos]=mapping[int(b['state'])]
        assert np.array_equal(restored,decoded)
        assert len(loaded['entities'])==len(region['Entities'])
        assert sum('nbt' in b for b in loaded['blocks'])==len(region['TileEntities'])
        ref = 'city_assets:landscape_20260919/'+ident
        sha = hashlib.sha256(target.read_bytes()).hexdigest()
        w,h,d = decoded.shape
        templates.append(dict(id=ident,displayName=r['name'],templateRef=ref,
            sourceNbt='../nbt/'+ident+'.nbt',sourceOriginal=str(source),preview=str(a.catalog.parent/r['preview']),
            sourceSha256=sha,sourceDataVersion=int(raw['MinecraftDataVersion']),rawSize=dict(width=w,height=h,depth=d),
            buildingSemantic=r['functionTerms'][0],functionTerms=r['functionTerms'],
            styleTerms=r['styleTerms'] or all_styles,categoryConfirmedByUser=True,styleConfirmedByUser=True,
            entrancesConfirmedByUser=True,noRoadEntrance=True,roadEntrances=[],runtimeValidated=False))
        annotations.append(dict(id=ident,templateRef=ref,sourceSha256=sha,reviewed=True,noRoadEntrance=True,roadEntrances=[],
            note='用户明确确认本批景观均无道路入口'))
        manifest.append(dict(id=ident,source=str(source),sourceSha256=r['sourceSha256'],nbtSha256=sha,
            sizeXYZ=[w,h,d],blockStateRoundtrip='PASS',entityCount=len(loaded['entities']),
            blockEntityCount=len(region['TileEntities']),pendingTicks=r['pendingTicks']))
        if i%50==0: print('Converted and verified',i,flush=True)
    write(a.output/'city_config/city_assets.json',dict(schema='city_asset_integration_source.v0.1',
        status='user_approved_pending_runtime_metadata',targetMinecraftVersion='1.20.1',selectedCount=len(rows),templates=templates))
    write(a.output/'用户确认无入口.json',dict(schema='city_entrance_annotations.v1',templates=annotations))
    write(a.output/'manifest.json',manifest)
    write(a.output/'verification.json',dict(converted=205,blockStateRoundtripPassed=205,payloadCountsPassed=205,
        gameplayValidated=False,commonStyles=all_styles,sourceCatalogSha256=hashlib.sha256(a.catalog.read_bytes()).hexdigest()))
    print('PASS: 205 structure conversions, exact block-state roundtrip and no-entry confirmations')


if __name__=='__main__':
    main()
