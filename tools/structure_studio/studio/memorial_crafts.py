"""Gothic everyday trades: process-specific stations in several spatial organizations."""
from .memorial_life import base,house,entry,upper,partition,zone,finish,cook,dining,sleep,stores,counter,use,pointed
from .components import bench,pendant


def home(m,x,z,key='home',y=3):
    cook(m,key+'cook',x,z,y=y);dining(m,x+1,z+5,4,y=y)
    sleep(m,key+'bed',x+1,z+12,2,y=y);stores(m,key+'linen','经营家庭衣物与被服',x+2,z+15,5,y=y)


def workplace(key,name,shape):
    if shape=='side':
        m=base(key,name,39,34);house(m,4,4,21,29);house(m,26,8,35,29)
        entry(m,'entry',13,4);entry(m,'homeentry',30,8);home(m,27,10)
        zone(m,'home','独立侧翼家庭宅',27,9,34,28,'前餐厨后双床，与经营区隔侧院')
        positions=[(5,6),(15,10),(5,14),(5,24),(15,23)]
        zone(m,'shop','前侧交易与原料',5,5,20,13,'临街顾客与原料查验、周转')
        zone(m,'work','后部加工交付厅',5,14,20,28,'加工、成品整理与记账，保留中轴通道')
        note='主作业厅与独立侧宅隔院分置，顾客和家庭各走自己的入户门。'
    elif shape=='upper':
        m=base(key,name,29,35,36);house(m,4,4,24,31,height=11);entry(m,'entry',14,4);upper(m,5,5,23,30,20,17)
        home(m,5,6,y=9);partition(m,5,16,19,13,y=9)
        stores(m,'familyextra','上层家庭备用食粮',14,27,5,y=9)
        positions=[(5,6),(16,10),(5,14),(5,25),(13,26)]
        zone(m,'shop','下层交易原料前厅',5,5,23,15,'销售、验货和原料归类')
        zone(m,'work','下层后加工成品区',5,16,19,30,'加工、成品、工作记录与侧梯')
        zone(m,'home','上层家庭炊食寝居',5,5,23,30,'完整双床家庭、食粮与被服，实体分隔前后',y=9)
        note='经营在下、家庭在上，以内侧实梯连接，生活寝居独立成层。'
    elif shape=='court':
        m=base(key,name,39,38);house(m,4,4,19,14);house(m,4,21,19,34);house(m,26,7,35,28)
        entry(m,'entry',12,4);entry(m,'workentry',12,21);entry(m,'homeentry',30,7);home(m,27,9)
        positions=[(5,6),(13,10),(5,23),(5,30),(13,30)]
        zone(m,'front','临街交易与原料屋',5,5,18,13,'顾客验货和待加工物资接收')
        zone(m,'back','独立后加工屋',5,22,18,33,'较嘈杂加工及成品整理，经院与前铺联系')
        zone(m,'home','侧院双床家宅',27,8,34,27,'完整家庭生活，独立于加工动线')
        bench(m,6,3,17,5,wood='spruce');m.point('yard','circulation',(22,3,18),'三屋之间运料院路')
        note='前交易、后加工和侧家庭三屋围院，原料转运经过开敞院路。'
    elif shape=='rear':
        m=base(key,name,31,45);house(m,4,4,26,22);house(m,4,28,26,40)
        entry(m,'entry',15,4);entry(m,'homeentry',15,28)
        cook(m,'homecook',5,30);dining(m,6,35,5);sleep(m,'homebed',18,35,2);stores(m,'homelinen','家庭被服衣物',19,38,5)
        m.box((16,3,29),(16,6,39),'calcite');m.door(16,3,34,wood='dark_oak',facing='east')
        positions=[(5,6),(19,7),(5,13),(5,19),(19,18)]
        zone(m,'shop','宽横交易厅',5,5,25,11,'顾客选购、进料与验货，中央通路贯通')
        zone(m,'work','宽横后加工厅',5,12,25,21,'加工整理与成品独立两侧')
        zone(m,'home','后院横向家宅',5,29,25,39,'侧隔墙区分餐厨与双床寝室')
        note='宽短经营屋与后院横向家庭宅分离，中央院路缓冲营业噪声。'
    elif shape=='elbow':
        m=base(key,name,39,39);house(m,4,4,30,15);house(m,4,15,18,34);house(m,25,18,34,35)
        m.box((8,3,15),(13,6,15),'air');entry(m,'entry',25,4);entry(m,'homeentry',30,18)
        cook(m,'homecook',26,20);dining(m,27,25,4);sleep(m,'homebed',26,31,2);stores(m,'homelinen','家庭衣物',27,34,5)
        positions=[(23,7),(5,7),(5,18),(5,29),(12,29)]
        zone(m,'front','横向交易验料厅',5,5,29,14,'沿街入口与两侧选货、验料，转角入后翼')
        zone(m,'work','折后加工翼',5,16,17,33,'生产、成品和管理沿工作翼排列')
        zone(m,'home','内院家庭屋',26,19,33,34,'炊食和双床完整位于生产翼之外')
        note='横街铺面折入纵向生产翼，内院另一侧设独立家宅，L形动线有真实转折。'
    else:
        m=base(key,name,31,41);house(m,4,4,16,35);house(m,21,15,27,35)
        entry(m,'entry',10,4);entry(m,'homeentry',24,15)
        partition(m,5,14,15,10);partition(m,5,24,15,10)
        cook(m,'homecook',22,17);dining(m,22,22,4);sleep(m,'homebed',22,29,1);stores(m,'homelinen','单人家庭被服',22,33,4)
        positions=[(5,6),(12,10),(5,17),(5,28),(12,30)]
        zone(m,'front','前端窄交易间',5,5,15,13,'街面选货与接单')
        zone(m,'work','中段独立加工间',5,15,15,23,'闭门加工，避免顾客直接触及工具')
        zone(m,'stock','后段成品与管理',5,25,15,34,'成品库及账册管理')
        zone(m,'home','侧后单人家宅',22,16,26,34,'单床、餐厨与被服，不依赖商铺充当寝室')
        note='窄长营业屋明确分成前交易、中加工、后库三个实墙房间，侧后另接单人家宅。'
    m.meta['source']='tools/structure_studio/studio/memorial_crafts.py:BUILDERS'
    return m,positions,note


