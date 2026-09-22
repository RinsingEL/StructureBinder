"""Cliff inns, cantilever dwellings and crew residences: independent plans."""
from functools import partial
from .cloud_navigation import base,platform,lodge,enter,desk,stairs_z
from .components import shelf,bench,column,hip_roof,pendant
from .samples import railing


def kitchen(m,key,x,y,z):
    # A continuous five-block worktop; cooking, washing and preparation stay adjacent.
    for dx,block in enumerate(['smoker[facing=south]','crafting_table','water_cauldron[level=3]','birch_planks','barrel[facing=south]']):
        m.set(x+dx,y,z,block)
    m.set(x+3,y+1,z,'flower_pot')
    m.box((x,y+1,z),(x,min(y+13,m.size[1]-1),z),'stone_bricks')
    m.box((x+1,y+3,z),(x+4,y+3,z),'birch_slab[type=bottom]')
    m.set(x+4,y+2,z,'barrel[facing=south]')
    m.point(key,'work',(x+1,y,z),'炉灶—洗涤—切配连续操作台',approach=(x+1,y,z+1))


def table(m,x,y,z,w=5):
    # Two-sided seats make a dining group rather than a lone bench.
    m.box((x,y,z),(x+w-1,y,z),'birch_slab[type=top]')
    m.box((x,y+1,z),(x+w-1,y+1,z),'cyan_carpet')
    for dx in range(0,w,2):
        m.set(x+dx,y,z-2,'birch_stairs[facing=south]')
        m.set(x+dx,y,z+2,'birch_stairs[facing=north]')


def beds(m,key,x,y,z,count=2):
    for i in range(count):
        xx=x+i*3
        m.bed(xx,y,z,'light_blue','north')
        m.set(xx-1,y,z-1,'barrel[facing=east]')
        m.set(xx-1,y+1,z-1,'lantern[hanging=false]')
        m.set(xx-1,y,z,'barrel[facing=east]')
        # Short head-end partitions leave the bedside and foot route open.
        m.box((xx-1,y+1,z-2),(xx-1,y+2,z-2),'birch_planks')
        m.point(f'{key}_{i+1}','sleep',(xx,y,z),'独立床位、床头灯与个人衣物箱',approach=(xx+1,y,z))


def sitting(m,key,x,z,f=7):
    m.set(x,f+1,z,'birch_stairs[facing=west]')
    m.set(x,f+1,z+2,'birch_stairs[facing=west]')
    m.set(x-2,f+1,z+1,'birch_slab[type=top]')
    m.set(x-2,f+2,z+1,'lantern[hanging=false]')
    m.box((x+1,f+1,z),(x+1,f+2,z+2),'bookshelf')
    m.box((x-1,f+1,z),(x-1,f+1,z+2),'light_gray_carpet')
    m.point(key,'work',(x+1,f+2,z+1),'共用坐读区与书物柜',approach=(x,f+1,z+1))


def welcome(m,key,x,z,f=7):
    bench(m,x,f+1,z,3,'north','birch')
    for dx in (0,2):m.set(x+dx,f+1,z+3,'barrel[facing=north]')
    m.set(x+1,f+1,z+3,'birch_planks')
    m.set(x+1,f+2,z+3,'lantern[hanging=false]')
    m.point(key,'storage',(x,f+1,z+3),'等候坐席与行李寄存箱',approach=(x,f+1,z+2))


def start(family,v,name,size,f=7):
    m=base(f'{family}-v{v:02d}',name,size,f=f,role='fill')
    m.meta['source']=f'tools/structure_studio/studio/cloud_living.py:{family}({v})'
    return m


