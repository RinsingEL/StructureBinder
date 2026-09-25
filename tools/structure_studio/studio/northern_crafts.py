"""Cold coast production, reserves, lodging and daily trade."""
from .northern_seafarers import base,lodge,entry,use,counter,cook,dining,sleep,finish,rail,steps
from .northern_life import stores,partition,zone,stairs,upper_windows
from .components import bench


def workhome(key,name,w,d,h=28):
    m=base(key,name,w,d,h,role='fill');m.meta['source']='tools/structure_studio/studio/northern_crafts.py:BUILDERS'
    return m


def canopy(m,x0,z0,x1,z1,y=3):
    m.box((x0,y-1,z0),(x1,y-1,z1),'spruce_planks')
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,y,z),(x,y+3,z),'dark_oak_log[axis=y]')
    m.box((x0,y+4,z0),(x1,y+4,z1),'spruce_slab[type=bottom,waterlogged=false]')
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],y+4)


def drying(m,key,x,z,n=7,y=3):
    for xx in (x,x+n-1):m.box((xx,y,z),(xx,y+3,z),'dark_oak_log[axis=y]')
    m.box((x,y+3,z),(x+n-1,y+3,z),'spruce_log[axis=x]')
    for xx in range(x+1,x+n-1):m.box((xx,y+1,z),(xx,y+2,z),'iron_bars[east=true,west=true,north=false,south=false,waterlogged=false]')
    use(m,key,'分列悬挂干燥架',x+2,z,x+2,z+1,y)


def fishline(m,x,z,key,y=3):
    counter(m,key+'wash','卸鱼检验、清洗与沥水',x,z,6,y,'water_cauldron[level=3]')
    counter(m,key+'cut','剖鱼去骨与盐料计量台',x,z+4,6,y,'crafting_table')
    for xx in (x,x+2,x+4):m.set(xx,y,z+8,'barrel[facing=up,open=false]')
    use(m,key+'salt','分批盐渍桶和批次通道',x+2,z+8,x+2,z+7,y)


def salt_small():
    m=workhome('NS-03-v01','前洗后盐小鱼坊',23,29)
    lodge(m,3,3,19,24);entry(m,'entry',11,3)
    partition(m,12,4,12,23,12,14)
    fishline(m,4,5,'fish');stores(m,'salt','干燥食盐与包材',4,22,6)
    drying(m,'rack',14,7,4);drying(m,'rack2',14,13,4)
    stores(m,'finished','已盐成品与取货架',14,21,4)
    zone(m,'wet','清洗剖切盐渍间',4,4,11,23,'入口卸料、分台清洗剖切、后部盐桶及干盐')
    zone(m,'dry','隔门干燥与成品间',13,4,18,23,'两列挂架及后成品柜；干货绕行独立于湿台')
    return finish(m,'窄湿作业长间与隔门干燥间并排，原鱼、清洗、剖切、盐渍、挂干及成品有完整小批处理顺序。')


def salt_court():
    m=workhome('NS-03-v02','围晒院盐鱼坊',33,31)
    lodge(m,3,3,13,26);lodge(m,21,3,29,14);lodge(m,21,20,29,26,height=4)
    entry(m,'entry',8,3);entry(m,'stockentry',25,3);entry(m,'doneentry',25,20)
    fishline(m,4,5,'fish');stores(m,'pack','空桶、包材及盐料',4,23,7)
    stores(m,'salt','干盐架',22,5,6);counter(m,'record','收料称验与批次登记',22,10,6,block='cartography_table')
    stores(m,'done','包装成品分批保管',22,22,6)
    canopy(m,15,6,19,24);drying(m,'dryA',15,9,5);drying(m,'dryB',15,16,5)
    zone(m,'wet','长条湿加工坊',4,4,12,25,'清洗、剖切、盐渍连续排布，末端包材')
    zone(m,'raw','干盐与登记屋',22,4,28,13,'原料干存及批次验收')
    zone(m,'done','独立成品屋',22,21,28,25,'盐魚分批包装后存放')
    zone(m,'dry','有顶通风挂晒院',15,7,19,23,'两排架间留工作过道，湿作业与成品屋之间转运')
    return finish(m,'湿坊、干盐登记屋和成品库围住有顶通风挂晒院，盐料与成品分屋保管，中院提供从加工到包装的转运路线。')


