"""Small inter-group landscape, optionally following the actual road grade.

Two real terrace boundaries must be close. Water and steep/deep intervening
terrain interrupt the search. ROAD_LEVEL earthworks stay outside core terraces.
"""
from collections import deque

DIRS=((1,0),(-1,0),(0,1),(0,-1))

def plan_gap_regions(result, radius=20, road_level=False):
    maximum=result.get('scene',{}).get('smallGapMaxBlocks',32)
    if result.get('scene',{}).get('gapSurface')=='CONNECTION_TERRACE':radius=maximum
    ground={(r[0],r[1]):(r[2],r[3]) for r in result['terrainColumns']}
    occupied={};datums={}
    for t in result['terraces']:
        ident=t['terraceId'];datums[ident]=t['datumY']
        for s in t['spans']:
            for x in range(s['minX'],s['maxX']+1):occupied[(x,s['z'])]=ident
    roads=set();blocked=set()
    for b in result['roadBands']:
        b=b['bounds'];roads.update((x,z) for x in range(b['minX']-2,b['maxX']+3) for z in range(b['minZ']-2,b['maxZ']+3))
    for f in result.get('fixtures',[]):
        p=f['originBlock'];blocked.update((x,z) for x in range(p['x']-2,p['x']+f['width']+2) for z in range(p['z']-2,p['z']+f['depth']+2))
    water={p for p,r in ground.items() if r[1]=='minecraft:water'}
    shore={(x+dx,z+dz) for x,z in water for dx in range(-2,3) for dz in range(-2,3)}
    def eligible(p,owner):
        r=ground.get(p)
        if r is None or p in occupied or p in shore or p in blocked:return False
        if abs(r[0]-datums[owner])>6:return False
        return all(abs(ground.get((p[0]+dx,p[1]+dz),r)[0]-r[0])<=1 for dx,dz in DIRS)
    distances={};queue=deque()
    for (x,z),owner in occupied.items():
        for dx,dz in DIRS:
            p=(x+dx,z+dz)
            if eligible(p,owner) and owner not in distances.get(p,{}):
                distances.setdefault(p,{})[owner]=1;queue.append((p,owner,1))
    while queue:
        (x,z),owner,d=queue.popleft()
        if d==radius:continue
        for dx,dz in DIRS:
            p=(x+dx,z+dz)
            if owner in distances.get(p,{}) or not eligible(p,owner):continue
            distances.setdefault(p,{})[owner]=d+1;queue.append((p,owner,d+1))
    actual_roads={(f['x'],f['z']) for f in result.get('surfacePrintPlan',{}).get('featureCells',[]) if f['kind'] in ('ROAD_SLAB','ROAD_STAIR') and f['surfaceOffset']==0}
    candidates={}
    for p,owners in distances.items():
        close=sorted(owners.items(),key=lambda row:(row[1],row[0]))
        if len(close)>=2 and close[0][1]>=(1 if road_level else 2) and close[0][1]+close[1][1]<=maximum and p not in (actual_roads if road_level else roads):
            pair=tuple(sorted((close[0][0],close[1][0])));candidates[p]=pair
    regions=[];remaining=set(candidates)
    while remaining:
        start=min(remaining);remaining.remove(start);pair=candidates[start];todo=[start];region={start}
        while todo:
            x,z=todo.pop()
            for dx,dz in DIRS:
                p=(x+dx,z+dz)
                if p in remaining and candidates[p]==pair:remaining.remove(p);region.add(p);todo.append(p)
        if len(region)>=60:regions.append(dict(pair=pair,columns=region))
    return regions,ground,roads,occupied


