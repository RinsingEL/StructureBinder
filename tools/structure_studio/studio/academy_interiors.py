"""Academy-wide functional interiors and supported, distributed light fixtures.

Each selected building family gets a new version; existing delivered versions
are preserved. The placement pass only inserts lights above headroom or replaces
ordinary solid floor tiles with full-cube inlaid lights, never changes the shell.
"""
from functools import lru_cache, partial
import json
from pathlib import Path

from .arcane_revision2 import BUILDERS as CORES
from .academy_support import BUILDERS as SUPPORT
from .academy_wings import BUILDERS as WINGS
from .campus_daily import BUILDERS as DAILY
from .academy_capacity import BUILDERS as CAPACITY
from .academy_classrooms import BUILDERS as CLASSROOMS
from .lighting import field

NUMBERS = tuple(list(range(1, 9)) + list(range(12, 24)) + list(range(25, 29))
                + [31, 32] + list(range(34, 41)) + [43, 44, 45])
AIR = 'minecraft:air'
FLOOR_MATERIALS = {
    'stone', 'stone_bricks', 'polished_andesite', 'smooth_stone', 'dark_oak_planks',
    'spruce_planks', 'oak_planks', 'birch_planks', 'polished_deepslate', 'calcite',
    'quartz_block', 'smooth_quartz', 'moss_block', 'grass_block', 'cobblestone',
    'deepslate_bricks', 'waxed_cut_copper', 'amethyst_block',
}
FLOOR_MATERIALS = {'minecraft:' + n for n in FLOOR_MATERIALS}


@lru_cache(maxsize=1)
def registry():
    return json.loads((Path(__file__).resolve().parents[1] / '.cache/registry.json').read_text(encoding='utf-8'))


def name(model, pos):
    return model.blocks.get(tuple(pos), (AIR,))[0]


def free_stance(model, pos):
    x, y, z = pos
    return (model.inside((x, y + 1, z)) and y > 0
            and name(model, (x, y, z)) == AIR and name(model, (x, y + 1, z)) == AIR
            and name(model, (x, y - 1, z)) in FLOOR_MATERIALS)


def data_for(model):
    states = sorted(set(model.blocks.values()))
    indices = {state: i for i, state in enumerate(states)}
    return dict(size=list(model.size), sha256='authoring-preview',
                palette=[dict(name=n, properties=dict(p)) for n, p in states],
                blocks=[dict(pos=list(pos), state=indices[state]) for pos, state in model.blocks.items()])


def lighting_targets(model):
    targets = {}
    for p in model.meta['points']:
        pos = tuple(p.get('approach', p['pos']))
        if p['kind'] != 'entrance' and free_stance(model, pos):
            targets[pos] = 8
    w, h, d = model.size
    floors = {floor['y'] + 1 for floor in model.meta['floors']}
    # Points also cover special raised platforms such as the quay and stair landings.
    floors.update(p.get('approach', p['pos'])[1] for p in model.meta['points'] if p['kind'] != 'entrance')
    for y in floors:
        for x in range(1, w - 1):
            for z in range(1, d - 1):
                pos = (x, y, z)
                if not free_stance(model, pos):
                    continue
                inside_room = any(all(r['min'][i] <= pos[i] <= r['max'][i] for i in range(3))
                                  for r in model.meta['rooms'])
                roofed = any(name(model, (x, yy, z)) in FLOOR_MATERIALS
                             or name(model, (x, yy, z)).endswith(('_glass', ':glass', '_terracotta', '_slab', '_stairs'))
                             for yy in range(y + 3, h))
                if inside_room or roofed:
                    targets[pos] = max(5, targets.get(pos, 0))
    return targets


def light_fixture(model, pos):
    """Prefer a supported low pendant; high/open spaces get a flush star-stone tile."""
    x, y, z = pos
    if not free_stance(model, pos):
        return None
    bulb_y = y + 3
    if model.inside((x, bulb_y, z)) and name(model, (x, bulb_y, z)) == AIR:
        for anchor_y in range(bulb_y + 1, min(model.size[1], y + 15)):
            material = name(model, (x, anchor_y, z))
            if material == AIR:
                continue
            if material in FLOOR_MATERIALS or material.endswith((':glass', '_stained_glass', '_terracotta')):
                model.set(x, bulb_y, z, 'lantern[hanging=true]')
                for yy in range(bulb_y + 1, anchor_y):
                    model.set(x, yy, z, 'chain[axis=y]')
                return dict(kind='supported_pendant', pos=[x, bulb_y, z], support=[x, anchor_y, z])
            # Do not overwrite shelves, equipment, existing fixtures or incomplete roof shapes.
            break
    if name(model, (x, y - 1, z)) in FLOOR_MATERIALS:
        model.set(x, y - 1, z, 'sea_lantern')
        return dict(kind='inlaid_floor', pos=[x, y - 1, z])
    return None


