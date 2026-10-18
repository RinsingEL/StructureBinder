"""Extract physical living facilities from a final NBT and optional author annotations.

Tag membership is supplied by the target pack, never inferred from a mod/block name.
Authored activity points supplement physical blocks; they do not invent beds.
"""
from __future__ import annotations
import math

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

    def text(value):
        return str(value).lower() if isinstance(value,bool) else str(value)

    def inside(p):
        return len(p) == 3 and all(isinstance(n, int) and not isinstance(n, bool) and 0 <= n < data['size'][i] for i, n in enumerate(p))

    def room_at(pos):
        rooms = [r for r in author.get('rooms', []) if all(r['min'][i] <= pos[i] <= r['max'][i] for i in range(3))]
        return min(rooms, key=lambda r: __import__('math').prod(r['max'][i] - r['min'][i] + 1 for i in range(3)), default={})

    def rotate(v, facing):
        x,y,z=v
        return {'north':(x,y,z),'east':(-z,y,x),'south':(-x,y,-z),'west':(z,y,-x)}[facing]

    def horizontal_facing(state, rule):
        facing=rule.get('facing') or state.get('properties',{}).get('facing','north')
        if facing not in ('north','south','east','west'):
            if rule.get('facing'):
                raise ValueError('Invalid living facility facing')
            # A barrel can face up/down; that is not the resident's horizontal orientation.
            return 'north'
        return facing

    def vector(value, bound, integral=False):
        if len(value)!=3 or any(isinstance(n,bool) or not isinstance(n,(int,float)) or not math.isfinite(n) or abs(n)>bound or (integral and n!=int(n)) for n in value):
            raise ValueError('Invalid living facility offset/approach')
        return value

    def add(kind, pos, state, annotation=None, rule=None, slot=None, required_parts=None):
        annotation, rule = annotation or {}, rule or {}
        slot=slot or {'id':'default'}
        if not inside(pos):
            raise ValueError(f'Living facility outside template: {pos}')
        key = (kind, tuple(pos), slot['id'])
        if key in occupied:
            return
        occupied.add(key)
        room = room_at(pos)
        use = annotation.get('use', room.get('living_use', rule.get('use', 'building')))
        if use not in ('building', 'resident', 'guest', 'care', 'public', 'staff'):
            raise ValueError(f'Invalid living use: {use}')
        facing=horizontal_facing(state,rule)
        approach = annotation.get('approach')
        if approach is None and 'approach' in slot:
            offset=rotate(vector(slot['approach'],8,True),facing)
            approach=[int(pos[i]+offset[i]) for i in range(3)]
        if approach is not None and not inside(approach):
            raise ValueError(f'Living approach outside template: {approach}')
        facility = dict(id=kind + ':' + ','.join(map(str, pos)) + (':'+slot['id'] if slot['id']!='default' else ''), kind=kind, pos=list(pos),
                        block=state['name'], facing=facing,
                        use=use, room=room.get('id', ''), name=annotation.get('name', kind),
                        adapter=rule.get('adapter', 'bed' if kind == 'bed' else 'stand'),
                        seatHeight=rule.get('seatHeight', .5))
        if approach is not None:
            facility['approach'] = approach
        if 'behavior' in rule:
            facility['behavior']=rule['behavior']
        if 'id' in rule:
            height=rule.get('height',rule.get('seatHeight',.15 if kind=='bed' else .5))
            if isinstance(height,bool) or not isinstance(height,(int,float)) or not math.isfinite(height) or not 0<=height<=2:
                raise ValueError('Invalid living surface height')
            facility.update(sourceRule=rule['id'],surfaceHeight=height,slot=slot['id'],
                            useOffset=list(rotate(vector(slot.get('offset',[0,0,0]),4),facing)),
                            requiredState=rule.get('state',{}),requiredParts=required_parts or [])
        facilities.append(facility)

    for pos, state in sorted(blocks.items()):
        name, props = state['name'], state.get('properties', {})
        annotation = next((p for p in points if tuple(p['pos']) == pos), {})
        configured_kinds=set()
        configured_slots=set()
        for index, original in enumerate(rules.get('facilities', [])):
            rule=dict(original)
            if name in ('minecraft:air','minecraft:cave_air','minecraft:void_air'):
                continue
            if not any(match(selector,name) for selector in rule.get('blocks',[])) or any(text(props.get(k))!=text(v) for k,v in rule.get('state',{}).items()):
                continue
            kind=rule.get('kind')
            if kind not in ('work','seat','bed','meeting'):
                raise ValueError('Invalid custom living facility kind')
            rule.setdefault('id','legacy_'+str(index))
            facing=horizontal_facing(state,rule)
            slots=rule.get('slots',[{'id':'default'}])
            if not 1<=len(slots)<=8 or len({s['id'] for s in slots})!=len(slots):
                raise ValueError('Living facility slots must be 1..8 distinct ids')
            selected=[s for s in slots if (kind,s['id']) not in configured_slots]
            configured_slots.update((kind,s['id']) for s in slots)
            required=[]
            complete=True
            for part in rule.get('requires',[]):
                delta=rotate(vector(part['offset'],8,True),facing)
                target=tuple(int(pos[i]+delta[i]) for i in range(3))
                actual=blocks.get(target,{})
                selectors=part.get('blocks',[])
                same=part.get('sameBlock',not selectors)
                states={k:props.get(v[1:],'<missing anchor property>') if isinstance(v,str) and v.startswith('$') else text(v) for k,v in part.get('state',{}).items()}
                if not inside(target) or not actual or (same and actual.get('name')!=name) or (selectors and not any(match(s,actual.get('name')) for s in selectors)) or any(text(actual.get('properties',{}).get(k))!=text(v) for k,v in states.items()):
                    complete=False
                required.append(dict(pos=list(target),blocks=selectors,state=states,sameBlock=same))
            configured_kinds.add(kind)
            if not complete:
                warnings.append(f'Incomplete configured facility {rule["id"]} at {pos}')
                continue
            for slot in selected:
                add(kind,pos,state,annotation,rule,slot,required)
        is_bed = name in tags.get('minecraft:beds', []) or name in {'minecraft:' + c + '_bed' for c in
            ('white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black')}
        if is_bed and 'bed' not in configured_kinds:
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
        elif name in {'minecraft:' + n for n in VANILLA_WORK} and 'work' not in configured_kinds:
            add('work', pos, state, annotation)
    for p in points:
        kind = p.get('kind')
        if kind not in ('entrance', 'work', 'seat', 'meeting'):
            continue
        pos = tuple(p['pos'])
        state = blocks.get(pos, {'name': 'minecraft:air', 'properties': {}})
        rule = {'adapter': p.get('adapter', 'stand'), 'seatHeight': p.get('seat_height', .5)}
        if 'behavior' in p:rule['behavior']=p['behavior']
        add(kind, pos, state, p, rule)
    terms=set(author.get('function_terms',[]))
    bed_count=sum(f['kind']=='bed' and f['use'] in ('building','resident') for f in facilities)
    work_count=sum(f['kind']=='work' for f in facilities)
    needs_beds=any(any(word in t.lower() for word in ('住宿','宿舍','住宅','residen','housing','dorm')) for t in terms)
    needs_work=any(any(word in t.lower() for word in ('教学','办事','行政','议事','养护','林业','医疗','餐饮','teaching','office','workshop')) for t in terms)
    if needs_beds and not bed_count:warnings.append('LIVING_CAPACITY_MISSING_BEDS: '+','.join(sorted(terms)))
    if needs_work and not work_count:warnings.append('LIVING_CAPACITY_MISSING_WORKPLACES: '+','.join(sorted(terms)))
    return dict(schema='realmfolk_facilities.v1', size=data['size'], sourceSha256=data['sha256'],
                capacity=dict(residentBeds=bed_count,workplaces=work_count),
                facilities=facilities, warnings=warnings)
