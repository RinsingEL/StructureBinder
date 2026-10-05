"""CH-11-v02: a real three-storey inn with two-storey courtyard wings."""
from .chinese_parts import base, boundary, pave, roof, window, doorway, bed, desk, kitchen, wash, garden, building
from .chinese_culture import table


def storey(m, x0,z0,x1,z1, floor):
    """Add one hollow storey without back-filling the rooms below it."""
    m.box((x0,floor,z0),(x1,floor,z1),'spruce_planks')
    for z in (z0,z1):m.box((x0,floor+1,z),(x1,floor+5,z),'calcite')
    for x in (x0,x1):m.box((x,floor+1,z0),(x,floor+5,z1),'calcite')
    for x in sorted({x0,x1,*range(x0,x1+1,4)}):
        for z in (z0,z1):m.box((x,floor,z),(x,floor+6,z),'dark_oak_log')
    for z in sorted({z0,z1,*range(z0,z1+1,7)}):
        for x in (x0,x1):m.box((x,floor,z),(x,floor+6,z),'dark_oak_log')
    for z in (z0,z1):
        m.box((x0,floor+5,z),(x1,floor+5,z),'spruce_log[axis=x]')
        m.box((x0,floor+6,z),(x1,floor+6,z),'dark_oak_planks')
        for x in range(x0+2,x1,4):window(m,x,z,floor,'north')
    for x in (x0,x1):
        m.box((x,floor+5,z0),(x,floor+5,z1),'spruce_log[axis=z]')
        m.box((x,floor+6,z0),(x,floor+6,z1),'dark_oak_planks')
        for z in range(z0+3,z1,7):window(m,x,z,floor,'east')


def upper_door(m, key, x,z,floor,side='south'):
    m.box((x,floor+1,z),(x,floor+3,z),'air')
    m.door(x,floor+1,z,facing=side)
    dx,dz={'south':(0,1),'north':(0,-1),'east':(1,0),'west':(-1,0)}[side]
    m.point(key,'circulation',(x-dx,floor+1,z-dz),'客房或楼廊入口')


def rail(m,x0,z0,x1,z1,y):
    links='east=true,west=true' if z0==z1 else 'north=true,south=true'
    m.box((x0,y,z0),(x1,y,z1),'dark_oak_fence['+links+']')


def guestroom(m,key,title,x0,z0,x1,z1,floor,door_side,door_x,door_z,compact=False):
    y=floor+1
    m.room(key,title,(x0+1,y,z0+1),(x1-1,floor+5,z1-1),'双床客房，独立房门、行李、书案与照明')
    upper_door(m,key+'_door',door_x,door_z,floor,door_side)
    xs=(x0+2,x1-2) if compact else (x0+2,x0+6)
    for i,x in enumerate(xs):bed(m,key+'_bed_'+str(i+1),x,z1-2,y,'white' if i==0 else 'brown')
    desk(m,key+'_luggage',x0+2,z0+1,y,kind='storage',block='barrel',name='客房行李存放')
    m.set(x1-2,y,z0+1,'dark_oak_slab[type=top]')
    m.set(x1-2,y+1,z0+1,'flower_pot')
    m.set(x0+2,floor+4,z0+2,'lantern[hanging=true]')
    m.box((x0+1,floor+5,z0+2),(x1-1,floor+5,z0+2),'spruce_log[axis=x]')


def stairs(m):
    # Two separate flights: ground to middle ascends south, middle to top north.
    # Open the actual slabs at each flight; keep the third-floor west passage.
    m.box((23,8,8),(25,11,14),'air')
    m.box((27,15,8),(29,18,14),'air')
    for i in range(7):
        for x in range(23,26):
            m.box((x,3,8+i),(x,3+i,8+i),'spruce_planks')
            m.set(x,3+i,8+i,'spruce_stairs[facing=south]')
        for x in (22,26):
            m.set(x,4+i,8+i,'dark_oak_fence[north=true,south=true]')
        for x in range(27,30):
            m.box((x,10,14-i),(x,10+i,14-i),'spruce_planks')
            m.set(x,10+i,14-i,'spruce_stairs[facing=north]')
        for x in (26,30):m.set(x,11+i,14-i,'dark_oak_fence[north=true,south=true]')
    # Upper hole edges have continuous guards. Flight entries stay open.
    rail(m,22,8,22,14,10);rail(m,26,8,26,14,10)
    rail(m,23,7,25,7,10)
    rail(m,26,8,26,14,17);rail(m,30,8,30,14,17)
    rail(m,27,15,29,15,17)
    for key,pos in [('ground_landing',(24,3,7)),('middle_landing',(24,10,16)),
                    ('upper_landing',(28,17,7)),('upper_passage',(24,17,16))]:
        m.point(key,'circulation',pos,'楼梯平台与楼层通路')


