"""Show accepted D4 geometry in the workbench without running another layout planner.

Horizontal geometry is frozen. D3-interpolated heights are a display estimate,
not an engineering/placement result. Imported scenes are read-only.
"""
import argparse
import hashlib
import math
from pathlib import Path

import workbench as wb


def height_sampler(field):
    step = field['cellStepBlocks']
    cells = {(c['blockMinX'], c['blockMinZ']): c for c in field['cells']}
    xs, zs = sorted({x for x, _ in cells}), sorted({z for _, z in cells})

    def sample(x, z):
        xx = max(xs[0], min(xs[-1], math.floor(x / step) * step))
        zz = max(zs[0], min(zs[-1], math.floor(z / step) * step))
        fx, fz = max(0, min(1, (x - xx) / step)), max(0, min(1, (z - zz) / step))
        h = lambda dx, dz: cells[(min(xs[-1], xx + dx), min(zs[-1], zz + dz))]['elevation']
        return round((h(0, 0) * (1 - fx) + h(step, 0) * fx) * (1 - fz)
                     + (h(0, step) * (1 - fx) + h(step, step) * fx) * fz)
    return sample


def import_preview(source, output=wb.OUTPUT):
    source, output = Path(source), Path(output)
    blueprint = wb.read(source / 'city_blueprint.json')
    trace = wb.read(source / 'city_blueprint_submission_trace.json')
    commit = wb.read(source / 'city_blueprint_geometry_commit.json')
    digest = 'sha256:' + hashlib.sha256((source / 'city_blueprint.json').read_bytes()).hexdigest()
    if trace['status'] != 'accepted' or trace['cityBlueprintHash'] != digest or commit['blueprintHash'] != digest:
        raise ValueError('D4 accepted blueprint and frozen geometry hashes do not match')
    layout = commit['result']['structureAnchorPlan']
    if wb.read(source / 'city_blueprint_last_valid_preview.json')['landscapeLayout'].get('instances'):
        raise ValueError('This importer does not yet render landscape parcels; no partial import was published')
    terrain_file = source.parent / 'land_use/land_use_terrain_field.json'
    field = wb.read(terrain_file)
    sample = height_sampler(field)
    assets = wb.catalog()
    by_ref = {a['assetId']: a for a in assets}
    districts = blueprint['districtDesigns']
    owner = {g: d['districtId'] for d in districts for g in d['groupIds']}
    names = {'teaching': '教学区', 'library': '藏书阅读区', 'observation': '观测区',
             'living': '生活区', 'reception': '接待区'}
    rotation = {'NONE': 0, 'CLOCKWISE_90': 90, 'CLOCKWISE_180': 180, 'COUNTERCLOCKWISE_90': 270}
    members, arrays, cells, models = [], [], {}, {}
    palette, palette_ids = [], {}

    def put(x, y, z, block, layer):
        if isinstance(block, str):
            name, props = wb.state(block)
            block = dict(name=name, properties=dict(props))
        if block['name'] in ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'):
            return
        key = (block['name'], tuple(sorted(block['properties'].items())))
        if key not in palette_ids:
            palette_ids[key] = len(palette)
            palette.append(block)
        cells[(x, y, z)] = (palette_ids[key], layer)

    footprints = [a['plannedFootprint'] for a in layout['anchors']]
    materials = blueprint.get('surfaceMaterials', {}).get('defaults', {}).get('groundAndRoad', {}).get('materials', {})
    road_block = materials.get('roadSurface', 'minecraft:stone_brick_slab')
    for road in layout.get('streetBands', []):
        b = road['bounds']
        for z in range(b['minZ'], b['maxZ'] + 1):
            for x in range(b['minX'], b['maxX'] + 1):
                if not any(f['minX'] <= x <= f['maxX'] and f['minZ'] <= z <= f['maxZ'] for f in footprints):
                    put(x, sample(x, z) + 1, z, road_block, 'infrastructure')
    for anchor in layout['anchors']:
        ref = anchor['blueprintStructureRef']
        if ref not in by_ref:
            raise ValueError('Missing authored NBT: ' + ref)
        if anchor.get('mirror', 'NONE') != 'NONE':
            raise ValueError('Mirror is not supported: ' + anchor['anchorId'])
        asset = by_ref[ref]
        if ref not in models:
            models[ref] = wb.read_structure(Path(asset['path']) / 'structure.nbt')
        model = models[ref]
        if model['sha256'] != asset['nbtSha256']:
            raise ValueError('Author NBT hash mismatch: ' + ref)
        if model['size'] != [anchor['rawSize'][k] for k in ('width', 'height', 'depth')]:
            raise ValueError('Frozen template dimensions changed: ' + ref)
        turn = rotation[anchor['rotation']]
        ox, oz = anchor['anchorBlock']['x'], anchor['anchorBlock']['z']
        w, h, d = model['size']
        width, depth = (d, w) if turn in (90, 270) else (w, d)
        f = anchor['plannedFootprint']
        if [ox, oz, ox + width - 1, oz + depth - 1] != [f[k] for k in ('minX', 'minZ', 'maxX', 'maxZ')]:
            raise ValueError('Frozen footprint/rotation mismatch: ' + anchor['anchorId'])
        y = sample(ox, oz)
        base = y + 1 - anchor.get('groundPlaneY', asset['groundPlane'])
        group, ident = anchor['placementGroupId'], anchor['anchorId']
        member = dict(memberId=ident, arrayId=ident, assetId=ref, originBlock=dict(x=ox, z=oz),
                      rotation=turn, width=width, depth=depth, height=h, surfaceY=y, placementBaseY=base,
                      districtId=owner[group], compositionPath=['group:' + group], nbtSha256=model['sha256'])
        members.append(member)
        arrays.append(dict(arrayId=ident, function=asset['name'], algorithm='GRID', count=1,
                           anchorBlock=dict(x=ox, z=oz), rotation=turn, coreAssetId=ref))
        for block in model['blocks']:
            x, yy, z = block['pos']
            rx, rz = x, z
            if turn == 90: rx, rz = d - 1 - z, x
            elif turn == 180: rx, rz = w - 1 - x, d - 1 - z
            elif turn == 270: rx, rz = z, w - 1 - x
            put(ox + rx, base + yy, oz + rz, wb.rotate_state(model['palette'][block['state']], turn), 'building:' + ident)
    if not cells:
        raise ValueError('No geometry to import')
    lo = [min(p[i] for p in cells) for i in range(3)]
    hi = [max(p[i] for p in cells) for i in range(3)]
    world = dict(origin=lo, size=[hi[i] - lo[i] + 1 for i in range(3)], palette=palette,
                 blocks=[dict(pos=[p[i] - lo[i] for i in range(3)], state=v[0], layer=v[1]) for p, v in cells.items()])
    display_districts = []
    for district in districts:
        local = [m for m in members if m['districtId'] == district['districtId']]
        core = next(a['anchorId'] for a in layout['anchors'] if a['placementGroupId'] == district['coreGroupId']
                    and a['blueprintStructureRef'] == district['coreStructureRef'])
        display_districts.append(dict(districtId=district['districtId'], name=names.get(district['districtId'], district['districtId']),
                                      coreMemberId=core, arrayIds=[m['arrayId'] for m in local], platform=False))
    compositions = [dict(compositionId='group:' + g, algorithm='GRID', anchorBlock=next(a['anchorBlock'] for a in arrays if a['arrayId'] == local[0]['arrayId']),
                         memberIds=[m['memberId'] for m in local])
                    for g in owner for local in [[m for m in members if 'group:' + g in m['compositionPath']]] if local]
    scene = dict(seed=blueprint['generationSeed'], greenery=False, arrays=arrays, compositions=compositions,
                 districts=display_districts, connections=[], importedD4=True)
    old = wb.read(output / 'state.json') if (output / 'state.json').exists() else {'revision': 0}
    revision = old['revision'] + 1
    folder = output / 'revisions' / f'r{revision:04d}-d4'
    folder.mkdir(parents=True, exist_ok=False)
    water = [c for c in field['cells'] if c['water']]
    context = dict(schema='campus_terrain_context.v1', bounds=field['planningBounds'], step=field['cellStepBlocks'],
                   samples=[[c['blockMinX'], c['blockMinZ'], c['elevation'], c['water']] for c in field['cells']],
                   detailedBounds=dict(minX=0, maxX=-1, minZ=0, maxZ=-1),
                   waterSampleCount=len(water), waterDisplayY=max((c['elevation'] for c in water), default=0) + 1,
                   detail='冻结 D4 坐标与旋转；建筑和道路高程按 D3 插值示意，未生成台地、支撑或施工结果。')
    metadata = dict(revision=revision, scene=scene, members=members, terraces=[], fixtures=[],
                    roadPlan=layout.get('cityMainRoadPlan', {}), streetReview=layout.get('designReview', {}),
                    terrain=dict(mode='D4_FROZEN_PREVIEW', detail=context['detail']), generationReady=False,
                    importedD4=True, evidenceDirectory=str(folder), sourceDirectory=str(source), sourceBlueprintHash=digest,
                    summary=dict(buildings=len(members), terraces=0, roadSegments=len(layout.get('streetBands', [])),
                                 lamps=0, wallBlocks=0, fixtures=0, blockCount=len(cells), owners=0))
    for name, data in [('world.json', world), ('metadata.json', metadata), ('scene.json', scene), ('terrain-context.json', context)]:
        wb.write(folder / name, data)
    wb.write(folder / 'import-evidence.json', dict(sourceBlueprintHash=digest, coordinatesPreserved=True,
             rotationsPreserved=True, retainedBuildings=len(members), heightMode='D3_DISPLAY_ESTIMATE', constructionExecuted=False))
    wb.write(output / 'state.json', dict(revision=revision, directory=str(folder), scene=scene))
    return metadata


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    args = parser.parse_args()
    result = import_preview(args.source)
    print(result['summary'])