def salt_shop():
    m=workhome('NS-03-v03','前售后制盐鱼铺',27,29)
    lodge(m,3,3,23,25);entry(m,'entry',13,3)
    partition(m,4,12,22,12,20,12)
    counter(m,'sales','盐鱼验货、分切与交付柜',5,8,10,block='barrel[facing=south,open=false]')
    stores(m,'display','不同批次成品展示',5,5,7);bench(m,17,3,6,4)
    fishline(m,4,14,'fish');stores(m,'salt','盐料及包材',13,15,5)
    drying(m,'dry',14,20,7);stores(m,'done','待售成品',14,23,7)
    zone(m,'retail','顾客选货与交付前厅',4,4,22,11,'展示、验货长柜、等候座及侧后员工门')
    zone(m,'process','后场批次加工间',4,13,22,24,'左洗切盐渍，右干料和挂晒，成品末端出库')
    return finish(m,'宽前厅接待选购和分切交货，顾客不穿湿加工区；侧门通后场，左侧完整处理线与右侧干料挂晒形成回转。')


def salt_raise():
    m=workhome('NS-03-v04','错台风干盐坊',31,29)
    lodge(m,3,3,15,24);entry(m,'entry',9,3);fishline(m,4,5,'fish');stores(m,'salt','盐和包材',4,22,7)
    m.box((19,0,7),(27,4,24),'cobblestone');canopy(m,19,7,27,24,y=5)
    steps(m,19,4,3,2);m.box((19,4,6),(21,4,7),'spruce_planks');entry(m,'raised',20,7,y=5,wall=False)
    for z in (10,16):drying(m,'rack'+str(z),20,z,6,y=5)
    stores(m,'done','高台干燥成品',20,22,6,y=5)
    rail(m,19,8,19,24,5);rail(m,27,7,27,24,5);rail(m,19,24,27,24,5)
    zone(m,'wet','低台湿加工间',4,4,14,23,'低处集中卸鱼清洗和盐渍')
    zone(m,'dry','抬高通风干燥台',20,8,26,23,'两列干燥架、成品柜，三边护边',y=5)
    m.meta['floors']=[dict(name='低台湿作业',y=2,max_y=6),dict(name='高台风干',y=4,max_y=8)]
    m.meta['terrain']['高程']='湿坊脚底Y=3，干燥台Y=5；三宽双级阶连接，两级边沿需要完整保留。'
    return finish(m,'湿加工留在低台，干燥架抬高两格置于有顶通风台，独立台阶和三边护边避免潮湿卸料与干货转运混杂。')


BUILDERS={'NS-03-v01':salt_small,'NS-03-v02':salt_court,'NS-03-v03':salt_shop,'NS-03-v04':salt_raise}


def net_small():
    m=workhome('NS-07-v01','分区长网织修坊',23,29)
    lodge(m,3,3,19,25);entry(m,'entry',11,3)
    counter(m,'receive','破网验收与修补登记',4,5,6,block='cartography_table')
    stores(m,'fiber','干燥纤维、绳索与浮子',13,5,5)
    for z in (10,15):counter(m,'weave'+str(z),'铺网编织与结点修复长台',5,z,9,block='loom[facing=south]')
    drying(m,'dry',5,20,7);stores(m,'done','尺寸分组的成网',13,23,5)
    zone(m,'front','验收与材料前室',4,4,18,8,'收取旧网、登记修补和材料分拣')
    zone(m,'work','双工作长台',4,9,18,18,'两条工作台之间留完整立位和搬运通路')
    zone(m,'back','后部晾网及交付',4,19,18,24,'挂架检查修补结点后入成网架')
    return finish(m,'长坊按前验收备料、中央双长台、后晾网和成品分段，长台容纳铺展网幅并保留两侧操作面。')


