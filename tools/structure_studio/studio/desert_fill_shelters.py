"""Reference sheet 8: working vine trellises and small trading shelters."""
from .model import Model
from .oasis_life import paved


def base(family, v, name, size, note):
    m = Model(f'{family}-v{v:02d}', name, size, family=family, civilization='沙漠', role='fill', terrain={'选址':'平缓街边或灌溉庭院', '接地':'外部地面方块 Y=1，人员脚底 Y=2'})
    m.meta.update(source=f'tools/structure_studio/studio/desert_fill_shelters.py:{"vineyard" if family=="DS-F03" else "trade_shelter"}({v})', ground_plane={'y':2,'note':'外部铺地顶面与人员脚底高度，铺地方块 Y=1，外地表站立面 Y=2'}, design_notes=[note], differences=[note], roof_min_y=6, floors=[dict(name='地面作业与通路',y=1,max_y=5)], preview_context=dict(kind='flat',land_surface_y=2,bed_y=-1,padding=3,surface='sand'))
    paved(m,{(x,z) for x in range(1,size[0]-1) for z in range(1,size[2]-1)})
    # Paved edges and a weathered working floor define the small parcel.
    for x in range(1,size[0]-1):
        for z in range(1,size[2]-1):
            if x in (1,size[0]-2) or z in (1,size[2]-2):m.set(x,1,z,'cut_sandstone')
            elif (x*7+z*11)%17<3:m.set(x,1,z,'sandstone')
            elif (x*13+z*3)%23==0:m.set(x,1,z,'gravel')
    return m


def entry(m,x,z,key='entry',facing='north'):
    m.point(key,'entrance',(x,2,z),'人员与手推货物入口',facing=facing)
    m.meta['connections'].append(dict(kind='pedestrian',pos=[x,1,z],direction=facing,clearance=[3,3],note='外部连接地面 Y=1'))


def work(m,key,x,z,name,ax,az,block='barrel[facing=up]'):
    m.set(x,2,z,block)
    m.point(key,'work',(x,2,z),name,approach=(ax,2,az))


def canopy(m,x0,z0,x1,z1,y=6,cloth=False):
    for x in (x0,x1):
        for z in (z0,z1):
            m.set(x,2,z,'cut_sandstone');m.box((x,3,z),(x,y-1,z),'spruce_fence')
        m.box((x,y-1,z0),(x,y-1,z1),'stripped_spruce_log[axis=z]')
    for z in (z0,z1):m.box((x0,y-1,z),(x1,y-1,z),'stripped_spruce_log[axis=x]')
    for x in range(x0,x1+1):
        m.box((x,y,z0),(x,y,z1),('white_wool' if (x-x0)%5<3 else 'red_terracotta') if cloth else 'spruce_slab[type=bottom]')
    m.set(x0+1,y-2,z0,'lantern[hanging=true]')
    m.set(x1-1,y-2,z1,'lantern[hanging=true]')
    # Visible timber brackets instead of an unsupported featureless canopy.
    for x in (x0,x1):
        for z in (z0+1,z1-1):m.set(x,y-2,z,'spruce_fence')
    if cloth:
        for x in range(x0,x1+1):
            if x%3==0:m.set(x,y,z0,'spruce_slab[type=bottom]')