def illuminate(model):
    targets = lighting_targets(model)
    fixtures = []
    w, _, d = model.size
    for attempt in range(6):
        levels, _ = field(data_for(model), registry())
        dark = [(pos, target - levels[pos[1] * w * d + pos[2] * w + pos[0]])
                for pos, target in targets.items()
                if levels[pos[1] * w * d + pos[2] * w + pos[0]] < target]
        if not dark:
            break
        added = []
        for pos, deficit in sorted(dark, key=lambda item: (-item[1], item[0])):
            if any(abs(pos[0] - q[0]) + abs(pos[1] - q[1]) + abs(pos[2] - q[2]) < 7 for q in added):
                continue
            fixture = light_fixture(model, pos)
            if fixture:
                fixtures.append(fixture)
                added.append(pos)
        if not added:
            raise ValueError(f"{model.meta['id']}: no supported fixture for dark points {dark[:5]}")
    else:
        raise ValueError(f"{model.meta['id']}: lighting did not converge")
    model.meta['interior_lighting'] = dict(
        source='fixed_emitters_no_skylight_conservative_estimate',
        occupiedPointTarget=8, floorTarget=5, sampledPositions=len(targets), fixtures=fixtures,
        note='低位吊灯使用实际屋面或楼板承吊；高厅与开放处采用齐平星石灯。估计不替代 Minecraft 夜间验收。')
    return model


def add_work(model, key, pos, title):
    if not any(p['id'] == key for p in model.meta['points']):
        model.point(key, 'work', pos, title, approach=pos, use='staff')


