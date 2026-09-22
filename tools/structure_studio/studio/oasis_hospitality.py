"""Authored inns and everyday trades for oasis streets."""
from .model import Model
from .components import bench,shelf,pendant,crate_stack,window
from .desert import room_shell,table,dome
from .oasis_life import paved,living,sleeping,ventilation
from .oasis_yards import canvas
from .oasis_crafts import textile_shelf,door_canopy,pool,kiln
from .samples import railing


INN_PLANS=[
    ('路旁双间客栈',(29,24,37),'前厅接待与进餐，后排两间客房分开，侧道通后院；适合商路旁的小补给点。'),
    ('四翼水庭旅舍',(43,24,43),'四间客房分列两翼，中央水庭、前门登记小屋与后排食堂形成围院，房门各自朝院。'),
    ('窄街楼廊客栈',(25,32,39),'底层前登记后餐饮，上层双客房与盥洗间接单侧走廊，七级内梯适应窄街占地。'),
    ('阶庭旅具客栈',(44,25,43),'前低台接待、餐饮和洗漱维修，后高台双客房接遮阳廊，中间短阶适应一格缓坡。'),
]
SHOP_PLANS=[
    ('街口薄饼干粮铺',(17,23,25),'小矩形店铺前售后烤，备料与烤炉相邻，短遮阳檐面对聚落街口。'),
    ('窄巷香料铺',(13,23,29),'狭长铺面前部分类香料，后间配料与存货，用小门分隔日常销售和备货。'),
    ('转角织物裁缝铺',(25,23,27),'L 形铺面围住歇脚小院，北门陈列、东侧裁缝作业，织机与试衣空间分别布置。'),
    ('院边旅具修补铺',(30,23,27),'封闭前铺卖小工具，院内开放凉棚承担修补、打磨和备件存放，适合有侧院的商路街段。'),
    ('凉篷饮水茶铺',(23,23,29),'前篷等候与取水，后厅分开服务柜台、两组茶座及烧水备料，依赖可靠的生活供水。'),
    ('露窑日用陶器铺',(27,23,29),'西侧制坯工作厅，东侧遮棚陈列与露天小窑，原泥、器皿、烧制和维护顺序明确。'),
    ('楼上抄图书铺',(19,31,29),'下层售书与阅读，上层抄图工作间兼值守睡眠，内部楼梯联系紧凑两层。'),
    ('分台果蔬小铺',(32,23,27),'低台遮棚售卖，高台仓房与值守间分开，短阶接一格地坪差，适合绿洲坡缘生活街。'),
]


def base(family,variant,plans,*,corner=None,grade_at=None):
    name,size,description=plans[variant-1]
    is_inn=family=='DS-08'
    m=Model(f'{family}-v{variant:02d}',name,size,family=family,civilization='沙海星象文明',role='fill',terrain={
        '选址':'有可靠生活用水与补给条件的聚落入口或商路停驻点' if is_inn else '有实际居民、旅人和相应行业供需的绿洲生活街区',
        '地块':description,'高程':'主入口脚底 Y=2；后高台脚底 Y=3' if grade_at else '主入口脚底 Y=2；两层版上层脚底 Y=9',
        '落地':'结构连同内院及铺面完整落地；外部交通、供水与库存另行接入，未写入的凹角保持原环境'})
    m.meta.update(source=f"tools/structure_studio/studio/oasis_hospitality.py:{'inn' if is_inn else 'shop'}({variant})",
        design_notes=[description],differences=[description],roof_min_y=8,floors=[dict(name='地面空间与院落',y=1,max_y=6)],
        preview_context=dict(kind='flat',land_surface_y=2,bed_y=-1,padding=3,surface='sand'))
    w,_,d=size;cells={(x,z) for x in range(2,w-2) for z in range(3,d-2)}
    if corner:cells={(x,z) for x,z in cells if x<=corner[0] or z>=corner[1]}
    paved(m,cells,lambda x,z:2 if grade_at and z>=grade_at else 1)
    if grade_at:m.meta['preview_context'].update(kind='slope',run=grade_at,rise=1,slope_origin_z=0)
    return m


def entry(m,x,z,*,key='entry',facing='north',name='主入口'):
    m.point(key,'entrance',(x,2,z),name,facing=facing)
    m.meta['connections'].append(dict(kind='pedestrian',pos=[x,1,z],direction=facing,clearance=[3,3],note='接外部步行街或商旅停驻面，脚底 Y=2'))


