"""Seven civic arcane assets; portals and power apparatus are static scenery."""
from .model import Model
from .components import shell, window, hip_roof, shelf, pendant, bench
from .samples import railing


def base(code,name,w,d,h=30,site='城镇稳定干燥台地，连接学院和普通街道',role='key'):
    m=Model(code+'-v01',name,(w,h,d),family=code,civilization='魔导学城',role=role,terrain={
        '选址':site,'高程':'主地板Y=3，普通道路脚底接Y=4；基础底Y=0须落在连续承载地层',
        '地形处理':'模板是人工整平石基，不适用于未经处理的峡谷、水域或陡坡。观测馆上台阶为模板内实体台地。',
        '接驳':'南北朝向以作者入口标记为准；外部道路、服务范围和供能管线另行核对。',
        '魔法边界':'紫晶、终界烛、传送环、隔离屏与供能接口仅为静态原版陈设，不代表传送、供能、治疗或异常模拟。'})
    m.meta.update(source='tools/structure_studio/studio/arcane_academy.py',roof_min_y=10,
        floors=[dict(name='城台主层',y=3,max_y=9)],preview_context=dict(kind='flat',land_surface_y=4,padding=4,surface='grass'))
    m.box((2,0,2),(w-3,2,d-3),'stone_bricks');m.box((2,3,2),(w-3,3,d-3),'polished_andesite')
    m.box((2,4,2),(w-3,h-1,d-3),'air')
    return m


