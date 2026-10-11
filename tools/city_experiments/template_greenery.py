"""Deterministic weighted NBT greenery in authored planting regions. No theme branches."""
import hashlib
import json
import math
import random
from pathlib import Path
from studio.model import read_structure,sha256
from studio.server import CATALOG

SLOTS=('trees','gapTrees','shrubs','hedges','flowers','planters')


def load_catalog(config,assets):
    path=(CATALOG/config['catalog']).resolve()
    if not path.is_relative_to(CATALOG.resolve()) or path.name!='greenery-pools.json':
        raise ValueError('绿化池必须来自素材目录中的 greenery-pools.json。')
    data=json.loads(path.read_text(encoding='utf-8'))
    if data.get('schema')!='studio.greenery-pools.v1':raise ValueError('未知绿化池格式。')
    byid={a['assetId']:a for a in assets};entries={a['assetId']:a for a in data['assets']}
    if len(entries)!=len(data['assets']):raise ValueError('绿化池条目重复。')
    for slot,pool in config['pools'].items():
        if slot not in SLOTS or pool not in data['pools']:raise ValueError('未知绿化子池：'+pool)
        if not data['pools'][pool]:raise ValueError('绿化子池为空。')
        for row in data['pools'][pool]:
            if not isinstance(row['weight'],(int,float)) or not math.isfinite(row['weight']) or row['weight']<=0:raise ValueError('绿化权重必须为有限正数。')
            a=entries[row['assetId']];asset=byid.get(row['assetId'])
            if not asset or asset['consumer']!='infrastructure':raise ValueError('绿化必须引用独立配套。')
            folder=Path(asset['path'])
            if sha256(folder/'structure.nbt')!=a['nbtSha256'] or sha256(folder/'author.json')!=a['authorSha256']:raise ValueError('绿化池已过期，请重新导出：'+row['assetId'])
            if a['sizeXYZ']!=[asset['width'],asset['height'],asset['depth']] or a['groundPlaneY']!=asset['groundPlane']:raise ValueError('绿化池尺寸或地面基准不一致。')
            expected={'trees':'tree','gapTrees':'tree','shrubs':'shrub','hedges':'hedge','flowers':'flowers','planters':'planter'}[slot]
            if a['kind']!=expected:raise ValueError('绿化子池用途不匹配：'+slot)
    return data


