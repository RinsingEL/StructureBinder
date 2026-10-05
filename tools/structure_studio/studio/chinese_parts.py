"""Timber, tiled roofs and usable interiors for the authored S13 cores."""
from .model import Model

CATALOG_DIR = 'S13_chinese_timber'
ROOF = 'deepslate_tile'


def base(number, name, width, depth, height=23):
    m = Model(f'CH-{number:02d}-v01', name, (width, height, depth),
              family=f'CH-{number:02d}', civilization='中式木构', role='key',
              terrain={'选址': '完整稳定平地，外部路面脚底与本地 Y=2 对齐；保留全尺寸院落和檐口净空。',
                       '供给': '有人居住，需生活饮水、食物与燃料；园艺用水另行接入。',
                       '推荐情境': ['城镇核心院落或独立街区']})
    m.box((0, 0, 0), (width-1, 1, depth-1), 'stone_bricks')
    m.box((0, 2, 0), (width-1, height-1, depth-1), 'air')
    m.box((1, 1, 1), (width-2, 1, depth-2), 'gravel')
    m.meta.update(source='tools/structure_studio/studio/chinese.py:BUILDERS',
                  ground_plane={'y': 2, 'note': '外部街面和院落地坪顶面为 Y=2；建筑室内由明确台阶抬高。'},
                  floors=[dict(name='院落与室内剖面', y=1, max_y=5)], roof_min_y=7,
                  preview_context=dict(kind='flat', land_surface_y=2, padding=3, surface='grass'),
                  design_notes=['以用户中式小宅图的灰瓦、浅墙、深木构和生活细节为参考，尺寸及三个核心平面独立设计。',
                                '使用原版 1.20.1 方块；所有室内、院落与主要设施保留可达站位。'])
    return m


def pave(m, x0, z0, x1, z1, block='stone_bricks'):
    m.box((x0, 1, z0), (x1, 1, z1), block)


