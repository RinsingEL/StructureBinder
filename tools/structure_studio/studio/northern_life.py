"""Twelve distinct cold-coast domestic plans, compact rooms and working hearths."""
from .northern_seafarers import base,lodge,entry,room,use,counter,stores as core_stores,sleep,cook,dining,steps,rail,finish
from .components import bench,shelf,pendant,window


def home(key,name,w,d,h=25):
    m=base(key,name,w,d,h,role='fill')
    m.meta['source']='tools/structure_studio/studio/northern_life.py:BUILDERS'
    return m


def stores(m,key,name,x,z,n=4,y=3):
    core_stores(m,key,name,x,z,n,y)
    if m.blocks.get((x,y,z+1),('minecraft:air',()))[0]!='minecraft:air':
        m.meta['points'][-1]['approach']=[x,y,z-1]


def partition(m,x0,z0,x1,z1,doorx,doorz,y=3,height=4):
    m.box((x0,y,z0),(x1,y+height-1,z1),'spruce_planks')
    m.door(doorx,y,doorz,facing='east' if x0==x1 else 'north')


def zone(m,key,name,x0,z0,x1,z1,purpose,y=3):room(m,key,name,(x0,y,z0),(x1,y+3,z1),purpose)


def hearth(m,x,z,key='hearth',y=3):
    m.set(x,y,z,'campfire[facing=south,lit=false,signal_fire=false,waterlogged=false]')
    for xx in (x-1,x+1):m.set(xx,y,z,'cobblestone')
    m.box((x-1,y+3,z),(x+1,y+3,z),'cobblestone')
    top=max(yy for (xx,yy,zz),b in m.blocks.items() if xx==x and zz==z and b[0]!='minecraft:air')+2
    m.box((x,y+4,z),(x,top,z),'cobblestone');m.set(x,top+1,z,'cobblestone_slab[type=bottom,waterlogged=false]')
    use(m,key,'封护共炉与灰渣清理',x,z,x,z+1,y)


def stairs(m,x,z,width=2,y=3,rise=6):
    m.box((x,y+rise-1,z),(x+width-1,y+rise+2,z+rise-1),'air')
    steps(m,x,z,width,rise,y)
    for xx in (x-1,x+width):
        for zz in range(z,z+rise):m.set(xx,y+rise,zz,'spruce_fence[east=false,west=false,north=true,south=true,waterlogged=false]')
    for xx in range(x-1,x+width+1):m.set(xx,y+rise,z-1,'spruce_fence[east=true,west=true,north=false,south=false,waterlogged=false]')
    m.set(x-1,y+rise,z-1,'spruce_fence[east=true,west=false,north=false,south=true,waterlogged=false]')
    m.set(x+width,y+rise,z-1,'spruce_fence[east=false,west=true,north=false,south=true,waterlogged=false]')


def upper_windows(m,back):
    for x in (3,17):
        for z in (6,back-4):window(m,(x,11,z),(x,12,z+1),axis='z')
    for z in (3,back):window(m,(9,11,z),(11,12,z),axis='x')


def short_longhouse():
    m=home('NS-05-v01','分寝短炉厅',19,25)
    lodge(m,3,3,15,21);entry(m,'entry',9,3)
    partition(m,4,14,14,14,9,14)
    sleep(m,'family',5,18,3);stores(m,'blankets','御寒被褥和冬衣',5,20,4)
    cook(m,'kitchen',4,5);dining(m,5,10,5)
    hearth(m,12,8);stores(m,'food','粮食及餐具',10,12,4)
    zone(m,'living','共炉炊食厅',4,4,14,13,'炊洗、对坐餐桌、独立封护炉及粮食')
    zone(m,'sleep','三床后寝间',4,15,14,20,'完整隔门卧室、床边个人柜与被褥架')
    return finish(m,'紧凑长屋按前炊食共炉、后隔门三床分区；餐桌与炉边各留通道，不把公共生活压进卧室。')