def furnish_work(model, number):
    public = {1: {'gallery'}, 5: set(), 7: {'dining'}, 8: {'bridge'},
              23: {'waiting'}, 37: {'reading-1', 'reading-11'}, 44: {'wash'}}
    for point in model.meta['points']:
        if point['id'] in public.get(number, set()):
            point.update(kind='circulation' if number == 8 else 'meeting', use='public')
        if point['kind'] in ('work', 'meeting', 'circulation') and 'approach' not in point:
            if free_stance(model, point['pos']):
                point['approach'] = point['pos'].copy()
    additions = {
        7: [('kitchen_assistant', (45, 2, 51), '备餐与分餐人员')],
        12: [('herb_west', (12, 2, 24), '西温室园艺养护'), ('herb_east', (30, 2, 24), '东温室药草管理')],
        28: [('cook', (5, 2, 19), '食堂烹饪人员'), ('service', (12, 2, 21), '食堂分餐服务')],
        31: [('greenhouse_assistant', (11, 2, 19), '园艺学徒操作位')],
        32: [('tool_repair', (6, 2, 7), '工具维护人员'), ('tool_stock', (8, 2, 13), '工具保管人员')],
        43: [('lab_assistant', (11, 2, 10), '实验备料与器材管理')],
        44: [('laundry', (11, 2, 18), '洗衣与毛巾管理'), ('bath_service', (11, 2, 7), '洗浴接待与清洁')],
        45: [('shopkeeper', (12, 2, 16), '室内店员与货品登记')],
    }
    for key, pos, title in additions.get(number, []):
        add_work(model, key, pos, title)
    equipment = ('brewing_stand' if number in (4, 15, 18, 43) else
                 'composter' if number in (12, 21, 31) else
                 'crafting_table' if number in (16, 32) else
                 'smoker' if number in (7, 28) else 'lectern')
    reserved = {tuple(p.get('approach', p['pos'])) for p in model.meta['points']}
    for point in model.meta['points']:
        if point['kind'] != 'work':
            continue
        point['use'] = 'staff'
        pos = tuple(point.get('approach', point['pos']))
        point['approach'] = list(pos)
        if name(model, point['pos']) != AIR or not free_stance(model, pos):
            continue
        x, y, z = pos
        # Preserve the exact reachable actor position; put the actual tool beside it.
        options = [(x + dx, y, z + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        for target in options:
            if target in reserved or not free_stance(model, target):
                continue
            tx, ty, tz = target
            if equipment == 'brewing_stand':
                model.set(tx, ty, tz, 'polished_andesite')
                ty += 1
            model.set(tx, ty, tz, equipment)
            point['pos'] = [tx, ty, tz]
            reserved.add(target)
            break
        else:
            # An open observation/guard position can be a real job without a fabricated table.
            point['name'] += '（保留站立操作空间）'
    model.meta['workplace_policy'] = 'authored'
    model.meta['design_notes'].append('仅作者声明的工作点计入岗位容量；装饰桶、家庭储物与学生/访客使用设施不冒充员工工位。')


def mark_seating(model, number):
    if number in (25, 26):
        return
    if number not in (1, 2, 7, 13, 17, 19, 28, 34, 35, 36, 37, 38):
        return
    reserved = {tuple(p['pos']) for p in model.meta['points']}
    floors = {f['y'] + 1 for f in model.meta['floors']}
    for (x, y, z), (material, props) in sorted(model.blocks.items()):
        if (y not in floors or material not in {'minecraft:birch_stairs', 'minecraft:dark_oak_stairs', 'minecraft:oak_stairs'}
                or dict(props).get('half', 'bottom') != 'bottom' or (x, y, z) in reserved):
            continue
        choices = [(x + dx, y, z + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        approach = next((p for p in choices if free_stance(model, p)), None)
        if number == 17 and (x, y, z) == (20, 2, 27):
            # The east side is pinched by the new research lectern; enter from the west aisle.
            approach = (19, 2, 27)
        if approach:
            model.point(f'seat_{x}_{y}_{z}', 'seat', (x, y, z), '公共阅读、听课或用餐座位',
                        approach=approach, use='resident' if number in (17, 37) else 'public',
                        adapter='marker_seat', seat_height=.5)


def mark_beds(model):
    marked = {tuple(p['pos']) for p in model.meta['points'] if p['kind'] == 'bed'}
    for (x, y, z), (material, props) in sorted(model.blocks.items()):
        if not material.endswith('_bed') or dict(props).get('part') != 'foot' or (x, y, z) in marked:
            continue
        approaches = [(x + dx, y, z + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        approach = next((p for p in approaches if free_stance(model, p)), None)
        if approach is None:
            raise ValueError(f"{model.meta['id']}: no standing approach for bed {(x, y, z)}")
        model.point(f'resident_bed_{x}_{y}_{z}', 'bed', (x, y, z), '住床', approach=approach, use='resident')


def build(number):
    family = f'MG-{number:02d}'
    revised_id = family + ('-v03' if number in (34, 37) else '-v02')
    if family + '-v02' in CAPACITY:
        model = CAPACITY[family + '-v02']()
    elif family + '-v02' in CLASSROOMS:
        model = CLASSROOMS[family + '-v02']()
    elif number in (34, 37):
        model = DAILY[family + '-v02']()
    elif family + '-v01' in CORES:
        model = CORES[family + '-v01']()
    elif family in SUPPORT:
        model = SUPPORT[family]()
    elif family + '-v01' in WINGS:
        model = WINGS[family + '-v01']()
    else:
        model = DAILY[family + '-v01']()
    model.meta.update(id=revised_id, source='tools/structure_studio/studio/academy_interiors.py:BUILDERS')
    model.meta.setdefault('differences', []).append('2026-10-19 全学院可进入建筑：补齐功能工位/公共席位，分层分区照明，保留旧版本。')
    if number == 28:
        for point in model.meta['points']:
            if point['id'].startswith('dining_'):
                x, y, z = point['pos']
                model.set(x, y, z, 'birch_stairs[facing=' + ('east' if x == 6 else 'west') + ']')
                point.update(kind='seat', name='食堂用餐座位', use='public', adapter='marker_seat',
                             seat_height=.5, approach=[x - 1 if x == 6 else x + 1, y, z])
    furnish_work(model, number)
    mark_seating(model, number)
    mark_beds(model)
    # The facade eaves are outside room labels; retain light on their usable floor.
    if number == 25:
        for x in (12, 22):
            model.set(x, 1, 3, 'sea_lantern')
    if number == 45:
        model.set(11, 1, 22, 'sea_lantern')
    return illuminate(model)


BUILDERS = {f'MG-{n:02d}-v{3 if n in (34, 37) else 2:02d}': partial(build, n) for n in NUMBERS}
