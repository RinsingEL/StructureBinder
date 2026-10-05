"""A new European medieval town: fortification, church, civic hall and trade."""
from .european_reborn_parts import (base,entry,work,shell,gable,timber,opening,window,stairs,railing,crenels,table,bed,lamp)
CATALOG_DIR='R04_european_reborn'


def castle():
    m=base(1,'欧洲城堡 · 双层主堡与环墙',(73,36,75),('防御','驻守','议事'))
    entry(m,36)
    # Thick curtain walls have an actual walkable top connected to one stair.
    for xa,za,xb,zb in ((6,12,66,14),(6,66,66,68),(6,14,8,66),(64,14,66,66)):
        m.box((xa,2,za),(xb,9,zb),'stone_bricks')
    m.box((33,2,12),(39,7,14),'air')
    for z in (12,68):
        for x in range(6,67,3):m.box((x,10,z),(min(x+1,66),11,z),'stone_bricks')
    for x in (6,66):
        for z in range(15,67,3):m.box((x,10,z),(x,11,z+1),'stone_bricks')
    for cx,cz in ((9,15),(63,15),(9,65),(63,65)):
        m.box((cx-4,2,cz-4),(cx+4,9,cz+4),'stone_bricks')
        crenels(m,(cx-4,cz-4,cx+4,cz+4),9)
        # Connecting openings in inward tower parapets.
        m.box((cx-1,10,cz-4),(cx+1,11,cz+4),'air')
        m.box((cx-4,10,cz-1),(cx+4,11,cz+1),'air')
        outer_x=cx-4 if cx<36 else cx+4
        outer_z=cz-4 if cz<36 else cz+4
        m.box((outer_x,10,cz-4),(outer_x,11,cz+4),'stone_bricks')
        m.box((cx-4,10,outer_z),(cx+4,11,outer_z),'stone_bricks')
    stairs(m,10,12,22,2,8)
    m.box((7,9,30),(12,9,33),'stone_bricks')
    for i in range(8):
        m.box((13,1,22+i),(13,2+i,22+i),'stone_bricks')
        m.set(13,3+i,22+i,'stone_brick_wall')
    railing(m,12,30,12,33,9)
    m.point('wallwalk','circulation',(7,10,32),'城墙巡逻通路')
    shell(m,(24,35,48,61),floor=1,height=14)
    m.box((24,8,35),(48,8,61),'spruce_planks')
    m.box((24,15,35),(48,15,61),'stone_bricks');crenels(m,(24,35,48,61),15)
    opening(m,36,35,width=3,height=5)
    # Two independent straight flights, cut through the appropriate floor.
    m.box((43,8,40),(45,8,47),'air');stairs(m,43,45,41,2,7)
    m.box((27,15,42),(29,15,49),'air');stairs(m,27,29,43,9,7)
    railing(m,42,41,42,47,8);railing(m,46,41,46,47,8)
    railing(m,26,43,26,49,15);railing(m,30,43,30,49,15)
    m.point('upper','circulation',(44,9,48),'主堡二层')
    m.point('roof','circulation',(28,16,50),'主堡瞭望平台')
    for x in (29,35):bed(m,f'guard_{x}',x,2,54)
    table(m,30,9,52,11,3);work(m,'council',36,9,50,'主堡议事桌')
    m.box((26,2,38),(32,3,38),'barrel')
    for x in (29,40):window(m,x,11,35,height=3)
    for z in (43,55):window(m,24,4,z,axis='z');window(m,48,11,z,axis='z')
    m.room('garrison','主堡驻守与仓储',(25,2,36),(47,7,60),'驻守休息与军需')
    m.room('council','主堡议事层',(25,9,36),(47,14,60),'议事与城堡管理')
    for x in (23,49):lamp(m,x,24)
    m.meta.update(roof_min_y=15,floors=[{'name':'内院与驻守','y':1,'max_y':7},{'name':'主堡议事与环墙','y':8,'max_y':14},{'name':'主堡瞭望台','y':15,'max_y':18}])
    return m