def rotated(x,z,w,d,rotation):
    return [(x,z),(d-1-z,x),(w-1-x,d-1-z),(z,w-1-x)][rotation//90]


def placement(entry,model,x,y,z,rotation,cells,sources,allowed,protected,rotate_state):
    w,h,d=model['size'];ax,_,az=entry['anchorXYZ'];rx,rz=rotated(ax,az,w,d,rotation)
    ox,oz=x-rx,z-rz;base=y+1-entry['groundPlaneY'];rw,rd=(d,w) if rotation%180 else (w,d)
    footprint={(xx,zz) for xx in range(ox,ox+rw) for zz in range(oz,oz+rd)}
    if not footprint<=allowed or footprint&protected:return None
    # Reserve the entire canopy volume. Unwritten air is not a licence to overlap.
    if any((xx,yy,zz) in cells for xx,zz in footprint for yy in range(y+1,base+h)):return None
    writes=[]
    for b in model['blocks']:
        bx,by,bz=b['pos'];xx,zz=rotated(bx,bz,w,d,rotation);xx+=ox;zz+=oz;yy=base+by
        s=rotate_state(model['palette'][b['state']],rotation)
        if s['name'] in ('minecraft:air','minecraft:water','minecraft:lava'):return None
        existing=cells.get((xx,yy,zz))
        if yy<=y:
            if s['name'] not in ('minecraft:dirt','minecraft:grass_block') or not sources.get((xx,yy,zz),'').startswith('landscape:'):return None
            if not existing or existing['name'] not in ('minecraft:dirt','minecraft:grass_block'):return None
        if by==0:
            floor=cells.get((xx,yy-1,zz),{}).get('name','minecraft:air')
            if floor not in ('minecraft:grass_block','minecraft:dirt','minecraft:stone','minecraft:smooth_stone','minecraft:polished_andesite','minecraft:stone_bricks'):return None
        writes.append((xx,yy,zz,s))
    return writes,dict(x=ox,y=base,z=oz),footprint


def apply_template_greenery(result,cells,sources,put,assets,rotate_state):
    scene=result['scene'];config=scene.get('greeneryTemplates')
    if not config or not scene.get('greenery',True):return dict(enabled=False,placements=[])
    data=scene['greeneryCatalog'];entries={a['assetId']:a for a in data['assets']};byid={a['assetId']:a for a in assets};models={}
    protected=set()
    def block_rect(x,z,w,d,margin):
        protected.update((xx,zz) for xx in range(x-margin,x+w+margin) for zz in range(z-margin,z+d+margin))
    for m in result['members']:block_rect(m['originBlock']['x'],m['originBlock']['z'],m['width'],m['depth'],1)
    for f in result.get('fixtures',[]):block_rect(f['originBlock']['x'],f['originBlock']['z'],f['width'],f['depth'],1)
    for b in result['roadBands']:
        b=b['bounds'];block_rect(b['minX'],b['minZ'],b['maxX']-b['minX']+1,b['maxZ']-b['minZ']+1,2)
    for f in result['surfacePrintPlan']['featureCells']:
        if f['kind'] in ('ROAD_SLAB','ROAD_STAIR','BRIDGE_DECK'):block_rect(f['x'],f['z'],1,1,2)
    access=result.get('externalAccessPlan')
    if access:
        for c in access['columns']:block_rect(c['x'],c['z'],1,1,3)
        b=access['harborReservation'];block_rect(b['minX'],b['minZ'],b['maxX']-b['minX']+1,b['maxZ']-b['minZ']+1,0)
    domains=[]
    for plan in result.get('publicSpacePlans',[]):
        planting={(x,z):y for x,y,z,kind in plan['writes'] if kind in ('BED','LAWN')}
        if not planting:continue
        # Existing generated public axes remain open, including those absent from roadBands.
        for x,y,z,kind in plan['writes']:
            if kind=='WALK':block_rect(x,z,1,1,1)
        domains.append((plan,planting))
    placed=[];skipped={};reserved=set();low_reserved=set();spacing=config.get('spacing',{});layered=config.get('layeredPlanting',False)
    path_edges={(x+dx,z+dz) for x,z in protected for dx,dz in ((0,0),(3,0),(-3,0),(0,3),(0,-3),(5,0),(-5,0),(0,5),(0,-5))} if layered else set()
    for slot,default in (('trees',13),('planters',18),('hedges',7),('shrubs',6),('flowers',5)):
        slot_count=0;slot_limit=config.get('slotLimits',{}).get(slot,1000)
        active_reserved=low_reserved if layered and slot in ('shrubs','flowers') else reserved
        for plan,planting in domains:
            key='gapTrees' if slot=='trees' and plan['kind']=='GAP_LANDSCAPE' else slot
            pool=config['pools'].get(key)
            if not pool:continue
            allowed=set(planting);distance=spacing.get(slot,default);chosen=[]
            candidates=sorted(planting,key=lambda p:((0 if p in path_edges else 1) if layered and slot=='flowers' else 0,hashlib.sha256(f"{scene.get('seed',0)}:{plan['nodeId']}:{slot}:{p}".encode()).digest()))
            for x,z in candidates:
                if len(placed)>=config.get('maximumPlacements',400) or slot_count>=slot_limit:break
                if (x,z) in protected or (x,z) in active_reserved or any((x-qx)**2+(z-qz)**2<distance**2 for qx,qz in chosen):continue
                rng=random.Random(f"{scene.get('seed',0)}:{plan['nodeId']}:{slot}:{x}:{z}")
                if plan['kind']=='OUTER_LANDSCAPE':
                    distance_ratio=plan['distanceFromCity'][f'{x},{z}']/plan['width']
                    # Coarse patches produce a softer edge instead of a uniform belt.
                    patch=random.Random(f"{scene.get('seed',0)}:fringe:{x//7}:{z//7}").random()
                    if patch<.7*distance_ratio:continue
                choices=sorted(data['pools'][pool],key=lambda row:-math.log(max(rng.random(),1e-12))/row['weight'])
                for choice in choices:
                    ident=choice['assetId'];entry=entries[ident]
                    if ident not in models:
                        models[ident]=read_structure(Path(byid[ident]['path'])/'structure.nbt')
                        if models[ident]['sha256']!=entry['nbtSha256']:raise ValueError('绿化 NBT 在生成期间改变。')
                    rotations=list(entry['rotations']);rng.shuffle(rotations)
                    found=None
                    for rotation in rotations:
                        found=placement(entry,models[ident],x,planting[(x,z)],z,rotation,cells,sources,allowed,protected|active_reserved,rotate_state)
                        if found:break
                    if not found:continue
                    writes,origin,footprint=found
                    for xx,yy,zz,s in writes:put(xx,yy,zz,s,'greenery-template:'+ident)
                    margin=entry.get('clearanceBlocks',1)
                    reserved.update((xx+dx,zz+dz) for xx,zz in footprint for dx in range(-margin,margin+1) for dz in range(-margin,margin+1))
                    if slot!='trees':low_reserved.update((xx+dx,zz+dz) for xx,zz in footprint for dx in range(-margin,margin+1) for dz in range(-margin,margin+1))
                    slot_count+=1
                    chosen.append((x,z));placed.append(dict(assetId=ident,pool=pool,nodeId=plan['nodeId'],kind=entry['kind'],origin=origin,rotation=rotation,
                        groundPlaneY=entry['groundPlaneY'],sizeXYZ=entry['sizeXYZ'],nbtSha256=entry['nbtSha256'],authorSha256=entry['authorSha256'],writtenBlocks=len(writes)))
                    break
                else:skipped[slot]=skipped.get(slot,0)+1
    return dict(enabled=True,placements=placed,skippedCandidates=skipped,catalog=data,
                policy='Seeded weighted selection among fitting templates; protected roads, buildings, facilities and full canopy boxes; no clearing.')
