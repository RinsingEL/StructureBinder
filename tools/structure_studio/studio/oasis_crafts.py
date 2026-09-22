"""Merchant domestic compounds and glass workshops with distinct circulation."""
from .model import Model
from .components import bench,crate_stack,window,shelf,pendant,arch_front
from .desert import room_shell,table,dome
from .oasis_life import living,sleeping,ventilation,paved
from .oasis_yards import canvas
from .samples import railing


MERCHANT_PLANS=[
    ('前铺水庭商宅',(41,24,39),'前排展示与账房接公共水庭，后排家居卧室分开，侧廊只存少量周转货物。'),
    ('转角双门商宅',(37,24,41),'L 形沿街地块，前铺面向北巷，账房从东侧接货，家居沿内部窄庭相连。'),
    ('双庭分客商宅',(49,24,43),'中央账房分隔前后两庭，前庭会商与存货，后庭家宴与起居，侧廊连接两者。'),
    ('楼院账房商宅',(32,31,38),'下层铺面与货仓，上层家居、账房和露台；内部楼梯连接商业与生活。'),
]
GLASS_PLANS=[
    ('单窑玻璃作坊',(29,24,37),'单窑设于工作厅后部，原料近前口、加工与冷却靠侧墙，后院另有休息与燃料棚。'),
    ('双窑露院工坊',(43,24,43),'露天双窑与遮阳操作区并列，东北原料库、南侧加工冷却厅和独立休息室围院展开。'),
    ('前售楼展玻璃坊',(35,31,41),'下层前售后作，上层展示和职工休息；内部楼梯通向屋面连廊，避开窑炉烟道。'),
    ('分台玻璃院坊',(39,24,39),'下台收料与成品，上台窑炉和加工，短阶连接一格高差，生产与顾客空间分开。'),
]


def base(family,variant,plans,*,grade=False,corner=None):
    name,size,description=plans[variant-1]
    m=Model(f'{family}-v{variant:02d}',name,size,family=family,civilization='星仪王国',role='fill',terrain={
        '选址':'可靠供水的贸易聚落，有顾客步行、货物卸载与家居生活需求' if family=='DS-05' else '具砂料、燃料、供水与外运条件的生产地块，热作业区和烟道周围保持净空',
        '地块':description,'接地':'下台脚底 Y=2，上台脚底 Y=3，沿 +Z 抬升一格' if grade else '主入口与地面层脚底 Y=2；上层版二层脚底 Y=9',
        '落地':'独立完整建筑与内部院落，不依靠运行时补齐；模板外与凹角未写入格保持原环境'})
    source='merchant' if family=='DS-05' else 'glassworks'
    m.meta.update(source=f'tools/structure_studio/studio/oasis_crafts.py:{source}({variant})',
        design_notes=[description],differences=[description],roof_min_y=8,
        floors=[dict(name='室内与院落',y=1,max_y=6)],preview_context=dict(kind='flat',land_surface_y=2,bed_y=-1,padding=3,surface='sand'))
    w,_,d=size;cells={(x,z) for x in range(2,w-2) for z in range(3,d-2)}
    if corner:cells={(x,z) for x,z in cells if x<=corner[0] or z>=corner[1]}
    paved(m,cells,lambda x,z:2 if grade and z>=20 else 1)
    if grade:m.meta['preview_context'].update(kind='slope',run=20,rise=1,slope_origin_z=0)
    return m


def entrance(m,key,x,z,*,facing='north',name='主入口'):
    m.point(key,'entrance',(x,2,z),name,facing=facing)
    m.meta['connections'].append(dict(kind='pedestrian',pos=[x,1,z],direction=facing,clearance=[3,3],note='外部步行或卸货面需接脚底 Y=2'))


def rug(m,x,z,w,d,*,y=2,color='blue'):
    for xx in range(x,x+w):
        for zz in range(z,z+d):
            if m.blocks.get((xx,y,zz),('minecraft:air',()))[0]=='minecraft:air':
                m.set(xx,y-1,zz,('orange' if xx in (x,x+w-1) or zz in (z,z+d-1) else color)+'_wool')


