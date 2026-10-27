"""Build the offline atlas from checked-in public presentation data."""
from pathlib import Path
import base64, json
HERE = Path(__file__).resolve().parent
read = lambda name: json.loads((HERE/name).read_text(encoding='utf-8'))
payload = read('data/atlas-base.json')
payload['academy'] = read('data/academy-animation.json')
payload['revision'] = read('data/atlas-revision.json')
payload['images'] = {n:'data:image/webp;base64,' + base64.b64encode((HERE/'assets'/f'atlas-{n}.webp').read_bytes()).decode() for n in ['design','arrival']}
assert len(payload['realms']) == 7
assert len(payload['revision']['districts']) == 16
assert len(payload['academy']['snapshots']['74']['anchors']) == 86
html = (HERE/'atlas-template.html').read_text(encoding='utf-8').replace('__ATLAS_DATA__',json.dumps(payload,ensure_ascii=False,separators=(',',':')).replace('</','<\\/'))
html = html.replace('__REVISION_JS__',(HERE/'atlas-revision.js').read_text(encoding='utf-8'))
(HERE/'wtd-atlas.html').write_text(html,encoding='utf-8')
print(f'wtd-atlas.html: {len(html.encode()):,} bytes')
