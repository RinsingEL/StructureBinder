from pathlib import Path
from collections import Counter
import json, sys, nbtlib
sys.stdout.reconfigure(encoding='utf-8')
repo=Path(r'E:\Mod_Dev\StructureBinder')
world=repo/'run/campus_fixes_playtest_20261017/saves/Campus_Fixes_PreD4_Round5'
steps=world/'realm_debug/provider_3d25631f_r19200/city_test_runs/city_jingchao_capital/steps'
def read(p): return json.loads(p.read_text(encoding='utf-8'))
soc=nbtlib.load(world/'data/realmfolk_society.dat')
data=json.loads(bytes(int(x)&255 for x in soc['data']['society_utf8']).decode('utf-8'))
print('society keys',list(data))
citizens=list(data['citizens'].values())
visitors=[c for c in citizens if c.get('visitor') and not c.get('dead')]
print('population',len(citizens),'living residents',sum(not c.get('visitor') and not c.get('dead') for c in citizens),'living visitors',len(visitors))
visitor_rows=[]
for c in visitors:
 row={k:c.get(k) for k in ['id','name','town','property','home','bedSite','visitor','workSite','dead']}
 prop=data['properties'].get(c.get('property'),{})
 row['propertyTerms']=prop.get('terms');row['claimedBedExists']=bool(c.get('bedSite'))
 visitor_rows.append(row)
 print('visitor',json.dumps(row,ensure_ascii=False))
terrain=read(steps/'land_use/land_use_terrain_field.json');step=terrain['cellStepBlocks']
terrain_cells={(c['blockMinX']//step,c['blockMinZ']//step):c for c in terrain['cells']}
surface=read(steps/'land_use/city_land_use_surface_print_plan.json')
anchor=read(steps/'d4/structure_anchor_plan.json')
ground={(f['x'],f['z']):f for f in surface['featureCells'] if f['surfaceOffset']==0 and f.get('targetSurfaceY') is not None}
platforms={}
for a in surface['areas']:
 for s in a['recipe'].get('platformSpans',[]):
  for x in range(s['minX'],s['maxX']+1): platforms[x,s['z']]=s['targetY']
space_rows=[]
for s in anchor['publicSpaces']:
 points={p:f for p,f in ground.items() if s['minX']<=p[0]<=s['maxX'] and s['minZ']<=p[1]<=s['maxZ']}
 green={p:f for p,f in points.items() if f['kind'] in ['GREEN_GROUND','GREEN_PATH']}
 planned=Counter(f['targetSurfaceY'] for f in green.values())
 coarse=Counter(terrain_cells.get((x//step,z//step),{}).get('elevation') for x,z in green)
 shared=Counter(platforms[p] for p in green if p in platforms)
 row={'owner':s['ownerGroupId'],'bounds':s,'greenColumns':len(green),'plannedGreenHeights':dict(planned),'coarseTerrainHeights':dict(coarse),'platformHeights':dict(shared)}
 space_rows.append(row);print('space',json.dumps(row,ensure_ascii=False))
samples=[]
for x,z in [(-2959,5273),(-2986,5270),(-3006,5270),(-3006,5269),(-3007,5270),(-2729,5356)]:
 cell=terrain_cells.get((x//step,z//step),{})
 row={'x':x,'z':z,'feature':ground.get((x,z)),'platform':platforms.get((x,z)),'coarseTerrain':{k:cell.get(k) for k in ['elevation','water','blockMinX','blockMinZ']}}
 samples.append(row);print('sample',json.dumps(row,ensure_ascii=False))
result={'world':str(world),'citizens':len(citizens),'visitors':visitor_rows,'spaces':space_rows,'samples':samples}
(Path(__file__).parent/'save-evidence.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