def road_level_targets(result,regions,ground):
    """Extend the closest real road's grade sideways through small gap masks."""
    mask=set().union(*(r['columns'] for r in regions)) if regions else set()
    road={(f['x'],f['z']):f['targetSurfaceY'] for f in result['surfacePrintPlan']['featureCells']
          if f['kind'] in ('ROAD_SLAB','ROAD_STAIR') and f['surfaceOffset']==0 and 'targetSurfaceY' in f}
    occupied={(x,s['z']) for t in result['terraces'] for s in t['spans'] for x in range(s['minX'],s['maxX']+1)}
    allowed=mask|occupied|road.keys()
    # Roads often sit a few blocks inside a terrace. Distance may traverse that
    # existing terrace, but its surface is never included in the earthworks.
    targets={p:(y,p,0) for p,y in sorted(road.items())};queue=deque(targets)
    while queue:
        p=queue.popleft();y,seed,d=targets[p]
        if d>=result.get('scene',{}).get('smallGapMaxBlocks',32) and result.get('scene',{}).get('gapSurface')=='CONNECTION_TERRACE':continue
        if d>=16 and result.get('scene',{}).get('gapSurface')!='CONNECTION_TERRACE':continue
        for dx,dz in DIRS:
            q=(p[0]+dx,p[1]+dz)
            if q in allowed and q not in targets and (q not in mask or abs(ground[q][0]-y)<=6):
                targets[q]=(y,seed,d+1);queue.append(q)
    return {p:v for p,v in targets.items() if p in mask}

