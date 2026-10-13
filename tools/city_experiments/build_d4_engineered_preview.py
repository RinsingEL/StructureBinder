"""Preview production terrace/road engineering around frozen accepted D4 anchors."""
import argparse
import copy
import subprocess
import time
from pathlib import Path

import workbench as wb
from template_greenery import load_catalog


def build(source):
    source = Path(source)
    previous = wb.read(wb.OUTPUT / 'state.json')
    old = wb.read(Path(previous['directory']) / 'metadata.json')
    if not old.get('importedD4') or Path(old['sourceDirectory']).resolve() != source.resolve():
        raise ValueError('Import this accepted D4 before engineering its preview')
    blueprint = wb.read(source / 'city_blueprint.json')
    layout = wb.read(source / 'city_blueprint_geometry_commit.json')['result']['structureAnchorPlan']
    anchors = {a['anchorId']: a for a in layout['anchors']}
    members = copy.deepcopy(old['members'])
    core_ids = {d['coreMemberId'] for d in old['scene']['districts']}
    for m in members:
        m['arrayId'] = anchors[m['memberId']]['placementGroupId']
        m['designRole'] = 'CORE' if m['memberId'] in core_ids else 'REQUIRED'
        m['issues'] = []
        m['terrainEvidence'] = {'waterAreaBlocks': 0}
        m['compositionPath'] = []
    arrays = []
    for g in blueprint['groups']:
        local = [m for m in members if m['arrayId'] == g['groupId']]
        arrays.append(dict(arrayId=g['groupId'], function=g['role'], anchorBlock=local[0]['originBlock'],
                           algorithm=g['algorithmProfileRef'].split(':')[1].upper(), count=len(local),
                           coreAssetId=local[0]['assetId'], rotation=local[0]['rotation'],
                           densityClass=g['densityClass'], **({'layout': g['layout']} if g.get('layout') else {})))
    compositions = []
    for c in blueprint['arrayCompositions']:
        ids = [c['centerGroupId'], *c['memberGroupIds']]
        compositions.append(dict(compositionId=c['compositionId'], algorithm=c['algorithmProfileRef'].split(':')[1].upper(),
                                 anchorBlock=c.get('anchorBlock', {'x': 0, 'z': 0}), memberIds=ids,
                                 layout=c.get('layout', {})))
        for m in members:
            if m['arrayId'] in ids:
                m['compositionPath'].append(c['compositionId'])
    districts = copy.deepcopy(old['scene']['districts'])
    foundation_groups = set(blueprint['outdoorPlan'].get('foundationGroupIds', []))
    for d in districts:
        d['arrayIds'] = next(x['groupIds'] for x in blueprint['districtDesigns'] if x['districtId'] == d['districtId'])
        d['platform'] = any(a in foundation_groups for a in d['arrayIds'])
    spaces = []
    for i, s in enumerate(layout.get('publicSpaces', [])):
        c = next((c for c in compositions if s['ownerGroupId'] in c['memberIds']), None)
        spaces.append(dict(nodeId=c['compositionId'] if c else s['ownerGroupId'], ownerArrayId=s['ownerGroupId'],
                           x=s['minX'], z=s['minZ'], width=s['maxX']-s['minX']+1, depth=s['maxZ']-s['minZ']+1,
                           purpose=s.get('purpose', 'public-space')))
    scene = dict(seed=blueprint['generationSeed'], arrays=arrays, compositions=compositions, districts=districts,
                 connections=[], greenery=True, importedD4=True, foundationMarginBlocks=8,
                 publicSpaces=[dict(nodeId=s['nodeId'], kind='GARDEN' if 'garden' in s['purpose'].lower() else 'PLAZA') for s in spaces],
                 terraceFeatures=True,
                 terraceStyles=[dict(styleId='d4_stone', name='D4 石砖台地', railBlock='minecraft:stone_brick_wall',
                    postBlock='minecraft:polished_andesite', capBlock='minecraft:stone_brick_slab',
                    postSpacing=10, minimumExposure=1, maximumCorners=0,
                    materials={'ground':'minecraft:stone_bricks','retainingWall':'minecraft:stone_bricks'})],
                 terraceGroups=[dict(terraceId='terrace:'+d['districtId'], districtId=d['districtId'],
                     coreMemberId=d['coreMemberId'], arrayIds=[a for a in d['arrayIds'] if a in foundation_groups],
                     styleRef='d4_stone') for d in districts if d['platform']])
    public = blueprint['outdoorPlan'].get('publicRealm', {})
    green = public.get('greenery')
    if green:
        scene['greeneryTemplates'] = {**green, 'catalog':'R02_arcane_reborn/greenery-pools.json',
            'maximumPlacements':green['maxPlacements']}
        scene['greeneryTemplates'].pop('maxPlacements')
        fringe = public.get('fringe')
        if fringe:
            scene['outerGreenery'] = {**fringe, 'maximumPlacements':fringe['maxPlacements']}
            scene['greeneryTemplates']['pools']['gapTrees'] = fringe['pools']['trees']
    folder = wb.OUTPUT / 'revisions' / f"r{previous['revision']+1:04d}-d4-engineered"
    if folder.exists():
        folder=folder.with_name(folder.name+"-"+str(time.time_ns()))
    folder.mkdir(parents=True, exist_ok=False)
    assets = wb.catalog()
    request = {**scene, 'assets':assets, 'terrainPath':str(source.parent/'land_use/land_use_terrain_field.json'),
               'frozenLayout':{'ok':True,'members':members,'scene':scene,'layoutSpaces':spaces},
               'frozenRoadBands':layout['streetBands'], 'frozenRoadPlan':layout.get('cityMainRoadPlan', {})}
    request['frozenNaturalArrays'] = [a['arrayId'] for a in arrays if a['arrayId'] not in foundation_groups]
    if green:
        request['greeneryCatalog'] = load_catalog(scene['greeneryTemplates'], assets)
        request['frozenLayout']['scene']['greeneryCatalog'] = request['greeneryCatalog']
    wb.write(folder/'request.json', request)
    java = 'C:/Program Files/Java/jdk-17/bin/java.exe'
    proc = subprocess.run([java,'-Xmx4g','-cp',(wb.ROOT/'build/city-workbench-classpath.txt').read_text(),
        'com.rinsing.geomantia.systems.city.application.CityCampusExperiment',str(folder/'request.json'),str(folder/'java-result.json')],
        cwd=wb.ROOT, capture_output=True)
    (folder/'java.log').write_bytes(proc.stdout+proc.stderr)
    if proc.returncode:
        raise RuntimeError('Engineering failed: '+str(folder/'java.log'))
    result = wb.read(folder/'java-result.json')
    if not result.get('ok'):
        raise ValueError(result.get('error', 'Rejected'))
    before = {m['memberId']:(m['originBlock'],m['rotation'],m['assetId']) for m in old['members']}
    after = {m['memberId']:(m['originBlock'],m['rotation'],m['assetId']) for m in result['members']}
    if before != after:
        raise ValueError('Frozen horizontal building geometry changed')
    if result['roadBands'] != layout['streetBands']:
        raise ValueError('Frozen horizontal road geometry changed')
    result['frozenImport'] = True
    result['_groundIndex'] = {(r[0],r[1]):r[2] for r in result['terrainColumns']}
    world = wb.compose(result, assets)
    del result['_groundIndex']
    meta = {**old, 'revision':previous['revision']+1, 'scene':scene, 'members':result['members'],
            'terraces':result['terraces'], 'fixtures':result.get('fixtures', []),
            'publicSpacePlans':result.get('publicSpacePlans', []), 'greeneryTemplatePlan':result.get('greeneryTemplatePlan'),
            'terrain':{'mode':'D4_ENGINEERED_PREVIEW','detail':'冻结 D4 平面布局；生产 Java 台地、支撑、坡道路面与配置绿化的离线模拟，不复现原生群系植被。'},
            'engineeredPreview':True, 'evidenceDirectory':str(folder),
            'summary':{**old['summary'],'terraces':len(result['terraces']),'owners':len(result['execution']),
                       'blockCount':len(world['blocks']), 'fixtures':len(result.get('fixtures', [])),
                       'wallBlocks':sum(r.get('foundationDiagnostics',{}).get('terraceEdgePreparedCount',0) for r in result['execution'])}}
    context = wb.read(Path(previous['directory'])/'terrain-context.json')
    context['detailedRegions'] = result['terrainDetailBounds']
    context['detail'] = meta['terrain']['detail']
    for name,data in [('world.json',world),('metadata.json',meta),('scene.json',scene),('terrain-context.json',context)]:
        wb.write(folder/name,data)
    wb.write(folder/'import-evidence.json',dict(coordinatesPreserved=True,rotationsPreserved=True,roadGeometryPreserved=True,
              originalBiomeVegetationGenerated=False,offlineEngineering=True,retainedBuildings=len(result['members'])))
    wb.write(wb.OUTPUT/'state.json',dict(revision=meta['revision'],directory=str(folder),scene=scene))
    return meta


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('source',type=Path);args=p.parse_args()
    print(build(args.source)['summary'])
