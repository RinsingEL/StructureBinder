"""Distinct compact street, civic and farm-support buildings for the EU pool."""
from functools import partial
from .european_village import (base,room_shell,windows,entry,roof,cross_roof,stair,
                              bed,station,seat,hearth,light,awning,frontage)


def townhouse(v=1):
    w,d=7,13
    m=base(29,v,'连排街屋 · '+{1:'共墙中户',2:'山墙端户',3:'转角侧门户'}[v],w,d,20)
    b=(0,2,6,11)
    room_shell(m,b,name='街屋起居');room_shell(m,b,6,name='二层寝室')
    # Flush lateral walls, no side eaves on attached rows.
    cross_roof(m,b,11,'brick',clip_eaves=True)
    # Cross roof goes beyond x bounds: handled by the bounded proxy in village module.
    entry(m,2)
    stair(m,5,4)
    for x in (1,3):bed(m,x,7,9,f'bed_{x}')
    hearth(m,1,3);m.set(1,2,6,'barrel[facing=east]')
    windows(m,b,8)
    if v==1:
        for x in (0,6):
            m.box((x,2,3),(x,10,10),'white_terracotta')
            for y in (5,6,10):m.box((x,y,2),(x,y,11),'dark_oak_log[axis=z]')
    elif v==3:
        m.box((0,0,5),(0,1,7),'cobblestone')
        m.door(0,2,6,facing='east')
        m.meta['terrain']['选址']+=' 转角户侧入口需外部街面，主入口仍为正面。'
    light(m,3,5,6);light(m,3,10,6)
    m.meta.update(floors=[{'name':'起居','y':1,'max_y':5},{'name':'寝室','y':6,'max_y':10}],roof_min_y=11,
                  differences=['中户侧面实墙不侵邻地；端户开窗；转角户增加侧门。每户自有实体楼梯。'])
    return m


def shop_home(v=1):
    m=base(30,v,'下店上宅 · '+('杂货双层店屋' if v==1 else '布料裁衣店屋'),11,13,23,('零售','家庭居住'))
    b=(1,2,9,11);room_shell(m,b,name='临街店铺');room_shell(m,b,6,name='店主住处')
    windows(m,b);windows(m,b,8);roof(m,b,11);entry(m,5);stair(m,8,4)
    for x in (3,5):bed(m,x,7,9,f'bed_{x}')
    station(m,2,2,6,'barrel[facing=east]' if v==1 else 'loom[facing=east]','售卖理货' if v==1 else '裁衣售卖')
    for z in (4,8):m.set(2,2,z,'barrel[facing=east]' if v==1 else 'white_wool')
    m.box((2,3,2),(3,3,2),'glass_pane');awning(m,1,7,0)
    light(m,5,5,7);light(m,5,10,6)
    m.meta.update(floors=[{'name':'店铺','y':1,'max_y':5},{'name':'住房','y':6,'max_y':10}],roof_min_y=11,
                  differences=['楼下商品与操作台按行业改变，上层独立生活空间；窄面店屋。'])
    return m


def manor():
    m=base(31,1,'富商宅邸 · 石基门厅与三翼小院',21,23,25)
    # Three wings surround a genuine open court instead of one giant room.
    for b,name in (((1,2,7,20),'西翼服务住房'),((13,2,19,20),'东翼家庭住房'),((7,14,13,20),'后部会客厅')):
        room_shell(m,b,name=name);windows(m,b);roof(m,b,6)
    entry(m,4);entry(m,16)
    m.box((4,1,1),(16,1,1),'cobblestone')
    for x in (7,13):
        m.box((x,2,15),(x,3,17),'air')
    for x in (3,5,15,17):
        for z in (8,17):bed(m,x,2,z,f'bed_{x}_{z}')
    station(m,10,2,19,'lectern[facing=north]','管家账房',(10,2,18))
    for x in (4,16):
        for z in (5,13,19):light(m,x,5,z)
    light(m,10,5,17)
    m.box((8,1,5),(12,1,12),'gravel')
    m.box((8,1,6),(8,1,11),'moss_block');m.box((12,1,6),(12,1,11),'moss_block')
    for x in (8,12):
        for z in (6,11):m.set(x,2,z,'azalea')
    m.meta['differences']=['三翼与开放小庭院，主人和服务住房分翼；庭院没有巨大统一台基。']
    return m