def apply_gap_landscape(result,cells,sources,put):
    scene=result['scene'];connection=scene.get('gapSurface')=='CONNECTION_TERRACE';level=scene.get('gapSurface')=='ROAD_LEVEL';green=scene.get('greenery',True)
    if not scene.get('gapLandscape') or (not green and not level and not connection):return None
    regions,ground,roads,occupied=plan_gap_regions(result,road_level=level)
    writes=[];counts={};records=[]
    def emit(x,y,z,block,kind):
        if scene.get('greeneryTemplates') and kind in ('SHRUB','FLOWER','TREE_TRUNK','TREE_CROWN'):return
        put(x,y,z,block,'landscape:group-gaps');writes.append([x,y,z,kind]);counts[kind]=counts.get(kind,0)+1
    engineering=[]
    if connection:
        plan=result['connectionTerracePlan'];engineering=plan['columns']
        for c in engineering:ground[(c['x'],c['z'])]=(c['targetY'],'minecraft:smooth_stone')
        regions=[dict(pair=r['terracePair'],columns={tuple(p) for p in r['columns']}-roads) for r in plan['regions']]
        regions=[r for r in regions if r['columns']]
    if level:
        targets=road_level_targets(result,regions,ground);accepted=set()
        for (x,z),(y,seed,distance) in sorted(targets.items()):
            native=ground[(x,z)][0]
            # Only change native terrain. Existing walls, lamps and buildings win.
            if any(sources.get((x,yy,z),'') not in ('','terrain') for yy in range(min(native,y)-2,max(native,y)+9)):continue
            for yy in range(y+1,native+1):emit(x,yy,z,'minecraft:air','CLEAR')
            for yy in range(min(native-2,y-2),y):emit(x,yy,z,'minecraft:dirt','FILL')
            emit(x,y,z,'minecraft:grass_block','SURFACE')
            ground[(x,z)]=(y,'minecraft:grass_block');accepted.add((x,z))
            engineering.append(dict(x=x,z=z,nativeY=native,targetY=y,roadX=seed[0],roadZ=seed[1],distance=distance))
        regions=[dict(pair=r['pair'],columns=r['columns']&accepted-roads) for r in regions]
        regions=[r for r in regions if r['columns']]
    def clear(p,height=2):
        if p not in ground or p in occupied or p in roads:return False
        x,z=p;y=ground[p][0]
        return sources.get((x,y,z)) in ('terrain','landscape:group-gaps','infrastructure:connection-terrace') and cells.get((x,y,z),{}).get('name') in ('minecraft:grass_block','minecraft:dirt','minecraft:stone','minecraft:smooth_stone') and all((x,yy,z) not in cells for yy in range(y+1,y+height+1))
    for index,region in enumerate(regions):
        mask=region['columns'];pair=region['pair'];boundary={p for p in mask if any((p[0]+dx,p[1]+dz) not in mask for dx,dz in DIRS)}
        depth={p:0 for p in boundary};queue=deque(sorted(boundary))
        while queue:
            x,z=queue.popleft()
            for dx,dz in DIRS:
                p=(x+dx,z+dz)
                if p in mask and p not in depth:depth[p]=depth[(x,z)]+1;queue.append(p)
        planted=[];trees=[];seats=[]
        # One continuous edge band and an open lawn interior, following ground height.
        for x,z in sorted(mask if green else ()):
            if not clear((x,z)):continue
            y=ground[(x,z)][0]
            if connection and depth[(x,z)]<2:continue
            emit(x,y,z,'minecraft:grass_block','LAWN')
            if depth[(x,z)]==(3 if connection else 1) and (x+z)%4==0:
                emit(x,y+1,z,'minecraft:flowering_azalea','SHRUB');planted.append([x,z])
            elif depth[(x,z)]==(4 if connection else 2) and (x-z)%3==0:
                emit(x,y+1,z,'minecraft:allium' if (x+z)%6<3 else 'minecraft:azure_bluet','FLOWER');planted.append([x,z])
        def tree_clear(p):
            x,z=p;y=ground[p][0]
            footprint={(x+dx,z+dz) for dx in range(-3,4) for dz in range(-3,4)}
            if not footprint<=mask or max(ground[q][0] for q in footprint)-min(ground[q][0] for q in footprint)>1:return False
            return all(q not in roads and all(sources.get((q[0],yy,q[1]),'') in ('','landscape:group-gaps') for yy in range(ground[q][0]+1,y+10)) for q in footprint)
        candidates=[p for p in sorted(mask) if green and not scene.get('greeneryTemplates') and depth[p]>=4 and tree_clear(p)]
        limit=min(8,max(1,len(mask)//240))
        while candidates and len(trees)<limit:
            p=max(candidates,key=lambda q:(min((q[0]-t[0])**2+(q[1]-t[1])**2 for t in trees),depth[q],q)) if trees else max(candidates,key=lambda q:(depth[q],q))
            x,z=p;y=ground[p][0]
            for yy in range(y+1,y+6):emit(x,yy,z,'minecraft:oak_log[axis=y]','TREE_TRUNK')
            for dy,r in ((4,2),(5,3),(6,2),(7,1)):
                for dx in range(-r,r+1):
                    for dz in range(-r,r+1):
                        if abs(dx)+abs(dz)<=r+1 and (dx or dz or dy>5):emit(x+dx,y+dy,z+dz,'minecraft:oak_leaves[persistent=true]','TREE_CROWN')
            trees.append([x,z]);candidates=[q for q in candidates if (q[0]-x)**2+(q[1]-z)**2>=14**2]
        # Resting nooks only on a flat patch beside a same-height real road.
        road_surface={(f['x'],f['z']):f['targetSurfaceY'] for f in result['surfacePrintPlan']['featureCells'] if f['surfaceOffset']==0 and f['kind']=='ROAD_SLAB' and 'targetSurfaceY' in f}
        for x,z in sorted(p for p in mask if 2<=depth[p]<=4):
            if not clear((x,z)):continue
            y=ground[(x,z)][0];floor={(x+dx,z+dz) for dx in range(-2,3) for dz in range(-1,2)}
            if not floor<=mask or not all(ground[q][0]==y and clear(q) for q in floor):continue
            nearby=[q for q,yy in road_surface.items() if yy==y and abs(q[0]-x)+abs(q[1]-z)<=7]
            if not nearby:continue
            facing='north' if min(nearby,key=lambda q:abs(q[0]-x)+abs(q[1]-z))[1]<z else 'south'
            for xx in range(x-1,x+2):emit(xx,y+1,z,'minecraft:dark_oak_stairs[facing='+facing+',half=bottom,shape=straight,waterlogged=false]','SEAT')
            seats.append([x,z]);break
        records.append(dict(regionId='gap-'+str(index+1),terracePair=pair,columns=[list(p) for p in sorted(mask)],trees=trees,seats=seats,plantingColumns=planted))
    return dict(nodeId='group-gaps',kind='GAP_LANDSCAPE',regions=records,counts=counts,writes=writes,
                terrainPolicy='CONNECTION_TERRACE' if connection else ('FOLLOW_ROAD_HEIGHT' if level else 'KEEP_EXISTING_HEIGHT'),engineeringColumns=engineering,maximumGapBlocks=scene.get('smallGapMaxBlocks',32))
