"""Airport warehouses and eight distinct everyday trades; static author assets."""
from functools import partial
from .cloud_navigation import base,platform,lodge,enter,desk,beds,kitchen,table,stairs_z
from .components import shelf,bench,column,hip_roof,crate_stack,pendant
from .samples import railing


def start(family,v,name,size):
    m=base(f'{family}-v{v:02d}',name,size,role='fill')
    m.meta['source']=f'tools/structure_studio/studio/cloud_market.py:{family}({v})'
    return m


def canopy(m,x0,z0,x1,z1,f=7):
    for x in (x0,x1):
        for z in (z0,z1):column(m,x,z,f+1,f+9,'stripped_dark_oak_log','dark_oak_planks')
    for z in (z0,z1):m.box((x0,f+8,z),(x1,f+8,z),'dark_oak_log[axis=x]')
    for x in (x0,x1):m.box((x,f+8,z0),(x,f+8,z1),'dark_oak_log[axis=z]')
    hip_roof(m,x0-1,x1+1,z0-1,z1+1,f+8,material='waxed_cut_copper',tiers=3)
    pendant(m,(x0+x1)//2,f+6,(z0+z1)//2,f+10)


def stair(m,x,z,rise=7,f=7):
    stairs_z(m,x,z,f+1,3,rise)
    for xx in (x-1,x+3):
        for i in range(rise):
            m.box((xx,f,z+i),(xx,f+1+i,z+i),'dark_oak_planks')
            m.box((xx,f+2+i,z+i),(xx,f+3+i,z+i),'dark_oak_fence[north=true,south=true,east=false,west=false]')


def crates(m,key,x,y,z,w=4,d=5,h=3):
    crate_stack(m,x,y,z,w,d,h)
    m.point(key,'storage',(x+1,y,z),'分区仓储货物',approach=(x+1,y,z-1))


def warehouse(v):
    names=['纵巷窄仓','叠层备件仓','双面转运院','分台装卸仓']
    sizes=[(33,34,49),(41,41,43),(53,35,45),(53,38,45)]
    m=start('CN-08',v,names[v-1]+' · 空港货仓',sizes[v-1]);w,_,d=m.size;platform(m,3,2,w-4,d-4)
    if v==1:
        lodge(m,7,7,25,39,h=8,gable=True);m.door(16,8,7,'birch','north');m.door(16,8,39,'birch','south')
        for key,x,z in [('a',9,11),('b',20,11),('c',9,23),('d',20,23)]:crates(m,key,x,8,z,4,7)
        desk(m,'ledger',9,8,34,'入出库账房',6);m.room('warehouse','中轴理货长仓',(8,8,8),(24,15,38),'四组存货、贯通中轴、前后装卸与后部账台')
        note='窄长岩沿单仓保持中央五格理货通道，货架分成四段，前后门对应接货与发货。'
    elif v==2:
        lodge(m,6,7,24,33);lodge(m,6,7,24,33,f=14);m.door(15,8,7,'birch','north');m.door(24,15,20,'birch','east')
        m.box((25,15,19),(25,18,21),'air')
        for level,y in [('low',8),('high',15)]:
            crates(m,level+'a',8,y,13,5,7);crates(m,level+'b',18,y,26,4,5)
            desk(m,level+'desk',8,y,28,'重货清点' if y==8 else '轻备件登记',7)
            m.room(level,'重货层' if y==8 else '轻备件层',(7,y,8),(23,y+5,32),'分区货位、账台、通路与独立楼层标记')
        stair(m,29,10);m.box((25,14,17),(32,14,24),'birch_planks')
        for z in (17,24):railing(m,(25,15,z),(32,15,z),'dark_oak','x')
        m.box((28,15,17),(31,18,17),'air');railing(m,(32,15,17),(32,15,24),'dark_oak','z')
        for x in (26,32):column(m,x,23,8,13,'stripped_dark_oak_log','dark_oak_planks')
        m.meta['floors']=[dict(name='地面重货',y=7,max_y=13),dict(name='楼上轻备件',y=14,max_y=20)]
        note='重货落在地面层、轻备件在上层，外置七级阶梯和带柱梁的接驳廊连到二层；不假定自动升降物流。'
    elif v==3:
        lodge(m,6,8,20,35,h=7);lodge(m,32,8,46,35,h=7)
        for x,face in ((20,'east'),(32,'west')):m.door(x,8,15,'birch',face);m.door(x,8,29,'birch',face)
        crates(m,'west',8,8,12,5,9);crates(m,'east',38,8,12,5,9)
        desk(m,'westdesk',8,8,29,'到港验货与回单',8);desk(m,'eastdesk',34,8,29,'发运分单与封箱',8,block='crafting_table')
        canopy(m,23,10,29,34);m.point('transfer','circulation',(26,8,23),'中央贯通转运廊',look_at=[26,10,34])
        for key,x in (('in',6),('out',32)):m.room(key,'到港仓' if key=='in' else '发运仓',(x+1,8,9),(x+13,14,34),'仓储、验货或封箱以及面向中廊的双门')
        note='东西双仓以带顶转运中廊相对，一边接收清点、一边封箱发货，四道侧门缩短搬运路线。'
    else:
        lodge(m,6,8,22,35,h=7);m.door(22,8,21,'birch','east');m.door(14,8,8,'birch','north')
        crates(m,'bulk',8,8,15,5,10);desk(m,'ledger',8,8,29,'地面补给账房',9)
        platform(m,33,8,48,38,11);lodge(m,35,11,46,33,f=11,h=6);m.door(35,12,23,'birch','west')
        crates(m,'staged',37,12,15,5,5);desk(m,'dispatch',37,12,28,'高台小件发运',7)
        stair(m,27,17,4);m.box((27,11,21),(34,11,27),'birch_planks')
        for z in (21,27):railing(m,(27,12,z),(34,12,z),'dark_oak','x')
        m.box((27,12,21),(29,15,21),'air');m.box((33,12,22),(33,15,26),'air')
        m.room('low','低台补给大仓',(7,8,9),(21,14,34),'大宗存货与账房');m.room('high','高台轻货发运间',(36,12,12),(45,17,32),'小件暂存与分单')
        m.meta['floors']=[dict(name='地面补给仓',y=7,max_y=14),dict(name='高台轻货仓',y=11,max_y=17)]
        note='低台大宗补给与高四格小件发运仓独立落墩，中间带栏短廊连接，适用于相邻双台地。'
    enter(m,(w-1)//2,3);m.meta['design_notes']=[note,'本体只提供理货空间、静态货箱与步行通路；空中运输接口必须由泊塔另行衔接，未实现物流或升降。'];m.meta['differences']=[note]
    return m


def shop_counter(m,key,x,y,z,name,width=4,block='cartography_table'):
    desk(m,key,x,y,z,name,width,block)
    for xx in range(x,x+width):
        if xx!=x+1:m.set(xx,y,z,'dark_oak_planks')


def shop_display(m,key,x,z,w=4,material='barrel[facing=south]',f=7,side=1):
    # Shop-only fitted display: raised merchandise, end panels and reachable frontage.
    m.box((x,f+1,z),(x+w-1,f+1,z),'dark_oak_planks')
    for dx in range(w):m.set(x+dx,f+2,z,material)
    m.box((x,f+3,z),(x+w-1,f+3,z),'dark_oak_slab[type=bottom]')
    m.point(key,'storage',(x+1,f+2,z),'分类商品与成品陈列',approach=(x+1,f+1,z+side))


def shop_seats(m,x,z,w=4,f=7):
    # Completes the existing dining table's opposite side without filling aisles.
    for dx in range(0,w,2):m.set(x+dx,f+1,z-2,'dark_oak_stairs[facing=south]')


def shop_sink(m,x,z,f=7):
    for dx,b in enumerate(['smoker[facing=south]','crafting_table','water_cauldron[level=3]','dark_oak_planks','barrel[facing=south]']):m.set(x+dx,f+1,z,b)
    m.set(x+3,f+2,z,'flower_pot')


def shop_bedside(m,x,z,count=2,f=7):
    for i in range(count):
        xx=x+i*3
        m.set(xx-1,f+1,z-1,'barrel[facing=east]');m.set(xx-1,f+2,z-1,'lantern[hanging=false]')
        m.box((xx-1,f+1,z-2),(xx-1,f+3,z-2),'dark_oak_planks')


def shop_detail(m,v):
    if v==1:
        shop_display(m,'bread',9,8,3,'composter[level=0]')
        # Cooling racks, flour sacks and a continuous wet/dry preparation run.
        shop_display(m,'flour',15,17,3)
        m.box((17,8,21),(18,8,26),'dark_oak_planks')
        for z in (21,23,25):m.set(18,9,z,'barrel[facing=west]')
        m.box((17,8,24),(18,10,24),'air')
        m.set(10,8,27,'crafting_table');m.set(12,8,27,'dark_oak_planks')
        m.set(9,9,20,'flower_pot');m.set(13,9,20,'flower_pot')
        m.point('cooling','work',(18,9,23),'出炉冷却、装袋与食品周转',approach=(16,8,23))
        m.room('bake_work','揉面—烘烤—冷却后场',(8,8,18),(18,13,28),'面粉备料、湿操作台、炉灶、冷却架和装袋，东侧门留净')
        m.set(9,8,10,'dark_oak_stairs[facing=south]');m.set(17,8,10,'dark_oak_stairs[facing=south]')
    elif v==2:
        shop_display(m,'cloth',8,8,7,'white_wool')
        for z,c in ((16,'cyan_wool'),(20,'light_blue_wool'),(27,'white_wool')):
            m.box((7,8,z),(7,10,z+1),c)
        # Tailoring bench, folded cloth and completed repairs distinguish the short wing.
        m.box((27,8,13),(35,8,13),'dark_oak_planks')
        for x in (27,31,35):m.set(x,9,13,'white_carpet')
        shop_display(m,'finished_sail',28,17,6,'light_blue_wool')
        m.set(18,8,28,'barrel[facing=west]');m.set(18,9,28,'lantern[hanging=false]')
        m.point('fit','work',(18,8,27),'成衣核尺与交付',approach=(17,8,27))
        m.room('materials','布料色样与验收',(7,8,8),(19,12,16),'前端整卷布色样、接单柜台及清楚的顾客通路')
        m.set(28,8,27,'dark_oak_slab[type=top]');m.set(28,9,27,'lantern[hanging=false]')
    elif v==3:
        shop_display(m,'stock',20,10,6,'barrel[facing=south]')
        m.set(21,9,12,'anvil[facing=east]');m.set(24,9,12,'grindstone[face=floor,facing=south]')
        m.box((20,8,12),(25,8,12),'dark_oak_planks')
        m.point('sample_tools','work',(21,9,12),'成套器具陈列与规格核对',approach=(21,8,13))
        m.box((10,8,26),(25,8,26),'dark_oak_planks')
        for x in (10,14,20,25):m.set(x,9,26,'barrel[facing=north]')
        for x in (10,16,22):
            m.box((x,8,21),(x+2,8,21),'dark_oak_planks')
            m.set(x+1,9,21,'heavy_weighted_pressure_plate')
        m.point('repair_bench','work',(17,8,21),'拆件、检量与装配工作台',approach=(17,8,20))
        shop_display(m,'parts',10,28,15,side=-1)
        m.room('sales','工具样品与交易前厅',(9,8,8),(27,14,16),'北墙分类器具架、双组样品与维修登记柜台')
        m.room('repair','拆检—磨修—装配作业区',(9,8,20),(27,14,28),'修理台、铁砧磨轮石切设备、零件和待交付柜')
    elif v==4:
        shop_display(m,'charts',8,8,5,'bookshelf')
        shop_display(m,'archive',16,17,5,'bookshelf')
        m.set(9,9,12,'lectern[facing=south]');m.set(16,9,12,'lantern[hanging=false]')
        shop_seats(m,9,23,9)
        m.box((21,8,25),(21,9,29),'bookshelf')
        m.point('reference','work',(21,9,27),'航线索引及订图记录',approach=(20,8,27))
        shop_sink(m,32,13);shop_seats(m,32,20,6)
        shop_bedside(m,32,31,3)
        m.box((31,8,26),(40,10,26),'white_terracotta');m.door(35,8,26,'birch','south')
        m.set(39,8,23,'dark_oak_stairs[facing=west]');m.box((40,8,23),(40,9,24),'bookshelf')
        m.point('home_read','work',(40,9,24),'店主坐读与生活书柜',approach=(39,8,24))
        m.room('home_sleep','店主独立寝间',(31,8,27),(40,13,32),'三床、个人灯柜与隔门，业务访客无需穿寝间')
    elif v==5:
        shop_display(m,'dry_stock',9,8,6)
        shop_display(m,'fresh_stock',21,8,6,'composter[level=0]')
        # Two short central shelves flank the main north-south customer lane.
        shop_display(m,'household',9,19,5)
        shop_display(m,'seasonal',23,19,4,'hay_block')
        m.box((22,8,32),(27,8,32),'dark_oak_planks');m.set(24,8,32,'water_cauldron[level=3]')
        for x in (21,25):m.set(x,9,29,'flower_pot')
        m.point('wash','work',(24,8,32),'补货清洗、验货与包装回收',approach=(24,8,33))
        m.room('front_stock','分组日用商品陈列',(8,8,8),(28,14,24),'干粮、根蔬、季节补给与日用品分架，保留中央通路')
    elif v==6:
        shop_display(m,'rope_samples',8,8,9)
        m.box((7,8,16),(19,11,16),'white_terracotta');m.door(13,8,16,'birch','south')
        shop_sink(m,8,17);m.point('cook','work',(9,8,17),'轮值厨房操作台',approach=(9,8,18))
        m.box((15,8,20),(18,8,20),'dark_oak_slab[type=top]');shop_seats(m,15,20,4)
        for x in (15,17):m.set(x,8,22,'dark_oak_stairs[facing=north]')
        shop_bedside(m,9,23,2)
        m.box((18,8,24),(19,9,25),'bookshelf');m.set(16,8,25,'dark_oak_stairs[facing=east]')
        m.point('rest_read','work',(18,9,24),'轮值坐读与用品柜',approach=(17,8,24))
        m.room('living','隔门轮值起居寝室',(7,8,17),(19,13,26),'厨房操作台、四座小餐桌、双床灯柜及坐读，独立东门')
        for z in (15,29):
            crates(m,'raw_'+str(z),27,8,z,2,3,2)
            m.box((36,8,z),(36,9,z+2),'barrel[facing=west]')
        m.point('rope_bins','storage',(36,9,30),'成品缆索与接头分类箱',approach=(35,8,30))
    elif v==7:
        shop_sink(m,8,8);shop_display(m,'tea_stock',16,9,5)
        m.box((7,8,12),(10,8,12),'dark_oak_planks');m.set(8,8,12,'water_cauldron[level=3]')
        m.point('wash','work',(8,8,12),'茶具回收清洗台',approach=(8,8,13))
        shop_seats(m,9,23,8)
        m.set(20,8,25,'barrel[facing=west]');m.set(20,9,25,'lantern[hanging=false]')
        shop_seats(m,34,20,6,f=11)
        shop_display(m,'terrace_service',35,14,5,'barrel[facing=south]',f=11)
        m.room('tea_prep','洗涤与备茶操作区',(7,8,8),(21,13,16),'炉灶、洗台、原料柜、茶具回收与冲泡备料')
    else:
        shop_display(m,'dried',8,8,5)
        shop_display(m,'ingredients',15,17,5)
        m.box((7,8,20),(9,8,23),'dark_oak_planks');m.set(8,9,21,'flower_pot')
        m.point('sort','work',(8,8,21),'药材筛选与批次整理',approach=(10,8,21))
        shop_seats(m,12,24,6)
        m.set(32,8,17,'dark_oak_stairs[facing=north]');m.set(34,8,17,'dark_oak_stairs[facing=north]')
        shop_display(m,'records_store',29,19,6,side=-1)
        m.box((28,8,10),(28,9,12),'bookshelf')
        m.room('sorting','清洗与分类配料后场',(7,8,19),(20,13,31),'筛选台、水盆、配料长桌、分类存药柜')


def shop(v):
    names=['窄巷烘焙铺','折庭帆衣铺','双面修具铺','航图商住院','双柜食杂铺','长场缆索铺','高台茶食铺','小庭药材铺']
    sizes=[(27,31,37),(43,33,39),(37,33,37),(47,35,43),(37,34,43),(45,35,49),(49,37,41),(43,33,43)]
    m=start('CN-F01',v,names[v-1]+' · 空港日常小商铺',sizes[v-1]);w,_,d=m.size;platform(m,3,2,w-4,d-4)
    if v==1:
        lodge(m,7,7,19,29,gable=True);m.door(13,8,7,'birch','north');m.door(19,8,24,'birch','east')
        shop_counter(m,'sale',9,8,12,'面包售卖与称量',7,block='barrel[facing=south]');kitchen(m,'oven',9,8,27)
        shop_counter(m,'dough',9,8,20,'整形与备料',6,block='crafting_table');shelf(m,8,8,17,4,'birch');bench(m,9,8,32,6,'north','birch')
        m.room('bakery','前店后炉的窄铺',(8,8,8),(18,13,28),'前柜销售、中段揉面、后段炉灶与饮水、外侧短候座')
        note='狭长烘焙铺依次分成售卖、备料、炉灶三段，厨房另有后侧出入口。'
    elif v==2:
        lodge(m,6,7,20,31);lodge(m,25,7,37,20);m.door(20,8,18,'birch','east');m.door(25,8,16,'birch','west')
        shop_counter(m,'counter',8,8,12,'帆衣量裁接单',8);shop_counter(m,'cutting',8,8,23,'长布裁剪台',9,block='loom[facing=south]');shelf(m,7,8,30,9,'birch')
        for x in (27,31,35):m.set(x,8,11,'loom[facing=south]')
        m.point('sew','work',(31,8,11),'织补与帆缝',approach=(31,8,12));shelf(m,26,8,19,8,'birch')
        m.room('cut','量裁与接单翼',(7,8,8),(19,13,30),'面料柜、裁台和接单');m.room('sew','独立织补短翼',(26,8,8),(36,13,19),'三部织机与缝补用品')
        canopy(m,25,25,37,32);bench(m,27,8,30,7,'north','birch');note='L形帆衣铺将接单裁台与织补短翼分开，内庭设带顶等候位，适合平台转角。'
    elif v==3:
        lodge(m,8,7,28,29,h=7);m.door(8,8,18,'birch','west');m.door(28,8,18,'birch','east')
        shop_counter(m,'sale',10,8,12,'工具销售与维修登记',7,block='smithing_table');shelf(m,9,8,8,13,'birch')
        for x,block in ((11,'anvil[facing=north]'),(17,'grindstone[face=floor,facing=south]'),(23,'stonecutter[facing=south]')):m.set(x,8,24,block);m.point('tool_'+str(x),'work',(x,8,24),'修整工具位',approach=(x,8,23))
        m.room('tool','双面修具厅',(9,8,8),(27,14,28),'北侧销售、南侧三类维修，中段横向双门通路')
        note='宽厅双侧入口保持中部横向通路，北侧工具销售和南侧铁砧磨轮石切台相对。'
    elif v==4:
        lodge(m,6,7,23,33);lodge(m,30,12,41,33);m.door(14,8,7,'birch','north');m.door(23,8,23,'birch','east');m.door(30,8,23,'birch','west')
        shop_counter(m,'map',8,8,12,'航图绘制与售卖',11);shelf(m,7,8,32,12,'birch','bookshelf');table(m,9,8,23,9)
        beds(m,'home',32,8,31,3);kitchen(m,'cook',32,8,13);table(m,32,8,20,6)
        m.room('maps','航图阅览与绘制',(7,8,8),(22,13,32),'绘图柜台、查阅桌和地图书柜');m.room('home','店主生活翼',(31,8,13),(40,13,32),'三床、备餐、饮水及小桌')
        bench(m,25,8,35,12,'north','birch');note='西侧航图店与东侧店主生活房围成安静小院，业务与睡眠分门，后部共用短廊。'
    elif v==5:
        lodge(m,7,7,29,35,h=7);m.door(18,8,7,'birch','north');m.door(29,8,29,'birch','east')
        shop_counter(m,'dry',9,8,14,'干粮与杂货柜',8,block='barrel[facing=south]');shop_counter(m,'fresh',20,8,14,'根蔬与饮水柜',7,block='composter[level=0]')
        for x in (9,23):shelf(m,x,8,23,5,'birch')
        crates(m,'store',9,8,29,6,4,2);shop_counter(m,'pack',21,8,29,'分装与库存核对',6,block='crafting_table')
        m.room('grocery','双柜食杂长店',(8,8,8),(28,14,34),'前双柜、中货架、后分装与补货侧门')
        note='双柜食品杂货铺保留中央进深通路，后场分装与收货，适合较大的日常补给点。'
    elif v==6:
        lodge(m,6,7,20,27);m.door(20,8,18,'birch','east');m.door(13,8,7,'birch','north')
        shop_counter(m,'order',8,8,12,'缆索规格与售后',8,block='crafting_table');shelf(m,7,8,26,9,'birch');beds(m,'rest',9,8,23,2)
        canopy(m,26,9,37,40)
        for z in (12,35):
            for x in (28,35):column(m,x,z,8,11,'stripped_dark_oak_log','dark_oak_planks')
            m.box((28,11,z),(35,11,z),'dark_oak_log[axis=x]')
        for x in (29,31,34):m.box((x,10,12),(x,10,35),'chain[axis=z]')
        shop_counter(m,'splice',27,8,38,'接头编结与检验',8,block='crafting_table');m.point('rope','work',(29,10,23),'静态绳索展架',approach=(27,8,23))
        m.room('office','缆索接单与轮值房',(7,8,8),(19,13,26),'接单、样件、双床轮值');m.room('yard','长线缆索作业棚',(27,8,10),(36,14,39),'长线展架、端架与编结检验桌')
        note='小型接单生活房与长形有顶拉索场并列，链条只代指静态展样，侧边留完整检验通道。'
    elif v==7:
        lodge(m,6,7,22,29);m.door(14,8,7,'birch','north');m.door(22,8,20,'birch','east');kitchen(m,'cook',8,8,8);shop_counter(m,'tea',8,8,17,'茶食点单与冲泡',10,block='brewing_stand');table(m,9,8,23,8)
        platform(m,31,8,44,34,11);canopy(m,33,12,42,29,f=11);table(m,34,12,20,6)
        stair(m,25,15,4);m.box((25,11,19),(32,11,25),'birch_planks')
        for z in (19,25):railing(m,(25,12,z),(32,12,z),'dark_oak','x')
        m.box((25,12,19),(27,15,19),'air');m.box((31,12,20),(31,15,24),'air')
        m.point('terrace','circulation',(37,12,25),'高台观谷茶席',look_at=[37,14,30]);m.room('tea','低台备餐与茶室',(7,8,8),(21,13,28),'厨房、点单与共餐');m.room('terrace','高台带顶茶席',(34,12,13),(41,18,28),'六格共餐桌、观谷与带栏阶道')
        m.meta['floors']=[dict(name='低台备餐茶室',y=7,max_y=13),dict(name='高台观景茶席',y=11,max_y=18)]
        note='低台封闭茶食房与高四格的观谷茶棚分工，步梯和带栏短廊连接，两个台地各有实体落墩。'
    else:
        lodge(m,6,7,21,33);lodge(m,27,7,37,20);m.door(14,8,7,'birch','north');m.door(21,8,22,'birch','east');m.door(27,8,16,'birch','west')
        shop_counter(m,'herbs',8,8,12,'药材称量与配料',9,block='brewing_stand');shelf(m,7,8,32,10,'birch');m.set(8,8,24,'water_cauldron[level=3]');table(m,12,8,24,6)
        shelf(m,28,8,8,7,'birch','bookshelf');shop_counter(m,'consult',29,8,14,'用材记录与咨询',6,block='lectern[facing=south]')
        m.box((27,7,25),(37,7,34),'stone_bricks')
        for x,z,flower in ((29,27,'potted_fern'),(33,27,'potted_dandelion'),(29,31,'potted_poppy'),(33,31,'potted_azure_bluet')):m.set(x,8,z,'birch_planks');m.set(x,9,z,flower)
        m.point('samples','work',(29,9,27),'非生产性的植物留样',approach=(29,8,26));m.room('herbs','药材配料长翼',(7,8,8),(20,13,32),'称量、用材柜、清洗和配料桌');m.room('records','咨询记录短翼',(28,8,8),(36,13,19),'用材参考书和咨询桌')
        note='药材配料与咨询记录分为两翼，内庭展示四类盆栽样本；不宣称新增药材作物或治疗机制。'
    shop_detail(m,v)
    enter(m,(w-1)//2,3);m.meta['design_notes']=[note,'店铺按居民与空港服务需求选用，门前平台必须接连续步道；陈设不自动产生交易、生产或治疗功能。'];m.meta['differences']=[note]
    return m


BUILDERS={**{f'CN-08-v{v:02d}':partial(warehouse,v) for v in range(1,5)},**{f'CN-F01-v{v:02d}':partial(shop,v) for v in range(1,9)}}