def tall_inn():
    m=base(11,'中式客栈 · 三层十二室',51,57,height=29)
    m.meta.update(id='CH-11-v02',planning_role='planning_role.self_contained',
                  source='tools/structure_studio/studio/chinese_inn.py:tall_inn',
                  function_terms=['旅客住宿','餐饮','接待'],
                  floors=[dict(name='一层 · 接待餐饮与后勤',y=1,max_y=7),
                          dict(name='二层 · 十间客房与楼廊',y=9,max_y=14),
                          dict(name='三层 · 两间客房与临街阳台',y=16,max_y=21)],
                  roof_min_y=22,
                  design_notes=['三层主楼和两层翼楼，真实楼板、梯段、转向平台与护栏；不是在单层屋顶上加高装饰。',
                                '一层公共与服务空间，二层十间、三层两间双床客房，共二十四客床；另有两张员工床。',
                                '保留 CH-11-v01 围院客舍体量供对比，新增形体不是简单同比例缩放。',
                                '各层切片可查看内部；去屋顶只裁主楼最高屋面，翼楼请用二层切片查看。'],
                  differences=['三层临街主楼、两层客房翼、十二室二十四客床、串联楼梯、临街阳台和围院楼廊。'])
    boundary(m)
    pave(m,21,0,29,53);pave(m,16,19,34,53)
    # Main building and wings all have genuine hollow lower floors.
    m.box((14,1,5),(36,1,18),'stone_bricks')
    for floor in (2,9,16):storey(m,14,5,36,18,floor)
    roof(m,14,5,36,18,23,ridge_x=True)
    for xa,xb in ((5,15),(35,45)):
        m.box((xa,1,22),(xb,1,50),'stone_bricks')
        for floor in (2,9):storey(m,xa,22,xb,50,floor)
        roof(m,xa,22,xb,50,16,ridge_x=False)
    # Entry, lobby, reception and through-passage into the open courtyard.
    doorway(m,'front_hall_door',25,5,width=2)
    doorway(m,'courtyard_door',25,18,side='south',width=2)
    m.point('front','entrance',(25,2,0),'临街主入口',facing='north')
    m.room('lobby','临街大堂与接待',(15,3,6),(35,7,17),'入住、等候、行李与楼梯口')
    desk(m,'reception',32,8,name='入住柜台与账簿')
    m.box((31,3,8),(34,3,8),'dark_oak_slab[type=top]')
    m.set(32,4,8,'lectern[facing=south]')
    for x in (16,19):table(m,x,11,3,2)
    m.box((31,3,15),(34,4,15),'barrel')
    m.point('luggage_store','storage',(31,3,15),'大堂寄存',approach=(31,3,14))
    # Two rooms on each upper floor, with a south hall around the stair core.
    for level,floor in ((2,9),(3,16)):
        for xa,xb in ((14,22),(30,36)):
            partition=xb if xa==14 else xa
            m.box((partition,floor+1,6),(partition,floor+5,14),'white_terracotta')
            m.box((xa,floor+1,14),(xb,floor+5,14),'white_terracotta')
            side='west' if xa==14 else 'east'
            guestroom(m,f'guest_l{level}_front_{side}',f'{level}层临街双床房',xa,5,xb,14,floor,'south',18 if xa==14 else 33,14,compact=True)
            upper_door(m,f'balcony_l{level}_{side}',18 if xa==14 else 33,5,floor,'north')
        # South corridor connects guest wings on floor two.
        if level==2:upper_door(m,f'rear_gallery_l{level}',25,18,floor,'south')
        m.box((13,floor,2),(37,floor,4),'spruce_planks')
        rail(m,13,2,37,2,floor+1);rail(m,13,2,13,4,floor+1);rail(m,37,2,37,4,floor+1)
        # Small cornices beneath the usable balcony, not blocking its surface.
        m.box((13,floor-1,1),(37,floor-1,1),'deepslate_tile_slab[type=top]')
    for x in (14,22,30,36):
        m.box((x,2,3),(x,15,3),'dark_oak_log')
        for y in (7,14,21):m.set(x,y,4,'lantern[hanging=true]')
    # Ground-floor service wings; passenger rooms start on the second floor.
    for xa,xb in ((5,15),(35,45)):
        m.box((xa+1,3,36),(xb-1,7,36),'white_terracotta')
    m.room('dining','一层餐厅',(6,3,23),(14,7,35),'完整堂食空间')
    doorway(m,'dining_door',15,29,side='east')
    for z in (26,32):table(m,8,z,3,5)
    m.point('dining_service','work',(12,3,26),'堂食服务',approach=(12,3,28))
    m.room('tea_lounge','一层茶座与公共休息',(6,3,37),(14,7,49),'住客会客与茶水')
    doorway(m,'tea_door',15,43,side='east')
    table(m,8,41,3,5);table(m,8,46,3,5)
    desk(m,'tea_station',7,38,block='water_cauldron[level=3]',name='备茶取水')
    m.room('kitchen','一层后厨与食材',(36,3,23),(44,7,35),'烹调、备餐、食材存放')
    doorway(m,'kitchen_door',35,29,side='west')
    doorway(m,'kitchen_delivery_door',45,29,side='east')
    m.box((48,2,28),(48,4,30),'air')
    for z in (27,31):m.box((48,2,z),(48,5,z),'dark_oak_log')
    m.box((48,5,27),(48,5,31),'spruce_log[axis=z]')
    roof(m,48,27,48,31,6,ridge_x=False)
    m.point('delivery','entrance',(49,2,29),'厨房侧面送货入口',facing='east')
    kitchen(m,'cook',37,24);desk(m,'food_store',42,32,kind='storage',block='barrel',name='食材储存')
    m.room('laundry','一层洗漱与布草',(36,3,37),(44,7,42),'洗漱、布草清洗和收纳')
    doorway(m,'laundry_door',35,39,side='west');wash(m,'wash',37,38)
    desk(m,'linen',42,40,kind='storage',block='barrel',name='清洁布草')
    m.box((36,3,43),(44,7,43),'white_terracotta')
    m.room('staff','员工值守休息',(36,3,44),(44,7,49),'两张员工床，区别于对客床位')
    doorway(m,'staff_door',35,46,side='west')
    bed(m,'staff_a',38,48);bed(m,'staff_b',42,48)
    # Eight upper wing rooms open onto continuous two-block-clear balconies.
    for side,xa,xb in (('west',5,15),('east',35,45)):
        for z in (29,36,43):m.box((xa+1,10,z),(xb-1,14,z),'white_terracotta')
        for i,za in enumerate((22,29,36,43)):
            guestroom(m,f'guest_l2_{side}_{i+1}',f'二层{side}翼双床房 {i+1}',xa,za,xb,za+7,9,'east' if side=='west' else 'west',xb if side=='west' else xa,za+3)
    m.box((13,9,19),(37,9,21),'spruce_planks')
    for xa,xb in ((16,19),(31,34)):
        m.box((xa,9,21),(xb,9,51),'spruce_planks')
        outer=xb if xa==16 else xa
        rail(m,outer,22,outer,51,10)
        rail(m,xa,51,xb,51,10)
        for z in (22,29,36,43,50):
            m.box((outer,2,z),(outer,8,z),'dark_oak_log')
            m.set(outer,7,z+1,'lantern[hanging=true]')
        m.box((xa,15,22),(xb,15,51),'deepslate_tile_slab[type=bottom]')
    rail(m,20,21,30,21,10)
    rail(m,13,19,13,21,10);rail(m,37,19,37,21,10)
    for x in (17,33):m.point(f'wing_gallery_{x}','circulation',(x,10,48),'翼楼楼廊尽端')
    # Railing below an eave still needs vertical supports for the roof above it.
    for x in (19,31):
        for z in (22,29,36,43,50):m.box((x,11,z),(x,14,z),'dark_oak_log')
    stairs(m)
    building(m,'rear_store','后院物资与工具',21,45,29,52)
    doorway(m,'rear_store_door',25,45)
    desk(m,'maintenance_store',23,49,kind='storage',block='barrel',name='维护与清洁工具')
    m.box((23,1,31),(27,1,35),'stone_bricks')
    m.box((24,1,32),(26,1,34),'water')
    m.set(24,2,32,'lily_pad')
    garden(m,7,7,11,16);garden(m,39,7,43,16)
    for x in (20,30):
        m.set(x,2,38,'barrel');m.set(x,3,38,'potted_azalea_bush')
    return m


BUILDERS={'CH-11-v02':tall_inn}
