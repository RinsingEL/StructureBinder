"""Verify all actual building entrances through narrow crops of frozen road routes.
Only one end is a walking seed. Crops omit other floor columns, adding no support.
"""
import sys,json
from pathlib import Path
from collections import deque
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from studio.navigation import audit

def check(folder):
 meta=json.loads((folder/'metadata.json').read_text(encoding='utf-8'));world=json.loads((folder/'world.json').read_text(encoding='utf-8'));result=json.loads((folder/'java-result.json').read_text(encoding='utf-8'))
 registry=json.loads((Path(__file__).resolve().parents[1]/'.cache/registry.json').read_text(encoding='utf-8'))
 road={(f['x'],f['z']) for f in result['surfacePrintPlan']['featureCells'] if f['surfaceOffset']==0 and f['kind'] in ('ROAD_SLAB','ROAD_STAIR','BRIDGE_DECK')}
 members=meta['members'];byid={m['memberId']:m for m in members};out=[]
 voxel={}
 for b in world['blocks']:
  x,y,z=[b['pos'][i]+world['origin'][i] for i in range(3)];voxel.setdefault((x,z),[]).append((x,y,z,b['state']))
 def pos(m):
  e=m['roadEntrances'][0]['worldPosition'];return(e['x'],e['z'])
 def nearest(p):return min(road,key=lambda v:abs(p[0]-v[0])+abs(p[1]-v[1]))
 # Each array proves its own entries; array representatives connect through the public network.
 arrays={}
 for m in members:arrays.setdefault(m['arrayId'],[]).append(m)
 pairs=[];root=byid['services:1']
 for local in arrays.values():
  core=local[0]
  if core!=root:pairs.append((root,core))
  pairs.extend((core,m) for m in local[1:])
 for a,b in pairs:
  pa,pb=pos(a),pos(b);ra,rb=nearest(pa),nearest(pb);queue=deque([ra]);prev={ra:None}
  while queue and rb not in prev:
   x,z=queue.popleft()
   for p in ((x+1,z),(x-1,z),(x,z+1),(x,z-1)):
    if p in road and p not in prev:prev[p]=(x,z);queue.append(p)
  if rb not in prev:raise ValueError('Frozen road disconnected: '+b['memberId'])
  route=[];p=rb
  while p is not None:route.append(p);p=prev[p]
  for p,r in ((pa,ra),(pb,rb)):
   x,z=p;route.append((x,z))
   while x!=r[0]:x+=1 if r[0]>x else -1;route.append((x,z))
   while z!=r[1]:z+=1 if r[1]>z else -1;route.append((x,z))
  mask={(x+dx,z+dz) for x,z in route for dx in range(-2,3) for dz in range(-2,3)}
  lo=[min(x for x,z in mask),min(a['surfaceY'],b['surfaceY'])-8,min(z for x,z in mask)];hi=[max(x for x,z in mask),max(a['surfaceY'],b['surfaceY'])+8,max(z for x,z in mask)]
  blocks=[]
  for p in mask:
   for x,y,z,state in voxel.get(p,[]):
    if lo[1]<=y<=hi[1]:blocks.append(dict(pos=[x-lo[0],y-lo[1],z-lo[2]],state=state))
  data=dict(size=[hi[i]-lo[i]+1 for i in range(3)],palette=world['palette'],blocks=blocks)
  points=[dict(id=m['memberId'],kind='entrance' if i==0 else 'circulation',pos=[p[0]-lo[0],m['surfaceY']+1-lo[1],p[1]-lo[2]]) for i,(m,p) in enumerate(((a,pa),(b,pb)))]
  report=audit(data,dict(points=points),registry);row=dict(source=a['memberId'],target=b['memberId'],routeColumns=len(route),audit=report);out.append(row)
  print(json.dumps({k:v for k,v in row.items() if k!='audit'}|dict(passed=report['passed'],nodes=report.get('nodes'),unreachable=report.get('unreachable')),ensure_ascii=False),flush=True)
 access=meta.get('externalAccessPlan')
 if access:
  import math
  mask={(c['x'],c['z']) for c in access['columns']};axis=access['axis'];start,end=axis[0],axis[-1]
  # The new path must start on the already checked public road network.
  assert (start[0],start[1]) in road
  lo=[min(x for x,z in mask),math.floor(min(p[2] for p in axis))-3,min(z for x,z in mask)]
  hi=[max(x for x,z in mask),math.ceil(max(p[2] for p in axis))+4,max(z for x,z in mask)]
  blocks=[dict(pos=[x-lo[0],y-lo[1],z-lo[2]],state=s) for p in mask for x,y,z,s in voxel.get(p,[]) if lo[1]<=y<=hi[1]]
  from studio.navigation import collision_boxes
  entry_surface=max(y+box[4] for x,y,z,s in voxel[(start[0],start[1])] for box in collision_boxes(world['palette'][s],registry) if abs(y+box[4]-start[2])<=.6)
  points=[dict(id=ident,kind='entrance' if i==0 else 'circulation',pos=[p[0]-lo[0],surface-lo[1],p[1]-lo[2]]) for i,(ident,p,surface) in enumerate((('city-exit',start,entry_surface),('harbor-landing',end,end[2])))]
  report=audit(dict(size=[hi[i]-lo[i]+1 for i in range(3)],palette=world['palette'],blocks=blocks),dict(points=points),registry)
  out.append(dict(source='city-exit',target='harbor-landing',routeColumns=len(mask),audit=report))
  print(json.dumps(dict(target='harbor-landing',passed=report['passed'],unreachable=report.get('unreachable'))),flush=True)
 (folder/'campus-walk-check.json').write_text(json.dumps(dict(revision=meta['revision'],passed=all(r['audit']['passed'] for r in out),routes=out),ensure_ascii=False,indent=2),encoding='utf-8')
 return all(r['audit']['passed'] for r in out)
if __name__=='__main__':sys.exit(0 if check(Path(sys.argv[1])) else 1)
