"""Salvage settlements: load-bearing machine remains and visibly newer infill."""
from .model import Model
from .components import bench, shelf, crate_stack, pendant


def base(key,name,size,condition,role='key'):
    m=Model(key,name,size,civilization='废土拾荒',role=role,terrain={
        '选址':condition,'接地':'完整旧机座埋入稳固碎石台地，地坪Y=2、入口脚底Y=3；不将薄壳残板当作新房基础。',
        '外接':'南侧接既有搬运路；模板外须保留回转、排水与坠落隔离带。避开活动滑坡、洪道及悬空残骸。',
        '供给':'生活水依外部运水，燃料和食物由聚落补给；不假设旧机械仍能工作。',
        '边界':'独立承载静态原版结构；残骸稳定、探险门禁与设备功能未接入运行时。'})
    m.box((1,0,1),(size[0]-2,2,size[2]-2),'gravel')
    m.box((1,3,1),(size[0]-2,size[1]-1,size[2]-2),'air')
    m.meta.update(source='tools/structure_studio/studio/relic_salvagers.py:'+key,roof_min_y=8,
        floors=[dict(name='修补地坪',y=2,max_y=7)],preview_context=dict(kind='flat',land_surface_y=3,padding=5,surface='grass'))
    m.meta['design_notes']=['深色旧机梁、齿轴与氧化铜壳为旧骨架；浅木、橙色板补丁与布棚为后加住用空间。']
    return m


def entry(m,x,z):
    m.point('entry','entrance',(x,3,z),'搬运路入口',facing='south')
    m.meta['connections'].append(dict(kind='pedestrian',pos=[x,2,z],direction='south',clearance=[3,3],note='接稳固旧场坪，货运另保留转向区'))


def work(m,key,x,z,block,label,y=3,approach=None):
    m.set(x,y,z,block);m.point(key,'work',(x,y,z),label,approach=approach or (x,y,z+1))


def store(m,key,x,z,w,label,y=3):
    shelf(m,x,y,z,w,'spruce');m.point(key,'storage',(x+1,y+1,z),label,approach=(x+1,y,z+1))


