"""CH-13-v02: monumental audience hall, raised terrace and separated inner court."""
from .chinese_parts import (
    base, building, doorway, boundary, pave, desk, bed, kitchen,
    wash, garden, pergola, corridor,
)
from .chinese_culture import table
from .chinese import record_part


def stone_rail(m,x0,z0,x1,z1,y):
    m.box((x0,y,z0),(x1,y,z1),'stone_brick_wall')
    for x,z in ((x0,z0),(x1,z1)):
        m.set(x,y,z,'chiseled_stone_bricks')
        m.set(x,y+1,z,'stone_brick_slab[type=bottom]')


def terrace_stair(m,x0,x1,z0,rise,*,south=True):
    for i in range(rise):
        z=z0+i if south else z0-i
        for x in range(x0,x1+1):
            m.box((x,1,z),(x,1+i,z),'stone_bricks')
            m.set(x,2+i,z,'stone_brick_stairs[facing='+('south' if south else 'north')+']')


def hall_door(m,key,x,z,side='north',width=1):
    # One step from terrace floor 5 to hall floor 6.
    dz=-1 if side=='north' else 1
    for xx in range(x,x+width):
        m.box((xx,7,z),(xx,10,z),'air')
        m.door(xx,7,z,facing=side,hinge='left' if (xx-x)%2==0 else 'right')
        m.set(xx,6,z+dz,'stone_brick_stairs[facing='+('south' if side=='north' else 'north')+']')
    m.point(key,'circulation',(x,7,z-dz),'正殿门内通路')


