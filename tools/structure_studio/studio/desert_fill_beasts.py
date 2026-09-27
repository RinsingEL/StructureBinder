"""Reference-sheet redesign of four desert animal yards; static architecture only."""
from .model import Model
from .samples import railing

PLANS = [
    ('路边小兽栏', (31,15,29), '宽阔单栏、赤陶色遮棚、东侧鞍具值守小屋与前置饮水槽。'),
    ('围院兽栏', (41,15,39), '六个分栏朝向中央宽牵引道，后端低矮值守翼，连续分段遮阴棚。'),
    ('护理兽栏', (37,15,35), '两侧安静隔栏、后部开放护理棚与独立值守室，中央护理等候院。'),
    ('商队换乘场', (45,15,35), '后部连续长棚、前置短时栏、中央搬运横道和侧边鞍具寄存小屋。'),
]


def canopy(m,x0,z0,x1,z1,y=7,color='terracotta',wood=False):
    for x in (x0,x1):
        for z in (z0,z1):
            m.box((x,2,z),(x,y-1,z),'stripped_spruce_log[axis=y]')
            m.set(x, y-2, z+1 if z==z0 else z-1, 'lantern[hanging=true]')
        m.box((x,y-1,z0),(x,y-1,z1),'spruce_slab[type=top]')
    for z in (z0,z1):m.box((x0,y-1,z),(x1,y-1,z),'spruce_slab[type=top]')
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            block='spruce_slab[type=bottom]' if wood else ('white_wool' if x in (x0,x1) else color)
            m.set(x,y+(1 if not wood and x0+2<=x<=x1-2 else 0),z,block)


def rail(m,x0,z0,x1,z1):
    axis='x' if z0==z1 else 'z'
    railing(m,(x0,2,z0),(x1,2,z1),'spruce',axis)
    for x,z in ((x0,z0),(x1,z1)):
        m.box((x,2,z),(x,3,z),'cut_sandstone')
        m.set(x,4,z,'sandstone_slab[type=bottom]')


def pen(m,key,x0,z0,x1,z1,side='east',roof=True,color='terracotta'):
    for x in range(x0+1,x1):
        for z in range(z0+1,z1):m.set(x,1,z,'coarse_dirt' if (x+z)%5 else 'podzol')
    rail(m,x0,z0,x1,z0);rail(m,x0,z1,x1,z1)
    rail(m,x0,z0,x0,z1);rail(m,x1,z0,x1,z1)
    gx=x1 if side=='east' else x0 if side=='west' else (x0+x1)//2
    gz=(z0+z1)//2 if side in ('east','west') else z0
    for n in (-1,0,1):
        x,z=(gx,gz+n) if side in ('east','west') else (gx+n,gz)
        m.box((x,2,z),(x,4,z),'air')
        m.set(x,2,z,f'spruce_fence_gate[facing={side},open=true]')
    m.box((x0+2,2,z1-3),(x0+3,3,z1-2),'hay_block[axis=y]')
    m.point(key+'_feed','work',(x0+2,2,z1-3),'草料取放',approach=(x0+2,2,z1-4))
    # Cauldrons remain contained under vanilla fluid updates.
    for x in range(x0+2,min(x0+5,x1-1)):m.set(x,2,z0+2,'water_cauldron[level=3]')
    m.point(key+'_water','work',(x0+2,2,z0+2),'栏内饮水',approach=(x0+2,2,z0+3))
    m.point(key+'_gate','circulation',(gx,2,gz),'三格牵引开口')
    if roof:canopy(m,x0+1,max(z0+1,z1-9),x1-1,z1-1,color=color)
    m.room(key,'遮阴兽栏' if roof else '短时停留栏',(x0+1,2,z0+1),(x1-1,6,z1-1),'饮水、饲料与静态栏位；开口三格')


def service(m,x0,z0,x1,z1):
    m.box((x0,2,z0),(x1,7,z1),'sandstone')
    m.box((x0+1,2,z0+1),(x1-1,6,z1-1),'air')
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,2,z),(x,7,z),'cut_sandstone')
    for z in (z0,z1):
        for x in range(x0+1,x1):m.set(x,5,z,'terracotta')
        wx=(x0+x1)//2
        m.set(wx,3,z,'glass_pane')
        m.set(wx,4,z,'spruce_trapdoor[facing=north,half=bottom,open=true]')
    for z in range(z0+1,z1):m.set(x1,5,z,'terracotta')
    for x in range(x0+1,x1):m.set(x,6,z0,'smooth_sandstone')
    m.box((x0,8,z0),(x1,8,z1),'sandstone_slab[type=bottom]')
    m.box((x0+1,8,z0+1),(x1-1,8,z1-1),'air')
    dz=z0+3;m.door(x0,2,dz,facing='west')
    m.set(x0,4,z1-2,'spruce_trapdoor[facing=west,half=bottom,open=true]')
    m.set(x0,3,z1-2,'air')
    m.set(x1,4,z0+3,'glass_pane')
    m.bed(x1-2,2,z1-2,color='brown',facing='north')
    m.point('keeper_bed','bed',(x1-2,2,z1-2),'值守床',approach=(x1-3,2,z1-2))
    m.set(x0+2,2,z0+1,'lectern[facing=south]')
    m.point('register','work',(x0+2,2,z0+1),'换乘登记',approach=(x0+2,2,z0+2))
    m.set(x1-1,2,z0+1,'barrel[facing=west]')
    m.set(x1-1,3,z0+1,'barrel[facing=west]')
    m.set(x1-1,2,z0+3,'crafting_table')
    m.point('tack','work',(x1-1,2,z0+3),'鞍具修补',approach=(x1-2,2,z0+3))
    m.set(x1-1,4,z0+2,'tripwire_hook[facing=west]')
    m.set(x0+3,6,z0+3,'lantern[hanging=true]')
    m.room('keeper','鞍具与值守室',(x0+1,2,z0+1),(x1-1,6,z1-1),'登记、鞍具修补、寄存、值守睡眠')
    canopy(m,x0-3,z0+1,x0-1,z1-1,y=6,wood=True)


