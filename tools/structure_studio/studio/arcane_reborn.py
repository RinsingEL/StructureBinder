"""Independent R02 magic-academy structures, authored for the five-style rebuild."""
from .arcane_reborn_parts import (CATALOG_DIR, base, rect, gable, arch, entry,
    buttress, tower, spire, straight_stair, work, bed, planter, lamp, circle, entrance)


def great_hall():
    m=base(1,'星穹学院礼堂与讲学楼',(57,47,65),terms=['学院','礼堂','授课','集会'])
    entrance(m,28)
    # Long, tall nave; low classrooms have independent lateral depth.
    rect(m,16,13,40,55,top=20)
    gable(m,16,13,40,55,21)
    for x0,x1,key in ((5,15,'west'),(41,51,'east')):
        rect(m,x0,25,x1,51,top=10)
        gable(m,x0,25,x1,51,11)
        entry(m,(x0+x1)//2,25,key=key+'_entry')
        for z in (31,40,47):
            arch(m,z,x0,3,3,6,axis='z');arch(m,z,x1,3,3,6,axis='z')
        m.room(key+'_classroom','讲学侧厅',(x0+1,2,26),(x1-1,9,50),'课程演示和小班讨论')
        work(m,key+'_lecture',x0+5,2,29,'侧厅讲台')
        for z in (34,39,44):
            for x in (x0+3,x0+7):
                m.set(x,2,z,'dark_oak_stairs[facing=north]')
    # Twin unequal portal towers articulate the front; solid spires are roof decoration.
    tower(m,10,14,5,27,roof_height=12)
    tower(m,46,14,5,23,roof_height=11)
    entry(m,10,9,key='west_tower_entry');entry(m,46,9,key='east_tower_entry')
    m.room('west_tower','西门值守塔厅',(7,2,11),(13,8,17),'入口登记、储物；上部为尖顶构架')
    m.room('east_tower','东门教务塔厅',(43,2,11),(49,8,17),'教务等候与图书归还')
    work(m,'registrar',10,2,16,'学院接待台');work(m,'returns',46,2,16,'归还台','barrel')
    entry(m,28,13,width=5,key='hall_entry');entry(m,28,55,width=3,side='south',key='rear_exit')
    # Rose window above the entrance, distinct from the long lancets.
    for x in range(22,35):
        for y in range(10,19):
            d=(x-28)**2+(y-14)**2
            if d<=20:m.set(x,y,13,'amethyst_block' if d>=12 else 'purple_stained_glass')
    m.box((28,10,13),(28,18,13),'smooth_quartz')
    m.box((24,14,13),(32,14,13),'smooth_quartz')
    for z in (20,29,38,47):
        for x,side in ((16,'west'),(40,'east')):
            arch(m,z,x,10,3,8,axis='z');buttress(m,x,z,18,side)
    # Break through at ground level after the side-hall walls and buttresses.
    for x in (15,16,40,41):m.box((x,2,35),(x,5,37),'air')
    # Raised teaching dais, connected by broad front steps.
    m.box((20,2,47),(36,3,53),'polished_andesite')
    for x in range(24,33):
        m.set(x,2,45,'stone_brick_stairs[facing=south]')
        m.set(x,3,46,'stone_brick_stairs[facing=south]')
    work(m,'main_lectern',28,4,50,'礼堂主讲台')
    m.box((22,5,54),(34,11,54),'purple_terracotta')
    for x in (22,34):m.box((x,4,54),(x,13,54),'polished_blackstone_bricks')
    for z in range(23,43,4):
        for x0 in (20,31):
            m.box((x0,2,z),(x0+5,2,z),'birch_stairs[facing=south]')
    for x in (19,37):
        for z in (18,33,44):
            m.box((x,2,z),(x,16,z),'polished_andesite')
            m.set(x,15,z+1,'lantern[hanging=true]')
    m.room('nave','高挑礼堂',(17,2,14),(39,19,54),'中央集会通道、听讲席、两级讲台')
    m.point('central_aisle','circulation',(28,2,32),'礼堂主通道')
    m.point('dais','circulation',(28,4,48),'实体台阶连接讲台')
    for x in (22,34):lamp(m,x,6)
    for x in (5,51):
        for z in (56,59):planter(m,x,z)
    m.meta.update(roof_min_y=21,floors=[{'name':'礼堂与侧厅','y':1,'max_y':9}],
        design_notes=m.meta['design_notes']+['正厅通高 19 格，双塔不等高；入口玫瑰窗与侧向尖拱窗分别塑造正侧立面。',
        '讲学侧厅、主讲台与双门塔皆有实体通路；屋顶尖塔没有虚构可用楼层。'])
    return m


def library():
    m=base(2,'星环双层大图书馆',(55,38,59),terms=['图书馆','藏书','阅览','环廊'])
    entrance(m,27)
    rect(m,8,9,46,49,top=16)
    # Four wings form a true open atrium; the second-floor ring overlooks it.
    m.box((18,2,20),(36,34,38),'air')
    m.box((9,8,10),(45,8,48),'dark_oak_planks')
    m.box((18,8,20),(36,8,38),'air')
    for x in (18,36):m.box((x,2,20),(x,16,38),'calcite')
    for z in (20,38):m.box((18,2,z),(36,16,z),'calcite')
    for z in (24,29,34):
        arch(m,z,18,2,5,6,axis='z',glass=False);arch(m,z,36,2,5,6,axis='z',glass=False)
        arch(m,z,18,9,5,6,axis='z',glass=False);arch(m,z,36,9,5,6,axis='z',glass=False)
    for z in (20,38):
        for x in (22,27,32):
            arch(m,x,z,2,3,6,glass=False);arch(m,x,z,9,3,6,glass=False)
    # Gallery railings face the atrium; free ground arches lead into the reading garden.
    for z in range(21,38):
        for x in (18,36):m.set(x,9,z,'polished_blackstone_wall')
    for x in range(19,36):
        for z in (20,38):m.set(x,9,z,'polished_blackstone_wall')
    gable(m,8,9,46,18,17,axis='x')
    gable(m,8,40,46,49,17,axis='x')
    gable(m,8,19,16,39,17);gable(m,38,19,46,39,17)
    for cx,cz,top in ((8,9,22),(46,9,22),(8,49,25),(46,49,25)):
        tower(m,cx,cz,4,top,roof_height=8)
        # Upper turret floors meet the reading ring; openings face inward only.
        m.box((cx-3,8,cz-3),(cx+3,8,cz+3),'dark_oak_planks')
        inward_x=cx+4 if cx==8 else cx-4
        inward_z=cz+4 if cz==9 else cz-4
        m.box((inward_x,9,cz-1),(inward_x,13,cz+1),'air')
        m.box((cx-1,9,inward_z),(cx+1,13,inward_z),'air')
        # The corner turrets hold book stacks, entered from their adjacent wing.
        for xx in range(cx-1,cx+2):
            m.box((xx,2,cz-4),(xx,5,cz+4),'air')
        for zz in range(cz-1,cz+2):m.box((cx-4,2,zz),(cx+4,5,zz),'air')
    entry(m,27,9,width=5,key='library_entry')
    for x in (17,27,37):arch(m,x,9,10,3,5)
    for x in (8,46):
        for z in (20,29,38):arch(m,z,x,10,3,5,axis='z')
    # Dedicated rear stair climbs 7 blocks; final landing meets floor y=8.
    straight_stair(m,40,39,1,8,width=3,facing='south')
    for y in (2,9):
        for z in range(16,46,5):
            for x in (10,44):
                # Keep the stair bay clear on both levels.
                if x==44 and z>=36:continue
                m.box((x,y,z),(x,y+3,z+2),'bookshelf')
        for x in (14,19,35,40):
            m.box((x,y,12),(x+2,y+3,12),'bookshelf')
            if x!=40:m.box((x,y,46),(x+2,y+3,46),'bookshelf')
        for x,z in ((13,25),(41,25),(23,43),(31,15)):
            work(m,f'reading_{y}_{x}_{z}',x,y,z,'阅览书桌')
    # Central open-air reading garden: small pool and four low seats.
    circle(m,27,29,5,1,'polished_blackstone_bricks',ring=True)
    circle(m,27,29,3,1,'water')
    m.box((27,1,29),(27,3,29),'amethyst_block');m.set(27,4,29,'amethyst_cluster')
    for x,z in ((23,24),(31,24),(23,34),(31,34)):m.set(x,2,z,'dark_oak_stairs[facing=south]')
    m.room('lower_ring','首层藏书环廊',(9,2,10),(45,7,48),'藏书、借阅、庭院出入')
    m.room('upper_ring','二层阅览环廊',(9,9,10),(45,15,48),'环绕中庭的阅读与珍本收藏')
    m.room('atrium','露天阅览中庭',(19,2,21),(35,16,37),'小型紫晶水景、四侧可穿行的庭院')
    m.point('upper_landing','circulation',(41,9,47),'楼梯上端的二层落脚平台')
    m.point('atrium_walk','circulation',(27,2,23),'中庭游步')
    work(m,'loan_desk',22,2,15,'借阅登记柜台')
    m.meta.update(roof_min_y=17,floors=[{'name':'底层藏书与中庭','y':1,'max_y':7},{'name':'二层阅览环廊','y':8,'max_y':15}])
    return m


BUILDERS={'MG-01-v01':great_hall,'MG-02-v01':library}


def observatory():
    m=base(3,'星仪观测台 · 三层阶梯塔',(43,40,45),terms=['天文观测','教学'])
    entrance(m,21);rect(m,8,7,34,35,top=15)
    for y in (8,15):m.box((9,y,8),(33,y,34),'dark_oak_planks')
    entry(m,21,7,width=5,key='tower_entry')
    for y in (3,10):
        for z in (14,23,30):
            arch(m,z,8,y,3,5,axis='z');arch(m,z,34,y,3,5,axis='z')
        for x in (15,27):arch(m,x,35,y,3,5)
    straight_stair(m,10,11,1,8,width=3)
    straight_stair(m,28,28,8,15,width=3,facing='north')
    # Rails along stair voids and around the open observation terrace.
    m.box((13,9,11),(13,9,17),'polished_blackstone_wall')
    m.box((9,9,11),(9,9,17),'polished_blackstone_wall')
    m.box((27,16,22),(27,16,28),'polished_blackstone_wall')
    m.box((31,16,22),(31,16,28),'polished_blackstone_wall')
    for x in (8,34):m.box((x,16,7),(x,16,35),'polished_blackstone_wall')
    for z in (7,35):m.box((8,16,z),(34,16,z),'polished_blackstone_wall')
    for x,z in ((8,7),(34,7),(8,35),(34,35)):
        m.box((x-1,2,z-1),(x+1,23,z+1),'polished_andesite');spire(m,x,z,3,24,9)
    circle(m,21,22,6,15,'polished_blackstone_bricks',ring=True)
    m.box((21,16,22),(21,19,22),'chiseled_quartz_block')
    # A supported three-dimensional meridian ring rises over the instrument plinth.
    for x in range(16,27):
        for y in range(19,30):
            r=(x-21)**2+(y-24)**2
            if 19<=r<=28:m.set(x,y,22,'gold_block')
    m.box((21,20,22),(21,24,22),'end_rod');m.set(21,24,22,'amethyst_block')
    work(m,'star_controls',21,16,15,'星仪观测台控制席')
    work(m,'charts',21,9,29,'星图记录台');work(m,'arrival',17,2,12,'观测登记')
    m.room('lower','观测准备室',(9,2,8),(33,7,34),'登记、器材整理与上楼')
    m.room('charts','二层星图室',(9,9,8),(33,14,34),'星图研读和环廊')
    m.room('terrace','露天观测平台',(9,16,8),(33,23,34),'星仪、观测站位和四角尖柱')
    m.point('first_landing','circulation',(11,9,19),'二层楼梯落脚')
    m.point('roof_landing','circulation',(29,16,20),'观测台楼梯落脚')
    m.meta.update(roof_min_y=24,floors=[{'name':'准备室','y':1,'max_y':7},{'name':'星图层','y':8,'max_y':14},{'name':'观测平台','y':15,'max_y':30}])
    return m


def alchemy():
    m=base(4,'三翼炼金实验院',(49,36,49),terms=['实验','药材配制','教学'])
    entrance(m,24)
    # A central hexagonal experiment chamber has three different attached wings.
    tower(m,24,27,10,17,roof_height=12)
    rect(m,4,18,15,38,top=10);gable(m,4,18,15,38,11)
    rect(m,33,16,44,38,top=12);gable(m,33,16,44,38,13)
    rect(m,17,5,31,18,top=9);gable(m,17,5,31,18,10)
    entry(m,24,5,width=3,key='entrance_hall');entry(m,24,17,width=5)
    m.box((22,2,18),(26,6,18),'air')
    for x in (14,15,33,34):m.box((x,2,25),(x,6,29),'air')
    for x in (4,44):
        for z in (23,32):arch(m,z,x,3,3,6,axis='z')
    circle(m,24,27,5,1,'amethyst_block',ring=True)
    for x,z in ((21,24),(27,24),(21,30),(27,30)):
        work(m,f'lab_{x}_{z}',x,2,z,'独立实验工作席','brewing_stand')
        m.set(x,2,z-1,'water_cauldron[level=3]')
    for z in (22,31):
        work(m,'herb_'+str(z),7,2,z,'药材配制','crafting_table')
        m.box((5,2,z+3),(9,3,z+3),'barrel')
        work(m,'archive_'+str(z),41,2,z,'试剂与记录','lectern')
    work(m,'checkin',20,2,8,'实验登记与防护用品','barrel')
    m.room('experiment','高挑环形实验厅',(18,2,21),(30,12,33),'四个独立实验席与环行通道')
    m.room('herbal','草药处理翼',(5,2,19),(13,9,37),'药材备料与清洗')
    m.room('records','样品与记录翼',(35,2,17),(43,10,37),'样品储存和实验记录')
    m.meta.update(roof_min_y=18)
    return m


def student_house():
    m=base(5,'双层学院宿舍与自习厅',(37,34,39),role='self_contained',terms=['集体住宿','自习'])
    entrance(m,18);rect(m,6,7,30,32,top=15)
    m.box((7,8,8),(29,8,31),'dark_oak_planks');gable(m,6,7,30,32,16)
    entry(m,18,7,width=3,key='entry')
    arch(m,18,7,10,5,5)
    straight_stair(m,8,20,1,8,width=3,facing='north')
    m.box((11,9,14),(11,9,20),'polished_blackstone_wall')
    m.box((7,9,14),(7,9,20),'polished_blackstone_wall')
    for y in (3,10):
        for z in (13,24,29):
            arch(m,z,6,y,3,5,axis='z');arch(m,z,30,y,3,5,axis='z')
    for x,z in ((14,13),(23,13),(14,24),(23,24)):
        work(m,f'study_{x}_{z}',x,2,z,'底层自习书桌')
    work(m,'shared_wash',27,2,28,'公共洗漱','water_cauldron[level=3]')
    for x,z in ((15,13),(24,13),(15,27),(24,27)):bed(m,f'bed_{x}_{z}',x,9,z)
    m.box((12,9,19),(28,13,19),'calcite');m.box((18,9,19),(20,12,19),'air')
    m.room('study','共享自习与起居',(7,2,8),(29,7,31),'书桌、盥洗与楼梯')
    m.room('sleep','二层合住宿舍',(12,9,8),(29,14,31),'四张独立床位与隔开的寝室')
    m.point('landing','circulation',(9,9,12),'上层楼梯平台')
    m.meta.update(roof_min_y=16,floors=[{'name':'自习起居','y':1,'max_y':7},{'name':'二层寝居','y':8,'max_y':14}])
    return m


def supply_shop():
    m=base(6,'学院拱廊器材店',(31,30,31),role='fill',terms=['零售','修补维护'])
    entrance(m,15);rect(m,5,9,25,25,top=10);gable(m,5,9,25,25,11)
    entry(m,15,9,width=3,key='shop_entry')
    for x in (5,10,20,25):m.box((x,2,5),(x,8,5),'polished_andesite')
    m.box((5,9,5),(25,9,8),'polished_blackstone_brick_slab[type=top]')
    for x in (9,21):arch(m,x,9,3,3,6)
    work(m,'sales',10,2,13,'文具与器材柜台','barrel')
    work(m,'repair',21,2,20,'器材修补台','smithing_table')
    for z in (12,17,22):m.box((6,2,z),(7,4,z+1),'bookshelf')
    m.room('shop','器材销售与修补',(6,2,10),(24,9,24),'售卖、库存和修补区')
    return m


BUILDERS.update({'MG-03-v01':observatory,'MG-04-v01':alchemy,'MG-05-v01':student_house,'MG-06-v01':supply_shop})


def refectory():
    m=base(7,'学院长桌食堂与备餐翼',(43,34,43),role='self_contained',terms=['餐饮','集会'])
    entrance(m,19);rect(m,6,7,30,35,top=14);gable(m,6,7,30,35,15)
    rect(m,30,20,38,35,top=8);gable(m,30,20,38,35,9)
    entry(m,19,7,width=5,key='dining_entry');entry(m,34,35,side='south',key='service_entry')
    for x in (11,25):arch(m,x,7,5,3,7)
    m.box((30,2,25),(30,6,28),'air')
    for z in (13,21,29):
        for x in (6,30):arch(m,z,x,5,3,7,axis='z')
    for x in (12,23):
        m.box((x,2,13),(x+2,2,28),'birch_slab[type=top]')
        for z in (13,20,28):m.set(x+1,3,z,'lantern')
        for z in range(14,28,3):
            m.set(x-1,2,z,'dark_oak_stairs[facing=east]');m.set(x+3,2,z,'dark_oak_stairs[facing=west]')
    work(m,'kitchen',33,2,22,'后厨炉灶','smoker[facing=south]')
    work(m,'water',36,2,31,'清洗取水','water_cauldron[level=3]')
    work(m,'serving',26,2,32,'供餐台','barrel')
    m.room('dining','高挑长桌厅',(7,2,8),(29,13,34),'两排长桌及中间通行轴')
    m.room('kitchen','独立备餐翼',(31,2,21),(37,7,34),'后勤入口、备餐与洗涤')
    m.meta['roof_min_y']=15
    return m


def gallery_bridge():
    m=base(8,'学院尖拱步廊桥',(19,28,41),role='structure',tags=['infrastructure'],terms=['步行过桥'])
    entrance(m,9)
    m.box((1,1,12),(17,1,28),'water')
    for z in range(7,34):
        rise=min(3,max(0,min(z-7,33-z)));y=1+rise
        for x in range(6,13):
            if z<=10 or z>=30:m.box((x,1,z),(x,y,z),'stone_bricks')
            m.set(x,y,z,'stone_brick_stairs[facing=south]' if z<=10 else 'stone_brick_stairs[facing=north]' if z>=30 else 'polished_andesite')
        for x in (6,12):m.set(x,y+1,z,'polished_blackstone_wall')
    for z in (12,20,28):
        for x in (6,12):m.box((x,2,z),(x,13,z),'polished_andesite')
        m.box((6,14,z),(12,14,z),'polished_andesite')
        arch(m,9,z,5,5,9,glass=False)
        m.set(6,15,z,'amethyst_cluster');m.set(12,15,z,'amethyst_cluster')
    gable(m,6,11,12,29,15)
    m.point('bridge_mid','circulation',(9,5,20),'廊桥中段');m.point('far_bank','circulation',(9,2,38),'另一岸步行衔接')
    m.meta.update(roof_min_y=15,floors=[{'name':'桥面','y':3,'max_y':9}])
    m.meta['terrain']['水岸']='模型自带静态水渠；两岸路面 Y=2，桥面 Y=5，实际河道与航行条件另行核对。'
    return m


def crystal_lamp():
    m=base(9,'学院晶石路灯',(11,18,11),role='structure',tags=['infrastructure'],terms=['道路照明'])
    entrance(m,5);lamp(m,5,5,height=6)
    for x in (3,7):
        m.box((x,2,5),(x,7,5),'polished_andesite');m.set(x,8,5,'end_rod')
    m.box((3,7,5),(7,7,5),'polished_blackstone_bricks')
    m.point('maintenance','work',(5,2,3),'灯座检修站位')
    m.meta.update(roof_min_y=None,floors=[])
    return m


def star_fountain():
    m=base(10,'学院星环喷泉',(27,24,27),role='structure',tags=['landscape'],terms=['水景观赏'])
    entrance(m,13)
    circle(m,13,14,9,1,'polished_blackstone_bricks')
    circle(m,13,14,8,2,'polished_blackstone_brick_slab',ring=True)
    circle(m,13,14,7,1,'water')
    circle(m,13,14,3,2,'smooth_quartz');circle(m,13,14,3,3,'smooth_quartz',ring=True)
    circle(m,13,14,2,3,'water')
    m.box((13,2,14),(13,7,14),'chiseled_quartz_block')
    m.set(13,8,14,'sea_lantern');m.set(13,9,14,'amethyst_cluster')
    for x,z in ((4,5),(22,5),(4,23),(22,23)):planter(m,x,z)
    m.point('view','circulation',(13,2,4),'喷泉前观赏位')
    m.meta.update(roof_min_y=None,floors=[],design_notes=m.meta['design_notes']+['双层封闭水盘，静态泉池不依赖悬空水流或外部补水机制。'])
    return m


def courtyard_tree():
    from .chinese_landscape import foliage
    m=base(11,'学院庭树 · 白桦读书角',(25,29,25),role='structure',tags=['landscape'],terms=['树木观赏','阅览'])
    entrance(m,12);m.box((3,1,6),(21,1,22),'grass_block')
    m.box((12,2,13),(12,18,13),'birch_log')
    for x,z,y in ((8,10,16),(16,10,18),(8,17,19),(16,17,16),(12,13,22)):
        m.box((min(x,12),y-2,min(z,13)),(max(x,12),y-2,max(z,13)),'birch_log')
        foliage(m,x,y,z,4,3,4,'birch_leaves')
    for x in (6,17):
        m.box((x,2,4),(x+2,2,4),'dark_oak_stairs[facing=south]')
    m.point('reading','circulation',(12,2,4),'庭树下读书站位');m.meta.update(roof_min_y=None,floors=[])
    return m


def herb_garden():
    m=base(12,'学院药草花园 · 四圃与凉亭',(33,28,33),role='structure',tags=['landscape'],terms=['园艺养护','花草观赏'])
    entrance(m,16)
    for x0,z0 in ((5,7),(20,7),(5,20),(20,20)):
        m.box((x0,1,z0),(x0+7,1,z0+6),'grass_block')
        for x in range(x0,x0+8):
            for z in range(z0,z0+7):
                if x in (x0,x0+7) or z in (z0,z0+6):m.set(x,2,z,'polished_blackstone_brick_slab')
                elif (x+z)%2: m.set(x,2,z,'allium' if x%3 else 'blue_orchid')
    for x,z in ((13,13),(19,13),(13,19),(19,19)):m.box((x,2,z),(x,7,z),'polished_andesite')
    spire(m,16,16,5,8,9)
    work(m,'seed_notes',16,2,16,'园艺记录台')
    m.point('garden_path','circulation',(16,2,23),'四圃之间的养护路')
    m.meta['roof_min_y']=8
    return m


BUILDERS.update({'MG-07-v01':refectory,'MG-08-v01':gallery_bridge,'MG-09-v01':crystal_lamp,
                 'MG-10-v01':star_fountain,'MG-11-v01':courtyard_tree,'MG-12-v01':herb_garden})