TRADES={
'candle':('日用与仪式蜡烛','蜂蜡、线芯与颜料','熔蜡与浇模','冷却脱模及成品','批次配方与订单','honeycomb_block','water_cauldron[level=1]','yellow_candle[candles=4,lit=false]','cartography_table'),
'stone':('碑石样品与委托','石坯、磨料及工具','刻字与精雕工作台','待交碑面与成品','碑文校核与订单','polished_andesite','stonecutter[facing=south]','chiseled_stone_bricks','lectern[facing=south]'),
'flowers':('花束与扎饰选购','花材分桶与枝叶','修枝、配束与绑扎','已订花束与器皿','花材养护与交付单','moss_block','crafting_table','flower_pot','lectern[facing=south]'),
'ritualcandle':('日用祭礼蜡烛选购','蜡烛线芯与包装','配烛、修芯及包扎','按单成组蜡烛','用品订单与账册','honeycomb_block','crafting_table','white_candle[candles=4]','lectern[facing=south]'),
'memorial':('纪念牌与小物展示','木片、石片与嵌件','刻印、修边与装配','已刻纪念物及包装','姓名与日期核对','stripped_oak_wood','stonecutter[facing=south]','chiseled_stone_bricks','lectern[facing=south]'),
'paper':('信纸、纪念卡与册页','纸材、染料与封套','裁纸、抄写及装订','已装订册与干纸品','版式与委托档案','bookshelf','cartography_table','bookshelf','lectern[facing=south]'),
'bread':('面包与热食柜','面粉、谷物及干料','和面、烘烤与冷却','当日成品与包装','批次与订餐记录','hay_block','smoker[facing=south]','cake[bites=0]','lectern[facing=south]'),
'grocery':('食粮及家用杂货','进货分拣与周转箱','计量、分装与封口','分类待取日用品','存货及赊购登记','barrel','crafting_table','barrel','lectern[facing=south]'),
'clothes':('成衣、样布与试衣','布卷、纽扣与线料','量裁、缝制与整烫','已完成衣物柜','尺寸记录与交付','white_wool','loom[facing=south]','blue_wool','lectern[facing=south]'),
'repair':('修理接件与验收','零件、木料和金属','拆检、打磨及装配','已修器具与待取柜','修理单与工序记录','iron_block','smithing_table','grindstone[face=floor,facing=south]','lectern[facing=south]'),
'veg':('居民蔬果销售','新到蔬菜与根茎','清洗、挑拣及分装','已分装食材','进货与余量记录','composter[level=0]','water_cauldron[level=3]','melon','lectern[facing=south]'),
'pots':('陶杯盆罐与日用器','陶土、釉料与托板','修坯、烧制及检视','已烧日用陶器','窑次与器型记录','clay','furnace[facing=south]','decorated_pot','lectern[facing=south]'),
'books':('日用册页与借阅','纸张、书脊与封面','抄写、修书及装订','修复书册与订单','借阅与取件记录','bookshelf','cartography_table','bookshelf','lectern[facing=south]'),
'lamps':('居民照明灯具','灯芯、油罐及配件','灯罩、灯芯与装配','已验照明器具','修配与交货记录','copper_block','crafting_table','lantern','lectern[facing=south]')}


