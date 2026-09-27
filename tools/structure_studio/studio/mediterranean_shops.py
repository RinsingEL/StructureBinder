"""Eight Mediterranean daily trades from the September reference sheet."""
from .mediterranean_shop_parts import *

TRADES=[
 ('面包侧炉铺','面包选购','面粉备料','和面与烘焙','包装冷却','hay_block','smoker[facing=south]','cake'),
 ('窄街上下杂货店宅','日用品销售','干货收纳','称量分装','待取货品','barrel','crafting_table','barrel'),
 ('L形裁缝庭院','成衣试样','布卷收纳','裁衣缝纫','成衣交付','white_wool','loom[facing=south]','cyan_wool'),
 ('敞廊器具维修铺','修理接件','零件存放','拆检修复','已修器具','iron_block','smithing_table','anvil'),
 ('分屋蔬果菜铺','蔬果售卖','鲜货周转','洗涤挑拣','筐装食材','melon','water_cauldron[level=3]','pumpkin'),
 ('陶器窑院','陶器陈列','陶土备料','拉坯烧制','成品陶器','clay','furnace[facing=south]','decorated_pot'),
 ('书信转角楼铺','书信册页销售','纸张封套','书信抄写装订','完成书册','bookshelf','cartography_table','bookshelf'),
 ('绳帆棚与收纳楼','绳具帆布接单','麻绳布料','裁帆修缮','成套绳帆','white_wool','loom[facing=south]','barrel')]


def trade(m,v,pts):
    title,sale,raw,work,done,rawblock,workblock,show=TRADES[v-1]
    for i,((x,z),label,block) in enumerate(zip(pts,(sale,raw,work,done,'经营记录与订单'),('barrel',rawblock,workblock,'barrel','lectern[facing=south]'))):
        counter(m,'station'+str(i),label,x,z,3,block=block)
        if i in (0,3):m.set(x+1,4,z,show)
    if v==3:
        for i in range(5):m.set(19+i,6,20,('white_wool','cyan_wool','orange_wool')[i%3])
    if v==8:
        for x in (7,11,15):
            m.box((x,5,9),(x,7,9),'chain[axis=y]')
        m.box((9,4,18),(18,4,19),'white_wool')
    m.meta['function_terms']=['零售',('烘焙','杂货销售','裁缝','工具维修','果蔬销售','陶器制作','书籍销售','缆索编结')[v-1],sale,raw,work,done,'家庭居住']


def oven(m,x,z):
    m.box((x,3,z),(x+4,7,z+4),'cut_sandstone')
    m.box((x+1,3,z),(x+3,5,z),'air')
    m.set(x+2,3,z+1,'furnace[facing=north,lit=true]')
    m.box((x+3,8,z+3),(x+3,15,z+3),'smooth_sandstone');m.set(x+3,16,z+3,'stone_brick_slab')


def compact_upper(m):
    house(m,4,7,23,30,height=11);entry(m,'entry',14,7)
    upper(m,5,8,22,29,19,18);home(m,5,10,y=9)
    canopy(m,4,3,15,6,'orange')
    # Balcony faces the street with a real upper door, floor and rail.
    m.box((7,8,5),(15,8,6),'spruce_planks');m.door(11,9,7,wood='spruce',facing='north')
    m.box((7,9,5),(15,9,5),'spruce_fence')
    for x in (7,15):m.set(x,9,6,'spruce_fence')
    return [(5,9),(15,10),(5,16),(5,25),(14,26)]