def paired_longhouse():
    m=home('NS-05-v02','双户并屋共前廊',31,25)
    for i,x in enumerate((3,17)):
        lodge(m,x,5,x+10,21);entry(m,f'entry{i}',x+5,5)
        partition(m,x+1,14,x+9,14,x+8,14)
        cook(m,f'cook{i}',x+1,7);dining(m,x+2,11,5)
        sleep(m,f'bed{i}',x+2,18,2);stores(m,f'linen{i}','家庭被褥与换季物品',x+1,20,4)
        zone(m,f'live{i}','独立家庭炊食厅',x+1,6,x+9,13,'每户独立炊洗、餐桌和入口')
        zone(m,f'sleep{i}','独立家庭双床寝间',x+1,15,x+9,20,'每户两床及个人用品，隔门保温')
    m.box((4,2,2),(26,2,4),'spruce_planks')
    for x in (4,13,17,26):m.box((x,3,2),(x,5,2),'dark_oak_log[axis=y]')
    m.box((4,6,2),(26,6,4),'spruce_slab[type=bottom,waterlogged=false]')
    m.meta['roof_min_y']=6
    return finish(m,'两户分别拥有入口、炊食和后寝，通过低前廊邻里相接；中间窄缝保持独立墙体，家庭生活不需穿过另一户。')


def communal_hearth():
    m=home('NS-05-v03','四寝围炉长厅',29,29,27)
    lodge(m,3,3,25,25,height=6);entry(m,'entry',14,3)
    # Four small sleeping cells flank an unobstructed shared central hall.
    for i,(x,z) in enumerate(((4,8),(18,8),(4,17),(18,17))):
        if x==4:partition(m,10,z-1,10,z+5,10,z+3)
        else:partition(m,18,z-1,18,z+5,18,z+3)
        m.box((x,3,z-1),(x+6,6,z-1),'spruce_planks')
        m.box((x,3,z+5),(x+6,6,z+5),'spruce_planks')
        sleep(m,f'cell{i}',x+1,z+2,1)
        stores(m,f'cellstore{i}','寝间个人被服',x+1,z+4,3)
        zone(m,f'cell{i}','围炉单寝间',x+1,z,x+5,z+4,'一床与独立被服储存，侧门接共厅')
    cook(m,'cook',5,5);stores(m,'publicfood','共用粮食餐具',19,5,5)
    hearth(m,14,13);bench(m,12,3,10,4)
    dining(m,12,21,4)
    zone(m,'shared','中央共炉餐叙厅',11,4,17,24,'前部炊食服务、中心封炉与长凳、后部共享餐桌')
    return finish(m,'四间单寝围绕细长公共厅，独立被服与门隔开休息；中央炉、前炊事和后共桌分段组织，适合小组共同越冬。')


def elbow_longhouse():
    m=home('NS-05-v04','折角炉厅家务院',29,29)
    lodge(m,3,3,24,13);lodge(m,3,13,13,25)
    entry(m,'entry',17,3);m.door(4,3,13)
    cook(m,'cook',4,5);dining(m,6,10,6);hearth(m,19,9)
    stores(m,'food','共厅干粮与器皿',18,5,5)
    sleep(m,'beds',5,18,2);stores(m,'bedlinen','后寝被褥及衣物',4,23,6)
    m.box((17,2,18),(24,2,24),'spruce_planks')
    for x in (17,24):
        for z in (18,24):m.box((x,3,z),(x,6,z),'dark_oak_log[axis=y]')
    m.box((17,7,18),(24,7,24),'spruce_slab[type=bottom,waterlogged=false]')
    counter(m,'mend','洗衣晾具与缝补工作台',18,20,5,block='loom[facing=south]')
    m.set(18,3,23,'water_cauldron[level=3]');use(m,'laundry','家务洗涤水盆',18,23,19,23)
    zone(m,'hall','横向共炉炊食厅',4,4,23,12,'炊食与共炉分边，宽入口面向前街')
    zone(m,'sleep','折角双床寝翼',4,14,12,24,'后部睡眠及衣物储存，不穿越洗衣院')
    zone(m,'chores','后院有顶家务台',18,19,23,23,'水盆、缝补及遮雪作业面')
    m.meta['roof_min_y']=7
    return finish(m,'横向共炉餐厅折接狭长寝翼，空出的后院布置有顶洗衣缝补台；家务水区独立于寝室，院内仍可周转。')