def hipped_roof(m,x0,z0,x1,z1,eave):
    """Four slopes meet on a short ridge; continuous fascia carries the eaves."""
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            distance,face=min(((x-x0,'east'),(x1-x,'west'),
                               (z-z0,'south'),(z1-z,'north')))
            y=eave+distance//2
            block=('deepslate_tile_stairs[facing='+face+']'
                   if distance%2==0 else 'deepslate_tiles')
            m.set(x,y,z,block)
            if distance==0:m.set(x,y-1,z,'dark_oak_slab[type=top]')
    mid=(z0+z1)//2;rise=(z1-z0)//2
    for x in range(x0+rise,x1-rise+1):
        m.set(x,eave+rise//2,mid,'deepslate_tiles')
        m.set(x,eave+rise//2+1,mid,'deepslate_tile_slab[type=bottom]')
    for x in (x0,x1):
        for z in (z0,z1):m.set(x,eave+1,z,'deepslate_tile_slab[type=bottom]')


def palace_grand(*,garden_theme='flower',ceremony='audience'):
    if ceremony not in ('audience','banquet'):raise ValueError('Unknown ceremony')
    m=base(13,'中式宫殿 · 高台重殿',81,95,height=31)
    m.meta.update(id='CH-13-v02',source='tools/structure_studio/studio/chinese_palace.py:palace_grand',
                  function_terms=['礼仪','正式接见','家庭居住'],
                  floors=[dict(name='全院与侧殿剖面',y=1,max_y=7),
                          dict(name='高台正殿与主位',y=5,max_y=13)],roof_min_y=17,
                  differences=['三门宫门、宽阔前庭、四级外阶与高台、重檐高挑正殿、低侧殿和独立后寝。'],
                  design_notes=['灰瓦深木延续参考语言，宫门与正殿加朱柱、赭红墙带和局部金饰，区分日常院宅。',
                                '正殿是高挑单层礼仪大厅；重檐上部为封闭屋面构架，不冒充可用楼层。',
                                '中央九格宽外阶接高台，正殿入口再抬一级；后阶连通内廷。',
                                '保留 CH-13-v01，小尺度原版与新体量分别存放。'])
    boundary(m)
    pave(m,35,0,45,92)
    pave(m,23,18,57,29,'polished_andesite')
    pave(m,18,18,22,85);pave(m,58,18,62,85)
    # Broad three-opening palace gate; all passages lead to the forecourt.
    building(m,'palace_gate','三门宫门与守候',28,5,52,16,floor=2,wall_height=9,color='red_terracotta')
    for x in (31,39,47):
        m.box((x,3,5),(x+2,7,16),'air')
        for xx in range(x,x+3):
            m.set(xx,2,4,'stone_brick_stairs[facing=south]')
            m.set(xx,2,17,'stone_brick_stairs[facing=north]')
    for z in (5,16):
        for x in (28,36,44,52):m.box((x,3,z),(x,10,z),'red_terracotta')
    m.point('front','entrance',(40,2,0),'正门外部街面',facing='north')
    m.point('palace_gate_path','circulation',(40,3,10),'中央宫门贯通')
    desk(m,'gate_register',35,9,name='宫门接见登记')
    for x0,x1 in ((3,27),(53,77)):
        m.box((x0,2,8),(x1,5,8),'red_terracotta')
        m.box((x0,2,8),(x1,2,8),'stone_bricks')
        m.box((x0,6,8),(x1,6,8),'deepslate_tile_slab[type=bottom]')
    for x in (27,53):
        m.box((x,2,7),(x,5,7),'stone_bricks');m.set(x,6,7,'lantern')
    # Light stone terrace dominates the foreground; side wings remain on ground.
    m.box((24,1,34),(56,5,62),'stone_bricks')
    m.box((24,5,34),(56,5,62),'smooth_stone')
    terrace_stair(m,36,44,30,4)
    terrace_stair(m,37,43,66,4,south=False)
    for x0,x1 in ((24,35),(45,56)):stone_rail(m,x0,34,x1,34,6)
    for x0,x1 in ((24,36),(44,56)):stone_rail(m,x0,62,x1,62,6)
    for x in (24,56):stone_rail(m,x,35,x,61,6)
    m.point('front_terrace','circulation',(40,6,35),'高台前沿')
    m.point('rear_terrace','circulation',(40,6,61),'后阶通往内廷')
    building(m,'audience','高台正殿：礼仪朝会与接见',27,38,53,57,floor=6,wall_height=10,color='red_terracotta')
    # Strong red columns and a deeper structural cornice replace house-scale trim.
    for x in (27,31,35,39,43,47,51,53):
        for z in (38,57):
            m.box((x,7,z),(x,15,z),'red_terracotta')
            m.set(x,16,z,'dark_oak_log[axis=x]')
    for z in (42,49):
        for x in (31,49):m.box((x,7,z),(x,15,z),'red_terracotta')
        m.box((28,15,z),(52,15,z),'dark_oak_log[axis=x]')
    # A deep front colonnade and tall lattice panels give the facade real depth.
    for x in (27,35,45,53):
        m.set(x,6,35,'chiseled_stone_bricks')
        m.box((x,7,35),(x,15,35),'red_terracotta')
        m.set(x,15,36,'dark_oak_stairs[facing=south,half=top]')
        m.box((x,16,35),(x,16,38),'dark_oak_log[axis=z]')
    m.box((27,16,35),(53,16,35),'dark_oak_log[axis=x]')
    for z in (38,57):
        for x in (29,33,37,45,51):
            m.box((x,9,z),(x,12,z),'spruce_fence[east=true,west=true]')
            m.set(x,13,z,'yellow_terracotta')
    for x in (27,53):
        for z in (41,45,49,53):
            m.box((x,9,z),(x,12,z),'spruce_fence[north=true,south=true]')
            m.set(x,13,z,'yellow_terracotta')
    # Broad hipped tiers replace the house roof; a low drum joins both roofs.
    m.box((26,17,37),(54,30,58),'air')
    hipped_roof(m,24,35,56,60,17)
    m.box((30,19,42),(50,22,53),'dark_oak_planks')
    for z in (42,53):m.box((31,21,z),(49,22,z),'red_terracotta')
    hipped_roof(m,28,40,52,55,23)
    for z in (42,53):
        for x in (33,40,47):m.set(x,22,z,'yellow_terracotta')
    # Wide central entrance and secondary doors keep ceremony and rear routes clear.
    hall_door(m,'audience_front',39,38,width=3)
    hall_door(m,'audience_west_front',31,38)
    hall_door(m,'audience_east_front',49,38)
    hall_door(m,'audience_back',49,57,side='south')
    m.box((39,6,39),(41,6,49),'red_terracotta')
    # Raised main seat with its own two-step approach, surrounded by a full aisle.
    m.box((35,7,52),(45,8,56),'dark_oak_planks')
    for x in range(38,43):
        m.set(x,7,50,'dark_oak_stairs[facing=south]')
        m.set(x,7,51,'dark_oak_planks');m.set(x,8,51,'dark_oak_stairs[facing=south]')
    m.box((35,9,56),(45,13,56),'red_terracotta')
    for x in (35,45):m.box((x,9,56),(x,13,56),'dark_oak_log')
    m.box((36,13,56),(44,13,56),'yellow_terracotta')
    m.set(40,9,54,'dark_oak_stairs[facing=north]')
    for x in (37,43):m.set(x,9,55,'gold_block')
    m.point('ceremonial_seat','work',(40,9,54),'正殿主位',approach=(40,9,53),look_at=[40,10,54])
    for x in (33,45):
        for z in (43,47):
            if ceremony=='audience':
                m.box((x,7,z),(x+2,7,z),'red_carpet')
            else:table(m,x,z,7,3)
    for x in (29,51):
        for z in (40,53):
            m.set(x,7,z,'chiseled_stone_bricks');m.set(x,8,z,'lantern')
    # Side halls and long covered passages are deliberately lower than the axis.
    building(m,'west_reception','西侧殿：文书与接见',6,23,17,40)
    doorway(m,'west_reception_door',17,31,side='east')
    desk(m,'record_desk',8,25,name='文书与接见登记');table(m,9,34,3,5)
    m.box((7,3,39),(16,4,39),'bookshelf')
    building(m,'east_reception','东侧殿：议事与等候',63,23,74,40)
    doorway(m,'east_reception_door',63,31,side='west')
    table(m,66,29,3,5);desk(m,'meeting_record',65,36,name='议事文书')
    corridor(m,19,24,21,61);corridor(m,59,24,61,61)
    for x0,x1 in ((6,16),(64,74)):garden(m,x0,46,x1,60)
    # Inner-court screen and gate divide the public forecourt from domestic life.
    for xa,xb in ((4,34),(46,76)):
        m.box((xa,2,68),(xb,4,68),'white_terracotta')
        m.box((xa,5,68),(xb,5,68),'deepslate_tile_slab[type=bottom]')
    # gate() would add a second external path seed; use a room and internal passage.
    building(m,'inner_gate','内廷门',35,69,45,75,floor=1,room=False)
    m.box((39,2,69),(41,5,75),'air')
    m.point('inner_gate_route','circulation',(40,2,72),'内廷门贯通')
    building(m,'private_shell','后寝：独立生活院',23,81,57,90,floor=3,room=False)
    for x in (35,45):m.box((x,4,82),(x,8,89),'white_terracotta')
    for i,(xa,xb) in enumerate(((23,35),(35,45),(45,57))):
        key=f'private_{i+1}';doorway(m,key+'_door',(xa+xb)//2,81,3)
        m.room(key,['西寝居','内廷起居','东寝居'][i],(xa+1,4,82),(xb-1,8,89),'内廷起居、寝居与私人会客')
        if i==1:table(m,38,85,4,5);wash(m,'private_wash',37,88,4)
        else:
            bed(m,key+'_bed',xa+3,88,4,'red')
            desk(m,key+'_wardrobe',xb-2,83,4,kind='storage',block='barrel',name='寝居衣物')
    building(m,'palace_kitchen','内廷膳房与备膳',6,73,17,86)
    doorway(m,'palace_kitchen_door',17,79,side='east')
    kitchen(m,'palace_stove',8,74);table(m,9,82,3,5);wash(m,'palace_wash',8,84)
    building(m,'palace_store','内廷器物与后勤库',63,73,74,86)
    doorway(m,'palace_store_door',63,79,side='west')
    for z in (74,84):m.box((65,3,z),(72,3,z),'barrel')
    desk(m,'palace_inventory',65,84,kind='storage',block='barrel',name='器物领用')
    pergola(m,'inner_pergola',24,71,32,76,garden_theme)
    garden(m,48,71,56,77)
    # Forecourt edge lanterns define the central procession without obstructing it.
    for x in (28,52):
        for z in (21,27):
            m.set(x,2,z,'chiseled_stone_bricks');m.set(x,3,z,'stone_brick_wall');m.set(x,4,z,'lantern')
    record_part(m,'ceremony',13,((28,7,39),(52,14,56)),{'ceremony':ceremony})
    record_part(m,'inner_pergola',15,((24,2,71),(32,7,76)),{'garden_theme':garden_theme})
    return m


BUILDERS={'CH-13-v02':palace_grand}