def textile_shelf(m,x,y,z,width=4):
    shelf(m,x,y,z,width,material='acacia',contents='white_wool')
    for i in range(width):m.set(x+i,y+1,z,('cyan','orange','blue','white')[i%4]+'_wool')


def door_canopy(m,x,y,z,width=5):
    """Shallow shade supported by the front wall and two brackets."""
    for xx in range(x-width//2,x+width//2+1):
        m.box((xx,y,z-2),(xx,y,z-1),'orange_wool' if (xx-x)%2 else 'white_wool')
    for xx in (x-width//2,x+width//2):m.set(xx,y-1,z-1,'acacia_stairs[facing=south,half=top]')


def accounts(m,x0,z0,x1,z1,*,f=1,key='accounts'):
    room_shell(m,x0,z0,x1,z1,f=f)
    table(m,x0+2,f+1,z0+4,x1-x0-3,'blue')
    m.set(x0+3,f+1,z0+4,'lectern[facing=south]');m.set(x0+3,f+2,z0+4,'air')
    shelf(m,x1-3,f+1,z1-1,2,material='acacia',contents='bookshelf')
    m.set(x0+1,f+1,z1-1,'barrel[facing=east]')
    m.point(key,'work',(x0+3,f+1,z0+4),'会商与账记',approach=(x0+3,f+1,z0+5))
    m.room(key,'会客账房',(x0+1,f+1,z0+1),(x1-1,f+5,z1-1),'接待、账册、贵重小货与交易记录')
    pendant(m,(x0+x1)//2,f+5,z0+3,f+7)
    m.set(x0+3,f+1,z0+3,'birch_stairs[facing=south]')
    if z1-z0>=9:
        bench(m,x0+2,f+1,z0+7,min(3,x1-x0-4),'north','birch')
        rug(m,x0+2,z0+5,min(5,x1-x0-3),2,y=f+1)


def trade_room(m,x0,z0,x1,z1,*,key='showroom'):
    room_shell(m,x0,z0,x1,z1)
    table(m,x0+2,2,z0+4,x1-x0-3,'orange')
    for x in range(x0+2,x1-1,3):m.set(x,3,z0+4,'flower_pot')
    shelf(m,x0+2,2,z1-1,min(5,x1-x0-4),material='acacia')
    m.point(key,'work',(x0+3,2,z0+4),'接待与商品展示',approach=(x0+3,2,z0+5))
    m.room(key,'临街接待铺面',(x0+1,2,z0+1),(x1-1,6,z1-1),'商品、接待柜台和备货')
    window(m,(x0+2,3,z0),(x0+4,5,z0),color='cyan_stained_glass')
    textile_shelf(m,x0+2,2,z1-1,min(5,x1-x0-4))
    bench(m,x0+2,2,z0+7,3,'north','birch');rug(m,x0+2,z0+5,5,2)
    for xx in range(x0+3,x1-2,4):pendant(m,xx,6,z0+3,8)


def pool(m,x,z):
    m.box((x-2,1,z-2),(x+2,1,z+2),'cyan_terracotta')
    m.box((x-1,1,z-1),(x+1,1,z+1),'water[level=0]')


def merchant(variant):
    m=base('DS-05',variant,MERCHANT_PLANS,corner=(17,18) if variant==2 else None)
    if variant==1:
        trade_room(m,4,6,20,16);accounts(m,22,6,36,16)
        living(m,'living','家庭炊事与起居',(4,22),(18,34));sleeping(m,'bedroom','家庭双床房',(22,24),(36,34),two=True)
        for x,z,face in [(11,6,'north'),(16,16,'south'),(29,6,'north'),(22,12,'west'),(11,22,'north'),(29,24,'north')]:m.door(x,2,z,facing=face)
        pool(m,20,20);canvas(m,31,18,36,22,y=7)
        crate_stack(m,34,2,19,2,2,2);m.point('stock','work',(34,2,20),'庭侧周转货',approach=(33,2,20))
        m.room('court','公共水庭',(4,2,17),(36,6,23),'客货分流、遮阳和少量周转存货')
        dome(m,29,11,9,r=4);ventilation(m,24,27)
        entrance(m,'entry',11,4)
    elif variant==2:
        trade_room(m,4,5,15,16);living(m,'living','家庭起居',(4,21),(17,36))
        accounts(m,20,18,32,25);sleeping(m,'bedroom','后翼卧室',(20,25),(32,36),two=True)
        for x,z,face in [(10,5,'north'),(10,16,'south'),(10,21,'north'),(17,28,'east'),(20,21,'west'),(32,21,'east'),(26,25,'south'),(20,30,'west')]:m.door(x,2,z,facing=face)
        m.set(19,2,35,'moss_block');m.set(19,3,35,'flowering_azalea')
        crate_stack(m,30,2,23,2,1,2)
        m.point('stock','work',(30,2,23),'东门待交付货',approach=(29,2,23))
        m.room('court','转角生活窄庭',(16,2,18),(19,6,36),'连接临街铺面、账房和家庭后翼')
        dome(m,9,10,9,r=4);ventilation(m,6,26)
        entrance(m,'north',10,3);entrance(m,'east',34,21,facing='east',name='东侧接货口')
    elif variant==3:
        trade_room(m,4,6,15,18);living(m,'living','后庭起居',(4,22),(15,37))
        room_shell(m,33,6,44,18);sleeping(m,'bedroom','后庭双床房',(33,22),(44,37),two=True)
        accounts(m,20,18,28,28)
        for x,z,face in [(15,12,'east'),(15,30,'east'),(33,12,'west'),(33,30,'west'),(24,18,'north'),(24,28,'south')]:m.door(x,2,z,facing=face)
        crate_stack(m,36,2,8,3,3,3);crate_stack(m,39,2,14,3,3,2)
        m.point('stock','work',(36,2,10),'前庭小货仓',approach=(35,2,10))
        m.room('stock','贸易小货仓',(34,2,7),(43,6,17),'分类货箱、交付与搬运通道')
        pool(m,24,11);bench(m,17,2,15,3,'north','birch')
        canvas(m,18,31,30,37,y=7);table(m,21,2,34,7,'cyan')
        m.point('family_table','work',(24,2,34),'后庭家宴桌',approach=(24,2,33))
        arch_front(m,20,2,6,9,7,material='orange_terracotta')
        m.box((4,2,39),(44,3,39),'cut_sandstone')
        m.room('front_court','前庭会商',(16,2,7),(32,6,17),'接待、候坐与公共水庭')
        m.room('rear_court','后庭家宴',(16,2,29),(32,6,38),'家人起居和遮阳进餐')
        dome(m,24,23,9,r=4);ventilation(m,6,26)
        entrance(m,'entry',24,4)
    else:
        room_shell(m,4,6,26,30)
        m.box((5,2,16),(25,6,16),'white_terracotta');m.door(16,2,16,facing='south')
        table(m,7,2,11,10,'orange');crate_stack(m,6,2,24,4,4,3)
        m.point('sales','work',(11,2,11),'楼下商品接待',approach=(11,2,12))
        m.point('stock','work',(9,2,25),'后仓周转货',approach=(10,2,25))
        m.room('sales','楼下接待铺',(5,2,7),(25,6,15),'展示、议价与顾客等候')
        m.room('stock','后仓与楼梯',(5,2,17),(25,6,29),'货物搬运、暂存与内部上楼')
        living(m,'living','楼上起居',(4,6),(14,18),f=8)
        accounts(m,14,6,26,18,f=8);sleeping(m,'bedroom','露台卧室',(4,20),(14,30),f=8)
        for x,z,face in [(14,12,'east'),(22,18,'south'),(9,18,'south'),(14,25,'east')]:m.door(x,9,z,facing=face)
        m.box((22,8,20),(23,8,28),'air')
        for i in range(7):
            for x in (22,23):
                m.box((x,2,26-i),(x,2+i,26-i),'sandstone');m.set(x,2+i,26-i,'sandstone_stairs[facing=north]')
        m.box((22,8,18),(23,8,19),'birch_planks')
        railing(m,(21,9,20),(21,9,28),'birch','z');railing(m,(26,9,19),(26,9,30),'birch','z')
        railing(m,(15,9,30),(25,9,30),'birch','x')
        m.set(18,9,28,'moss_block');m.set(18,10,28,'flowering_azalea')
        bench(m,16,9,23,3,'north','birch');m.set(17,9,26,'water_cauldron[level=3]')
        m.point('terrace','circulation',(18,9,20),'上层露台',look_at=[18,10,25])
        m.room('terrace','楼上家用露台',(15,9,19),(25,13,29),'歇凉、取水和卧室出入')
        m.door(16,2,6,facing='north');window(m,(6,3,6),(12,5,6),color='cyan_stained_glass')
        window(m,(19,3,6),(24,5,6),color='cyan_stained_glass')
        ventilation(m,5,7,y=16);dome(m,20,12,16,r=4)
        m.meta.update(roof_min_y=15,floors=[dict(name='下层接待与存货',y=1,max_y=6),dict(name='上层家居与露台',y=8,max_y=13)])
        entrance(m,'entry',16,4)
    if variant==1:
        door_canopy(m,11,6,6);door_canopy(m,29,6,6)
        rug(m,11,27,6,4);bench(m,13,2,31,3,'north','birch');table(m,13,2,29,3,'blue')
        m.point('tea','work',(14,2,29),'家庭待客茶桌',approach=(14,2,28))
        m.set(27,2,20,'moss_block');m.set(27,3,20,'flowering_azalea')
    elif variant==2:
        door_canopy(m,10,6,5)
        rug(m,6,28,7,5);table(m,8,2,30,3,'blue');bench(m,8,2,32,3,'north','birch')
        m.point('tea','work',(9,2,30),'家用茶桌',approach=(9,2,29))
    elif variant==3:
        rug(m,6,29,7,5);table(m,8,2,31,3,'blue');bench(m,8,2,33,3,'north','birch')
        m.point('tea','work',(9,2,31),'后院茶桌',approach=(9,2,30))
        bench(m,21,2,36,7,'north','birch')
        for xx in (18,30):m.set(xx,2,28,'moss_block');m.set(xx,3,28,'flowering_azalea')
        textile_shelf(m,35,2,17,3)
    else:
        door_canopy(m,16,6,6,7);window(m,(6,10,6),(11,12,6),color='cyan_stained_glass')
        window(m,(17,10,6),(23,12,6),color='cyan_stained_glass')
        textile_shelf(m,6,2,15,6);bench(m,20,2,11,3,'south','birch');rug(m,19,8,5,5)
        for x in (7,10,13,16):m.set(x,3,11,'flower_pot')
        shelf(m,6,2,18,5,material='acacia');crate_stack(m,14,2,27,3,2,2)
        m.point('pack','work',(14,2,27),'周转货打包位',approach=(14,2,26))
        rug(m,6,12,6,4,y=9);shelf(m,5,9,13,2,material='birch',contents='bookshelf')
        for x,z in ((11,9),(18,21),(11,27)):pendant(m,x,6,z,8)
    for room in m.meta['rooms']:
        if room['id'] in ('living','bedroom'):
            x0,y0,z0=room['min'];x1,_,z1=room['max']
            pendant(m,(x0+x1)//2,y0+4,(z0+z1)//2,y0+6)
    return m


def kiln(m,key,x,z,*,f=1):
    """Inspectable vanilla furnace installation; this is not a mod machine."""
    m.box((x,f+1,z),(x+4,f+5,z+4),'bricks')
    m.box((x+1,f+2,z+1),(x+3,f+4,z+3),'air')
    for xx in range(x+1,x+4):m.set(xx,f+1,z,'furnace[facing=north,lit=true]')
    m.box((x+1,f+5,z+2),(x+3,f+14,z+4),'bricks')
    m.box((x+2,f+2,z+3),(x+2,f+14,z+3),'air')
    for xx in range(x+1,x+4):
        for zz in range(z+2,z+5):
            if (xx,zz)!=(x+2,z+3):m.set(xx,f+15,zz,'brick_slab[type=bottom]')
    m.set(x+2,f+2,z+5,'lever[face=wall,facing=south,powered=false]')
    m.point(key,'work',(x+2,f+1,z),'窑炉装料与出料',approach=(x+2,f+1,z-1))
    m.point(key+'_service','work',(x+2,f+2,z+5),'窑后检查与维护',approach=(x+2,f+1,z+6))
    m.meta['design_notes'].append('砖窑和贯通烟道为原版熔炉的建筑表达；标记操作与维护位置，不声明配方、热工或自动化生产。')


def glass_rack(m,key,x,y,z,width=5):
    colors=('cyan','light_blue','orange','lime','magenta','yellow')
    m.box((x,y,z),(x+width-1,y,z),'smooth_stone')
    for i in range(width):
        m.set(x+i,y+1,z,colors[i%len(colors)]+'_stained_glass')
        if i%2==0:m.set(x+i,y+2,z,colors[i%len(colors)]+'_stained_glass_pane[north=true,south=true]')
    m.point(key,'work',(x+width//2,y+1,z),'玻璃冷却陈列架',approach=(x+width//2,y,z-1))


def sand_store(m,key,x,z,*,f=1,width=3):
    m.box((x,f+1,z),(x+width-1,f+1,z+2),'sand')
    for xx in range(x,x+width):m.set(xx,f+2,z+1,'sand')
    m.box((x-1,f+1,z),(x-1,f+2,z+2),'cut_sandstone')
    m.box((x+width,f+1,z),(x+width,f+2,z+2),'cut_sandstone')
    m.box((x,f+1,z+3),(x+width-1,f+2,z+3),'cut_sandstone')
    m.point(key,'work',(x+width//2,f+1,z),'原砂备料',approach=(x+width//2,f+1,z-1))


def mould_table(m,key,x,y,z,width=5):
    table(m,x,y,z,width,'cyan')
    m.set(x,y,z,'crafting_table');m.set(x,y+1,z,'air')
    for xx in range(x+2,x+width,2):m.set(xx,y+1,z,'flower_pot')
    m.point(key,'work',(x,y,z),'模具与冷加工台',approach=(x,y,z-1))


def glassworks(variant):
    m=base('DS-07',variant,GLASS_PLANS,grade=variant==4)
    if variant==1:
        room_shell(m,3,6,25,24)
        kiln(m,'kiln',5,16);sand_store(m,'sand',5,8)
        mould_table(m,'mould',14,2,17,7);glass_rack(m,'cooling',14,2,22,7)
        m.door(13,2,6,facing='north');m.door(22,2,24,facing='south')
        window(m,(16,3,6),(22,5,6),color='cyan_stained_glass')
        m.room('hall','单窑加工厅',(4,2,7),(24,6,23),'原砂、窑炉、模具、冷却架与窑后维护通道')
        room_shell(m,3,26,12,33);m.door(7,2,26,facing='north')
        bench(m,5,2,31,4,'north','birch');m.set(10,2,29,'water_cauldron[level=3]');table(m,5,2,28,3,'blue')
        m.point('rest','work',(10,2,29),'工人饮水与休息',approach=(9,2,29))
        m.room('rest','后院休息间',(4,2,27),(11,6,32),'远离窑炉的饮水、坐席与餐桌')
        canvas(m,15,26,24,32,y=7);crate_stack(m,18,2,28,4,3,2)
        m.point('fuel','work',(18,2,28),'棚下燃料与包材',approach=(17,2,28))
        m.room('fuel','燃料棚',(15,2,26),(24,6,32),'后院接货与遮阳储料')
        entrance(m,'entry',13,4)
    elif variant==2:
        room_shell(m,30,6,38,17);m.door(30,2,12,facing='west');sand_store(m,'sand',32,8)
        crate_stack(m,34,2,14,3,2,2);m.point('fuel','work',(34,2,14),'独立原料库燃料',approach=(33,2,14))
        m.room('raw','原料库',(31,2,7),(37,6,16),'原砂与燃料分区暂存')
        canvas(m,5,7,13,13,y=7);canvas(m,18,7,26,13,y=7)
        kiln(m,'west_kiln',6,16);kiln(m,'east_kiln',19,16)
        m.room('kilns','露院双窑',(4,2,7),(27,6,23),'双窑前操作和后维护分开，遮阳候作区在前')
        room_shell(m,4,28,26,37);m.door(17,2,28,facing='north')
        mould_table(m,'mould',7,2,33,8);glass_rack(m,'cooling',19,2,33,6)
        window(m,(7,3,37),(14,5,37),color='cyan_stained_glass')
        m.room('finishing','加工冷却厅',(5,2,29),(25,6,36),'模具、整修、冷却与成品陈列')
        room_shell(m,31,27,38,37);m.door(31,2,31,facing='west')
        bench(m,33,2,34,3,'north','birch');m.set(36,2,29,'water_cauldron[level=3]')
        table(m,33,2,30,2,'blue');m.point('rest','work',(36,2,29),'歇工饮水',approach=(36,2,30))
        m.room('rest','独立休息室',(32,2,28),(37,6,36),'与窑炉保持距离的饮水和用餐')
        m.box((29,1,22),(31,1,24),'cyan_terracotta');m.set(30,1,23,'water[level=0]')
        entrance(m,'entry',20,4)
    elif variant==3:
        room_shell(m,4,6,28,34);room_shell(m,4,6,28,18,f=8)
        m.box((5,2,16),(27,6,16),'white_terracotta');window(m,(7,3,16),(19,5,16),color='cyan_stained_glass')
        m.door(24,2,16,facing='south')
        glass_rack(m,'sales',6,2,11,6);sand_store(m,'sand',17,29)
        mould_table(m,'mould',18,2,20,6);kiln(m,'kiln',6,22)
        m.room('sales','前铺展示接待',(5,2,7),(27,6,15),'顾客观样与交易，隔墙后为生产空间')
        m.room('workshop','后部窑炉工作厅',(5,2,17),(27,6,33),'窑炉、原料、加工与侧门装卸')
        m.box((25,8,24),(26,8,32),'air')
        for i in range(7):
            for x in (25,26):
                m.box((x,2,31-i),(x,2+i,31-i),'sandstone');m.set(x,2+i,31-i,'sandstone_stairs[facing=north]')
        m.box((25,8,23),(26,8,24),'birch_planks')
        railing(m,(24,9,25),(24,9,32),'birch','z');railing(m,(28,9,19),(28,9,34),'birch','z')
        railing(m,(5,9,34),(27,9,34),'birch','x');railing(m,(4,9,19),(4,9,34),'birch','z')
        m.box((20,9,7),(20,14,17),'white_terracotta');m.door(20,9,14,facing='east')
        m.door(17,9,18,facing='south');m.door(26,9,18,facing='south')
        glass_rack(m,'gallery',6,9,9,10);bench(m,7,9,14,6,'north','birch')
        m.room('gallery','楼上精品展厅',(5,9,7),(19,13,17),'采光陈列与坐席')
        m.bed(24,9,10,'cyan','north');m.point('sleep','sleep',(24,9,10),'值班休息床',approach=(23,9,10))
        m.set(26,9,16,'water_cauldron[level=3]');table(m,22,9,15,2,'blue')
        m.room('rest','职工值班室',(21,9,7),(27,13,17),'夜间看守、饮水和简单用餐')
        m.point('terrace','circulation',(21,9,23),'屋面连廊',look_at=[14,10,24])
        m.room('terrace','窑侧屋面连廊',(5,9,19),(27,13,33),'楼梯通往展厅与值班室，窑炉烟道保持独立')
        m.door(16,2,6,facing='north');m.door(28,2,28,facing='east')
        window(m,(6,10,6),(18,12,6),color='cyan_stained_glass')
        m.meta.update(roof_min_y=15,floors=[dict(name='接待与生产',y=1,max_y=6),dict(name='上层展示与值班',y=8,max_y=13)])
        entrance(m,'entry',16,4);entrance(m,'cargo',30,28,facing='east',name='侧巷卸货门')
    else:
        room_shell(m,4,6,14,16);m.door(14,2,12,facing='east');sand_store(m,'sand',6,8)
        m.room('raw','下台原料库',(5,2,7),(13,6,15),'由下台卸货面送入原砂和燃料')
        crate_stack(m,6,2,13,3,2,2);m.point('fuel','work',(6,2,13),'原料库燃料',approach=(5,2,13))
        room_shell(m,23,6,34,16);m.door(23,2,12,facing='west');glass_rack(m,'sales',25,2,10,7)
        m.room('sales','下台成品间',(24,2,7),(33,6,15),'顾客观样、成品打包与交付')
        room_shell(m,18,23,33,34,f=2);m.door(31,3,23,facing='north')
        mould_table(m,'mould',21,3,28,5);glass_rack(m,'cooling',26,3,32,6)
        bench(m,22,3,25,4,'north','birch');m.set(19,3,25,'water_cauldron[level=3]')
        m.point('rest','work',(19,3,25),'歇工饮水',approach=(20,3,25))
        m.room('finishing','上台加工间',(19,3,24),(32,7,33),'模具与冷加工、冷却架、饮水与歇坐')
        kiln(m,'kiln',5,24,f=2);canvas(m,5,20,14,22,y=8)
        m.room('kiln','上台露窑与遮阳',(4,3,20),(15,7,32),'窑前候作，后方保留维护通道')
        for x in range(16,21):m.set(x,2,20,'sandstone_stairs[facing=south]')
        m.meta.update(roof_min_y=8,floors=[dict(name='上下台工作地坪',y=1,max_y=7)])
        entrance(m,'entry',19,4)
    if variant==1:
        door_canopy(m,13,6,6,7)
        window(m,(25,3,10),(25,5,13),axis='z',color='cyan_stained_glass')
        shelf(m,15,2,8,7,material='acacia');m.set(12,2,15,'water_cauldron[level=3]')
        m.set(12,2,18,'anvil[facing=north]');m.point('tools','work',(12,2,18),'加工器具与水盆',approach=(12,2,17))
        for x,z in ((14,12),(18,20)):pendant(m,x,6,z,8)
    elif variant==2:
        for x,key in ((7,'west_tools'),(20,'east_tools')):
            table(m,x,2,10,4,'cyan');m.set(x,2,10,'smithing_table');m.set(x,3,10,'air')
            m.set(x+4,2,11,'water_cauldron[level=3]');m.point(key,'work',(x,2,10),'遮阳工具与模具台',approach=(x,2,9))
        shelf(m,7,2,36,7,material='acacia');shelf(m,20,2,36,4,material='acacia')
        for x in (10,21):pendant(m,x,6,31,8)
    elif variant==3:
        door_canopy(m,16,6,6,7);window(m,(6,3,6),(12,5,6),color='cyan_stained_glass')
        table(m,20,2,10,6,'blue');m.set(22,2,10,'lectern[facing=south]');m.set(22,3,10,'air')
        m.point('account','work',(22,2,10),'成品接待账台',approach=(22,2,11))
        bench(m,8,2,14,4,'north','birch');shelf(m,6,2,18,6,material='acacia')
        m.set(14,2,22,'water_cauldron[level=3]');m.set(14,2,25,'anvil[facing=north]')
        m.point('tools','work',(14,2,25),'冷整修器具',approach=(14,2,24))
        shelf(m,22,9,7,4,material='birch');rug(m,22,12,4,3,y=9,color='cyan')
        canvas(m,13,25,20,32,y=14,base_y=9)
        bench(m,15,9,29,3,'north','birch');m.set(18,9,27,'water_cauldron[level=3]')
        for x,y,z in ((16,6,12),(18,6,25),(12,13,12),(24,13,13)):pendant(m,x,y,z,y+2)
    else:
        door_canopy(m,19,6,6,5)
        # Freestanding entrance shade has its own posts; no wall at the central gate.
        for x in (17,21):m.box((x,2,4),(x,5,4),'acacia_log[axis=y]')
        window(m,(25,3,6),(31,5,6),color='cyan_stained_glass')
        table(m,26,2,14,5,'blue');m.set(27,2,14,'lectern[facing=north]');m.set(27,3,14,'air')
        m.point('account','work',(27,2,14),'下台成品登记',approach=(27,2,13))
        shelf(m,19,3,33,5,material='acacia');m.set(15,3,27,'water_cauldron[level=3]')
        m.set(15,3,29,'anvil[facing=north]');m.point('tools','work',(15,3,29),'窑侧加工器具',approach=(15,3,28))
        pendant(m,26,7,30,9)
    return m
