"""Place authored infrastructure tiles on planting contours, preserving circulation."""
from pathlib import Path
from studio.model import read_structure

DIRS=((0,-1),(1,0),(0,1),(-1,0))  # north, east, south, west


def contour_tiles(mask):
    """Convex/concave corners face the planted interior; single-cell islands skip."""
    border={p for p in mask if any((p[0]+dx,p[1]+dz) not in mask for dx in (-1,0,1) for dz in (-1,0,1))}
    for x,z in sorted(border):
        outside=[i for i,(dx,dz) in enumerate(DIRS) if (x+dx,z+dz) not in mask]
        if len(outside)==2 and (outside[0]-outside[1])%2:
            # Canonical outer_left occupies the south-east inner quadrant.
            rotation=next(r for r in range(4) if set(outside)=={r,(r+3)%4})
            kind='outer';rotation*=90
        elif len(outside)<=1:
            rotation=outside[0]*90 if outside else 0;kind='straight' if outside else None
            # A missing diagonal on the interior side forms a concave joint.
            for r in range(4):
                a,b=DIRS[r],DIRS[(r+3)%4]
                if (x+a[0],z+a[1]) in mask and (x+b[0],z+b[1]) in mask and (x+a[0]+b[0],z+a[1]+b[1]) not in mask:
                    kind='inner';rotation=r*90;break
            if kind is None:continue
        else:continue
        yield (x,z),kind,rotation


def apply_landscape_borders(result,cells,sources,put,assets,rotate_state):
    scene=result['scene'];kit=scene.get('landscapeBorder')
    if not kit or not scene.get('greenery',True):return
    by_id={a['assetId']:a for a in assets};models={}
    for kind,ident in kit.items():
        a=by_id[ident];model=read_structure(Path(a['path'])/'structure.nbt')
        if model['size']!=[1,2,1] and tuple(model['size'])!=(1,2,1):raise ValueError('花坛收边需要 1×2×1 消费件。')
        if model['sha256']!=a['nbtSha256']:raise ValueError('花坛边 NBT 改变。')
        models[kind]=model
    roads=set()
    for band in result['roadBands']:
        b=band['bounds'];roads.update((x,z) for x in range(b['minX']-2,b['maxX']+3) for z in range(b['minZ']-2,b['maxZ']+3))
    ground={(r[0],r[1]):r[2] for r in result['terrainColumns']}
    for plan in result.get('publicSpacePlans',[]):
        ground.update({(c['x'],c['z']):c['targetY'] for c in plan.get('engineeringColumns',[])})
    fixtures=set()
    for f in result.get('fixtures',[]):
        p=f['originBlock'];fixtures.update((x,z) for x in range(p['x']-2,p['x']+f['width']+2) for z in range(p['z']-2,p['z']+f['depth']+2))
    replaceable={'minecraft:oak_leaves','minecraft:azalea_leaves','minecraft:flowering_azalea_leaves',
                 'minecraft:flowering_azalea','minecraft:allium','minecraft:azure_bluet'}
    for plan in result.get('publicSpacePlans',[]):
        masks=[];gap=plan['kind']=='GAP_LANDSCAPE'
        if plan['kind'] in ('GARDEN','PLAZA'):
            masks=[{(x,z) for x in range(a,c+1) for z in range(b,d+1)} for a,b,c,d in plan['beds']]
        elif gap:masks=[{tuple(p) for p in r['columns']} for r in plan['regions']]
        else:continue
        placements=[]
        for mask in masks:
            tiles=list(contour_tiles(mask))
            if gap:tiles=[t for t in tiles if any((t[0][0]+dx,t[0][1]+dz) in roads for dx in range(-3,4) for dz in range(-3,4) if abs(dx)+abs(dz)<=3)]
            selected={p for p,_,_ in tiles}
            for (x,z),kind,rotation in tiles:
                if (x,z) in roads or (x,z) in fixtures:continue
                y=ground[(x,z)] if gap else plan['surfaceY'];layer='landscape:'+plan['nodeId']
                if sources.get((x,y,z),'').startswith(('building:','fixture:')) or cells.get((x,y,z),{}).get('name') in (None,'minecraft:water'):continue
                above=cells.get((x,y+1,z))
                if above and (sources.get((x,y+1,z))!=layer or above['name'] not in replaceable):continue
                if any((x,yy,z) in cells for yy in range(y+2,y+9)):continue
                if gap and sum((x+dx,z+dz) in selected for dx,dz in DIRS)<=1:kind='end'
                model=models[kind]
                for block in model['blocks']:
                    yy=y+block['pos'][1]
                    put(x,yy,z,rotate_state(model['palette'][block['state']],rotation),layer)
                    plan['writes'].append([x,yy,z,'BORDER'])
                placements.append(dict(x=x,z=z,surfaceY=y,kind=kind,rotation=rotation,assetId=kit[kind],nbtSha256=model['sha256']))
        plan['borderPlacements']=placements;plan['counts']['BORDER']=len(placements)*2