def desk(m,key,x,y,z,width=4):
    table(m,x,y,z,width,'blue');m.set(x+1,y,z,'lectern[facing=south]');m.set(x+1,y+1,z,'air')
    m.point(key,'work',(x+1,y,z),'接待登记',approach=(x+1,y,z+1))


def tools_corner(m,key,x,y,z):
    m.set(x,y,z,'crafting_table');m.set(x+2,y,z,'anvil[facing=east]')
    m.set(x,y,z+2,'barrel[facing=north]')
    m.point(key,'work',(x,y,z),'旅具检查与修补',approach=(x,y,z-1))


def inn_hall(m,x0,z0,x1,z1):
    room_shell(m,x0,z0,x1,z1)
    desk(m,'register',x0+2,2,z0+3)
    table(m,x0+6,2,z0+7,5,'cyan');bench(m,x0+6,2,z0+9,5,'north','birch')
    m.point('meal','work',(x0+8,2,z0+7),'旅人用餐桌',approach=(x0+8,2,z0+6))
    m.set(x0+1,2,z1-1,'smoker[facing=north]');m.box((x0+1,3,z1-1),(x0+1,12,z1-1),'sandstone')
    m.set(x0+3,2,z1-1,'water_cauldron[level=3]');shelf(m,x1-4,2,z1-1,3,material='acacia')
    m.point('cook','work',(x0+1,2,z1-1),'餐食炉灶',approach=(x0+1,2,z1-2))
    tools_corner(m,'repair',x1-4,2,z0+3)
    m.room('common','登记餐饮与补给前厅',(x0+1,2,z0+1),(x1-1,6,z1-1),'登记、用餐、炊事和旅具修补')
    for x in (x0+5,x1-5):pendant(m,x,6,z0+5,8)


def north_stair(m,x,z,*,upper=8):
    m.box((x,upper,z-6),(x+1,upper,z+2),'air')
    for i in range(7):
        for xx in (x,x+1):
            m.box((xx,2,z-i),(xx,2+i,z-i),'sandstone');m.set(xx,2+i,z-i,'sandstone_stairs[facing=north]')
    m.box((x,8,z-8),(x+1,8,z-7),'birch_planks')
    railing(m,(x-1,9,z-6),(x-1,9,z+2),'birch','z')