def inn():
    m=base(32,1,'街角酒馆 · 横脊客房与侧厨房',15,19,24,('餐饮','饮酒会聚','旅宿'))
    b=(1,2,13,17);room_shell(m,b,name='酒馆大厅与厨房');room_shell(m,b,6,name='楼上客房')
    windows(m,b);windows(m,b,8);cross_roof(m,b,11,'stone_brick');entry(m,7);stair(m,12,4)
    for x in (3,5,7,9):bed(m,x,7,15,f'guest_{x}',use='public')
    for x in (3,5):bed(m,x,7,6,f'staff_{x}',use='staff')
    m.box((8,7,3),(8,9,8),'spruce_planks');m.box((8,7,7),(8,8,7),'air')
    station(m,2,2,5,'smoker[facing=east]','厨师');station(m,2,2,9,'barrel[facing=east]','酒保')
    for z in (5,8,12):
        seat(m,8,2,z,'west');m.set(6,2,z,'spruce_planks')
    for y in (5,10):
        for z in (5,11,15):light(m,6,y,z)
    awning(m,4,10,0)
    m.meta.update(floors=[{'name':'酒馆','y':1,'max_y':5},{'name':'客房','y':6,'max_y':10}],roof_min_y=11,
                  differences=['横向屋脊、宽售饮前厅与二层客房，员工床和旅人床分开。'])
    return m


def stall(v=1):
    m=base(34,v,'集市摊棚 · '+{1:'布料单面棚',2:'果蔬双开口',3:'陶器贴墙棚'}[v],7,7,10,('集市交易',))
    m.box((1,0,1),(5,1,5),'spruce_planks')
    for x in (1,5):
        for z in (1,5):m.box((x,2,z),(x,4,z),'spruce_fence')
    for x in range(7):m.box((x,5+(x%2),0),(x,5+(x%2),6),'white_wool' if x%2 else 'green_wool')
    m.box((2,2,2),(4,2,2),'spruce_planks')
    for x in (2,4):m.set(x,3,2,{1:'red_carpet',2:'melon',3:'decorated_pot'}[v])
    m.set(3,1,6,'spruce_planks');m.point('entry','entrance',(3,2,6),'摊主后侧入口')
    station(m,2,2,4,'barrel[facing=east]','摊主',(3,2,4))
    if v==3:m.box((1,2,5),(5,4,5),'spruce_planks');m.box((3,2,5),(3,3,5),'air')
    if v==2:
        m.set(5,2,3,'spruce_slab[type=top]');m.set(5,2,4,'spruce_slab[type=top]')
        m.set(5,1,2,'spruce_planks')
        m.point('side_customer','meeting',(5,2,2),'侧向售卖停留',approach=(5,2,2),use='public')
    light(m,3,4,4)
    m.meta.update(roof_min_y=5,floors=[{'name':'摊位','y':1,'max_y':4}],differences=['摊主后勤入口与前售卖面分开；商品和围合方式不同。'])
    return m


def clinic():
    m=base(35,1,'草药诊所 · 采光病室与药柜',11,15,19,('医疗','照料'))
    b=(1,2,9,13);room_shell(m,b,name='诊疗病室');windows(m,b);roof(m,b,6);entry(m,5)
    for x in (3,6):bed(m,x,2,11,f'care_{x}',use='care',color='white')
    station(m,2,2,5,'brewing_stand','药师诊疗');m.set(2,1,5,'spruce_planks')
    m.box((7,2,4),(8,2,6),'bookshelf');m.set(7,3,5,'potted_fern')
    seat(m,3,2,7);light(m,5,5,5);light(m,5,5,10)
    m.meta['differences']=['两床独立照料空间、药柜和候诊位；病床用途明确。']
    return m