def net_open():
    m=workhome('NS-07-v02','双跨开敞修网棚',31,29)
    lodge(m,3,3,12,24);entry(m,'entry',8,3)
    stores(m,'fiber','干纤维绳索',4,5,7);counter(m,'tools','小件制作及修网工具',4,11,6,block='loom[facing=south]')
    stores(m,'done','已修净网',4,21,7)
    canopy(m,16,4,27,24)
    for z in (8,17):
        counter(m,'table'+str(z),'宽棚铺网、梭具和结绳',17,z,8,block='loom[facing=south]')
        drying(m,'rack'+str(z),17,z+3,9)
    entry(m,'shed',21,4,wall=False)
    zone(m,'dry','封闭工具与净网屋',4,4,11,23,'绳料、工具、小件制作和成网隔潮储存')
    zone(m,'open','两跨修网作业棚',17,5,26,23,'前后两套铺展台和悬挂检查架；侧边开放便于长网转运')
    return finish(m,'封闭干料净网屋并列宽跨开敞修网棚，两套铺网和悬挂复检工位分别工作，湿旧网不用进入干储屋。')


def net_retail():
    m=workhome('NS-07-v03','前售后织网具铺',25,31)
    lodge(m,3,3,21,27);entry(m,'entry',12,3)
    partition(m,4,12,20,12,18,12)
    counter(m,'sale','样网选型与尺寸订单',5,8,9,block='cartography_table')
    stores(m,'samples','钓线浮子与成网样品',5,5,6);bench(m,16,3,6,3)
    counter(m,'weave','订单铺网和修结长台',5,16,10,block='loom[facing=south]')
    stores(m,'fiber','按订单备好的材料',5,24,6);drying(m,'dry',13,23,6)
    zone(m,'retail','临街接单选购厅',4,4,20,11,'样品、订单柜及等候凳，后场从侧门到达')
    zone(m,'work','订单织修间',4,13,20,26,'横向长台、后材料架和成网吊架')
    return finish(m,'面街订单厅给顾客比较网样并登记尺寸，后部单长台适合铺展定制网幅，材料和完成网分靠后墙两侧。')


def net_elbow():
    m=workhome('NS-07-v04','折院湿网修缮坊',31,31)
    lodge(m,3,3,15,27);lodge(m,15,18,27,27,height=4)
    entry(m,'entry',9,3);m.door(15,3,22,facing='east')
    counter(m,'check','旧网验伤与分批登记',4,5,8,block='cartography_table')
    counter(m,'repair','补片织造与结绳台',4,12,8,block='loom[facing=south]')
    stores(m,'material','梭具、补片和绳料',4,24,8)
    stores(m,'finished','独立成网库',17,24,8);counter(m,'measure','修后尺度复验和打包',17,20,8)
    canopy(m,19,4,27,13);drying(m,'washrack',19,8,9)
    m.set(20,3,11,'water_cauldron[level=3]');use(m,'wash','归岸湿网清洗',20,11,21,11)
    zone(m,'repair','封闭检修长坊',4,4,14,26,'验伤、补织与材料储存按长度展开')
    zone(m,'stock','后折角复验成网间',16,19,26,26,'尺寸检查与独立成品存放')
    zone(m,'wet','独立有顶洗晾院',20,5,26,12,'湿网清洗和初步挂晾，不压进编织屋')
    return finish(m,'长检修坊折接后成网库，前侧院专门容纳湿网清洗与挂晾；干净修补材料、湿旧网和交付成网分区明确。')


