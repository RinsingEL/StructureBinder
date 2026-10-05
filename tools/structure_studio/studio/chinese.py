"""Three complete S13 timber compounds; default exports and bounded themes.

MOCK-S13-13 -> CH-13-v01, MOCK-S13-14 -> CH-14-v01,
MOCK-S13-11 -> CH-11-v01. Theme changes are author-source rebuilds, not a runtime API.
"""
from .chinese_parts import (
    base, building, doorway, gate, boundary, desk, bed, table, kitchen, wash,
    pave, garden, pergola, corridor, veranda, gallery, roof, CATALOG_DIR,
)


def bedroom(m, key, x0,z0,x1,z1, *, floor=2, color='white'):
    y=floor+1
    bed(m,key+'_bed',x0+2,z1-2,y,color)
    desk(m,key+'_store',x1-1,z0+2,y,kind='storage',block='barrel[facing=south]',name='衣物与行李')
    m.set(x0+1,y,z0+1,'bookshelf')
    m.set(x0+2,y,z0+1,'spruce_slab[type=top]')
    m.set(x0+2,y+1,z0+1,'lantern')


def record_part(m, key, number, bounds, config):
    m.meta.setdefault('authoring_parts',[]).append(dict(
        id=key, prototype=f'MOCK-S13-{number:02d}', min=list(bounds[0]),max=list(bounds[1]),
        configuration=config, scope='作者源码局部重建配置，不是 City 运行时字段'))