def church():
    m=base(2,'欧洲教堂 · 钟塔与十字中殿',(53,48,69),('礼仪','集会'))
    entry(m,26)
    shell(m,(17,16,35,57),height=18)
    for bounds in ((9,20,16,55),(36,20,43,55)):
        shell(m,bounds,height=8);gable(m,bounds,10,'stone_brick')
    # Transept projects beyond the aisles and opens into the nave.
    for bounds in ((5,36,16,46),(36,36,47,46)):
        shell(m,bounds,height=11);gable(m,bounds,13,'stone_brick')
    for xa,xb in ((16,17),(35,36)):
        for za,zb in ((24,28),(37,45),(49,52)):m.box((xa,2,za),(xb,8,zb),'air')
    gable(m,(17,16,35,57),20,'deepslate_tile')
    opening(m,26,16,width=5,height=8)
    # Bell tower is a distinct stone volume with an open belfry above the entrance.
    shell(m,(20,5,32,15),height=26)
    opening(m,26,5,width=5,height=8);opening(m,26,15,width=5,height=8)
    m.box((21,19,6),(31,19,14),'stone_bricks')
    for z in (5,15):m.box((23,21,z),(29,25,z),'air')
    for x in (20,32):m.box((x,21,8),(x,25,12),'air')
    m.box((25,26,9),(27,26,11),'dark_oak_log[axis=x]');m.set(26,25,10,'bell[attachment=ceiling]')
    gable(m,(20,5,32,15),28,'deepslate_tile')
    # Tall slit windows and external buttresses rhythm the nave.
    for z in (23,32,49,54):
        for x in (17,35):window(m,x,12,z,axis='z',glass='yellow_stained_glass',height=5)
    for x in (8,44):
        for z in (23,31,50):m.box((x,2,z),(x,9,z+1),'stone_bricks')
    for z in (24,29,34, 40):
        for xa,xb in ((21,24),(28,31)):m.box((xa,2,z),(xb,2,z),'birch_stairs[facing=south]')
    m.box((20,2,50),(32,3,55),'stone_bricks');stairs(m,24,28,48,2,2)
    table(m,24,4,53,5,2)
    m.point('altar','circulation',(26,4,51),'后部祭坛前方')
    m.room('nave','高挑中殿',(18,2,17),(34,18,56),'集会与礼仪')
    m.meta.update(roof_min_y=20,floors=[{'name':'中殿与侧廊','y':1,'max_y':10}])
    return m


def town_hall():
    m=base(3,'欧洲市政会馆 · 拱廊议事楼',(47,39,51),('公共办事','议事','文献保管'))
    entry(m,23)
    # Ground stone arcade carries a jettied timber upper storey.
    shell(m,(9,12,37,41),height=7)
    for x in (15,23,31):opening(m,x,12,width=3,height=5)
    timber(m,(8,11,38,42),floor=8,height=8)
    gable(m,(8,11,38,42),17,'brick')
    m.box((31,8,22),(33,8,29),'air');stairs(m,31,33,23,2,7)
    railing(m,30,23,30,29,8);railing(m,34,23,34,29,8)
    m.point('upper','circulation',(32,9,30),'议事层楼梯口')
    for x in (14,21):table(m,x,2,30,4,2)
    table(m,15,9,32,13,3)
    m.box((11,9,39),(25,11,40),'bookshelf')
    work(m,'service',16,2,27,'一层办事接待');work(m,'meeting',21,9,30,'二层议事桌')
    for x in (12,18,25,32):window(m,x,11,11,height=3)
    for z in (18,33):window(m,8,11,z,axis='z');window(m,38,11,z,axis='z')
    m.room('service','石拱廊办事厅',(10,2,13),(36,7,40),'市民接待、登记')
    m.room('council','木构议事与档案厅',(9,9,12),(37,15,41),'议事及文献保管')
    m.meta.update(roof_min_y=17,floors=[{'name':'办事拱廊','y':1,'max_y':7},{'name':'议事与档案','y':8,'max_y':15}])
    return m


