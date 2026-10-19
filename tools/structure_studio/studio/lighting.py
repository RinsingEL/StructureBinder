"""Conservative offline block-light estimate for authored occupied spaces.

This is not Minecraft's light engine: no sky light, partial collision blocks are
opaque, and state-dependent emitters are not simulated. It is an authoring check
for fixed lanterns, sea lanterns and end rods, not an in-game night acceptance.
"""
from collections import deque
import argparse
import json
from pathlib import Path

from .model import read_structure, write_json

FIXED_EMITTERS = {'minecraft:' + name for name in (
    'lantern', 'soul_lantern', 'sea_lantern', 'end_rod', 'glowstone', 'shroomlight',
    'ochre_froglight', 'verdant_froglight', 'pearlescent_froglight', 'torch',
    'wall_torch', 'soul_torch', 'soul_wall_torch', 'jack_o_lantern')}


def field(data, registry):
    width, height, depth = data['size']
    layer = width * depth
    levels = bytearray(layer * height)
    costs = bytearray([1]) * len(levels)
    emitters, queue = [], deque()
    palette = []
    for block in data['palette']:
        spec = registry[block['name']]
        if 'light_emission' not in spec or 'light_filter' not in spec:
            raise ValueError('Registry needs lighting fields: run node scripts/export_registry.mjs')
        collision = any(spec.get('collisions', []))
        cost = 15 if collision and not spec['transparent'] else max(1, spec['light_filter'])
        emission = spec['light_emission'] if block['name'] in FIXED_EMITTERS else 0
        palette.append((cost, emission))
    for block in data['blocks']:
        x, y, z = block['pos']
        i = y * layer + z * width + x
        costs[i], emission = palette[block['state']]
        if emission:
            levels[i] = emission
            queue.append(i)
            emitters.append({'pos': block['pos'], 'level': emission})
    while queue:
        i = queue.popleft()
        y, flat = divmod(i, layer)
        z, x = divmod(flat, width)
        neighbors = []
        if x: neighbors.append(i - 1)
        if x + 1 < width: neighbors.append(i + 1)
        if z: neighbors.append(i - width)
        if z + 1 < depth: neighbors.append(i + width)
        if y: neighbors.append(i - layer)
        if y + 1 < height: neighbors.append(i + layer)
        for target in neighbors:
            candidate = levels[i] - costs[target]
            if candidate > levels[target]:
                levels[target] = candidate
                queue.append(target)
    return levels, emitters


def audit(data, author, registry):
    levels, emitters = field(data, registry)
    width, height, depth = data['size']
    grid = {tuple(b['pos']): data['palette'][b['state']] for b in data['blocks']}

    def light(pos):
        x, y, z = pos
        return levels[y * width * depth + z * width + x]

    points = []
    for point in author.get('points', []):
        if point['kind'] == 'entrance':
            continue
        pos = point.get('approach', point['pos'])
        points.append({'id': point['id'], 'kind': point['kind'], 'pos': pos,
                       'estimatedBlockLight': light(pos)})
    floors = set()
    for room in author.get('rooms', []):
        for floor in author.get('floors', []):
            y = floor['y'] + 1
            if not room['min'][1] <= y <= room['max'][1] or y + 1 >= height:
                continue
            for x in range(room['min'][0], room['max'][0] + 1):
                for z in range(room['min'][2], room['max'][2] + 1):
                    support = grid.get((x, y - 1, z), {}).get('name', 'minecraft:air')
                    free = all(grid.get((x, yy, z), {}).get('name', 'minecraft:air') == 'minecraft:air'
                               for yy in (y, y + 1))
                    if free and support not in ('minecraft:air', 'minecraft:water', 'minecraft:lava'):
                        floors.add((x, y, z))
    # Also inspect roofed usable floor outside room labels (corridors, service wings,
    # platforms); repeat this from final NBT instead of trusting authoring-pass counts.
    floor_heights = {f['y'] + 1 for f in author.get('floors', [])}
    floor_heights.update(p.get('approach', p['pos'])[1] for p in author.get('points', []) if p['kind'] != 'entrance')
    for y in floor_heights:
        if y < 1 or y + 1 >= height: continue
        for x in range(1, width - 1):
            for z in range(1, depth - 1):
                support = grid.get((x, y - 1, z), {}).get('name', 'minecraft:air')
                if support in ('minecraft:air', 'minecraft:water', 'minecraft:lava'): continue
                if any(grid.get((x, yy, z), {}).get('name', 'minecraft:air') != 'minecraft:air' for yy in (y, y + 1)): continue
                roofed = any(grid.get((x, yy, z), {}).get('name', 'minecraft:air').endswith(
                    ('_planks', '_bricks', ':stone', ':calcite', ':polished_andesite', ':smooth_stone',
                     ':polished_deepslate', ':smooth_quartz', '_quartz', '_glass', ':glass', '_terracotta', '_slab', '_stairs'))
                    for yy in range(y + 3, height))
                if roofed: floors.add((x, y, z))
    floor_samples = [{'pos': list(pos), 'estimatedBlockLight': light(pos)} for pos in sorted(floors)]
    dark_points = [p for p in points if p['estimatedBlockLight'] < 8]
    dark_floor = [p for p in floor_samples if p['estimatedBlockLight'] < 5]
    return dict(schema='structure-studio.lighting-review.v1', nbt_sha256=data['sha256'],
                scope=__doc__, emitterCount=len(emitters), occupiedPointTarget=8, floorTarget=5,
                occupiedPoints=points, floorSampleCount=len(floor_samples),
                minimumOccupiedLight=min((p['estimatedBlockLight'] for p in points), default=None),
                minimumFloorLight=min((p['estimatedBlockLight'] for p in floor_samples), default=None),
                darkOccupiedPoints=dark_points, darkFloorSamples=dark_floor,
                passed=bool(points) and not dark_points and not dark_floor)


def main():
    from .server import TOOL, asset_paths
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ids', nargs='+')
    args = parser.parse_args()
    registry = json.loads((TOOL / '.cache/registry.json').read_text(encoding='utf-8'))
    paths = asset_paths()
    passed = True
    for key in args.ids:
        directory = paths[key]
        result = audit(read_structure(directory / 'structure.nbt'),
                       json.loads((directory / 'author.json').read_text(encoding='utf-8')), registry)
        write_json(directory / 'lighting.json', result)
        print(json.dumps({k: result[k] for k in ('passed', 'emitterCount', 'minimumOccupiedLight', 'minimumFloorLight')}
                         | {'id': key, 'darkPoints': len(result['darkOccupiedPoints']),
                            'darkFloorSamples': len(result['darkFloorSamples'])}), flush=True)
        passed &= result['passed']
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
