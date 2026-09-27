"""Ceremonial stairhead over a two-basin subterranean column hall."""
from .model import Model


def cistern():
    m = Model('DS-03-v01', '阶庭地下蓄水厅', (43, 30, 40), family='DS-03',
              civilization='沙漠', role='key', terrain={
                  '选址': '稳定岩土内的储水节点，需要可靠引水、防渗与地下开挖条件。',
                  '埋置': '地表脚底 Y=15；基底 Y=0，干侧检修脚底 Y=3。',
                  '水': '两座封闭蓄水池；补水、排水、容量机制未接入。'})
    m.meta.update(source='tools/structure_studio/studio/desert_cistern.py:cistern',
        roof_min_y=13, floors=[dict(name='地下柱厅与双池', y=2, max_y=12),
                              dict(name='地上阶顶入口亭', y=14, max_y=28)],
        design_notes=['参考用户左案：阶顶入口亭、露天下降仪式长阶与地下柱厅。',
                      '厚覆土顶板下的双池、连续干侧环道；楼梯和柱梁均落地支撑。'],
        differences=['以地下柱厅与下沉长阶组织空间，不复用住宅方盒。'],
        preview_context=dict(kind='flat', land_surface_y=15, bed_y=0, padding=4, surface='sand'))
    # Explicit excavation, sealed stone foundation and thick sandstone perimeter.
    m.box((2,0,2),(40,14,37),'sandstone')
    m.box((3,0,3),(39,2,36),'stone_bricks')
    m.box((4,3,4),(38,12,35),'air')
    m.box((3,14,3),(39,14,36),'smooth_sandstone')
    # Separate pools below the dry walkway floor.
    for x0,x1 in ((7,15),(27,35)):
        m.box((x0,1,17),(x1,2,31),'water[level=0]')
        for x in (x0-1,x1+1):
            m.box((x,3,16),(x,3,32),'smooth_sandstone_slab[type=bottom]')
        for z in (16,32):
            m.box((x0,3,z),(x1,3,z),'smooth_sandstone_slab[type=bottom]')
    # Real footings, tall columns, capitals and arch haunches carrying the roof.
    for x in (6,16,26,36):
        for z in (14,23,33):
            m.box((x-1,2,z-1),(x+1,3,z+1),'cut_sandstone')
            m.box((x,4,z),(x,10,z),'smooth_sandstone')
            m.set(x,4,z,'cyan_terracotta')
            m.box((x-1,11,z-1),(x+1,11,z+1),'chiseled_sandstone')
            m.box((x-1,12,4),(x+1,12,35),'smooth_sandstone')
    for z in (14,23,33):
        m.box((4,12,z),(38,12,z),'cut_sandstone')
        for x in (6,16,26,36):
            for dx in (-2,2):
                if 4 <= x+dx <= 38:
                    m.set(x+dx,11,z,'sandstone_stairs[facing=' + ('east' if dx<0 else 'west') + ',half=top]')
    # Daylight wells on either side of the descending stair, glass seals rain out.
    for x in (10,30):
        for z in (18,27):
            m.box((x-1,13,z-1),(x+2,14,z+2),'air')
            m.box((x-1,14,z-1),(x+2,14,z+2),'light_blue_stained_glass')
            for xx in (x-2,x+3):m.box((xx,15,z-2),(xx,15,z+3),'smooth_sandstone_slab[type=bottom]')
            for zz in (z-2,z+3):m.box((x-1,15,zz),(x+2,15,zz),'smooth_sandstone_slab[type=bottom]')
    # Substantial central stair rests on its own masonry spine.
    m.box((18,3,11),(24,29,26),'air')
    for i in range(12):
        z,y=12+i,14-i
        m.box((18,2,z),(24,y,z),'cut_sandstone')
        for x in range(19,24):m.set(x,y,z,'sandstone_stairs[facing=north]')
        for x in (18,24):
            m.set(x,y+1,z,'smooth_sandstone')
            m.set(x,y+2,z,'sandstone_wall')
    # Safe rim around the open stair trench, and lower landing opening.
    for x in (17,25):m.box((x,15,11),(x,15,26),'sandstone_wall')
    m.box((18,15,26),(24,15,26),'sandstone_wall')
    m.box((18,3,24),(24,6,25),'air')
    # Stepped entry pavilion on the street side, with a supported timber cornice.
    m.box((12,14,2),(30,14,11),'cut_sandstone')
    for x in (13,29):m.box((x,15,3),(x,21,10),'smooth_sandstone')
    m.box((13,15,3),(29,21,3),'smooth_sandstone')
    m.box((19,15,3),(23,19,3),'air')
    for x in (16,26):
        m.box((x-1,14,9),(x+1,15,11),'cut_sandstone')
        m.box((x,16,10),(x,21,10),'smooth_sandstone')
        m.box((x-1,21,9),(x+1,21,11),'chiseled_sandstone')
    m.box((12,22,2),(30,22,11),'stripped_dark_oak_log[axis=x]')
    m.box((13,23,3),(29,23,10),'smooth_sandstone')
    m.box((15,24,4),(27,24,9),'smooth_sandstone')
    m.box((17,25,5),(25,25,8),'cut_sandstone')
    m.box((19,26,6),(23,26,7),'smooth_sandstone_slab[type=bottom]')
    # Street apron at surface level and low parapet defining the cistern terrace.
    m.box((18,14,0),(24,14,3),'smooth_sandstone')
    for x in (3,39):m.box((x,15,12),(x,15,36),'smooth_sandstone')
    m.box((3,15,36),(39,15,36),'smooth_sandstone')
    # Dry-side records and repair furniture, with reachable working space.
    m.set(30,3,7,'lectern[facing=south]')
    m.box((32,3,6),(35,4,6),'bookshelf')
    m.set(35,3,9,'barrel[facing=west]')
    m.set(35,4,9,'lantern')
    m.set(8,3,7,'grindstone[face=floor,facing=south]')
    m.set(6,3,7,'crafting_table')
    m.set(6,3,10,'barrel[facing=east]')
    m.set(29,15,6,'barrel[facing=west]')
    m.set(28,15,8,'lectern[facing=west]')
    for x,z in ((5,18),(37,18),(5,28),(37,28),(21,32),(30,8),(8,8)):
        m.box((x,10,z),(x,12,z),'chain[axis=y]')
        m.set(x,9,z,'lantern[hanging=true]')
    for x in (16,26):m.set(x,20,9,'wall_torch[facing=north]')
    m.point('front','entrance',(21,15,1),'地表仪式入口',facing='north')
    m.point('down','circulation',(21,3,25),'长阶地下落地平台',look_at=[10,6,26])
    m.point('register','work',(30,3,7),'水位与维护记录',approach=(30,3,8))
    m.point('maintenance','work',(8,3,7),'检修工作台',approach=(8,3,8))
    m.point('west_pool','circulation',(5,3,27),'西池干侧环道',look_at=[11,4,25])
    m.point('east_pool','circulation',(37,3,27),'东池干侧环道',look_at=[31,4,25])
    m.point('gate_register','work',(28,15,8),'入口值守登记',approach=(27,15,8))
    m.meta['connections']=[dict(kind='pedestrian',pos=[21,15,0],direction='north',clearance=[5,4],note='地表入口脚底 Y=15，需与现场地表对齐')]
    m.room('vault','双池地下柱厅',(4,3,14),(38,12,35),'两池储水与连续干侧检修环道')
    m.room('records','干侧管理与维修厅',(4,3,4),(38,10,12),'蓄水记录、工具与维修工作台')
    m.room('gate','阶顶入口亭',(14,15,4),(28,21,10),'有顶遮阳入口、值守与下行长阶')
    m.meta["ground_plane"] = {'y': 15, 'note': '自然地表上边界 Y=15；入口外沿支撑方块为 Y=14，地下柱厅脚底 Y=3。'}
    return m
