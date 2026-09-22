"""Steppe route services, food/leather workflows and eight small trading layouts."""
from functools import partial
from .components import bench, crate_stack
from .steppe_caravans import canopy, cart
from .steppe_common import base, ground, entry, point, cabinet, dining, tent


def camp(key,name,w,d,condition):
    m=base(key,name,(w,19,d),condition,'steppe_crafts');ground(m,1,1,w-2,d-2)
    return m


def rest(m,x,z,key='keeper'):
    tent(m,x,z,15,17,2,'brown')
    m.box((x+7,3,z+2),(x+7,5,z+9),'white_wool');m.door(x+7,3,z+7,'dark_oak','east')
    m.bed(x+4,3,z+6,'brown','north');m.set(x+4,3,z+7,'barrel[facing=up]')
    m.point(key+'bed','sleep',(x+4,3,z+6),'值宿床铺与行李',approach=(x+5,3,z+6))
    cabinet(m,key+'cloth',x+8,z+4,3,label='值守衣物与干粮')
    point(m,key+'cook',x+10,z+8,'smoker[facing=west]','值宿热食',approach=(x+9,3,z+8))
    m.box((x+10,4,z+8),(x+10,11,z+8),'cobblestone_wall')
    point(m,key+'water',x+10,z+10,'water_cauldron[level=3]','值守储水',approach=(x+9,3,z+10))
    m.box((x+4,3,z+10),(x+6,3,z+11),'dark_oak_slab[type=top]');bench(m,x+4,3,z+13,3,'north','dark_oak')
    point(m,key+'record',x+4,z+10,'lectern[facing=east]','值守记录与用餐',approach=(x+3,3,z+10))
    m.room(key,'值守生活帐',(x+2,3,z+2),(x+12,6,z+14),'睡眠隔断、衣物、热食储水与记账用餐')


def fence(m,x0,z0,x1,z1,gap=3):
    mid=(x0+x1)//2
    for x in range(x0,x1+1):
        for z in (z0,z1):
            if z==z1 and abs(x-mid)<=gap//2:continue
            m.set(x,3,z,'dark_oak_fence[east=true,west=true]')
    for z in range(z0,z1+1):
        for x in (x0,x1):m.set(x,3,z,'dark_oak_fence[north=true,south=true]')
    for x in (x0,x1):
        for z in (z0,z1):m.set(x,3,z,'dark_oak_fence[north=true,south=true,east=true,west=true]')


