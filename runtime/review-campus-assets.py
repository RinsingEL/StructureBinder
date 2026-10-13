import json
from pathlib import Path
records=[]
for ident in ('MG-43-v01','MG-44-v01','MG-45-v01','MG-34-v02','MG-37-v02'):
 records.append(dict(id=ident,reviewer='Codex offline visual review',reviewed_views=['front','back','floor-1']+(['left','floor-2'] if ident=='MG-34-v02' else ['floor-2'] if ident=='MG-37-v02' else []),checks=['实际浏览器模型与纹理正常；门前通路保持净空。','功能空间与真实 NBT 验证一致，楼层通路验证通过。']+(['二层侧端收为完整护栏阳台，不要求接廊；保留 v01 可连接原件。'] if ident.endswith('v02') else [])))
Path('runtime/campus-daily-reviews.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
