"""Versioned interior capacity updates; the accepted v01 envelopes stay intact."""
from .arcane_revision2 import dormitory, infirmary, professor_home
from .revision_geometry import bed


def wall_lights(model, wall_x, inward, standing_y, positions):
    """Low wall brackets leave the two-block walking envelope unobstructed."""
    for z in positions:
        x = wall_x + inward
        root = model.blocks.get((wall_x, standing_y + 3, z), ('minecraft:air',))[0]
        if root == 'minecraft:air':
            floor_lamp(model, x, standing_y, z)
        else:
            model.set(x, standing_y + 3, z, 'polished_andesite')
            model.set(x, standing_y + 2, z, 'lantern[hanging=true]')


def floor_lamp(model, x, y, z):
    model.box((x, y, z), (x, y + 1, z), 'stone_brick_wall')
    model.set(x, y + 2, z, 'lantern')


def ceiling_lamp(model, x, y, z, ceiling_y):
    model.set(x, y, z, 'lantern[hanging=true]')
    for chain_y in range(y + 1, ceiling_y):
        model.set(x, chain_y, z, 'chain[axis=y]')


def _revision(model, name, difference):
    model.meta.update(
        id=model.meta['family'] + '-v02',
        name=name,
        source='tools/structure_studio/studio/academy_capacity.py:BUILDERS',
        differences=[difference],
    )
    model.meta['design_notes'].append(
        '2026-10-19 容量微调：保留 v01 外形、占地、屋面、入口和实体楼梯，只调整内部床位与生活空间。')
    return model


def _bed(model, key, x, y, z, *, use='resident', color='purple'):
    bed(model, key, x, y, z, color)
    model.meta['points'][-1].update(use=use, name='病床' if use == 'care' else '住床')
    # A bedside cabinet is storage, not a barrel-derived occupational workplace.
    model.set(x - 1, y, z, 'chest[facing=south,type=single]')


def _existing_beds(model, use):
    for point in model.meta['points']:
        if point['kind'] == 'bed':
            point.update(use=use, name='病床' if use == 'care' else '住床')
            x, y, z = point['pos']
            model.set(x - 1, y, z, 'chest[facing=south,type=single]')


def dormitory_v02():
    model = _revision(dormitory(), '回廊学生宿舍 · 双翼三层六十床',
                      '每翼每层从 4 床增至 10 床，共 60 床；保留前部自习桌、两段实体楼梯和二层桥廊。')
    _existing_beds(model, 'resident')
    for x0, side in ((9, '西翼'), (43, '东翼')):
        for floor, y in enumerate((2, 9, 16), 1):
            wall_lights(model, x0, 1, y, (19, 25, 32, 39, 46, 53))
            wall_lights(model, x0 + 16, -1, y, (19, 25, 32, 39, 46, 53))
            model.box((x0, y + 5, 20), (x0 + 16, y + 5, 20), 'dark_oak_log[axis=x]')
            ceiling_lamp(model, x0 + 8, y + 3, 20, y + 5)
            model.point(f'study_{x0}_{floor}', 'meeting', (x0 + 8, y, 22),
                        '寝室自习与讨论', approach=(x0 + 8, y, 22), use='public')
            for x in (x0 + 6, x0 + 10):
                for z in (28, 34, 40):
                    _bed(model, f'resident_{x}_{y}_{z}', x, y, z)
            model.room(f'dorm_{x0}_{floor}', f'{side}{floor}层寝居',
                       (x0 + 5, y, 26), (x0 + 14, y + 4, 54),
                       '前部八床多人间与后部双床小寝室，共十床；楼梯侧通道连接自习区与寝居区。')
            model.meta['rooms'][-1]['living_use'] = 'resident'
    for point in model.meta['points']:
        if point['id'].startswith('floor3_'):
            point.update(kind='circulation', name='三层寝室公共通道')
    model.meta['design_notes'].append('各层寝居、前部自习区和楼梯侧设低位壁灯；床头灯照亮各自床侧，照明不依赖高屋顶。')
    for x in (29, 34, 39):
        for z in (22, 32, 42):
            model.set(x, 1, z, 'sea_lantern')
    model.meta['design_notes'].append('庭院步行面嵌入九处星石灯，保留中轴通道和花带，不设置阻路灯柱。')
    return model


