"""Reference-led oasis clinic: connected low wings and shaded healing gardens."""
from .model import Model
from .components import shell, bench, pendant


def clinic():
    m = Model('DS-09-v01', '棕影错院医馆', (43, 16, 40), family='DS-09', civilization='沙漠', role='key', terrain={
        '选址': '安静可达的居民或旅人服务地块，可靠洁净用水与药材补给',
        '地形': '平缓干燥地基；北入口脚底 Y=2，诊室与休养床位同层无台阶',
        '配套': '诊疗、药房、候诊、休养及值守生活；不附加治疗机制'})
    m.meta.update(source='tools/structure_studio/studio/desert_clinic.py:clinic', roof_min_y=7,
        floors=[dict(name='候诊、诊疗与静养', y=1, max_y=6)],
        design_notes=['参考用户 Gemini 左案：错落诊疗翼、局部土色高厅、木遮阴廊和小尺度花园。',
                      '低矮相连建筑翼与两处高厅形成非对称轮廓，无整圈堡垒墙、无连续彩带。'],
        differences=['北侧开放门庭通向树荫候诊，东西诊室各自开门；后侧静养远离入口。'],
        preview_context=dict(kind='flat', land_surface_y=1, bed_y=-2, padding=4, surface='sand'))
    m.box((2,0,2),(40,1,37),'sandstone')
    m.box((2,2,2),(40,14,37),'air')
    m.box((3,1,3),(39,1,36),'smooth_sandstone')
    # One low west wing, a rear convalescent wing, and a staggered east wing.
    def wing(key,name,a,b,roof,wall='smooth_sandstone'):
        x0,z0=a;x1,z1=b
        shell(m,(x0,1,z0),(x1,roof-1,z1),wall,'smooth_sandstone',ceiling='smooth_sandstone')
        m.box((x0,roof+1,z0),(x1,roof+1,z0),'smooth_sandstone_slab[type=bottom]')
        m.box((x0,roof+1,z1),(x1,roof+1,z1),'smooth_sandstone_slab[type=bottom]')
        for x in (x0,x1):m.box((x,roof+1,z0),(x,roof+1,z1),'smooth_sandstone_slab[type=bottom]')
        m.room(key,name,(x0+1,2,z0+1),(x1-1,roof-1,z1-1),name)
        pendant(m,(x0+x1)//2,roof-1,(z0+z1)//2,roof)
    wing('duty','值守起居与小厨房',(5,4),(14,11),9,'terracotta')
    wing('treatment','西侧诊疗室',(5,11),(14,24),7)
    wing('pharmacy','药房与药材分拣',(4,25),(15,35),10,'terracotta')
    wing('recovery','安静休养长翼',(15,27),(36,35),7)
    wing('consult','东侧问诊室',(30,10),(38,23),8)
    # Openings remain separate from shaded circulation, no room is a through-route.
    for x,z,facing in ((14,7,'east'),(14,17,'east'),(15,26,'east'),(21,27,'north'),(32,27,'north'),(30,16,'west')):
        m.box((x,2,z),(x,4,z),'air');m.door(x,2,z,wood='acacia',facing=facing)
    # Recovery rooms are genuinely separated, each with its own garden-side door.
    m.box((26,2,28),(26,6,34),'smooth_sandstone')
    for x,z,axis in ((5,15,'x'),(5,21,'x'),(4,29,'x'),(38,14,'x'),(38,20,'x')):
        m.box((x,3,z),(x,4,z+1),'acacia_fence')
    for x in (18,23,29,34):m.box((x,3,35),(x+1,4,35),'acacia_fence')
    # Timber pergolas meet the walls, rather than floating standalone roofs.
    def shade(x0,z0,x1,z1,y=6):
        for x,z in ((x0,z0),(x1,z0),(x0,z1),(x1,z1)):
            m.box((x,2,z),(x,y,z),'stripped_dark_oak_log[axis=y]')
        for z in (z0,z1):m.box((x0,y,z),(x1,y,z),'stripped_dark_oak_log[axis=x]')
        for x in range(x0,x1+1,2):m.box((x,y+1,z0),(x,y+1,z1),'dark_oak_slab[type=bottom]')
        for x in (x0,x1):m.box((x,y,z0),(x,y,z1),'stripped_dark_oak_log[axis=z]')
    shade(15,11,18,25);shade(27,11,29,25);shade(18,24,29,26)
    # A low front veranda breaks the long east elevation and shelters a garden seat.
    shade(30,8,38,9,y=5)
    bench(m,33,2,8,3,'north','acacia')
    for x in (32,35):m.box((x,3,10),(x+1,4,10),'acacia_fence')
    for x in (8,11):m.box((x,4,4),(x,5,4),'acacia_fence')
    # The high pharmacy hall gets clerestory slots above the lower connecting wing.
    for x in (7,11):m.box((x,7,25),(x,8,25),'acacia_fence')
    for z in (28,32):m.box((4,7,z),(4,8,z),'acacia_fence')
    # Welcoming low garden edge and entrance arch, no defensive enclosing wall.
    for x0,x1 in ((4,17),(25,38)):m.box((x0,2,3),(x1,2,3),'smooth_sandstone_slab[type=bottom]')
    for x in (18,24):m.box((x,2,4),(x,6,4),'cut_sandstone')
    m.box((18,7,4),(24,7,4),'smooth_sandstone');m.box((19,6,4),(19,6,4),'sandstone_stairs[facing=east,half=top]')
    m.set(23,6,4,'sandstone_stairs[facing=west,half=top]')
    m.point('front','entrance',(21,2,3),'北侧候诊入口',facing='north')
    m.meta['connections'].append(dict(kind='pedestrian',pos=[21,2,2],direction='north',clearance=[5,4],note='同层入口接步行街面'))
    # Garden beds use real soil; a small sealed basin is supplied externally.
    for x0,z0,x1,z1 in ((19,12,24,14),(19,20,24,22),(32,4,37,6)):
        m.box((x0,1,z0),(x1,1,z1),'dirt')
        for x in range(x0,x1+1,2):
            for z in range(z0,z1+1,2):m.set(x,2,z,'flowering_azalea')
    for x,z in ((18,9),(28,8),(39,29)):
        m.set(x,1,z,'dirt');m.box((x,2,z),(x,7,z),'jungle_log[axis=y]')
        m.box((x-2,7,z),(x+2,7,z),'jungle_leaves[persistent=true]')
        m.box((x,7,z-2),(x,7,z+2),'jungle_leaves[persistent=true]')
        m.box((x-1,8,z-1),(x+1,8,z+1),'jungle_leaves[persistent=true]')
    m.box((20,1,16),(24,1,18),'cut_sandstone')
    m.box((20,2,16),(24,2,18),'smooth_sandstone_slab[type=bottom]')
    m.box((21,1,17),(23,1,17),'water[level=0]');m.box((21,2,17),(23,2,17),'air')
    bench(m,20,2,10,4,'south','acacia');bench(m,20,2,24,4,'north','acacia')
    m.room('waiting','树荫候诊与药草小院',(16,2,8),(29,6,26),'候诊、遮阴步行、庭院休养')
    m.point('waiting','work',(21,2,10),'树荫候诊座椅',approach=(21,2,11))
    # Duty quarters have an actual bed, pantry, stove, washing and a dining table.
    m.bed(7,2,8,color='orange',facing='north');m.point('duty_bed','bed',(7,2,8),'值守床',approach=(8,2,8))
    for x,block in ((7,'barrel[facing=south]'),(9,'smoker[facing=south]'),(11,'water_cauldron[level=3]')):m.set(x,2,5,block)
    m.set(11,2,9,'acacia_slab[type=top]');m.set(12,2,9,'acacia_stairs[facing=west]')
    m.point('kitchen','work',(9,2,5),'值守备餐',approach=(9,2,6))
    # Treatment and consultation each have an examination bed, records and washbasin.
    for key,x,z in (('treatment',8,21),('consult',35,20)):
        m.bed(x,2,z,color='white',facing='north');m.point(key+'_bed','bed',(x,2,z),'诊疗床',approach=(x+1,2,z))
    m.box((6,2,12),(9,3,12),'bookshelf');m.set(11,2,13,'lectern[facing=south]');m.set(6,2,17,'water_cauldron[level=3]')
    m.point('records','work',(11,2,13),'诊疗记录',approach=(11,2,14))
    m.box((32,2,11),(36,3,11),'bookshelf');m.set(36,2,14,'water_cauldron[level=3]');m.set(32,2,19,'lectern[facing=east]')
    m.point('consult_desk','work',(32,2,19),'问诊案桌',approach=(33,2,19))
    # Pharmacy: shelves, compounding bench and washing, with a clear central aisle.
    for z in (27,30,33):
        m.set(5,2,z,'barrel[facing=east]');m.set(5,3,z,'barrel[facing=east]')
    m.box((8,2,34),(13,2,34),'acacia_planks');m.set(9,3,34,'brewing_stand');m.set(12,3,34,'flower_pot')
    m.set(13,2,26,'water_cauldron[level=3]')
    m.point('compound','work',(9,3,34),'药材配制',approach=(9,2,33))
    m.point('medicine','storage',(5,2,30),'药材储存',approach=(6,2,30))
    for key,x,z in (('recovery_a',18,33),('recovery_b',23,33),('recovery_c',29,33),('recovery_d',34,33)):
        m.bed(x,2,z,color='light_blue',facing='north');m.point(key,'bed',(x,2,z),'静养床位',approach=(x+1,2,z))
    for x in (16,27):m.set(x,2,29,'water_cauldron[level=3]');m.set(x,2,31,'barrel[facing=east]')
    m.meta["ground_plane"] = {'y': 2, 'note': '北侧为同层入口，无外阶；门外场坪支撑方块 Y=1，外部步行街面上边界 Y=2。旧 preview_context 的 Y=1 未表示正确同层接驳。'}
    return m