def inn(variant):
    m=base('DS-08',variant,INN_PLANS,grade_at=20 if variant==4 else None)
    if variant==1:
        inn_hall(m,4,6,24,18)
        sleeping(m,'west','单人客房',(4,21),(13,32));sleeping(m,'east','双人客房',(15,21),(24,32),two=True)
        for x,y,z,face in [(13,2,6,'north'),(13,2,18,'south'),(10,2,21,'north'),(20,2,21,'north')]:m.door(x,y,z,facing=face)
        door_canopy(m,13,6,6,7);ventilation(m,6,23)
        m.set(26,2,27,'water_cauldron[level=3]');m.point('wash','work',(26,2,27),'院侧洗漱',approach=(26,2,26))
        bench(m,6,2,34,5,'north','birch');m.room('rear','后院歇脚',(4,2,33),(24,6,34),'客房后歇坐与旅人通路')
        window(m,(17,3,6),(21,5,6),color='cyan_stained_glass');entry(m,13,4)
    elif variant==2:
        for key,label,x0,x1,z0,z1,face in [('nw','西前客房',4,14,6,18,'east'),('sw','西后客房',4,14,22,36,'east'),
                                            ('ne','东前客房',29,39,6,18,'west'),('se','东后客房',29,39,22,36,'west')]:
            sleeping(m,key,label,(x0,z0),(x1,z1),two=True)
            m.door(x1 if face=='east' else x0,2,z0+7,facing=face)
        room_shell(m,18,6,25,13);desk(m,'register',19,2,9,4)
        m.set(24,2,11,'crafting_table');m.point('repair','work',(24,2,11),'旅具修补小角',approach=(23,2,11))
        m.room('reception','门厅登记小屋',(19,2,7),(24,6,12),'入住登记与简单修补')
        m.door(22,2,6,facing='north');m.door(22,2,13,facing='south')
        living(m,'kitchen','后庭公用食堂',(16,27),(27,37))
        m.box((18,2,30),(24,3,32),'air')
        table(m,18,2,30,7,'cyan');bench(m,18,2,32,7,'north','birch')
        m.door(22,2,27,facing='north');m.point('meal','work',(21,2,30),'食堂餐桌',approach=(21,2,29))
        pool(m,22,20);canvas(m,16,16,27,24,y=7)
        bench(m,17,2,25,3,'north','birch');m.set(28,2,25,'water_cauldron[level=3]')
        m.point('wash','work',(28,2,25),'庭院公用水盆',approach=(28,2,24))
        m.room('court','遮阳水庭',(15,2,14),(28,6,26),'客房门廊、候坐、凉棚与取水')
        ventilation(m,6,8);ventilation(m,33,26);dome(m,21,9,9,r=3)
        door_canopy(m,22,6,6,5);entry(m,22,4)
    elif variant==3:
        room_shell(m,3,6,21,34);room_shell(m,3,6,21,34,f=8)
        m.box((4,2,17),(20,6,17),'white_terracotta');m.door(13,2,17,facing='south')
        desk(m,'register',6,2,11,6);shelf(m,5,2,7,5,material='acacia');bench(m,15,2,12,3,'north','birch')
        table(m,7,2,23,5,'cyan');bench(m,7,2,25,5,'north','birch')
        m.point('meal','work',(9,2,23),'楼下餐桌',approach=(9,2,22))
        m.set(4,2,33,'smoker[facing=north]');m.set(6,2,33,'water_cauldron[level=3]');shelf(m,8,2,33,4,material='acacia')
        m.point('cook','work',(4,2,33),'楼下厨房',approach=(4,2,32));tools_corner(m,'repair',15,2,20)
        m.room('reception','临街登记厅',(4,2,7),(20,6,16),'寄存、登记与候坐')
        m.room('dining','后部食堂与楼梯',(4,2,18),(20,6,33),'餐食、旅具修补与上楼通路')
        sleeping(m,'front','楼前双人房',(3,6),(13,18),f=8,two=True)
        sleeping(m,'rear','楼后双人房',(3,20),(13,34),f=8,two=True)
        m.door(13,9,14,facing='east');m.door(13,9,26,facing='east')
        m.box((15,9,7),(15,14,16),'white_terracotta');m.box((15,9,16),(20,14,16),'white_terracotta');m.door(18,9,16,facing='south')
        for x in (17,19):m.set(x,9,8,'water_cauldron[level=3]')
        bench(m,17,9,13,2,'north','birch');m.point('wash','work',(17,9,8),'楼上公用盥洗',approach=(17,9,9))
        m.room('wash','楼上盥洗间',(16,9,7),(20,13,15),'洗漱、坐席和洗漱用品')
        north_stair(m,18,31)
        m.point('upper','circulation',(16,9,21),'上层侧廊',look_at=[16,10,14])
        m.room('upper','客房侧廊',(14,9,7),(20,13,33),'连接内梯、两间客房和盥洗间')
        m.box((4,3,33),(4,20,33),'sandstone');ventilation(m,5,7,y=16)
        m.door(13,2,6,facing='north');door_canopy(m,13,6,6,7)
        window(m,(5,3,6),(10,5,6),color='cyan_stained_glass');window(m,(5,10,6),(10,12,6),color='cyan_stained_glass')
        m.meta.update(roof_min_y=15,floors=[dict(name='登记与餐饮',y=1,max_y=6),dict(name='客房与盥洗',y=8,max_y=13)])
        entry(m,13,4)
    else:
        inn_hall(m,4,6,22,17)
        m.door(13,2,6,facing='north');m.door(22,2,12,facing='east');door_canopy(m,13,6,6,7)
        room_shell(m,26,6,38,17);m.box((32,2,7),(32,6,16),'white_terracotta');m.door(32,2,12,facing='east');m.door(26,2,11,facing='west')
        for x in (28,30):m.set(x,2,8,'water_cauldron[level=3]')
        m.box((28,2,10),(30,3,10),'smooth_sandstone');m.point('wash','work',(28,2,8),'低台公用盥洗',approach=(28,2,9))
        tools_corner(m,'large_repair',34,2,10);bench(m,34,2,15,3,'north','birch')
        m.room('wash','独立盥洗间',(27,2,7),(31,6,16),'隔屏后洗漱与用品存放')
        m.room('repair','旅具维修间',(33,2,7),(37,6,16),'修补与临时备件')
        sleeping(m,'west','高台西客房',(4,24),(18,37),f=2,two=True)
        sleeping(m,'east','高台东客房',(24,24),(38,37),f=2,two=True)
        for x in (11,31):m.door(x,3,24,facing='north')
        for x in range(20,23):m.set(x,2,20,'sandstone_stairs[facing=south]')
        canvas(m,4,21,18,23,y=8,base_y=3);canvas(m,24,21,38,23,y=8,base_y=3)
        m.room('veranda','高台遮阳廊',(4,3,21),(38,7,23),'短阶接客房门廊，适应一格高差')
        m.point('upper','circulation',(21,3,22),'高台走廊',look_at=[11,4,24])
        ventilation(m,6,27,y=10);m.meta['floors']=[dict(name='低台补给与高台客房',y=1,max_y=7)]
        entry(m,13,4)
    return m