def market_hall():
    m=base(4,'欧洲市场大厅 · 开敞木构长棚',(43,29,57),('集市交易','货物暂存'))
    entry(m,21)
    # Open posts, transverse trusses and longitudinal counters, not enclosed housing.
    for x in (9,33):
        for z in (10,20,30,40,48):m.box((x,2,z),(x,10,z),'dark_oak_log')
    for z in (10,20,30,40,48):
        m.box((9,10,z),(33,10,z),'dark_oak_log[axis=x]')
        for i in range(5):
            m.set(10+i,9-i,z,'dark_oak_log[axis=x]');m.set(32-i,9-i,z,'dark_oak_log[axis=x]')
    gable(m,(9,10,33,48),11,'brick')
    for x in (12,27):
        for z in (17,27,37):
            m.box((x,2,z),(x+3,2,z+3),'oak_planks')
            m.set(x+1,3,z+1,'barrel');m.set(x+2,3,z+2,'melon')
            work(m,f'stall_{x}_{z}',x-1,2,z+1,'摊位交易通道')
    for x in (10,29):m.box((x,2,46),(x+3,3,47),'barrel')
    m.room('market','双列摊位长厅',(10,2,11),(32,10,47),'开放式交易与货物暂存')
    m.point('aisle','circulation',(21,2,29),'中央交易通道')
    m.meta['roof_min_y']=11
    return m

BUILDERS={f'EU-{i:02d}-v01':fn for i,fn in ((1,castle),(2,church),(3,town_hall),(4,market_hall))}


def timber_home():
    m=base(5,'欧洲木骨架住宅 · 双层街屋',(31,33,37),('家庭居住',),role='self_contained')
    entry(m,15)
    shell(m,(7,9,23,30),height=6)
    timber(m,(6,8,24,31),floor=7,height=7)
    gable(m,(6,8,24,31),15,'brick')
    opening(m,15,9,width=1,height=3)
    m.box((19,7,17),(21,7,23),'air');stairs(m,19,21,18,2,6)
    railing(m,18,18,18,23,7);railing(m,22,18,22,23,7)
    m.point('upper','circulation',(20,8,24),'楼上寝居')
    table(m,10,2,17,5,2)
    m.set(9,2,27,'smoker[facing=east]');m.set(9,2,28,'water_cauldron[level=3]')
    work(m,'cook',10,2,27,'家庭炉灶')
    bed(m,'bed1',10,8,25);bed(m,'bed2',15,8,25)
    m.box((8,8,11),(12,9,11),'barrel')
    for x in (10,19):window(m,x,10,8,height=3)
    for z in (14,26):window(m,6,10,z,axis='z')
    m.box((8,3,27),(8,25,28),'bricks');m.box((7,25,26),(9,25,29),'brick_slab')
    m.room('living','石砌起居层',(8,2,10),(22,6,29),'家庭起居与备餐')
    m.room('sleep','木构寝居层',(7,8,9),(23,13,30),'寝居与衣物储藏')
    m.meta.update(roof_min_y=15,floors=[{'name':'起居','y':1,'max_y':6},{'name':'寝居','y':7,'max_y':13}])
    return m


def inn():
    m=base(6,'欧洲街角客栈 · 三层旅宿楼',(47,42,51),('旅宿接待','餐饮'),role='self_contained')
    entry(m,23)
    shell(m,(9,12,37,42),height=6)
    for floor in (8,15):timber(m,(8,11,38,43),floor=floor,height=6)
    gable(m,(8,11,38,43),22,'brick')
    opening(m,23,12,width=3,height=4)
    # Lower flight east, upper flight west; no staircase occupies its own landing.
    m.box((31,8,18),(33,8,25),'air');stairs(m,31,33,19,2,7)
    m.box((12,15,18),(14,15,25),'air');stairs(m,12,14,19,9,7)
    for floor,xa,xb in ((8,30,34),(15,11,15)):
        railing(m,xa,19,xa,25,floor);railing(m,xb,19,xb,25,floor)
    m.point('second','circulation',(32,9,26),'二层楼梯平台');m.point('third','circulation',(13,16,26),'三层楼梯平台')
    for x in (14,25):table(m,x,2,32,7,2)
    m.box((12,2,16),(19,2,16),'dark_oak_planks');m.set(13,3,16,'lantern')
    work(m,'reception',17,2,18,'客栈接待台')
    m.set(35,2,37,'smoker[facing=west]');m.set(35,2,39,'water_cauldron[level=3]');work(m,'kitchen',34,2,37,'后厨炉前')
    for floor in (8,15):
        # Two guest rooms face a common landing and front sitting hall.
        m.box((9,floor+1,29),(37,floor+6,29),'white_terracotta')
        m.box((23,floor+1,30),(23,floor+6,42),'dark_oak_planks')
        for x in (16,30):
            opening(m,x,29,floor+1,width=1,height=3)
            bed(m,f'bed_{floor}_{x}_a',x-3,floor+1,36)
            bed(m,f'bed_{floor}_{x}_b',x+1,floor+1,36)
            m.room(f'guest_{floor}_{x}','双床客房',(10 if x==16 else 24,floor+1,30),(22 if x==16 else 36,floor+6,42),'旅宿寝居')
        table(m,20,floor+1,18,5,2)
        for x in (12,20,29,35):window(m,x,floor+2,11,height=3)
    m.room('dining','接待与餐饮',(10,2,13),(36,7,41),'接待、餐饮与后厨')
    lamp(m,6,8);lamp(m,40,8)
    m.meta.update(roof_min_y=22,floors=[{'name':'接待餐饮','y':1,'max_y':7},{'name':'二层客房','y':8,'max_y':14},{'name':'三层客房','y':15,'max_y':21}])
    return m