def reserve_small():
    m=workhome('NS-08-v01','分仓紧凑越冬库',25,27)
    lodge(m,3,3,21,23);entry(m,'entry',12,3)
    partition(m,4,12,20,12,10,12);partition(m,12,13,12,22,12,18)
    counter(m,'check','物资称验与领用登记',4,5,6,block='cartography_table')
    stores(m,'food','粮食与盐藏品',4,9,7);stores(m,'food2','备用食物和饮水容器',14,5,6)
    stores(m,'tools','工具、灯具与修缮物资',4,15,7);stores(m,'clothes','被服和防寒用品',4,21,7)
    m.box((15,3,15),(18,4,19),'stripped_spruce_log[axis=z]');use(m,'wood','隔间干燥燃料垛',15,17,14,17,kind='storage')
    zone(m,'food','收发及食品前仓',4,4,20,11,'前部登记台与两组食品架')
    zone(m,'tools','后工具被服仓',4,13,11,22,'工具和被服分架储存')
    zone(m,'fuel','隔门燃料仓',13,13,20,22,'中部燃料垛四周可搬运，与食品间隔墙')
    return finish(m,'紧凑越冬仓把食品放前部、工具被服放后左、燃料放后右隔间，三类储物由登记台统一领用，燃料不混进粮架。')


def reserve_cells():
    m=workhome('NS-08-v02','四格独立门冬储院',35,29)
    for i,(x,z) in enumerate(((3,3),(21,3),(3,16),(21,16))):
        lodge(m,x,z,x+10,z+9,height=4);entry(m,'entry'+str(i),x+5,z)
        labels=('谷物盐藏食品','御寒织物被褥','金属工具与灯具','干柴木料燃料')
        if i<3:
            stores(m,'stock'+str(i),labels[i],x+1,z+3,8);stores(m,'reserve'+str(i),labels[i]+'储备',x+1,z+7,8)
        else:
            m.box((x+2,3,z+3),(x+7,4,z+6),'stripped_spruce_log[axis=x]');use(m,'fuel',labels[i],x+3,z+3,x+3,z+2,kind='storage')
        zone(m,'cell'+str(i),labels[i]+'分库',x+1,z+1,x+9,z+8,'独立库门和分类存放，保持中央存取走道')
    canopy(m,15,5,18,21);counter(m,'record','院内收发登记台',15,9,4,block='cartography_table')
    zone(m,'check','有顶收发廊',15,6,18,20,'四间分库外统一验收与登记')
    return finish(m,'四个带独立入口的短仓围绕中央收发廊，食品、被服、工具和燃料彻底分屋，冬季领用可直接到对应库门。')


def reserve_upper():
    m=workhome('NS-08-v03','双层干货与装备库',21,29)
    lodge(m,3,3,17,25,height=11);entry(m,'entry',10,3)
    m.box((4,8,4),(16,8,24),'spruce_planks');stairs(m,13,12);upper_windows(m,25)
    counter(m,'check','领用登记、检验和修补',4,5,7,block='cartography_table')
    for z in (10,16,22):stores(m,'tools'+str(z),'分批工具与修缮备件',4,z,7)
    for z in (5,11,20):stores(m,'food'+str(z),'密封干粮及冬衣储备',4,z,7,y=9)
    stores(m,'light','上层轻件备用品',11,23,5,y=9)
    zone(m,'tools','下层工具收发库',4,4,16,24,'重工具和常领用件在下层，登记与修补靠前')
    zone(m,'dry','上层轻质干货库',4,4,16,24,'密封食品和织物在高处隔潮，梯口周边保留通道',y=9)
    m.meta.update(roof_min_y=14,floors=[dict(name='装备收发',y=2,max_y=6),dict(name='轻质干储',y=8,max_y=12)])
    return finish(m,'窄地块两层储备：下层收发重工具，上层密封食品与织物，三排架保持横向取货带，内梯带完整梯井护栏。')


