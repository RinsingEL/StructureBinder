"""Exterior landscape mask and explicit, terrain-checked city access routes."""
from collections import deque
import math

DIRS=((1,0),(-1,0),(0,1),(0,-1))

def rect(b,margin=0):
    return {(x,z) for x in range(b['minX']-margin,b['maxX']+margin+1) for z in range(b['minZ']-margin,b['maxZ']+margin+1)}

def apply_access(result,cells,sources,put):
    config=result['scene'].get('externalAccess')
    if not config:return None
    for key in ('landing','harborReservation'):
        b=config[key]
        if not (0<=b['maxX']-b['minX']<=64 and 0<=b['maxZ']-b['minZ']<=64):raise ValueError('港口承接范围必须为有效且不超过 65 格的矩形。')
    ground={(r[0],r[1]):r[2:4] for r in result['terrainColumns']}
    nodes=config['waypoints'];half=config['width']//2;axis=[]
    for a,b in zip(nodes,nodes[1:]):
        dx,dz=b['x']-a['x'],b['z']-a['z'];length=abs(dx)+abs(dz)
        if not length or length>512 or (dx and dz):raise ValueError('对外道路节点必须为非零且不超过 512 格的轴向线段。')
        if abs(b['surface']-a['surface'])>length/2:raise ValueError('对外道路坡度过大。')
        for i in range(length+1):
            surface=math.floor((a['surface']+(b['surface']-a['surface'])*i/length)*2+.00001)/2
            axis.append((a['x']+(i if dx>0 else -i if dx<0 else 0),a['z']+(i if dz>0 else -i if dz<0 else 0),surface))
    road={(f['x'],f['z']):f['targetSurfaceY'] for f in result['surfacePrintPlan']['featureCells'] if f['kind']=='ROAD_SLAB' and f['surfaceOffset']==0}
    start=nodes[0]
    if road.get((start['x'],start['z']))!=start['surface']-.5:raise ValueError('出口道路起点必须接入同高现有道路。')
    targets={}
    for x,z,surface in axis:
        for xx in range(x-half,x+half+1):
            for zz in range(z-half,z+half+1):targets[(xx,zz)]=max(targets.get((xx,zz),surface),surface)
    for p in rect(config['landing']):targets[p]=nodes[-1]['surface']
    if (nodes[-1]['x'],nodes[-1]['z']) not in rect(config['landing']):raise ValueError('道路终点必须落在港口承接平台内。')
    protected=set()
    for m in result['members']+result.get('fixtures',[]):
        o=m['originBlock'];protected|=rect(dict(minX=o['x'],maxX=o['x']+m['width']-1,minZ=o['z'],maxZ=o['z']+m['depth']-1),1)
    if targets.keys()&protected:raise ValueError('对外道路侵入建筑或设施。')
    # Validate the complete corridor before writing; only native ground and edge rails may change.
    cleared=[]
    for (x,z),s in targets.items():
        g=ground.get((x,z))
        if not g or g[1]=='minecraft:water':raise ValueError('对外道路须位于已采样陆地。')
        if abs(g[0]-(s-.5))>8:raise ValueError('对外道路土方超过八格。')
        y=math.ceil(s)-1
        if (x,z) in road and road[(x,z)]+.5!=s:raise ValueError('对外道路改变已有道路高度。')
        for yy in range(y,math.ceil(max(s,g[0]+1))+4):
            p=(x,yy,z);owner=sources.get(p,'');name=cells.get(p,{}).get('name','')
            if owner in ('','terrain'):continue
            if yy==y and (x,z) in road:continue
            if owner.startswith(('infrastructure','landscape:')) and (name.endswith(('_wall','_fence','_slab')) or yy<=y):continue
            raise ValueError('对外道路净空冲突：'+str(p)+' '+owner+' '+name)
    for (x,z),s in sorted(targets.items()):
        if (x,z) in road:continue
        y=math.ceil(s)-1;native=ground[(x,z)][0]
        for yy in range(y,math.ceil(max(s,native+1))+4):
            if (x,yy,z) in cells:cleared.append([x,yy,z])
            put(x,yy,z,'minecraft:air','external-access')
        for yy in range(min(native-2,y-1),y):put(x,yy,z,'minecraft:stone_bricks','external-access')
        kind='bottom' if s%1 else 'top'
        put(x,y,z,'minecraft:polished_andesite_slab[type='+kind+',waterlogged=false]','external-access')
    return dict(nodeId='harbor-access',kind='EXTERNAL_ACCESS',axis=[list(p) for p in axis],
        columns=[dict(x=x,z=z,surface=s) for (x,z),s in sorted(targets.items())],landing=config['landing'],
        harborReservation=config['harborReservation'],clearedColumns=len({(p[0],p[2]) for p in cleared}),
        policy='Explicit AI-selected connection; half-block walkable grades; land only; reserved harbor remains unbuilt.')

def apply_fringe(result,cells,sources,put):
    config=result['scene'].get('outerGreenery')
    if not config or not result['scene'].get('greenery',True):return None
    ground={(r[0],r[1]):r[2:4] for r in result['terrainColumns']}
    urban={(x,s['z']) for t in result['terraces'] for s in t['spans'] for x in range(s['minX'],s['maxX']+1)}
    urban.update((c['x'],c['z']) for c in result.get('connectionTerracePlan',{}).get('columns',[]))
    if not urban:return None
    # Flood from sampled-world edges so enclosed courtyards never become exterior.
    xs=[p[0] for p in ground];zs=[p[1] for p in ground];bounds=(min(xs),max(xs),min(zs),max(zs))
    exterior={p for p in ground if p not in urban and (p[0] in bounds[:2] or p[1] in bounds[2:])};queue=deque(sorted(exterior))
    while queue:
        x,z=queue.popleft()
        for dx,dz in DIRS:
            q=x+dx,z+dz
            if q in ground and q not in urban and q not in exterior:exterior.add(q);queue.append(q)
    distance={p:0 for p in urban};queue=deque(sorted(urban));width=config['width']
    while queue:
        x,z=queue.popleft();d=distance[(x,z)]
        if d>=width:continue
        for dx,dz in DIRS:
            q=x+dx,z+dz
            if q in exterior and q not in distance:distance[q]=d+1;queue.append(q)
    water={p for p,g in ground.items() if g[1]=='minecraft:water'}
    blocked={(x+dx,z+dz) for x,z in water for dx in range(-2,3) for dz in range(-2,3)}
    access=result.get('externalAccessPlan')
    if access:
        blocked|=rect(access['harborReservation'])
        blocked.update((c['x']+dx,c['z']+dz) for c in access['columns'] for dx in range(-3,4) for dz in range(-3,4))
    writes=[];distances={}
    for (x,z),d in sorted(distance.items()):
        if d<3 or (x,z) in blocked:continue
        y,block=ground[(x,z)]
        if block not in ('minecraft:grass_block','minecraft:dirt'):continue
        if sources.get((x,y,z))!='terrain' or any((x,yy,z) in cells for yy in range(y+1,y+3)):continue
        if any(abs(ground.get((x+dx,z+dz),(y,))[0]-y)>1 for dx,dz in DIRS):continue
        put(x,y,z,'minecraft:grass_block','landscape:city-fringe');writes.append([x,y,z,'LAWN']);distances[f'{x},{z}']=d
    return dict(nodeId='city-fringe',kind='OUTER_LANDSCAPE',writes=writes,counts={'LAWN':len(writes)},
        distanceFromCity=distances,width=width,terrainPolicy='KEEP_EXISTING_HEIGHT_AND_SHORE')
