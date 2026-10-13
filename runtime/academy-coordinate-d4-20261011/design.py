import json,pathlib,sys
p=pathlib.Path('runtime/academy-coordinate-d4-20261011'); old=pathlib.Path('runtime/academy-d4-refresh-20261011')
which,prev,out=sys.argv[1:];d=json.loads(json.load(open(p/(prev+'.response.json'),encoding='utf-8'))['content'][0]['text'])
files={'teaching':'16-teaching-repair','library':'09-library','living':'18-services-layout','reception':'15-reception'}
r=json.load(open(old/(files[which]+'.json'),encoding='utf-8'));a=r['args']
for k in ['targetDistrictId','baseDraftHash','assessment']:a.pop(k,None)
a.update(contextId=d['d4Workflow']['contextId'],workflowRevision=d['d4Workflow']['revision'])
for g in a['districtDesign']['groups']:
 g.pop('preferredPatchRefs',None);g.pop('preferredPatchZone',None)
origins={'teaching':(-3016,5250),'library':(-3030,5164),'living':(-3010,5446),'reception':(-2854,5124)}
x,z=origins[which]
if a['districtDesign'].get('arrayCompositions'): a['districtDesign']['arrayCompositions'][0]['anchorBlock']={'x':x,'z':z}
else:a['districtDesign']['groups'][0]['anchorBlock']={'x':x,'z':z}
if which=='reception':
 g=a['districtDesign']['groups'][0];g['algorithmProfileRef']='algorithm:grid';g['layout']={'gapBlocks':8,'columns':2}
(p/(out+'.json')).write_text(json.dumps(r,ensure_ascii=False),encoding='utf-8')