def patchroom(m,key,x,z,w,d,label):
    # Separate footings, salvaged upright frame and heterogeneous replacement panels.
    m.box((x,2,z),(x+w-1,2,z+d-1),'spruce_planks')
    for xx in range(x,x+w):
        for zz in (z,z+d-1):
            m.box((xx,3,zz),(xx,7,zz),'stripped_spruce_wood' if (xx-x)%4 else 'orange_terracotta')
    for zz in range(z,z+d):
        for xx in (x,x+w-1):m.box((xx,3,zz),(xx,7,zz),'spruce_planks' if (zz-z)%4 else 'waxed_exposed_cut_copper')
    for xx in (x,x+w-1):
        for zz in (z,z+d-1):m.box((xx,2,zz),(xx,8,zz),'polished_deepslate')
    m.box((x-1,8,z-1),(x+w,8,z+d),'spruce_slab[type=top]')
    m.box((x+2,8,z),(x+4,8,z+d-1),'waxed_weathered_cut_copper')
    m.box((x+w//2,3,z+d-1),(x+w//2,4,z+d-1),'air');m.door(x+w//2,3,z+d-1,'spruce')
    m.box((x+2,5,z),(x+4,6,z),'glass')
    pendant(m,x+w//2,6,z+d//2,8)
    m.room(key,label,(x+1,3,z+1),(x+w-2,7,z+d-2),'有独立旧场坪支承的后加板房，门窗和完整使用通路')


def living(m,key,x,z):
    patchroom(m,key,x,z,13,16,'修补值守住屋')
    m.box((x+5,3,z+1),(x+5,5,z+7),'white_wool')
    m.bed(x+2,3,z+4,'orange','north');m.point(key+'bed','sleep',(x+2,3,z+4),'屏后床位',approach=(x+3,3,z+4))
    store(m,key+'linen',x+1,z+7,3,'衣物与床品')
    store(m,key+'food',x+7,z+2,4,'干粮与器具')
    work(m,key+'cook',x+8,z+6,'smoker[facing=south]','备餐火灶')
    m.box((x+9,3,z+6),(x+10,3,z+6),'spruce_planks')
    work(m,key+'water',x+10,z+7,'water_cauldron[level=3]','运水和清洗')
    m.box((x+6,3,z+10),(x+8,3,z+11),'spruce_slab[type=top]');bench(m,x+6,3,z+13,3,'north')
    m.point(key+'dine','work',(x+7,3,z+10),'用餐桌席',approach=(x+5,3,z+10))
    work(m,key+'read',x+2,z+11,'lectern[facing=south]','起居记事与交接')


def rib(m,x,z,w=19,h=12):
    # Truncated octagonal machine ring, visibly hollow and open at ground level.
    for dx in range(w):
        edge=min(dx,w-1-dx);y=3+min(h,5+edge)
        m.set(x+dx,y,z,'polished_deepslate');m.set(x+dx,y,z+1,'waxed_oxidized_cut_copper')
    for xx in (x,x+w-1):m.box((xx,2,z),(xx,8,z+1),'polished_deepslate')
    for xx in (x+1,x+w-2):m.box((xx,7,z),(xx,10,z),'iron_block')


def machine(m,x,z,length=10):
    m.box((x,2,z),(x+8,3,z+length-1),'polished_blackstone_bricks')
    m.box((x+3,4,z),(x+5,5,z+length-1),'iron_block')
    for zz in (z+1,z+length-2):
        m.box((x+1,4,zz),(x+7,7,zz),'waxed_weathered_cut_copper')
        m.box((x+2,5,zz),(x+6,6,zz),'polished_deepslate')
        m.box((x+3,4,zz),(x+5,7,zz),'iron_block')
    m.box((x+4,6,z),(x+4,6,z+length-1),'lightning_rod[facing=north]')


def hall():
    m=base('RS-02','巨械肋架拆解大厅',(49,23,45),'废弃巨械腹腔旧机座上开展拆解；侧边新值守屋独立落地，前场通重件。')
    for z in (5,13,21,29):rib(m,5,z,23,13)
    for x in (5,27):m.box((x,9,5),(x,9,30),'polished_deepslate')
    # Broken top shell covers only rear two bays, leaving extraction opening above rotor.
    m.box((8,16,14),(24,16,29),'waxed_oxidized_cut_copper');m.box((11,17,21),(21,17,28),'waxed_weathered_cut_copper')
    machine(m,12,10,13)
    m.box((7,17,7),(27,17,7),'polished_deepslate');m.box((18,10,7),(18,16,7),'chain[axis=y]');m.set(18,9,7,'anvil')
    living(m,'keeper',33,6)
    store(m,'raw',5,33,5,'拆解待验零件');store(m,'parts',20,33,6,'分类合格部件')
    for key,x,z,b,label in [('cut',8,24,'stonecutter[facing=south]','初拆切整台'),('repair',23,24,'anvil','修复校整台'),('record',33,30,'lectern[facing=south]','作业工单与检验'),('wash',37,30,'water_cauldron[level=3]','零件清洗')]:work(m,key,x,z,b,label)
    m.box((6,3,26),(10,3,28),'spruce_planks');m.box((21,3,26),(26,3,28),'spruce_planks')
    m.room('salvage','巨械残轴拆解区',(6,3,5),(26,15,30),'双侧工位围绕真实旧轴和机座，中央吊运天窗及前场搬运通路')
    m.point('rotor','work',(16,5,10),'旧巨械传动轴勘察',approach=(16,3,8));entry(m,17,42)
    return m


def control():
    m=base('RS-04','断裂控制舱与侧接档案间',(43,23,39),'旧控制舱嵌在稳定半截机壳内；残轴尾段保持封边，新增档案间由外侧地基承重。','structure')
    for z in (5,13,21):rib(m,4,z,23,12)
    m.box((6,2,5),(24,2,23),'polished_andesite');m.box((6,3,5),(24,7,5),'deepslate_tiles')
    m.box((6,14,6),(24,14,21),'waxed_weathered_cut_copper')
    for x in (7,11,15,19):
        m.box((x,3,8),(x+2,3,8),'polished_deepslate');m.set(x+1,4,8,'redstone_lamp');m.set(x+1,4,9,'lever[face=floor,facing=north,powered=false]')
    m.point('console','work',(12,4,8),'断电主控制台',approach=(12,3,10))
    for x in (8,16):bench(m,x,3,13,3,'north')
    store(m,'archive',7,19,6,'旧纸本运行档案');work(m,'map',20,18,'cartography_table','旧线路图校对')
    m.bed(22,3,22,'gray','north');m.set(20,3,22,'barrel[facing=up]')
    m.point('oldbunk','sleep',(22,3,22),'旧值班铺与遗留私物',approach=(23,3,22))
    patchroom(m,'repair',29,8,10,16,'后加档案修复间')
    store(m,'newfiles',30,10,5,'整理后档案柜');work(m,'desk',32,15,'lectern[facing=south]','复抄工作台');work(m,'restore',35,18,'crafting_table','纸本装帧修补')
    m.box((31,3,13),(34,3,13),'spruce_slab[type=top]');bench(m,31,3,20,3,'north')
    machine(m,7,27,8);m.room('controls','原控制舱',(6,3,6),(24,13,23),'成排停用控制台、操作席与旧档案，保持历史用途证据')
    m.point('shell','work',(9,5,27),'控制舱外断轴遗存',approach=(9,3,25));entry(m,27,36)
    return m


def canopy(m,x,z,w,d):
    for xx in (x,x+w-1):
        for zz in (z,z+d-1):m.box((xx,3,zz),(xx,8,zz),'stripped_spruce_log[axis=y]')
    m.box((x,8,z),(x+w-1,8,z+d-1),'orange_wool')
    for zz in range(z,z+d,4):m.box((x,8,zz),(x+w-1,8,zz),'white_wool')


def market():
    m=base('RS-03','断轮广场零件市场',(47,24,43),'旧巨械车轮骨架包围交易前庭，商棚与后仓使用独立落地支架，南侧接商路。')
    # Upright broken wheel: stepped annulus at the rear, heavy hubs remain above stalls.
    for x in range(7,34):
        for y in range(3,23):
            d=(x-20)**2+(y-10)**2
            if 100<=d<=144 and not (x>27 and y>16):m.box((x,y,5),(x,y,7),'polished_deepslate')
    m.box((18,3,5),(22,13,7),'iron_block');m.box((8,9,5),(31,11,7),'waxed_oxidized_cut_copper')
    patchroom(m,'warehouse',30,11,12,15,'市场分类后仓')
    store(m,'stock',31,13,7,'登记零件后仓');store(m,'fragile',31,18,5,'精细仪表盒');work(m,'appraise',38,21,'cartography_table','鉴定与批次登记')
    for key,x,z,b,label in [('fasteners',5,12,'anvil','紧固件与金工'),('instruments',18,12,'comparator[facing=north]','旧仪表鉴别'),('cloth',5,26,'loom[facing=south]','回收帆布缝补'),('tools',20,27,'grindstone[face=floor,facing=north]','修复工具试用')]:
        canopy(m,x,z,10,8);m.box((x+2,3,z+4),(x+7,3,z+4),'spruce_planks');work(m,key,x+4,z+4,b,label);store(m,key+'stock',x+2,z+1,4,label+'储物')
    work(m,'record',36,31,'lectern[facing=south]','摊位管理与账目');crate_stack(m,36,3,35,4,3,2)
    m.room('sales','旧断轮下新摊街',(4,3,10),(29,7,35),'四类有后勤柜的行业摊位围绕可通行庭院');entry(m,18,40)
    return m


def school():
    m=base('RS-10','旧锅炉半壳学堂',(43,24,41),'废旧锅炉底座上保留巨大弧壳与停用管道，教学区和修复侧屋分别有独立地坪支撑。')
    for z in (6,12,18,24):rib(m,4,z,25,14)
    for x in range(5,28):
        yy=8+min(x-4,28-x,9)
        m.box((x,yy,6),(x,yy,24),'waxed_weathered_cut_copper')
    m.box((6,3,6),(26,6,6),'deepslate_tiles');m.box((9,4,7),(20,6,7),'black_concrete')
    work(m,'teach',12,10,'lectern[facing=south]','旧世技艺讲台')
    for z in (14,19,24):
        for x in (8,19):
            m.box((x,3,z),(x+4,3,z),'spruce_slab[type=top]');bench(m,x,3,z+2,4,'north')
    m.point('learn','work',(10,3,14),'抄录与课堂桌',approach=(10,3,13))
    patchroom(m,'restore',31,8,9,19,'藏品修复侧屋');store(m,'books',32,10,5,'旧世讲义与资料')
    m.box((32,3,17),(36,3,18),'spruce_slab[type=top]');store(m,'supplies',32,24,4,'修复耗材与待修藏品')
    m.box((2,5,8),(2,5,24),'waxed_exposed_cut_copper');m.box((2,5,8),(2,12,8),'waxed_exposed_cut_copper')
    work(m,'repair',34,15,'crafting_table','藏品修复操作');work(m,'catalog',35,21,'lectern[facing=south]','藏品编目与借阅')
    m.box((5,3,32),(24,3,34),'polished_andesite')
    for x,b in [(7,'piston[facing=up]'),(13,'daylight_detector'),(20,'anvil')]:m.set(x,4,33,b)
    m.point('exhibit','work',(13,4,33),'拆解旧机件教学展台',approach=(13,3,35))
    m.room('class','旧锅炉壳内课堂',(6,3,8),(26,15,28),'六组抄录桌椅、讲台与黑板，不将残骸仅作外部摆设');entry(m,28,38)
    return m


def relay():
    m=base('RS-11','断继电塔下旅途营地',(43,27,41),'城外旧继电站完整基座与断塔仍在，生活板房避开塔臂坠落区，物资由巡路者补给。','structure')
    m.box((5,2,5),(17,3,17),'polished_blackstone_bricks')
    for x,z in [(6,6),(16,6),(6,16),(16,16)]:m.box((x,4,z),(x,22,z),'polished_deepslate')
    for y in (8,14,20):
        m.box((6,y,6),(16,y,6),'iron_block');m.box((6,y,16),(16,y,16),'iron_block')
        m.box((6,y,6),(6,y,16),'iron_block');m.box((16,y,6),(16,y,16),'iron_block')
    m.box((7,21,9),(24,21,11),'waxed_oxidized_cut_copper');m.box((19,18,9),(24,18,11),'waxed_exposed_cut_copper')
    # Exposed cable droops down a snapped arm, not an inhabited upper floor.
    m.box((24,15,10),(24,20,10),'chain[axis=y]')
    living(m,'shelter',25,7);canopy(m,5,24,15,10)
    store(m,'parts',6,25,6,'继电零件与巡路维修');work(m,'repair',10,29,'smithing_table','线路修复工作台');work(m,'water',17,29,'water_cauldron[level=3]','应急运水')
    m.box((7,4,8),(9,7,12),'waxed_weathered_cut_copper');m.box((12,4,8),(14,6,12),'polished_deepslate')
    m.point('relay','work',(8,5,12),'停用继电机柜',approach=(8,3,19));m.room('tower','断塔机座',(5,3,5),(17,23,17),'上部禁止居住的旧设施，地面勘察与独立新营地分开');entry(m,22,38)
    return m


def depot():
    m=base('RS-12','封存动力舱设备库',(47,25,45),'完整旧动力舱基础上的设备封存遗址，单侧维修走廊开放；封存区保留可观察边界。','structure')
    for z in (5,12,19,26,33):rib(m,5,z,27,15)
    m.box((7,18,5),(29,18,33),'waxed_oxidized_cut_copper')
    m.box((7,3,5),(29,10,5),'deepslate_tiles')
    machine(m,9,10,17);machine(m,21,11,14)
    # Transparent sealed barrier leaves a full-width public service aisle to its south.
    m.box((8,3,30),(29,7,30),'glass');m.box((8,8,30),(29,8,30),'polished_deepslate')
    m.point('sealed','work',(17,5,30),'玻璃封存界面与设备观察',approach=(17,3,32))
    patchroom(m,'service',35,8,9,20,'设备维护备品侧间');store(m,'spares',36,10,5,'密封备件与工具');work(m,'bench',38,17,'smithing_table','维护装配台');work(m,'ledger',38,23,'lectern[facing=south]','封存台账')
    store(m,'kits',6,37,7,'开放维护通道耗材');work(m,'inspect',26,37,'cartography_table','设备布局核验')
    m.room('sealedbay','封存双动力机舱',(8,3,8),(29,17,28),'两组真实机座、轮轴与管体；封存区只从开放走廊观察，不伪造内部可达点')
    m.room('aisle','开放维护走廊',(6,3,32),(31,7,40),'检查窗、耗材及记录点保持连通');entry(m,20,42)
    return m


BUILDERS={'RS-02':hall,'RS-03':market,'RS-04':control,'RS-10':school,'RS-11':relay,'RS-12':depot}