def reserve_cross():
    m=workhome('NS-08-v04','分屋卸货冬储场',35,31)
    lodge(m,3,3,15,26);lodge(m,23,3,31,15);canopy(m,20,19,31,26)
    entry(m,'foodentry',9,3);entry(m,'toolentry',27,3)
    counter(m,'receive','粮食验收与封包复查',4,5,8,block='cartography_table')
    for z in (11,17,23):stores(m,'food'+str(z),'批次密封食品',4,z,9)
    stores(m,'tools','工具和灯具',24,5,6);counter(m,'repair','备用工具保养',24,11,6,block='grindstone[face=floor,facing=south]')
    m.box((22,3,21),(29,4,24),'stripped_spruce_log[axis=x]');use(m,'wood','独立遮雪燃料垛',23,21,23,20,kind='storage')
    counter(m,'issue','院内配给分拣与交接',17,10,4,block='barrel[facing=south,open=false]')
    zone(m,'food','长条食品批次库',4,4,14,25,'验收后按批次入三排食品架')
    zone(m,'tool','独立装备小库',24,4,30,14,'工具储存与维护')
    zone(m,'fuel','露侧有顶燃料场',21,20,30,25,'木料与食品分开，通风防雪不模拟防火功能')
    zone(m,'yard','中间卸货与配给院',16,4,22,25,'分拣台旁留贯通搬运路线')
    return finish(m,'大宗粮库、工具保养屋和开放燃料棚围住卸货院，三种存储对湿度和搬运的不同要求以真实分屋和工作面表达。')


BUILDERS.update({f'NS-07-v{i+1:02}':f for i,f in enumerate((net_small,net_open,net_retail,net_elbow))})
BUILDERS.update({f'NS-08-v{i+1:02}':f for i,f in enumerate((reserve_small,reserve_cells,reserve_upper,reserve_cross))})


def inn_small():
    m=workhome('NS-09-v01','前食堂后四寝海员栈',25,31)
    lodge(m,3,3,21,27);entry(m,'entry',12,3)
    partition(m,4,16,20,16,17,16)
    cook(m,'cook',4,5);counter(m,'desk','入住登记与寄存收据',14,5,6,block='cartography_table')
    dining(m,6,11,8);stores(m,'bags','公共行李和航海装备',14,14,6)
    sleep(m,'guest',5,20,4);stores(m,'linen','清洁被褥和换洗衣物',5,25,7)
    zone(m,'food','登记与食堂前厅',4,4,20,15,'厨房、八格共桌和靠门登记，后侧装备架')
    zone(m,'sleep','隔门四床客寝',4,17,20,26,'每床私柜，后置清洁织物与走道')
    return finish(m,'小型海员栈用前食堂兼登记与后四床客寝组成完整接待，行李靠厅后，床边另有个人柜，湿装备不堆床旁。')


def inn_cells():
    m=workhome('NS-09-v02','四间侧寝长屋客栈',31,33)
    lodge(m,3,3,27,29,height=6);entry(m,'entry',15,3)
    cook(m,'cook',4,5);counter(m,'desk','接待、账册与钥匙',20,5,6,block='cartography_table')
    dining(m,11,12,7)
    for i,(x,z) in enumerate(((4,10),(21,10),(4,20),(21,20))):
        edge=x+5 if x==4 else x
        partition(m,edge,z-1,edge,z+7,edge,z+5)
        m.box((x,3,z-1),(x+5,6,z-1),'spruce_planks');m.box((x,3,z+7),(x+5,6,z+7),'spruce_planks')
        sleep(m,'guest'+str(i),x+1,z+2,1);stores(m,'bag'+str(i),'本间行李与被服',x+1,z+6,3)
        zone(m,'guest'+str(i),'独立单床客房',x+1,z,x+4,z+6,'单床、个人柜及行李架，门开向中廊')
    stores(m,'linen','公共清洁织物',11,26,7);counter(m,'tea','热饮与旅途补给',11,20,6,block='water_cauldron[level=3]')
    zone(m,'public','中廊共食与接待',10,4,20,28,'前登记厨房、中共桌和热饮台、末端清洁储备')
    return finish(m,'四间单床侧寝围住公共长廊，旅客独立休息和寄物，中部共有餐桌与热饮，前厨接待和后被服服务可贯通。')


