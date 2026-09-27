"""DS-10: three unequal observation towers connected by usable exterior routes."""
import math
from .model import Model
from .components import shell, window, arch_front, bench
from .samples import railing


def observatory():
    m = Model('DS-10-v01', '连桥三塔星象台', (46, 31, 37), family='DS-10',
              civilization='沙漠', role='key', terrain={
                  '选址': '开阔天空与稳定平地，观测方向避免高山和树冠遮挡',
                  '地形': '完整承重基座，北侧主门接街道；外梯和连桥均需保留',
                  '供给': '值守人员需食物和洁净水补给',
                  '机制': '星仪、刻度和观测装置为原版静态表达'})
    m.meta.update(source='tools/structure_studio/studio/desert_observatory.py:observatory',
        roof_min_y=7, floors=[dict(name='藏书计算与值守',y=1,max_y=6),
        dict(name='连桥与星仪平台',y=12,max_y=19),dict(name='高塔观测台',y=23,max_y=28)],
        preview_context=dict(kind='flat',land_surface_y=1,bed_y=-2,padding=4,surface='sand'),
        design_notes=['参考用户星象台右案：独立三塔、折转外楼梯、架空连桥和低层藏书翼。',
        '主塔分两段登临，所有平台通过三格宽楼梯或连桥抵达；砂岩台墩与柱梁承担桥面。',
        '取消贯穿各栋的统一彩色腰线，赭色局部遮檐与观测刻度用于识别。'],
        differences=['纵向主塔、低星仪塔、东侧测时亭和藏书翼分别由用途决定轮廓。'])
    m.box((2,0,2),(43,1,34),'cut_sandstone')
    m.box((2,2,2),(43,30,34),'air')
    m.box((3,1,3),(42,1,33),'smooth_sandstone')
    # Low precinct wall frames the forecourt without hiding the tower composition.
    for z in (3,33): m.box((3,2,z),(42,3,z),'sandstone')
    for x in (3,42): m.box((x,2,3),(x,3,33),'sandstone')
    arch_front(m,20,2,3,7,7)
    m.box((22,2,2),(24,5,3),'air')
    m.point('front','entrance',(23,2,2),'北侧星图门',facing='north')
    m.meta['connections'].append(dict(kind='pedestrian',pos=[23,1,2],direction='north',clearance=[3,4],note='街面脚底 Y=2'))
    for x in range(22,25): m.set(x,1,1,'sandstone_stairs[facing=south]')

    def tower(x0,z0,x1,z1,h):
        shell(m,(x0,1,z0),(x1,h-1,z1),'smooth_sandstone','cut_sandstone',ceiling='smooth_sandstone')
        for x in (x0,x1):
            for z in (z0,z1): m.box((x,2,z),(x,h,z),'cut_sandstone')
        for z in (z0,z1):
            for x in range(x0+2,x1-1,3): window(m,(x,5,z),(x,7,z),color='gray_stained_glass')

    tower(19,18,28,29,12)
    tower(4,20,12,30,12)
    tower(35,20,41,28,12)
    m.box((22,2,18),(24,5,18),'air')
    m.box((8,2,20),(9,4,20),'air')
    m.box((37,2,20),(39,4,20),'air')
    # Library: long ground-floor wing, recesses and a shaded reading porch.
    shell(m,(30,1,7),(41,6,17),'smooth_sandstone','birch_planks',ceiling='smooth_sandstone')
    m.box((30,2,10),(30,4,13),'air')
    for z in (9,15): window(m,(41,3,z),(41,4,z+1),'z','cyan_stained_glass')
    m.box((27,6,8),(30,6,16),'stripped_acacia_log[axis=z]')
    for z in (8,16): m.box((27,2,z),(27,5,z),'stripped_acacia_log[axis=y]')
    m.box((27,7,8),(30,7,16),'acacia_slab[type=bottom]')
    m.box((33,2,16),(40,4,16),'bookshelf')
    m.box((40,2,8),(40,4,14),'bookshelf')
    m.box((34,2,10),(37,2,10),'birch_slab[type=top]')
    m.set(36,3,10,'light_weighted_pressure_plate[power=0]')
    m.set(32,2,14,'lectern[facing=west]')
    bench(m,34,2,12,4,'north','birch')
    m.point('survey','work',(36,3,10),'星图计算台',approach=(36,2,9))
    m.point('records','work',(32,2,14),'历法与观测记录',approach=(31,2,14))
    m.set(39,2,8,'lantern')
    # Ground-level instruments room and keeper dwelling, both genuinely accessible.
    m.box((21,2,27),(26,3,27),'bookshelf')
    m.set(26,2,21,'cartography_table')
    m.point('calibration','work',(26,2,21),'仪器校准台',approach=(25,2,21))
    m.set(20,3,24,'wall_torch[facing=east]')
    m.bed(6,2,26,color='cyan',facing='south')
    m.point('keeper_bed','bed',(6,2,26),'观测者床位',approach=(7,2,26))
    for p,b in [((10,2,28),'barrel'),((10,2,23),'water_cauldron[level=3]'),
                ((5,2,22),'smoker[facing=east]'),((6,2,22),'crafting_table'),((10,3,28),'lantern')]: m.set(*p,b)
    m.set(39,2,26,'lectern[facing=north]')
    m.point('clock_records','work',(39,2,26),'测时记录室',approach=(39,2,25))
    m.set(36,3,24,'wall_torch[facing=east]')
    # First external flight: 11 full stair rises, masonry underneath and side rails.
    for i in range(11):
        z=7+i; y=2+i
        m.box((15,1,z),(17,y-1,z),'sandstone')
        for x in (15,16,17): m.set(x,y,z,'sandstone_stairs[facing=south]')
        for x in (14,18):
            m.box((x,1,z),(x,y,z),'cut_sandstone')
            m.set(x,y+1,z,'acacia_fence[north=true,south=true]')
    # Tower's western terrace carries the switchback and the two bridges.
    m.box((14,12,18),(18,12,32),'smooth_sandstone')
    for x,z in ((15,18),(15,24),(15,31),(18,31)): m.box((x,2,z),(x,11,z),'cut_sandstone')
    railing(m,(14,13,18),(14,13,21),wood='acacia',axis='z')
    railing(m,(14,13,25),(14,13,31),wood='acacia',axis='z')
    railing(m,(15,13,32),(18,13,32),wood='acacia',axis='x')
    # Upper room: a narrow pierced tower, with its accessible door on the west.
    shell(m,(20,12,20),(27,22,27),'smooth_sandstone','cut_sandstone',ceiling='smooth_sandstone')
    m.box((20,13,23),(20,16,24),'air')
    for z in (20,27):
        for x in (22,25): window(m,(x,17,z),(x,19,z),color='gray_stained_glass')
    m.set(24,13,25,'lectern[facing=north]')
    m.point('upper_records','work',(24,13,25),'高塔观测日志',approach=(24,13,24))
    m.set(26,14,23,'wall_torch[facing=west]')
    # Second flight rises across the rear; clearance remains open above every tread.
    for i in range(11):
        x=18+i; y=13+i
        m.box((x,2,29),(x,y-1,31),'sandstone')
        for z in (29,30,31): m.set(x,y,z,'sandstone_stairs[facing=east]')
        for z in (28,32):
            m.box((x,2,z),(x,y,z),'cut_sandstone')
            m.set(x,y+1,z,'acacia_fence[east=true,west=true]')
    m.box((29,23,18),(31,23,31),'smooth_sandstone')
    m.box((32,23,18),(32,23,31),'smooth_sandstone')
    m.box((29,23,32),(31,23,32),'smooth_sandstone')
    for z in (18,25,31): m.box((31,2,z),(31,22,z),'cut_sandstone')
    m.box((19,23,18),(28,23,28),'smooth_sandstone')
    railing(m,(19,24,18),(31,24,18),wood='acacia',axis='x')
    railing(m,(19,24,19),(19,24,27),wood='acacia',axis='z')
    railing(m,(32,24,18),(32,24,31),wood='acacia',axis='z')
    railing(m,(29,24,32),(31,24,32),wood='acacia',axis='x')
    # Upper parapet is interrupted only where the ascent meets it.
    railing(m,(20,24,28),(27,24,28),wood='acacia',axis='x')
    m.box((23,24,22),(25,24,24),'terracotta')
    m.set(24,25,23,'lightning_rod[facing=up]')
    m.set(26,24,22,'lectern[facing=east]')
    m.point('summit','work',(26,24,22),'高塔子午观测台',approach=(27,24,22))
    # Supported bridges and their deck rails; ends open onto the tower terraces.
    for xa,xb in ((12,19),(28,35)):
        m.box((xa,12,22),(xb,12,24),'acacia_planks')
        for z in (21,25):
            m.box((xa,12,z),(xb,12,z),'stripped_acacia_log[axis=x]')
            railing(m,(xa,13,z),(xb,13,z),wood='acacia',axis='x')
        for x in (xa+1,xb-1):
            for z in (21,25): m.box((x,2,z),(x,11,z),'cut_sandstone')
    # Bridge parapets stop at the perpendicular terrace junction.
    for z in (21,25): m.box((15,13,z),(19,15,z),'air')
    m.box((28,13,21),(28,15,21),'air')
    # Guard the north shoulder used to reach the eastern bridge.
    m.box((19,12,17),(29,12,17),'smooth_sandstone')
    railing(m,(19,13,17),(29,13,17),wood='acacia',axis='x')
    m.box((29,12,18),(29,12,20),'smooth_sandstone')
    railing(m,(29,13,18),(29,13,20),wood='acacia',axis='z')
    for xa,xb,za,zb in ((4,12,20,30),(35,41,20,28)):
        for z in (za,zb): railing(m,(xa,13,z),(xb,13,z),wood='acacia',axis='x')
        for x in (xa,xb): railing(m,(x,13,za),(x,13,zb),wood='acacia',axis='z')
    m.box((12,13,22),(12,15,24),'air')
    m.box((35,13,22),(35,15,24),'air')
    # A compact armillary on the west platform leaves a full perimeter walkway.
    m.box((7,13,24),(9,14,26),'waxed_cut_copper')
    for step in range(48):
        a=step*math.tau/48; d=round(3*math.cos(a)); h=17+round(3*math.sin(a))
        m.set(8+d,h,25,'waxed_cut_copper')
        m.set(8,h,25+d,'waxed_cut_copper')
    m.set(8,17,25,'gold_block')
    m.set(10,13,22,'lectern[facing=east]')
    m.point('instrument','work',(10,13,22),'星仪观测与记录',approach=(11,13,22))
    # East tower carries a light, column-supported shade pavilion and sundial.
    for x in (37,40):
        for z in (21,27): m.box((x,13,z),(x,16,z),'cut_sandstone')
    m.box((36,17,20),(41,17,28),'orange_terracotta')
    m.box((37,18,21),(40,18,27),'smooth_sandstone_slab[type=bottom]')
    m.box((38,17,23),(40,18,25),'air')  # Light well exposes the dial to the sky.
    m.set(39,13,24,'chiseled_sandstone');m.set(39,14,24,'lightning_rod[facing=up]')
    m.point('sundial','work',(39,14,24),'日影校时台',approach=(38,13,24))
    m.point('first_landing','circulation',(16,13,19),'外梯第一平台',look_at=[8,17,25])
    m.point('deck_landing','circulation',(30,24,30),'高塔楼梯出口',look_at=[24,25,23])
    m.room('library','藏书与星图计算翼',(31,2,8),(40,6,16),'书架、星图台、读写与遮阴门廊')
    m.room('keeper','低塔值守居室',(5,2,21),(11,7,29),'床位、饮水、炊具与储物')
    m.room('calibration','主塔仪器校准室',(20,2,19),(27,8,27),'校准、仪器维护与历法藏书')
    m.room('upper','主塔日志室',(21,13,21),(26,20,26),'桥层读写与观测日志')
    m.room('instrument','低塔星仪平台',(5,13,21),(11,21,29),'小型星仪和环绕操作空间')
    m.room('sundial','东塔测时亭',(36,13,21),(40,17,27),'带遮檐的校时观测平台')
    m.room('summit','主塔高空观测台',(20,24,19),(30,28,27),'子午刻度与开阔观测')
    m.meta["ground_plane"] = {'y': 1, 'note': '自然街面上边界 Y=1；北侧 Y=1 南向楼梯从半格踏面升至 Y=2 门前平台，入口高于外部地面一格。'}
    return m
