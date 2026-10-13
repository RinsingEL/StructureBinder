import urllib.request,json
from pathlib import Path
base='http://127.0.0.1:8767';meta=json.load(urllib.request.urlopen(base+'/api/campus'))['metadata']
scene=json.loads(Path('tools/city_experiments/campus-groups.json').read_text(encoding='utf-8'))
req=urllib.request.Request(base+'/api/campus/build',data=json.dumps(dict(scene=scene,baseRevision=meta['revision'])).encode(),headers={'Content-Type':'application/json','Origin':base,'X-Campus-Write':'build'})
try:
 out=json.load(urllib.request.urlopen(req,timeout=180));print(out['revision'],out['summary']);print(out['evidenceDirectory'])
except urllib.error.HTTPError as e:print(e.read().decode());raise
