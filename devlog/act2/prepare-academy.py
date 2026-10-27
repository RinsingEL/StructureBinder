"""Extract presentation geometry from imported saves; nbtlib is needed only here."""
from pathlib import Path
import json, hashlib
import nbtlib
HERE=Path(__file__).resolve().parent
ROOT=HERE.parent.parent
read=lambda p:json.loads(p.read_text())
evidence=read(HERE/'data/academy-20261020.json')
result={'snapshots':{},'cards':[]}
for s in evidence['snapshots']:
 content=read(ROOT/s['source'])['content']; layout=content['geometry']['compiledLayout']
 result['snapshots'][str(s['revision'])]={
  'anchors':s['anchors'],
  'roads':[{k:r[k] for k in ['start','end','widthBlocks','groupId','sourceGroupId','targetGroupId'] if k in r} for r in layout['streetBands']],
  'courts':layout['publicSpaces'],
  'gaps':layout['cityMainRoadPlan'].get('skippedConnections',[]),
  'groups':[{k:g[k] for k in ['groupId','anchorBlock','structureCount','layout'] if k in g} for g in content['blueprint']['groups']]}
final=read(ROOT/evidence['snapshots'][-1]['source'])['content']['blueprint']
roles=read(HERE/'replay-evidence.json')['calls'][0]['arguments']['overview']['districts']
result['districts']=[]
for d in final['districtDesigns']:
 role=next((r['role'] for r in roles if r['groupId']==d['districtId']),d['districtId'])
 anchors=[a for a in evidence['snapshots'][-1]['anchors'] if a['placementGroupId'] in d['groupIds']]
 if not anchors:continue
 result['districts'].append({'id':d['districtId'],'name':role.split('】')[-1],'x':sum((a['plannedFootprint']['minX']+a['plannedFootprint']['maxX'])/2 for a in anchors)/len(anchors),'z':sum((a['plannedFootprint']['minZ']+a['plannedFootprint']['maxZ'])/2 for a in anchors)/len(anchors)})
for name in ['MG-27-v02','MG-37-v03','MG-05-v02','MG-17-v02']:
 folder=ROOT/'asset_catalogs/original_civilizations/R02_arcane_reborn/models'/name
 author=read(folder/'author.json'); nbt=nbtlib.load(folder/'structure.nbt'); w,h,d=map(int,nbt['size'])
 heights={}
 for b in nbt['blocks']:
  block=str(nbt['palette'][int(b['state'])]['Name'])
  if block in ['minecraft:air','minecraft:cave_air','minecraft:void_air','minecraft:structure_void']:continue
  x,y,z=map(int,b['pos']);heights[x,z]=max(y,heights.get((x,z),-1))
 # Topmost occupied voxel, quantized to four ink densities; no invented silhouette.
 rows=[]
 for z in range(d):
  x=0
  while x<w:
   if (x,z) not in heights:x+=1;continue
   band=min(3,heights[x,z]*4//max(1,h));start=x;x+=1
   while x<w and (x,z) in heights and min(3,heights[x,z]*4//max(1,h))==band:x+=1
   rows.append([start,z,x-start,band])
 result['cards'].append({'id':name,'name':author['name'].split(' · ')[0],'beds':sum(p.get('kind')=='bed' for p in author['points']),'width':w,'depth':d,'rows':rows,'source':str(folder.relative_to(ROOT)),'nbtSha256':hashlib.sha256((folder/'structure.nbt').read_bytes()).hexdigest()})
(HERE/'data/academy-animation.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':')))
print('Prepared',len(result['districts']),'districts;',len(result['cards']),'voxel projections')