def beast_yard(variant):
    name,size,desc=PLANS[variant-1]
    m=Model(f'DS-02-v{variant:02d}',name,size,family='DS-02',civilization='沙漠',role='fill',terrain={'选址':'连续承载的平坦地块；入口连接商路并有可靠饮水饲料来源。','接地':'外部地面脚底 Y=2，实体地坪顶面；基础底部 Y=0 不作为落地线。'})
    w,_,d=size
    m.meta.update(source=f'tools/structure_studio/studio/desert_fill_beasts.py:beast_yard({variant})',design_notes=[desc],differences=[desc],ground_plane={'y':2,'note':'外围道路与院内地坪统一脚底 Y=2，地坪方块在 Y=1；不以基础最低层定位。'},roof_min_y=6,floors=[{'name':'牵引通道、兽栏与值守内饰','y':1,'max_y':5}],preview_context={'kind':'flat','land_surface_y':2,'bed_y':-1,'padding':3,'surface':'sand'})
    m.meta['offline_limits'].append('静态栏位和护理设备，无动物实体；动物尺寸、牵引、回转与围栏行为未做游戏验收。')
    m.box((1,0,1),(w-2,0,d-2),'sandstone')
    m.box((1,1,1),(w-2,1,d-2),'smooth_sandstone')
    m.box((1,2,1),(w-2,12,d-2),'air')
    for x in range(2,w-2):
        for z in range(2,d-2):
            if (x*17+z*7)%19<4:m.set(x,1,z,'sandstone')
    # Low perimeter keeps view open and makes a coherent compound silhouette.
    rail(m,2,2,2,d-3);rail(m,w-3,2,w-3,d-3);rail(m,2,d-3,w-3,d-3)
    cx=w//2
    rail(m,2,2,cx-3,2);rail(m,cx+3,2,w-3,2)
    m.point('entry','entrance',(cx,2,2),'五格牵引主入口',facing='north')
    m.meta['connections'].append({'kind':'animal','pos':[cx,1,2],'direction':'north','clearance':[5,5],'note':'五格宽几何预留，须另外验证具体实体及外部接驳。'})
    if variant==1:
        pen(m,'main',4,7,17,24)
        service(m,21,15,27,25)
        canopy(m,21,6,27,11,y=6,wood=True)
        m.box((23,2,7),(25,3,8),'hay_block[axis=y]')
        m.point('feed_store','work',(23,2,8),'遮棚饲料储备',approach=(23,2,9))
    elif variant==2:
        for i,(z0,z1) in enumerate(((5,13),(15,23),(25,33))):
            pen(m,f'west{i}',4,z0,14,z1,'east',color='white_wool' if i==1 else 'terracotta')
            pen(m,f'east{i}',26,z0,36,z1,'west',color='white_wool' if i==1 else 'terracotta')
        # Registration cabin sits at end of main lane without blocking it.
        service(m,17,27,23,36)
    elif variant==3:
        pen(m,'quiet',4,5,14,16,'east')
        pen(m,'recovery',4,20,14,30,'east',color='white_wool')
        pen(m,'observe',23,5,32,15,'west')
        service(m,25,21,33,31)
        canopy(m,16,24,21,31,y=7,color='white_wool')
        m.set(17,2,29,'grindstone[face=floor,facing=north]')
        m.set(20,2,29,'water_cauldron[level=3]')
        m.set(20,2,30,'barrel[facing=north]')
        m.point('care','work',(17,2,29),'遮阴护理工作位',approach=(18,2,29))
        m.room('care','开放护理棚',(16,2,24),(21,6,31),'护理工具、清水和等候操作空地')
    else:
        for i,(x0,x1) in enumerate(((4,13),(15,24),(26,33))):
            pen(m,f'long{i}',x0,20,x1,30,'north',color='terracotta' if i==1 else 'white_wool')
        pen(m,'shortA',4,5,14,13,'east',roof=False)
        pen(m,'shortB',25,5,34,13,'west',roof=False)
        service(m,37,20,42,31)
        canopy(m,36,5,41,13,y=6,wood=True)
        for z in (6,9):m.box((39,2,z),(40,3,z+1),'barrel[facing=west]')
        m.point('cargo','work',(39,2,9),'旅具交接暂存',approach=(38,2,9))
        m.room('cargo','侧边旅具寄放棚',(36,2,5),(41,5,13),'寄存、装卸、交接')
    return m
