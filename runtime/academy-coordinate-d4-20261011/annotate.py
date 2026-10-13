from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json
p=Path('runtime/academy-coordinate-d4-20261011');old=Path('runtime/academy-d4-refresh-20261011')
im=Image.new('RGB',(1280,620),'#f5f3eb');d=ImageDraw.Draw(im)
def txt(x,y,s,n=20): d.text((x,y),s,font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',n),fill='#243649')
txt(24,15,'学院 D4 · 从分区选 Patch 改为明确坐标',27)
txt(24,58,'同一地形、同一比例、同样 33 项建筑；以下仅比较平面布局。',17)
box=(245,150,855,610)
im.paste(Image.open(old/'18-services-layout.2.png').crop(box),(20,124))
im.paste(Image.open(p/'07-reception.2.png').crop(box),(650,124))
txt(24,94,'上一版：各区选 Patch 后连接',20);txt(654,94,'新版：明确坐标 + 嵌套阵列',20)
txt(24,590,'33 / 33 保留',16);txt(654,590,'33 / 33 保留 · 已保存 D4，未施工',16)
im.save(p/'coordinate-comparison.png')
r=json.loads(json.load(open(p/'07-reception.response.json',encoding='utf-8'))['content'][0]['text'])
bp=r['revisionEvidence']['previousBlueprint'];assert all(not g.get('preferredPatchRefs') for g in bp['groups']);assert sum('anchorBlock' in g for g in bp['groups'])==2;assert sum('anchorBlock' in g for g in bp['arrayCompositions'])==2
summary={'groups':len(bp['groups']),'rootCoordinateCount':4,'patchSelectedGroups':0,'topPatchCalls':0,'planned':33,'retained':33,'autoCompile':'not_queued','anchors':[{'id':g.get('groupId',g.get('compositionId')),'anchorBlock':g['anchorBlock']} for g in bp['groups']+bp['arrayCompositions'] if 'anchorBlock' in g]}
(p/'result-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8');print(summary)
