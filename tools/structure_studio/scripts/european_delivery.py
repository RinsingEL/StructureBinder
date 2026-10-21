"""Generate a local delivery index only after actual per-model review publication."""
import sys,json,html
from pathlib import Path
from collections import Counter
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from studio.european_village import get_builders
from studio.server import CATALOG
from studio.model import sha256,write_json

root=CATALOG/'R04_european_reborn'
rows=[]
for key in sorted(get_builders()):
    d=root/'models'/key
    a=json.loads((d/'author.json').read_text(encoding='utf-8'))
    review=json.loads((d/'review.json').read_text(encoding='utf-8'))
    validation=json.loads((d/'validation.json').read_text(encoding='utf-8'))
    capture=json.loads((d/'previews/capture.json').read_text(encoding='utf-8'))
    living=json.loads((d/'living_facilities.json').read_text(encoding='utf-8'))
    assert review['status']=='accepted'
    for record in (review,capture):
        assert record['nbt_sha256']==sha256(d/'structure.nbt')
        assert record['author_sha256']==sha256(d/'author.json')
    assert validation['passed'] and validation['navigation']['passed'] and not validation['warnings']
    beds=Counter(p.get('use','resident') for p in a['points'] if p['kind']=='bed')
    n=int(key.split('-')[1]);group='旧模型修订' if n<27 else '日常与服务' if n<=45 else '地域配套'
    rows.append(dict(id=key,name=a['name'],group=group,size_xyz=a['size'],beds=dict(beds),
        workplaces=living['capacity']['workplaces'],residentBeds=living['capacity']['residentBeds'],
        shots=len(capture['shots']),nbt_sha256=sha256(d/'structure.nbt'),author_sha256=sha256(d/'author.json')))
write_json(root/'紧凑建筑验收摘要.json',dict(date='2026-10-21',scope='offline_asset_review',
    models=len(rows),new_versions=61,revised_versions=7,shots=sum(r['shots'] for r in rows),
    runtime_installed=False,worldgen_verified=False,rows=rows))
lines=['# 欧洲紧凑建筑 · 本轮交付','',
    '2026-10-21。本轮 68 个版本：EU-27～45 的 19 个新增类型及变体、EU-46～67 的 22 个地域配套类型，共 61 个新建版本；另有 7 个既有类型的修订版。旧版保留，欧洲目录现共 94 个版本、67 个独立编号。','',
    '全部完成最终 NBT 数据、离线通路检查与外观／分层图审，逐项 review.json 绑定当前 NBT 和作者文件双哈希。图审是本轮作者判断，不代表用户已认可审美。','',
    '未安装到游戏实例，未验证实际世界生成、NPC 路径、动物通过、流体、机器或夜间照明。坡地小屋目前只声明两格坡差；栈桥声明固定岸高与水位基准，不代表适配所有地形。','',
    '[交互图册](紧凑建筑图册.html) · [逐项验收摘要](紧凑建筑验收摘要.json) · [制作清单](补充建模清单.md)','',
    '![六栋同尺度对照](紧凑建筑同尺度预览.jpg)','',
    '上图是手工摆放的离线尺度对照场景，不是城市生成成果。','',
    '| 编号／外观 | 建筑 | 宽×深×高 | 居民床 | 客床 | 员工床 | 病床 | 工位 |','| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |']
for r in rows:
    w,h,d=r['size_xyz'];b=r['beds']
    lines.append(f"| [{r['id']}](models/{r['id']}/previews/front.png) | {r['name']} | {w}×{d}×{h} | {b.get('resident',0)} | {b.get('public',0)} | {b.get('staff',0)} | {b.get('care',0)} | {r['workplaces']} |")
lines+=['','## 复核与重建','',
    '- Studio 中可逐项查看实际模型、切片、房间与使用点；每个模型保留作者标记、生活设施提取、数据检查、截图和图审记录。',
    '- 在 `tools/structure_studio` 运行 `python -m studio.european_village` 重建本批全部；亦已接入原 `python -m studio.build <ID>` 入口。重建后若哈希变化，旧图审自动失效，须重新截图查看。',
    '- 11 项聚焦测试通过，覆盖本批范围、真实床位与标记、旧版保护、L 形空地、既有楼层通路与分类。',
    '- 小屋现有端门、侧门、短翼及两格坡差版本；家宅有四床、六床与 L 形；街屋有中户、端户、转角；配套包含独立棚、围栏、栈桥与渠道。',
    '- 尺寸超过原建议的条目保留实际尺寸：L 形家宅为 15×15，摊棚含檐口为 7×7；参考范围不是缩通路或截屋檐的理由。',
    '- 默认作者生命周期仍为 draft；离线接受状态由 review.json 表达，不自动晋升为运行时已导入。','']
(root/'紧凑建筑交付.md').write_text('\n'.join(lines),encoding='utf-8')
cards=[]
for r in rows:
    key=r['id'];w,h,d=r['size_xyz'];search=html.escape(key+' '+r['name']+' '+r['group'],quote=True)
    cards.append(f'<article data-search="{search}" data-group="{r["group"]}"><a href="models/{key}/previews/front.png"><img loading="lazy" src="models/{key}/previews/front.png" alt="{html.escape(r["name"])}"></a><h2>{html.escape(r["name"])}</h2><p>{key} · {w}×{d}×{h} · {r["group"]}</p><nav><a href="models/{key}/previews/roof-off.png">去屋顶</a> <a href="models/{key}/previews/floor-1.png">首层</a> <a href="models/{key}/previews/back.png">背面</a></nav></article>')
page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>欧洲紧凑建筑图册</title><style>body{margin:0;background:#ece8df;color:#272c2b;font:16px/1.6 system-ui}header{padding:32px 5vw;background:#24332f;color:#faf7ed}h1{margin:0}main{padding:24px 5vw}input,select{padding:10px;border:1px solid #a8b0a8;border-radius:5px;font:inherit;margin:5px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:20px}article{background:white;border:1px solid #d1d5cc;border-radius:8px;overflow:hidden}article img{width:100%;display:block}h2{font-size:18px;margin:12px 16px 2px}article p,nav{margin:8px 16px}a{color:#17684e}[hidden]{display:none}small{color:#e1e7dd}</style><header><h1>欧洲紧凑建筑 · 68 个版本</h1><p>小屋、街屋、作坊与地域配套。尺寸为宽×深×高，单位为方块。</p><small>离线模型图册；尚未安装或进行游戏生成验收。</small></header><main><input id="q" placeholder="搜索名称或编号"><select id="group"><option value="">全部</option><option>日常与服务</option><option>地域配套</option><option>旧模型修订</option></select><p id="count"></p><div class="grid">'''+''.join(cards)+'''</div></main><script>function filter(){let n=0;for(const a of document.querySelectorAll('article')){a.hidden=!(a.dataset.search.toLowerCase().includes(q.value.toLowerCase())&&(!group.value||a.dataset.group===group.value));if(!a.hidden)n++}count.textContent=`显示 ${n} 个版本`}q.oninput=group.onchange=filter;filter()</script></html>'''
(root/'紧凑建筑图册.html').write_text(page,encoding='utf-8')
print('Delivery index:',len(rows),'models;',sum(r['shots'] for r in rows),'captured views')
