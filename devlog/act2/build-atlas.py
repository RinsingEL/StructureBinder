"""Build only wtd-atlas.html. No dependencies, network or writes to existing slides."""
from pathlib import Path
import base64, collections, hashlib, json
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SAVE = ROOT / 'run/saves/geometia_beta3_02/realm_debug/provider_3d25631f_r19200'
def read(path): return json.loads(path.read_text())
def embed(path): return 'data:image/webp;base64,' + base64.b64encode(path.read_bytes()).decode()
def packed(data): return base64.b64encode(bytes(data)).decode()
world = read(SAVE / 'world_feature_grid.json')
profiles = read(SAVE / 'realm_profiles.json')
seeds = read(SAVE / 'realm_seeds.json')
territory = read(SAVE / 'realm_territory_map.json')
replay = read(HERE / 'replay-evidence.json')['calls']
d3path = ROOT / 'run-data/devlog/academy-20261020/steps/d3/city_landform_review_package.json'
d3 = read(d3path)
cells = sorted(world['cells'], key=lambda c:(c['gridZ'], c['gridX']))
assert len(cells)==304*304
assert territory['expansionModel']=='action_budget'
biomes = sorted({max(c['biomeHist'],key=c['biomeHist'].get) for c in cells})
world_bytes=[]
for c in cells:
 h=round(c['heightP50'])+64
 world_bytes.extend([h%256,h//256,round(c['waterFrac']*255),biomes.index(max(c['biomeHist'],key=c['biomeHist'].get))])
ids=[p['realmId'] for p in profiles]
claims=[]
status_names=['owned','wild','contested','blocked','unreachable']
for c in territory['territoryCells']:
 claims.append([c['gridX'],c['gridZ'],ids.index(c['realmId']) if c['realmId'] else -1,c['claimCost'],status_names.index(c['status'])])
# Cost is retained at JSON precision. Equal-cost final cells appear together.
claims.sort(key=lambda c:(c[3],c[1],c[0]))
kinds=['water','shore','plain','slope','terrace']
local={}
for patch in d3['landformPatches']:
 for c in patch['memberCells']:
  local[(c['blockMinX'],c['blockMinZ'])]=kinds.index(patch['landformType'])
# Public tool anchors only: array anchors are not invented per-member positions.
shorts=['声明 16 个功能区','筛选学院素材','南宿院 · 申请四栋','保留四栋院落','自习宿院与小宿舍','东移 12 · 南移 6','改为生活巷','北侧补餐饮','保存设计']
logs=[]
for call,short in zip(replay,shorts):
 design=call['arguments'].get('districtDesign',{})
 anchors=[]
 for g in design.get('groups',[])+design.get('arrayCompositions',[]):
  if 'anchorBlock' in g: anchors.append({'id':g.get('groupId',g.get('compositionId')),**g['anchorBlock']})
 logs.append({'line':call['line'],'tool':call['tool'],'text':short,'ok':call['result']['ok'],'anchors':anchors})
city=read(SAVE/'city_seed_registry.json')['citySeeds'][0]
# Each fragment below occurs literally in its source profile.
notes=[('共同背景','季节供给、工艺传授与海路往来'),('哈维克','冬季共储、渔业和船具维护'),('瑟弥亚','港口工坊'),('埃斯伦','木作协作、越冬储粮和区域集市')]
for name,fragment in notes:
 candidates=profiles if name=='共同背景' else [p for p in profiles if p['name']==name]
 assert any(fragment in p['theme'] for p in candidates),fragment
payload={'world':packed(world_bytes),'biomes':biomes,'claims':claims,'statuses':dict(collections.Counter(c['status'] for c in territory['territoryCells'])),
 'realms':[{'id':p['realmId'],'name':p['name'],'fixed':p.get('territoryPolicy',{}).get('mode')=='fixed','x':next(s for s in seeds if s['realmId']==p['realmId'])['seedBlock']['x'],'z':next(s for s in seeds if s['realmId']==p['realmId'])['seedBlock']['z']} for p in profiles],
 'notes':notes,'local':[[x,z,k] for (x,z),k in sorted(local.items())], 'logs':logs,'city':city['anchorBlock'],
 'districts':[d['role'].replace('【公共】','').replace('【工作】','').replace('【居住】','') for d in replay[0]['arguments']['overview']['districts']],
 'academy':read(HERE/'data/academy-animation.json'),
 'images':{n:embed(HERE/'assets'/f'atlas-{n}.webp') for n in ['design','arrival']}}
template=(HERE/'atlas-template.html').read_text()
html=template.replace('__ATLAS_DATA__',json.dumps(payload,ensure_ascii=False,separators=(',',':')).replace('</','<\\/'))
(HERE/'wtd-atlas.html').write_text(html)
print(f'wtd-atlas.html: {len(html.encode()):,} bytes; world {len(cells)}; claims {len(claims)}; local {len(local)}')