def trellis(m,key,x0,z0,x1,z1):
    # Load-bearing posts and open rafters support the leafy crop representation.
    for x in (x0,x1):
        for z in range(z0,z1+1,4):
            m.set(x,2,z,'cut_sandstone');m.box((x,3,z),(x,5,z),'spruce_fence')
        m.box((x,6,z0),(x,6,z1),'stripped_spruce_log[axis=z]')
    for z in range(z0,z1+1,2):
        m.box((x0,6,z),(x1,6,z),'spruce_slab[type=bottom]')
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            if (x+z)%5: m.set(x,7,z,'oak_leaves[persistent=true]')
    for x in (x0,x1):
        for z in range(z0,z1+1):
            m.set(x,1,z,'coarse_dirt')
            if (z-z0)%4==0:
                m.set(x,3,z,'stripped_spruce_log[axis=y]')
            elif (z-z0)%4==2:
                m.set(x,2,z,'oak_fence')
                m.set(x,3,z,'oak_leaves[persistent=true]')
    for z in range(z0+1,z1,3):
        for x in (x0,x1):
            m.set(x,5,z,'purple_stained_glass_pane[north=true,south=true]')
            m.set(x,6,z,'oak_leaves[persistent=true]')
    m.room(key,'葡萄棚采收道',(x0+1,2,z0),(x1-1,5,z1),'原版叶块与紫色玻璃表现葡萄；中间保留贯通采收道')
    m.point(key+'_walk','circulation',((x0+x1)//2,2,(z0+z1)//2),'棚下采收路线')


def vineyard(variant):
    names=['单列窄长葡萄棚','双列采收葡萄棚','L形转角葡萄棚','沿墙葡萄分拣庭']
    sizes=[(15,11,29),(25,11,29),(28,11,27),(26,11,21)]
    notes=['长棚和侧边步道形成窄地块，前后均可采收进入。','两列藤架夹中央搬运道，后端独立分拣棚。','两段相交棚架围绕开放转角作业庭，凹角保持宽通路。','长院墙承托边棚，侧面小分拣棚形成不对称工作庭。']
    m=base('DS-F03',variant,names[variant-1],sizes[variant-1],notes[variant-1])
    m.meta['offline_limits'].append('葡萄由原版常绿叶块与紫色玻璃静态表达，不提供葡萄种植或收获功能。')
    if variant==1:
        trellis(m,'long',3,4,8,24);entry(m,10,2)
        work(m,'baskets',11,8,'采收筐',10,8)
        work(m,'tools',11,22,'采收工具',10,22,'crafting_table')
        for z in (5,13,21):m.set(2,2,z,'sandstone_wall')
        entry(m,10,26,'rear','south')
    elif variant==2:
        trellis(m,'west',3,4,8,20);trellis(m,'east',16,4,21,20)
        canopy(m,9,22,15,26,cloth=True);entry(m,12,2)
        work(m,'sort',10,25,'采收分拣台',11,25,'crafting_table')
        work(m,'baskets',14,25,'装筐暂存',13,25)
    elif variant==3:
        trellis(m,'west',3,4,8,22);trellis(m,'north',9,4,23,9)
        entry(m,15,24,facing='south');entry(m,25,15,'side','east')
        work(m,'sort',10,19,'转角分拣',11,19,'crafting_table')
        work(m,'basket',10,21,'采收筐',11,21)
        m.box((14,2,11),(20,2,11),'sandstone_wall')
        m.set(21,2,11,'water_cauldron[level=3]')
    else:
        m.box((2,2,3),(23,5,3),'sandstone');m.box((2,4,3),(23,4,3),'terracotta')
        trellis(m,'wall',3,4,8,16)
        # Second arm along wall is shorter, leaving the front court open.
        trellis(m,'cross',9,4,20,9)
        canopy(m,14,12,22,17,cloth=True);entry(m,11,18,facing='south')
        work(m,'sorting',20,15,'分拣桌',19,15,'crafting_table')
        work(m,'crates',20,16,'装筐货位',19,16)
        work(m,'water',12,5,'工具清洗用水',12,6,'water_cauldron[level=3]')
    # Low perimeter fragments and drying baskets mark the working edge.
    w,_,d=m.size
    for x,z in ((2,2),(w-3,2),(w-3,d-3)):
        m.set(x,2,z,'sandstone_wall')
        m.set(x,3,z,'lantern')
    return m


def counter(m,x0,x1,z):
    for x in range(x0,x1+1):
        m.set(x,2,z,'barrel[facing=up]')
        m.set(x,3,z,'yellow_carpet' if x%3 else 'red_carpet')
    for x in (x0,x1):m.set(x,3,z,'flower_pot')


def trade_shelter(variant):
    names=['独立遮阳单摊','双面交接小摊','墙边寄货棚','贯通登记货棚']
    sizes=[(16,11,17),(23,12,21),(23,12,18),(25,14,25)]
    notes=['小后室接前部低布棚，单侧交易台与侧向摊主入口。','两侧经营面共享中央库存，前后通道与错落双棚。','自身厚背墙支撑长棚，前端交接后端分格寄货。','中央直通搬运巷，两侧货位和侧后登记凉亭，不封堵前后入口。']
    m=base('DS-F04',variant,names[variant-1],sizes[variant-1],notes[variant-1])
    if variant==1:
        m.box((5,2,10),(12,5,13),'sandstone');m.box((6,2,10),(11,4,12),'air')
        m.box((5,6,10),(12,6,13),'smooth_sandstone_slab')
        canopy(m,3,4,12,9,cloth=True);counter(m,4,9,6);entry(m,10,2)
        work(m,'stock',10,12,'摊后库存',10,11)
        work(m,'sell',6,6,'交易台',6,5)
        m.point('keeper','circulation',(7,2,8),'摊主整理通道')
    elif variant==2:
        canopy(m,3,4,10,15,cloth=True);canopy(m,12,5,19,16,y=7,cloth=True)
        counter(m,4,9,6);counter(m,13,18,14)
        for x in range(6,17,2):m.set(x,2,10,'barrel[facing=north]')
        entry(m,11,2);entry(m,11,18,'rear','south')
        work(m,'front_trade',5,6,'前侧交易',5,5)
        work(m,'rear_trade',17,14,'后侧交易',17,15)
        work(m,'stock',10,10,'共享库存',10,9)
    elif variant==3:
        m.box((3,2,13),(19,7,14),'sandstone');m.box((3,5,12),(19,5,12),'terracotta')
        canopy(m,3,5,19,12,cloth=False)
        counter(m,4,8,6);entry(m,11,2)
        for x in (4,8,13,17):
            m.box((x,2,10),(x+1,3,11),'barrel[facing=north]')
        work(m,'register',6,6,'寄货交接',6,5)
        work(m,'warehouse',13,10,'分格寄货',13,9)
        m.room('store','干燥寄货区',(10,2,7),(18,5,12),'分格堆货与宽卸货通道')
    else:
        canopy(m,4,5,19,18,y=8,cloth=True)
        # Narrower taller side kiosk gives a distinct silhouette without a false inaccessible roof.
        canopy(m,17,17,22,22,y=10,cloth=True)
        for x in (5,17):
            for z in (8,12,16):
                m.box((x,2,z),(x+1,3,z+1),'barrel[facing=up]')
        for x in (4,20):m.box((x,2,7),(x,2,18),'sandstone_wall')
        entry(m,12,2);entry(m,12,22,'rear','south')
        work(m,'west_goods',6,12,'西侧周转货物',7,12)
        work(m,'east_goods',17,12,'东侧待交货物',16,12)
        work(m,'register',20,20,'看管登记',19,20,'lectern[facing=west]')
        work(m,'water',18,21,'搬运饮水',18,20,'water_cauldron[level=3]')
        m.room('through','前后贯通搬运道',(9,2,3),(15,6,22),'主搬运巷与两侧存货区')
    w,_,d=m.size
    for x,z in ((2,3),(w-3,d-3)):
        m.set(x,2,z,'barrel[facing=up]')
        m.set(x,3,z,'flower_pot')
    # A merchandise shelf at the rear of each parcel stays out of the main routes.
    for x in (3,4):
        m.set(x,2,d-3,'barrel[facing=north]')
        m.set(x,3,d-3,'spruce_slab[type=bottom]')
    return m
