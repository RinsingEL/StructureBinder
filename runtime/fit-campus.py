import json,subprocess
from pathlib import Path
root=Path.cwd();folder=sorted((root/'runtime/campus-groups-check/revisions').iterdir())[-1]
cp=(root/'build/city-workbench-classpath.txt').read_text()
subprocess.run(['C:/Program Files/Java/jdk-17/bin/java.exe','-cp',cp,'com.rinsing.geomantia.systems.city.application.CityArrayExperiment',str(folder/'request.json'),str(folder/'arrays.json')],check=True)
r=json.loads((folder/'arrays.json').read_text(encoding='utf-8'));print('ok',r.get('ok'),r.get('error'))
for m in r.get('members',[]):print(m['memberId'],m['originBlock'],m['width'],m['depth'],m['issues'],m['terrainEvidence']['waterAreaBlocks'])
field=json.loads(Path(r['scene']['terrainPath']).read_text(encoding='utf-8'));step=field['cellStepBlocks'];cells={(c['cellX'],c['cellZ']):c for c in field['cells']}
# Translate the complete hierarchy, never individual buildings. Actual all-envelope dry-land fit.
candidates=[]
for dx in range(-192,193,8):
 for dz in range(-256,193,8):
  good=True
  for m in r['members']:
   x=m['originBlock']['x']+dx;z=m['originBlock']['z']+dz
   for xx in range(x//step,(x+m['width']-1)//step+1):
    for zz in range(z//step,(z+m['depth']-1)//step+1):
     c=cells.get((xx,zz))
     if c is None or c['water']:good=False;break
    if not good:break
   if not good:break
  if good:candidates.append((dx*dx+dz*dz,dx,dz))
print('fits',sorted(candidates)[:5])
if candidates:
 _,dx,dz=min(candidates);p=Path('tools/city_experiments/campus-groups.json');s=json.loads(p.read_text(encoding='utf-8'));s['compositions'][-1]['anchorBlock']['x']+=dx;s['compositions'][-1]['anchorBlock']['z']+=dz;p.write_text(json.dumps(s,ensure_ascii=False,indent=2),encoding='utf-8')

