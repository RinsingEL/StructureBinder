from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
import json
p=Path('runtime/academy-d4-refresh-20261011')
im=Image.open(p/'18-services-layout.2.png').convert('RGB'); d=ImageDraw.Draw(im)
def font(n): return ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',n)
def txt(x,y,s,n=18,fill='#233247'): d.text((x,y),s,font=font(n),fill=fill)
d.rectangle((0,0,1279,63),fill='#f5f3eb'); txt(22,10,'学院整城 · 补齐小建筑后的 D4 平面预览',26); txt(24,43,'真实开发存档 / 33 项全部保留 / 方框代表建筑占地，不是游戏实景',14)
d.rectangle((945,64,1279,899),fill='#f5f3eb')
for x,y,s,color in [(265,180,'接待行政 · 4','#207d67'),(474,141,'藏书阅读 · 3','#775194'),(683,235,'生活区 · 12','#28768b'),(397,381,'教学区 · 14','#336fa0')]:
 b=d.textbbox((x,y),s,font=font(19));d.rounded_rectangle((b[0]-7,b[1]-5,b[2]+7,b[3]+5),radius=5,fill='#fbfaf4');txt(x,y,s,19,color)
for y,s,n in [(88,'这次的小建筑有多少？',23),(134,'33  项建筑 / 庭园',25),(176,'26  项长边 ≤ 35 格',24),(220,'4 个功能区 · 7 个建筑阵列',17),(271,'教学区 14',21),(309,'主院落 4 + 普通课堂 6',17),(336,'实践课堂 / 温室 4',17),(384,'生活区 12',21),(422,'集体宿舍 1 + 小宿舍 8',17),(449,'食堂 / 浴室 / 文具店各 1',17),(497,'藏书阅读 3',21),(535,'图书馆 + 抄写院 + 阅读庭',17),(583,'接待行政 4',21),(621,'登记 / 行政 + 两栋小服务',17),(691,'验证范围',21),(732,'D4 已保存；未触发世界施工。',16),(762,'道路、台地与绿化实景仍需',16),(788,'后续生成阶段验证。',16)]:txt(968,y,s,n)
im.save(p/'academy-city-d4-annotated.png')
# Keep the complete final evidence compact and machine readable.
f=json.loads(json.load(open(p/'19-finalize.response.json',encoding='utf-8'))['content'][0]['text'])
r=json.loads(json.load(open(p/'18-services-layout.response.json',encoding='utf-8'))['content'][0]['text'])
(p/'result-summary.json').write_text(json.dumps(dict(status=f.get('status'),planned=33,retained=33,smallFootprints=26,smallFootprintDefinition='max(width,depth)<=35; includes reading garden',review=r['revisionEvidence']['compiledDesignReview'],handoff=f['generationHandoff']),ensure_ascii=False,indent=2),encoding='utf-8')