def inn_upper():
    m=workhome('NS-09-v03','楼上客寝港街栈',21,29)
    lodge(m,3,3,17,25,height=11);entry(m,'entry',10,3)
    m.box((4,8,4),(16,8,24),'spruce_planks');stairs(m,13,12);upper_windows(m,25)
    cook(m,'cook',4,5);counter(m,'desk','前台登记与船讯',11,5,5,block='cartography_table')
    dining(m,5,11,6);stores(m,'gear','海员公共湿具寄存',4,21,7)
    partition(m,4,13,11,13,9,13,y=9)
    sleep(m,'front',5,7,2,y=9);sleep(m,'back',5,19,2,y=9)
    stores(m,'linen','上层被褥与换洗用品',10,23,5,y=9)
    zone(m,'public','街层接待食堂',4,4,16,24,'厨房、登记、共桌和后装备寄存，内梯靠侧')
    zone(m,'frontbed','上层前双寝间',4,4,11,12,'两床及私柜，隔门安静休息',y=9)
    zone(m,'backbed','上层后双寝与服务廊',4,14,16,24,'两床、清洁织物与楼梯落脚通路',y=9)
    m.meta.update(roof_min_y=14,floors=[dict(name='接待食堂',y=2,max_y=6),dict(name='四床客寝',y=8,max_y=12)])
    return finish(m,'狭窄港街地块把接待食堂和湿装备留街层，上层前后两组双寝隔门，楼梯落脚处连到服务廊而非穿床位。')


def inn_court():
    m=workhome('NS-09-v04','避风院分屋客栈',35,33)
    lodge(m,3,3,15,28);lodge(m,23,3,31,15);lodge(m,19,21,31,28,height=4)
    entry(m,'entry',9,3);entry(m,'kitchenentry',27,3);entry(m,'sleepentry',25,21)
    counter(m,'desk','登记、船期和寄物票',4,5,8,block='cartography_table')
    dining(m,5,12,8);stores(m,'gear','分格海员行李和雨具',4,24,9)
    cook(m,'cook',24,5);stores(m,'food','客栈粮食与餐具',24,12,6)
    sleep(m,'guest',20,25,3)
    canopy(m,18,7,20,17);counter(m,'wash','院廊洗漱与饮水',18,11,3,block='water_cauldron[level=3]')
    zone(m,'public','长食堂及登记寄物厅',4,4,14,27,'登记、公共餐桌、后部装备行李分区')
    zone(m,'cook','独立厨房',24,4,30,14,'厨房及食品避免穿客寝配送')
    zone(m,'sleep','后院三床客寝',20,22,30,27,'三床及个人柜，离街休息')
    zone(m,'wash','遮雪洗漱廊',18,8,20,16,'集中洗漱和饮水，院内通往独立客寝')
    return finish(m,'长食堂、独立炊事屋和后院客寝围出避风院，洗漱在院廊，海员登记寄物、吃饭和休息各有实际空间。')


BUILDERS.update({f'NS-09-v{i+1:02}':f for i,f in enumerate((inn_small,inn_cells,inn_upper,inn_court))})


def shop_food():
    m=workhome('NS-F01-v01','窄街热食与干粮铺',19,25)
    lodge(m,3,3,15,21);entry(m,'entry',9,3);partition(m,4,13,14,13,12,13)
    counter(m,'sale','干粮验货与热食交付',4,8,7,block='barrel[facing=south,open=false]')
    stores(m,'goods','旅行干粮及盐藏食品',4,5,7);bench(m,11,3,10,3)
    cook(m,'cook',4,15);stores(m,'raw','食材与清洁餐具',4,19,7)
    zone(m,'shop','窄铺售货厅',4,4,14,12,'食品架、交付柜及短等候凳')
    zone(m,'prep','隔门备餐间',4,14,14,20,'炊洗与食材储存，员工从侧门出菜')
    return finish(m,'窄食品铺前选货交付、后炊洗备餐，等候凳靠另一侧，旅行干粮和热食服务都有完整设施。')


