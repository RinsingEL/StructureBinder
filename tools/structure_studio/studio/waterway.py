"""Individually planned waterway civic, working and historical structures."""
from .model import Model
from .components import shell,window,column,arch_front,hip_roof,bench,shelf,pendant,crate_stack
from .samples import railing


def base(number,name,size,description,*,shore=None,f=3,role='key'):
    m=Model(f'WT-{number:02d}-v01',name,size,family=f'WT-{number:02d}',civilization='地中海',role=role,terrain={
        '选址':description,'高程':f'主体地坪方块 Y={f}，脚底 Y={f+1}；局部高差另在房间与接驳标记记录',
        '落地':'只整备本体院落与岸台；水侧桩脚和实体岸基分别承接，不填满外部水道',
        '运行边界':'作者资产表达空间与设备外形，交通、船只、液体和机器行为需要另行接入'})
    m.meta.update(source=f'tools/structure_studio/studio/waterway.py:waterway({number})',roof_min_y=f+8,
        floors=[dict(name='地面使用空间',y=f,max_y=f+5)],design_notes=[],differences=[],
        preview_context=dict(kind='flat',land_surface_y=f+1,bed_y=-1,padding=4))
    if shore is not None:
        m.meta['preview_context'].update(kind='shore',shore_z=shore,water_surface_y=3)
        m.meta['terrain']['水岸']=f'岸线沿 X，参考水面 Y=3，Z≥{shore} 为水侧；需核对实际水深、岸床与航行宽度'
    return m


def pad(m,x0,z0,x1,z1,f=3):
    m.box((x0,0,z0),(x1,f,z1),'stone_bricks')
    m.box((x0,f+1,z0),(x1,m.size[1]-1,z1),'air')
    m.box((x0,f,z0),(x1,f,z1),'smooth_sandstone')
    for x in range(x0,x1+1):
        for z in (z0,z1):m.set(x,f,z,'cut_sandstone')


def eave_beam(m,x0,z0,x1,z1,y,wood='birch'):
    """Support the first inset roof course continuously along the wall/post line."""
    for z in (z0,z1):m.box((x0,y,z),(x1,y,z),f'{wood}_planks')
    for x in (x0,x1):m.box((x,y,z0),(x,y,z1),f'{wood}_planks')