def hall(m,x0,z0,x1,z1,h=6,f=3):
    shell(m,(x0,f,z0),(x1,f+h,z1),'calcite',floor='smooth_stone',ceiling='polished_diorite')
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,f+1,z),(x,f+h+1,z),'polished_deepslate')
    for z in (z0,z1):
        m.box((x0,f+h,z),(x1,f+h,z),'purple_terracotta')
        for x in range(x0+2,x1-1,4):window(m,(x,f+2,z),(min(x+1,x1-1),f+4,z),color='purple_stained_glass')
    for x in (x0,x1):
        for z in range(z0+3,z1-1,5):window(m,(x,f+2,z),(x,f+4,z+1),axis='z',color='purple_stained_glass')
    # Eave tier meets the full ceiling; each inner tier overlaps below it.
    hip_roof(m,x0-1,x1+1,z0-1,z1+1,f+h+1,material='polished_blackstone_brick',tiers=min(4,(x1-x0)//2,(z1-z0)//2))
    pendant(m,(x0+x1)//2,f+h-1,(z0+z1)//2,f+h+1)


def door(m,x,z,face='north',f=3):m.door(x,f+1,z,'dark_oak',face)


def entry(m,x,z,f=3):
    m.point('entry','entrance',(x,f+1,z),'普通步行入口',facing='north')
    m.meta['connections'].append(dict(kind='pedestrian',pos=[x,f,z],direction='north',clearance=[3,4],note='外接连续普通街道；魔法设施不替代步行'))


def room(m,key,name,x0,z0,x1,z1,purpose,f=3,h=6):m.room(key,name,(x0,f+1,z0),(x1,f+h,z1),purpose)


def counter(m,key,x,z,w=5,block='lectern[facing=south]',label='登记与操作',f=3):
    m.box((x,f+1,z),(x+w-1,f+1,z),'dark_oak_planks');m.set(x+1,f+1,z,block)
    m.set(x+w-1,f+2,z,'lantern[hanging=false]');m.point(key,'work',(x+1,f+1,z),label,approach=(x+1,f+1,z+1))


def storage(m,key,x,z,w=4,contents='barrel',f=3,side=1):
    shelf(m,x,f+1,z,w,'dark_oak',contents)
    m.point(key,'storage',(x+1,f+2,z),'分类用品和档案',approach=(x+1,f+1,z+side))


def dining(m,x,z,w=5,f=3):
    m.box((x,f+1,z),(x+w-1,f+1,z),'dark_oak_slab[type=top]')
    for dx in range(0,w,2):
        m.set(x+dx,f+1,z-2,'dark_oak_stairs[facing=south]');m.set(x+dx,f+1,z+2,'dark_oak_stairs[facing=north]')
    m.set(x+w//2,f+2,z,'lantern[hanging=false]')


def kitchen(m,key,x,z,f=3):
    for dx,b in enumerate(['smoker[facing=south]','crafting_table','water_cauldron[level=3]','dark_oak_planks','barrel[facing=south]']):m.set(x+dx,f+1,z,b)
    m.set(x+3,f+2,z,'flower_pot');m.point(key,'work',(x+1,f+1,z),'连续备餐、清洗与食品存储',approach=(x+1,f+1,z+1))


def bed(m,key,x,z,f=3):
    m.bed(x,f+1,z,'purple','north');m.set(x-1,f+1,z-1,'barrel[facing=east]');m.set(x-1,f+2,z-1,'lantern[hanging=false]')
    m.box((x-1,f+1,z-2),(x-1,f+3,z-2),'dark_oak_planks');m.point(key,'sleep',(x,f+1,z),'有个人灯柜的床位',approach=(x+1,f+1,z))


def portal(m,x,z,w=9,y=4,broken=False):
    for dx in range(w):
        rise=min(dx,w-1-dx,3)
        if dx in (0,w-1):m.box((x+dx,y,z),(x+dx,y+6,z),'polished_blackstone_bricks')
        else:m.box((x+dx,y+4+rise,z),(x+dx,y+5+rise,z),'chiseled_polished_blackstone')
    for dx in (0,w-1):m.set(x+dx,y+7,z,'amethyst_block')
    if broken:m.box((x+w-4,y+5,z),(x+w-1,y+9,z),'air')
    else:
        for dx in (2,w-3):m.set(x+dx,y+5,z,'end_rod[facing=down]')


def finish(m,note):
    m.meta['design_notes']=[note,'房间与真实操作点逐项标记。魔法设备只作静态表达；本次未启动Minecraft。']
    m.meta['differences']=[note]
    return m


def station():
    m=base('AA-01','三门环庭 · 传送驿站',49,45)
    hall(m,5,7,16,32);hall(m,32,7,43,32);hall(m,17,29,31,39,h=8)
    door(m,16,16,'east');door(m,32,16,'west');door(m,24,29)
    # A high open gate is the architectural centre, separate from ordinary access.
    portal(m,20,22);m.box((20,3,19),(28,3,25),'purple_terracotta')
    for x in (19,29):m.set(x,4,22,'amethyst_block');m.set(x,5,22,'end_rod')
    counter(m,'register',7,10,7,label='目的地登记与票据核对');storage(m,'destinations',7,8,7,'bookshelf')
    dining(m,8,20,5);storage(m,'luggage',7,29,6,side=-1)
    counter(m,'prepare',34,11,6,'brewing_stand','行前材料与行李检查');storage(m,'supplies',34,8,6)
    bench(m,35,4,20,5,'north','dark_oak');counter(m,'dispatch',34,27,6,'cartography_table','普通道路与换乘说明')
    counter(m,'control',19,32,8,'enchanting_table','站务观察及静态环控制');storage(m,'records',19,37,9,'bookshelf',side=-1)
    m.point('ring','work',(24,5,22),'失能时仍可绕行的静态传送环',approach=(24,4,20))
    room(m,'waiting','登记候行与行李翼',6,8,15,31,'登记、双侧坐席、目的地档案、行李存柜')
    room(m,'prepare','准备与普通交通翼',33,8,42,31,'检验台、材料柜、等候长凳和普通道路说明')
    room(m,'dispatch','后部高站务厅',18,30,30,38,'站务控制与档案，面向庭院传送环',h=8)
    entry(m,24,3)
    return finish(m,'双窄翼围出通行庭院，后部高站务厅与露天传送环形成轴线；公共候行、准备和控制独立。')


def quarantine():
    m=base('AA-02','梳齿清检院 · 传送检疫所',53,49,site='传送驿站步行可达的独立侧街，避免必经人流穿越隔离庭')
    hall(m,5,7,23,19);hall(m,5,24,18,42);hall(m,25,7,46,16)
    for z in (21,34):hall(m,30,z,46,z+10)
    door(m,14,7);door(m,14,19,'south');door(m,18,29,'east');door(m,25,12,'west')
    for z in (26,39):door(m,30,z,'west')
    counter(m,'intake',7,10,11,label='公共登记与健康询问');storage(m,'intake_records',7,17,8,'bookshelf',side=-1)
    dining(m,17,15,4)
    counter(m,'clean',27,10,8,'water_cauldron[level=3]','清洁、物品交接与观察记录');storage(m,'linen',38,13,6,side=-1)
    for n,z in enumerate((21,34),1):
        bed(m,f'patient_{n}',34,z+7);storage(m,f'patient_store_{n}',39,z+2,5)
        counter(m,f'observe_{n}',39,z+7,5,'brewing_stand','观察记录与清洁材料');m.set(32,4,z+2,'water_cauldron[level=3]')
        room(m,f'isolate_{n}',f'独门观察室{n}',31,z+1,45,z+9,'单床个人灯柜、衣物、洗盆和观察台；院侧独立入口')
    kitchen(m,'staff_cook',7,26);dining(m,8,32,5);bed(m,'staff_bed',8,39);storage(m,'staff_books',13,37,3,'bookshelf',side=-1)
    room(m,'intake','公共登记厅',6,8,22,18,'前门登记、候诊餐桌式坐席及档案')
    room(m,'clean','清洁交接长翼',26,8,45,15,'洗涤消毒外形、材料交接与清洁用品存放')
    room(m,'staff','员工生活翼',6,25,17,41,'备餐共餐、睡眠个人柜及阅读用品')
    # Open-faced courtyard barrier expresses the change of zone without an iron lock.
    railing(m,(25,4,19),(25,4,43),'dark_oak','z');m.box((25,4,28),(25,6,30),'air')
    m.point('handover','circulation',(26,4,29),'受限区交接门道，玩法权限另接')
    entry(m,14,3)
    return finish(m,'前部公共登记、侧面员工住宅与三条独立清检/观察翼呈梳齿形；公众无需穿过观察房。')


def power():
    m=base('AA-03','四柱晶核庭 · 魔力配给所',51,47,h=33,site='靠近学城设施的维护街区，基础避开地下水，预留普通装卸路和地下接口')
    hall(m,5,8,17,35);hall(m,33,8,45,25);hall(m,30,30,45,40)
    door(m,17,20,'east');door(m,33,18,'west');door(m,30,35,'west')
    # Four real supports and overhead crossing beams enclose a crystal apparatus.
    for x in (21,29):
        for z in (15,23):
            m.box((x,4,z),(x,15,z),'polished_deepslate');m.set(x,16,z,'amethyst_block')
    for z in (15,23):m.box((21,15,z),(29,15,z),'polished_blackstone_bricks')
    for x in (21,29):m.box((x,15,15),(x,15,23),'polished_blackstone_bricks')
    m.box((23,4,17),(27,5,21),'chiseled_polished_blackstone');m.box((24,6,18),(26,11,20),'amethyst_block');m.set(25,12,19,'amethyst_cluster[facing=up]')
    for x,z in ((25,15),(21,19),(29,19),(25,23)):m.set(x,4,z,'copper_block')
    counter(m,'distribution',7,11,7,'enchanting_table','晶核配额和设施台账');storage(m,'accounts',7,9,7,'bookshelf')
    counter(m,'maintenance',7,24,7,'smithing_table','配给组件维修');storage(m,'parts',7,32,7,side=-1)
    for z in (11,18):storage(m,'crystal_'+str(z),35,z,7,'amethyst_block')
    counter(m,'meter',32,33,9,'comparator[facing=south]','供能接口检查与停机挂牌');storage(m,'tools',32,38,9,side=-1)
    m.point('core','work',(25,8,19),'隔距观察晶核',approach=(25,4,24))
    m.set(43,4,35,'copper_block');m.set(43,5,35,'lightning_rod[facing=up]')
    m.point('interface','work',(43,4,35),'外部供能接口制作预留',approach=(42,4,35))
    room(m,'control','管理与维修长翼',6,9,16,34,'配额管理、账本、维修长台与备件')
    room(m,'stock','晶石分类仓',34,9,44,24,'两排封存晶石架，搬运间距清楚')
    room(m,'interface','配给接口工间',31,31,44,39,'计量外形、检修台、工具与接口预留')
    entry(m,25,3)
    return finish(m,'四柱高晶核架立于开放检修庭，西侧管理长翼、东北晶仓和东南接口工间分开，设备周围可绕行。')


def academy():
    m=base('AA-04','四课庭院 · 魔导学院',59,55,h=34,site='服务多个街区的教育节点，要求可整平的大地块、普通步行出入口与实验物资侧路')
    hall(m,6,8,23,43);hall(m,35,8,52,43);hall(m,24,34,34,48,h=8)
    # Divide long wings into real teaching rooms with their own courtyard doors.
    for x0,x1 in ((7,22),(36,51)):m.box((x0,4,25),(x1,9,25),'calcite')
    for z in (17,30):door(m,23,z,'east');door(m,35,z,'west')
    door(m,29,34)
    counter(m,'lesson',8,11,11,'lectern[facing=south]','术式课堂讲台')
    for z in (17,21):
        for x in (9,15):
            m.box((x,4,z),(x+3,4,z),'dark_oak_slab[type=top]');m.set(x+1,4,z+1,'dark_oak_stairs[facing=north]')
    storage(m,'teaching',8,9,10,'bookshelf')
    for z in (29,36):counter(m,'lab_'+str(z),8,z,9,'brewing_stand','教学实验与操作记录')
    m.set(20,4,29,'water_cauldron[level=3]');storage(m,'samples',8,41,11,side=-1)
    for z in (11,17):storage(m,'reading_'+str(z),37,z,11,'bookshelf')
    dining(m,39,22,7)
    counter(m,'faculty',37,29,10,'lectern[facing=south]','教师备课与学生会面');storage(m,'faculty_files',37,27,11,'bookshelf')
    dining(m,38,37,7);storage(m,'equipment',37,41,11,side=-1)
    counter(m,'seminar',26,38,6,'enchanting_table','高厅示范与学科交流');storage(m,'hall_books',26,46,6,'bookshelf',side=-1)
    for x in (25,33):m.box((x,3,9),(x,3,30),'purple_terracotta')
    for z in (12,26):bench(m,27,4,z,4,'south','dark_oak')
    room(m,'class','理论课堂',7,9,22,24,'讲台、两排双人桌椅和课程书架')
    room(m,'lab','操作实验室',7,26,22,42,'分组操作台、洗盆、样品与器具柜')
    room(m,'reading','课程阅览室',36,9,51,24,'两排书架和完整共读桌椅')
    room(m,'faculty','教师备课与会面',36,26,51,42,'备课资料、工作台及交流餐桌式坐席')
    room(m,'seminar','高示范厅',25,35,33,47,'中央示范台与参考资料，院内独立入口',h=8)
    entry(m,29,3)
    return finish(m,'长双教学翼各分两课室，后接窄高示范厅围成开放课庭；理论、实验、阅览、教师工作四组独立。')


def library():
    m=base('AA-09','十字卷藏馆 · 术式图书馆',55,53,h=35)
    hall(m,18,6,36,45,h=8);hall(m,5,18,17,34);hall(m,37,18,49,34)
    door(m,27,6);m.box((17,4,24),(18,7,27),'air');m.box((36,4,24),(37,7,27),'air')
    counter(m,'borrow',21,11,12,label='借阅登记与术式索引');storage(m,'catalogue',20,8,5,'bookshelf')
    dining(m,23,22,9);dining(m,23,31,9)
    for z in (20,27):storage(m,'west_'+str(z),7,z,8,'bookshelf')
    for z in (20,27):storage(m,'east_'+str(z),39,z,8,'bookshelf')
    m.box((19,4,36),(35,10,36),'calcite');door(m,27,36,'south')
    m.box((27,4,38),(27,9,44),'calcite')
    counter(m,'research_a',20,40,5,label='独立术式对照研究');counter(m,'research_b',29,40,5,label='档案抄录与研究')
    storage(m,'archive_a',20,43,5,'bookshelf',side=-1);storage(m,'archive_b',29,43,5,'bookshelf',side=-1)
    # Low drums on a solid high roof make the reading spine identifiable.
    m.box((23,15,20),(31,16,30),'purple_terracotta');m.box((24,17,21),(30,17,29),'purple_stained_glass')
    m.box((26,18,23),(28,19,27),'polished_blackstone_bricks')
    room(m,'reading','高脊共读厅',19,7,35,35,'借阅、目录和两组完整阅览桌席',h=8)
    room(m,'west','西侧开放书库',6,19,16,33,'双列术式书架，连通主厅')
    room(m,'east','东侧专题书库',38,19,48,33,'双列专题书架，连通主厅')
    room(m,'research','后部双研究间',19,37,35,44,'门内分隔的抄录小间，各有书桌和档案')
    entry(m,27,3)
    return finish(m,'十字平面由高长阅览脊、两侧低书库及后部双研究间组成；屋顶低鼓窗强调阅览核心。')


def ruin():
    m=base('AA-11','断环旧驿 · 失效古代传送站',47,45,h=30,site='旧道路遗址，地面稳定并允许封闭危险区；不作为现役交通节点',role='structure')
    hall(m,5,8,17,30);hall(m,29,23,41,37)
    door(m,17,17,'east');door(m,29,28,'west')
    portal(m,21,17,broken=True)
    m.box((21,4,17),(29,6,17),'cracked_stone_bricks')
    for x,z in ((23,22),(25,24),(28,20)):m.box((x,4,z),(x+1,4,z+1),'cracked_stone_bricks')
    m.box((8,10,20),(15,17,29),'air');m.box((10,10,18),(13,15,21),'air');m.box((12,8,29),(16,10,30),'air')
    railing(m,(6,4,25),(16,4,25),'dark_oak','x')
    for x,z in ((9,27),(12,28),(15,27)):m.box((x,4,z),(x,5,z),'cracked_stone_bricks')
    counter(m,'old_register',7,11,7,label='封存旧目的地登记');storage(m,'old_archive',7,9,6,'bookshelf')
    bench(m,8,4,18,5,'north','dark_oak');storage(m,'recovered',7,23,3,side=-1)
    counter(m,'old_control',31,26,7,'enchanting_table','失效控制台与停用记录');storage(m,'relics',31,34,7,side=-1)
    m.point('sealed','work',(25,5,17),'封闭旧接驳端；断环上部不可通行',approach=(25,4,15))
    room(m,'old_wait','封边旧候行室',6,9,16,24,'旧登记、残存档案、候行座与回收箱，倒塌后半部封边')
    room(m,'control','残存控制间',30,24,40,36,'停用控制台、回收文物与旧维护物')
    entry(m,23,3)
    return finish(m,'断裂传送环被石砌封堵，旧候行翼屋盖局部坍塌且危险段围栏隔开；残存控制间与遗物路线一致。')


def observatory():
    m=base('AA-12','阶台隔距馆 · 异常观测馆',53,53,h=36,site='指定异常边缘的干燥稳定岩台；镜向与安全距离必须依据实际异常设定重新核对',role='structure')
    hall(m,5,8,21,30);hall(m,5,34,23,46)
    m.box((30,0,25),(46,9,46),'stone_bricks');m.box((30,10,25),(46,10,46),'smooth_stone')
    hall(m,32,28,44,44,h=6,f=10);door(m,38,28,f=10)
    # Seven rises arrive at a fully supported landing, with continuous stepped side rails.
    for i in range(7):
        z=16+i;m.box((37,3,z),(39,3+i,z),'stone_bricks')
        for x in range(37,40):m.set(x,4+i,z,'stone_brick_stairs[facing=south]')
        for x in (36,40):
            m.box((x,3,z),(x,4+i,z),'stone_bricks');m.box((x,5+i,z),(x,6+i,z),'dark_oak_fence[north=true,south=true]')
    m.box((36,0,23),(40,10,27),'stone_bricks')
    for x in (30,46):railing(m,(x,11,25),(x,11,46),'dark_oak','z')
    railing(m,(30,11,46),(46,11,46),'dark_oak','x')
    for x0,x1 in ((30,36),(40,46)):railing(m,(x0,11,25),(x1,11,25),'dark_oak','x')
    door(m,13,8);door(m,21,22,'east');door(m,14,34)
    counter(m,'log',7,11,10,label='普通到访与异常记录');storage(m,'records',7,9,10,'bookshelf')
    kitchen(m,'cook',7,15);dining(m,8,20,6);bed(m,'guard',17,28)
    m.box((6,4,24),(20,9,24),'calcite');door(m,13,24,'south')
    storage(m,'guard_locker',7,28,4,side=-1)
    room(m,'sleep','驻守独立寝间',6,25,20,29,'隔门寝间、个人灯柜与衣物储存')
    counter(m,'sample',7,37,10,'brewing_stand','隔离样本记录');storage(m,'sealed_stock',7,44,9,side=-1)
    m.box((18,4,37),(21,7,41),'purple_stained_glass');m.set(19,5,39,'amethyst_block')
    counter(m,'observe',34,31,8,'cartography_table','高台观测与比对',f=10);storage(m,'instruments',34,41,8,'bookshelf',f=10,side=-1)
    m.set(38,12,36,'tinted_glass');m.set(38,11,36,'polished_deepslate');m.set(38,13,36,'end_rod[facing=south]')
    m.point('instrument','work',(38,12,36),'静态观测仪，朝向按异常选址',approach=(38,11,37))
    room(m,'staff','记录与驻守生活',6,9,20,29,'记录、备餐共餐、单床灯柜与用品')
    room(m,'sample','独立样本隔离翼',6,35,22,45,'样本封柜、隔离玻璃龛与台账')
    room(m,'observe','高台观测室',33,29,43,43,'观测仪、工作台与资料柜',f=10)
    m.meta['floors'].append(dict(name='实体高台观测层',y=10,max_y=16))
    entry(m,13,3)
    return finish(m,'低层驻守与独立样本翼、东部七级实心阶台观测室形成不对称高低群；不假设异常种类或防护功能。')


BUILDERS={'AA-01-v01':station,'AA-02-v01':quarantine,'AA-03-v01':power,'AA-04-v01':academy,'AA-09-v01':library,'AA-11-v01':ruin,'AA-12-v01':observatory}