def shop_cloth():
    m=workhome('NS-F01-v02','转角防寒衣物铺',27,25)
    lodge(m,3,3,15,21);lodge(m,15,12,23,21,height=4);entry(m,'entry',9,3);m.door(15,3,16,facing='east')
    stores(m,'display','防寒外衣与毯子展示',4,5,8);counter(m,'sale','试样、尺码登记与交付',4,10,8,block='cartography_table')
    partition(m,4,14,14,14,11,14);bench(m,5,3,18,4);stores(m,'try','试穿与个人衣物临放',10,19,4)
    counter(m,'sew','侧间裁衣与缝补台',17,14,5,block='loom[facing=south]');stores(m,'fabric','布匹、毛线与扣件',17,19,5)
    zone(m,'sales','衣物陈列与量身前厅',4,4,14,13,'成衣和防寒毯分类，柜面记录尺码')
    zone(m,'fitting','后部隔门试穿间',4,15,14,20,'座凳和随身衣物临放')
    zone(m,'sew','折角裁缝工间',16,13,22,20,'织补台与原材料，独立于试穿站位')
    return finish(m,'转角铺将销售、隔门试穿和侧翼裁缝分成三间，量身订单由侧工间完成，不用柜台象征整套衣物服务。')


def shop_fishing():
    m=workhome('NS-F01-v03','前售后修钓具铺',23,27)
    lodge(m,3,3,19,23);entry(m,'entry',11,3);partition(m,4,13,18,13,16,13)
    stores(m,'goods','钓线、浮子和钩组',4,5,9);counter(m,'sale','钓具选配与订单交付',4,9,8,block='barrel[facing=south,open=false]')
    counter(m,'repair','结线换钩与浮子维修',4,16,8,block='loom[facing=south]');stores(m,'parts','维修备料及待交货钓具',4,21,9)
    m.set(16,3,18,'water_cauldron[level=3]');use(m,'wash','旧钓具清洗盆',16,18,15,18)
    zone(m,'sale','前钓具选配厅',4,4,18,12,'分类展示、长柜验货及侧通道')
    zone(m,'repair','后钓具小修间',4,14,18,22,'结线维修、清洗与备料成品分柜')
    return finish(m,'钓具铺前厅服务配线选钩，后间有织结工作台与独立清洗盆，修理材料和待取钓具沿后墙分类存放。')


def shop_tools():
    m=workhome('NS-F01-v04','器具售修与侧卸货铺',29,25)
    lodge(m,3,3,17,21);entry(m,'entry',10,3)
    partition(m,4,12,16,12,14,12)
    stores(m,'goods','刀斧、手锤及船用小工具',4,5,9);counter(m,'sale','工具验货与领件柜',4,8,8,block='anvil[facing=north]')
    counter(m,'fix','拆修、铆紧与刃具保养',4,15,8,block='grindstone[face=floor,facing=south]')
    stores(m,'parts','五金、木柄与修妥器具',4,19,9)
    canopy(m,21,8,25,20);stores(m,'stock','成捆木柄与笨重备件',21,11,5);counter(m,'receive','侧棚验收与开箱',21,17,5)
    zone(m,'sale','工具陈列验货厅',4,4,16,11,'工具架与验货柜')
    zone(m,'fix','后修理间',4,13,16,20,'保养工作台及替换零件')
    zone(m,'yard','独立遮雪卸货棚',21,9,25,19,'笨重备件在外侧卸货清点')
    return finish(m,'工具铺区分顾客验货、后场维修和侧棚卸货，拆箱与长木柄不堵店内通道，保养台配置实际砂轮。')


def shop_general():
    m=workhome('NS-F01-v05','前店后住日用杂货铺',21,29)
    lodge(m,3,3,17,25);entry(m,'entry',10,3)
    partition(m,4,14,16,14,14,14)
    stores(m,'goodsA','灯具、器皿与细绳',4,5,6);stores(m,'goodsB','包材、肥皂与旅行小件',11,5,5)
    counter(m,'sale','杂货配单与找零柜',4,10,8,block='cartography_table')
    cook(m,'cook',4,16);sleep(m,'owner',12,20,1);stores(m,'linen','店主衣物',11,23,5)
    dining(m,5,22,4)
    zone(m,'sale','前杂货配单厅',4,4,16,13,'分类货架、配单柜和顾客绕行')
    zone(m,'home','后部完整店主生活',4,15,16,24,'炊洗、共桌、个人床柜及干衣')
    return finish(m,'杂货铺采用紧凑前店后住，店主后间具有完整炊食床柜而非象征床位，经营动线与生活区通过侧门隔开。')


