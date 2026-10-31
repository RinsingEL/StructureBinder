"""Build the offline atlas from checked-in public presentation data."""
from pathlib import Path
import base64, json
HERE = Path(__file__).resolve().parent
read = lambda name: json.loads((HERE/name).read_text(encoding='utf-8'))
payload = read('data/atlas-base.json')
payload['academy'] = read('data/academy-animation.json')
payload['revision'] = read('data/atlas-revision.json')
payload['round2'] = read('data/atlas-round2.json')
for material in payload['round2']['materials'].values():
    if material['image']: material['imageData']='data:image/webp;base64,'+base64.b64encode((HERE/material['image']).read_bytes()).decode()
payload['images'] = {n:'data:image/webp;base64,' + base64.b64encode((HERE/'assets'/f'atlas-{n}.webp').read_bytes()).decode() for n in ['design','arrival']}
photos = HERE.parents[1]/'dev_docs/systems/city/reports/20261031_学院功能区实景报告/evidence'
payload['photos'] = {n:'data:image/jpeg;base64,'+base64.b64encode((photos/f'{n}.jpg').read_bytes()).decode() for n in ['arrival','housing','starhall','library','observatory','botanical']}
assert len(payload['realms']) == 7
assert len(payload['revision']['districts']) == 16
assert len(payload['academy']['snapshots']['74']['anchors']) == 86
html = (HERE/'atlas-template.html').read_text(encoding='utf-8').replace('__ATLAS_DATA__',json.dumps(payload,ensure_ascii=False,separators=(',',':')).replace('</','<\\/'))
html = html.replace('__REVISION_JS__',(HERE/'atlas-revision.js').read_text(encoding='utf-8')+'\n'+(HERE/'atlas-round2.js').read_text(encoding='utf-8'))
(HERE/'wtd-atlas.html').write_text(html,encoding='utf-8')
print(f'wtd-atlas.html: {len(html.encode()):,} bytes')