def pavilion(m,x0,z0,x1,z1,*,f=3,wall_height=7,roof='brick'):
    top=f+wall_height
    shell(m,(x0,f,z0),(x1,top,z1),'white_terracotta',floor='birch_planks',ceiling='birch_planks')
    for z in (z0,z1):
        m.box((x0,f+1,z),(x1,f+1,z),'cyan_terracotta')
        m.box((x0,top,z),(x1,top,z),'cyan_terracotta')
    for x in (x0,x1):
        for z in (z0,z1):column(m,x,z,f+1,top,'smooth_sandstone','chiseled_sandstone')
    eave_beam(m,x0,z0,x1,z1,top+2)
    hip_roof(m,x0-1,x1+1,z0-1,z1+1,top+2,material=roof,tiers=min(6,(x1-x0)//2+2,(z1-z0)//2+2))


def desk(m,key,x,y,z,width=5,name='记录桌',facing='south'):
    m.box((x,y,z),(x+width-1,y,z),'birch_planks')
    m.set(x+1,y,z,f'lectern[facing={facing}]');m.set(x+width-1,y+1,z,'lantern[hanging=false]')
    m.point(key,'work',(x+1,y,z),name,approach=(x+1,y,z+(1 if facing=='south' else -1)))


def table(m,x,y,z,width=5,color='cyan'):
    for xx in range(x,x+width):
        m.set(xx,y,z,'birch_slab[type=top]');m.set(xx,y+1,z,color+'_carpet')


def entry(m,x,z,*,y=4,key='land',name='陆侧入口',face='north'):
    m.point(key,'entrance',(x,y,z),name,facing=face)
    m.meta['connections'].append(dict(kind='pedestrian',pos=[x,y-1,z],direction=face,clearance=[3,4],note=f'{name}，脚底 Y={y}；外部交通另接'))


def pier(m,x0,z0,x1,z1,*,f=3):
    m.box((x0,f,z0),(x1,f,z1),'spruce_planks')
    m.box((x0,f+1,z0),(x1,min(f+14,m.size[1]-1),z1),'air')
    for x in (x0,x1):
        for z in range(z0,z1+1,5):m.box((x,0,z),(x,f,z),'stripped_dark_oak_log[axis=y]')
    for z in range(z0,z1+1,5):m.box((x0,f-1,z),(x1,f-1,z),'dark_oak_log[axis=x]')


def workbench(m,key,x,y,z,name='修理工作台'):
    m.set(x,y,z,'crafting_table');m.set(x+2,y,z,'anvil[facing=east]')
    m.point(key,'work',(x,y,z),name,approach=(x,y,z-1))


def shipyard():
    m=base(3,'长肋船坊 · 修造坞',(61,32,57),'有宽阔陆侧修造坪与连续入水坡道的避风岸段；整座船台须落在岸上，水侧保留拖船进出净空',shore=44)
    m.meta['design_notes']=['长肋屋架覆盖岸上造船台，西侧木料和工具房、东侧船工生活与放样室分别开门；中轴坡道接水，两侧短栈道供检视。',
        '船台上的木肋为在建船体外形，吊架和坡道只作空间表达；不是可驾驶船只或自动下水系统。']
    pad(m,3,3,57,43)
    pavilion(m,4,6,18,22);pavilion(m,4,27,18,39,roof='dark_prismarine')
    pavilion(m,42,6,56,23);pavilion(m,42,28,56,39,roof='dark_prismarine')
    for x,y,z,face in [(18,4,14,'east'),(18,4,33,'east'),(42,4,15,'west'),(42,4,33,'west')]:m.door(x,y,z,'birch',face)
    for x0,z in ((6,6),(44,6),(6,39),(44,39)):window(m,(x0,6,z),(x0+4,8,z),color='light_blue_stained_glass')
    for z in (9,18):m.box((6,4,z),(13,5,z+1),'stripped_spruce_log[axis=x]')
    crate_stack(m,15,4,17,2,3,2);m.point('timber','storage',(9,4,9),'分拣船材',approach=(9,4,11))
    workbench(m,'tools',7,4,31);shelf(m,6,4,38,8,'spruce');m.set(15,4,37,'water_cauldron[level=3]')
    m.room('timber','西侧木料房',(5,4,7),(17,9,21),'长木材、周转货物与取料通路')
    m.room('tools','西侧工具间',(5,4,28),(17,9,38),'修理、砧台、工具架与水盆')
    m.box((43,4,17),(55,9,17),'white_terracotta');m.door(49,4,17,'birch','south')
    table(m,45,4,11,7);bench(m,45,4,13,7,'north','birch')
    m.set(43,4,8,'smoker[facing=east]');m.box((43,5,8),(43,16,8),'stone_bricks');m.set(45,4,8,'water_cauldron[level=3]')
    for x in (45,53):m.bed(x,4,21,'light_blue','north');m.point(f'bed_{x}','sleep',(x,4,21),'船工歇班床',approach=(x+1,4,21))
    shelf(m,47,4,22,4,'birch');m.point('meal','work',(48,4,11),'船工餐桌',approach=(48,4,10))
    m.point('cook','work',(43,4,8),'船工炉灶',approach=(44,4,8))
    m.room('mess','船工食堂',(43,4,7),(55,9,16),'餐桌、烧饭与饮水')
    m.room('rest','船工歇班间',(43,4,18),(55,9,22),'双床、衣物和行李')
    desk(m,'plans',45,4,32,8,'船型放样与工单');m.set(46,4,32,'cartography_table')
    shelf(m,44,4,38,8,'birch','bookshelf');m.room('plans','放样记录室',(43,4,29),(55,9,38),'船型图、订单与尺度记录')
    # A wide gabled shed and independent lifting gantry over the construction hull.
    for z in (13,20,27,34,41):
        for x in (21,39):
            column(m,x,z,4,17,'stripped_dark_oak_log','dark_oak_planks')
            m.box((x,14,z),(x,17,z),'dark_oak_fence')
            m.set(x,18,z,'dark_oak_planks')
        for x in range(20,41):m.set(x,18+min(x-20,40-x),z,'dark_oak_log[axis=z]')
    for x in range(20,41):
        y=18+min(x-20,40-x)
        for z in range(12,43):
            if z not in (13,20,27,34,41):m.set(x,y,z,'dark_prismarine_stairs[facing='+('east' if x<=30 else 'west')+']')
    for x in (21,39):m.box((x,16,13),(x,16,41),'dark_oak_log[axis=z]')
    m.box((21,16,27),(39,16,27),'dark_oak_log[axis=x]');m.box((30,10,27),(30,15,27),'chain[axis=y]')
    m.set(30,9,27,'grindstone[face=ceiling,facing=north]')
    m.box((30,4,15),(30,4,39),'stripped_spruce_log[axis=z]')
    for z in range(16,39):
        span=min(5,(z-14)//2,(40-z)//2)
        m.box((30-span,5,z),(30+span,5,z),'spruce_planks')
        for x in (30-span,30+span):m.set(x,6,z,'stripped_spruce_log[axis=z]')
        if z in (18,23,28,33,37):
            for x in range(30-span,31+span):m.set(x,6,z,'stripped_spruce_log[axis=x]')
            for x in (30-span,30+span):m.box((x,7,z),(x,8,z),'stripped_spruce_log[axis=y]')
    for z in (17,25,34):m.box((26,4,z),(34,4,z),'dark_oak_log[axis=x]')
    m.set(23,4,26,'stonecutter[facing=west]');m.point('hoist','work',(23,4,26),'横梁吊架绞盘',approach=(23,4,25))
    m.point('hull','work',(26,6,23),'船肋检修面',approach=(24,4,23));m.room('hall','覆盖船台',(22,4,13),(38,16,42),'在建船肋、支承垫木、双侧作业面与横移吊架')
    for z,y in ((44,3),(45,2),(46,1)):
        m.box((26,0,z),(34,y,z),'stone_bricks')
        for x in range(26,35):m.set(x,y,z,'stone_brick_stairs[facing=north]')
    for x0 in (19,37):
        pier(m,x0,44,x0+4,54)
        railing(m,(x0,4,45),(x0,4,54),'spruce','z');railing(m,(x0+4,4,45),(x0+4,4,54),'spruce','z')
        m.point(f'slip_{x0}','circulation',(x0+2,4,52),'下水坡侧检视栈道',look_at=[30,3,47])
    for x,z in ((11,14),(11,33),(49,12),(49,33)):pendant(m,x,9,z,11)
    entry(m,30,5);m.meta['connections'].append(dict(kind='boat',pos=[30,3,56],direction='south',clearance=[13,12],note='船台中轴坡道接连续水域，静态船坯不具备驾驶或下水功能'))
    return m


def lock_house():
    m=base(4,'双水尺闸院 · 船闸管理所',(61,30,59),'确有航道高差的人工运河节点；两岸有可步行检修面，上下游为同轴水路',f=5)
    m.meta['design_notes']=['西岸值班档案与设备间，东岸检修廊与歇班屋；两道闭合闸门及门顶巡检通道分开上下游水位，北侧高桥连接两岸。',
        '上游参考水面 Y=5、闸室与下游 Y=3；木闸叶、绞盘、齿轮和示意水体是静态建筑，不表示已实现放水、开闸或通船。']
    m.meta['terrain'].update(水位='上游在 -Z，参考水面 Y=5；闸室与 +Z 下游水面 Y=3；两岸脚底 Y=6，高桥脚底 Y=11',净空='河道 X=24～36，保留 13 格宽水路；高桥底 Y=10，上游水面至桥底 5 格')
    m.meta['preview_context'].update(kind='canal',canal_x0=24,canal_x1=36,upper_end_z=11,upstream_surface_y=5,downstream_surface_y=3)
    for x0,x1 in ((3,23),(37,57)):pad(m,x0,2,x1,56,5)
    m.box((24,0,0),(36,0,58),'stone_bricks');m.box((24,1,0),(36,29,58),'air')
    m.box((24,1,0),(36,4,10),'water[level=0]');m.box((24,1,12),(36,2,46),'water[level=0]');m.box((24,1,48),(36,2,58),'water[level=0]')
    for x in (23,37):
        m.box((x,0,0),(x,5,58),'stone_bricks')
        for za,zb in ((14,45),(49,55)):railing(m,(x,6,za),(x,6,zb),'dark_oak','z')
    for z in (11,47):
        m.box((24,1,z),(36,5,z),'dark_oak_planks')
        m.box((24,5,z-2),(36,5,z+2),'spruce_planks')
        for yy in (2,4):m.box((24,yy,z-1),(36,yy,z-1),'stripped_dark_oak_log[axis=x]')
        for zz in (z-2,z+2):railing(m,(24,6,zz),(36,6,zz),'spruce','x')
        for x in (21,39):
            m.set(x,6,z,'stripped_dark_oak_log[axis=y]');m.set(x,7,z,'lever[face=floor,facing=north]')
        m.point(f'gate_{z}','work',(21,6,z),'上闸绞盘' if z==11 else '下闸绞盘',approach=(20,6,z))
    # Five steps lift each bank approach to the bridge without entering an office.
    for i in range(5):
        for z in range(4,7):
            for x,face in ((18+i,'east'),(42-i,'west')):
                m.box((x,6,z),(x,6+i,z),'stone_bricks');m.set(x,6+i,z,f'stone_brick_stairs[facing={face}]')
    m.box((23,10,3),(37,10,7),'stone_bricks')
    for z in (3,7):railing(m,(23,11,z),(37,11,z),'birch','x')
    m.point('bridge','circulation',(30,11,5),'跨渠高桥',look_at=[30,5,20])
    pavilion(m,4,14,20,29,f=5,roof='brick');m.box((13,6,15),(13,12,28),'white_terracotta');m.door(13,6,22,'birch','east')
    m.door(20,6,22,'birch','east');desk(m,'duty',6,6,19,5,'船闸值班台');desk(m,'log',15,6,19,4,'过闸记录')
    shelf(m,5,6,28,7,'birch','bookshelf');shelf(m,15,6,28,4,'birch','bookshelf');bench(m,6,6,24,4,'north','birch')
    window(m,(6,8,14),(10,10,14),color='light_blue_stained_glass');window(m,(15,8,14),(18,10,14),color='light_blue_stained_glass')
    pavilion(m,4,34,18,50,f=5,roof='dark_prismarine');m.door(18,6,40,'birch','east')
    workbench(m,'repair',7,6,39);shelf(m,5,6,49,7,'spruce');crate_stack(m,14,6,45,3,3,2)
    m.set(15,6,35,'water_cauldron[level=3]');window(m,(6,8,34),(11,10,34))
    pavilion(m,42,30,55,47,f=5);m.door(42,6,38,'birch','west')
    m.box((43,6,40),(54,11,40),'white_terracotta');m.door(49,6,40,'birch','south')
    table(m,45,6,35,7);bench(m,45,6,37,7,'north','birch')
    for x in (45,52):m.bed(x,6,45,'light_blue','north');m.point(f'bed_{x}','sleep',(x,6,45),'歇班床',approach=(x+1,6,45))
    shelf(m,47,6,46,3,'birch');m.set(44,6,42,'barrel[facing=east]')
    m.set(43,6,32,'smoker[facing=east]');m.box((43,7,32),(43,18,32),'stone_bricks');m.set(45,6,32,'water_cauldron[level=3]')
    m.point('meal','work',(47,6,35),'歇班餐桌',approach=(47,6,34));window(m,(48,8,30),(52,10,30))
    for x,z in ((9,22),(16,22),(10,43),(48,34)):pendant(m,x,11,z,13)
    m.room('duty','值班观察室',(5,6,15),(12,11,28),'值班、通航次序和候坐')
    m.room('records','过闸记录室',(14,6,15),(19,11,28),'账册、档案与记录桌')
    m.room('maintenance','闸务设备间',(5,6,35),(17,11,49),'工具、备件、水盆和修理')
    m.room('mess','东岸歇班食堂',(43,6,31),(54,11,39),'餐桌、炉灶与饮水')
    m.room('rest','东岸双床间',(43,6,41),(54,11,46),'轮值人员歇班')
    m.room('channel','闸室与双岸巡检',(21,1,10),(39,10,49),'闭合木闸叶、门顶通道、上下游水位与双岸维护')
    m.point('inspect','circulation',(39,6,24),'东岸巡检廊',look_at=[30,3,25]);entry(m,10,5,y=6)
    for x,z in ((23,8),(37,50)):
        for y in range(1,6):m.set(x,y,z,'white_concrete' if y%2 else 'black_concrete')
    m.meta['floors']=[dict(name='两岸管理与闸室',y=1,max_y=9),dict(name='高桥与屋面以下',y=10,max_y=12)]
    m.meta['connections'] += [dict(kind='boat',pos=[30,y,z],direction=direction,clearance=[13,5],note='闭合闸门为静态表达；外接同轴运河与匹配水面，需另做开闸机制') for y,z,direction in ((5,0,'north'),(3,58,'south'))]
    return m


def customs():
    m=base(9,'三檐验货院 · 港口海关',(53,29,47),'水陆货运实际交汇的港口岸台，申报人员走北门，货物沿西侧进入验货庭，再转水侧吊装',shore=36)
    m.meta['design_notes']=['挑高申报厅夹在低檐值班与档案翼之间，后部验货雨棚、独立扣货房和水侧装卸台相连。',
        '扣留空间用于货物；建筑与作者标记不实现税务、贸易、查验或吊运机制。']
    pad(m,3,3,48,35)
    pavilion(m,6,6,19,22);pavilion(m,20,6,32,22,wall_height=9,roof='dark_prismarine');pavilion(m,33,6,45,22)
    for x in (19,32):m.box((x,4,14),(x+1,6,16),'air')
    arch_front(m,22,4,6,9,8);arch_front(m,23,4,22,7,8)
    for x0 in (8,14,35,40):window(m,(x0,6,6),(x0+3,8,6),color='light_blue_stained_glass')
    desk(m,'declare',23,4,13,7,'入港货物申报',facing='north')
    bench(m,21,4,10,3,'south','birch');bench(m,29,4,10,3,'south','birch')
    desk(m,'clerk',9,4,12,7,'查验工单与值班记录');shelf(m,7,4,7,6,'birch','bookshelf')
    m.box((7,4,17),(18,9,17),'white_terracotta');m.door(13,4,17,'birch','south')
    m.bed(9,4,21,'light_blue','north');m.point('sleep','sleep',(9,4,21),'夜班歇床',approach=(10,4,21))
    m.set(17,4,21,'smoker[facing=north]');m.box((17,5,21),(17,16,21),'stone_bricks');m.set(15,4,21,'water_cauldron[level=3]')
    for z in (8,20):shelf(m,35,4,z,8,'birch','bookshelf')
    desk(m,'archive',36,4,14,7,'关务档案桌')
    for x,z in ((12,12),(26,17),(39,12)):pendant(m,x,9,z,11)
    for x in (7,25):
        for z in (26,34):column(m,x,z,4,11,'stripped_birch_log','birch_planks')
    eave_beam(m,7,26,25,34,11)
    hip_roof(m,6,26,25,35,11,material='dark_prismarine',tiers=4)
    table(m,11,4,29,10,'blue');m.set(13,4,29,'crafting_table');m.set(13,5,29,'air');m.set(18,5,29,'stone_pressure_plate')
    m.point('inspect','work',(18,4,29),'验货称量台',approach=(18,4,28));crate_stack(m,8,4,32,3,2,2)
    pavilion(m,31,26,45,34,wall_height=6);m.door(31,4,30,'birch','west')
    crate_stack(m,33,4,28,4,3,3);crate_stack(m,40,4,28,3,4,2)
    m.point('hold','storage',(34,4,28),'扣留货物',approach=(34,4,27));pendant(m,38,8,30,10)
    pier(m,6,36,46,42)
    for x0,x1 in ((6,15),(24,37)):railing(m,(x0,4,42),(x1,4,42),'spruce','x')
    m.box((44,4,38),(44,13,38),'stripped_dark_oak_log[axis=y]');m.box((38,13,38),(45,13,38),'dark_oak_log[axis=x]')
    m.box((38,8,38),(38,12,38),'chain[axis=y]');m.set(38,7,38,'grindstone[face=ceiling,facing=north]')
    m.set(44,4,40,'stonecutter[facing=west]');m.point('crane','work',(44,4,40),'码头起货绞盘',approach=(43,4,40))
    m.point('quay','circulation',(20,4,40),'货船靠泊面',look_at=[20,3,46])
    for key,name,a,b,purpose in [
        ('declare','挑高申报厅',(21,4,7),(31,10,21),'申报、候坐与向后庭分流'),
        ('clerks','查验工单间',(7,4,7),(18,9,16),'工单、值班记录与书架'),
        ('rest','夜班生活角',(7,4,18),(18,9,21),'歇床、炉灶和饮水'),
        ('archive','关务档案翼',(34,4,7),(44,9,21),'分类档案、凭证与查阅'),
        ('inspect','后庭验货棚',(7,4,26),(25,9,34),'开箱、称量和周转货物'),
        ('hold','独立扣货房',(32,4,27),(44,8,33),'扣留货物与门禁入口'),
        ('quay','水侧装卸台',(6,4,36),(46,9,42),'卸货、起吊与泊位净空')]:m.room(key,name,a,b,purpose)
    entry(m,26,4);entry(m,4,29,key='cargo',name='陆侧货运口',face='west')
    m.meta['connections'].append(dict(kind='boat',pos=[20,3,43],direction='south',clearance=[9,8],note='参考水面 Y=3，泊位开口 X=16～23；外部航路另接'))
    m.meta['roof_min_y']=10
    return m


def guild():
    m=base(10,'分流议庭 · 水利会馆',(49,30,45),'服务真实水利管理需求的岸上聚落中心；有足够议事与教学用地、生活供水和步行联系',f=2)
    m.meta['design_notes']=['高檐议厅居中，西侧教学、东侧档案各自开门，后庭用小型分水模型说明维护议题，后廊提供室外讲解与歇坐。',
        '院内水体是封闭的静态展示模型；不会把会馆本体当成实际分水枢纽。']
    pad(m,2,2,46,42,2)
    pavilion(m,14,5,35,22,f=2,wall_height=8,roof='dark_prismarine')
    pavilion(m,3,9,12,29,f=2,roof='brick');pavilion(m,38,9,45,29,f=2,roof='brick')
    arch_front(m,20,3,5,10,8);arch_front(m,22,3,22,7,8)
    m.door(14,3,19,'birch','west');m.door(12,3,19,'birch','east');m.door(35,3,19,'birch','east');m.door(38,3,19,'birch','west')
    for x0 in (15,30):window(m,(x0,5,5),(x0+3,8,5),color='light_blue_stained_glass')
    for z in (11,24):
        window(m,(3,5,z),(3,7,z+2),axis='z');window(m,(45,5,z),(45,7,z+2),axis='z')
    for z in (12,13,14):table(m,20,3,z,10,'blue')
    for x in (20,26):
        bench(m,x,3,10,4,'south','birch');bench(m,x,3,17,4,'north','birch')
    desk(m,'chair',23,3,20,5,'水务议事主持台',facing='north')
    m.point('meeting','work',(24,3,12),'水务合议长桌',approach=(24,3,11))
    shelf(m,16,3,20,4,'birch','bookshelf');shelf(m,30,3,20,4,'birch','bookshelf')
    desk(m,'teach',5,3,11,5,'水工教学讲台')
    for z in (16,23):table(m,5,3,z,5);bench(m,5,3,z+2,5,'north','birch')
    shelf(m,5,3,28,6,'birch','bookshelf');m.point('study','work',(7,3,16),'学员练习桌',approach=(7,3,15))
    for z in (11,27):shelf(m,39,3,z,5,'birch','bookshelf')
    desk(m,'records',39,3,16,5,'堤渠图册整理');m.set(40,3,16,'cartography_table')
    m.box((18,2,28),(31,3,35),'polished_andesite')
    m.box((19,3,29),(30,3,34),'water[level=0]')
    m.box((24,3,29),(24,4,34),'stone_bricks');m.box((24,3,31),(24,3,32),'dark_oak_planks')
    for x in (21,27):m.set(x,4,28,'lever[face=floor,facing=north]')
    m.point('model','work',(24,4,29),'分水教学模型',approach=(24,3,27))
    for x in (14,21,28,35):
        for z in (38,40):column(m,x,z,3,9,'stripped_birch_log','birch_planks')
    eave_beam(m,14,38,35,40,9)
    hip_roof(m,13,36,37,41,9,material='dark_prismarine',tiers=3)
    bench(m,16,3,39,5,'north','birch');bench(m,28,3,39,5,'north','birch')
    m.set(37,3,33,'water_cauldron[level=3]');m.point('wash','work',(37,3,33),'教学取水盆',approach=(36,3,33))
    m.point('gallery','circulation',(24,3,39),'后庭讲解廊',look_at=[24,4,31])
    for x,z,y in ((24,8,9),(24,19,9),(8,20,8),(42,21,8)):pendant(m,x,y,z,y+3)
    m.room('hall','合议大厅',(15,3,6),(34,9,21),'主持台、合议桌、坐席与常用资料')
    m.room('school','水工教学室',(4,3,10),(11,8,28),'讲台、练习桌与书架')
    m.room('archive','堤渠档案室',(39,3,10),(44,8,28),'地图、档案与整理台')
    m.room('model','后庭水工展示',(16,3,25),(36,7,36),'封闭分水模型、旋钮表达与围观通道')
    m.room('gallery','后庭讲解廊',(14,3,38),(35,7,40),'歇坐、讲解与避雨')
    m.meta.update(roof_min_y=9,floors=[dict(name='议事教学与展示庭',y=2,max_y=7)])
    entry(m,24,3,y=3)
    return m


def old_pump():
    m=base(11,'青锈泵屋 · 旧泵房',(37,28,35),'旧取水岸段与岸上供水设施之间，保留进水方向、设备检修间和后来增建的看守生活空间',shore=25,role='structure')
    m.meta['design_notes']=['斑驳高泵厅保留双铜筒、木轮和进水管，侧边低檐看守间仍有床、炉与餐桌，水侧木栈道可检查进水口。',
        '旧水泵为原版方块组成的机械外形，腐蚀、检修与生活痕迹不代表真实抽水和管网已经接入。']
    pad(m,3,3,33,24)
    pavilion(m,5,7,23,22,wall_height=8,roof='waxed_oxidized_cut_copper')
    pavilion(m,26,8,33,22,wall_height=6,roof='dark_prismarine')
    arch_front(m,11,4,7,8,8);m.door(26,4,16,'birch','west')
    window(m,(7,6,7),(10,9,7),color='light_blue_stained_glass');window(m,(19,6,7),(21,9,7),color='light_blue_stained_glass')
    for z in (10,15):window(m,(23,6,z),(23,8,z+2),axis='z')
    for x,z in ((5,10),(5,18),(23,19),(7,22),(20,22)):
        m.box((x,4,z),(x,5,z),'mossy_stone_bricks')
    # Explicit chipped wall/roof edges retain stable piers and sealed interior floor.
    m.set(23,8,16,'air');m.set(4,13,12,'air');m.set(4,13,13,'air')
    for x in (9,19):
        m.box((x,4,18),(x+2,4,20),'stone_bricks');m.box((x,5,18),(x+2,8,20),'waxed_weathered_cut_copper')
        m.box((x,9,18),(x+2,9,20),'waxed_oxidized_cut_copper_slab[type=bottom]')
        m.set(x+1,6,17,'lever[face=wall,facing=north]')
    for x in range(12,19):
        for y in range(4,11):
            r=(x-15)**2+(y-7)**2
            if 4<=r<=10 or x==15 or y==7:m.set(x,y,16,'stripped_dark_oak_log[axis=z]')
    m.box((15,7,15),(15,7,20),'dark_oak_log[axis=z]')
    m.box((10,8,21),(20,8,21),'waxed_oxidized_copper');m.box((15,4,21),(15,8,21),'waxed_oxidized_copper')
    m.box((15,4,22),(15,4,32),'waxed_oxidized_copper');m.box((15,1,32),(15,4,32),'waxed_oxidized_copper')
    workbench(m,'repair',7,4,11);shelf(m,6,4,21,3,'spruce');crate_stack(m,20,4,10,2,2,2)
    m.point('pump','work',(10,6,17),'旧泵筒检修阀',approach=(10,4,16));m.point('wheel','work',(15,7,16),'泵轮观察',approach=(15,4,13))
    table(m,28,4,12,4);bench(m,28,4,14,4,'north','birch')
    m.bed(28,4,20,'light_blue','north');m.point('bed','sleep',(28,4,20),'守泵人床位',approach=(29,4,20))
    m.set(32,4,21,'smoker[facing=north]');m.box((32,5,21),(32,16,21),'stone_bricks');m.set(30,4,21,'water_cauldron[level=3]')
    m.set(27,4,21,'barrel[facing=north]');m.point('cook','work',(32,4,21),'看守炉灶',approach=(32,4,20))
    window(m,(28,6,8),(31,8,8));pendant(m,29,8,16,10);pendant(m,15,10,11,12)
    pier(m,8,25,23,30)
    m.box((15,4,22),(15,4,32),'waxed_oxidized_copper');m.box((15,1,32),(15,4,32),'waxed_oxidized_copper')
    for x0,x1 in ((8,13),(17,23)):railing(m,(x0,4,30),(x1,4,30),'spruce','x')
    m.point('intake','work',(15,3,32),'旧进水口检视',approach=(12,4,29))
    m.room('pump','旧泵机械厅',(6,4,8),(22,10,21),'双铜筒、木轮、轴、检修台和备件')
    m.room('keeper','增建看守间',(27,4,9),(32,8,21),'歇床、炊事、用餐与衣物')
    m.room('intake','进水检视栈道',(8,4,25),(23,8,30),'固定进水管与双侧检查通道')
    entry(m,14,5);m.meta['connections'].append(dict(kind='water',pos=[15,3,33],direction='south',clearance=[5,5],note='对接实际水源及旧水务系统；无抽水逻辑'))
    m.meta['roof_min_y']=10
    return m


def water_temple():
    m=base(12,'回潮祭庭 · 旧水神庙',(47,32,47),'位于洪水安全线以上、能解释当地水文化的岸上高台；保留朝水祭庭与看守通路',shore=40,role='structure')
    m.meta['design_notes']=['双阶抬高正殿，前部青檐门亭、后部临水观潮台贯穿中轴；西侧看守小屋与东侧残廊保留生活和旧仪式陈设。',
        '水神像为原创棱柱形仪式陈设；岸台与参考水面关系仅作为选址示意，实际洪水风险需依地理条件判断。']
    pad(m,2,3,44,39);pad(m,12,13,34,34,5)
    pavilion(m,12,15,34,34,f=5,wall_height=9,roof='brick')
    arch_front(m,19,6,15,11,9);m.door(24,6,34,'birch','south')
    window(m,(22,10,34),(26,12,34),color='light_blue_stained_glass')
    for x in range(21,28):
        m.set(x,4,12,'stone_bricks');m.set(x,4,35,'stone_bricks')
        m.set(x,4,11,'stone_brick_stairs[facing=south]');m.set(x,5,12,'stone_brick_stairs[facing=south]')
        m.set(x,5,35,'stone_brick_stairs[facing=north]');m.set(x,4,36,'stone_brick_stairs[facing=north]')
    for x in (12,17,29,34):column(m,x,14,6,15,'smooth_sandstone','chiseled_sandstone')
    for x in (12,34):
        for z in (19,27):window(m,(x,9,z),(x,12,z+3),axis='z',color='light_blue_stained_glass')
    for x in range(23,26):m.box((x,5,17),(x,5,26),'cyan_terracotta')
    for x in (15,28):bench(m,x,6,23,4,'south','birch')
    table(m,20,6,27,9,'blue');m.set(24,7,27,'gold_block')
    m.box((21,6,29),(27,7,32),'prismarine_bricks');m.box((23,8,30),(25,8,31),'dark_prismarine')
    m.box((24,9,30),(24,12,30),'prismarine_bricks');m.box((22,10,30),(26,10,30),'prismarine_bricks');m.set(24,13,30,'sea_lantern')
    m.point('offering','work',(24,6,27),'供物台',approach=(24,6,26));m.point('ritual','circulation',(24,6,21),'正殿仪式中轴',look_at=[24,10,30])
    for x in (16,31):pendant(m,x,12,20,15)
    # Open gateway: both faces are genuine arches, with no sealed rear wall.
    pavilion(m,17,5,31,9,wall_height=5,roof='dark_prismarine')
    arch_front(m,20,4,5,9,5);arch_front(m,20,4,9,9,5)
    pavilion(m,3,19,10,34,wall_height=6);m.door(10,4,26,'birch','east')
    m.bed(5,4,32,'light_blue','north');m.point('keeper_bed','sleep',(5,4,32),'守庙人床位',approach=(6,4,32))
    table(m,5,4,24,4);m.set(4,4,20,'smoker[facing=east]');m.box((4,5,20),(4,15,20),'stone_bricks');m.set(8,4,33,'water_cauldron[level=3]')
    shelf(m,4,4,28,4,'birch','bookshelf');m.point('keeper','work',(6,4,24),'守庙人用餐与抄记',approach=(6,4,25))
    window(m,(5,6,19),(8,8,19));pendant(m,7,8,26,10)
    # Partial old colonnade with grounded remains, not floating ruins.
    for x in (37,43):
        for z,height in ((19,9),(25,6),(32,8)):
            column(m,x,z,4,height,'mossy_stone_bricks','chiseled_stone_bricks')
    m.box((37,10,18),(43,10,20),'dark_prismarine_slab[type=bottom]')
    desk(m,'history',38,4,23,5,'旧水约与祭仪记录',facing='north');shelf(m,38,4,33,4,'spruce',contents='flower_pot')
    for x,z in ((38,29),(42,27)):
        m.set(x,3,z,'moss_block');m.set(x,4,z,'azalea')
    m.room('sanctuary','高台正殿',(13,6,16),(33,13,33),'仪式轴线、坐席、供物与原创水神陈设')
    m.room('gate','前庭门亭',(18,4,6),(30,8,8),'入庭遮雨与正殿前导')
    m.room('keeper','守庙小屋',(4,4,20),(9,8,33),'值守、炊事、床位、书册与生活用品')
    m.room('ruins','东侧旧廊',(37,4,18),(43,8,34),'残柱、旧水约、供器和低矮植被')
    pier(m,18,40,30,44)
    railing(m,(18,4,44),(30,4,44),'birch','x')
    for x in (18,30):railing(m,(x,4,40),(x,4,43),'birch','z')
    m.point('water','circulation',(24,4,43),'后庭观潮台',look_at=[24,3,46]);entry(m,24,4)
    m.meta.update(roof_min_y=9,floors=[dict(name='前庭、抬高正殿与临水台',y=3,max_y=8)])
    return m


def waterway(number):
    return {3:shipyard,4:lock_house,9:customs,10:guild,11:old_pump,12:water_temple}[number]()
