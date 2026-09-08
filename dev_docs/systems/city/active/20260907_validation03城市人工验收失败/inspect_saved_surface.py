"""Read-only inspection of saved region blocks against the frozen surface plan."""
import collections
import io
import json
import pathlib
import zlib
import nbtlib

SAVE = pathlib.Path(r'D:\PCL2-2.8.12\.minecraft\versions\1.20.1-Forge_47.4.23\saves\RTF_beta_validation_03')
STEPS = SAVE / 'realm_debug/provider_bd973b975f0bead3_r8192/city_test_runs/city_qareth_skywatch_post/steps'
CACHE = {}

def block(x, y, z):
    cx, cz = x // 16, z // 16
    if (cx, cz) not in CACHE:
        path = SAVE / 'region' / f'r.{cx//32}.{cz//32}.mca'
        with path.open('rb') as f:
            f.seek(4 * ((cx % 32) + (cz % 32) * 32))
            location = int.from_bytes(f.read(4), 'big')
            if not location:
                CACHE[cx, cz] = {}
            else:
                f.seek((location >> 8) * 4096)
                size = int.from_bytes(f.read(4), 'big')
                assert f.read(1) == b'\x02'
                root = nbtlib.File.parse(io.BytesIO(zlib.decompress(f.read(size-1))))
                CACHE[cx, cz] = {int(s['Y']): s.get('block_states') for s in root['sections']}
    section = CACHE[cx, cz].get(y // 16)
    if section is None:
        return 'UNAVAILABLE'
    palette = section['palette']
    if len(palette) == 1:
        index = 0
    else:
        bits = max(4, (len(palette)-1).bit_length())
        per_long = 64 // bits
        position = ((y % 16) * 16 + z % 16) * 16 + x % 16
        value = int(section['data'][position // per_long]) & ((1 << 64)-1)
        index = (value >> ((position % per_long) * bits)) & ((1 << bits)-1)
    return str(palette[index]['Name'])

def cells(spans):
    return {(x,s['z']) for s in spans for x in range(s['minX'],s['maxX']+1)}

def main():
    plan = json.loads((STEPS/'land_use/city_land_use_surface_print_plan.json').read_text('utf-8'))
    foundation = plan['areas'][0]
    area = cells(foundation['memberSpans'])
    excluded = cells(foundation['exclusionSpans'])
    holes = []
    counts = collections.Counter()
    for x,z in sorted(area):
        name = block(x,84,z)
        counts[name] += 1
        if name in ('minecraft:air','minecraft:cave_air','minecraft:water'):
            floor = next((y for y in range(83,39,-1) if block(x,y,z) not in
                          ('minecraft:air','minecraft:cave_air','minecraft:water')), None)
            holes.append({'x':x,'z':z,'at84':name,'floor':floor,'excluded':(x,z) in excluded})
    remaining = {(r['x'],r['z']):r for r in holes}
    components = []
    while remaining:
        seed = next(iter(remaining))
        todo = [seed]
        group = []
        while todo:
            point = todo.pop()
            row = remaining.pop(point,None)
            if row is None: continue
            group.append(row)
            x,z = point
            todo.extend([(x-1,z),(x+1,z),(x,z-1),(x,z+1)])
        components.append({'count':len(group),'bounds':[min(r['x'] for r in group),min(r['z'] for r in group),max(r['x'] for r in group),max(r['z'] for r in group)],'excludedCount':sum(r['excluded'] for r in group),'floors':dict(collections.Counter(r['floor'] for r in group))})
    result = {'foundationCells':len(area),'excludedWithinFoundation':len(area & excluded),'blocksAt84':dict(counts),'holeCells':len(holes),'holeComponents':sorted(components,key=lambda c:-c['count'])}
    pathlib.Path(__file__).with_name('saved_surface_evidence.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps({**result,'holeComponents':result['holeComponents'][:12]},ensure_ascii=False))

if __name__ == '__main__': main()
