"""Independent elven civic structures: faceted halls, slender arches and living courts."""
from .elven_reborn_parts import base,pavilion,entrance,path,branch_tree,planter,seat,desk,bookcase,lamp,arch
CATALOG_DIR='R01_elven_reborn'


def arrival(m,x):
    m.point('front','entrance',(x,2,0),'外部步行入口',facing='north')
    path(m,x-2,0,x+2,10)


def council():
    m=base(1,'精灵高庭议事宫',(67,42,65),['议事','正式接见'])
    arrival(m,33)
    pavilion(m,'high_hall','高台议事殿',(20,26,46,52),floor=4,height=14,roof_height=18,cut=5)
    entrance(m,'main_door',33,26,4,5,height=9)
    for x0,x1,key in ((4,18,'west'),(48,62,'east')):
        pavilion(m,key,'侧庭会谈室',(x0,19,x1,35),floor=2,height=8,roof_height=9,cut=3)
        entrance(m,key+'_entry',(x0+x1)//2,19,2)
        desk(m,(x0+x1)//2,3,27);m.point(key+'_desk','work',((x0+x1)//2+2,3,27),'侧庭会谈席')
    # Radial council seats leave an axial approach to the raised speaker.
    for x,z,face in ((26,34,'east'),(26,39,'east'),(26,44,'east'),(40,34,'west'),(40,39,'west'),(40,44,'west')):seat(m,x,5,z,face)
    desk(m,33,5,46);m.point('speaker','work',(33,5,45),'议事主持席')
    path(m,9,12,57,16);path(m,30,15,36,22)
    for x in (11,55):branch_tree(m,x,51,height=15,radius=3)
    for x in (24,42):lamp(m,x,12)
    m.meta.update(roof_min_y=16,floors=[{'name':'议事与侧庭','y':1,'max_y':10}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def sanctuary():
    m=base(2,'精灵月泉圣所',(53,40,55),['礼仪','水景观赏'])
    arrival(m,26)
    pavilion(m,'sanctum','月泉祭仪厅',(11,12,41,43),floor=3,height=12,roof_height=18,cut=6)
    entrance(m,'entry',26,12,3,5,height=10)
    for x in range(21,32):
        for z in range(23,34):
            r=(x-26)**2+(z-28)**2
            if r<=25:m.set(x,3,z,'water')
            elif r<=36:m.set(x,3,z,'chiseled_quartz_block')
    desk(m,26,4,38);m.set(26,5,38,'sea_lantern')
    m.point('ritual','work',(26,4,36),'月泉后方礼仪站位')
    for x in (18,34):
        for z in (22,28,34):seat(m,x,4,z,'east' if x==18 else 'west')
    for x,z in ((6,9),(46,9),(6,47),(46,47)):planter(m,x,z,'lily_of_the_valley')
    m.meta.update(roof_min_y=16,floors=[{'name':'泉池与祭仪','y':2,'max_y':10}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def archive():
    m=base(3,'精灵双庭藏卷馆',(59,34,49),['文献保管','阅览'])
    arrival(m,29)
    for x0,x1,key in ((5,25,'west'),(33,53,'east')):
        pavilion(m,key,'藏卷与阅览',(x0,14,x1,39),floor=2,height=10,roof_height=15,cut=4)
        entrance(m,key+'_entry',(x0+x1)//2,14,2,3,height=8)
        for z in (23,30):
            bookcase(m,x0+5,z,x0+6,z+2)
            bookcase(m,x1-6,z,x1-5,z+2)
        desk(m,(x0+x1)//2,3,33);m.point(key+'_reader','work',((x0+x1)//2,3,32),'阅览书桌')
    path(m,13,8,45,11);path(m,27,10,31,42)
    # Open reading colonnade between independent faceted halls.
    for z in (19,27,35):
        for x in (27,31):m.box((x,2,z),(x,8,z),'quartz_pillar')
        arch(m,29,z,2,3,7)
    branch_tree(m,29,43,height=16,radius=2)
    m.point('colonnade','circulation',(29,2,27),'开敞阅览连廊')
    m.meta['roof_min_y']=13
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def arts_hall():
    m=base(4,'精灵艺学堂 · 林间乐庭',(53,34,57),['教学','演出','集会'])
    arrival(m,26)
    pavilion(m,'stage','开敞讲演厅',(13,27,39,47),floor=3,height=8,roof_height=15,cut=4)
    entrance(m,'stage_entry',26,27,3,7,height=7)
    m.box((19,4,27),(33,9,27),'air')
    desk(m,26,4,39);m.point('teaching','work',(26,4,38),'讲演席')
    for z in (12,17,22):
        for xa,xb in ((14,22),(30,38)):
            m.box((xa,2,z),(xb,2,z),'birch_stairs[facing=south]')
    for x in (6,46):branch_tree(m,x,36,height=16,radius=2)
    path(m,24,2,28,24)
    m.point('audience','circulation',(26,2,18),'观演中央通道')
    m.meta.update(roof_min_y=12,floors=[{'name':'讲演与乐庭','y':1,'max_y':8}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m

BUILDERS={'EL-01-v01':council,'EL-02-v01':sanctuary,'EL-03-v01':archive,'EL-04-v01':arts_hall}

# Daily buildings occupy stepped terraces; their circulation is authored separately.
from .elven_reborn_parts import rail,stair_north,spire_roof


def sleeping(m,key,x,y,z):
    m.bed(x,y,z,'white','south')
    m.point(key,'rest',(x,y,z),'床位',approach=(x+1,y,z))


def terrace_home():
    m=base(5,'精灵露台小宅',(37,30,39),['家庭居住'],role='self_contained')
    arrival(m,18)
    path(m,8,10,28,31,3)
    m.box((8,1,10),(28,2,31),'polished_diorite')
    pavilion(m,'home','切角起居与寝居',(10,17,26,32),floor=3,height=7,roof_height=12,cut=3)
    entrance(m,'home_door',18,17,3,3,height=6)
    stair_north(m,16,20,8,2,2)
    for xa,xb in ((8,15),(21,28)):rail(m,xa,10,xb,10,3)
    rail(m,8,11,8,30,3);rail(m,28,11,28,30,3)
    sleeping(m,'bed',14,4,26)
    desk(m,21,4,23);m.point('table','work',(21,4,22),'家庭起居桌')
    m.set(22,4,28,'smoker[facing=north]');m.set(23,4,28,'barrel')
    m.point('cook','work',(22,4,27),'备餐台')
    for x in (11,25):
        m.set(x,3,12,'moss_block');m.set(x,4,12,'flowering_azalea')
    m.meta.update(roof_min_y=11,floors=[{'name':'露台与起居','y':2,'max_y':8}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def arcade_shop():
    m=base(6,'精灵叶廊商铺',(35,30,37),['零售','货物仓储'],role='fill')
    arrival(m,17)
    pavilion(m,'shop','前厅销售与后仓',(7,14,27,30),floor=2,height=8,roof_height=13,cut=3)
    entrance(m,'shop_door',17,14,2,3,height=7)
    # Transparent canopy on branching piers, separate from the pitched shop roof.
    for x in (9,25):
        m.box((x,2,7),(x,8,7),'quartz_pillar')
        m.box((x,8,8),(x,8,13),'quartz_pillar')
    for x in range(8,27):
        for z in range(7,14):m.set(x,9+abs(x-17)//5,z,'light_blue_stained_glass')
    for z in (19,22):
        m.box((10,3,z),(12,3,z),'birch_planks');m.set(11,4,z,'flower_pot')
    m.box((21,3,19),(23,3,23),'birch_planks');m.set(22,4,21,'lantern')
    m.box((11,3,27),(23,3,27),'barrel')
    m.point('counter','work',(20,3,21),'销售柜台');m.point('stock','storage',(17,3,26),'后仓货架')
    path(m,13,4,21,13)
    m.meta['roof_min_y']=10
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def terrace_inn():
    m=base(7,'精灵双台旅舍 · 桥廊客房',(59,38,55),['旅宿接待','餐饮'],role='self_contained')
    arrival(m,29)
    pavilion(m,'lobby','接待与用餐',(19,9,39,29),floor=2,height=9,roof_height=13,cut=4)
    entrance(m,'lobby_entry',29,9,2,3,height=7)
    desk(m,24,3,19);desk(m,33,3,22)
    m.point('reception','work',(24,3,18),'旅舍登记');m.point('dining','work',(33,3,21),'餐饮桌')
    # Rear stair climbs to a walkable bridge linking two elevated guest pavilions.
    path(m,27,30,31,33)
    arch(m,29,29,3,3,7)
    stair_north(m,27,31,33,2,5)
    path(m,13,38,45,41,6)
    for x in (14,23,35,44):m.box((x,1,39),(x,5,39),'quartz_pillar')
    rail(m,13,38,26,38,6);rail(m,32,38,45,38,6);rail(m,13,41,45,41,6)
    for x0,x1,key in ((5,21,'west'),(37,53,'east')):
        pavilion(m,key,'高台双床客舍',(x0,29,x1,48),floor=6,height=8,roof_height=13,cut=3)
        # Open inward-facing door reaches the bridge at the same level.
        wall=x1 if key=='west' else x0
        arch(m,39,wall,7,3,6,axis='z')
        m.point(key+'_door','circulation',(wall,7,39),'客舍桥廊入口')
        sleeping(m,key+'_bed1',x0+5,7,34);sleeping(m,key+'_bed2',x0+10,7,43)
        desk(m,x0+5,7,43)
    m.point('bridge','circulation',(29,7,39),'客舍桥廊')
    for x in (8,50):branch_tree(m,x,13,height=16,radius=3)
    m.meta.update(roof_min_y=12,floors=[{'name':'接待大厅','y':1,'max_y':9},{'name':'高台客舍与桥廊','y':6,'max_y':12}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def slender_bridge():
    m=base(8,'精灵细拱步桥',(21,21,43),['步行过桥'],role='structure',tags=('infrastructure',))
    arrival(m,10)
    m.box((1,1,12),(19,1,30),'water')
    # Deck rises in discrete walkable steps; underside follows an open arch.
    for z in range(7,36):
        rise=min(4,(z-7)//2,(35-z)//2)
        floor=1+rise
        for x in range(7,14):
            m.set(x,floor,z,'smooth_quartz')
            if 9<=z<=15 and (z-7)%2==0:m.set(x,floor,z,'quartz_stairs[facing=south]')
            if 27<=z<=33 and (35-z)%2==0:m.set(x,floor,z,'quartz_stairs[facing=north]')
        for x in (6,14):
            m.set(x,floor,z,'quartz_pillar');m.set(x,floor+1,z,'quartz_slab')
    for z in (11,31):
        for x in (6,14):
            m.box((x,1,z),(x,9,z),'quartz_pillar')
            m.set(x,10,z,'sea_lantern');m.set(x,11,z,'quartz_slab')
    path(m,8,36,12,42)
    m.point('far_bank','circulation',(10,2,42),'对岸步行出口')
    m.point('crown','circulation',(10,6,21),'桥拱最高处')
    m.meta.update(roof_min_y=None,floors=[{'name':'桥面','y':1,'max_y':14}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def branch_lamp():
    m=base(9,'精灵枝形路灯',(13,18,13),['道路照明'],role='structure',tags=('infrastructure',))
    arrival(m,6)
    path(m,3,3,9,9)
    m.box((6,2,6),(6,10,6),'quartz_pillar')
    for s in (-1,1):
        for i in range(1,4):m.set(6+s*i,8+i//2,6,'quartz_pillar')
        m.set(6+s*3,8,6,'soul_lantern[hanging=true]')
        m.set(6+s*3,10,6,'waxed_oxidized_cut_copper_slab')
    m.set(6,11,6,'sea_lantern');m.set(6,12,6,'end_rod')
    m.meta['roof_min_y']=None
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def ancient_tree():
    m=base(10,'精灵古树 · 环根小径',(35,34,35),['树木观赏'],role='structure',tags=('landscape',))
    arrival(m,17)
    branch_tree(m,17,18,height=23,radius=8,silver=False)
    m.box((16,2,17),(18,16,19),'stripped_oak_log')
    for dx,dz in ((-1,0),(1,0),(0,-1),(0,1)):
        for r in range(2,6):
            for y in range(2,6-r//2):m.set(17+dx*r,y,18+dz*r,'stripped_oak_log')
    for x in range(3,32):
        for z in range(3,32):
            d=(x-17)**2+(z-18)**2
            if 90<=d<=130:m.set(x,1,z,'smooth_quartz')
    path(m,15,6,19,10)
    for x,z in ((9,11),(25,11),(7,22),(27,22),(16,29)):planter(m,x,z,'azalea')
    for x in (10,24):seat(m,x,2,27,'north')
    m.meta.update(roof_min_y=None,floors=[{'name':'树根与环路','y':1,'max_y':7}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def crescent_bed():
    m=base(11,'精灵月牙花坛',(23,14,23),['花草观赏','园艺养护'],role='structure',tags=('landscape',))
    arrival(m,11)
    for x in range(3,20):
        for z in range(5,21):
            outer=(x-11)**2+(z-12)**2;inner=(x-14)**2+(z-9)**2
            if outer<=64 and inner>=30:
                m.set(x,2,z,'moss_block')
                if outer>=47 or inner<=41:m.set(x,2,z,'quartz_slab')
                elif (x+z)%3==0:m.set(x,3,z,'allium' if x%2 else 'lily_of_the_valley')
    path(m,10,0,12,8)
    seat(m,15,2,8,'south');lamp(m,17,6)
    m.meta['roof_min_y']=None
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def spring_pool():
    m=base(12,'精灵阶泉庭 · 三层水台',(31,24,35),['水景观赏','休憩'],role='structure',tags=('landscape',))
    arrival(m,15)
    # Closed bowls retain static water; surrounding stairs provide a separate dry route.
    for xa,xb,za,zb,y in ((7,23,10,21,2),(10,20,19,26,4),(12,18,24,29,6)):
        m.box((xa,1,za),(xb,y,zb),'smooth_quartz')
        m.box((xa+1,y,za+1),(xb-1,y,zb-1),'water')
    for x0,x1 in ((4,6),(24,26)):stair_north(m,x0,x1,20,2,5)
    path(m,4,25,11,29,6);path(m,19,25,26,29,6)
    for x in (4,26):rail(m,x,25,x,29,6)
    for x in (5,25):m.box((x,1,27),(x,5,27),'quartz_pillar')
    rail(m,4,29,11,29,6);rail(m,19,29,26,29,6)
    rail(m,7,25,11,25,6);rail(m,19,25,23,25,6)
    for x in (9,21):branch_tree(m,x,29,height=15,radius=2)
    for x in (4,26):lamp(m,x,12)
    m.point('water_view','circulation',(5,7,27),'上层观泉平台')
    m.meta.update(roof_min_y=None,floors=[{'name':'阶泉与游步','y':1,'max_y':12}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m

BUILDERS.update({f'EL-{i:02d}-v01':fn for i,fn in ((5,terrace_home),(6,arcade_shop),(7,terrace_inn),(8,slender_bridge),(9,branch_lamp),(10,ancient_tree),(11,crescent_bed),(12,spring_pool))})