def sleep_room(m,key,x0,z0,x1,z1,f=7,count=2,door=None):
    lodge(m,x0,z0,x1,z1,f=f)
    if door is None:door=((x0+x1)//2,z0,'north')
    m.door(door[0],f+1,door[1],'birch',door[2])
    beds(m,key,x0+2,f+1,z1-2,count)
    shelf(m,x0+1,f+1,z0+1,min(5,x1-x0-2),'birch')
    m.set(x1-1,f+1,z0+2,'water_cauldron[level=3]')
    desk(m,f'{key}_table',x0+2,f+1,z0+4,'住客书写与行李整理',min(4,x1-x0-5))
    sitting(m,key+'_read',x1-2,z0+2,f)
    m.room(key+'_read','寝室前部书写坐读',(x0+1,f+1,z0+2),(x1-1,f+5,z0+6),'写字台、双坐席、书柜与个人行李分区')
    m.room(key,'独立住宿房',(x0+1,f+1,z0+1),(x1-1,f+6,z1-1),'每床配床头灯、个人储物箱与短隐私屏；前部书写、坐读和饮水，保留侧面通道')


def dining(m,key,x0,z0,x1,z1,f=7,door=None):
    lodge(m,x0,z0,x1,z1,f=f)
    if door is None:door=((x0+x1)//2,z0,'north')
    m.door(door[0],f+1,door[1],'birch',door[2])
    kitchen(m,key+'_cook',x0+2,f+1,z0+(2 if x1-x0<=11 else 1))
    table_z=z0+(4 if z1-z0<=10 else 6)
    w=min(8,x1-x0-4);table(m,x0+2,f+1,table_z,w)
    shelf(m,x0+1,f+1,z1-1,min(7,x1-x0-2),'birch')
    if z1-z0>=16:sitting(m,key+'_read',x1-2,z1-6,f)
    m.point(key+'_meal','work',(x0+3,f+1,table_z),'公共餐桌',approach=(x0+3,f+1,table_z-1))
    m.room(key,'公共厨房与餐厅',(x0+1,f+1,z0+1),(x1-1,f+6,z1-1),'炉灶、饮水、食品柜、共餐和回收餐具')


def apartment(m,key,x0,z0,x1,z1,*,f=7,count=2,side=None):
    lodge(m,x0,z0,x1,z1,f=f,gable=(x1-x0<=14))
    mid=z1-7
    m.box((x0+1,f+1,mid),(x1-1,f+6,mid),'white_terracotta')
    m.door((x0+x1)//2,f+1,mid,'birch','south')
    m.door((x0+x1)//2,f+1,z0,'birch','north')
    if side:m.door(side[0],f+1,side[1],'birch',side[2])
    kitchen(m,key+'_cook',x0+2,f+1,z0+1);table(m,x0+2,f+1,z0+5,min(6,x1-x0-4))
    sitting(m,key+'_read',x1-2,z0+8,f)
    m.room(key+'_read','窗边坐读与家用书柜',(x1-5,f+1,z0+7),(x1-1,f+5,z0+11),'双坐席、茶几、照明与书物柜')
    beds(m,key,x0+2,f+1,z1-2,count);shelf(m,x0+1,f+1,mid+1,min(3,x1-x0-2),'birch')
    desk(m,key+'_dress',x1-4,f+1,mid+2,'卧室梳写台与衣物整理',3,block='birch_planks')
    m.set(x1-3,f+1,mid+4,'birch_stairs[facing=north]')
    m.room(key+'_living','家庭共餐起居',(x0+1,f+1,z0+1),(x1-1,f+6,mid-1),'连续厨房操作台、食品与餐具柜、双侧用餐坐席、窗边坐读和连续入户通道')
    m.room(key+'_sleep','家庭独立卧室',(x0+1,f+1,mid+1),(x1-1,f+6,z1-1),'分隔卧室、每床床头灯、隐私短屏与个人衣物收纳')


def stair(m,x,z,f,rise=7,width=3):
    stairs_z(m,x,z,f+1,width,rise)
    for xx in (x-1,x+width):
        for i in range(rise):
            m.box((xx,f,z+i),(xx,f+1+i,z+i),'dark_oak_planks')
            m.box((xx,f+2+i,z+i),(xx,f+3+i,z+i),'dark_oak_fence[north=true,south=true,east=false,west=false]')


def balcony(m,x0,z0,x1,z1,f=7,name='观景阳台'):
    # Called on existing supported platforms; furniture stays off circulation lines.
    bench(m,x0+2,f+1,z1-2,min(6,x1-x0-4),'north','birch')
    m.point('balcony','circulation',((x0+x1)//2,f+1,z0+1),name,look_at=[(x0+x1)//2,f+2,z1+1])
    m.room('balcony',name,(x0+1,f+1,z0),(x1-1,f+5,z1-1),'完整栏杆、坐席及回到地面入口的连续路线')


def upper_entry(m,x,z,f=14):
    m.box((x+1,f+1,z-1),(x+1,f+3,z+1),'air')
    # Lower stove vents laterally before meeting the floor above, then up an exterior flue.
    m.box((5,12,8),(8,12,8),'stone_bricks');m.box((5,8,8),(5,29,8),'stone_bricks')


def home(v):
    names=['短挑双床宅','并门双户宅','折廊工居宅','叠层望谷宅','分台连桥宅','长脊峡口宅']
    sizes=[(29,31,37),(41,31,37),(45,33,43),(35,39,37),(47,37,39),(31,35,47)]
    m=start('CN-05',v,names[v-1]+' · 悬臂住宅',sizes[v-1]);w,_,d=m.size
    platform(m,3,2,w-4,d-4)
    if v==1:
        apartment(m,'home',7,7,21,27,count=2,side=(14,27,'south'));balcony(m,4,29,24,33)
        note='单户南向短挑阳台；前段厨房起居、后段独立双床卧室，入口落在北侧实体锚座。'
    elif v==2:
        apartment(m,'west',6,7,17,27,count=2,side=(17,19,'east'));apartment(m,'east',23,7,34,27,count=2,side=(23,19,'west'))
        balcony(m,4,29,36,33,name='双户共用观景廊');note='两套各有厨房的独立家庭住宅，以中间巷道和后部公共阳台连接；不共享卧室。'
    elif v==3:
        apartment(m,'home',6,7,20,33,count=3,side=(20,19,'east'))
        lodge(m,25,7,38,20);m.door(31,8,7,'birch','north');m.door(25,8,16,'birch','west')
        desk(m,'craft',27,8,12,'家庭旅具修补桌',7,block='crafting_table');shelf(m,26,8,19,8,'birch')
        m.room('craft','独立居家工房',(26,8,8),(37,13,19),'旅具修补、工具与成品收纳')
        for x in (23,38):column(m,x,28,8,16,'stripped_dark_oak_log','dark_oak_planks')
        hip_roof(m,22,39,25,31,15,material='waxed_cut_copper',tiers=3)
        balcony(m,22,31,39,39,name='折廊内庭阳台');note='L形工居宅：西侧居住长翼、东北独立修补工房与半围合观景内庭。'
    elif v==4:
        dining(m,'living',6,7,22,27)
        sleep_room(m,'upper',6,7,22,27,f=14,count=4,door=(22,17,'east'))
        upper_entry(m,22,17)
        stair(m,26,8,7);m.box((23,14,15),(29,14,20),'birch_planks')
        railing(m,(29,15,15),(29,15,20),'dark_oak','z');railing(m,(23,15,20),(29,15,20),'dark_oak','x')
        railing(m,(23,15,15),(25,15,15),'dark_oak','x')
        m.box((29,14,15),(29,14,20),'dark_oak_log[axis=z]')
        m.point('landing','circulation',(25,15,18),'二层外廊入口',look_at=[22,16,17]);balcony(m,4,29,29,33)
        m.meta['floors']=[dict(name='地面起居',y=7,max_y=13),dict(name='二层卧室',y=14,max_y=20)]
        note='两层紧凑住宅以外置七级楼梯到达四床睡眠层；地面厨房和共餐空间完整，后部留观景短廊。'
    elif v==5:
        apartment(m,'low',6,7,21,29,count=2,side=(21,19,'east'))
        platform(m,27,9,42,31,11)
        sleep_room(m,'high',29,11,40,27,f=11,count=2,door=(29,21,'west'))
        stair(m,23,15,7,rise=4);m.box((23,11,19),(28,11,23),'birch_planks')
        for z in (19,23):railing(m,(23,12,z),(28,12,z),'dark_oak','x')
        m.box((23,12,19),(25,14,19),'air')
        m.box((27,12,20),(27,14,22),'air')
        m.point('bridge','circulation',(25,12,21),'分台卧翼连接廊',look_at=[29,13,21]);m.meta['floors']=[dict(name='低台共餐与卧室',y=7,max_y=13),dict(name='高台客卧',y=11,max_y=17)]
        note='低台家庭生活与高四格的客卧分处两个实体固定端，短桥和外阶连接；高台独立石墩直落承载岩层。'
    else:
        apartment(m,'home',7,7,23,35,count=4,side=(15,35,'south'))
        m.box((6,0,31),(24,6,37),'stone_bricks')
        for x in (7,23):m.box((x,2,14),(x,6,30),'dark_oak_log[axis=z]')
        balcony(m,4,37,26,43,name='峡口长屋南廊');note='狭长峡口地块上的四床长屋，北锚座与南石台共同承托中段木梁；前段起居面积较大，南端集中卧室与观景。'
    enter(m,(w-1)//2,3);m.meta['design_notes']=[note,'所有悬挑构件都回接北岩台、石墩或对岸固定端；本体不提供浮空能力。'];m.meta['differences']=[note]
    return m


def dorm(v):
    names=['六床短廊舍','长排双寝舍','三翼共餐院','上下轮班舍','分台安静舍','对廊器材舍']
    sizes=[(37,33,39),(51,32,35),(49,33,49),(37,40,39),(51,38,41),(49,34,45)]
    m=start('CN-06',v,names[v-1]+' · 船员宿舍',sizes[v-1]);w,_,d=m.size;platform(m,3,2,w-4,d-4)
    if v==1:
        sleep_room(m,'sleep',6,7,29,20,count=6);dining(m,'mess',6,25,29,33,door=(18,25,'north'))
        note='六床集中在短寝室，后部独立窄餐厅服务同一班组，中间横向过道分开安静与共餐。'
    elif v==2:
        sleep_room(m,'west',6,7,20,25,count=4,door=(20,18,'east'));sleep_room(m,'east',28,7,43,25,count=4,door=(28,18,'west'))
        # A roofed central mess is open at north/south and has a continuous central lane.
        for x in (23,25):
            for z in (9,24):column(m,x,z,8,18,'stripped_dark_oak_log','dark_oak_planks')
        hip_roof(m,21,27,7,26,16,material='waxed_cut_copper',tiers=3)
        kitchen(m,'galley',8,8,29);table(m,30,8,28,9)
        for x in (7,20,31,43):column(m,x,31,8,16,'stripped_dark_oak_log','dark_oak_planks')
        hip_roof(m,5,45,26,32,15,material='waxed_cut_copper',tiers=3)
        m.room('mess','后廊共餐与备餐',(6,8,27),(44,13,30),'双寝翼共用的后廊厨房、餐桌和饮水')
        note='八床分成东西两间长寝室，中间是贯通带顶步廊；厨房与共餐位于后廊，避开睡眠区。'
    elif v==3:
        sleep_room(m,'west',6,8,18,28,count=3,door=(18,19,'east'));sleep_room(m,'east',30,8,42,28,count=3,door=(30,19,'west'))
        dining(m,'mess',12,33,36,42,door=(24,33,'north'))
        shelf(m,21,8,10,5,'birch');m.point('gear','storage',(22,9,10),'中央旅具存放架',approach=(22,8,11))
        m.room('court','三翼中庭',(20,8,13),(28,13,30),'班组集合、旅具和连接三翼的通路');note='三翼院落宿舍：双三床寝翼围合中庭，南侧公共食堂，中央存放旅具。'
    elif v==4:
        dining(m,'mess',6,7,22,28);sleep_room(m,'upper',6,7,22,28,f=14,count=4,door=(22,18,'east'))
        upper_entry(m,22,18)
        stair(m,27,9,7);m.box((23,14,16),(30,14,21),'birch_planks')
        for z in (16,21):railing(m,(23,15,z),(30,15,z),'dark_oak','x')
        m.box((26,15,16),(29,17,16),'air');railing(m,(30,15,16),(30,15,21),'dark_oak','z')
        m.meta['floors']=[dict(name='公共餐饮与旅具',y=7,max_y=13),dict(name='四床安静层',y=14,max_y=20)]
        note='轮值人员采用上下分层：楼下公共厨房和大型餐桌，楼上四床集中安静层，外阶独立进出。'
    elif v==5:
        dining(m,'mess',6,7,22,28,door=(22,18,'east'));platform(m,30,7,45,34,11)
        sleep_room(m,'upper',32,10,43,30,f=11,count=3,door=(32,20,'west'))
        stair(m,25,14,7,rise=4);m.box((25,11,18),(31,11,23),'birch_planks')
        for z in (18,23):railing(m,(25,12,z),(31,12,z),'dark_oak','x')
        m.box((25,12,18),(27,14,18),'air');m.box((30,12,19),(30,14,22),'air')
        m.point('link','circulation',(28,12,21),'餐饮与安静寝翼连接',look_at=[32,13,20]);m.meta['floors']=[dict(name='低台共餐',y=7,max_y=13),dict(name='高台安静寝翼',y=11,max_y=17)]
        note='餐厅落在低台，三床安静寝翼高四格独立落墩，带栏短桥分离生活噪声与休息。'
    else:
        sleep_room(m,'west',6,7,18,29,count=3,door=(18,19,'east'));sleep_room(m,'east',30,7,42,29,count=3,door=(30,19,'west'))
        dining(m,'mess',11,33,37,41,door=(24,33,'north'))
        for z in (12,24):
            shelf(m,21,8,z,5,'birch');m.point('gear_'+str(z),'storage',(22,9,z),'值班旅具分类架',approach=(22,8,z+1))
        m.room('gear','中央开放旅具廊',(20,8,8),(28,13,30),'前后分组的行装收纳与宽敞集合通道');note='对廊宿舍为两间三床长翼，中央露天集合廊容纳两组旅具柜；后端独立食堂。'
    enter(m,(w-1)//2,3);m.meta['design_notes']=[note,'宿舍需与泊塔、修造库及商铺形成连续步行网络；床位标记不表示已接入住民系统。'];m.meta['differences']=[note]
    return m


def inn(v):
    names=['四门围庭栈','长廊望谷栈','分台双院栈','叠阁悬崖栈']
    sizes=[(49,34,49),(57,34,39),(53,38,49),(43,40,45)]
    m=start('CN-04',v,names[v-1]+' · 悬崖旅舍',sizes[v-1]);w,_,d=m.size;platform(m,3,2,w-4,d-4)
    if v==1:
        for key,x,z,face in [('nw',6,7,'east'),('sw',6,26,'east'),('ne',32,7,'west'),('se',32,26,'west')]:
            sleep_room(m,key,x,z,x+10,z+13,count=2,door=(x+10 if face=='east' else x,z+7,face))
        dining(m,'mess',19,23,29,39,door=(24,23,'north'))
        desk(m,'reception',21,8,11,'旅舍接待与寄存',5);m.room('reception','中央接待庭',(18,8,8),(30,13,21),'入店登记、行李交接与四间客房分流')
        note='四间双床客房围绕中央接待庭，后部独立厨房餐厅；房门朝向内庭，外缘作为观景绕行廊。'
    elif v==2:
        for i,x in enumerate((6,19,32)):
            sleep_room(m,'guest'+str(i),x,7,x+10,22,count=2,door=(x+5,22,'south'))
        dining(m,'mess',43,7,51,29,door=(43,25,'west'))
        for x in (7,22,37):column(m,x,28,8,17,'stripped_dark_oak_log','dark_oak_planks')
        hip_roof(m,5,41,24,30,15,material='waxed_cut_copper',tiers=3)
        desk(m,'reception',7,8,31,'长廊旅客接待',6);m.room('gallery','长廊观景与接待',(6,8,24),(41,13,34),'三间双床房面向连续有顶走廊，末端接小餐厅')
        note='狭长岩沿布置三间双床客房，连续南向观景长廊承担入房、接待与休息，东端为小餐厅。'
    elif v==3:
        dining(m,'mess',6,7,23,27,door=(23,20,'east'));sleep_room(m,'low',6,32,23,42,count=4,door=(23,37,'east'))
        platform(m,32,8,47,43,11)
        for key,z in (('high_a',11),('high_b',28)):sleep_room(m,key,34,z,45,z+12,f=11,count=2,door=(34,z+6,'west'))
        stair(m,27,15,7,rise=4);m.box((27,11,19),(33,11,38),'birch_planks')
        railing(m,(27,12,23),(27,12,38),'dark_oak','z');railing(m,(27,12,38),(33,12,38),'dark_oak','x')
        m.box((32,12,20),(32,14,37),'air');desk(m,'reception',25,8,9,'分台旅舍登记',5)
        m.meta['floors']=[dict(name='低台餐厅与通铺房',y=7,max_y=13),dict(name='高台独立客房',y=11,max_y=17)]
        note='低台餐厅和四床通铺，高台两间双床安静客房；四级台阶与独立带栏廊道回应自然双岩台。'
    else:
        dining(m,'mess',6,7,24,30)
        sleep_room(m,'upper_a',6,7,24,17,f=14,count=4,door=(24,15,'east'))
        sleep_room(m,'upper_b',6,20,24,30,f=14,count=4,door=(24,25,'east'))
        upper_entry(m,24,15);upper_entry(m,24,25)
        stair(m,31,6,7);m.box((25,14,13),(35,14,31),'birch_planks')
        for x in (35,):railing(m,(x,15,13),(x,15,31),'dark_oak','z')
        railing(m,(25,15,13),(30,15,13),'dark_oak','x');railing(m,(34,15,13),(35,15,13),'dark_oak','x')
        railing(m,(25,15,18),(25,15,19),'dark_oak','z')
        railing(m,(25,15,31),(35,15,31),'dark_oak','x')
        for x in (26,34):
            for z in (15,29):column(m,x,z,8,13,'stripped_dark_oak_log','dark_oak_planks')
        desk(m,'reception',27,8,19,'旅舍前台与寄物',7);shelf(m,27,8,27,7,'birch')
        m.room('lobby','下层前台廊',(26,8,14),(34,13,30),'接待、寄物与楼梯分流')
        balcony(m,4,33,37,41,name='旅舍南向公共阳台')
        m.meta['floors']=[dict(name='餐厅与接待',y=7,max_y=13),dict(name='楼上双客房',y=14,max_y=20)]
        note='下层餐厅与有顶接待廊、上层两间四床客房，独立外阶接宽观景走廊，适合较窄但可建两层的悬崖台地。'
    for i,(x,z) in enumerate({1:[(20,16)],2:[(22,31)],3:[(25,32)],4:[(28,22)]}[v]):welcome(m,'luggage_'+str(i),x,z)
    enter(m,(w-1)//2,3);m.meta['design_notes']=[note,'旅舍主体支撑落在岩台/石墩，观景边缘持续封栏；仅在真实步行入口与补给条件满足时选用。'];m.meta['differences']=[note]
    return m


BUILDERS={**{f'CN-05-v{v:02d}':partial(home,v) for v in range(1,7)},**{f'CN-06-v{v:02d}':partial(dorm,v) for v in range(1,7)},**{f'CN-04-v{v:02d}':partial(inn,v) for v in range(1,5)}}
