from pathlib import Path
import json
p=Path('runtime/academy-coordinate-d4-20261011')
rows=[]
for f in sorted(p.glob('*.response.json')):
 raw=json.loads(f.read_text(encoding='utf-8'));text=next((c['text'] for c in raw.get('content',[]) if c.get('type')=='text'),'')
 try:d=json.loads(text)
 except ValueError:d={'text':text}
 req=json.loads(f.with_name(f.name.replace('.response.json','.json')).read_text(encoding='utf-8'))
 review=d.get('revisionEvidence',{}).get('compiledDesignReview',{}); hand=d.get('generationHandoff',{})
 row={'call':f.name,'tool':req['tool'],'isError':raw.get('isError',False),'ok':d.get('ok'),'reasonCode':d.get('reasonCode'),'status':d.get('status'),'planned':review.get('plannedAndRetained',{}).get('plannedBuildingCount'),'retained':review.get('plannedAndRetained',{}).get('retainedBuildingCount'),'warnings':review.get('acceptance',{}).get('warnings',[]),'missing':hand.get('missingRequiredStructures',[]),'terrain':[{'groupId':v.get('groupId'),'structureRef':v.get('structureRef'),'adaptations':v.get('terrainEvidence',{}).get('terrainAdaptations',[])} for v in hand.get('terrainRequirements',[])]}
 rows.append(row)
(p/'mcp-audit.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
for r in rows: print(json.dumps(r,ensure_ascii=False))
log=Path('runtime/academy-d4-refresh-20261011/calls.jsonl')
matched=[json.loads(l) for l in log.read_text(encoding='utf-8').splitlines() if 'academy-coordinate-d4-20261011' in l]
print('LOG entries:',len(matched));print('tools:',[r['request']['tool'] for r in matched])