def washing(v=1):
    w,d=(9,11) if v==1 else (15,17)
    m=base(36,v,'公共洗浴 · '+('开敞洗衣棚' if v==1 else '双池浴室与更衣间'),w,d,21,('洗衣',) if v==1 else ('公共沐浴',))
    b=(1,2,w-2,d-2);room_shell(m,b,name='洗衣操作区' if v==1 else '更衣与浴池');windows(m,b);cross_roof(m,b,6,'stone_brick');entry(m,w//2)
    if v==1:
        m.box((1,2,4),(1,4,7),'air')
        for z in (5,7):station(m,w-3,2,z,'water_cauldron[level=3]','洗衣操作',(w-4,2,z))
        m.box((2,4,5),(2,4,8),'oak_fence');m.set(2,3,6,'white_wool')
    else:
        for x in (3,9):
            m.box((x,1,9),(x+2,1,13),'stone_bricks')
            m.box((x,2,9),(x+2,2,13),'stone_bricks')
            m.box((x+1,2,10),(x+1,2,12),'water')
        station(m,2,2,5,'barrel[facing=east]','浴室管理员')
        for x in (9,11):seat(m,x,2,5,'south')
    for z in range(5,d-3,5):light(m,w//2,5,z)
    m.meta['differences']=['小洗衣棚与封闭浴室独立交付；水体仅静态，未模拟流体与洗浴行为。']
    return m


def sentry(v=1):
    m=base(37,v,'街口哨亭 · '+('开敞木亭' if v==1 else '石墙值守亭'),5,5,12,('驻守',))
    m.box((1,0,1),(3,1,3),'stone_bricks')
    for x in (1,3):
        for z in (1,3):m.box((x,2,z),(x,4,z),'oak_log')
    if v==2:
        for x in (1,3):m.box((x,2,1),(x,3,3),'stone_bricks')
        m.box((1,2,3),(3,3,3),'stone_bricks')
    roof(m,(1,1,3,3),5,'stone_brick');m.set(2,1,0,'stone_bricks')
    m.point('entry','entrance',(2,2,0),'值守亭入口');m.point('guard','work',(2,2,2),'守卫',approach=(2,2,2),use='staff')
    light(m,2,4,2);m.meta.update(roof_min_y=5,differences=['开敞亭与围合岗亭不同；单人值守不占整片广场。'])
    return m


def furniture(v=1):
    m=base(38,v,'街角配套 · '+('双人长椅' if v==1 else '布告栏与停留位'),7,5,10,('休憩',) if v==1 else ('公告',))
    m.box((1,0,1),(5,1,3),'cobblestone');m.set(3,1,0,'cobblestone');m.point('entry','entrance',(3,2,0),'步行接近面')
    if v==1:
        for x in (2,4):seat(m,x,2,3,'north')
        m.box((1,2,3),(1,3,3),'oak_trapdoor[open=true,facing=west]')
        m.box((5,2,3),(5,3,3),'oak_trapdoor[open=true,facing=east]')
    else:
        for x in (1,5):m.box((x,2,3),(x,4,3),'oak_fence')
        m.box((2,3,3),(4,4,3),'stripped_oak_log[axis=x]')
        m.box((0,5,2),(6,5,4),'spruce_slab')
        m.point('read','meeting',(3,2,2),'公告阅读停留',approach=(3,2,2),use='public')
    m.meta.update(asset_tags=['landscape'],roof_min_y=5,differences=['小型独立家具；座位与公告停留点不作为工位。'])
    return m


def school():
    m=base(39,1,'教区学堂 · 八席课堂与书柜',13,15,20,('教学',))
    b=(1,2,11,13);room_shell(m,b,name='八席课堂');windows(m,b);cross_roof(m,b,6);entry(m,6)
    for x in (3,9):
        for z in (4,6,8,10):seat(m,x,2,z,'east' if x==3 else 'west')
    for x in (5,7):m.box((x,2,4),(x,2,10),'spruce_slab[type=top]')
    station(m,6,2,12,'lectern[facing=north]','教师',(6,2,11))
    m.box((3,3,13),(9,4,13),'black_terracotta')
    for z in (5,10):light(m,6,5,z)
    m.meta['differences']=['实际八席、中央通路、教师台与黑板；无需大教学楼占地。']
    return m


def stable(v=1):
    m=base(41,v,'畜牧配套 · '+('双栏饲养棚' if v==1 else '独立双门围栏'),11,15,18,('畜牧',))
    if v==1:
        b=(1,2,9,13);room_shell(m,b,name='饲养棚');roof(m,b,6,'stone_brick');entry(m,5)
        m.box((3,2,6),(3,2,11),'oak_fence');m.box((7,2,6),(7,2,11),'oak_fence')
        for x in (3,7):m.set(x,2,8,'oak_fence_gate[facing=east,open=true]')
        m.box((2,2,11),(2,3,12),'hay_block');m.box((8,2,11),(8,3,12),'hay_block')
        station(m,2,2,4,'water_cauldron[level=3]','畜牧饲养');light(m,5,5,7)
    else:
        m.box((1,0,1),(9,1,13),'dirt')
        for x in (1,9):m.box((x,2,1),(x,2,13),'oak_fence')
        for z in (1,13):m.box((1,2,z),(9,2,z),'oak_fence');m.set(5,2,z,'oak_fence_gate[facing=south,open=true]')
        m.set(5,1,0,'dirt');m.point('entry','entrance',(5,2,0),'围栏入口')
        station(m,2,2,7,'water_cauldron[level=3]','围栏饮水维护')
    m.meta['differences']=['棚体和围栏独立组合；动物尺寸及寻路需实际游戏另验。']
    return m


def shrine(v=1):
    m=base(44,v,'路边神龛 · '+('独立木顶石龛' if v==1 else '贴墙小龛'),5,5,12,('礼仪','纪念'))
    m.box((1,0,1),(3,1,3),'stone_bricks');m.box((1,2,3),(3,4,3),'stone_bricks')
    m.set(2,3,3,'chiseled_stone_bricks');m.set(2,2,3,'lantern')
    if v==1:roof(m,(1,1,3,3),5,'stone_brick')
    else:m.box((0,5,2),(4,5,4),'stone_brick_slab')
    m.set(2,1,0,'cobblestone');m.point('entry','entrance',(2,2,0),'神龛接近面')
    m.point('visit','meeting',(2,2,2),'礼仪停留',approach=(2,2,2),use='public')
    m.meta['differences']=['独立有顶神龛与贴墙龛；停留点不冒充岗位。']
    return m


def fisher():
    m=base(45,1,'渔家小屋 · 侧向网具操作廊',11,13,20,('家庭居住','捕鱼'))
    b=(1,2,7,11);room_shell(m,b,name='渔家住屋');windows(m,b);roof(m,b,6,'stone_brick');entry(m,4)
    for x in (3,5):bed(m,x,2,9,f'bed_{x}')
    hearth(m,2,3);light(m,4,5,6)
    m.box((8,0,3),(9,1,10),'spruce_planks')
    m.box((7,2,5),(7,3,5),'air')
    for z in (3,10):m.box((9,2,z),(9,4,z),'spruce_fence')
    m.box((9,4,3),(9,4,10),'oak_fence');m.box((9,3,7),(9,3,9),'cobweb')
    station(m,9,2,4,'barrel[facing=west]','渔网与渔具整理',(8,2,4))
    m.meta['terrain']['选址']='岸上稳定承载面；侧廊接近作业岸线，房屋本体不跨水；栈台独立选配。'
    m.meta['differences']=['屋体与网具侧廊有明确分工；水上栈台不绑进住宅。']
    return m


BUILDERS={}
for number,fn,count in ((29,townhouse,3),(30,shop_home,2),(34,stall,3),(36,washing,2),(37,sentry,2),(38,furniture,2),(41,stable,2),(44,shrine,2)):
    for v in range(1,count+1):BUILDERS[f'EU-{number:02d}-v{v:02d}']=partial(fn,v)
for number,fn in ((31,manor),(32,inn),(35,clinic),(39,school),(45,fisher)):
    BUILDERS[f'EU-{number:02d}-v01']=fn
