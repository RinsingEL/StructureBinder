import json, hashlib, re, sys
from pathlib import Path
from collections import Counter
import nbtlib
root=Path(sys.argv[1]); out=Path(__file__).parent
read=lambda n:json.loads((root/n).read_text(encoding='utf-8-sig'))
cat=read('template_catalog.json'); ref=read('blueprint_reference_catalog.json'); pack=read('city_template_content_pack.json')
issues=[]; rows=[]
def issue(code,target,detail):issues.append(dict(code=code,target=target,detail=detail))
def sha(p):return 'sha256:'+hashlib.sha256(p.read_bytes()).hexdigest()
if sha(root/'template_catalog.json')!=pack['catalogSha256']:issue('CATALOG_HASH','bundle','manifest hash mismatch')
entries={e['templateRef']:e for e in pack['templates']}; keys=set()
for t in cat['templates']:
 tid=t['templateId']; key=(tid,t.get('variant',t.get('variantId'))); ports=t.get('roadEntrances',[]); size=t['rawSize']; w,h,d=[size[k] for k in ['width','height','depth']]
 if key in keys:issue('DUPLICATE_TEMPLATE',tid,str(key))
 keys.add(key)
 for field,allowed in [('allowedRotations',{'NONE','CLOCKWISE_90','CLOCKWISE_180','COUNTERCLOCKWISE_90'}),('allowedMirrors',{'NONE','LEFT_RIGHT','FRONT_BACK'})]:
  if not t.get(field) or set(t[field])-allowed:issue('TRANSFORM_INVALID',tid,field)
 if min(w,h,d)<=0:issue('SIZE_INVALID',tid,str(size))
 ids=[p['entranceId'] for p in ports]
 if len(ids)!=len(set(ids)):issue('DUPLICATE_PORT',tid,str(ids))
 for p in ports:
  x,z=p.get('position',p)['x'],p.get('position',p)['z']; direction=p['direction']
  if not(0<=x<w and 0<=z<d) or direction not in {'WEST','EAST','NORTH','SOUTH'}:issue('PORT_INVALID',tid,str(p))
  elif not {'WEST':x==0,'EAST':x==w-1,'NORTH':z==0,'SOUTH':z==d-1}[direction]:issue('INTERIOR_PORT_REVIEW',tid,str(p))
 policy=t.get('frontagePolicy','FIXED_FRONT')
 if policy not in {'FIXED_FRONT','ANY_AUTHORED_ENTRANCE'}:issue('FRONTAGE_POLICY_INVALID',tid,policy)
 frontage='ready'
 if not ports:frontage='missing';issue('FRONTAGE_MISSING',tid,'No authored entrances')
 elif len(ports)>1 and policy!='ANY_AUTHORED_ENTRANCE' and sum(i.lower()=='front' for i in ids)!=1:
  frontage='ambiguous';issue('FRONTAGE_AMBIGUOUS',tid,json.dumps(ports,ensure_ascii=False))
 e=entries.get(t['templateRef']); nbt_size=None
 if e is None:issue('CONTENT_MAPPING_MISSING',tid,t['templateRef'])
 else:
  file=(root/'city_template_content_pack'/e['sourceFile']).resolve()
  if not file.is_relative_to((root/'city_template_content_pack').resolve()) or not file.is_file():issue('CONTENT_FILE_INVALID',tid,str(file))
  else:
   if sha(file)!=e['sourceSha256']:issue('CONTENT_HASH_MISMATCH',tid,str(file))
   try:
    n=nbtlib.load(file);nbt_size=list(map(int,n['size']))
    if nbt_size!=[w,h,d]:issue('NBT_SIZE_MISMATCH',tid,str(nbt_size))
    palettes=n.get('palettes',[n.get('palette',[])])
    for palette in palettes:
     for b in n['blocks']:
      pos=list(map(int,b['pos'])); state=int(b['state'])
      if not all(0<=v<s for v,s in zip(pos,nbt_size)) or not 0<=state<len(palette):issue('NBT_BLOCK_INVALID',tid,str(pos));break
   except Exception as exc:issue('NBT_READ_ERROR',tid,str(exc))
 rows.append(dict(templateId=tid,frontage=frontage,entrances=ports,policy=policy,nbtSize=nbt_size))
refs={r['structureRef']:r for r in ref['structureRefs']}; profiles=[json.loads(l) for l in (root/'StructureProfile.jsonl').read_text(encoding='utf-8-sig').splitlines() if l.strip()]; by_profile={p['structureId']:p for p in profiles}
for r in ref['structureRefs']:
 for c in r['templateCandidates']:
  if (c['templateId'],c['variantId']) not in keys:issue('TEMPLATE_REFERENCE_UNKNOWN',r['structureRef'],str(c))
 p=by_profile.get(r['structureRef'])
 if p is None or p.get('reviewState')!='approved' or not p.get('functionTerms') or not p.get('styleTerms'):issue('PROFILE_INVALID',r['structureRef'],str(p))
for pool in ref['fillPools']:
 for r in pool['structureRefs']:
  if r not in refs:issue('POOL_REFERENCE_UNKNOWN',pool['poolRef'],r)
for row in rows:
 row['structureRefs']=[r['structureRef'] for r in ref['structureRefs'] if any(c['templateId']==row['templateId'] for c in r['templateCandidates'])]
 row['fillPools']=[p['poolRef'] for p in ref['fillPools'] if any(r in p['structureRefs'] for r in row['structureRefs'])]
summary=dict(bundle=str(root),templates=len(rows),profiles=len(profiles),structureRefs=len(refs),fillPools=len(ref['fillPools']),issueCounts=dict(Counter(i['code'] for i in issues)),issues=issues,templatesDetail=rows)
(out/'检查结果.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in summary.items() if k!='templatesDetail'},ensure_ascii=False,indent=2))