def roof(m, x0, z0, x1, z1, eave, *, ridge_x=None):
    """Two-block run per rise, closed gables, continuous supported eaves."""
    ridge_x = x1-x0 >= z1-z0 if ridge_x is None else ridge_x
    lo, hi = (z0-1, z1+1) if ridge_x else (x0-1, x1+1)
    start, end = (x0-1, x1+1) if ridge_x else (z0-1, z1+1)
    def put(u, v, y, b):
        m.set(v, y, u, b) if ridge_x else m.set(u, y, v, b)
    for i in range((hi-lo)//2+1):
        a, b = lo+i, hi-i
        yy = eave+i//2
        for u, face in ((a, 'south' if ridge_x else 'east'), (b, 'north' if ridge_x else 'west')):
            for v in range(start, end+1):
                put(u, v, yy, f'{ROOF}_stairs[facing={face}]' if i%2 == 0 else 'deepslate_tiles')
            # Gable infill and rafters meet the roof rather than floating below it.
            for v in (start+1, end-1):
                for y in range(eave, yy):
                    put(u, v, y, 'white_terracotta')
                if yy > eave:
                    put(u, v, yy-1, 'spruce_planks')
        # Deep timber fascia supports the first roof course.
        if i == 0:
            for u in (a, b):
                for v in range(start, end+1): put(u, v, eave-1, 'spruce_slab[type=top]')
    center = (lo+hi)//2
    top = eave+((hi-lo)//2)//2
    for v in range(start, end+1):
        put(center, v, top, 'deepslate_tiles')
        put(center, v, top+1, 'deepslate_tile_slab[type=bottom]')
    for v in (start, end):
        put(center, v, top+1, 'polished_deepslate_wall')
    for u in (lo, hi):
        for v in (start, end):
            put(u, v, eave+1, 'deepslate_tile_slab[type=bottom]')


def window(m, x, z, floor, side):
    links = 'east=true,west=true' if side in ('north', 'south') else 'north=true,south=true'
    for y in (floor+2, floor+3): m.set(x, y, z, f'spruce_fence[{links}]')
    m.set(x, floor+1, z, 'spruce_planks')
    m.set(x, floor+4, z, 'spruce_planks')


def building(m, key, title, x0, z0, x1, z1, *, floor=2, wall_height=5, color='calcite', ridge_x=None, room=True):
    m.box((x0, 1, z0), (x1, floor, z1), 'stone_bricks')
    m.box((x0, floor+1, z0), (x1, floor+wall_height, z1), color)
    m.box((x0+1, floor+1, z0+1), (x1-1, floor+wall_height, z1-1), 'air')
    m.box((x0+1, floor, z0+1), (x1-1, floor, z1-1), 'spruce_planks')
    for x in sorted({x0, x1, *range(x0, x1+1, 4)}):
        for z in (z0, z1): m.box((x, floor+1, z), (x, floor+wall_height, z), 'dark_oak_log[axis=y]')
    for z in sorted({z0, z1, *range(z0, z1+1, 4)}):
        for x in (x0, x1): m.box((x, floor+1, z), (x, floor+wall_height, z), 'dark_oak_log[axis=y]')
    for z in (z0, z1):
        m.box((x0, floor+wall_height, z), (x1, floor+wall_height, z), 'spruce_log[axis=x]')
        for x in range(x0+2, x1, 4): window(m, x, z, floor, 'north')
    for x in (x0, x1):
        m.box((x, floor+wall_height, z0), (x, floor+wall_height, z1), 'spruce_log[axis=z]')
        for z in range(z0+2, z1, 4): window(m, x, z, floor, 'east')
    # Tie beams span the building and visibly join the wall plates.
    for z in range(z0+2, z1, 4):
        m.box((x0+1, floor+wall_height, z), (x1-1, floor+wall_height, z), 'spruce_log[axis=x]')
    roof(m, x0, z0, x1, z1, floor+wall_height+1, ridge_x=ridge_x)
    if room: m.room(key, title, (x0+1, floor+1, z0+1), (x1-1, floor+wall_height, z1-1), title)
    for x in range(x0+2, x1, 6):
        m.set(x, floor+wall_height-1, z0+2, 'lantern[hanging=true]')
    return (x0, z0, x1, z1, floor)


def veranda(m, x0, z0, x1, floor=2):
    """Ground-level covered frontage; does not erase existing entry stairs."""
    pave(m,x0,z0-3,x1,z0-1)
    columns=(x0,x0+4,x1-4,x1) if x1-x0>=16 else (x0,x1)
    for x in columns:
        m.box((x,2,z0-3),(x,floor+3,z0-3),'dark_oak_log')
        m.set(x,2,z0-3,'stone_bricks')
        m.set(x,floor+3,z0-2,'spruce_stairs[facing=south,half=top]')
    m.box((x0,floor+4,z0-3),(x1,floor+4,z0-3),'spruce_log[axis=x]')
    for z in range(z0-3,z0):
        for x in range(x0-1,x1+2):
            m.set(x,floor+5,z,'deepslate_tile_slab[type=bottom]' if z==z0-3 else 'deepslate_tile_stairs[facing=south]')
    for x in (x0+2,x1-2):m.set(x,floor+3,z0-3,'lantern[hanging=true]')


def gallery(m,x,z0,z1):
    for z in (z0,z0+7,z0+14,z1):
        m.box((x,2,z),(x,5,z),'dark_oak_log')
    m.box((x,6,z0),(x,6,z1),'spruce_log[axis=z]')
    m.box((x-1,7,z0-1),(x+1,7,z1+1),'deepslate_tile_slab[type=bottom]')


def doorway(m, key, x, z, floor=2, side='north', width=1):
    dx, dz = dict(north=(0,-1), south=(0,1), east=(1,0), west=(-1,0))[side]
    for i in range(width):
        xx, zz = (x+i, z) if dx == 0 else (x, z+i)
        m.box((xx, floor+1, zz), (xx, floor+3, zz), 'air')
        m.door(xx, floor+1, zz, facing=side, hinge='left' if i%2==0 else 'right')
        # Stairs rise from the courtyard to the raised indoor floor.
        for step in range(floor-1):
            dist = floor-1-step
            sx, sz, sy = xx+dx*dist, zz+dz*dist, 2+step
            m.box((sx, 1, sz), (sx, sy-1, sz), 'stone_bricks')
            m.set(sx, sy, sz, 'stone_brick_stairs[facing='+dict(north='south',south='north',east='west',west='east')[side]+']')
    # The room-side point is an audit target, not an independent path seed.
    m.point(key, 'circulation', (x-dx, floor+1, z-dz), '室内入口通路', facing=side)


def gate(m, cx, z=4):
    building(m, 'gate', '门厅与接待', cx-5, z, cx+5, z+6, floor=1, room=False)
    m.box((cx-1, 2, z), (cx+1, 5, z+6), 'air')
    for x in (cx-3, cx+3): m.set(x, 5, z-1, 'lantern[hanging=true]')
    pave(m, cx-2, 0, cx+2, z+8)
    m.point('front', 'entrance', (cx, 2, 0), '正门外部街面', facing='north')
    m.point('gate_path', 'circulation', (cx, 2, z+3), '正门贯通通道')


def boundary(m):
    w, _, d = m.size
    for z in (2, d-3):
        for x in range(2, w-2):
            if z == 2 and abs(x-w//2) <= 3: continue
            m.set(x, 2, z, 'stone_bricks'); m.set(x, 3, z, 'white_terracotta')
            m.set(x, 4, z, 'deepslate_tile_slab[type=bottom]')
    for x in (2, w-3):
        for z in range(3, d-3):
            m.set(x, 2, z, 'stone_bricks'); m.set(x, 3, z, 'white_terracotta')
            m.set(x, 4, z, 'deepslate_tile_slab[type=bottom]')
    for x in (2, w-3):
        for z in (2, d-3):
            m.box((x, 2, z), (x, 4, z), 'stone_bricks')
            m.set(x, 5, z, 'deepslate_tile_slab')


def desk(m, key, x, z, y=3, kind='work', block='lectern[facing=south]', name='工作台'):
    m.set(x, y, z, block)
    m.point(key, kind, (x, y, z), name, approach=(x, y, z+1), look_at=[x,y+1,z])


def bed(m, key, x, z, y=3, color='white'):
    m.bed(x, y, z, color, facing='north')
    m.set(x-1, y, z-1, 'barrel[facing=up]')
    m.point(key, 'bed', (x,y,z), '床位', approach=(x+1,y,z), look_at=[x,y,z-1])


def table(m, x, z, y=3, length=3):
    m.box((x,y,z), (x+length-1,y,z), 'spruce_slab[type=top]')
    for xx in (x,x+length-1):
        m.set(xx,y,z-1,'spruce_stairs[facing=south]')
        m.set(xx,y,z+1,'spruce_stairs[facing=north]')


def kitchen(m, key, x, z, y=3):
    desk(m,key,x,z,y,block='smoker[facing=south]',name='备餐与炉灶')
    m.set(x+1,y,z,'crafting_table');m.set(x+2,y,z,'water_cauldron[level=3]')
    m.set(x+3,y,z,'barrel[facing=south]')
    m.set(x,y+1,z,'stone_brick_wall')
    m.set(x+3,y+1,z,'lantern')


def wash(m, key, x, z, y=3):
    desk(m,key,x,z,y,block='water_cauldron[level=3]',name='洗漱与取水')
    m.set(x+1,y,z,'barrel[facing=south]')
    m.set(x+1,y+1,z,'spruce_trapdoor[facing=north,open=true]')


def garden(m,x0,z0,x1,z1):
    pave(m,x0,z0,x1,z1,'grass_block')
    for x in range(x0,x1+1,2):
        for z in range(z0,z1+1,3):m.set(x,2,z,'poppy' if (x+z)%3 else 'fern')
    for x,z in ((x0,z0),(x1,z1)):
        m.set(x,2,z,'azalea')


def pergola(m, key, x0,z0,x1,z1,theme='grape'):
    if theme not in ('grape','gourd','flower'):raise ValueError('Unknown garden theme')
    pave(m,x0,z0,x1,z1,'grass_block')
    pave(m,x0+2,z0,x1-2,z1,'stone_bricks')
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,2,z),(x,5,z),'spruce_fence[north=true,south=true]')
        m.box((x,6,z0),(x,6,z1),'spruce_log[axis=z]')
    for z in range(z0,z1+1,2):
        m.box((x0,6,z),(x1,6,z),'spruce_planks')
        for x in range(x0+1,x1):
            m.set(x,7,z,'flowering_azalea_leaves[persistent=true]' if theme=='flower' else 'oak_leaves[persistent=true]')
        if theme=='grape':
            for x in (x0+1,x1-1):m.set(x,5,z,'amethyst_cluster[facing=down]')
        elif theme=='gourd':
            for x in (x0+1,x1-1):m.set(x,5,z,'pumpkin')
    m.set(x0+1,2,z1-1,'composter')
    m.set(x1-1,2,z1-1,'barrel')
    m.point(key,'work', (x0+1,2,z1-1),'棚架养护与采收',approach=(x0+2,2,z1-1))
    m.room(key,key,(x0,2,z0),(x1,7,z1),'园艺部件；葡萄果串使用紫晶簇、葫芦使用南瓜作静态原版表达')
    m.meta.setdefault('design_notes',[]).append('种植棚为可配置静态景观：grape/flower/gourd；不声明原版存在葡萄或葫芦作物。')


def corridor(m,x0,z0,x1,z1):
    pave(m,x0,z0,x1,z1)
    for x in (x0,x1):
        for z in range(z0,z1+1,5):
            m.box((x,2,z),(x,5,z),'stripped_spruce_log')
        m.box((x,6,z0),(x,6,z1),'spruce_log[axis=z]')
    roof(m,x0,z0,x1,z1,7,ridge_x=False)
