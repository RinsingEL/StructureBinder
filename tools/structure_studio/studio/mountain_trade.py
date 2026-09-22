"""Gem working, mineral storage, hospitality and mountain daily commerce."""
from functools import partial
from .mountain_forge import base,hall,front,room,use,desk,kitchen,bunks,table,WALL,FLOOR
from .mountain_life import stair as life_stair,partition
from .components import window,column,bench,pendant,shelf,crate_stack

def describe(m,note,site=None):
    m.meta.update(source='tools/structure_studio/studio/mountain_trade.py:BUILDERS',design_notes=[note],differences=[note])
    if site:m.meta['terrain']['选址']=site
    for area in m.meta['rooms']:
        lo,hi=area['min'],area['max']
        names=[p['name'] for p in m.meta['points'] if p.get('approach') and all(lo[i]<=p['pos'][i]<=hi[i] for i in range(3))]
        if names:area['purpose']+='；具体工位：'+'、'.join(names)
    return m

def stair(m,x,z,y,rise=6,width=2):
    top=life_stair(m,x,z,y,rise,width)
    # Explicit fence states do not acquire their missing corner arm on export.
    m.set(x-1,top,z-1,'spruce_fence[east=true,west=false,north=false,south=true,waterlogged=false]')
    m.set(x+width,top,z-1,'spruce_fence[east=false,west=true,north=false,south=true,waterlogged=false]')
    return top