def court_longhouse():
    m=home('NS-05-v05','双翼围院共食屋',33,31)
    lodge(m,3,3,13,26);lodge(m,21,3,29,15);lodge(m,13,20,29,26,height=4)
    entry(m,'west_entry',8,3);entry(m,'east_entry',25,3)
    m.door(13,3,23,facing='east');m.door(24,3,20)
    sleep(m,'front',5,9,2);sleep(m,'rear',5,17,2)
    partition(m,4,12,12,12,11,12)
    stores(m,'sleepstore','全户换季衣物',4,24,6)
    cook(m,'cook',22,5);stores(m,'food','干粮、餐具与柴火',22,12,6)
    dining(m,17,23,8);hearth(m,16,16)
    zone(m,'sleep','四床分寝长翼',4,4,12,25,'前后两间双床，集中衣物保管')
    zone(m,'kitchen','独立炊事翼',22,4,28,14,'炊洗及家庭冬储食品')
    zone(m,'dining','后共食横厅',14,21,28,25,'八格长桌与对坐凳，连接庭院与寝翼')
    zone(m,'court','避风炉院',14,4,20,19,'保留中心炉和进出两翼的绕行路线')
    return finish(m,'长寝翼、独立厨房和后横向共食厅围成炉院；四床分前后寝间，八格共桌服务整户，烹饪噪声与睡眠分开。')


def two_storey_longhouse():
    m=home('NS-05-v06','阁寝双层炉厅',21,25,27)
    lodge(m,3,3,17,21,height=11);entry(m,'entry',10,3)
    m.box((4,8,4),(16,8,20),'spruce_planks');stairs(m,13,9)
    upper_windows(m,21)
    cook(m,'cook',4,5);dining(m,5,11,5);hearth(m,7,17)
    stores(m,'food','粮食与餐具',10,19,5)
    sleep(m,'upperA',5,7,2,y=9);sleep(m,'upperB',5,15,2,y=9)
    partition(m,4,11,11,11,9,11,y=9)
    counter(m,'mending','寝阁缝补与个人整理台',5,10,3,y=9,block='loom[facing=south]')
    m.meta['points'][-1]['approach']=[8,9,10]
    stores(m,'upperlinen','阁寝冬衣与被服',5,19,5,y=9)
    zone(m,'lower','下层共炉炊食',4,4,16,20,'炊洗、共桌、后炉和食品，楼梯边保留运物通道')
    zone(m,'upper','上层四床寝阁',4,4,16,20,'两组双床，衣物架及完整U形梯井护栏',y=9)
    m.meta.update(roof_min_y=14,floors=[dict(name='共炉生活层',y=2,max_y=6),dict(name='四床寝阁',y=8,max_y=12)])
    return finish(m,'窄地块将公共共炉炊食留在下层，四床与被服转至上层；六级双宽内梯连接，梯井三边护栏显式补齐转角连接。')


def fisher_entryroom():
    m=home('NS-06-v01','前网后居渔户宅',21,28)
    lodge(m,3,3,17,24);entry(m,'entry',10,3)
    partition(m,4,10,16,10,10,10);partition(m,4,18,16,18,10,18)
    counter(m,'net','进屋后网具清理与补钩台',4,5,6,block='loom[facing=south]')
    stores(m,'gear','干网绳索与雨具',12,5,4)
    cook(m,'cook',4,12);dining(m,10,14,5)
    sleep(m,'beds',5,21,3);stores(m,'linen','干衣和被褥',11,23,5)
    zone(m,'gear','前部湿具风斗',4,4,16,9,'渔网维修、绳索及雨具在生活区之前处理')
    zone(m,'living','中部炊食间',4,11,16,17,'炊洗和对坐餐桌，不与湿网混用')
    zone(m,'sleep','后部三床寝间',4,19,16,23,'完整隔门卧室，个人柜与被服')
    return finish(m,'狭长渔宅顺序分为前网具风斗、中炊食、后三床寝间；两道门隔湿气，网具和家庭织物分柜保管。')