def shop(variant):
    m=base('DS-F01',variant,SHOP_PLANS,corner=(13,13) if variant==3 else None,grade_at=15 if variant==8 else None)
    if variant==1:
        room_shell(m,3,6,13,20)
        table(m,5,2,11,6,'orange')
        for x in (5,8):m.set(x,3,11,'cake[bites=0]')
        m.point('sales','work',(7,2,11),'薄饼与干粮柜台',approach=(7,2,12))
        m.set(4,2,17,'smoker[facing=east]');m.box((4,3,17),(4,12,17),'sandstone')
        table(m,8,2,17,4,'white');m.set(8,2,17,'crafting_table');m.set(8,3,17,'air')
        m.point('bake','work',(4,2,17),'烘烤炉',approach=(5,2,17));m.point('prep','work',(8,2,17),'和面备餐台',approach=(8,2,16))
        m.set(12,2,19,'barrel[facing=north]');m.set(6,2,19,'water_cauldron[level=3]')
        m.room('shop','干粮与烘烤间',(4,2,7),(12,6,19),'展示、备料、饮水和烘烤')
        m.door(11,2,6,facing='north');door_canopy(m,10,6,6,5)
        window(m,(5,3,6),(8,5,6),color='yellow_stained_glass');dome(m,8,11,9,r=3)
        pendant(m,8,6,14,8);entry(m,11,4)
    elif variant==2:
        room_shell(m,3,5,9,24);m.box((4,2,17),(8,6,17),'white_terracotta');m.door(6,2,17,facing='south')
        table(m,5,2,11,3,'orange')
        for x,color in ((5,'red'),(6,'yellow'),(7,'brown')):
            m.set(x,3,11,color+'_carpet');m.set(x,2,13,'barrel[facing=up]')
        m.point('sales','work',(6,2,11),'分类香料台',approach=(6,2,10))
        for z in (7,9,14):
            m.set(4,2,z,'acacia_slab[type=top]');m.set(4,3,z,'potted_dead_bush')
        m.set(4,2,22,'crafting_table');crate_stack(m,7,2,21,2,2,2)
        m.point('blend','work',(4,2,22),'香料配料台',approach=(5,2,22))
        m.room('sales','窄巷香料前铺',(4,2,6),(8,6,16),'香料陈列、干燥香草与分类容器')
        m.room('mixing','后间配料与储藏',(4,2,18),(8,6,23),'小批配料与备货')
        m.door(6,2,5,facing='north');door_canopy(m,6,6,5,5)
        window(m,(4,4,5),(8,5,5),color='orange_stained_glass');ventilation(m,5,19)
        pendant(m,6,6,14,8);entry(m,6,3)
    elif variant==3:
        room_shell(m,3,5,12,16);room_shell(m,13,13,21,23)
        textile_shelf(m,5,2,9,5);table(m,5,2,12,5,'cyan')
        m.point('sales','work',(7,2,12),'织物接待台',approach=(7,2,11))
        for x in (15,17):m.set(x,2,17,'loom[facing=north]')
        m.point('sew','work',(15,2,17),'织机与修衣',approach=(15,2,16))
        table(m,15,2,21,3,'white');m.set(15,3,21,'flower_pot')
        m.box((19,2,20),(19,3,22),'white_wool');m.box((19,4,20),(19,4,22),'acacia_slab[type=top]')
        m.point('fit','circulation',(20,2,21),'试衣隔帘',look_at=[20,3,17])
        m.box((12,2,15),(13,4,15),'air');m.door(8,2,5,facing='north');m.door(21,2,18,facing='east')
        canvas(m,3,18,12,23,y=7,colors=('cyan','white'));bench(m,5,2,21,4,'north','birch')
        m.room('sales','北侧布匹陈列',(4,2,6),(11,6,15),'织物样品与接待')
        m.room('work','东侧裁缝间',(14,2,14),(20,6,22),'织机、裁布桌与试衣隔帘')
        m.room('court','院边歇脚棚',(3,2,18),(12,6,23),'等候取衣与通风歇坐')
        window(m,(4,3,5),(6,5,5),color='cyan_stained_glass');door_canopy(m,8,6,5,5)
        pendant(m,8,6,13,8);ventilation(m,16,18)
        entry(m,8,3);entry(m,22,18,key='east',facing='east',name='东侧取衣口')
    elif variant==4:
        room_shell(m,3,5,13,19);m.door(8,2,5,facing='north');m.door(13,2,14,facing='east')
        table(m,5,2,10,6,'gray');m.set(6,2,10,'smithing_table');m.set(6,3,10,'air')
        m.set(10,3,10,'grindstone[face=floor,facing=north]');shelf(m,5,2,17,6,material='acacia')
        m.point('sales','work',(7,2,10),'旅具与配件柜台',approach=(7,2,11))
        canvas(m,16,9,25,21,y=8,colors=('light_gray','white'))
        m.box((16,1,12),(25,1,21),'stone_bricks')
        m.set(17,2,14,'crafting_table');m.set(22,2,14,'anvil[facing=east]');m.set(19,2,18,'grindstone[face=floor,facing=north]')
        crate_stack(m,23,2,18,2,3,2);m.set(17,2,20,'water_cauldron[level=3]')
        m.point('repair','work',(17,2,14),'修补工作台',approach=(17,2,13));m.point('forge','work',(22,2,14),'旅具整形砧',approach=(22,2,13))
        m.room('shop','旅具小铺',(4,2,6),(12,6,18),'小工具展示、接待与备件')
        m.room('yard','院内维修凉棚',(16,2,9),(25,7,21),'修补、打磨、取水和备料')
        door_canopy(m,8,6,5,5);window(m,(5,3,5),(6,5,5),color='cyan_stained_glass');pendant(m,8,6,13,8)
        entry(m,8,3)
    elif variant==5:
        room_shell(m,3,7,19,23);m.door(11,2,7,facing='north')
        canvas(m,4,3,18,6,y=7,colors=('cyan','white'))
        table(m,5,2,11,6,'cyan')
        for x in (5,7,9):m.set(x,2,9,'water_cauldron[level=3]')
        m.point('water','work',(7,2,9),'净水储盆',approach=(7,2,8));m.point('serve','work',(8,2,11),'取水与茶饮柜台',approach=(8,2,12))
        for x,key in ((5,'west'),(13,'east')):
            table(m,x,2,17,4,'blue');bench(m,x,2,19,4,'north','birch')
            m.set(x+1,3,17,'flower_pot');m.point(key,'work',(x+1,2,17),'茶座',approach=(x+1,2,16))
        m.set(4,2,21,'smoker[facing=east]');m.box((4,3,21),(4,12,21),'sandstone')
        m.point('boil','work',(4,2,21),'烧水与简餐炉',approach=(5,2,21));shelf(m,15,2,22,3,material='acacia')
        m.room('tea','饮水与茶座厅',(4,2,8),(18,6,22),'柜台、两组座席、储水与烧水')
        m.room('canopy','临街凉篷',(4,2,3),(18,6,6),'排队取水和街边歇凉')
        for x in (5,14):window(m,(x,3,7),(x+2,5,7),color='cyan_stained_glass')
        pendant(m,11,6,16,8);ventilation(m,14,15);entry(m,11,3)
        m.meta['terrain']['供水']='仅用于能稳定取得生活用水的位置；锅盆为储水与服务空间，不会凭模板产生水源或经济服务'
    elif variant==6:
        room_shell(m,3,7,15,24);m.door(9,2,7,facing='north');m.door(15,2,17,facing='east')
        m.box((5,2,10),(7,2,12),'clay');m.set(8,2,11,'water_cauldron[level=3]')
        m.point('clay','work',(6,2,10),'原泥备料',approach=(6,2,9))
        table(m,5,2,17,6,'white');m.set(5,2,17,'crafting_table');m.set(5,3,17,'air')
        for x in (7,9):m.set(x,3,17,'flower_pot')
        m.point('mould','work',(5,2,17),'制坯台',approach=(5,2,16));shelf(m,5,2,23,8,material='acacia',contents='flower_pot')
        canvas(m,18,8,23,17,y=7,colors=('orange','white'));table(m,19,2,11,4,'white')
        for x in range(19,23):m.set(x,3,11,'flower_pot')
        table(m,18,2,15,5,'brown');m.point('sales','work',(20,2,15),'日用陶器陈列',approach=(20,2,14))
        kiln(m,'kiln',19,20)
        m.room('mould','制坯与晾干厅',(4,2,8),(14,6,23),'原泥、水盆、制坯与室内晾干架')
        m.room('sales','遮棚陶器陈列',(18,2,8),(23,6,17),'样品与出售柜台')
        m.room('kiln','独立露窑',(18,2,19),(24,6,26),'装窑、出窑和后方维护')
        window(m,(4,3,7),(7,5,7),color='yellow_stained_glass');door_canopy(m,9,6,7,5)
        pendant(m,9,6,14,8);entry(m,9,4)
        m.meta['design_notes'].append('陶器窑炉借用原版熔炉表达建筑用途，未接入制陶配方、晾干或烧成机制。')
    elif variant==7:
        room_shell(m,3,6,15,24);room_shell(m,3,6,15,24,f=8)
        desk(m,'sales',5,2,10,5);m.box((4,2,23),(10,4,23),'bookshelf')
        table(m,5,2,15,3,'cyan');bench(m,5,2,17,3,'north','birch')
        m.point('read','work',(6,2,15),'店内阅读桌',approach=(6,2,14))
        table(m,4,9,9,5,'blue');m.set(4,9,9,'cartography_table');m.set(4,10,9,'air')
        m.point('maps','work',(4,9,9),'楼上抄图工作台',approach=(4,9,10));m.box((4,9,16),(8,11,16),'bookshelf')
        m.box((4,9,18),(10,12,18),'white_terracotta');m.door(9,9,18,facing='south')
        m.bed(6,9,21,'cyan','north');m.point('sleep','sleep',(6,9,21),'值守睡眠',approach=(7,9,21))
        shelf(m,4,9,23,4,material='birch');m.set(10,9,23,'water_cauldron[level=3]')
        north_stair(m,12,21);ventilation(m,5,18,y=16)
        m.room('shop','楼下书铺',(4,2,7),(14,6,23),'账台、阅读与书架')
        m.room('maps','楼上抄图间',(4,9,7),(14,13,17),'绘图、卷册与工作用品')
        m.room('sleep','后部值守床间',(4,9,19),(10,13,23),'隔屏后的卧床、衣柜与饮水')
        m.door(10,2,6,facing='north');door_canopy(m,10,6,6,5)
        window(m,(5,3,6),(7,5,6),color='cyan_stained_glass');window(m,(5,10,6),(11,12,6),color='cyan_stained_glass')
        pendant(m,8,6,13,8);pendant(m,9,13,12,15)
        m.meta.update(roof_min_y=15,floors=[dict(name='售书与阅读',y=1,max_y=6),dict(name='抄图与值守',y=8,max_y=13)])
        entry(m,10,4)
    else:
        canvas(m,4,5,16,12,y=7,colors=('lime','white'))
        table(m,6,2,9,7,'green')
        for x,block in ((6,'melon'),(9,'pumpkin'),(12,'hay_block')):m.set(x,2,9,block);m.set(x,3,9,'air')
        m.point('sales','work',(9,2,9),'果蔬与粮食小柜台',approach=(9,2,8))
        sleeping(m,'staff','高台值守间',(4,17),(14,23),f=2)
        m.door(11,3,17,facing='north');room_shell(m,18,15,27,23,f=2);m.door(18,3,19,facing='west')
        crate_stack(m,20,3,17,4,2,2);m.set(25,3,21,'water_cauldron[level=3]')
        m.point('stock','work',(20,3,18),'高台周转筐',approach=(19,3,18))
        m.room('stock','高台存货间',(19,3,16),(26,7,22),'果蔬粮食暂存与补货')
        m.room('stall','下台售卖凉棚',(4,2,5),(16,6,12),'展示与顾客取货')
        for x in range(15,18):m.set(x,2,15,'sandstone_stairs[facing=south]')
        ventilation(m,20,17,y=10);m.meta['floors']=[dict(name='下台售卖与高台生活',y=1,max_y=6)]
        entry(m,10,3)
    return m