def canopy(m,x0,z0,x1,z1,y=2,roofy=7):
    for x in (x0,x1):
        for z in (z0,z1):column(m,x,z,y,roofy-1,'stripped_spruce_log','waxed_cut_copper')
    m.box((x0-1,roofy,z0-1),(x1+1,roofy,z1+1),'spruce_slab[type=bottom,waterlogged=false]')
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],roofy)
    pendant(m,(x0+x1)//2,roofy-1,(z0+z1)//2,roofy)

def cutbench(m,x,y,z,key):
    m.set(x,y,z,'stonecutter[facing=south]');m.set(x+2,y,z,'grindstone[face=floor,facing=south]');m.set(x+4,y,z,'water_cauldron[level=3]')
    use(m,key,'work',x,y,z,'宝石切磨与水洗',x,z+1)
def gems(m,x,y,z,key):
    for i,gem in enumerate(['amethyst_block','diamond_block','emerald_block']):
        m.set(x+2*i,y,z,'chiseled_deepslate');m.set(x+2*i,y+1,z,gem);m.set(x+2*i,y+2,z,'glass')
    use(m,key,'work',x+2,y+1,z,'成品陈列与检视',x+2,z+1,ay=y)

def worktop(m,x,y,z,length,key,label,material='spruce_planks',sample=None):
    """A usable continuous work surface, with a clear south-side approach."""
    m.box((x,y,z),(x+length-1,y,z),material)
    if sample:m.set(x,y+1,z,sample)
    m.set(x+length-1,y+1,z,'lantern[hanging=false,waterlogged=false]')
    use(m,key,'work',x+length//2,y,z,label,x+length//2,z+1)

def stock(m,x,y,z,length,key,label,contents='barrel',az=None):
    shelf(m,x,y,z,length,contents=contents)
    use(m,key,'storage',x,y+1,z,label,x,(z+1 if z==4 else z-1) if az is None else az,ay=y)

def gemworks(v):
    names=['临街筛磨宝石铺','院内双台切磨坊','窄巷宝石鉴定店','阶台原石工坊']
    w,d=[(25,27),(31,29),(19,27),(29,29)][v-1]
    m=base(f'MF-06-v{v:02}',names[v-1],w,d,23,role='fill')
    if v==1:
        hall(m,3,3,21,23,roof='gable');front(m,12)
        partition(m,4,13,20,13,(12,13));desk(m,5,2,6,'sale','接待与成品交付');gems(m,13,2,7,'display')
        cutbench(m,5,2,17,'cut');shelf(m,13,2,21,6);crate_stack(m,18,2,16,2,2,2)
        worktop(m,5,2,10,5,'inspection','铺毡验货台与当面交割',sample='white_carpet')
        bench(m,14,2,11,5,'north');stock(m,5,2,4,5,'sales_samples','分级样品与交易文书',contents='bookshelf')
        worktop(m,12,2,16,4,'sort','原石分级与称检',material='polished_andesite',sample='amethyst_cluster[facing=up,waterlogged=false]')
        worktop(m,5,2,21,5,'setting','成品镶配与包装',sample='flower_pot')
        m.set(20,2,21,'ender_chest[facing=north,waterlogged=false]');use(m,'secure','storage',20,2,21,'待交付贵重成品柜',20,20)
        room(m,'store','前店交易与陈列',4,4,20,12,'顾客接待、成品检视和交易');room(m,'work','后场筛磨保管',4,14,20,22,'原石分拣、切磨、水洗和成品保管')
        note='宽面前店经中门进入后场；成品交易与切磨噪声分开，后场原石堆与成品架分边。'
    elif v==2:
        hall(m,3,3,26,11,roof='hip');front(m,15);m.box((13,2,11),(16,4,11),'air')
        desk(m,5,2,5,'sale','验货登记');gems(m,18,2,6,'display')
        canopy(m,4,15,25,24);cutbench(m,6,2,17,'cut1');cutbench(m,18,2,21,'cut2')
        crate_stack(m,6,2,23,4,2,2);shelf(m,18,2,15,6)
        worktop(m,5,2,9,5,'inspection','来料检视与交易清点',sample='white_carpet');bench(m,18,2,9,5,'north')
        worktop(m,12,2,22,4,'sorting','双台之间原石分级台',material='polished_andesite',sample='amethyst_cluster[facing=up,waterlogged=false]')
        stock(m,6,2,14,5,'blank','待切原石与水洗器具');worktop(m,18,2,25,6,'packing','干区成品装匣与封签',sample='flower_pot')
        room(m,'shop','前部验货厅',4,4,25,10,'登记、样品与客商会面');room(m,'yard','双台切磨院',5,16,24,24,'两组切磨、水洗与分类储料，院门无需经过顾客柜台')
        note='横向验货厅后接大开间切磨雨棚，双台相错，半室外装卸与封闭顾客厅明确分开。'
    elif v==3:
        hall(m,3,3,15,23,roof='gable');front(m,9)
        desk(m,5,2,6,'assess','鉴定与报价');gems(m,5,2,11,'display');partition(m,4,16,14,16,(10,16))
        m.set(5,2,19,'grindstone[face=floor,facing=east]');use(m,'polish','work',5,2,19,'少量复磨',6,19)
        shelf(m,8,2,21,6);m.set(13,2,18,'ender_chest[facing=west,waterlogged=false]');use(m,'safe','storage',13,2,18,'样品保管',12,18)
        bench(m,10,2,6,3,'south');worktop(m,5,2,14,4,'inspection','独立铺毡精检与当面复核',sample='white_carpet')
        stock(m,5,2,4,3,'records','估价档案与对照图册',contents='bookshelf')
        worktop(m,8,2,18,3,'packing','样品编号封装台',sample='flower_pot')
        room(m,'assess','鉴定陈列室',4,4,14,15,'估价书写、三种样品与候客');room(m,'secure','后部样品小库',4,17,14,22,'成品保管、少量复磨和材料架')
        note='狭长一对一鉴定店，前部书写陈列，后小库隔门储存；仅一处复磨台，区别于批量切磨坊。'
    else:
        m.box((2,0,14),(26,2,26),'stone_bricks');m.box((13,2,14),(15,2,14),'stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]')
        hall(m,3,3,25,11,roof='hip');front(m,14);m.box((13,2,11),(15,4,11),'air')
        desk(m,5,2,5,'sale','低街样品登记');gems(m,17,2,6,'display')
        hall(m,3,17,25,25,y=2,height=5,roof='flat');front(m,14,17,3);cutbench(m,5,3,20,'cut');shelf(m,17,3,23,6)
        worktop(m,5,2,9,6,'inspection','街面验货与交付长台',sample='white_carpet');bench(m,17,2,9,5,'north')
        stock(m,5,2,7,5,'records','原石批次与交易账簿',contents='bookshelf')
        worktop(m,16,3,19,6,'sort','高台原石分拣与编号',material='polished_andesite',sample='amethyst_cluster[facing=up,waterlogged=false]')
        worktop(m,5,3,23,6,'packing','干燥镶配与成品包装',sample='flower_pot')
        room(m,'sale','低街展售厅',4,4,24,10,'样品接待与成品交付');room(m,'work','高台切磨室',4,18,24,24,'原石、水洗切磨、成品储架',3,7)
        m.meta.update(floors=[dict(name='低展售与高作坊',y=1,max_y=6)],preview_context=dict(kind='slope',land_surface_y=2,padding=5,run=14,rise=1,slope_origin_z=14))
        m.meta['terrain']['高程']='前店脚底 Y=2，后坊脚底 Y=3；一格向 +Z 上升，三宽短阶联系。'
        note='前低展售、后高切磨两屋夹阶院，顺坡分开顾客与原石加工；高台基础闭合。'
    return describe(m,note,'邻近矿料路线且具有干燥安全保管条件；切磨声与扬尘需和生活门面分开。')

def ore_stack(m,x,y,z,ore='raw_iron_block',w=3,d=3):
    m.box((x,y,z),(x+w-1,y,z+d-1),ore)
    if w>1 and d>1:m.box((x,y+1,z),(x+w-2,y+1,z+d-2),ore)

def warehouse(v):
    names=['小车紧凑矿仓','四格分类矿仓','顺坡双面装卸仓','长棚周转与账房']
    w,d=[(23,26),(31,31),(29,31),(35,25)][v-1]
    m=base(f'MF-07-v{v:02}',names[v-1],w,d,23,role='fill')
    if v==1:
        hall(m,3,3,19,22,roof='gable');front(m,11,wide=True)
        for x,ore in ((5,'raw_iron_block'),(15,'coal_block')):
            for z in (7,14):ore_stack(m,x,2,z,ore,3,3)
        desk(m,8,2,20,'ledger','后端库存账台');shelf(m,4,2,20,3)
        use(m,'iron','storage',5,2,7,'铁料货格',8,7);use(m,'coal','storage',15,2,14,'燃料货格',14,14)
        worktop(m,5,2,4,4,'receiving','门侧到货抽检与称记',material='polished_andesite')
        stock(m,14,2,20,4,'dispatch','已核验出库器材');m.set(17,2,4,'composter[level=0]')
        m.box((10,1,5),(12,1,18),'stone_bricks');m.point('haul','circulation',(11,2,17),'中央小车回转及出库道',look_at=[11,2,4])
        room(m,'warehouse','双列紧凑仓',4,4,18,21,'双边分类矿料、中央车道、后账台')
        note='窄门面双列堆料围绕贯通中道，后端账台与空桶架；两类矿料保持分堆。'
    elif v==2:
        hall(m,3,3,27,27,roof='hip');front(m,15,wide=True)
        for x in (12,18):m.box((x,2,4),(x,5,26),WALL)
        for z in (12,18):
            m.box((4,2,z),(11,5,z),WALL);m.box((19,2,z),(26,5,z),WALL)
        for x in (12,18):
            for z in (8,22):m.box((x,2,z-1),(x,4,z+1),'air')
        m.box((12,2,14),(18,5,16),'air')
        for x,z,ore in ((5,5,'coal_block'),(21,5,'raw_iron_block'),(5,21,'raw_copper_block'),(21,21,'raw_gold_block')):
            ore_stack(m,x,2,z,ore,4,4);use(m,f'pile{x}_{z}','storage',x,2,z,'分类矿料',x,z+4)
        desk(m,4,2,15,'ledger','横向验收账台');crate_stack(m,23,2,14,3,3,2)
        worktop(m,4,2,10,5,'coal_check','燃料批次取样与检验',material='polished_andesite')
        worktop(m,20,2,10,5,'iron_check','铁料验级工作台',material='polished_andesite')
        stock(m,5,2,26,5,'copper_dispatch','铜料已封签小批出库');stock(m,21,2,26,5,'gold_dispatch','金料保管与封签器具')
        room(m,'bays','四格矿料间',4,4,26,26,'四类矿料各有防混隔墙和宽口');room(m,'cross','十字验收与搬运道',13,4,17,26,'纵向主道与横向验收分流')
        note='四座防混料仓围出十字车道，煤铁铜金四类分区；横向账台不占纵向主运道。'
    elif v==3:
        m.box((2,0,14),(26,2,28),'stone_bricks');m.box((12,2,14),(15,2,14),'stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]')
        hall(m,3,3,11,11,roof='hip');front(m,7);desk(m,4,2,6,'ledger','低街收货账房')
        hall(m,3,18,25,27,y=2,height=5,roof='flat');front(m,14,18,3,wide=True)
        for x,ore in ((5,'raw_iron_block'),(19,'raw_copper_block')):ore_stack(m,x,3,21,ore,4,4);use(m,f'ore{x}','storage',x,3,21,'高台矿料堆',x+4,21)
        canopy(m,15,4,25,11);crate_stack(m,17,2,6,5,3,2)
        stock(m,4,2,10,5,'records','收货账簿与封签',contents='bookshelf')
        worktop(m,17,2,10,6,'receiving','棚下到货抽检与登记',material='polished_andesite')
        worktop(m,10,3,25,7,'dispatch','高仓出库分装检验台',material='polished_andesite')
        room(m,'ledger','低位收货账房',4,4,10,10,'登记验收');room(m,'dock','前部低街卸货棚',16,5,24,10,'散货短时周转');room(m,'store','高街保管仓',4,19,24,26,'高台分堆与中间宽车道',3,7)
        m.meta.update(floors=[dict(name='低街与高仓',y=1,max_y=6)],preview_context=dict(kind='slope',land_surface_y=2,padding=5,run=14,rise=1,slope_origin_z=14))
        m.meta['terrain']['高程']='低街脚底 Y=2、高仓 Y=3；+Z 单格坡差，四宽短阶供人员，不代表矿车能过台阶。'
        note='低位收货账房与开放周转棚并置，后高台独立保管仓；明确低街卸货和高街存放两面。'
    else:
        hall(m,25,3,31,21,roof='hip');front(m,28);desk(m,26,2,6,'ledger','库存账房');shelf(m,26,2,19,4,contents='bookshelf')
        canopy(m,4,4,21,20);m.door(25,2,13,facing='west')
        for x,z,ore in ((5,5,'coal_block'),(15,5,'raw_iron_block'),(5,16,'raw_copper_block'),(15,16,'raw_gold_block')):
            ore_stack(m,x,2,z,ore,4,3);use(m,f'ore{x}_{z}','storage',x,2,z,'棚下周转矿料',x,z+3)
        worktop(m,5,2,11,5,'receiving','长棚入库抽验台',material='polished_andesite')
        worktop(m,15,2,11,5,'packing','出库分装封签台');bench(m,26,2,10,3,'north')
        stock(m,26,2,16,4,'records','当日运单与封签器具')
        room(m,'shed','开放周转长棚',5,5,20,19,'两排临时矿料堆、中央贯通卸货面');room(m,'office','侧边账房',26,4,30,20,'账台、档案与沿棚侧门')
        note='无封墙的两排矿料长棚容纳横向装卸，狭长账房贴在东侧，不占中央周转通道。'
    m.meta['terrain']['保留空间']='依托实际货运面，保留宽门、搬运道及棚下空区；不得用矿堆填满人行主路。'
    return describe(m,note)

def inn(v):
    names=['两床炉边小食堂','转角酒馆客舍','楼上四床客栈','坡院换班食宿所']
    w,d=[(27,29),(33,31),(25,29),(31,31)][v-1]
    m=base(f'MF-08-v{v:02}',names[v-1],w,d,26,role='fill')
    if v==1:
        hall(m,3,3,23,25,roof='hip');front(m,13);partition(m,4,17,22,17,(13,17))
        kitchen(m,4,2,5);shelf(m,4,2,13,4);desk(m,17,2,5,'innkeeper','登记与寄存')
        for z in (9,13):table(m,11,2,z,7)
        bunks(m,5,2,20,2,'guest');m.set(20,2,22,'water_cauldron[level=3]');shelf(m,14,2,23,6)
        worktop(m,5,2,9,4,'prep','厨房切配与餐盘回收',sample='flower_pot')
        stock(m,16,2,4,6,'luggage','登记后的行李寄存');m.set(21,2,18,'barrel[facing=up,open=false]')
        use(m,'wash','work',20,2,22,'客房洗漱与洁布',20,21)
        room(m,'dining','共餐与厨房',4,4,22,16,'两排长餐桌、登记、炊事与储粮');room(m,'sleep','后部两床客房',4,18,22,24,'二床、衣柜、饮水与行李架')
        note='一层小食堂前厅两排长桌，后隔门两床客房；前部登记靠街，储粮与灶台同侧。'
    elif v==2:
        hall(m,3,3,29,13,roof='hip');hall(m,3,13,13,27,roof='gable');front(m,19);m.door(29,2,9,facing='east');m.point('corner','entrance',(30,2,9),'转角侧街门')
        kitchen(m,4,2,5);desk(m,24,2,5,'bar','酒馆柜台');table(m,14,2,7,7);shelf(m,15,2,11,7)
        m.door(9,2,13);bunks(m,5,2,16,2,'guest1');bunks(m,5,2,22,2,'guest2')
        canopy(m,17,18,27,25);table(m,19,2,20,5);m.set(25,2,23,'water_cauldron[level=3]')
        worktop(m,5,2,10,6,'prep','酒馆切配与清洁餐盘',sample='flower_pot')
        stock(m,23,2,11,5,'drinks','酒桶与杯具后架');stock(m,10,2,26,2,'linen','客舍换洗布与公用器具')
        use(m,'wash','work',25,2,23,'院侧洗涤与回收',25,22)
        room(m,'tavern','转角公共酒馆',4,4,28,12,'双街入口、吧台、炊事与大餐桌');room(m,'guest','四床客舍翼',4,14,12,26,'两组双床与行李储物');room(m,'patio','棚下食桌院',18,19,26,24,'院侧用餐与洗涤')
        note='L形酒馆与四床客舍围出院边食棚，双街门服务转角地块，住客不穿厨房。'
    elif v==3:
        hall(m,3,3,21,25,height=5,roof='flat');hall(m,3,3,21,25,y=7,height=5,roof='gable');front(m,13)
        kitchen(m,4,2,5);desk(m,16,2,5,'register','旅客登记');table(m,11,2,12,7);shelf(m,11,2,23,7);stair(m,5,11,2,6,3)
        for z in (5,20):bunks(m,12,8,z,2,'room'+str(z))
        m.set(19,8,14,'water_cauldron[level=3]');shelf(m,11,8,24,7)
        worktop(m,11,2,18,6,'service','备餐交付与餐盘回收',sample='flower_pot')
        stock(m,15,2,4,5,'luggage','住客寄存与房号管理');bench(m,11,8,12,4,'south')
        use(m,'wash','work',19,8,14,'住宿层洗漱与洁布',18,14)
        room(m,'dining','楼下餐饮与接待',4,4,20,24,'厨房、餐桌、登记寄存及内梯');room(m,'rooms','楼上四床住宿',4,4,20,24,'四床、衣柜、饮水和有护栏的梯井',8,12)
        m.meta.update(roof_min_y=7,floors=[dict(name='接待餐饮',y=1,max_y=5),dict(name='住宿层',y=7,max_y=11)])
        note='窄街两层旅舍，下层完整公共餐饮，上层四床；六级三宽梯与护栏控制楼层联系。'
    else:
        m.box((2,0,16),(28,2,28),'stone_bricks');m.box((13,2,16),(15,2,16),'stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]')
        hall(m,3,3,12,12,roof='hip');front(m,8);kitchen(m,4,2,5);desk(m,8,2,10,'register','换班食宿登记')
        canopy(m,16,4,27,12);table(m,18,2,7,7)
        hall(m,3,19,27,27,y=2,height=5,roof='hip');front(m,14,19,3);bunks(m,5,3,22,2,'west');bunks(m,18,3,22,2,'east')
        m.set(14,3,24,'water_cauldron[level=3]');shelf(m,11,3,26,6)
        stock(m,4,2,11,3,'food','粮食与清洁餐具');worktop(m,18,2,4,7,'service','食棚分餐与收盘长台',sample='flower_pot')
        stock(m,20,3,20,6,'linen','换班衣物与行李寄存',az=21);use(m,'wash','work',14,3,24,'高台客房洗漱',14,23)
        room(m,'kitchen','低台厨房登记',4,4,11,11,'炊事、饮水、储物与登记');room(m,'dining','低台棚下长桌',17,5,26,11,'面向街面的公共用餐');room(m,'sleep','后高台四床屋',4,20,26,26,'四床、行李柜、洗漱水盆和储物架',3,7)
        m.meta.update(roof_min_y=7,floors=[dict(name='低餐饮与高客房',y=1,max_y=6)],preview_context=dict(kind='slope',land_surface_y=2,padding=5,run=16,rise=1,slope_origin_z=16))
        m.meta['terrain']['高程']='低台公共用餐脚底 Y=2；后高台客房 Y=3；向 +Z 升一格。'
        note='低台封闭厨房配开放大食棚，后高台四床屋经中央短阶进入；换班食客和住宿动线分开。'
    return describe(m,note,'矿业居住组团或访客路线旁，有稳定干地及足够的日常给水与食物补给。')

def shop(v):
    names=['炉饼窄面食品铺','转角矿具修理铺','双翼衣物缝补店','前店后住杂货铺','楼上抄图书铺','院边暖茶小铺','顺坡皮具修补铺','石器陶器小作坊']
    dims=[(19,23),(25,23),(29,25),(23,29),(21,27),(29,23),(27,27),(31,27)]
    w,d=dims[v-1];m=base(f'MF-F01-v{v:02}',names[v-1],w,d,25,role='fill')
    if v==1:
        hall(m,3,3,15,19,roof='gable');front(m,9);desk(m,5,2,6,'sale','食品交付');kitchen(m,5,2,13)
        shelf(m,10,2,17,4);m.set(12,2,11,'barrel[facing=up,open=false]')
        worktop(m,10,2,6,4,'display','炉饼成品与包装交付',sample='cake[bites=0]')
        worktop(m,5,2,10,4,'knead','揉面整形与配料');stock(m,5,2,18,4,'flour','面粉原料与烤具')
        room(m,'shop','前售后烤食品铺',4,4,14,18,'前台交付、后烘烤洗涤、储粮和器具')
        note='窄深食品铺以前台、后烤炉和侧储粮组织单向备餐；适合狭窄生活街。'
    elif v==2:
        hall(m,3,3,21,19,roof='hip');front(m,12);m.door(21,2,10,facing='east');m.point('corner','entrance',(22,2,10),'侧街取修门')
        desk(m,5,2,6,'sale','收件与报价');m.set(16,2,6,'smithing_table');use(m,'repair','work',16,2,6,'矿具修理',15,6)
        m.set(17,2,13,'anvil[facing=north]');m.set(12,2,15,'grindstone[face=floor,facing=north]');shelf(m,4,2,17,6);crate_stack(m,18,2,16,2,2,2)
        worktop(m,5,2,11,5,'inspect','来件拆检与修后验收',sample='heavy_weighted_pressure_plate[power=0]')
        stock(m,13,2,18,4,'finished','已修工具及分件待取');use(m,'forge','work',17,2,13,'铁砧整形工位',16,13);use(m,'edge','work',12,2,15,'磨刃与刀口复核',12,14)
        room(m,'shop','双门修理铺',4,4,20,18,'收件、修造、锻打、磨刃和备件储存')
        note='转角双门铺，接单柜台与侧街取修面分开，室内环绕锻造台、铁砧和磨刃台。'
    elif v==3:
        hall(m,3,3,13,21,roof='gable');hall(m,17,3,25,13,roof='hip');front(m,8);front(m,21);m.door(13,2,12,facing='east');m.door(17,2,10,facing='west')
        desk(m,5,2,6,'sale','衣物交付');shelf(m,4,2,19,6,contents='white_wool')
        for z in (7,10):m.set(19,2,z,'loom');use(m,f'loom{z}','work',19,2,z,'裁缝织补工位',20,z)
        m.box((19,2,16),(23,2,16),'spruce_planks');bench(m,18,2,20,5);m.set(24,2,19,'water_cauldron[level=3]')
        worktop(m,5,2,12,5,'measure','量衣检修与成衣叠放',sample='white_carpet');stock(m,5,2,16,5,'dyed','分类染色布匹',contents='blue_wool')
        stock(m,20,2,12,4,'thread','织补线料与小工具');use(m,'cutting','work',21,2,16,'院内长裁衣台',21,17)
        room(m,'sale','衣物展售长翼',4,4,12,20,'登记、布匹和成衣架');room(m,'tailor','独立双机缝补间',18,4,24,12,'两处织补台及裁衣工位');room(m,'yard','试衣候取院',14,14,24,21,'长凳候取、裁衣桌与洗涤')
        note='双翼衣铺将布匹展售和双织机缝补分屋，院内候取与裁衣桌服务不同工作流。'
    elif v==4:
        hall(m,3,3,19,25,roof='gable');front(m,11);partition(m,4,15,18,15,(11,15))
        desk(m,5,2,6,'sale','杂货柜台');shelf(m,13,2,5,5);shelf(m,4,2,12,5);crate_stack(m,15,2,11,3,2,2)
        bunks(m,5,2,19,1,'owner');kitchen(m,13,2,18);table(m,12,2,22,3)
        worktop(m,5,2,9,5,'packing','散货称分与装包');stock(m,12,2,14,6,'orders','顾客已订货与待交付箱')
        stock(m,4,2,24,4,'homeware','居室私人物品与清洁用品')
        room(m,'store','分类杂货前铺',4,4,18,14,'货架、柜台与周转箱');room(m,'home','掌柜后居',4,16,18,24,'一床、衣柜、厨房、饮水和餐桌')
        note='前店后住的深街坊杂货铺，生活门隔开货架和后居，具备完整一人生活空间。'
    elif v==5:
        hall(m,3,3,17,23,height=5,roof='flat');hall(m,3,3,17,23,y=7,height=5,roof='gable');front(m,10)
        desk(m,4,2,5,'sale','书册借售');shelf(m,10,2,21,6,contents='bookshelf');shelf(m,11,2,11,5,contents='bookshelf');stair(m,5,11,2,6,2)
        m.set(11,8,6,'cartography_table');use(m,'maps','work',11,8,6,'矿路抄图',12,6);desk(m,11,8,14,'copy','文书誊写');shelf(m,10,8,21,6,contents='bookshelf')
        worktop(m,11,2,6,5,'browse','顾客翻阅与装订检查',sample='flower_pot')
        worktop(m,10,8,10,6,'layout','矿路图纸展开与测绘校核',sample='white_carpet');stock(m,10,8,18,6,'paper','纸卷、墨具与待抄文书')
        room(m,'store','楼下书册铺',4,4,16,22,'借售登记、书架及侧梯');room(m,'copy','楼上抄图室',4,4,16,22,'制图台、誊写、文书架与梯井',8,12)
        m.meta.update(roof_min_y=7,floors=[dict(name='书册铺',y=1,max_y=5),dict(name='抄图室',y=7,max_y=11)])
        note='窄街书铺向上叠置安静抄图室，六级内梯联系借售与矿路文书作业，楼上非住宿。'
    elif v==6:
        hall(m,3,3,14,19,roof='hip');front(m,9);m.door(14,2,11,facing='east');kitchen(m,4,2,6);desk(m,5,2,14,'tea','暖茶柜台')
        canopy(m,18,5,25,17);table(m,19,2,8,4);table(m,19,2,13,4)
        shelf(m,4,2,17,6);m.set(12,2,6,'water_cauldron[level=3]')
        worktop(m,5,2,10,6,'blend','茶料配制与温杯长台',sample='flower_pot')
        stock(m,5,2,4,6,'tea_stock','干茶与杯盏分类架');worktop(m,19,2,17,5,'return','茶院归还杯具与擦拭台',sample='flower_pot')
        room(m,'tea','烧水与茶饮服务间',4,4,13,18,'备茶、储水、器具和收银');room(m,'yard','院边两组茶座',19,6,24,16,'小组歇脚与用茶')
        note='侧门茶铺面向两组院边茶桌，封闭烧水间与开放歇脚座分离，保留入院通道。'
    elif v==7:
        m.box((2,0,13),(24,2,24),'stone_bricks');m.box((12,2,13),(14,2,13),'stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]')
        hall(m,3,3,23,10,roof='hip');front(m,13);desk(m,5,2,5,'sale','皮具收件');shelf(m,16,2,7,5,contents='brown_wool')
        hall(m,3,16,23,23,y=2,height=5,roof='flat');front(m,13,16,3);m.set(5,3,19,'loom');use(m,'stitch','work',5,3,19,'皮具缝补',6,19);m.set(8,3,19,'water_cauldron[level=3]');shelf(m,16,3,21,5)
        worktop(m,5,2,8,6,'measure','皮具验损量取与估价',sample='brown_carpet');bench(m,16,2,4,4,'south')
        worktop(m,11,3,19,4,'cutting','皮革裁片与打孔',sample='brown_carpet');stock(m,5,3,22,6,'raw_hide','皮料与缝补工具')
        room(m,'sale','低街收件陈列',4,4,22,9,'前台与皮具展示');room(m,'work','后高台皮作间',4,17,22,22,'清洗、缝补与材料保管',3,7)
        m.meta.update(floors=[dict(name='低前店与高后作',y=1,max_y=6)],preview_context=dict(kind='slope',land_surface_y=2,padding=5,run=13,rise=1,slope_origin_z=13));m.meta['terrain']['高程']='向 +Z 升一格；前铺 Y=2，后作 Y=3。'
        note='低街收件陈列、后高台清洗缝补，阶院将湿作业和顾客面隔开。'
    else:
        hall(m,3,3,13,22,roof='gable');front(m,8);desk(m,5,2,6,'sale','石陶器交易');shelf(m,4,2,19,7,contents='decorated_pot')
        canopy(m,18,4,27,21);m.set(20,2,7,'stonecutter[facing=south]');use(m,'cut','work',20,2,7,'石器切磨',20,8)
        m.set(25,2,7,'water_cauldron[level=3]');m.box((20,2,15),(23,2,15),'clay');m.box((25,2,17),(26,3,20),'furnace[facing=west,lit=false]')
        use(m,'kiln','work',25,2,18,'小窑炉前',24,18);m.door(13,2,12,facing='east')
        worktop(m,5,2,11,5,'inspect','石陶器检视与包扎',sample='decorated_pot');stock(m,5,2,15,5,'display','分级成品器具陈列',contents='flower_pot')
        worktop(m,19,2,11,6,'forming','湿泥成型与石坯划线',material='polished_andesite',sample='clay');stock(m,19,2,20,4,'drying','入窑前素坯晾干架',contents='decorated_pot')
        room(m,'store','封闭石陶器铺',4,4,12,21,'交易和样品器具陈列');room(m,'work','侧院石陶作业棚',19,5,26,20,'切石、水洗、黏土与炉台')
        note='纵向石陶器铺旁是宽作业棚，切磨、湿泥和炉台分列；可从侧门运料。'
    return describe(m,note,'服务工人与居民日常生活；依照门面、转角、坡差或院落要求落位，保持完整独立模板。')

BUILDERS={}
for family,fn,count in [('MF-06',gemworks,4),('MF-07',warehouse,4),('MF-08',inn,4),('MF-F01',shop,8)]:
    BUILDERS.update({f'{family}-v{i:02}':partial(fn,i) for i in range(1,count+1)})
