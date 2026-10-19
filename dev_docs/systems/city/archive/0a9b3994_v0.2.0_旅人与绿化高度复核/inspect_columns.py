from pathlib import Path
from collections import Counter
import json, zlib, gzip, io, math, nbtlib, sys
sys.stdout.reconfigure(encoding='utf-8')
repo=Path(r'E:\Mod_Dev\StructureBinder')
world=repo/'run/campus_fixes_playtest_20261017/saves/Campus_Fixes_PreD4_Round5'
steps=world/'realm_debug/provider_3d25631f_r19200/city_test_runs/city_jingchao_capital/steps'
surface=json.loads((steps/'land_use/city_land_use_surface_print_plan.json').read_text(encoding='utf-8'))
spaces=json.loads((steps/'d4/structure_anchor_plan.json').read_text(encoding='utf-8'))['publicSpaces']
ground={(f['x'],f['z']):f for f in surface['featureCells'] if f['surfaceOffset']==0 and f.get('targetSurfaceY') is not None and f['kind'] in ['GREEN_GROUND','GREEN_PATH']}
chunks={}
def chunk(cx,cz):
 if (cx,cz) in chunks:return chunks[cx,cz]
 path=world/'region'/f'r.{cx//32}.{cz//32}.mca'
 with path.open('rb') as f:
  f.seek(4*((cx%32)+(cz%32)*32));entry=f.read(4);sector=int.from_bytes(entry[:3],'big')
  if not sector:raise ValueError('chunk missing')
  f.seek(sector*4096);length=int.from_bytes(f.read(4),'big');kind=f.read(1)[0];raw=f.read(length-1)
 decoded=zlib.decompress(raw) if kind==2 else gzip.decompress(raw) if kind==1 else raw
 data=nbtlib.File.parse(io.BytesIO(decoded));sections={int(s['Y']):s for s in data['sections']};chunks[cx,cz]=sections;return sections
def block(x,y,z):
 s=chunk(x//16,z//16).get(y//16)
 if s is None or 'block_states' not in s:return 'minecraft:air'
 bs=s['block_states'];pal=bs['palette']
 if len(pal)==1:return str(pal[0]['Name'])
 bits=max(4,(len(pal)-1).bit_length());per=64//bits;idx=(y%16)*256+(z%16)*16+x%16
 value=int(bs['data'][idx//per])&((1<<64)-1);key=(value>>((idx%per)*bits))&((1<<bits)-1)
 return str(pal[key]['Name'])
def column(x,z):
 result=[]
 for y in range(55,82):
  name=block(x,y,z)
  if result and result[-1][2]==name:result[-1][1]=y
  else:result.append([y,y,name])
 return result
rows=[]
for s in spaces:
 def inside(x,z):return s['minX']<=x<=s['maxX'] and s['minZ']<=z<=s['maxZ']
 edges=[]
 for (x,z),f in ground.items():
  if not inside(x,z):continue
  for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)]:
   q=(x+dx,z+dz)
   if inside(*q) or q not in ground:continue
   other=ground[q];delta=f['targetSurfaceY']-other['targetSurfaceY']
   if abs(delta)>=2:edges.append({'inside':[x,z,f['targetSurfaceY']],'outside':[*q,other['targetSurfaceY']],'delta':delta})
 edges.sort(key=lambda e:(-abs(e['delta']),e['inside']))
 selected=edges[:2]
 for e in selected:
  e['insideBlocks']=column(*e['inside'][:2]);e['outsideBlocks']=column(*e['outside'][:2])
 row={'owner':s['ownerGroupId'],'abruptGreenEdges':len(edges),'deltaCounts':dict(Counter(e['delta'] for e in edges)),'examples':selected}
 rows.append(row);print(json.dumps(row,ensure_ascii=False))
sample={'coordinate':[-2959,5273],'blocks':column(-2959,5273)}
print(json.dumps(sample))
(Path(__file__).parent/'column-evidence.json').write_text(json.dumps({'courtyardEdges':rows,'flowerColumn':sample},ensure_ascii=False,indent=2),encoding='utf-8')
