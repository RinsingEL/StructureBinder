"""Usable lecture furniture and low lighting for the two compact classrooms."""
from .academy_support import house
from .academy_capacity import wall_lights


def classroom(number, medium=False):
    width, depth = (35, 31) if medium else (21, 21)
    model = house(number, '中等教学楼' if medium else '小课堂', width, depth,
                  ['教学', '阅读', '研讨'] if medium else ['教学', '研讨'], medium=medium)
    cx = width // 2
    model.meta.update(id=f'MG-{number:02d}-v02',
                      name='中等教学楼 · 二十四席课堂与双教师工位' if medium else '小课堂 · 八席研讨与讲台',
                      source='tools/structure_studio/studio/academy_classrooms.py:BUILDERS',
                      differences=['保留建筑外形和入口，重排实体学生桌椅、教师讲台与备课位；学生座位不算教师工位。'])
    model.box((4, 2, 5), (width - 5, 5, depth - 5), 'air')
    model.meta['points'] = [p for p in model.meta['points'] if p['kind'] != 'work']
    # The rear wall has a real teaching board and book storage, with clear aisles in front.
    model.box((cx - 3, 3, depth - 5), (cx + 3, 5, depth - 5), 'black_concrete')
    for x in (5, width - 7):
        model.box((x, 2, depth - 6), (x + 1, 4, depth - 6), 'bookshelf')
    teacher_z = depth - 7
    model.set(cx, 2, teacher_z, 'lectern[facing=north]')
    model.point('teacher', 'work', (cx, 2, teacher_z), '教师授课讲台',
                approach=(cx, 2, teacher_z - 1), use='staff')
    if medium:
        model.set(7, 2, depth - 9, 'lectern[facing=north]')
        model.point('assistant', 'work', (7, 2, depth - 9), '助教备课与课堂记录',
                    approach=(7, 2, depth - 10), use='staff')
    xs = (6, 10, 14, 20, 24, 28) if medium else (6, 8, 12, 14)
    zs = (8, 12, 16, 20) if medium else (8, 11)
    for x in xs:
        for z in zs:
            model.set(x, 2, z, 'birch_stairs[facing=south,half=top]')
            model.set(x, 2, z - 1, 'dark_oak_stairs[facing=south]')
            model.point(f'student_{x}_{z}', 'seat', (x, 2, z - 1), '学生听课与书写座位',
                        approach=(x - 1, 2, z - 1), use='public', adapter='marker_seat', seat_height=.5)
    positions = (7, 13, 19, 25) if medium else (7, 13, 16)
    wall_lights(model, 3, 1, 2, positions)
    wall_lights(model, width - 4, -1, 2, positions)
    # Distributed low pendants cover desks and the teaching zone, not just the ridge.
    for z in ((8, 14, 20, 25) if medium else (8, 14)):
        for x in ((10, cx, 26) if medium else (cx,)):
            model.set(x, 4, z, 'lantern[hanging=true]')
            for y in range(5, 9):
                model.set(x, y, z, 'chain[axis=y]')
        for x in range(3, width - 3):
            model.set(x, 9, z, 'dark_oak_log[axis=x]')
    model.meta['design_notes'].append(
        '中轴与桌列侧方保持步行通道；低位壁灯覆盖座位区，讲台设两点低垂灯，吊杆接到贯通木梁。')
    model.meta['rooms'][0]['purpose'] = ('24 个学生席位、1 个授课讲台、1 个助教备课位。' if medium
                                       else '8 个学生席位、1 个教师讲台与后墙教学板。')
    return model


BUILDERS = {'MG-25-v02': lambda: classroom(25, True), 'MG-26-v02': lambda: classroom(26)}