def professor_home_v02():
    model = _revision(professor_home(), '导师小院 · 三间家庭寝室与玻璃冬园',
                      '上层从 2 床增至 6 床，三间双床寝室连接楼梯侧走廊；首层书斋与冬园保留。')
    _existing_beds(model, 'resident')
    # West-side landing remains open; each room has a doorway onto that corridor.
    model.box((15, 10, 18), (15, 13, 40), 'dark_oak_planks')
    for z in (26, 33):
        model.box((16, 10, z), (27, 13, z), 'dark_oak_planks')
    for z in (22, 28, 36):
        model.box((15, 10, z), (15, 12, z + 1), 'air')
    for x in (18, 23):
        for z in (23, 29):
            _bed(model, f'family_{x}_{z}', x, 10, z)
    for index, (z0, z1) in enumerate(((18, 25), (27, 32), (34, 40)), 1):
        model.room(f'bedroom_{index}', f'家庭双床寝室{index}',
                   (16, 10, z0), (26, 13, z1), '两张实体床、独立储物和通往楼梯侧走廊的门洞。')
        model.meta['rooms'][-1]['living_use'] = 'resident'
    for point in model.meta['points']:
        if point['id'] == 'upper':
            point.update(kind='circulation', pos=[14, 10, 32], approach=[14, 10, 32],
                         name='家庭寝室与楼梯相连的走廊')
    for y in (2, 10):
        wall_lights(model, 8, 1, y, (19, 25, 32, 39))
        wall_lights(model, 28, -1, y, (19, 25, 32, 39))
    wall_lights(model, 39, -1, 2, (26, 32, 39))
    for x in (18, 23):
        for z in (22, 31):
            ceiling_lamp(model, x, 4, z, 9)
    model.set(19, 4, 28, 'lantern')
    ceiling_lamp(model, 18, 4, 40, 9)
    model.meta['design_notes'].append('书斋、三间寝室、楼梯侧走廊和冬园分别设置低位壁灯，分隔后的房间独立获得照明。')
    return model


def infirmary_v02():
    model = _revision(infirmary(), '愈疗圣堂 · 十二床十字病房与日照庭',
                      '两侧病房各六张病床，保留中厅和十字护理通道；全部病床标为 care，不计居民住房。')
    old_beds = [p for p in model.meta['points'] if p['kind'] == 'bed']
    for point in old_beds:
        x, y, z = point['pos']
        for pos in ((x, y, z), (x, y, z - 1), (x - 1, y, z), (x - 1, y + 1, z)):
            model.set(*pos, 'air')
    model.meta['points'] = [p for p in model.meta['points'] if p['kind'] != 'bed']
    for x0, side in ((6, '西'), (42, '东')):
        for x in (x0 + 5, x0 + 11):
            for z in (35, 41, 47):
                _bed(model, f'patient_{x}_{z}', x, 2, z, use='care', color='white')
        model.room(f'ward_{x0}', f'{side}侧六床病房',
                   (x0 + 2, 2, 33), (x0 + 14, 6, 48),
                   '两列各三床，中间纵向护理通道与中厅横向通道相连，床侧保留使用站位。')
        model.meta['rooms'][-1]['living_use'] = 'care'
        wall_lights(model, x0, 1, 2, (34, 40, 47))
        wall_lights(model, x0 + 16, -1, 2, (34, 40, 47))
    for wall, direction in ((23, 1), (41, -1)):
        wall_lights(model, wall, direction, 2, (18, 25, 32, 39, 46, 54))
    for x in (28, 36):
        for z in (20, 29, 38, 47, 56):
            floor_lamp(model, x, 2, z)
    model.set(32, 4, 52, 'lantern')
    model.set(33, 4, 52, 'brewing_stand')
    model.meta['design_notes'].append('中央照护厅按开间布置低位壁灯，两侧病房另设床头灯和墙灯，保证护理活动层有独立光源。')
    return model


BUILDERS = {
    'MG-05-v02': dormitory_v02,
    'MG-15-v02': infirmary_v02,
    'MG-17-v02': professor_home_v02,
}
