"""Conservatively annotate exterior ground-floor doors; preserve existing work."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

import nbtlib
import numpy as np

AIR = {'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'}
DIRECTIONS = {'NORTH': (0, -1), 'SOUTH': (0, 1), 'WEST': (-1, 0), 'EAST': (1, 0)}


def find_entrances(data):
    size = tuple(map(int, data['size']))
    grid = np.zeros(size, dtype=np.int32)
    palette = [p.unpack() for p in data['palette']]
    for block in data['blocks']:
        grid[tuple(map(int, block['pos']))] = int(block['state'])
    def at(x, y, z):
        return palette[int(grid[x, y, z])]
    def clear(x, y, z):
        return (0 <= x < size[0] and 0 <= y < size[1] and 0 <= z < size[2]
                and at(x, y, z)['Name'] in AIR)
    def floor(x, y, z):
        if y < 0:
            return False
        p = at(x, y, z)
        n = p['Name']
        # Only unambiguous full-block support. Partial blocks require manual review.
        return n in {'minecraft:grass_block', 'minecraft:dirt', 'minecraft:coarse_dirt',
                     'minecraft:stone', 'minecraft:cobblestone', 'minecraft:sand',
                     'minecraft:red_sand', 'minecraft:sandstone', 'minecraft:red_sandstone',
                     'minecraft:terracotta', 'minecraft:gravel', 'minecraft:andesite',
                     'minecraft:diorite', 'minecraft:granite', 'minecraft:deepslate'} or n.endswith((
                         '_planks', '_log', '_wood', '_bricks', '_terracotta', '_concrete',
                         '_wool', '_sandstone', '_quartz_block', ':quartz_block', ':bone_block'))
    doors = []
    for x, y, z in np.ndindex(size):
        p = at(x, y, z)
        if p['Name'].endswith('_door') and p.get('Properties', {}).get('half') == 'lower':
            doors.append((x, y, z, p))
    if not doors:
        return [], '无门方块：需查看开放入口或装饰用途'
    lowest = min(d[1] for d in doors)
    found = []
    for x, y, z, p in doors:
        if y != lowest or y > 2 or p['Name'] == 'minecraft:iron_door':
            continue
        facing = p.get('Properties', {}).get('facing', '').upper()
        directions = ['NORTH', 'SOUTH'] if facing in ('NORTH', 'SOUTH') else ['WEST', 'EAST']
        paths = []
        for direction in directions:
            dx, dz = DIRECTIONS[direction]
            xx, zz = x + dx, z + dz
            path = []
            while 0 <= xx < size[0] and 0 <= zz < size[2]:
                if not (clear(xx, y, zz) and clear(xx, y + 1, zz) and floor(xx, y - 1, zz)):
                    break
                path.append([xx, y, zz])
                xx += dx
                zz += dz
            else:
                if path:
                    paths.append((direction, path))
                elif floor(x, y-1, z) and clear(x-dx, y, z-dz) and clear(x-dx, y+1, z-dz):
                    # A door already on the template edge needs no outward projection.
                    paths.append((direction, [[x, y, z]]))
        # Two clear exits from the same door imply ambiguous exterior/interior orientation.
        if len(paths) == 1:
            direction, path = paths[0]
            end = path[-1]
            found.append(dict(direction=direction, position=dict(x=end[0], z=end[2]),
                              door=[x, y, z], path=path))
    # Adjacent leaves of a double door describe a single road entrance.
    merged = []
    for entry in found:
        if not any(e['direction'] == entry['direction'] and
                   sum(abs(a-b) for a, b in zip(e['door'], entry['door'])) <= 1 for e in merged):
            merged.append(entry)
    return merged, '底层木门到边界直线可达；全程同高实心支撑、两格空气' if merged else '缺少可确认的平直外接通道：需检查楼梯、院门或开放入口'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--annotations', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert not args.output.exists(), 'Never overwrite an annotation output'
    source = json.loads((args.bundle / 'city_config/city_assets.json').read_text(encoding='utf8'))
    original = json.loads(args.annotations.read_text(encoding='utf-8-sig'))
    assets = {r['id']: r for r in source['templates']}
    assert len(original['templates']) == len(assets)
    assert len({r['id'] for r in original['templates']}) == len(assets)
    result = copy.deepcopy(original)
    report = []
    for r in result['templates']:
        a = assets[r['id']]
        assert a['templateRef'] == r['templateRef'] and a['sourceSha256'] == r['sourceSha256']
        if r['reviewed'] or r['roadEntrances'] or r['noRoadEntrance'] or r.get('note'):
            report.append(dict(id=r['id'], name=a['displayName'], status='保留已有标注'))
            continue
        path = args.bundle / 'city_config' / a['sourceNbt']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == r['sourceSha256']
        entries, reason = find_entrances(nbtlib.load(path))
        if entries:
            r['roadEntrances'] = [dict(entranceId=f"asset_{r['id']}_entrance_{i}",
                position=e['position'], direction=e['direction']) for i, e in enumerate(entries, 1)]
            r['reviewed'] = True
            r['note'] = '由助手批量标注：' + reason + '。未做游戏内实测。'
        report.append(dict(id=r['id'], name=a['displayName'], status='助手标注' if entries else '待核对',
                           reason=reason, entries=entries))
        print(r['id'], report[-1]['status'], flush=True)
    result['updatedAt'] = __import__('datetime').datetime.now(__import__('datetime').timezone.utc).isoformat()
    args.output.mkdir(parents=True)
    (args.output / '入口标注_合并.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
    (args.output / '标注依据.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
    (args.output / '手工标注原件.json').write_bytes(args.annotations.read_bytes())
    from collections import Counter
    print(dict(Counter(r['status'] for r in report)))


if __name__ == '__main__':
    main()