def pen(m,x,z,w,d,key='pen',water=1,cover=True):
    fence(m,x,z,x+w-1,z+d-1)
    if cover:canopy(m,x+1,z+1,x+w-2,z+7,'brown',7)
    m.box((x+2,3,z+2),(x+4,4,z+3),'hay_block')
    for i in range(water):point(m,key+'water'+str(i),x+w-4-i*2,z+4,'water_cauldron[level=3]','运水饮畜槽')
    cabinet(m,key+'care',x+2,z+d-5,3,label='照护与清栏器具')
    m.point(key+'handle','work',(x+w//2,3,z+d-3),'检畜与交接净空',approach=(x+w//2,3,z+d-2))
    m.room(key,'饮水休畜栏',(x+1,3,z+1),(x+w-2,6,z+d-2),'休畜、运水、饲料与检畜通路')


def relay(v):
    sizes=[(37,31),(45,35),(39,45),(49,39)];w,d=sizes[v-1]
    names=['小畜栏换乘驿','双槽补水驿','前后分栏驿','鞍具帐与回转栏驿']
    m=camp(f'SC-02-v{v:02}',names[v-1],w,d,['普通小商路旁背风台地，短时换乘','依托稳定外部水源的补水节点，双水槽不等于自生水源','狭长路侧场地，检畜与歇畜前后错开','转弯路线旁扩大回转院，鞍具值守帐侧向布置'][v-1])
    rest(m,3,3)
    if v==1:pen(m,21,4,13,20)
    if v==2:pen(m,23,4,18,24,water=2);cabinet(m,'reservewater',5,25,5,label='取水桶与补给周转')
    if v==3:
        pen(m,22,4,14,18);pen(m,22,24,14,15,'quarantine',cover=False)
        cabinet(m,'tack',5,27,4,label='鞍具维修储备');point(m,'repair',11,27,'crafting_table','快修鞍具')
    if v==4:
        pen(m,25,4,20,23,water=2);canopy(m,4,26,20,33,'orange',7)
        cabinet(m,'tack',6,27,4,label='鞍具皮料');point(m,'repair',13,28,'crafting_table','交接前鞍具检查')
    entry(m,19,d-3);m.meta['differences']=[names[v-1]+'；水槽数、栏位组织、维修与回转空间不同。'];return m


def repair(v):
    w,d=[(29,35),(43,35),(29,48),(48,37)][v-1]
    names=['单车修理棚','并列双车修理棚','串列长轴维修棚','配件帐联修车棚']
    m=camp(f'SC-03-v{v:02}',names[v-1],w,d,'已有车队路线的'+['小维修支点','宽阔换轴场','狭长待修地块','长期配件与修车节点'][v-1]+'；南端留车辆进出，内棚为静态车体。')
    shedright=38 if v==2 else 25;end=39 if v==3 else 27
    canopy(m,3,3,shedright,end,'orange',8);cart(m,8,8)
    if v==2:cart(m,24,8)
    if v==3:cart(m,8,25)
    cabinet(m,'parts',5,4,5,label='按轮轴规格分格备件')
    point(m,'fit',19,8,'crafting_table','轮轴装配台');point(m,'grind',19,12,'grindstone[face=floor,facing=west]','轮缘修整',approach=(18,3,12))
    point(m,'iron',19,16,'anvil[facing=north]','轴箍维修',approach=(18,3,16))
    m.box((19,3,20),(22,3,23),'dark_oak_log[axis=z]')
    m.point('timber','storage',(20,3,21),'备用轴木',approach=(18,3,21))
    if v==4:rest(m,30,4);cabinet(m,'longparts',31,27,6,label='封装备件与返修件')
    else:
        bench(m,5,3,end-2,3,'north','dark_oak');point(m,'water',5,end-6,'water_cauldron[level=3]','修理清洗水')
    m.room('repair','车辆维修区',(3,3,3),(shedright,7,end),'车体、轮轴、修整、备件与进出通路')
    entry(m,14,d-3);m.meta['connections'].append(dict(kind='vehicle',pos=[14,2,d-2],direction='south',clearance=[7,6],note='静态外接车道，不声明车辆运行'))
    m.meta['differences']=[names[v-1]+'；单车、并列、串列与配件值宿布局分别建模。'];return m


def production(v,family):
    dairy=family=='SC-06';w,d=[(29,33),(31,41),(51,34),(43,43)][v-1]
    labels=['小加工帐','长棚流水作业','双帐洁污分区','转角加工与封装棚']
    m=camp(f'{family}-v{v:02}',('乳品' if dairy else '皮革鞍具')+labels[v-1],w,d,
        ('依托稳定畜乳与洁净运水，凉藏为静态柜不模拟温控；' if dairy else '依托畜牧与行旅维修，浸洗用水须回收，不就地排向水源；')+['紧凑日间工位','长条场地按前后流程收料','两帐分别容纳加工与洁净封装储物','靠营地边角布置L形作业并保留外侧通道'][v-1])
    if v==1:tent(m,4,3,21,23,2,'orange' if dairy else 'brown')
    elif v==2:canopy(m,4,3,26,32,'orange' if dairy else 'brown',8)
    elif v==3:
        tent(m,4,3,21,23,2,'orange' if dairy else 'brown');tent(m,31,5,15,19,2,'white')
    else:
        canopy(m,4,3,25,21,'orange' if dairy else 'brown',8);canopy(m,24,22,38,36,'brown',7)
    # Pairwise work islands leave a wide central aisle; stations represent a full process.
    cabinet(m,'raw',8,8,4,label='生乳洁净桶与批次' if dairy else '未加工皮料与辅料')
    point(m,'wash',20,9,'water_cauldron[level=3]','器具清洗' if dairy else '皮料浸洗',approach=(19,3,9))
    m.box((8,3,13),(11,3,15),'dark_oak_planks')
    point(m,'process',9,13,'smoker[facing=south]' if dairy else 'loom[facing=south]','加热处理与乳脂分离' if dairy else '裁切缝制与皮带装配',approach=(9,3,12))
    if dairy:m.box((9,4,13),(9,12,13),'cobblestone_wall')
    else:m.box((10,4,15),(11,4,15),'brown_carpet')
    m.box((18,3,14),(21,3,16),'dark_oak_planks')
    point(m,'seal',20,16,'crafting_table','分装封口' if dairy else '鞍具组装',approach=(20,3,17))
    m.set(18,4,14,'flower_pot' if dairy else 'brown_wool')
    # Intermediate processing and drying are distinct from the raw and finished cabinets.
    if dairy:
        for zz in (10,12):m.set(15,3,zz,'water_cauldron[level=3]')
        m.point('culture','work',(15,3,10),'乳桶静置与分批处理',approach=(14,3,10))
        m.box((9,4,15),(11,4,15),'white_carpet')
        m.box((18,4,14),(19,4,14),'white_carpet');m.set(21,4,14,'flower_pot')
        cabinet(m,'vessels',8,18,4,label='洗净器皿与滤布分柜')
    else:
        m.box((14,3,8),(14,6,8),'dark_oak_log[axis=y]');m.box((18,3,8),(18,6,8),'dark_oak_log[axis=y]')
        m.box((14,6,8),(18,6,8),'dark_oak_log[axis=x]');m.box((15,4,8),(17,5,8),'brown_wool')
        m.point('dry','work',(16,5,8),'浸洗皮料独立晾架',approach=(16,3,9))
        cabinet(m,'fittings',8,18,4,label='扣环针线与修补辅件')
        m.set(18,4,15,'anvil[facing=north]');m.set(21,4,14,'brown_carpet')
    cabinet(m,'finished',9,21,4,label='封装乳品与批次暂存' if dairy else '成品鞍具与修补件')
    point(m,'record',18,21,'lectern[facing=south]','批次交接与卫生记录' if dairy else '订单与尺码登记')
    if v==2:
        dining(m,'break',8,28,4,label='员工休息与交接桌');cabinet(m,'tools',20,27,4,label='洗净备用器具' if dairy else '制鞍备用工具')
    elif v==3:
        cabinet(m,'clean',35,9,4,label='洁净成品与包装柜');point(m,'pack',38,15,'crafting_table','独立成品包装')
        cabinet(m,'packstock',40,11,3,label='封口器材与空容器' if dairy else '鞍带扣具与护套')
        m.box((34,3,14),(36,3,17),'dark_oak_planks');m.box((34,4,14),(36,4,14),'white_carpet' if dairy else 'brown_carpet')
        point(m,'checkpack',35,17,'crafting_table','洁净复核与整箱扎包',approach=(35,3,18))
        cabinet(m,'dispatch',39,19,3,label='已验封箱待发货')
        dining(m,'break',29,27,4,label='休息交接桌');m.room('clean','独立洁净储藏帐',(32,3,7),(44,6,21),'成品与包装分离原料')
    elif v==4:
        cabinet(m,'clean',27,25,5,label='待发成品');dining(m,'break',28,30,4,label='交接与休息桌')
        point(m,'tool',35,26,'brewing_stand' if dairy else 'grindstone[face=floor,facing=south]','检测器具台' if dairy else '五金修磨台')
        m.room('annex','转角封装交接棚',(24,3,22),(38,6,36),'发货包装与员工休息')
    else:bench(m,17,3,25,3,'north','dark_oak')
    m.room('flow','原料处理与封装',(6,3,6),(23,7,24),'原料柜、洗槽、主处理、封装、成品与管理')
    entry(m,15,d-3);m.meta['differences']=[labels[v-1]+'；结合行业分别组织收料、加工、成品和管理。'];return m


def cargo(v):
    w,d=[(25,37),(43,35),(47,37),(33,47)][v-1]
    names=['窄条货棚','双面装卸货场','看管帐货物场','前后转运长棚']
    m=camp(f'SC-08-v{v:02}',names[v-1],w,d,'季节停驻贸易点的'+['狭长边带','宽敞分流院','带值宿的稳定存放区','长条前后转运地块'][v-1]+'；货棚保持干燥，南端接既有车道。')
    sx=4;ex=20 if v in (1,4) else 25;ez=36 if v==4 else 26
    canopy(m,sx,3,ex,ez,'brown',8)
    for i,z in enumerate(range(6,ez-4,7)):
        cabinet(m,'goods'+str(i),6,z,4,label=['粮袋干货','织品皮料','陶器工具','备用车料'][i%4]);crate_stack(m,15,3,z,3,3,2)
        m.point('load'+str(i),'storage',(15,4,z),'分组封装货垛',approach=(14,3,z))
    point(m,'ledger',6,ez-2,'lectern[facing=south]','收发货登记')
    if v==2:cart(m,32,6);cart(m,32,21)
    if v==3:rest(m,29,4)
    if v==4:cart(m,24,7)
    m.room('cargo','分类干货与装卸',(4,3,3),(ex,7,ez),'中央卸货通道，两侧分类柜与货垛')
    entry(m,12,d-3);m.meta['differences']=[names[v-1]+'；分类规模、装卸侧、看管配套与货车排列不同。'];return m


def stall(v):
    names=['热食双座摊','草药双面摊','织毡侧棚摊','工具车旁摊','种粮封装摊','皮具修补摊','灯具转角摊','地图书信摊']
    w,d=[(19,23),(21,19),(25,23),(31,23),(21,25),(23,23),(25,25),(21,23)][v-1]
    m=camp(f'SC-F01-v{v:02}',names[v-1],w,d,'按商路实际需求设置的'+names[v-1]+'；短驻轻型棚保持邻侧步道，商品由外部供给。')
    canopy(m,3,3,15,13,'orange' if v%2 else 'brown',7)
    m.box((5,3,7),(12,3,8),'dark_oak_planks');cabinet(m,'stock',5,4,4,label='本行业后备货物')
    block=['smoker[facing=south]','brewing_stand','loom[facing=south]','grindstone[face=floor,facing=south]','composter','crafting_table','smithing_table','cartography_table'][v-1]
    point(m,'trade',8,8,block,'售卖与行业操作')
    m.set(5,4,7,['flower_pot','flower_pot','white_wool','anvil[facing=north]','hay_block','brown_wool','yellow_candle[candles=3]','lectern[facing=south]'][v-1])
    point(m,'cash',12,7,'barrel[facing=south]','交接与小额寄存',approach=(13,3,7))
    if v==1:
        m.box((8,4,8),(8,11,8),'cobblestone_wall');dining(m,'meal',5,17,3,label='顾客短餐桌椅')
    elif v==2:
        m.box((17,3,6),(17,3,12),'dark_oak_planks');point(m,'dispense',17,9,'brewing_stand','外侧配药交付',approach=(18,3,9))
    elif v==3:
        canopy(m,17,4,22,18,'brown',7);m.box((18,3,8),(20,3,12),'dark_oak_planks');m.box((18,4,8),(20,4,10),'orange_carpet')
        point(m,'measure',18,13,'loom[facing=south]','侧棚裁布织补')
    elif v==4:cart(m,22,4);point(m,'repair',16,16,'anvil[facing=north]','车旁临修')
    elif v==5:
        m.box((5,3,17),(12,3,18),'dark_oak_planks');m.set(5,4,17,'melon');m.set(8,4,17,'pumpkin');point(m,'seal',11,18,'crafting_table','种粮封装')
    elif v==6:
        dining(m,'measure',5,17,4,label='量裁修补桌');point(m,'stitch',18,10,'loom[facing=south]','皮带缝补')
    elif v==7:
        canopy(m,16,14,21,21,'brown',7);m.box((17,3,16),(19,3,18),'dark_oak_planks');point(m,'lights',18,18,'yellow_candle[candles=4]','灯具测试与包装')
    else:
        cabinet(m,'books',5,12,4,label='书信与地图卷宗');dining(m,'reading',5,17,4,label='阅图写信桌')
    m.room('sales','交易与后備货物',(3,3,3),(15,6,13),'有顶柜台、行业样品与库存，外部工位随行业区别')
    entry(m,14,d-3);m.meta['differences']=[names[v-1]+'；不同商品同时改变侧棚、双面、车位、顾客餐桌和操作空间。'];return m


BUILDERS={**{f'SC-02-v{v:02}':partial(relay,v) for v in range(1,5)},**{f'SC-03-v{v:02}':partial(repair,v) for v in range(1,5)},
 **{f'{fam}-v{v:02}':partial(production,v,fam) for fam in ('SC-06','SC-07') for v in range(1,5)},
 **{f'SC-08-v{v:02}':partial(cargo,v) for v in range(1,5)},**{f'SC-F01-v{v:02}':partial(stall,v) for v in range(1,9)}}
