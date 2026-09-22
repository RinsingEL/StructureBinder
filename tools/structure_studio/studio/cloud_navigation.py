"""Cloud navigation: grounded cliff engineering and individually planned civic assets.

All airships, wind equipment and hoists are static geometry. No runtime flying,
climate, transport or structural simulation is implied by these author assets.
"""
from functools import partial
from .model import Model
from .components import shell, window, column, hip_roof, bench, shelf, pendant, crate_stack, arch_front
from .samples import railing


def base(key, name, size, *, f=7, role='key', description='高原岩台边缘，北侧接稳定地面，南侧朝开阔谷地'):
    m=Model(key,name,size,family=key.rsplit('-v',1)[0],civilization='云海航行文明',role=role,terrain={
        '选址':description,'高程':f'主平台地板 Y={f}，入口脚底 Y={f+1}；外接同高岩台道路',
        '固定与承重':'北侧石砌锚座压在实体岩台，南侧木梁和斜撑回接石墩；石墩底必须落在连续承载岩层。不可当作无支撑浮空结构。',
        '接驳边界':'模板内支撑与边栏完整；外侧山体、接驳道路、峡谷宽度及航行净空必须按实际场地核对。',
        '运行边界':'泊位、缆索、风向仪、船体与吊装均为静态空间表达；未实现飞行、气象、物流或受力模拟。'})
    m.meta.update(source='tools/structure_studio/studio/cloud_navigation.py', roof_min_y=f+7,
                  floors=[dict(name='岩台主层',y=f,max_y=f+6)],
                  preview_context=dict(kind='slope',land_surface_y=f+1,bed_y=-3,padding=4,run=8,rise=-1,slope_origin_z=0,surface='snow'))
    return m


