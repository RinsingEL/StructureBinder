from pathlib import Path
p=Path('tools/city_experiments/designed_landscape.py');s=p.read_text(encoding='utf-8');s=s.replace('    return plans\n', '''    # Group edges receive linear planting beds, not unstructured scatter.
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
                if (x+z)%3==0:
                    put(x,y+1,z,'minecraft:allium','landscape:group-edges');counts['FLOWER']=counts.get('FLOWER',0)+1;writes.append([x,y+1,z,'FLOWER'])
            if valid:edges.append(dict(memberId=m['memberId'],columns=valid))
        plans.append(dict(nodeId='group-edges',kind='EDGE_PLANTING',beds=edges,counts=counts,writes=writes))
    return plans
''');p.write_text(s,encoding='utf-8')
p=Path('tools/structure_studio/scripts/capture-campus-groups.mjs');s=p.read_text(encoding='utf-8').replace('assert.equal(meta.publicSpacePlans.length,2)','assert.equal(meta.publicSpacePlans.length,3)');p.write_text(s,encoding='utf-8')