def fisher_split():
    m=home('NS-06-v02','坡岸前作后居宅',27,31)
    lodge(m,3,3,21,12);entry(m,'entry',12,3)
    m.box((3,0,18),(22,4,27),'cobblestone');lodge(m,4,18,21,27,y=4)
    steps(m,11,15,3,2);m.box((11,4,17),(13,4,18),'spruce_planks');m.door(12,5,18)
    m.box((11,3,12),(13,5,12),'air')
    counter(m,'repair','钓具小修与船件分拣',4,5,6)
    stores(m,'gear','网具与绳索',14,5,6);counter(m,'clean','归岸清洗台',4,10,5,block='water_cauldron[level=3]')
    cook(m,'cook',5,20,y=5);sleep(m,'beds',15,23,2,y=5)
    partition(m,13,19,13,26,13,23,y=5)
    dining(m,6,24,5,y=5)
    zone(m,'gear','低岸生产间',4,4,20,11,'湿具清洗、小修和干绳分别布置')
    zone(m,'living','后高台炊食间',5,19,12,26,'后抬高两格隔潮，独立炊洗共桌',y=5)
    zone(m,'sleep','后高台双床寝间',14,19,20,26,'睡眠与被服远离低岸湿具',y=5)
    m.meta['floors']=[dict(name='低岸作业',y=2,max_y=6),dict(name='高台家居',y=4,max_y=8)]
    m.meta['preview_context']=dict(kind='slope',land_surface_y=3,slope_origin_z=13,run=8,rise=1,padding=4,surface='snow')
    m.meta['terrain']['高程']='前部作业脚底Y=3，后部生活脚底Y=5，双级三宽石阶连接；需匹配前低后高岸坡。'
    return finish(m,'湿具处理与小修留在低岸前屋，家居抬高两格，后屋再隔炊食与双床寝间；中央阶院连接两屋，防止湿具穿越卧室。')


def fisher_duplex():
    m=home('NS-06-v03','双户共网院',33,29)
    for i,x in enumerate((3,19)):
        lodge(m,x,3,x+10,23);entry(m,f'entry{i}',x+5,3)
        partition(m,x+1,15,x+9,15,x+5,15)
        cook(m,f'cook{i}',x+1,5);dining(m,x+2,11,5)
        sleep(m,f'beds{i}',x+2,19,2);stores(m,f'linen{i}','本户衣物储存',x+1,22,5)
        zone(m,f'life{i}','独立家庭炊食间',x+1,4,x+9,14,'每户自行炊食与接待')
        zone(m,f'sleep{i}','独立家庭双寝',x+1,16,x+9,22,'隔门睡眠和私人衣柜')
    counter(m,'net','两户共用补网台',15,11,3,block='loom[facing=south]')
    stores(m,'rope','公用绳索浮子',14,20,4)
    zone(m,'court','窄共网院',14,4,18,22,'两户共同使用的补网和公用绳索，主通道在台旁')
    return finish(m,'两户各自完整炊食双寝，面对中间共网院；渔业工具共享，家庭床柜和厨房不共享，形成真实双户布局。')


def fisher_sidework():
    m=home('NS-06-v04','侧网房折角渔宅',29,27)
    lodge(m,3,3,15,23);lodge(m,15,13,25,23,height=4)
    entry(m,'entry',9,3);m.door(15,3,18,facing='east')
    partition(m,4,14,14,14,9,14)
    cook(m,'cook',4,5);dining(m,5,10,6)
    sleep(m,'beds',5,18,2);stores(m,'linen','家庭干衣和被褥',4,21,6)
    counter(m,'net','侧房整网和结绳工作台',17,15,6,block='loom[facing=south]')
    stores(m,'netstore','按用途保管网具',17,21,6)
    m.set(23,3,18,'water_cauldron[level=3]');use(m,'wash','湿具冲洗',23,18,22,18)
    zone(m,'life','家庭炊食前厅',4,4,14,13,'厨房及对坐餐桌')
    zone(m,'sleep','家庭后寝',4,15,14,22,'双床、私人柜及干燥被服')
    zone(m,'net','折角专业网具间',16,14,24,22,'整网结绳、分类网柜与冲洗，隔门接家庭后廊')
    return finish(m,'前炊食后双寝构成紧凑住宅，折角侧房单独容纳整网、网柜和冲洗；生产屋和卧室分别有完整功能边界。')


