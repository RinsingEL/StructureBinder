import json
from pathlib import Path
f=Path('runtime/campus-workbench/revisions/r0019');m=json.loads((f/'metadata.json').read_text(encoding='utf-8'));w=json.loads((f/'world.json').read_text(encoding='utf-8'));a=next(a for a in m['members'] if a['memberId']=='main-court:1');print(a['originBlock'],a['roadEntrances'],a['surfaceY'])
p=a['roadEntrances'][0]['worldPosition'];cells={tuple(b['pos'][i]+w['origin'][i] for i in range(3)):(w['palette'][b['state']],b['layer']) for b in w['blocks']}
for z in range(p['z']-2,p['z']+14):
 print(z,[(y,cells.get((p['x'],y,z))) for y in range(a['surfaceY']-2,a['surfaceY']+4) if (p['x'],y,z) in cells])
