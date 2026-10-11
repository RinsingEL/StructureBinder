"""Designed public spaces in the offline compositor; no theme/asset-ID branches.

Reserve axial circulation first, then bounded beds, low borders, edge trees and
seating. Randomness never determines the plan. Frozen roads/structures win.
"""

def apply_public_spaces(result, cells, sources, put):
    scene=result['scene']; specs={p['nodeId']:p for p in scene.get('publicSpaces',[])}
    if not specs:return []
    spaces={p['nodeId']:p for p in result.get('layoutSpaces',[])}
    if set(specs)-spaces.keys():raise ValueError('公共空间需要 COURTYARD 留空：'+str(sorted(set(specs)-spaces.keys())))
    green=scene.get('greenery',True);templated=bool(scene.get('greeneryTemplates'))
    plans=[]
    for ident,spec in specs.items():
        space=spaces[ident];x0,z0=space['x'],space['z'];w,d=space['width'],space['depth'];cx,cz=x0+w//2,z0+d//2
        owner=next(m for m in result['members'] if m['arrayId']==space['ownerArrayId'])
        y=owner['surfaceY'];protected=set();supported=set()
        for t in result['terraces']:
            if t['terraceId']==owner['terraceId']:
                for row in t['spans']:
                    if row['targetY']==y:supported.update((x,row['z']) for x in range(row['minX'],row['maxX']+1))
        for band in result['roadBands']:
            b=band['bounds'];protected.update((x,z) for x in range(b['minX']-2,b['maxX']+3) for z in range(b['minZ']-2,b['maxZ']+3))
        for f in result.get('fixtures',[]):
            p=f['originBlock'];protected.update((x,z) for x in range(p['x']-2,p['x']+f['width']+2) for z in range(p['z']-2,p['z']+f['depth']+2))
        walk={(x,z) for x in range(x0,x0+w) for z in range(z0,z0+d) if abs(x-cx)<=2 or abs(z-cz)<=2}
        road_protected=protected.copy();protected.update(walk)
        counts={};writes=[]
        def safe(x,z,*,plant=False):
            if not(x0<=x<x0+w and z0<=z<z0+d) or (x,z) not in supported:return False
            if plant and (x,z) in protected:return False
            if sources.get((x,y,z),'').startswith('building:'):return False
            return cells.get((x,y,z),{}).get('name') not in ('minecraft:water',None) and cells.get((x,y+1,z),{}).get('name','minecraft:air')=='minecraft:air'
        def emit(x,yy,z,block,kind):
            if templated and kind in ('LOW_HEDGE','FLOWER','TREE_TRUNK','TREE_CROWN'):return
            put(x,yy,z,block,'landscape:'+ident);counts[kind]=counts.get(kind,0)+1;writes.append([x,yy,z,kind])
        for x,z in sorted(walk):
            if safe(x,z) and (x,z) not in road_protected:emit(x,y,z,'minecraft:polished_andesite','WALK')
        garden=spec['kind']=='GARDEN'
        flower=spec.get('flowerBlock','minecraft:allium');hedge=spec.get('hedgeBlock','minecraft:oak_leaves');leaves=spec.get('treeLeaves','minecraft:azalea_leaves')
        beds=[]
        for xa,xb in (((x0+4,cx-5),(cx+5,x0+w-5)) if green else ()):
            for za,zb in ((z0+4,cz-5),(cz+5,z0+d-5)):
                if xa>xb or za>zb:continue
                if not garden:
                    xa,xb=(xa,min(xa+4,xb)) if xa<cx else (max(xb-4,xa),xb)
                    za,zb=(za,min(za+6,zb)) if za<cz else (max(zb-6,za),zb)
                beds.append([xa,za,xb,zb])
                for x in range(xa,xb+1):
                    for z in range(za,zb+1):
                        if not safe(x,z,plant=True):continue
                        edge=x in (xa,xb) or z in (za,zb)
                        emit(x,y,z,'minecraft:grass_block','BED')
                        if edge:emit(x,y+1,z,hedge+'[persistent=true]','LOW_HEDGE')
                        elif (x-xa)%3==1 and (z-za)%3==1:emit(x,y+1,z,flower,'FLOWER')
        trees=[]
        for tx,tz in (((x0+5,z0+5),(x0+w-6,z0+5),(x0+5,z0+d-6),(x0+w-6,z0+d-6)) if green and not templated else ()):
            footprint={(tx+dx,tz+dz) for dx in range(-2,3) for dz in range(-2,3)}
            def tree_safe(p):
                x,z=p
                return p in supported and p not in protected and all(sources.get((x,yy,z),'') in ('','landscape:'+ident) for yy in range(y+1,y+8))
            if not all(tree_safe(p) for p in footprint):continue
            for yy in range(y+1,y+5):emit(tx,yy,tz,'minecraft:oak_log[axis=y]','TREE_TRUNK')
            for dy,r in ((4,2),(5,2),(6,1)):
                for dx in range(-r,r+1):
                    for dz in range(-r,r+1):
                        if abs(dx)+abs(dz)<=r+1 and (dx or dz or dy>4):emit(tx+dx,y+dy,tz+dz,leaves+'[persistent=true]','TREE_CROWN')
            trees.append([tx,tz])
        seats=[]
        for sx,sz,facing in ((cx-7,z0+2,'south'),(cx+5,z0+2,'south'),(cx-7,z0+d-3,'north'),(cx+5,z0+d-3,'north')):
            if all(safe(x,sz,plant=True) for x in range(sx,sx+3)):
                for x in range(sx,sx+3):emit(x,y+1,sz,'minecraft:dark_oak_stairs[facing='+facing+',half=bottom,shape=straight,waterlogged=false]','SEAT')
                seats.append([sx,sz])
        for lx,lz in ((x0+2,z0+2),(x0+w-3,z0+2),(x0+2,z0+d-3),(x0+w-3,z0+d-3)):
            if safe(lx,lz,plant=True) and all((lx,yy,lz) not in cells for yy in range(y+2,y+6)):
                for yy in range(y+1,y+4):emit(lx,yy,lz,'minecraft:dark_oak_fence','LIGHT_POST')
                emit(lx,y+4,lz,'minecraft:lantern[hanging=false,waterlogged=false]','LAMP')
        amenities=[]
        if not garden:
            # A small well occupies a designed side pocket, leaving the crossing open.
            for wx,wz in ((cx-10,cz-10),(cx+10,cz-10),(cx-10,cz+10),(cx+10,cz+10)):
                if not all(safe(x,z,plant=True) for x in range(wx-2,wx+3) for z in range(wz-2,wz+3)):continue
                for x in range(wx-2,wx+3):
                    for z in range(wz-2,wz+3):emit(x,y,z,'minecraft:stone_bricks','WELL_BASE')
                for x in range(wx-1,wx+2):
                    for z in range(wz-1,wz+2):
                        emit(x,y+1,z,'minecraft:water[level=0]' if (x,z)==(wx,wz) else 'minecraft:stone_bricks','WELL')
                for x in (wx-2,wx+2):emit(x,y+1,wz,'minecraft:oak_fence','WELL_POST');emit(x,y+2,wz,'minecraft:oak_fence','WELL_POST')
                for x in range(wx-2,wx+3):emit(x,y+3,wz,'minecraft:dark_oak_slab[type=bottom]','WELL_ROOF')
                amenities.append(dict(kind='WELL',x=wx,z=wz));break
            for bx,bz in ((cx-10,z0+2),(cx+10,z0+2)):
                if not all(safe(x,bz,plant=True) for x in range(bx-2,bx+3)):continue
                for x in (bx-2,bx+2):
                    emit(x,y+1,bz,'minecraft:oak_fence','NOTICE_POST');emit(x,y+2,bz,'minecraft:oak_fence','NOTICE_POST')
                for x in range(bx-1,bx+2):emit(x,y+2,bz,'minecraft:spruce_trapdoor[facing=north,half=bottom,open=true,powered=false,waterlogged=false]','NOTICE_BOARD')
                amenities.append(dict(kind='NOTICE_BOARD',x=bx,z=bz));break
        plans.append(dict(nodeId=ident,kind=spec['kind'],surfaceY=y,bounds=dict(x=x0,z=z0,width=w,depth=d),
                          reservedWalkColumns=len(walk),protectedRoadColumns=len(road_protected),beds=beds,trees=trees,seats=seats,amenities=amenities,counts=counts,
                          supportedColumns=sum((x,z) in supported for x in range(x0,x0+w) for z in range(z0,z0+d)),writes=writes))
    # Group edges receive linear planting beds, not unstructured scatter.
    if green:
        heights={(x,row['z']):row['targetY'] for t in result['terraces'] for row in t['spans'] for x in range(row['minX'],row['maxX']+1)}
        road=set()
        for band in result['roadBands']:
            b=band['bounds'];road.update((x,z) for x in range(b['minX']-2,b['maxX']+3) for z in range(b['minZ']-2,b['maxZ']+3))
        blocked=set()
        for m in result['members']:
            p=m['originBlock'];blocked.update((x,z) for x in range(p['x']-1,p['x']+m['width']+1) for z in range(p['z']-1,p['z']+m['depth']+1))
        for f in result.get('fixtures',[]):
            p=f['originBlock'];blocked.update((x,z) for x in range(p['x']-1,p['x']+f['width']+1) for z in range(p['z']-1,p['z']+f['depth']+1))
        edges=[];counts={};writes=[]
        for m in result['members']:
            p=m['originBlock'];x0,z0=p['x'],p['z'];w,d=m['width'],m['depth'];direction=m['roadEntrances'][0]['direction'];y=m['surfaceY']
            # Back of each independent building; 3-deep bounded bed, all plants low.
            if direction=='NORTH':bed=[(x,z0+d+q) for x in range(x0+3,x0+w-3) for q in range(2,5)]
            elif direction=='SOUTH':bed=[(x,z0-q) for x in range(x0+3,x0+w-3) for q in range(2,5)]
            elif direction=='WEST':bed=[(x0+w+q,z) for z in range(z0+3,z0+d-3) for q in range(2,5)]
            else:bed=[(x0-q,z) for z in range(z0+3,z0+d-3) for q in range(2,5)]
            valid=[]
            for x,z in bed:
                if (x,z) in blocked or (x,z) in road or heights.get((x,z))!=y:continue
                if any((x,yy,z) in cells for yy in (y+1,y+2)) or cells.get((x,y,z),{}).get('name') in (None,'minecraft:water'):continue
                put(x,y,z,'minecraft:grass_block','landscape:group-edges');counts['BED']=counts.get('BED',0)+1;writes.append([x,y,z,'BED']);valid.append([x,z])
                if not templated and (x+z)%3==0:
                    put(x,y+1,z,'minecraft:allium','landscape:group-edges');counts['FLOWER']=counts.get('FLOWER',0)+1;writes.append([x,y+1,z,'FLOWER'])
            if valid:edges.append(dict(memberId=m['memberId'],columns=valid))
        plans.append(dict(nodeId='group-edges',kind='EDGE_PLANTING',beds=edges,counts=counts,writes=writes))
    from gap_landscape import apply_gap_landscape
    gap_plan=apply_gap_landscape(result,cells,sources,put)
    if gap_plan is not None:plans.append(gap_plan)
    return plans