def decorate_trade(m,kind,positions):
    sale,raw,work,done,record,rawblock,workblock,show,desk=TRADES[kind]
    (sx,sz),(rx,rz),(wx,wz),(fx,fz),(dx,dz)=positions
    counter(m,'sale',sale,sx,sz,3,block='barrel');m.set(sx+1,4,sz,show)
    stores(m,'raw',raw,rx,rz,3);m.set(rx+2,4,rz,rawblock)
    counter(m,'process',work,wx,wz,4,block=workblock)
    counter(m,'finished',done,fx,fz,4,block='barrel');m.set(fx+1,4,fz,show)
    counter(m,'records',record,dx,dz,3,block=desk)
    m.point('customer','circulation',(sx+1,3,sz+2),'顾客选货与交付停留',look_at=[sx+1,4,sz])
    if kind in ('candle','ritualcandle'):
        m.set(wx+2,3,wz,'water_cauldron[level=1]');m.set(wx+1,4,wz,'yellow_candle[candles=3]')
        m.set(fx+2,4,fz,'white_candle[candles=4]')
    elif kind=='stone':
        m.box((wx+1,3,wz+3),(wx+3,3,wz+4),'polished_andesite');m.set(wx+2,4,wz+4,'chiseled_stone_bricks')
        use(m,'roughcut','待刻大石坯与侧向搬运面',wx+1,wz+3,wx,wz+3)
    elif kind=='flowers':
        for i,b in enumerate(('potted_poppy','potted_blue_orchid','potted_allium')):m.set(fx+i,4,fz,b)
        m.set(wx+2,3,wz,'water_cauldron[level=3]')
    elif kind=='clothes':
        m.set(wx+2,3,wz,'loom[facing=south]');m.set(fx+1,4,fz,'red_wool');m.set(fx+2,4,fz,'yellow_wool')
    elif kind in ('bread','pots'):
        m.set(wx+2,3,wz,'furnace[facing=south]')
        roof=max(y for (x,y,z),b in m.blocks.items() if x==wx+2 and z==wz and b[0]!='minecraft:air')
        m.box((wx+2,4,wz),(wx+2,roof+2,wz),'bricks')
    elif kind=='repair':m.set(wx+2,3,wz,'anvil[facing=north]')
    elif kind=='lamps':
        for i in (1,2):m.set(fx+i,4,fz,'lantern')
    m.meta['static_facilities']='行业设备、蜡液、加工与商品均为静态场景表达；未声称接入生产或交易机制。'


def shop(key,name,shape,trade):
    m,positions,note=workplace(key,name,shape);decorate_trade(m,trade,positions)
    return finish(m,note+' '+TRADES[trade][0]+'经营完整设置原料、加工、成品、交易和管理，每处标注独立站位。')


BUILDERS={}
for i,(shape,title) in enumerate((('side','侧宅日用蜡坊'),('court','围院熔蜡作坊'),('upper','楼下制蜡楼上家宅'),('elbow','折街前售后制蜡坊')),1):
    key=f'ML-04-v{i:02}';BUILDERS[key]=lambda key=key,title=title,shape=shape:shop(key,title,shape,'candle')
for i,(shape,title) in enumerate((('rear','宽前雕刻后宅石坊'),('court','分屋转料庭院石坊'),('side','侧宅碑石展示作坊'),('elbow','转角验料与长雕刻翼')),1):
    key=f'ML-06-v{i:02}';BUILDERS[key]=lambda key=key,title=title,shape=shape:shop(key,title,shape,'stone')