def shop_charts():
    m=workhome('NS-F01-v06','避风前室海图文具铺',25,25)
    lodge(m,3,7,21,21);lodge(m,8,3,16,7,height=4);entry(m,'entry',12,3);m.door(12,3,7)
    bench(m,9,3,5,3);stores(m,'maps','航路海图与纸张保管',4,9,6)
    counter(m,'sale','海图选购、抄录与路线核对',11,11,8,block='cartography_table')
    counter(m,'copy','后部抄写装订工作台',4,17,8,block='lectern[facing=south,has_book=false,powered=false]')
    stores(m,'ink','墨料、笔具与空白纸',14,19,6)
    zone(m,'porch','短风斗等候间',9,4,15,6,'避风脱雪、坐等取图')
    zone(m,'shop','海图选择与校对厅',4,8,20,14,'分类海图、宽台铺图和路线核对')
    zone(m,'copy','后抄写装订区',4,15,20,20,'工作台及纸墨分柜，纸品远离入口湿具')
    return finish(m,'海图文具铺带独立短风斗，中心宽台铺图核对、后部抄写装订和纸墨干存，适合航民出航准备。')


def shop_barrels():
    m=workhome('NS-F01-v07','桶器铺与制柄小院',29,27)
    lodge(m,3,3,15,23);entry(m,'entry',9,3)
    counter(m,'sale','桶器尺寸检查和交付',4,7,8,block='barrel[facing=up,open=false]')
    stores(m,'goods','饮水桶、木盆与备用箍',4,5,8)
    partition(m,4,12,14,12,12,12)
    counter(m,'join','修桶、制柄及拼板工作台',4,16,8);stores(m,'parts','木楔、桶箍与待修器具',4,21,8)
    canopy(m,19,6,25,22);m.box((20,3,9),(23,4,16),'stripped_spruce_log[axis=z]');use(m,'timber','按长度风干木板',20,12,19,12,kind='storage')
    counter(m,'cut','棚内截料备件台',20,20,5,block='stonecutter[facing=south]')
    zone(m,'sales','桶器验货前厅',4,4,14,11,'器具展示、尺寸比较及交付')
    zone(m,'work','后桶器修配间',4,13,14,22,'拼板维修与配件分存')
    zone(m,'wood','侧院遮雪木料棚',20,7,24,21,'干板分垛和独立截料工作面')
    return finish(m,'桶盆与船用木器有独立验货厅、后修配间和侧院干板棚，长料从院内准备再入后工间，销售不承担粗加工。')


def shop_lights():
    m=workhome('NS-F01-v08','双屋油灯与航行杂具铺',31,25)
    lodge(m,3,3,15,21);lodge(m,21,7,27,21,height=4);entry(m,'entry',9,3);entry(m,'workentry',24,7)
    stores(m,'goods','油灯、灯芯和航行小件',4,5,8);counter(m,'sale','灯具检查与配货',4,10,8,block='lantern[hanging=false,waterlogged=false]')
    stores(m,'stock','封存备用灯和配套容器',4,18,8);bench(m,12,3,14,2)
    counter(m,'repair','灯芯剪配和灯罩修理',22,10,4);stores(m,'parts','分柜灯芯与金属备件',22,18,4)
    zone(m,'sale','独立油灯杂具店',4,4,14,20,'展示、检验交付柜、后储备和等候位置')
    zone(m,'repair','分屋灯具修理间',22,8,26,20,'维修台与配件分类；室内不放明火')
    return finish(m,'灯具销售屋和狭长修配屋分开，顾客可在交付柜检验成品，灯芯、灯罩与小金属件在独立维修间整理。')


BUILDERS.update({f'NS-F01-v{i+1:02}':f for i,f in enumerate((shop_food,shop_cloth,shop_fishing,shop_tools,shop_general,shop_charts,shop_barrels,shop_lights))})