def palace(*, garden_theme='flower', ceremony='audience'):
    if ceremony not in ('audience','banquet'): raise ValueError('Unknown ceremony')
    m=base(13,'中式宫殿 · 前朝后寝',61,69)
    boundary(m);gate(m,30)
    pave(m,27,8,33,65)
    pave(m,19,12,41,24,'polished_andesite')
    # A raised audience hall anchors the central axis.
    building(m,'audience','正殿：朝会与正式接见',20,25,40,39,floor=4,wall_height=6)
    doorway(m,'audience_front',29,25,4,width=3)
    doorway(m,'audience_back',37,39,4,'south')
    veranda(m,20,25,40,4)
    # Upper roof tier has a supported closed timber drum; it is not a second floor.
    m.box((23,14,29),(37,16,35),'spruce_planks')
    for z in (29,35):m.box((24,15,z),(36,15,z),'red_terracotta')
    roof(m,23,29,37,35,17,ridge_x=True)
    for sx in (28,32):
        for sy,sz in ((2,22),(3,23),(4,24)):
            m.box((sx,1,sz),(sx,sy,sz),'stone_bricks')
            m.set(sx,sy+1,sz,'stone_brick_wall')
    m.box((24,5,35),(36,5,37),'spruce_planks')
    for x in range(27,34):m.set(x,5,34,'spruce_stairs[facing=south]')
    desk(m,'ceremonial_seat',30,36,6,block='dark_oak_stairs[facing=north]',name='正殿主位')
    m.box((27,6,38),(33,8,38),'red_terracotta')
    for x in (25,35):
        for z in (29,32):
            if ceremony=='audience':m.set(x,5,z,'red_carpet')
            else:table(m,x-1,z,5,3)
    # The ceremonial mat is deliberately not an audit standing point.
    for x in (22,38):m.set(x,5,27,'flower_pot')
    building(m,'west_reception','西侧殿：会客与文书',5,18,13,32)
    doorway(m,'west_reception_door',13,25,side='east')
    desk(m,'record_desk',7,20,name='接见登记与文书')
    table(m,7,27);m.box((6,3,31),(11,4,31),'bookshelf')
    building(m,'east_reception','东侧殿：议事与等候',47,18,55,32)
    doorway(m,'east_reception_door',47,25,side='west')
    table(m,49,24);desk(m,'meeting_record',49,29,name='议事记录')
    building(m,'palace_kitchen','膳房与食材存放',5,39,13,48)
    doorway(m,'palace_kitchen_door',13,43,side='east')
    kitchen(m,'palace_stove',7,40)
    wash(m,'palace_wash',7,46)
    building(m,'palace_store','器物与日常物资库',47,39,55,48)
    doorway(m,'palace_store_door',47,43,side='west')
    for z in (40,45):
        for x in range(49,54):m.set(x,3,z,'barrel[facing=south]')
    desk(m,'palace_inventory',49,45,kind='storage',block='barrel[facing=south]',name='器物收发')
    corridor(m,16,16,18,47);corridor(m,42,16,44,47)
    building(m,'private','后寝院：寝居与私人会客',12,52,48,63,room=False)
    for x in (24,36):m.box((x,3,53),(x,6,62),'white_terracotta')
    for i,(xa,xb) in enumerate(((12,24),(24,36),(36,48))):
        key=f'private_{i+1}'
        m.room(key,['西寝居','内廷起居','东寝居'][i],(xa+1,3,53),(xb-1,7,62),'内廷生活与私人接待')
        doorway(m,key+'_door',(xa+xb)//2,52)
        if i!=1:bedroom(m,key,xa,52,xb,63,color='red')
        else:table(m,28,57,3,5);wash(m,'private_wash',27,60)
    pergola(m,'inner_pergola',21,44,27,48,garden_theme)
    garden(m,34,44,39,48)
    for xa,xb in ((6,14),(46,54)):garden(m,xa,12,xb,14)
    for x in (23,37):
        for z in (14,19):
            m.set(x,2,z,'chiseled_stone_bricks');m.set(x,3,z,'stone_brick_wall');m.set(x,4,z,'lantern')
    for x in (26,34):m.box((x,2,4),(x,5,4),'red_terracotta')
    m.meta.update(function_terms=['礼仪','正式接见','家庭居住'],
                  differences=['三进中轴、重檐抬高正殿、两侧连廊与独立后寝；上层为屋面构架，非可用二层。'])
    record_part(m,'ceremony',13,((21,5,26),(39,10,38)),{'ceremony':ceremony})
    record_part(m,'inner_pergola',15,((21,2,44),(27,7,48)),{'garden_theme':garden_theme})
    return m


def shop_fit(m, key, x0,z0,x1,z1, kind='general'):
    if kind not in ('general','cloth','woodcraft','tools'):raise ValueError('Unknown shop type')
    props={'general':('barrel','composter'), 'cloth':('white_wool','blue_wool'),
           'woodcraft':('spruce_stairs[facing=south]','oak_trapdoor[half=top]'),
           'tools':('smithing_table','grindstone[facing=south,face=floor]')}
    for x in range(x0+2,x1-1,2):
        m.set(x,3,z0+1,'spruce_planks');m.set(x,4,z0+1,props[kind][(x//2)%2])
    m.box((x0+2,3,z1-2),(x1-2,3,z1-2),'spruce_slab[type=top]')
    desk(m,key+'_counter',x0+2,z1-2,block='barrel[facing=south]',name='店铺柜台与交付')
    m.set(x1-1,3,z0+2,'barrel')


def manor(*, garden_theme='grape', shop='general'):
    m=base(14,'中式大庄园 · 前宅后园',49,57)
    boundary(m);gate(m,24)
    pave(m,21,8,27,34);pave(m,14,29,34,34);pave(m,17,32,19,52);pave(m,31,32,33,52)
    building(m,'main_home','主宅会客与家庭起居',16,14,32,26)
    doorway(m,'main_home_door',23,14,width=2);doorway(m,'main_home_back',24,26,side='south')
    veranda(m,16,14,32)
    table(m,21,20,3,5);desk(m,'home_desk',18,23,name='家庭书案与会客记录')
    m.box((27,3,25),(30,4,25),'bookshelf')
    for x in range(20,27):
        for z in range(18,23):
            if m.blocks[(x,3,z)][0]=='minecraft:air':m.set(x,3,z,'brown_carpet')
    for x in (18,19,29,30):m.set(x,3,16,'spruce_stairs[facing=south]')
    for x in (17,31):
        m.set(x,2,12,'barrel');m.set(x,3,12,'potted_fern')
    for side,xa,xb in (('west',5,12),('east',36,43)):
        building(m,side+'_home','居住厢房',xa,17,xb,30,room=False)
        m.box((xa+1,3,24),(xb-1,6,24),'white_terracotta')
        for i,(za,zb) in enumerate(((17,24),(24,30))):
            key=f'{side}_bed_{i}'
            m.room(key,'家庭卧室',(xa+1,3,za+1),(xb-1,7,zb-1),'家庭睡眠、书写与衣物')
            door_z=za+2 if side=='east' and i==1 else za+3
            doorway(m,key+'_door',xb if side=='west' else xa,door_z,side='east' if side=='west' else 'west')
            bedroom(m,key,xa,za,xb,zb)
    building(m,'manor_shop','院侧店铺：商品与柜台',5,4,15,10)
    doorway(m,'manor_shop_door',10,10,side='south')
    shop_fit(m,'shop',5,4,15,10,shop)
    building(m,'manor_kitchen','厨房餐厅与生活后勤',5,37,15,48)
    doorway(m,'manor_kitchen_door',15,42,side='east')
    kitchen(m,'manor_stove',7,38);table(m,8,44)
    wash(m,'manor_wash',7,46)
    building(m,'manor_store','院侧货仓与园艺工具',35,37,43,48)
    doorway(m,'manor_store_door',35,42,side='west')
    for z in (38,45):
        for x in range(37,42):m.set(x,3,z,'barrel[facing=south]')
    desk(m,'manor_goods',37,45,kind='storage',block='barrel[facing=south]',name='货仓收发')
    pergola(m,'garden_pergola',21,36,29,45,garden_theme)
    for xa,xb in ((5,14),(35,43)):
        garden(m,xa,32,xb,34)
    garden(m,35,5,42,12)
    # Working vegetable beds with real vanilla hydration geometry.
    for x in range(19,32):
        for z in range(49,53):
            m.set(x,1,z,'water' if x in (22,28) else 'farmland[moisture=7]')
            if x not in (22,28):m.set(x,2,z,'carrots[age=7]')
    pave(m,18,48,32,48)
    m.point('vegetable_bed','work',(20,2,49),'菜畦照护',approach=(20,2,48))
    m.meta.update(planning_role='planning_role.self_contained',
                  function_terms=['家庭居住','棚架种植','零售','货物仓储'],
                  agriculture={'description':'后园原版胡萝卜畦，中央水沟；棚架果实为静态原版表达。'},
                  differences=['中央主宅、两侧家庭卧室、南侧园艺后勤区及独立院侧店铺。'])
    record_part(m,'garden_pergola',15,((21,2,36),(29,7,45)),{'garden_theme':garden_theme})
    record_part(m,'courtyard_shop',4,((5,3,4),(15,7,10)),{'shop':shop})
    record_part(m,'warehouse',7,((35,3,37),(43,7,48)),{'goods':'箱装杂货'})
    return m


def inn():
    m=base(11,'中式中型客栈 · 六室围院',43,51)
    boundary(m);gate(m,21)
    pave(m,16,10,26,28,'stone_bricks')
    pave(m,14,11,15,47);pave(m,27,11,28,47)
    # Reception uses the gatehouse side bays while the middle stays traversable.
    desk(m,'reception',18,6,2,block='lectern[facing=south]',name='入住登记与账簿')
    m.set(17,2,7,'barrel');m.set(24,2,6,'spruce_stairs[facing=west]')
    m.room('reception','门厅接待',(17,2,5),(25,6,9),'登记、等候与行李暂存')
    for side,xa,xb in (('west',4,13),('east',29,38)):
        building(m,side+'_wing','围院客房翼',xa,15,xb,36,room=False)
        for z in (22,29):m.box((xa+1,3,z),(xb-1,6,z),'white_terracotta')
        for i,za in enumerate((15,22,29)):
            key=f'{side}_guest_{i+1}'
            m.room(key,'客房 '+str(i+1)+(' 西' if side=='west' else ' 东'),(xa+1,3,za+1),(xb-1,7,za+6),'双床客房，含行李、桌案与照明')
            doorway(m,key+'_door',xb if side=='west' else xa,za+3,side='east' if side=='west' else 'west')
            bed(m,key+'_a',xa+2,za+5)
            bed(m,key+'_b',xa+6,za+5,color='brown')
            desk(m,key+'_luggage',xa+2,za+1,kind='storage',block='barrel[facing=south]',name='客房行李')
            m.set(xa+4,3,za+1,'spruce_slab[type=top]')
            m.set(xa+5,3,za+1,'spruce_slab[type=top]')
            m.set(xa+4,3,za+2,'spruce_stairs[facing=north]')
            m.set(xa+5,4,za+1,'flower_pot')
    gallery(m,15,15,36);gallery(m,27,15,36)
    building(m,'dining','餐厅、茶水与公共休息',17,30,25,44)
    doorway(m,'dining_door',21,30)
    veranda(m,17,30,25)
    for z in (34,39):table(m,19,z,3,3)
    desk(m,'tea_water',19,42,block='water_cauldron[level=3]',name='茶水备制')
    building(m,'inn_kitchen','后厨与食材库',4,41,13,47)
    doorway(m,'inn_kitchen_door',13,44,side='east')
    kitchen(m,'inn_stove',6,42)
    building(m,'bath_linen','洗漱与布草后勤',29,41,38,47)
    doorway(m,'bath_linen_door',29,44,side='west')
    wash(m,'wash_basin',31,42)
    desk(m,'linen',36,45,kind='storage',block='barrel[facing=south]',name='清洁布草')
    for x in (17,24):
        m.set(x,2,20,'stone_brick_wall');m.set(x,3,20,'lantern')
    garden(m,5,11,11,12);garden(m,31,11,37,12)
    pave(m,18,22,24,25,'polished_andesite')
    m.box((19,1,23),(23,1,24),'water')
    m.set(19,2,23,'lily_pad');m.set(23,2,24,'lily_pad')
    m.meta.update(planning_role='planning_role.self_contained',
                  function_terms=['旅客住宿','餐饮','接待'],
                  differences=['六间双床客房共十二床，中心庭院与餐厅分开，后厨、布草和洗漱独立设置。'])
    return m


BUILDERS={'CH-13-v01':palace, 'CH-14-v01':manor, 'CH-11-v01':inn}