def workshop():
    m=base(7,'欧洲铁匠作坊 · 炉棚侧院',(39,32,43),('工具制造','工具维护','货物仓储'),role='fill')
    entry(m,19)
    timber(m,(6,13,24,35),height=8);gable(m,(6,13,24,35),10,'brick')
    opening(m,15,13,width=3,height=4)
    # A separate lean-to supports outside hot work and keeps the main shop open.
    for x in (26,34):
        for z in (18,32):m.box((x,2,z),(x,7,z),'dark_oak_log')
    for x in range(25,36):m.box((x,8+(35-x)//4,17),(x,8+(35-x)//4,33),'dark_oak_slab')
    for x in (26,34):
        for z in (18,32):
            top=8+(35-x)//4
            m.box((x,7,z),(x,top-1,z),'dark_oak_log')
    for z in (17,33):
        for x in range(25,36):
            top=8+(35-x)//4
            m.set(x,top-1,z,'dark_oak_log[axis=x]')
    m.box((29,2,28),(33,5,32),'bricks')
    m.set(31,2,27,'blast_furnace[facing=north]')
    m.box((31,6,30),(32,23,31),'bricks')
    m.set(29,2,21,'anvil');m.set(33,2,22,'grindstone[face=floor]')
    work(m,'forge',31,2,26,'炉前操作');work(m,'anvil',28,2,21,'铁砧操作')
    for z in (21,29):m.box((8,2,z),(11,3,z),'barrel')
    m.box((18,2,23),(21,2,26),'crafting_table');work(m,'bench',17,2,24,'修配工作台')
    window(m,10,4,13);window(m,19,4,13)
    m.room('shop','修配与储物',(7,2,14),(23,8,34),'工具制作和成品储放')
    m.room('forge','开敞锻造棚',(27,2,19),(33,7,31),'热作业场地')
    m.meta['roof_min_y']=10
    return m


def bridge():
    m=base(8,'欧洲石桥 · 双岸桥头',(23,24,45),('步行过桥',),role='structure',tags=('infrastructure',))
    entry(m,11)
    m.box((1,1,13),(21,1,31),'water')
    stairs(m,8,14,9,2,4)
    m.box((8,5,13),(14,5,31),'stone_bricks')
    for i in range(4):
        y=2+i;z=35-i
        m.box((8,1,z),(14,y-1,z),'stone_bricks')
        m.box((8,y,z),(14,y,z),'stone_brick_stairs[facing=north]')
    for x in (7,15):
        for z in range(12,33):
            underside=2+min(2,(z-12)//3,(32-z)//3)
            m.box((x,underside,z),(x,5,z),'stone_bricks')
            m.set(x,6,z,'stone_brick_wall')
        for z in (12,32):m.box((x,1,z),(x,5,z),'stone_bricks')
    for x,z in ((5,9),(16,35)):lamp(m,x,z)
    m.point('middle','circulation',(11,6,22),'桥上通路');m.point('far_bank','circulation',(11,2,44),'对岸出口')
    m.meta.update(roof_min_y=None,floors=[{'name':'桥面与桥拱','y':1,'max_y':9}])
    return m


def street_lamp():
    m=base(9,'欧洲街灯 · 木柱铁吊灯',(11,16,11),('道路照明',),role='structure',tags=('infrastructure',))
    entry(m,5)
    m.box((4,2,4),(6,2,6),'stone_bricks');lamp(m,5,5)
    m.meta.update(roof_min_y=None,floors=[])
    return m


def fountain():
    m=base(10,'欧洲广场喷泉 · 八角石盆',(27,24,27),('水景观赏','休憩'),role='structure',tags=('landscape',))
    entry(m,13)
    from .elven_reborn_parts import oct_cells
    # Only the generic polygon rasterizer is reused, not elf building geometry.
    cells=oct_cells(5,5,21,21,4)
    for x,z in cells:
        edge=any((x+dx,z+dz) not in cells for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)))
        m.set(x,2,z,'stone_bricks' if edge else 'water')
    m.box((12,2,12),(14,6,14),'chiseled_stone_bricks')
    m.box((10,7,10),(16,7,16),'stone_bricks')
    m.box((11,7,11),(15,7,15),'water')
    m.box((13,7,13),(13,10,13),'stone_brick_wall');m.set(13,11,13,'lantern')
    for x in (3,23):
        for z in (9,16):m.set(x,2,z,'spruce_stairs[facing='+('east' if x==3 else 'west')+']')
    m.point('view','circulation',(13,2,3),'喷泉前广场')
    m.meta.update(roof_min_y=None,floors=[])
    return m


def oak_tree():
    m=base(11,'欧洲广场橡树 · 环树座椅',(33,30,33),('树木观赏','休憩'),role='structure',tags=('landscape',))
    entry(m,16)
    m.box((3,1,3),(29,1,29),'grass_block')
    m.box((15,2,15),(17,15,17),'oak_log')
    from .chinese_landscape import foliage
    for dx,dz,dy in ((-6,0,0),(6,2,1),(0,-6,2),(1,6,0),(0,0,5)):
        for t in range(1,7):m.set(16+round(dx*t/6),11+round((dy+4)*t/6),16+round(dz*t/6),'oak_log')
        foliage(m,16+dx,15+dy,16+dz,5,4,5,'oak_leaves')
    for x in range(10,23):
        for z in (10,22):m.set(x,2,z,'spruce_stairs[facing='+('north' if z==10 else 'south')+']')
    for z in range(11,22):
        for x in (10,22):m.set(x,2,z,'spruce_stairs[facing='+('west' if x==10 else 'east')+']')
    m.point('shade','circulation',(16,2,8),'树荫休憩位置')
    m.meta.update(roof_min_y=None,floors=[])
    return m


def garden():
    m=base(12,'欧洲宅旁花园 · 篱笆与花拱',(31,23,35),('花草观赏','园艺养护'),role='structure',tags=('landscape',))
    entry(m,15)
    m.box((4,1,5),(26,1,30),'grass_block')
    for z in (5,30):m.box((4,2,z),(26,2,z),'oak_fence[east=true,west=true]')
    for x in (4,26):m.box((x,2,5),(x,2,30),'oak_fence[north=true,south=true]')
    m.box((13,2,5),(17,2,5),'air')
    m.box((14,1,5),(16,1,29),'gravel');m.box((5,1,16),(25,1,18),'gravel')
    for x in (12,18):m.box((x,2,7),(x,7,7),'oak_fence')
    m.box((12,8,7),(18,8,7),'oak_slab')
    for x in range(11,20):
        for z in (6,8):m.set(x,8,z,'flowering_azalea_leaves[persistent=true]')
    for xa,xb in ((7,11),(19,23)):
        for za,zb in ((10,14),(21,27)):
            for x in range(xa,xb+1,2):
                for z in range(za,zb+1,2):m.set(x,2,z,'poppy' if za==10 else 'cornflower')
    m.box((12,2,28),(18,2,28),'spruce_stairs[facing=north]')
    m.point('crossing','circulation',(15,2,17),'花园十字小径')
    m.meta.update(roof_min_y=None,floors=[])
    return m

BUILDERS.update({f'EU-{i:02d}-v01':fn for i,fn in ((5,timber_home),(6,inn),(7,workshop),(8,bridge),(9,street_lamp),(10,fountain),(11,oak_tree),(12,garden))})