for i,(shape,trade,title) in enumerate((('elbow','flowers','折院花材与扎饰铺'),('upper','ritualcandle','楼下配烛楼上家宅'),('rear','memorial','纪念刻物宽铺与后宅'),('narrow','paper','三进纸品装订铺')),1):
    key=f'ML-09-v{i:02}';BUILDERS[key]=lambda key=key,title=title,shape=shape,trade=trade:shop(key,title,shape,trade)
for i,(shape,trade,title) in enumerate((('side','bread','侧宅面包烘焙铺'),('narrow','grocery','三进分装杂货铺'),('upper','clothes','裁衣工坊与上层家宅'),('elbow','repair','转角器具维修铺'),('court','veg','分屋蔬果挑拣铺'),('rear','pots','陶器烧修宽铺后宅'),('narrow','books','前售后修日常书铺'),('court','lamps','分屋灯具制作与家宅')),1):
    key=f'ML-F01-v{i:02}';BUILDERS[key]=lambda key=key,title=title,shape=shape,trade=trade:shop(key,title,shape,trade)


def inn_front(m):
    counter(m,'reception','住宿登记与房间分配',16,6,5,block='lectern[facing=south]');stores(m,'luggage','带号寄存行李',16,11,5)
    cook(m,'kitchen',5,6);dining(m,6,11,6);stores(m,'food','旅店备粮与餐具',5,17,5)


def inn_small():
    m=base('ML-08-v01','侧廊双客房小旅店',27,40);house(m,4,4,22,36);entry(m,'entry',13,4);inn_front(m)
    partition(m,5,20,21,17);m.box((13,3,21),(13,6,35),'calcite')
    for zz in (24,32):m.door(13,3,zz,wood='dark_oak',facing='east')
    partition(m,5,28,12,9);sleep(m,'guesta',6,24,2);sleep(m,'guestb',6,32,2)
    partition(m,14,27,21,17);sleep(m,'staff',15,32,2);stores(m,'linen','后寝共用被服',16,35,5)
    m.set(18,3,22,'water_cauldron[level=3]');use(m,'wash','旅客洗尘补水',18,22)
    zone(m,'common','登记寄物与前餐厅',5,5,21,19,'正常供餐、备粮、入店登记及寄存')
    zone(m,'rooma','第一双床客房',5,21,12,27,'双床与个人柜，侧廊独立门')
    zone(m,'roomb','第二双床客房',5,29,12,35,'安静双床客房与独立床柜')
    zone(m,'staff','后店主双床间',14,28,21,35,'店主家庭寝居与被服')
    m.meta['source']='tools/structure_studio/studio/memorial_crafts.py:BUILDERS'
    return finish(m,'小旅店以前共厅后侧廊组织两间双床客房，另有店主双床寝间；寄存、洗尘、供餐与备粮均独立标记。')


def inn_court():
    m=base('ML-08-v02','三翼庭院朝圣客栈',40,40)
    house(m,4,4,22,18);house(m,28,4,36,18);house(m,4,25,15,35);house(m,22,25,33,35)
    entry(m,'entry',14,4);entry(m,'staffentry',32,4);entry(m,'guestaentry',10,25);entry(m,'guestbentry',28,25)
    counter(m,'reception','到店登记与路程咨询',16,6,5,block='lectern[facing=south]');stores(m,'luggage','院前统一行李寄存',16,13,5)
    cook(m,'cook',5,6);dining(m,6,11,6);stores(m,'food','公共厨房粮食餐具',5,16,6)
    sleep(m,'staff',29,10,2);stores(m,'stafflinen','店主家庭被服',30,15,5)
    for i,x in enumerate((5,23)):
        sleep(m,'guest'+str(i),x+1,30,3);stores(m,'linen'+str(i),'本屋客用被服',x+2,33,6)
        zone(m,'guest'+str(i),'三床独立客舍'+str(i+1),x,26,x+9,34,'三床与床柜，朝庭院独立入口')
    bench(m,11,3,21,6,wood='spruce');m.set(29,3,21,'water_cauldron[level=3]');use(m,'wash','庭院洗尘与清水',29,21)
    zone(m,'common','前部接待供餐翼',5,5,21,17,'完整登记、寄存、餐厨与备粮')
    zone(m,'staff','侧翼店主寝居',29,5,35,17,'独立双床和被服，供餐由前翼厨房承担')
    zone(m,'court','中庭长凳与洗尘',5,19,35,24,'连接各舍的公共休息与步行空间')
    m.meta['source']='tools/structure_studio/studio/memorial_crafts.py:BUILDERS'
    return finish(m,'前接待餐厨、侧店主寝屋和后两座三床客舍围出开放休息院；六客床无需穿过餐桌或其他客房到达。')


