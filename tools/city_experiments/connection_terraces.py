"""Generic near-neighbour terrace infill and exposed-boundary reconstruction."""
from gap_landscape import DIRS,plan_gap_regions,road_level_targets


def synchronize_road_sides(result,targets,ground,occupied,pairs):
    """A triggered cross-section supplies both verges, up to existing platforms."""
    road={(f['x'],f['z']) for f in result['surfacePrintPlan']['featureCells'] if f['surfaceOffset']==0 and f['kind'] in ('ROAD_SLAB','ROAD_STAIR')}
    blocked=set()
    for f in result.get('fixtures',[]):
        p=f['originBlock'];blocked.update((x,z) for x in range(p['x']-2,p['x']+f['width']+2) for z in range(p['z']-2,p['z']+f['depth']+2))
    water={p for p,r in ground.items() if r[1]=='minecraft:water'}
    shore={(x+dx,z+dz) for x,z in water for dx in range(-2,3) for dz in range(-2,3)}
    axes={}
    for band in result.get('roadBands',[]):
        b=band['bounds'];vertical=bool(band.get('axisZ')) or (not band.get('axisX') and b['maxZ']-b['minZ']>=b['maxX']-b['minX'])
        for p in road:
            if b['minX']<=p[0]<=b['maxX'] and b['minZ']<=p[1]<=b['maxZ']:axes.setdefault(p,set()).add(0 if vertical else 1)
    # Band metadata omits some internal streets. Infer their tangent from the
    # actual surface footprint, comparing the local longitudinal runs.
    for p in road:
        if p in axes:continue
        runs=[]
        for axis in (0,1):
            length=1
            for sign in (-1,1):
                for n in range(1,9):
                    q=(p[0]+sign*n,p[1]) if axis==0 else (p[0],p[1]+sign*n)
                    if q not in road:break
                    length+=1
            runs.append(length)
        axes[p]={0} if runs[1]>runs[0] else ({1} if runs[0]>runs[1] else {0,1})
    added=[];expanded=dict(targets);maximum=result['scene'].get('smallGapMaxBlocks',32)
    for p,(y,seed,d) in sorted(targets.items()):
        for axis in sorted(axes.get(seed,())):
            tangent=1-axis
            if p[tangent]!=seed[tangent]:continue
            lo=hi=seed[axis]
            def point(n):return (n,seed[1]) if axis==0 else (seed[0],n)
            while seed[axis]-lo<16 and point(lo-1) in road:lo-=1
            while hi-seed[axis]<16 and point(hi+1) in road:hi+=1
            if p[axis]<lo:start,sign,width=hi+1,1,lo-p[axis]
            elif p[axis]>hi:start,sign,width=lo-1,-1,p[axis]-hi
            else:continue
            for offset in range(min(width,maximum)):
                q=point(start+sign*offset);native=ground.get(q)
                if q in occupied or q in road or q in blocked or q in shore or native is None:break
                if abs(native[0]-y)>6 or any(abs(ground.get((q[0]+dx,q[1]+dz),native)[0]-native[0])>1 for dx,dz in DIRS):break
                if q not in expanded:
                    expanded[q]=(y,seed,offset+1);pairs[q]=pairs[p];added.append(q)
    return expanded,added


def apply_connection_terraces(result,cells,sources,put):
    scene=result['scene']
    if scene.get('gapSurface')!='CONNECTION_TERRACE' or not scene.get('gapLandscape'):return
    regions,ground,roads,occupied=plan_gap_regions(result,road_level=True)
    targets=road_level_targets(result,regions,ground)
    groups={g['terraceId']:g for g in scene.get('terraceGroups',[])}
    styles={s['styleId']:s for s in scene.get('terraceStyles',[])}
    datums={t['terraceId']:t['datumY'] for t in result['terraces']}
    pair_by_cell={p:r['pair'] for r in regions for p in r['columns']}
    targets,synchronized=synchronize_road_sides(result,targets,ground,occupied,pair_by_cell)
    # Added cells inherit the triggering region for planting and boundary review.
    extra=set(synchronized)
    for region in regions:
        region['columns'].update(p for p in extra if pair_by_cell[p]==region['pair'])
        extra.difference_update(region['columns'])
    style_by_owner={t:styles.get(groups.get(t,{}).get('styleRef'),{}) for t in datums}
    accepted={};writes=[]
    def emit(x,y,z,block,kind):
        put(x,y,z,block,'infrastructure:connection-terrace');writes.append([x,y,z,kind])
    for (x,z),(y,seed,d) in sorted(targets.items()):
        native=ground[(x,z)][0]
        if any(sources.get((x,yy,z),'') not in ('','terrain') for yy in range(min(native,y)-2,max(native,y)+9)):continue
        style=style_by_owner[pair_by_cell[(x,z)][0]];materials=style.get('materials',{})
        wall=materials.get('retainingWall','minecraft:stone_bricks');surface=materials.get('ground','minecraft:smooth_stone')
        for yy in range(y+1,native+1):emit(x,yy,z,'minecraft:air','CLEAR')
        for yy in range(min(native-2,y-2),y):emit(x,yy,z,wall,'SUPPORT')
        emit(x,y,z,surface,'SURFACE')
        accepted[(x,z)]=dict(x=x,z=z,targetY=y,nativeY=native,roadX=seed[0],roadZ=seed[1],distance=d)
    # A former core edge is internal only when its whole local boundary is now
    # supported at that height. Preserve guards wherever a real drop remains.
    heights={p:datums[owner] for p,owner in occupied.items()}
    heights.update({p:c['targetY'] for p,c in accepted.items()})
    heights.update({(f['x'],f['z']):f['targetSurfaceY'] for f in result['surfacePrintPlan']['featureCells'] if f['surfaceOffset']==0})
    removed=[]
    for x,y,z,block,kind in result['mutations']:
        if not kind.startswith('TERRACE_EDGE_') or (x,z) not in occupied:continue
        p=(x,z);datum=heights[p]
        touching=any((x+dx,z+dz) in accepted for dx,dz in DIRS)
        closed=all(heights.get((x+dx,z+dz),-999)>=datum for dx,dz in DIRS)
        if touching and closed and sources.get((x,y,z))=='infrastructure' and y>datum:
            emit(x,y,z,'minecraft:air','REMOVE_INTERNAL_EDGE');removed.append([x,y,z])
    outer=[]
    for (x,z),c in accepted.items():
        y=c['targetY'];style=style_by_owner[pair_by_cell[(x,z)][0]]
        exposed=any(heights.get((x+dx,z+dz),ground.get((x+dx,z+dz),(y,))[0])<y-1 for dx,dz in DIRS)
        if not exposed or (x,z) in roads:continue
        if (x,y+1,z) in cells:continue
        emit(x,y+1,z,style.get('railBlock','minecraft:stone_brick_wall'),'OUTER_RAIL');outer.append([x,z])
    result['connectionTerracePlan']=dict(kind='CONNECTION_TERRACE',maximumGapBlocks=scene.get('smallGapMaxBlocks',32),
        synchronizedColumns=[list(p) for p in synchronized if p in accepted],
        columns=list(accepted.values()),removedInternalEdges=removed,outerRailColumns=outer,writes=writes,
        regions=[dict(terracePair=r['pair'],columns=[list(p) for p in sorted(r['columns']&accepted.keys())]) for r in regions if r['columns']&accepted.keys()])
