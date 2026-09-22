"""Working warehouses and merchant homes for distinct water-edge parcels."""
from .model import Model
from .components import shell,window,column,arch_front,hip_roof,bench,shelf,pendant,crate_stack
from .waterway import pad,pavilion,desk,table,entry,pier,workbench,eave_beam
from .samples import railing


WAREHOUSE_PLANS=[
    ('窄岸谷物长仓',(28,24,38),28,'狭长岸地的一进长仓，粮垛分列中轴两侧，前段独立账房，北街进货、南岸装船。'),
    ('双层分货栈',(35,31,41),32,'下层大宗货与过秤，上层干货分拣及独立值守账房，七级内梯接两层，后岸留整条装卸栈桥。'),
    ('街水贯通双仓',(43,25,39),30,'两条货仓夹三格贯通搬运巷，街侧小账房独立开门，分别存船材与绳帆，后端合流到长栈桥。'),
    ('验货围院栈',(43,26,42),34,'低檐账房与验货凉棚在前，后部横向大仓承接粮包及布货；前院短驳、后岸水运分开。'),
]
MERCHANT_PLANS=[
    ('窄街绳具楼铺',(25,31,35),28,'狭长两层店宅，底层前售绳具后修补，上层起居与双床卧室；沿东墙内梯通家居，后门接岸。'),
    ('转角帆布店宅',(37,31,38),31,'L 形店宅西翼两层，北街陈列与楼上家庭生活，后侧低檐裁帆间向东巷开门，内角保留院落。'),
    ('两面鱼盐店宅',(35,32,41),34,'底层北街零售、南岸收货贯通，西侧盐货与烟熏作业各自布置，东侧内梯到后半部家庭楼层。'),
    ('阶岸杂货院宅',(37,28,47),41,'前低台杂货铺与后高两格家庭院宅错开，中间石阶接生活院，后阶降至水岸栈道。'),
]


def base(family,variant,plans):
    name,size,shore,note=plans[variant-1]
    m=Model(f'{family}-v{variant:02d}',name,size,family=family,civilization='地中海',role='fill',terrain={
        '选址':'有实际水陆转运需求、避风且可以支撑固定木桩的岸段' if family=='WT-02' else '具有实际街巷与家庭生活用水的岸上商住地块',
        '地块':note,'高程':f'主地坪方块 Y=3、脚底 Y=4；参考水面 Y=3，水岸在 Z={shore}；两层版本上层脚底 Y=11',
        '落地':'保留明确岸壁与桩脚；模板外航行宽度、水深、洪水线及道路另按实际环境核对，不填平外部水道'})
    m.meta.update(source=f"tools/structure_studio/studio/waterway_trade.py:{'warehouse' if family=='WT-02' else 'merchant'}({variant})",
        design_notes=[note],differences=[note],roof_min_y=11,floors=[dict(name='岸台与地面空间',y=3,max_y=8)],
        preview_context=dict(kind='shore',shore_z=shore,land_surface_y=4,water_surface_y=3,bed_y=-1,padding=4))
    return m


def cargo(m,key,x,y,z,w=4,d=4,kind='crate'):
    if kind=='grain':
        m.box((x,y,z),(x+w-1,y+1,z+d-1),'hay_block[axis=y]')
        m.box((x+1,y+2,z+1),(x+w-2,y+2,z+d-2),'hay_block[axis=x]')
    elif kind=='timber':
        m.box((x,y,z),(x+w-1,y+1,z+d-1),'stripped_spruce_log[axis=z]')
    elif kind=='cloth':
        m.box((x,y,z),(x+w-1,y+1,z+d-1),'white_wool')
        m.box((x,y+2,z),(x+w-1,y+2,z+d-1),'cyan_carpet')
    else:crate_stack(m,x,y,z,w,d,3)
    m.point(key,'storage',(x,y,z),{'grain':'粮包分区','timber':'船材分区','cloth':'帆布分区','crate':'周转货物'}[kind],approach=(x-1,y,z))


def quay(m,x0,x1,z0,z1,*,boat_x):
    pier(m,x0,z0,x1,z1)
    for a,b in ((x0,boat_x-3),(boat_x+3,x1)):
        if a<=b:railing(m,(a,4,z1),(b,4,z1),'spruce','x')
    for x in (x0,x1):m.set(x,4,z1,'lantern[hanging=false]')
    m.point('quay','circulation',(boat_x,4,z1-1),'水侧装卸站位',look_at=[boat_x,3,z1+2])
    m.meta['connections'].append(dict(kind='boat',pos=[boat_x,3,z1+1],direction='south',clearance=[7,6],note='停靠与装卸面；需匹配实际船只及岸线'))
    m.room('quay','水侧装卸栈桥',(x0,4,z0),(x1,8,z1),'水陆货物周转、桩基与登船净空')