def inn_upper():
    m=base('ML-08-v03','下餐厅上客房街角旅店',29,36,36);house(m,4,4,24,32,height=11);entry(m,'entry',14,4);upper(m,5,5,23,31,20,16)
    inn_front(m);partition(m,5,23,19,13);sleep(m,'staff',6,28,2);stores(m,'stafflinen','店主被服',7,31,6)
    m.box((16,9,5),(16,12,30),'calcite');m.door(16,9,11,wood='dark_oak',facing='east');m.door(16,9,25,wood='dark_oak',facing='east')
    partition(m,5,18,15,11,y=9);sleep(m,'guesta',6,11,3,y=9);stores(m,'linena','前客房被服',7,15,6,y=9)
    sleep(m,'guestb',6,25,3,y=9);stores(m,'linenb','后客房被服',7,29,6,y=9)
    m.set(19,9,7,'water_cauldron[level=3]');use(m,'wash','楼层洗尘补水',19,7,y=9)
    zone(m,'common','下层餐厨与登记',5,5,19,22,'餐饮、寄存和接待，侧梯上客房')
    zone(m,'staff','下层后店主寝间',5,24,19,31,'店主双床与被服')
    zone(m,'guesta','上层前三床客房',5,5,15,17,'独立三床、床柜和被服',y=9)
    zone(m,'guestb','上层后三床客房',5,19,15,31,'第二三床客房，侧廊入口不穿前客房',y=9)
    zone(m,'landing','侧梯与洗尘廊',17,5,23,31,'内梯护栏、上层交通和洗尘',y=9)
    m.meta['source']='tools/structure_studio/studio/memorial_crafts.py:BUILDERS'
    return finish(m,'两层街角旅店用下层餐厅与店主后寝、上层两间三床客房；楼层侧廊分户进入，寄物留在下层登记旁。')


def inn_hostel():
    m=base('ML-08-v04','横厅与纵寝翼多人旅舍',39,41)
    house(m,4,4,33,15);house(m,4,15,17,36);house(m,25,23,34,36)
    m.box((8,3,15),(13,6,15),'air');entry(m,'entry',23,4);entry(m,'staffentry',30,23)
    cook(m,'cook',5,6);dining(m,6,11,6);counter(m,'reception','多人旅舍登记',23,6,6,block='lectern[facing=south]')
    stores(m,'luggage','旅行包裹寄存',23,12,6);stores(m,'food','餐厅粮食与餐具',16,7,4)
    sleep(m,'guesta',6,21,3);sleep(m,'guestb',6,30,3);stores(m,'linen','大寝翼公共被服',7,34,7)
    m.box((5,3,26),(12,4,26),'bookshelf');bench(m,19,3,19,6,wood='spruce')
    sleep(m,'staff',26,29,2);stores(m,'stafflinen','店主家庭被服',27,34,5);counter(m,'records','店主账务整理',26,25,5,block='lectern[facing=south]')
    m.set(29,3,19,'water_cauldron[level=3]');use(m,'wash','庭院洗尘补水',29,19)
    zone(m,'common','横街公共供餐厅',5,5,32,14,'长餐桌、登记、寄存和完整厨房')
    zone(m,'dorm','六床纵向合宿翼',5,16,16,35,'两组三床以矮书柜分隔，床柜和公共被服齐全')
    zone(m,'staff','独立店主寝与账房',26,24,33,35,'店主双床、被服与账册，供餐共享主厨房')
    zone(m,'court','侧院洗尘休息',18,16,33,22,'旅客长凳及洗尘，通往独立店主屋')
    m.meta['source']='tools/structure_studio/studio/memorial_crafts.py:BUILDERS'
    return finish(m,'宽公共横厅接长六床合宿翼，院侧另设店主寝居；合宿用低书屏分组，食品服务和行李登记仍在独立前厅。')


BUILDERS.update({f'ML-08-v{i:02}':f for i,f in enumerate((inn_small,inn_court,inn_upper,inn_hostel),1)})
