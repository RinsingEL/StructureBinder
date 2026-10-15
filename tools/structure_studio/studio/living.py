"""Extract physical living facilities from a final NBT and optional author annotations.

Tag membership is supplied by the target pack, never inferred from a mod/block name.
Authored activity points supplement physical blocks; they do not invent beds.
"""
from __future__ import annotations

VANILLA_WORK = {
    'blast_furnace', 'smoker', 'cartography_table', 'brewing_stand', 'composter',
    'barrel', 'fletching_table', 'cauldron', 'lectern', 'stonecutter', 'loom',
    'smithing_table', 'grindstone', 'crafting_table', 'furnace', 'anvil',
}


def extract(data, author, rules=None):
    rules = rules or {}
    tags = rules.get('tags', {})
    blocks = {tuple(b['pos']): data['palette'][b['state']] for b in data['blocks']}
    points = author.get('points', [])
    facilities, occupied, warnings = [], set(), []

    def match(selector, name):
        return name in tags.get(selector[1:], []) if selector.startswith('#') else name == selector

    def inside(p):
        return len(p) == 3 and all(isinstance(n, int) and not isinstance(n, bool) and 0 <= n < data['size'][i] for i, n in enumerate(p))

    def room_at(pos):
        rooms = [r for r in author.get('rooms', []) if all(r['min'][i] <= pos[i] <= r['max'][i] for i in range(3))]
        return min(rooms, key=lambda r: __import__('math').prod(r['max'][i] - r['min'][i] + 1 for i in range(3)), default={})

    def add(kind, pos, state, annotation=None, rule=None):
        annotation, rule = annotation or {}, rule or {}
        if not inside(pos):
            raise ValueError(f'Living facility outside template: {pos}')
        key = (kind, tuple(pos))
        if key in occupied:
            return
        occupied.add(key)
        room = room_at(pos)
        use = annotation.get('use', room.get('living_use', rule.get('use', 'building')))
        if use not in ('building', 'resident', 'guest', 'care', 'public', 'staff'):
            raise ValueError(f'Invalid living use: {use}')
        approach = annotation.get('approach')
        if approach is not None and not inside(approach):
            raise ValueError(f'Living approach outside template: {approach}')
        facility = dict(id=kind + ':' + ','.join(map(str, pos)), kind=kind, pos=list(pos),
                        block=state['name'], facing=state.get('properties', {}).get('facing', 'north'),
                        use=use, room=room.get('id', ''), name=annotation.get('name', kind),
                        adapter=rule.get('adapter', 'bed' if kind == 'bed' else 'stand'),
                        seatHeight=rule.get('seatHeight', .5))
        if approach is not None:
            facility['approach'] = approach
        facilities.append(facility)

    for pos, state in sorted(blocks.items()):
        name, props = state['name'], state.get('properties', {})
        annotation = next((p for p in points if tuple(p['pos']) == pos), {})
        is_bed = name in tags.get('minecraft:beds', []) or name in {'minecraft:' + c + '_bed' for c in
            ('white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black')}
        if is_bed:
            if props.get('part') != 'head':
                continue
            dx, dz = {'north': (0, -1), 'south': (0, 1), 'west': (-1, 0), 'east': (1, 0)}[props['facing']]
            foot = (pos[0] - dx, pos[1], pos[2] - dz)
            partner = blocks.get(foot, {})
            if partner.get('name') != name or partner.get('properties', {}).get('part') != 'foot' or partner.get('properties', {}).get('facing') != props['facing']:
                warnings.append(f'Incomplete bed at {pos}')
                continue
            annotation = next((p for p in points if p.get('kind') == 'bed' and tuple(p['pos']) in (pos, foot)), annotation)
            add('bed', pos, state, annotation)
        elif name in {'minecraft:' + n for n in VANILLA_WORK}:
            add('work', pos, state, annotation)
        for rule in rules.get('facilities', []):
            if any(match(selector, name) for selector in rule.get('blocks', [])):
                if rule['kind'] not in ('work', 'seat'):
                    raise ValueError('Custom facility rule must be work or seat')
                add(rule['kind'], pos, state, annotation, rule)
    for p in points:
        kind = p.get('kind')
        if kind not in ('entrance', 'work', 'seat', 'meeting'):
            continue
        pos = tuple(p['pos'])
        state = blocks.get(pos, {'name': 'minecraft:air', 'properties': {}})
        rule = {'adapter': p.get('adapter', 'stand'), 'seatHeight': p.get('seat_height', .5)}
        add(kind, pos, state, p, rule)
    return dict(schema='realmfolk_facilities.v1', size=data['size'], sourceSha256=data['sha256'],
                facilities=facilities, warnings=warnings)
