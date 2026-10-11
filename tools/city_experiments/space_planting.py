"""Seeded planting patches layered around a finished space and its paths."""
import math,random
from pathlib import Path
from studio.model import read_structure
from template_greenery import placement

def plant_zones(plan,assets,cells,owners,put,members,reserved,rotate_state):
    zones=plan.get('plantingZones',[]);density=plan.get('plantingDensity',1)
    if len({z['id'] for z in zones})!=len(zones):raise ValueError('种植区 ID 重复。')
    paths={(x,z) for (x,y,z),o in owners.items() if o=='path'}
    protected=set(reserved)
    protected.update((x+dx,z+dz) for x,z in paths for dx in range(-1,2) for dz in range(-1,2))
    for p in members:protected.update((x,z) for x in range(p['x']-1,p['x']+p['width']+1) for z in range(p['z']-1,p['z']+p['depth']+1))
    edges={(x+dx,z+dz) for x,z in paths for dx in range(-7,8) for dz in range(-7,8)}
    models={};results=[];reports=[]
    soil_owners={p:('landscape:space' if o=='ground' else o) for p,o in owners.items()}
    for zone in zones:
        if zone['pool'] not in plan['pools']:raise ValueError('种植区引用未知池：'+zone['pool'])
        x0,z0=zone['x'],zone['z'];w,d=zone['width'],zone['depth']
        if x0<0 or z0<0 or x0+w>plan['width'] or z0+d>plan['depth']:raise ValueError('种植区越界：'+zone['id'])
        allowed={(x,z) for x in range(x0,x0+w) for z in range(z0,z0+d)}
        if zone['shape']=='ELLIPSE':allowed={p for p in allowed if ((p[0]-x0-(w-1)/2)/(w/2))**2+((p[1]-z0-(d-1)/2)/(d/2))**2<=1}
        if zone['shape']=='PATH_EDGE':allowed&=edges
        # Only existing grass becomes planting soil; no new paving or terrain flattening.
        allowed={p for p in allowed if cells.get((p[0],1,p[1]),{}).get('name')=='minecraft:grass_block'}
        rng=random.Random(f"{plan['seed']}:{zone['id']}");candidates=sorted(allowed);rng.shuffle(candidates)
        target=min(150,round(zone['count']*density));centers=[]
        for x,z in candidates:
            if len(centers)>=target or len(results)>=400:break
            if (x,z) in protected or any((x-xx)**2+(z-zz)**2<zone['spacing']**2 for xx,zz in centers):continue
            choices=sorted(plan['pools'][zone['pool']],key=lambda a:-math.log(max(rng.random(),1e-12))/a['weight'])
            for row in choices:
                aid=row['assetId'];a=assets.get(aid)
                if not a or not('landscape' in a.get('tags',[]) or a['consumer']=='infrastructure'):raise ValueError('种植区需要景观配套素材。')
                if aid not in models:models[aid]=read_structure(Path(a['path'])/'structure.nbt')
                m=models[aid]
                if m['sha256']!=a['nbtSha256']:raise ValueError('种植素材哈希已变化。')
                aw,ah,ad=m['size'];entry={'anchorXYZ':[aw//2,0,ad//2],'groundPlaneY':a['groundPlane']}
                rotations=[0,90,180,270];rng.shuffle(rotations)
                for r in rotations:
                    # Shared template validator restricts embedded soil to landscape-owned grass.
                    found=placement(entry,m,x,1,z,r,cells,soil_owners,allowed,protected,rotate_state)
                    if found:break
                if not found:continue
                writes,origin,mask=found;ident=f"planting/{zone['id']}:{len(centers)+1}"
                for xx,yy,zz,s in writes:put(xx,yy,zz,s,ident)
                protected.update((xx+dx,zz+dz) for xx,zz in mask for dx in range(-1,2) for dz in range(-1,2))
                centers.append((x,z));rw,rd=(ad,aw) if r%180 else (aw,ad)
                results.append(dict(id=ident,assetId=aid,x=origin['x'],z=origin['z'],width=rw,depth=rd,rotation=r,nbtSha256=m['sha256']))
                break
        reports.append(dict(id=zone['id'],requested=target,placed=len(centers)))
    return dict(placements=results,zones=reports)
