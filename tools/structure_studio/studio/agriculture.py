"""Six distinct farm plans: shapes, access, irrigation, crop and elevation vary."""
from .model import Model
from .components import column,pendant


PLANS=[
    ("窄条麦薯田",11,29,"窄长地块，中央灌溉沟，两侧田埂；麦与马铃薯轮作"),
    ("十字菜园",23,21,"四块短矩形菜畦围绕十字作业道；每畦独立取水点与不同作物"),
    ("院边 L 形田",25,25,"L 形田沿院墙或道路转角包边，凹口保留给邻地；双向作业道"),
    ("斜巷楔形菜田",18,28,"逐段收窄的街巷边角地块；边界随可用地面收束，水沟按宽度分段"),
    ("三渠长田与灌溉口",27,31,"较长生产地块，三条独立水沟、横向运输道和田头工具棚"),
    ("三台微坡梯田",25,24,"三级一格高差，横向等高灌溉沟；田埂阶梯贯通三个作业面"),
]


def farm(variant):
    name,w,d,description=PLANS[variant-1]
    m=Model(f"SR-F02-v{variant:02d}",name,(w,10,d),family="SR-F02",civilization="铜钟公国",role="fill",terrain={
        "选址":"铁路聚落郊区；需要土壤、足够光照与可维护的水源", "地块":description,
        "地坪":"主要田埂脚底 Y=2；坡地版沿 +Z 每 7 格升高 1 格" if variant==6 else "田埂脚底 Y=2；平地版本",
        "落地方式":"固定模板田块；独立成品，非 Landscape 自然生长大田",
        "边界":"仅填入实际田块与田埂范围；形状外空格不参与清地，凹口和收窄边界留给地形或邻地"})
    m.meta.update(design_notes=[description,"成熟作物与少量待收行表现连续生产；水沟保持开放维护，工具位置避开主要通行。"],
        differences=[description],floors=[dict(name="作物与作业通路",y=0,max_y=5)],
        source=f"tools/structure_studio/studio/agriculture.py:farm({variant})",
        preview_context=dict(kind="slope" if variant==6 else "flat",land_surface_y=2,padding=3,bed_y=-1,run=7,rise=1,slope_origin_z=0))
    if variant==1:
        footprint={(x,z) for x in range(1,10) for z in range(1,28)}
        path={(x,z) for x,z in footprint if x in (1,2,8,9) or z in (1,2,14,26,27)}
        water={(5,z) for z in range(3,26) if z!=14}
        crop_names=["wheat","potatoes"];gate=(2,1);tool=(8,3)
    elif variant==2:
        footprint={(x,z) for x in range(1,22) for z in range(1,20)}
        path={(x,z) for x,z in footprint if x in (1,10,11,12,21) or z in (1,9,10,11,19)}
        water={(5,5),(17,5),(5,15),(17,15)}
        # A second source balances each deliberately broad bed's edge hydration.
        water|={(8,5),(14,5),(8,15),(14,15)}
        crop_names=["carrots","beetroots","potatoes","wheat"];gate=(11,1);tool=(20,2)
    elif variant==3:
        footprint={(x,z) for x in range(1,24) for z in range(1,24) if x<=10 or z>=14}
        path={(x,z) for x,z in footprint if x in (1,9,10,23) or z in (1,14,22,23)}
        water={(4,z) for z in range(2,22) if z!=14}|{(x,18) for x in range(11,23)}
        crop_names=["carrots","potatoes"];gate=(9,1);tool=(2,22)
    elif variant==4:
        footprint={(x,z) for z in range(1,27) for x in range(1,6+z//3)}
        path={(x,z) for x,z in footprint if x in (1,2) or z in (1,13,26) or (x+1,z) not in footprint}
        water={(x,z) for x,z in footprint if x in (5,11) and z not in (1,13,26) and (x+1,z) in footprint}
        crop_names=["beetroots","carrots"];gate=(2,1);tool=(2,24)
    elif variant==5:
        footprint={(x,z) for x in range(1,26) for z in range(1,30)}
        path={(x,z) for x,z in footprint if x in (1,2,24,25) or z in (1,2,15,28,29) or (x>=19 and z<=6)}
        water={(x,z) for x in (6,13,20) for z in range(3,28) if (x,z) not in path}
        crop_names=["wheat","carrots","potatoes"];gate=(2,1);tool=(22,3)
    else:
        footprint={(x,z) for x in range(1,24) for z in range(1,22)}
        path={(x,z) for x,z in footprint if x in (1,18,19,20,21,22,23) or z in (1,6,7,13,14,20,21)}
        water={(x,z) for z in (3,10,17) for x in range(2,18)}
        crop_names=["wheat","carrots","beetroots"];gate=(20,1);tool=(22,18)
    # Open boundaries remain a narrow grass verge; source water is contained.
    edge={p for p in footprint if any((p[0]+dx,p[1]+dz) not in footprint for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)))}
    path|=edge
    crop=footprint-path-water
    for x,z in sorted(footprint):
        level=z//7 if variant==6 else 0;y=1+level
        m.box((x,0,z),(x,y,z),"dirt")
        m.box((x,y+1,z),(x,y+5,z),"air")
        if (x,z) in water:m.set(x,y,z,"water[level=0]")
        elif (x,z) in path:m.set(x,y,z,"mossy_cobblestone" if (x+2*z)%7==0 else "gravel")
        else:
            m.set(x,y,z,"farmland[moisture=7]")
            group=(x//7 if variant in (1,5) else z//7) if variant!=2 else (2 if z>10 else 0)+(1 if x>11 else 0)
            plant=crop_names[group%len(crop_names)]
            age=3 if plant=="beetroots" else (6 if z%5==0 else 7)
            m.set(x,y+1,z,f"{plant}[age={age}]")
    # Covered bridges across water at working crossings; corners stay blocked to fluids.
    for x,z in sorted(path):
        if variant==6 and z in (7,14) and 19<=x<=21:
            m.set(x,1+z//7,z,"cobblestone_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
    # The entrance is a useful three-wide step from surrounding lower ground.
    gx,gz=gate
    for x in range(max(0,gx-1),min(w,gx+2)):
        m.set(x,1,0,"cobblestone_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
    ty=2+(tool[1]//7 if variant==6 else 0);tx,tz=tool
    m.set(tx,ty,tz,"composter[level=4]")
    # Accessible supplies replace only edge-path cells, never a hidden crop row.
    bx=tx if tx+1>=w-1 else tx+1
    bz=tz+1 if (tx,tz+1) in path else tz-1
    if (bx,bz) in footprint:
        by=2+(bz//7 if variant==6 else 0)
        m.set(bx,by,bz,"barrel[facing=up,open=false]")
        m.set(bx,by+1,bz,"lantern[hanging=false,waterlogged=false]")
    if variant==5:
        for x,z in ((19,2),(25,2),(19,6),(25,6)):column(m,x,z,2,5,"spruce_log","spruce_planks")
        m.box((18,6,1),(26,6,7),"spruce_slab[type=top,waterlogged=false]")
        pendant(m,22,5,5,6)
    if variant==2:
        m.set(11,2,10,"water_cauldron[level=3]")
        m.box((12,2,10),(12,3,10),"mossy_cobblestone")
        m.set(11,3,10,"tripwire_hook[facing=west,attached=false,powered=false]")
    # Every crop needs water at its own terrace. This is a design invariant.
    dry=[]
    for x,z in crop:
        yy=1+(z//7 if variant==6 else 0)
        if not any(abs(x-wx)<=4 and abs(z-wz)<=4 and 1+(wz//7 if variant==6 else 0) in (yy,yy+1) for wx,wz in water):dry.append((x,z))
    if dry:raise ValueError(f"{m.meta['id']}: dry planting cells {dry[:12]}")
    m.room("field","种植与作业面",(1,2,1),(w-2,6,d-2),description)
    m.point("gate","entrance",(gx,2,gz),"田头入口",facing="north")
    # Find an explicit adjacent dry path position for the compost workstation.
    approach=next((p for p in ((tx-1,tz),(tx+1,tz),(tx,tz-1),(tx,tz+1)) if p in path and p!=(bx,bz)),None)
    if approach is None:raise ValueError("No tool approach")
    ax,az=approach;ay=2+(az//7 if variant==6 else 0)
    m.point("compost","work",(tx,ty,tz),"堆肥与工具点",approach=(ax,ay,az))
    if variant==6:
        for i,z in enumerate((5,12,19),1):m.point(f"terrace_{i}","circulation",(19,2+z//7,z),f"第 {i} 台田埂",look_at=[12,2+z//7,z])
    m.meta["agriculture"]=dict(crops=crop_names,planted_cells=len(crop),irrigation_sources=len(water),layout=description,hydration="每个种植格已核对同高程或高一格、水平 4 格范围水源",template_mode="independent_fixed_plot")
    m.meta["connections"]=[dict(kind="pedestrian",pos=[gx,1,0],direction="north",clearance=[3,3],note="田头台阶；外部道路需要衔接入口高程")]
    return m