def build(v):
    title=TRADES[v-1][0];key=f'WT-F01-v{v:02}'
    if v==1:
        m=base(key,title,37,34)
        house(m,4,8,20,28,opened=True);house(m,25,10,33,29)
        canopy(m,4,3,20,7,'orange',tile=False);entry(m,'homeentry',29,10)
        m.point('entry','entrance',(12,3,2),'遮阳售卖廊入口',facing='north');home(m,26,12)
        oven(m,24,3);pts=[(6,5),(5,11),(5,19),(13,20),(13,25)]
        zone(m,'bakery','面包铺与后烘焙间',5,9,19,27,'售卖遮阳廊、备料、和面烘焙与包装')
    elif v==2:
        m=base(key,title,28,35);pts=compact_upper(m)
        zone(m,'shop','下层杂货销售与分装',5,8,22,29,'前销售、中分装、后周转，上层完整家庭')
    elif v==3:
        m=base(key,title,38,35)
        house(m,4,6,15,30,opened=True);house(m,24,10,33,30,height=11)
        entry(m,'entry',10,6);entry(m,'homeentry',28,10)
        upper(m,25,11,32,29,29,14)
        cook(m,'homecook',25,12,y=9);dining(m,25,20,2,y=9);sleep(m,'homebed',25,24,2,y=9);stores(m,'homelinen','衣物被服',25,28,5,y=9)
        canopy(m,16,24,23,29,'white',tile=True)
        pts=[(5,8),(5,14),(5,22),(25,13),(25,18)]
        zone(m,'tailor','长裁衣翼与晾衣院',5,7,22,29,'量裁、布料收纳、缝制及院内晾晒')
    elif v==4:
        m=base(key,title,39,35)
        house(m,4,7,23,28,opened=True);house(m,27,10,36,30)
        canopy(m,4,3,23,6,'white',tile=True);entry(m,'homeentry',31,10)
        m.point('entry','entrance',(13,3,2),'开敞修理廊入口',facing='north');home(m,28,12)
        pts=[(5,9),(16,10),(5,18),(15,24),(5,25)]
        zone(m,'repair','开敞维修作业厅',5,8,22,27,'接件、零件、工台和交付，宽阔中央通路')
    elif v==5:
        m=base(key,title,36,37)
        house(m,4,22,16,33);house(m,24,12,33,33);entry(m,'entry',10,22);entry(m,'homeentry',28,12)
        canopy(m,4,4,17,14,'green');canopy(m,20,25,24,32,'white',tile=True)
        home(m,25,15);pts=[(5,7),(5,24),(20,18),(11,11),(5,30)]
        m.set(21,3,18,'water_cauldron[level=3]')
        zone(m,'produce','菜摊与分屋周转庭院',4,4,23,32,'遮阳销售、清洗挑拣、后屋干货与独立家庭')
    elif v==6:
        m=base(key,title,39,39)
        house(m,4,7,21,20,opened=True);house(m,5,25,15,35,height=11)
        entry(m,'entry',12,7);entry(m,'homeentry',10,25)
        upper(m,6,26,14,34,11,28)
        cook(m,'homecook',6,26,y=9);sleep(m,'homebedA',6,29,1,y=9);sleep(m,'homebedB',6,33,1,y=9)
        dining(m,6,32,3);stores(m,'linen','家庭衣物',6,34,3)
        oven(m,29,17);canopy(m,24,27,34,34,'white',tile=True)
        pts=[(5,9),(5,15),(25,29),(13,15),(25,33)]
        zone(m,'pottery','前敞廊陶器铺与窑院',5,8,34,34,'展示厅、烧窑、修坯棚、成品及后侧家庭楼')
    elif v==7:
        m=base(key,title,31,35);pts=compact_upper(m)
        canopy(m,24,11,27,24,'white',tile=True)
        m.box((5,3,19),(5,5,23),'bookshelf');m.box((16,3,14),(16,5,16),'bookshelf')
        zone(m,'bookshop','角楼书信铺',5,8,22,29,'前铺销售、书架阅读、抄写装订与后部记录')
    else:
        m=base(key,title,39,36)
        house(m,4,7,22,13,opened=True);house(m,27,10,35,31,height=11)
        entry(m,'homeentry',31,10);upper(m,28,11,34,30,31,14)
        cook(m,'homecook',28,12,y=9);sleep(m,'homebed',28,25,2,y=9);stores(m,'linen','家庭衣物',28,28,4,y=9);dining(m,28,18,1,y=9)
        canopy(m,5,15,23,27,'white');m.point('entry','entrance',(14,3,3),'绳帆修整院入口',facing='north')
        pts=[(5,9),(28,13),(7,23),(28,27),(16,10)]
        zone(m,'sail','绳具挂架和帆布作业院',4,7,25,28,'低棚绳具、宽白布遮阳裁帆桌和后侧收纳楼')
    trade(m,v,pts);yard(m)
    if v==6:
        # The rear home's door opens into the kiln yard, reached from the shop's street entrance.
        for point in m.meta['points']:
            if point['id']=='homeentry':point['kind']='circulation'
    m.meta['design_notes']=['按图4八个日常小商铺设计：暖白米石墙、低坡红瓦、木廊、蓝绿百叶与有用途的经营院。']
    m.meta['differences']=[title+'；占地、层数、廊院、工作台与生活组织独立配置。']
    return m

BUILDERS={f'WT-F01-v{i:02}':lambda i=i:build(i) for i in range(1,9)}