def platform(m,x0,z0,x1,z1,f=7,*,front_rock=True):
    """A real north abutment, two rear piers and visible diagonal timber braces."""
    m.box((x0,f+1,z0),(x1,min(f+24,m.size[1]-1),z1),'air')
    m.box((x0,f,z0),(x1,f,z1),'birch_planks')
    for x in range(x0,x1+1):
        for z in (z0,z1):m.set(x,f,z,'dark_oak_log[axis=x]')
    for x in (x0,x1):m.box((x,f,z0),(x,f,z1),'dark_oak_log[axis=z]')
    depth=min(7,(z1-z0)//2)
    if front_rock:
        for y in range(f):
            xa=x0+max(0,(f-y-1)//3);xb=x1-max(0,(f-y-1)//3)
            m.box((xa,y,z0),(xb,y,z0+depth),'stone' if y%4 else 'andesite')
        m.box((x0,f-1,z0),(x1,f-1,z0+depth),'stone_bricks')
    for x in (x0+1,x1-1):
        for z in (z0+depth,z1-1):
            m.box((x-1,0,z-1),(x+1,1,z+1),'stone')
            m.box((x,1,z),(x,f-2,z),'stone_bricks')
        m.box((x,f-1,z0+depth),(x,f-1,z1),'dark_oak_log[axis=z]')
        for i in range(min(5,f-1)):
            m.set(x,f-2-i,z1-2-i,'stripped_dark_oak_log[axis=z]')
            m.set(x,f-2-i,z0+depth+1+i,'stripped_dark_oak_log[axis=z]')
    for z in range(z0+depth,z1,5):m.box((x0,f-1,z),(x1,f-1,z),'dark_oak_log[axis=x]')
    for x in (x0,x1):railing(m,(x,f+1,z0+1),(x,f+1,z1),'dark_oak','z')
    railing(m,(x0,f+1,z1),(x1,f+1,z1),'dark_oak','x')


def lodge(m,x0,z0,x1,z1,*,f=7,h=6,roof='waxed_weathered_cut_copper',gable=False):
    m.meta['roof_min_y']=min(m.meta.get('roof_min_y') or f+h+1,f+h+1)
    if len(m.meta['floors'])==1 and m.meta['floors'][0]['y']==f:
        m.meta['floors'][0]['max_y']=min(m.meta['floors'][0]['max_y'],f+h)
    shell(m,(x0,f,z0),(x1,f+h,z1),'white_terracotta',floor='birch_planks',ceiling='birch_planks')
    for z in (z0,z1):
        m.box((x0,f+1,z),(x1,f+1,z),'cyan_terracotta')
        m.box((x0,f+h,z),(x1,f+h,z),'dark_oak_log[axis=x]')
    for x in (x0,x1):m.box((x,f+h,z0),(x,f+h,z1),'dark_oak_log[axis=z]')
    for x in (x0,x1):
        for z in (z0,z1):column(m,x,z,f+1,f+h,'stripped_dark_oak_log','dark_oak_planks')
    # Continuous attic curb connects the ceiling to the inward-rising roof.
    for z in (z0,z1):m.box((x0,f+h+2,z),(x1,f+h+2,z),'dark_oak_log[axis=x]')
    for x in (x0,x1):m.box((x,f+h+2,z0),(x,f+h+2,z1),'dark_oak_log[axis=z]')
    if gable:
        for x in range(x0-1,x1+2):
            yy=f+h+2+min(x-x0+1,x1+1-x)
            for z in range(z0-1,z1+2):m.set(x,yy,z,f'{roof}_stairs[facing={"east" if x<=(x0+x1)//2 else "west"}]')
        for z in (z0,z1):
            for x in range(x0+1,x1):m.box((x,f+h+2,z),(x,f+h+2+min(x-x0,x1-x),z),'cyan_terracotta')
    else:hip_roof(m,x0-1,x1+1,z0-1,z1+1,f+h+2,material=roof,tiers=min(5,(x1-x0)//2+1,(z1-z0)//2+1))
    for z in (z0,z1):
        for x in range(x0+2,x1-2,5):window(m,(x,f+3,z),(min(x+2,x1-2),f+4,z),color='light_blue_stained_glass')
    for x in (x0,x1):
        if z1-z0>7:window(m,(x,f+3,z0+3),(x,f+4,z0+5),axis='z',color='light_blue_stained_glass')
    pendant(m,(x0+x1)//2,f+h-1,(z0+z1)//2,f+h+1)


def enter(m,x,z,*,f=7,key='entry',name='岩台地面入口',face='north'):
    m.point(key,'entrance',(x,f+1,z),name,facing=face)
    m.meta['connections'].append(dict(kind='pedestrian',pos=[x,f,z],direction=face,clearance=[3,4],note=f'{name}，外接脚底 Y={f+1} 的连续道路'))


def desk(m,key,x,y,z,name,width=4,block='cartography_table'):
    m.box((x,y,z),(x+width-1,y,z),'birch_planks');m.set(x+1,y,z,block)
    m.set(x+width-1,y+1,z,'lantern[hanging=false]')
    m.point(key,'work',(x+1,y,z),name,approach=(x+1,y,z+1))


def beds(m,key,x,y,z,count=2):
    for i in range(count):
        xx=x+i*3;m.bed(xx,y,z,'light_blue','north')
        m.point(f'{key}_{i+1}','sleep',(xx,y,z),'值守/船员床位',approach=(xx+1,y,z))


def kitchen(m,key,x,y,z):
    m.set(x,y,z,'smoker[facing=south]');m.set(x+2,y,z,'water_cauldron[level=3]');m.set(x+4,y,z,'barrel[facing=south]')
    m.box((x,y+1,z),(x,min(y+13,m.size[1]-1),z),'stone_bricks')
    m.point(key,'work',(x,y,z),'备餐炉灶与饮水',approach=(x,y,z+1))


def table(m,x,y,z,w=5):
    m.box((x,y,z),(x+w-1,y,z),'birch_slab[type=top]')
    m.box((x,y+1,z),(x+w-1,y+1,z),'cyan_carpet')
    bench(m,x,y,z+2,w,'north','birch')


def stairs_z(m,x,z,y,width,rise,*,south=True):
    for i in range(rise):
        zz=z+i if south else z-i
        m.box((x,y-1,zz),(x+width-1,y+i,zz),'dark_oak_planks')
        for xx in range(x,x+width):m.set(xx,y+i,zz,f'birch_stairs[facing={"south" if south else "north"}]')


def mast(m,x,y,z,height=9):
    m.box((x,y,z),(x,y+height,z),'stripped_dark_oak_log[axis=y]')
    m.box((x-3,y+height-1,z),(x+3,y+height-1,z),'dark_oak_fence')
    m.box((x+1,y+height-3,z),(x+4,y+height-2,z),'white_wool');m.set(x+4,y+height-3,z,'light_blue_wool')


def mooring():
    m=base('CN-01-v01','峡谷冠台 · 飞空艇泊塔',(49,42,51),description='峡谷北岸的稳定岩台；南侧保留开阔空中进港面，地面补给从北侧道路进入')
    platform(m,3,2,45,46)
    lodge(m,6,7,21,24);m.door(14,8,7,'birch','north');m.door(21,8,19,'birch','east')
    desk(m,'ticket',8,8,12,'候船登记与票务',7);bench(m,8,8,16,8,'north','birch')
    shelf(m,7,8,23,9,'birch');m.set(19,8,22,'water_cauldron[level=3]')
    m.room('waiting','岩台候船厅',(7,8,8),(20,13,23),'登记、长椅、饮水、手提行李与两侧出入口')
    lodge(m,27,7,41,23);m.door(33,8,7,'birch','north');m.door(27,8,19,'birch','west')
    desk(m,'duty',29,8,12,'航班站务与缆索工单',7);beds(m,'rest',30,8,21,2);shelf(m,28,8,16,6,'birch','bookshelf')
    m.room('station','站务与轮值间',(28,8,8),(40,13,22),'航班记录、缆索工单、双床与档案')
    # Open undercroft carries the upper docking deck. Stairs remain independent of offices.
    for x in (9,21,33,42):
        for z in (30,43):
            m.box((x,0,z),(x,8,z),'stone_bricks');column(m,x,z,8,19,'stripped_dark_oak_log','dark_oak_planks')
    m.box((8,19,29),(43,19,44),'birch_planks')
    for z in (30,36,43):m.box((8,18,z),(43,18,z),'dark_oak_log[axis=x]')
    for x in (9,21,33,42):m.box((x,18,29),(x,18,44),'dark_oak_log[axis=z]')
    for x in (8,43):railing(m,(x,20,29),(x,20,44),'dark_oak','z')
    for a,b in ((8,22),(27,43)):railing(m,(a,20,29),(b,20,29),'dark_oak','x')
    for a,b in ((8,20),(28,43)):railing(m,(a,20,44),(b,20,44),'dark_oak','x')
    stairs_z(m,23,17,8,4,12);m.box((23,19,29),(26,19,32),'birch_planks')
    for x in (22,27):
        for i in range(12):
            m.box((x,7,17+i),(x,8+i,17+i),'dark_oak_planks')
            m.box((x,9+i,17+i),(x,10+i,17+i),'dark_oak_fence[north=true,south=true,east=false,west=false]')
    m.box((22,20,29),(27,23,29),'air')
    for x in (12,38):
        mast(m,x,20,39,13);m.set(x+1,20,37,'stonecutter[facing=south]')
        m.point(f'winch_{x}','work',(x+1,20,37),'泊位系留绞盘',approach=(x+1,20,36))
    m.box((21,19,44),(27,19,49),'birch_planks')
    for x in (21,27):railing(m,(x,20,45),(x,20,49),'dark_oak','z')
    m.point('boarding','circulation',(24,20,48),'南侧空中登船廊',look_at=[24,20,50])
    m.room('deck','高架泊位与登船台',(9,20,30),(42,28,44),'双侧系留、中央登船、护栏与完整步梯')
    crate_stack(m,10,8,34,5,4,3);desk(m,'cargo',29,8,32,'地面货物交接',8,block='crafting_table')
    m.point('freight','storage',(12,8,34),'装卸周转货物',approach=(12,8,33));m.room('cargo','泊台下装卸层',(9,8,29),(41,17,43),'石墩木柱间的理货与缆索维护空间')
    enter(m,24,3);m.meta['connections'].append(dict(kind='airship',pos=[24,19,50],direction='south',clearance=[17,18],note='平台脚底 Y=20，舰侧必须另对齐；外部至少17格宽空中净空。无实际飞行或登船机制。'))
    m.meta.update(roof_min_y=14,floors=[dict(name='岩台站务与理货',y=7,max_y=13),dict(name='高架登船台',y=19,max_y=26)],design_notes=['候船和站务分置两翼，直上步梯连接12格高差；高架泊台的下层作为地面理货廊。','系留桅杆是静态设施，承台由四排柱梁传力到石墩；登船端仅短挑出六格。'])
    return m


def shipworks():
    m=base('CN-02-v01','双脊风帆库 · 船体修造库',(57,38,53))
    platform(m,3,2,53,49)
    lodge(m,5,7,17,23);m.door(17,8,17,'birch','east');desk(m,'plans',7,8,12,'船架放样与维修工单',7);shelf(m,6,8,22,8,'birch','bookshelf')
    lodge(m,5,29,17,44);m.door(17,8,35,'birch','east');kitchen(m,'cook',7,8,30);beds(m,'rest',7,8,42,3)
    m.room('plans','放样与工具翼',(6,8,8),(16,13,22),'尺度图、工单、工具和档案');m.room('rest','工匠歇班房',(6,8,30),(16,13,43),'炉灶、饮水、三床与休息')
    for x in (21,49):
        for z in (9,18,27,36,45):column(m,x,z,8,21,'stripped_dark_oak_log','dark_oak_planks')
    for x in range(20,51):
        yy=22+min(x-20,50-x)//2
        for z in range(7,48):m.set(x,yy,z,'waxed_weathered_cut_copper_stairs[facing='+('east' if x<35 else 'west')+']')
    for z in (9,18,27,36,45):
        m.box((21,20,z),(49,20,z),'dark_oak_log[axis=x]')
        for x in range(20,51):m.set(x,22+min(x-20,50-x)//2,z,'dark_oak_log[axis=z]')
    for x in (21,49):m.box((x,20,9),(x,20,45),'dark_oak_log[axis=z]')
    # A keel and open rib hull, with walkways outside rather than false accessible hull markers.
    m.box((35,8,12),(35,8,43),'dark_oak_log[axis=z]')
    for z in range(12,44):
        w=min(6,(z-10)//2,(45-z)//2)
        m.box((35-w,9,z),(35+w,9,z),'spruce_planks')
        for x in (35-w,35+w):m.box((x,10,z),(x,11,z),'stripped_spruce_log[axis=z]')
        if z%5==2:
            m.box((35-w,10,z),(35+w,10,z),'stripped_spruce_log[axis=x]')
            for x in (35-w,35+w):m.box((x,12,z),(x,14,z),'stripped_spruce_log[axis=y]')
    for z in (17,27,37):m.box((29,8,z),(41,8,z),'dark_oak_log[axis=x]')
    m.box((35,15,27),(35,19,27),'chain[axis=y]');m.set(35,14,27,'grindstone[face=ceiling,facing=north]')
    desk(m,'repair',23,8,30,'侧廊船材修整',4,block='crafting_table');m.set(24,8,35,'anvil[facing=north]')
    m.point('rib','work',(29,11,27),'船体肋架检修面',approach=(27,8,27))
    m.set(45,8,27,'stonecutter[facing=north]');m.point('hoist','work',(45,8,27),'吊具侧廊绞盘',approach=(45,8,26))
    crate_stack(m,44,8,10,4,5,3);m.point('spares','storage',(45,8,10),'船材与备件',approach=(45,8,9))
    m.room('hull','长跨船体修造厅',(22,8,8),(48,20,46),'龙骨、船肋、吊具、双侧检修道与周转备件')
    enter(m,25,3);m.meta['design_notes']=['开放端的长跨双脊屋盖与西侧两间低檐工作生活房形成体量层次。','船体为开放龙骨修造阶段；高原地面工厂，不假定整栋悬空或船坯可驾驶。']
    m.meta['roof_min_y']=14
    return m


def navigation_tower():
    m=base('CN-03-v01','折阶观风塔 · 风向导航塔',(39,41,43))
    platform(m,3,2,35,39)
    lodge(m,6,7,20,24);m.door(13,8,7,'birch','north');m.door(20,8,18,'birch','east')
    desk(m,'log',8,8,12,'风向与航路记录',8);shelf(m,7,8,23,8,'birch','bookshelf');beds(m,'watch',9,8,21,2)
    m.room('watch','导航值守房',(7,8,8),(19,13,23),'观测日志、图书、双床和器材')
    # Two exposed stair flights on a solid stone tower connect two observation levels.
    m.box((7,0,28),(20,20,37),'stone_bricks');m.box((8,1,29),(19,18,36),'stone')
    stairs_z(m,24,9,8,4,12);m.box((24,19,21),(27,19,28),'birch_planks')
    for x in (23,28):
        for i in range(12):
            m.box((x,7,9+i),(x,8+i,9+i),'dark_oak_planks')
            m.box((x,9+i,9+i),(x,10+i,9+i),'dark_oak_fence[north=true,south=true,east=false,west=false]')
    railing(m,(28,20,21),(28,20,27),'dark_oak','z')
    m.box((28,19,21),(28,19,27),'dark_oak_planks')
    m.box((7,19,27),(27,19,38),'birch_planks');m.box((7,20,28),(20,20,37),'birch_planks')
    # top stone course lowered so both landings have matching walking height
    m.box((7,20,28),(20,20,37),'air');m.box((7,19,28),(20,19,37),'birch_planks')
    for x in (7,27):railing(m,(x,20,27),(x,20,38),'dark_oak','z')
    railing(m,(7,20,38),(27,20,38),'dark_oak','x')
    railing(m,(7,20,27),(23,20,27),'dark_oak','x')
    for x in (8,19):
        for z in (29,33):column(m,x,z,20,31 if z==29 else 30,'stripped_dark_oak_log','dark_oak_planks')
    hip_roof(m,6,21,26,34,29,material='waxed_weathered_cut_copper',tiers=4)
    desk(m,'readings',10,20,30,'高台风向读数',6);m.set(23,20,34,'lightning_rod[facing=up]');mast(m,24,20,35,14)
    m.point('horizon','circulation',(16,20,36),'南向航路观测面',look_at=[16,22,42])
    m.room('observation','带顶观测台',(8,20,28),(26,27,37),'记录台、开阔南向观测面和风向桅杆')
    enter(m,24,3);m.meta.update(roof_min_y=14,floors=[dict(name='低层值守',y=7,max_y=13),dict(name='高台观测',y=19,max_y=27)],design_notes=['实体石塔与外置十二级步梯构成明确的观測高差；低层值守房沿北侧岩台布置。','风向旗与避雷外形是静态参考；只能在实际视线开阔、天气观测有需求的地方选用。'])
    return m


def weather_school():
    m=base('CN-09-v01','三庭观云馆 · 气象学馆',(53,36,51))
    platform(m,3,2,49,47)
    lodge(m,6,7,21,25);lodge(m,30,7,45,25);lodge(m,13,31,39,43,h=7)
    for x,z,face in ((14,7,'north'),(37,7,'north'),(21,19,'east'),(30,19,'west'),(26,31,'north')):m.door(x,8,z,'birch',face)
    desk(m,'lecture',8,8,11,'教学讲台',8,block='lectern[facing=south]')
    for z in (15,20):table(m,9,8,z,7)
    shelf(m,7,8,24,9,'birch','bookshelf');m.room('teaching','西翼气象教室',(7,8,8),(20,13,24),'讲台、双排学习桌与书架')
    desk(m,'lab',32,8,12,'观测样本整理',8,block='brewing_stand');m.set(33,8,19,'water_cauldron[level=3]');m.set(40,8,19,'composter[level=0]')
    shelf(m,31,8,24,10,'birch','bookshelf');m.room('lab','东翼样本实验间',(31,8,8),(44,13,24),'水样、样本器材、记录与分类柜')
    desk(m,'archive',16,8,35,'气候档案查阅',9);shelf(m,14,8,42,12,'birch','bookshelf');beds(m,'duty',32,8,41,2)
    m.room('archive','南翼档案与轮值',(14,8,32),(38,14,42),'长时观测档案、查阅桌和轮值床位')
    for x,z in ((24,15),(27,23)):
        m.set(x,8,z,'smooth_stone');m.set(x,9,z,'lightning_rod[facing=up]')
    m.set(25,8,27,'water_cauldron[level=3]');m.point('gauge','work',(25,8,27),'雨量计与观测庭',approach=(25,8,26))
    mast(m,26,8,24,15);m.room('court','仪器观测庭',(23,8,12),(28,19,29),'无遮蔽的雨量与温度仪器示意，实测环境另核对')
    enter(m,25,3);m.meta['design_notes']=['教学与实验分列两翼，后部档案形成三面围庭；中庭仪器与高旗杆成为公共辨识点。','庭院不是已实现气象采样系统；选址应避开实际风雨遮蔽，书馆样本和观测机制另接。']
    return m


def council():
    m=base('CN-10-v01','双翼航图厅 · 航路议事所',(53,37,49))
    platform(m,3,2,49,45)
    lodge(m,14,7,38,27,h=9,gable=True);arch_front(m,23,8,7,7,8)
    lodge(m,5,15,12,36);m.door(12,8,29,'birch','east');lodge(m,41,15,47,36);m.door(41,8,29,'birch','west')
    m.door(20,8,27,'birch','south');m.door(33,8,27,'birch','south')
    m.box((19,8,17),(33,8,20),'birch_planks');m.box((20,9,18),(32,9,19),'cyan_carpet')
    for z,face in ((15,'south'),(22,'north')):bench(m,19,8,z,15,face,'birch')
    desk(m,'chair',22,8,11,'航路议事主席台',8,block='lectern[facing=south]')
    m.point('council','work',(25,8,18),'公共航图议事桌',approach=(25,8,16));m.room('hall','高脊议事大厅',(15,8,8),(37,16,26),'航图长桌、代表长椅、主席台和后庭双门')
    shelf(m,6,8,35,5,'birch','bookshelf');desk(m,'records',6,8,20,'航路管理档案',4)
    m.room('records','西侧航路档案翼',(6,8,16),(11,13,35),'分类档案和管理记录')
    bench(m,43,8,24,2,'north','birch');desk(m,'guest',42,8,19,'会客与预约',3)
    m.room('guest','东侧会客翼',(42,8,16),(46,13,35),'来访登记、座席与小型会客空间')
    for x in (19,33):
        column(m,x,34,8,17,'stripped_dark_oak_log','dark_oak_planks');column(m,x,41,8,17,'stripped_dark_oak_log','dark_oak_planks')
    hip_roof(m,18,34,33,42,16,material='waxed_cut_copper',tiers=4)
    table(m,22,8,36,8);m.point('forum','circulation',(26,8,40),'后庭公共讨论廊',look_at=[26,11,36])
    m.room('forum','后庭讨论廊',(19,8,34),(33,14,41),'有顶室外讨论、观景和会后交流')
    enter(m,26,3);m.meta['design_notes']=['高脊中厅、狭长档案与会客双翼、后庭凉廊构成公共院落。','桌面航图为静态陈设；本体不承诺航线管理或议事机制。']
    return m


def abandoned():
    m=base('CN-11-v01','断缆旧站 · 废弃泊塔',(41,36,43),role='structure',description='已停用航路上的旧岩台泊位，主体地基仍稳定；南侧破损设施封边，保留北侧探索通路')
    platform(m,3,2,37,39)
    lodge(m,6,8,21,25,roof='dark_prismarine');m.door(13,8,8,'spruce','north');m.door(21,8,20,'spruce','east')
    desk(m,'oldlog',8,8,13,'旧航路日志与临时勘察记录',8);shelf(m,7,8,24,6,'spruce','bookshelf');beds(m,'camp',15,8,23,1)
    m.set(7,8,18,'cobweb');m.room('shelter','旧站务避风房',(7,8,9),(20,13,24),'可读旧档案、临时勘察桌和单床；保留完整的避雨屋面')
    for x in (27,33):column(m,x,29,8,22,'stripped_dark_oak_log','dark_oak_planks')
    m.box((27,22,29),(33,22,29),'dark_oak_log[axis=x]');m.box((29,18,29),(29,21,29),'chain[axis=y]')
    m.box((24,7,31),(35,7,38),'dark_oak_planks')
    for x,z in ((27,35),(28,35),(31,36),(31,37),(32,37)):m.set(x,7,z,'air')
    # Continuous barrier closes damaged south deck, leaving a documented safe viewing side.
    railing(m,(24,8,32),(36,8,32),'spruce','x')
    m.point('inspect','circulation',(29,8,30),'旧泊位封闭线',look_at=[30,8,37])
    m.set(30,8,27,'barrel[facing=up]');m.point('salvage','storage',(30,8,27),'回收缆索箱',approach=(30,8,26))
    for x,z in ((5,6),(22,28),(35,13)):m.set(x,8,z,'moss_carpet')
    m.room('ruin','封闭的断缆泊位',(24,8,27),(36,22,38),'断链、破板与持续封边；标记只位于安全侧')
    enter(m,25,3);m.meta['design_notes']=['废弃状态来自断链、拆除的登船端、后部破板和封边，不牺牲仍可探索的旧站务室。','只在航路改变的历史节点明确选用；破损区不可作为运行中的泊位。']
    return m


def wreck_camp():
    m=base('CN-12-v01','岩坳折翼营 · 坠落船骸回收营',(55,34,49),role='structure',f=4,description='高原峡谷侧的低缓岩坳，先有可进入的坠落船骸与回收需求，再布置临时营地；北侧步道接补给')
    platform(m,3,2,51,45,4)
    lodge(m,6,7,21,23,f=4,h=5,roof='waxed_cut_copper');m.door(14,5,7,'spruce','north');m.door(21,5,17,'spruce','east')
    beds(m,'camp',8,5,21,4);kitchen(m,'cook',8,5,9);table(m,9,5,13,8)
    m.room('camp','回收队生活营房',(7,5,8),(20,9,22),'四床、共餐、炉灶、饮水及物资')
    for x in (7,21):
        for z in (29,40):column(m,x,z,5,14,'stripped_dark_oak_log','dark_oak_planks')
    hip_roof(m,6,22,28,41,13,material='waxed_cut_copper',tiers=4)
    desk(m,'sort',8,5,31,'回收件拆解分拣',10,block='crafting_table');m.set(9,5,36,'anvil[facing=east]');crate_stack(m,16,5,36,4,4,3)
    m.point('parts','storage',(17,5,36),'可再用零件',approach=(17,5,35));m.room('sorting','有顶回收工棚',(7,5,29),(21,11,40),'拆解、修理和分类周转')
    # Wreck is off-axis in plan: the broken stern steps east from its nose.
    for z in range(10,41):
        cx=34+(z-10)//9;w=min(6,(z-8)//2,(43-z)//2)
        m.box((cx-w,5,z),(cx+w,5,z),'spruce_planks')
        m.set(cx,4,z,'dark_oak_log[axis=z]')
        for x in (cx-w,cx+w):
            if (x+z)%7:m.box((x,6,z),(x,7+(z%3==0),z),'stripped_spruce_log[axis=z]')
        if z in (14,20,26,33,39):
            m.box((cx-w,6,z),(cx+w,6,z),'stripped_spruce_log[axis=x]')
            for x in (cx-w,cx+w):m.box((x,7,z),(x,10+(z%2),z),'stripped_spruce_log[axis=y]')
    m.box((35,8,21),(35,16,21),'stripped_dark_oak_log[axis=y]');m.box((36,7,22),(44,7,22),'dark_oak_log[axis=x]')
    m.box((37,8,23),(42,8,26),'white_wool');m.box((41,8,25),(43,8,29),'light_blue_wool')
    m.point('wreck','work',(29,7,20),'船骸西侧拆解面',approach=(27,5,20));m.room('wreck','坠落船骸现场',(27,5,9),(47,17,41),'偏折龙骨、断肋、折桅与落帆；拆解通路沿外侧')
    enter(m,25,3,f=4);m.meta['design_notes']=['住宿、分拣棚与偏折船骸形成三块独立作业空间；外缘木台由实体岩坳承接。','船骸是预制静态情景，不能宣称模拟过坠落；实地地形和历史来源必须匹配。']
    return m


BUILDERS={f'CN-{n:02d}-v01':fn for n,fn in ((1,mooring),(2,shipworks),(3,navigation_tower),(9,weather_school),(10,council),(11,abandoned),(12,wreck_camp))}