def fisher_upper():
    m=home('NS-06-v05','下网厅上家居宅',21,27,27)
    lodge(m,3,3,17,23,height=11);entry(m,'entry',10,3)
    m.box((4,8,4),(16,8,22),'spruce_planks');stairs(m,13,11)
    upper_windows(m,23)
    counter(m,'net','长网铺展与补结台',5,8,6,block='loom[facing=south]')
    stores(m,'gear','绳索浮子与小工具',4,5,6);stores(m,'finished','已修网具',4,20,6)
    m.set(11,3,20,'water_cauldron[level=3]');use(m,'wash','网具冲洗',11,20,11,19)
    cook(m,'cook',4,5,y=9);dining(m,5,10,5,y=9)
    partition(m,4,15,11,15,8,15,y=9)
    sleep(m,'beds',5,19,2,y=9);stores(m,'linen','家庭干衣',10,21,5,y=9)
    zone(m,'net','下层专业网厅',4,4,16,22,'铺网、补结、冲洗及干湿分柜，楼梯到家居')
    zone(m,'family','上层完整家居',4,4,16,22,'厨房、餐桌、隔屏双床与干衣保管',y=9)
    m.meta.update(roof_min_y=14,floors=[dict(name='渔具作业层',y=2,max_y=6),dict(name='家庭生活层',y=8,max_y=12)])
    return finish(m,'临岸窄地块采用下层完整网厅、上层家居；上层仍有独立炊食和隔屏双床，内梯与封护梯井将湿作业和干生活分层。')


def fisher_court():
    m=home('NS-06-v06','围晒院分屋渔宅',31,31)
    lodge(m,3,3,13,26);lodge(m,19,3,27,14);lodge(m,19,20,27,26,height=4)
    entry(m,'entry',8,3);entry(m,'work_entry',23,3);entry(m,'store_entry',23,20)
    partition(m,4,15,12,15,8,15)
    cook(m,'cook',4,5);dining(m,5,11,5);sleep(m,'beds',5,20,2);stores(m,'linen','家庭被服',4,24,6)
    counter(m,'repair','独立渔具维修屋',20,5,6,block='loom[facing=south]')
    stores(m,'tools','工具与修网材料',20,11,6)
    stores(m,'finished','干燥成网与备用绳',20,22,6)
    m.box((15,3,16),(15,6,16),'dark_oak_log[axis=y]');m.box((25,3,16),(25,6,16),'dark_oak_log[axis=y]')
    m.box((15,6,16),(25,6,16),'spruce_log[axis=x]')
    for x in (17,19,21,23):m.box((x,4,16),(x,5,16),'iron_bars[east=true,west=true,north=false,south=false,waterlogged=false]')
    use(m,'dry','避风院晾网架',19,16,19,17)
    zone(m,'home','完整双床家庭长屋',4,4,12,25,'前部炊食与后部独立双寝')
    zone(m,'repair','小型渔具修缮屋',20,4,26,13,'补网工具台、材料及小件')
    zone(m,'stock','干燥成网库',20,21,26,25,'与湿网院分离的成品储存')
    zone(m,'dry','开敞晾网院',14,15,26,19,'横向晾网架和院内通路')
    return finish(m,'完整家庭长屋、独立修具屋和后部干网库围出晾网院；湿网先在中院晾晒再入后库，生产流线不穿家庭寝室。')


BUILDERS={f'NS-05-v{i+1:02}':f for i,f in enumerate((short_longhouse,paired_longhouse,communal_hearth,elbow_longhouse,court_longhouse,two_storey_longhouse))}
BUILDERS.update({f'NS-06-v{i+1:02}':f for i,f in enumerate((fisher_entryroom,fisher_split,fisher_duplex,fisher_sidework,fisher_upper,fisher_court))})