def two_storey(m,x0,z0,x1,z1):
    pavilion(m,x0,z0,x1,z1,wall_height=14)
    m.box((x0+1,10,z0+1),(x1-1,10,z1-1),'birch_planks')
    for z in (z0,z1):m.box((x0,10,z),(x1,10,z),'cyan_terracotta')
    for x in (x0,x1):m.box((x,10,z0),(x,10,z1),'cyan_terracotta')
    m.meta.update(roof_min_y=18,floors=[dict(name='底层营业与货运',y=3,max_y=8),dict(name='上层生活与干货',y=10,max_y=16)])


def stair(m,x,z,width=3,key='stair'):
    # Seven rising treads from feet 4 to 11; the upper landing starts at z+7.
    m.box((x,4,z),(x+width-1,14,z+6),'air')
    for i in range(7):
        m.box((x,4,z+i),(x+width-1,4+i,z+i),'stone_bricks')
        m.box((x,4+i,z+i),(x+width-1,4+i,z+i),'stone_brick_stairs[facing=south]')
    for xx in (x-1,x+width):railing(m,(xx,11,z),(xx,11,z+6),'birch','z')
    railing(m,(x,11,z-1),(x+width-1,11,z-1),'birch','x')
    m.point(key+'_low','circulation',(x+width//2,4,z-1),'楼梯下口',look_at=[x+width//2,8,z+5])
    m.point(key+'_high','circulation',(x+width//2,11,z+7),'楼梯上口',look_at=[x+width//2,8,z+2])


def domestic(m,x0,z0,x1,z1,*,y=11,key='home',beds=2):
    table(m,x0+2,y,z0+3,min(5,x1-x0-3));bench(m,x0+2,y,z0+5,min(5,x1-x0-3),'north','birch')
    m.set(x0,y,z1,'smoker[facing=north]');m.box((x0,y+1,z1),(x0,m.size[1]-5,z1),'stone_bricks')
    m.set(x0+2,y,z1,'water_cauldron[level=3]')
    m.point(key+'_cook','work',(x0,y,z1),'家庭炉灶',approach=(x0,y,z1-1))
    for i in range(beds):
        x=x1-i*3
        m.bed(x,y,z1,'light_blue','north');m.point(key+f'_bed{i}','sleep',(x,y,z1),'家庭床位',approach=(x-1,y,z1))
    shelf(m,x0+2,y,z0,3,'birch');m.point(key+'_meal','work',(x0+3,y,z0+3),'家庭用餐',approach=(x0+3,y,z0+2))
    m.room(key,'家庭起居与睡眠',(x0,y,z0),(x1,y+5,z1),'完整餐食、清洗饮水、睡床与衣物储存')


def warehouse(variant):
    m=base('WT-02',variant,WAREHOUSE_PLANS)
    if variant==1:
        pad(m,3,3,24,27);pavilion(m,4,5,22,26)
        arch_front(m,10,4,5,7,6);arch_front(m,10,4,26,7,6)
        m.box((15,4,14),(21,9,14),'white_terracotta');m.door(18,4,14,'birch','south')
        desk(m,'ledger',16,4,9,5,'粮栈登记与验单');shelf(m,16,4,6,5,'birch','bookshelf')
        for i,z in enumerate((8,17,22)):cargo(m,f'grain{i}',6,4,z,4,3,'grain')
        cargo(m,'sacks',18,4,18,3,5,'grain');m.set(20,4,24,'crafting_table')
        m.point('weigh','work',(20,4,24),'封包与称量',approach=(19,4,24))
        window(m,(6,6,5),(8,8,5));window(m,(22,6,17),(22,8,21),axis='z')
        for z in (10,20):pendant(m,13,9,z,11)
        m.room('cargo','中轴粮包长仓',(5,4,6),(14,9,25),'粮包分堆与贯通搬运通路')
        m.room('ledger','前段账房',(16,4,6),(21,9,13),'入栈登记、票据与清点')
        m.room('packing','后段封包区',(16,4,15),(21,9,25),'待装粮包与封包作业')
        quay(m,4,23,28,34,boat_x=13);entry(m,13,4)
    elif variant==2:
        pad(m,3,3,31,31);two_storey(m,5,5,29,29)
        arch_front(m,13,4,5,9,6);arch_front(m,13,4,29,9,6)
        stair(m,25,12)
        for i,z in enumerate((8,17,24)):cargo(m,f'bulk{i}',8,4,z,5,3)
        table(m,17,4,22,5);m.set(19,5,22,'heavy_weighted_pressure_plate')
        m.point('scale','work',(19,4,22),'地面货物过秤',approach=(19,4,21))
        m.box((6,11,22),(18,17,22),'white_terracotta');m.door(14,11,22,'birch','south')
        desk(m,'ledger',7,11,25,5,'上层账务与看守');m.bed(16,11,27,'light_blue','north')
        m.point('bed','sleep',(16,11,27),'值守床位',approach=(15,11,27));shelf(m,7,11,28,5,'birch','bookshelf')
        m.set(18,11,28,'water_cauldron[level=3]');m.set(17,11,23,'barrel[facing=north]')
        for i,x in enumerate((8,17)):cargo(m,f'dry{i}',x,11,8,4,5,'cloth')
        workbench(m,'pack',20,11,24,'上层分货与封箱');shelf(m,21,11,28,6,'spruce')
        for y in (6,13):
            for x in (8,21):window(m,(x,y,5),(x+3,y+2,5))
            window(m,(5,y,17),(5,y+2,20),axis='z')
        for y in (8,16):pendant(m,17,y,16,18 if y==16 else 10)
        m.room('bulk','底层大宗货仓',(6,4,6),(24,8,28),'大宗货物、地面过秤与南北装卸')
        m.room('loft','上层干货分拣',(6,11,6),(24,16,21),'干燥货物与内部搬运')
        m.room('keeper','上层账务值守',(6,11,23),(18,16,28),'账台、票据、歇床与饮水')
        m.room('packing','上层封箱',(19,11,20),(28,16,28),'分货工作台与周转箱')
        quay(m,4,30,32,37,boat_x=17);entry(m,17,4)
    elif variant==3:
        pad(m,3,3,39,29)
        pavilion(m,4,5,16,14,wall_height=6,roof='dark_prismarine')
        pavilion(m,4,17,16,28,wall_height=8);pavilion(m,24,5,38,28)
        m.door(16,4,10,'birch','east')
        m.box((16,4,21),(16,8,24),'air');m.box((24,4,10),(24,8,13),'air');m.box((24,4,22),(24,8,25),'air')
        desk(m,'ledger',6,4,10,7,'街水货单核对');shelf(m,6,4,6,7,'birch','bookshelf')
        cargo(m,'timber',6,4,20,5,6,'timber');cargo(m,'cloth',29,4,7,6,5,'cloth')
        cargo(m,'rope',29,4,19,6,5);workbench(m,'bind',27,4,16,'绳帆打包与修整')
        window(m,(6,6,5),(13,8,5));window(m,(27,6,28),(34,8,28))
        for x,z,y in ((10,10,8),(11,23,10),(31,15,9)):pendant(m,x,y,z,y+2)
        for x in (18,22):
            column(m,x,4,4,9,'stripped_dark_oak_log','dark_oak_planks')
        m.box((18,9,4),(22,9,4),'dark_oak_log[axis=x]')
        m.room('ledger','独立街侧账房',(5,4,6),(15,8,13),'货单核对和档案')
        m.room('timber','西侧船材仓',(5,4,18),(15,10,27),'长木料与搬运侧道')
        m.room('sail','东侧绳帆仓',(25,4,6),(37,9,27),'帆布、绳具与打包作业')
        m.room('lane','街水贯通巷',(17,4,5),(23,8,29),'不进入仓内也可直通水岸的搬运通道')
        quay(m,4,38,30,35,boat_x=20);entry(m,20,3)
        m.meta['roof_min_y']=10
    elif variant==4:
        pad(m,3,3,39,33);pavilion(m,5,5,16,15,wall_height=6,roof='dark_prismarine')
        pavilion(m,5,19,37,32,wall_height=8)
        m.door(16,4,10,'birch','east');arch_front(m,17,4,19,9,7);arch_front(m,17,4,32,9,7)
        desk(m,'ledger',7,4,10,7,'货院账务');shelf(m,7,4,6,7,'birch','bookshelf')
        for x in (23,36):
            for z in (6,14):column(m,x,z,4,10,'stripped_dark_oak_log','dark_oak_planks')
        eave_beam(m,23,6,36,14,10,'dark_oak')
        hip_roof(m,22,37,5,15,10,'dark_prismarine',3)
        table(m,25,4,10,8,'blue');m.set(28,5,10,'heavy_weighted_pressure_plate')
        m.point('inspect','work',(28,4,10),'院内验货台',approach=(28,4,9));crate_stack(m,34,4,7,2,3,2)
        cargo(m,'grain',8,4,23,5,6,'grain');cargo(m,'cloth',29,4,23,5,6,'cloth')
        workbench(m,'pack',24,4,27,'仓内封包台')
        window(m,(8,6,19),(13,8,19));window(m,(28,6,19),(34,8,19))
        for x in (15,26):pendant(m,x,10,25,12)
        pendant(m,10,8,10,10);m.room('ledger','临街账房',(6,4,6),(15,8,14),'登记与签收')
        m.room('inspect','前院验货棚',(23,4,6),(36,8,14),'雨下验货、分类与临时存货')
        m.room('main','后排横向货仓',(6,4,20),(36,10,31),'粮包与帆布分区、中央水运过道和封包工作位')
        quay(m,5,37,34,38,boat_x=21);entry(m,20,4)
        m.meta['roof_min_y']=10
    return m


def merchant(variant):
    m=base('WT-05',variant,MERCHANT_PLANS)
    if variant==1:
        pad(m,3,3,21,27);two_storey(m,4,6,20,25);stair(m,17,11,2)
        m.door(10,4,6,'birch','north');m.door(10,4,25,'birch','south')
        desk(m,'counter',6,4,12,7,'绳具售卖与询价');shelf(m,6,4,7,6,'spruce')
        workbench(m,'repair',7,4,21,'小型船具修补');crate_stack(m,13,4,22,2,2,2)
        m.box((5,11,18),(15,16,18),'white_terracotta');m.door(12,11,18,'birch','south')
        table(m,6,11,11,5);bench(m,6,11,13,5,'north','birch')
        m.set(6,11,7,'smoker[facing=south]');m.box((6,12,7),(6,26,7),'stone_bricks')
        m.set(8,11,7,'water_cauldron[level=3]');shelf(m,11,11,7,4,'birch')
        m.point('cook','work',(6,11,7),'家庭炉灶',approach=(6,11,8))
        for i,x in enumerate((6,14)):
            m.bed(x,11,23,'light_blue','north');m.point(f'bed{i}','sleep',(x,11,23),'楼上家庭床位',approach=(x+1,11,23))
        shelf(m,8,11,24,4,'birch')
        for y in (6,13):window(m,(13,y,6),(16,y+2,6));window(m,(7,y,25),(14,y+2,25))
        for y in (8,16):pendant(m,11,y,16,y+2)
        m.room('shop','前店与修补',(5,4,7),(16,8,24),'绳具柜台、货架和后侧修补台')
        m.room('living','楼上家居',(5,11,7),(16,16,17),'起居、用餐、炉灶与清洗')
        m.room('bedroom','后间双床卧室',(5,11,19),(16,16,24),'双床、衣物与独立卧室门')
        quay(m,4,20,28,31,boat_x=10);entry(m,10,4)
    elif variant==2:
        pad(m,3,3,19,30);pad(m,20,17,33,30)
        two_storey(m,4,6,19,28);stair(m,16,11,2)
        pavilion(m,20,18,32,28,wall_height=6,roof='dark_prismarine')
        m.door(10,4,6,'birch','north');m.door(32,4,23,'birch','east')
        m.door(19,4,23,'birch','east');m.box((20,4,23),(20,6,23),'air')
        desk(m,'counter',6,4,12,7,'帆布柜台');m.box((6,4,7),(12,5,7),'white_wool');m.box((6,6,7),(12,6,7),'cyan_carpet')
        shelf(m,6,4,27,7,'spruce');crate_stack(m,12,4,21,2,3,2)
        table(m,23,4,23,6,'white');m.set(23,4,20,'loom[facing=south]');m.set(26,4,20,'loom[facing=south]')
        m.point('sew','work',(25,4,23),'裁帆长桌',approach=(25,4,24));m.point('loom','work',(23,4,20),'帆布织机',approach=(23,4,21))
        domestic(m,6,8,14,26)
        for y in (6,13):window(m,(12,y,6),(15,y+2,6));window(m,(4,y,20),(4,y+2,24),axis='z')
        window(m,(24,6,28),(29,8,28));pendant(m,10,8,18,10);pendant(m,10,16,17,18);pendant(m,27,8,23,10)
        m.room('shop','北街帆布铺',(5,4,7),(15,8,27),'陈列、销售与后仓')
        m.room('workshop','东巷裁帆间',(21,4,19),(31,8,27),'织机、裁帆台与独立取货门')
        quay(m,4,32,31,34,boat_x=24);entry(m,10,4)
        entry(m,33,23,key='east',name='东巷取货入口',face='east')
    elif variant==3:
        pad(m,3,3,31,33);pavilion(m,5,6,29,31,wall_height=7)
        # Remove the temporary single-storey rear roof before building the high wing.
        m.box((4,10,15),(30,23,32),'air')
        shell(m,(5,10,16),(29,17,31),'white_terracotta',floor='birch_planks',ceiling='birch_planks')
        eave_beam(m,5,16,29,31,19)
        hip_roof(m,4,30,15,32,19,'brick',6)
        m.box((24,10,14),(27,10,16),'birch_planks');stair(m,24,8,3)
        arch_front(m,13,4,6,9,6);arch_front(m,13,4,31,9,6)
        m.box((6,4,21),(13,9,21),'white_terracotta');m.door(10,4,21,'birch','south')
        desk(m,'counter',7,4,13,6,'鱼盐干货柜台');shelf(m,7,4,7,6,'spruce')
        m.box((7,4,24),(9,5,27),'quartz_block');m.set(12,4,27,'smoker[facing=north]')
        m.box((12,5,27),(12,26,27),'stone_bricks');m.set(10,4,30,'water_cauldron[level=3]')
        m.point('smoke','work',(12,4,27),'烟熏备货',approach=(12,4,26))
        crate_stack(m,24,4,24,3,5,2);m.point('receive','storage',(24,4,24),'岸侧收货',approach=(23,4,24))
        m.box((24,11,16),(27,14,16),'air')
        domestic(m,7,18,22,29)
        for x in (7,23):window(m,(x,6,6),(x+3,8,6))
        window(m,(7,13,16),(13,15,16));window(m,(29,13,22),(29,15,26),axis='z')
        pendant(m,17,8,20,10);pendant(m,17,16,22,18)
        m.room('sales','北街售货前厅',(6,4,7),(23,8,20),'盐货与鱼干柜台、货架和贯通走道')
        m.room('smoke','西后烟熏备货',(6,4,22),(13,8,30),'存盐、备货、炉灶与清洗')
        m.room('receiving','南岸收货区',(14,4,22),(28,8,30),'双面进出与暂存箱')
        m.meta.update(roof_min_y=18,floors=[dict(name='街岸售货与加工',y=3,max_y=8),dict(name='后半部家居楼层',y=10,max_y=16)])
        quay(m,5,29,34,37,boat_x=17);entry(m,17,4)
    elif variant==4:
        pad(m,3,3,32,40);pad(m,5,23,31,39,5)
        pavilion(m,4,6,20,19,wall_height=7);pavilion(m,6,24,30,38,f=5,wall_height=7,roof='dark_prismarine')
        m.door(11,4,6,'birch','north');m.door(14,4,19,'birch','south');m.door(18,6,24,'birch','north');m.door(18,6,38,'birch','south')
        for x in range(16,21):
            m.set(x,4,21,'stone_brick_stairs[facing=south]');m.set(x,4,22,'stone_bricks');m.set(x,5,22,'stone_brick_stairs[facing=south]')
            m.set(x,4,39,'stone_bricks');m.set(x,5,39,'stone_brick_stairs[facing=north]');m.set(x,4,40,'stone_brick_stairs[facing=north]')
        desk(m,'counter',6,4,12,8,'坡岸杂货柜台');shelf(m,6,4,7,6,'spruce');crate_stack(m,16,4,15,3,3,2)
        domestic(m,8,26,28,36,y=6)
        table(m,24,4,11,5);bench(m,24,4,13,5,'north','birch')
        for x in (24,30):
            for z in (7,16):column(m,x,z,4,10,'stripped_dark_oak_log','dark_oak_planks')
        eave_beam(m,24,7,30,16,10,'dark_oak')
        hip_roof(m,23,31,6,17,10,'dark_prismarine',3)
        m.point('tea','work',(26,4,11),'坡岸院内歇脚',approach=(26,4,10))
        window(m,(15,6,6),(18,8,6));window(m,(9,8,24),(13,10,24));window(m,(24,8,24),(28,10,24))
        pendant(m,12,9,15,11);pendant(m,18,11,31,13)
        m.room('shop','前低台杂货铺',(5,4,7),(19,9,18),'销售、日杂陈列与补货')
        m.room('court','侧院歇脚棚',(24,4,7),(30,8,16),'客人歇坐与卸货后的短暂停留')
        quay(m,7,30,41,44,boat_x=18);entry(m,11,4)
        m.meta['terrain']['高程']='前铺与栈道脚底 Y=4，后家庭院宅脚底 Y=6；前后实体双级石阶联系岸上高台'
        m.meta.update(roof_min_y=10,floors=[dict(name='低铺、高台家居与岸道',y=3,max_y=9)])
    return m
