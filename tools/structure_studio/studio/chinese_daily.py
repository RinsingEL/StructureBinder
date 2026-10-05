"""S13 everyday buildings and small infrastructure, authored as usable structures."""
from .chinese_parts import (base,building,doorway,veranda,desk,bed,table,kitchen,wash,pave,roof,garden)
from .chinese import record_part


def daily(n,name,w,d,h=23,role='fill',terms=(),tags=()):
    m=base(n,name,w,d,height=h)
    m.meta.update(planning_role='planning_role.'+role,function_terms=list(terms),asset_tags=list(tags),
                  source='tools/structure_studio/studio/chinese_daily.py:BUILDERS',
                  design_notes=['日常木构沿用灰瓦、浅墙、门廊和石基；具体功能由实际空间及设施表达。'])
    m.point('front','entrance',(w//2,2,0),'外部步行入口',facing='north')
    pave(m,w//2-1,0,w//2+1,d-2)
    return m


def small_home():
    m=daily(1,'中式门廊小宅',23,27,terms=['家庭居住'])
    building(m,'home','起居与寝居',4,7,18,22,room=False)
    doorway(m,'home_door',10,7,width=2);veranda(m,4,7,18)
    m.box((5,3,16),(17,6,16),'white_terracotta');m.box((10,3,16),(11,5,16),'air')
    m.room('living','起居与炊事',(5,3,8),(17,6,15),'就餐、备餐和洗漱')
    m.room('sleep','双床寝居',(5,3,17),(17,6,21),'家庭卧室与衣物')
    kitchen(m,'stove',5,8);table(m,12,12,length=3);wash(m,'wash',5,13)
    bed(m,'bed_west',7,20);bed(m,'bed_east',14,20);desk(m,'wardrobe',16,18,kind='storage',block='barrel')
    garden(m,2,5,3,20);m.set(17,3,5,'flower_pot')
    return m


def shop(*,goods='general'):
    items={'general':'barrel','cloth':'white_wool','woodcraft':'bookshelf','tools':'smithing_table'}
    if goods not in items:raise ValueError('Unknown goods theme')
    m=daily(4,'中式通用店铺 · 前廊后仓',29,27,terms=['零售','货物仓储'])
    building(m,'shop','售卖与备货',5,7,23,22,room=False)
    doorway(m,'customer',13,7,width=3);doorway(m,'delivery',23,19,side='east');veranda(m,5,7,23)
    m.box((6,3,17),(22,6,17),'spruce_planks');m.box((13,3,17),(15,5,17),'air')
    m.room('sales','前堂售卖',(6,3,8),(22,6,16),'陈列、柜台和顾客通道')
    m.room('stock','后仓备货',(6,3,18),(22,6,21),'独立侧门收货、暂存')
    for x in (7,19):
        for z in (9,13,20):m.box((x,3,z),(x+2,3,z),items[goods])
    desk(m,'counter',10,14,block='barrel',name='收款与交付');desk(m,'stock_check',17,20,block='barrel',kind='storage',name='备货清点')
    record_part(m,'goods',4,((6,3,8),(22,5,21)),{'goods':goods})
    return m


def workshop():
    m=daily(6,'中式侧院木作坊',33,33,terms=['木器制作','工具维护'])
    building(m,'work','接单与木作',4,7,18,25);doorway(m,'work_entry',10,7,width=2)
    veranda(m,4,7,18)
    for z in (10,16,22):desk(m,'bench_'+str(z),6,z,block='crafting_table',name='木作工作台')
    desk(m,'repair',15,10,block='smithing_table',name='工具手修')
    m.box((13,3,22),(16,4,24),'spruce_planks')
    for x in (22,29):
        for z in (13,26):m.box((x,2,z),(x,5,z),'dark_oak_log')
    roof(m,22,13,29,26,7)
    m.box((24,2,17),(26,3,23),'spruce_log[axis=z]')
    m.room('yard','晾木与卸料棚',(22,2,13),(29,5,26),'卸料、木材干燥与分拣')
    m.point('wood_yard','work',(23,2,19),'木料作业通道')
    return m


def warehouse():
    m=daily(7,'中式院侧货仓 · 双开间',31,35,terms=['货物仓储','理货'])
    building(m,'storage','货仓',5,8,25,28,wall_height=7)
    doorway(m,'loading',14,8,width=3);veranda(m,5,8,25)
    for x in (7,21):
        for z in (11,18,25):m.box((x,3,z),(x+2,5,z+1),'barrel')
    for z in (12,19,26):m.point('aisle_'+str(z),'storage',(15,3,z),'贯通理货通道')
    desk(m,'receiving',18,10,block='lectern',name='收发账册')
    return m


def mine():
    m=daily(30,'中式采矿场 · 矿口与选矿院',43,45,h=25,role='structure',terms=['采矿作业','矿料仓储','工具维护'])
    # Self-contained cut rock, with a genuinely open gallery and visible ore face.
    for x in range(3,40):
        for z in range(22,42):
            top=7+int(11*max(0,1-((x-21)/23)**2-((z-34)/20)**2))
            m.box((x,1,z),(x,top,z),'andesite' if (x*7+z*3)%13<3 else 'stone')
    m.box((17,2,22),(25,7,37),'air')
    for z in (22,27,32,37):
        for x in (17,25):m.box((x,2,z),(x,6,z),'dark_oak_log')
        m.box((17,7,z),(25,7,z),'dark_oak_log[axis=x]')
    for z in range(20,37):m.set(21,2,z,'rail[shape=north_south]')
    for x in (18,20,23,24):m.box((x,3,38),(x,5,38),'iron_ore')
    for z in (25,31,36):m.set(18,6,z,'lantern[hanging=true]')
    roof(m,15,19,27,24,9)
    m.room('gallery','支护采掘巷道',(18,2,23),(24,6,37),'矿面、轨道及步行侧道')
    m.point('ore_face','work',(23,2,36),'采掘工作面前站位')
    building(m,'tools','工具修理与值守',4,6,14,17);doorway(m,'tools_entry',9,6)
    desk(m,'repair',6,8,block='smithing_table',name='工具修理');desk(m,'register',11,13,name='矿料登记')
    for x in (29,38):
        for z in (8,17):m.box((x,2,z),(x,5,z),'dark_oak_log')
    roof(m,29,8,38,17,7)
    m.box((31,2,10),(34,2,13),'raw_iron_block');m.box((36,2,11),(37,3,14),'barrel')
    m.point('sort','work',(30,2,12),'矿料分拣棚侧道')
    m.meta['terrain']['资源']='矿石方块为静态矿面表达；放置位置需另行确认资源与开采规则，不声称自动产矿。'
    return m


def street_lamp():
    m=daily(23,'中式双挑檐路灯',9,9,h=15,role='structure',terms=['道路照明'],tags=['infrastructure'])
    m.box((4,2,4),(4,3,4),'chiseled_stone_bricks');m.box((4,4,4),(4,8,4),'dark_oak_log')
    m.box((1,8,4),(7,8,4),'dark_oak_log[axis=x]')
    for x in (1,7):
        m.set(x,7,4,'lantern[hanging=true]');m.set(x,9,4,'deepslate_tile_slab')
        m.set(x,8,3,'deepslate_tile_stairs[facing=south]');m.set(x,8,5,'deepslate_tile_stairs[facing=north]')
    m.point('maintenance','work',(4,2,2),'灯柱检修站位')
    return m


def arch_bridge():
    m=daily(24,'中式石拱桥 · 双岸踏步',19,39,h=17,role='structure',terms=['步行过桥'],tags=['infrastructure'])
    # Remove the inherited central path within the water; keep a sealed basin.
    m.box((1,1,10),(17,1,28),'water')
    for x in (0,18):m.box((x,1,10),(x,1,28),'stone_bricks')
    for z in range(5,34):
        rise=min(5,max(0,min(z-5,33-z)))
        y=1+rise
        if z<=10:surface='stone_brick_stairs[facing=south]'
        elif z>=28:surface='stone_brick_stairs[facing=north]'
        else:surface='smooth_stone'
        for x in range(6,13):
            if z<=10 or z>=28:m.box((x,1,z),(x,y,z),'stone_bricks')
            else:
                # The curved underside rises off the river rather than filling it.
                underside=min(5,2+min(z-10,28-z)//3)
                m.box((x,underside,z),(x,y,z),'stone_bricks')
            m.set(x,y,z,surface)
        for x in (6,12):m.set(x,y+1,z,'stone_brick_wall')
    m.point('bridge_crown','circulation',(9,7,19),'拱桥桥顶')
    m.point('far_bank','circulation',(9,2,36),'另一岸连接点')
    m.meta.update(roof_min_y=None,floors=[],design_notes=['桥面两岸 Y=2，五级踏步升到 Y=7；栏杆连续。自带封闭水渠仅作摆放示意，未验证船舶净空与真实河道衔接。'])
    return m


def quay():
    m=daily(25,'中式河埠码头 · 候船亭与栈桥',39,43,h=23,role='structure',terms=['水路客运','货物暂存'],tags=['infrastructure'])
    m.box((1,1,25),(37,1,41),'water')
    m.box((4,1,24),(34,1,26),'stone_bricks')
    m.box((12,2,25),(16,2,38),'spruce_planks');m.box((12,2,35),(30,2,38),'spruce_planks')
    for x in range(12,17):m.set(x,2,24,'spruce_stairs[facing=south]')
    for x in (12,16):
        for z in (27,34,38):m.box((x,0,z),(x,1,z),'dark_oak_log');m.set(x,3,z,'spruce_fence')
    for x in (22,29):m.box((x,0,37),(x,1,37),'dark_oak_log');m.set(x,3,38,'spruce_fence')
    building(m,'waiting','候船与登记',6,8,20,20);doorway(m,'waiting_entry',12,8,width=2);doorway(m,'water_exit',14,20,side='south',width=2)
    veranda(m,6,8,20);table(m,8,14);desk(m,'tickets',17,11,name='候船登记')
    for x in (25,33):
        for z in (13,21):m.box((x,2,z),(x,5,z),'dark_oak_log')
    roof(m,25,13,33,21,7);m.box((27,2,15),(30,3,17),'barrel')
    m.point('cargo','storage',(29,2,19),'临水货棚');m.point('boarding','circulation',(25,3,36),'栈桥登船候行处')
    m.meta['terrain']['接水']='陆侧 Y=2，栈桥脚底 Y=3，静态水面 Y=2；模型水池需接入真实河岸，航行与船舶另验。'
    return m


BUILDERS={'CH-01-v01':small_home,'CH-04-v01':shop,'CH-06-v01':workshop,'CH-07-v01':warehouse,
          'CH-23-v01':street_lamp,'CH-24-v01':arch_bridge,'CH-25-v01':quay,'CH-30-v01':mine}
