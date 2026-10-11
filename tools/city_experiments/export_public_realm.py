"""Export verified authored voxels to the Java runtime; never synthesize substitute assets."""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/structure_studio'))
from studio.model import read_structure


def export():
    catalog = ROOT / 'asset_catalogs/original_civilizations/R02_arcane_reborn'
    pools = json.loads((catalog / 'greenery-pools.json').read_text(encoding='utf-8'))
    entries = {a['assetId']: a for a in pools['assets']}
    # The three validated compositions reference these landscape/infrastructure assets.
    examples = json.loads(Path(__file__).with_name('space-examples.json').read_text(encoding='utf-8'))
    ids = set(entries)
    def collect(value):
        if isinstance(value, dict):
            if 'assetId' in value: ids.add(value['assetId'])
            for v in value.values(): collect(v)
        elif isinstance(value, list):
            for v in value: collect(v)
    collect(examples)
    out = ROOT / 'src/main/resources/data/geomantia/city/public_realm'
    out.mkdir(parents=True, exist_ok=True)
    assets = []
    for asset_id in sorted(ids):
        folder = catalog / 'models' / asset_id.split(':')[1].upper().replace('-V', '-v')
        author_bytes = (folder / 'author.json').read_bytes()
        author = json.loads(author_bytes)
        nbt = (folder / 'structure.nbt').read_bytes()
        digest = hashlib.sha256(nbt).hexdigest()
        if author['nbt_sha256'] != digest: raise ValueError('stale author: ' + asset_id)
        model = read_structure(folder / 'structure.nbt')
        if model.get('entity_count', 0): raise ValueError('entities require a runtime adapter: ' + asset_id)
        entry = entries.get(asset_id)
        if entry and (entry['nbtSha256'] != digest or entry['authorSha256'] != hashlib.sha256(author_bytes).hexdigest()):
            raise ValueError('stale pool: ' + asset_id)
        if entry and (entry['sizeXYZ'] != model['size'] or entry['groundPlaneY'] != author['ground_plane']['y']):
            raise ValueError('stale geometry: ' + asset_id)
        size = model['size']
        blocks = []
        for b in model['blocks']:
            s = model['palette'][b['state']]
            if s['name'] in ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'): continue
            if b.get('nbt'): raise ValueError('block entities require a runtime adapter: ' + asset_id)
            props = dict(s.get('properties', {}))
            if s['name'].endswith('_leaves'): props['persistent'] = 'true'
            state = s['name'] + ('[' + ','.join(k+'='+str(v) for k,v in sorted(props.items())) + ']' if props else '')
            blocks.append([*b['pos'], state])
        assets.append(dict(id=asset_id, size=size, groundPlane=author['ground_plane']['y'],
                           kind=entry['kind'] if entry else 'fixture', nbtSha256=digest,
                           authorSha256=hashlib.sha256(author_bytes).hexdigest(), blocks=blocks))
    data = dict(schema='city_public_realm_assets.v1', assets=assets, pools=pools['pools'])
    texts = {'academy.json': json.dumps(data, ensure_ascii=False, separators=(',', ':'))+'\n',
             'examples.json': json.dumps(examples, ensure_ascii=False, indent=2)+'\n'}
    for name, text in texts.items():
        if '--check' in sys.argv:
            if (out / name).read_text(encoding='utf-8') != text: raise ValueError('runtime export stale: '+name)
        else: (out / name).write_text(text, encoding='utf-8')
    print(f'{"Verified" if "--check" in sys.argv else "Exported"} {len(assets)} assets, {sum(len(a["blocks"]) for a in assets)} authored voxels')


if __name__ == '__main__': export()
