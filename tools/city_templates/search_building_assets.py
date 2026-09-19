"""Search building names across the construction pack; keep discovery separate from approval."""
import argparse
import ast
import hashlib
import html
import json
import re
import shutil
from collections import Counter
from pathlib import Path
import nbtlib
import numpy as np
from PIL import Image, ImageDraw
from index_construction_assets import classify

GROUPS = {'中式': ['东方','中式','唐风'], '日式': ['日式','日本','和风'],
          '东南亚': [], '沙漠': ['沙漠','埃及','阿拉伯','波斯']}
PALACE = '城市,房屋/寺庙教堂等 构建包/aa1 东方风格宫殿.litematic'
EXCLUDE = ['全部','雕像','石像','齿轮','铁轨','帆船','木船','船只','池塘','路灯','水车','干草棚屋','红色大门']


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['source','output','active-source','preview-helper']:
        p.add_argument('--'+key,type=Path,required=True)
    p.add_argument('--reuse-previews',type=Path)
    a=p.parse_args()
    assert not a.output.exists()
    (a.output/'previews').mkdir(parents=True)
    old=json.loads(a.active_source.read_text(encoding='utf-8-sig'))['templates']
    known=set()
    for r in old:
        path=Path(r['sourceOriginal'])
        if not path.is_absolute():path=a.active_source.parent/path
        if path.is_file():known.add(hashlib.sha256(path.read_bytes()).hexdigest())
    tree=ast.parse(a.preview_helper.read_text(encoding='utf8'))
    functions=ast.Module(body=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in {'color','render','unpack'}],type_ignores=[])
    ns=dict(np=np,Image=Image,ImageDraw=ImageDraw)
    exec(compile(functions,str(a.preview_helper),'exec'),ns)
    index,focus,excluded=[],[],[]
    files=sorted(a.source.rglob('*.litematic'))
    for seq,source in enumerate(files,1):
        relative=source.relative_to(a.source)
        if relative.parts[0] in ['雕像','载具武器类','树木,绿植']:continue
        # Include explicitly named buildings even outside the building folder.
        if relative.parts[0]!='城市,房屋' and not re.search('民居|住宅|宅邸|城堡|寺庙|神庙|市政厅|图书馆|铁匠铺|酒馆|旅店|客栈|仓库|兵营',source.stem):continue
        hit_groups=[g for g,words in GROUPS.items() if any(w in relative.as_posix() for w in words)]
        base=classify(relative)
        row=dict(id=f'B{seq:04}',name=source.stem,source=str(source),relativePath=relative.as_posix(),
            styleTerms=base['styles'],functionTerms=base['functions'],sourceSeries=base['sourceSeries'],
            nameEvidence=[w for words in GROUPS.values() for w in words if w in source.stem],
            pathEvidence=[w for words in GROUPS.values() for w in words if w in '/'.join(relative.parts[:-1])],
            runtimeReady=False)
        if relative.as_posix()==PALACE:hit_groups=['东南亚']
        elif '日式' in hit_groups:hit_groups=['日式']
        if hit_groups:row['styleTerms']=hit_groups.copy()
        index.append(row)
        if any(w in relative.as_posix() for w in ['北欧','维京','斯堪']):
            excluded.append(dict(id=row['id'],path=relative.as_posix(),reason='用户决定不选北欧建筑'))
            continue
        if not hit_groups:continue
        if any(w in source.stem for w in EXCLUDE) or '木船' in relative.parts or '池塘' in relative.parts:
            excluded.append(dict(id=row['id'],path=relative.as_posix(),reason='非本轮建筑主体或合集'))
            continue
        data=nbtlib.load(source)
        regions=data['Regions']
        sha=hashlib.sha256(source.read_bytes()).hexdigest()
        version=int(data['MinecraftDataVersion'])
        already=sha in known
        sizes=[[abs(int(r['Size'][k])) for k in ['x','y','z']] for r in regions.values()]
        status='已收录' if already else '新版待适配' if version>3465 else '多区域待拆分' if len(regions)!=1 else '可进入选材审核'
        preview=None
        if len(regions)==1 and np.prod(sizes[0])<=750000:
            region=next(iter(regions.values()))
            preview=f'previews/{row["id"]}.png'
            cached=a.reuse_previews/preview if a.reuse_previews else None
            if cached and cached.is_file():shutil.copy2(cached,a.output/preview)
            else:ns['render'](ns['unpack'](region),region['BlockStatePalette'],a.output/preview)
        focus.append(dict(row,groups=hit_groups,dataVersion=version,regionCount=len(regions),sizeXYZ=sizes,
            sourceSha256=sha,alreadyConfigured=already,status=status,preview=preview))
    for name,rows in [('building_index.jsonl',index),('candidates.jsonl',focus),('excluded.jsonl',excluded)]:
        (a.output/name).write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows),encoding='utf8')
    summary=dict(scannedFiles=len(files),buildingIndex=len(index),focusCandidates=len(focus),
        statuses=dict(Counter(r['status'] for r in focus)),groups={g:dict(Counter(r['status'] for r in focus if g in r['groups'])) for g in GROUPS},
        outsideNamedStyleFolders=sum(bool(r['nameEvidence']) and not r['pathEvidence'] for r in focus),
        previews=sum(bool(r['preview']) for r in focus),installed=False)
    (a.output/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    lines=['# 建筑跨目录检索','',f'扫描 {len(files)} 个原投影，建筑检索索引 {len(index)} 项；中式/日式/东南亚/沙漠候选 {len(focus)} 项。来源与匹配依据逐件保留。','',
        '按用户选材反馈：东方建筑构建及东方类归中式；日式独立；aa1 东方风格宫殿单列东南亚；北欧不再选用；沙漠保留。名称和上级路径都参与检索。','',
        '| 检索组 | 可进入选材审核 | 新版待适配 | 已收录 |','| --- | ---: | ---: | ---: |']
    for g,counts in summary['groups'].items():lines.append(f'| {g} | {counts.get("可进入选材审核",0)} | {counts.get("新版待适配",0)} | {counts.get("已收录",0)} |')
    lines+=['','## 优先核对的功能建筑','','| 名称 | 来源 | 状态 |','| --- | --- | --- |']
    for r in focus:
        if any(k in r['name'] for k in ['商人','铁匠','图书','戏院','酒馆','市政','领主','锯木','兵营','中式']):
            lines.append(f'| {r["name"]} | {r["relativePath"]} | {r["status"]} |')
    lines+=['','名称只是召回依据，不会把无功能标注的“小屋”擅自变成仓库或工坊。“可进入选材审核”仅表示源版本不高于1.20.1、单区域且未与现有素材源hash重复，不代表运行时兼容或功能完整。新版素材保留展示，不直接改版本号导入。','',
        '原文件、已收录298件和城墙独立配置均未改变。完整建筑名字索引见 building_index.jsonl；聚焦候选的版本、尺寸、路径和预览见 candidates.jsonl。','']
    (a.output/'README.md').write_text('\n'.join(lines),encoding='utf8')
    doc='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>建筑跨目录检索</title><style>body{font:16px system-ui;background:#edf0e7;color:#293e34;margin:24px}input,select{padding:10px;margin:5px}main{display:grid;grid-template-columns:repeat(auto-fill,minmax(290px,1fr));gap:14px}article{background:#fffdf7;padding:12px;border-radius:9px}article[hidden]{display:none}img{width:100%;height:265px;object-fit:contain}small{display:block;overflow-wrap:anywhere;color:#59695d}p{line-height:1.6}</style><h1>建筑跨目录检索</h1><p>按名称与路径召回，再区分版本和功能。预览为简化方块；不会自动导入未审核建筑。</p><input id="q" placeholder="搜名称、功能、原目录…"><select id="group"><option value="">全部检索组</option><option>中式</option><option>日式</option><option>东南亚</option><option>沙漠</option></select><select id="status"><option value="">全部状态</option><option selected>可进入选材审核</option><option>新版待适配</option><option>已收录</option><option>多区域待拆分</option></select><span id="count"></span><p><a href="README.md">功能核对说明</a> · <a href="building_index.jsonl">全建筑名称索引</a></p><main>'''
    for r in focus:
        title=html.escape(r['name']);preview=f'<img loading="lazy" src="{r["preview"]}" alt="{title}">' if r['preview'] else '<p>大体量或多区域，待专门预览</p>'
        search=html.escape(' '.join([r['name'],r['relativePath']]+r['functionTerms']),quote=True)
        doc+=f'<article data-group="{" ".join(r["groups"])}" data-status="{r["status"]}" data-search="{search}">{preview}<h3>{r["id"]} {title}</h3><p>{" / ".join(r["styleTerms"])}</p><p>{r["status"]} · DataVersion {r["dataVersion"]}</p><small>{" / ".join(r["functionTerms"])}</small><small>{html.escape(r["relativePath"])}</small><small>尺寸 X/Y/Z：{r["sizeXYZ"]}</small></article>'
    doc+='''</main><script>function filter(){let n=0;const q=document.getElementById('q').value.toLowerCase(),g=document.getElementById('group').value,s=document.getElementById('status').value;document.querySelectorAll('article').forEach(a=>{a.hidden=!!((g&&!a.dataset.group.split(' ').includes(g))||(s&&a.dataset.status!==s)||!a.dataset.search.toLowerCase().includes(q));if(!a.hidden)n++});document.getElementById('count').textContent=n+' 项'}['q','group','status'].forEach(id=>document.getElementById(id).oninput=filter);filter();</script></html>'''
    (a.output/'index.html').write_text(doc,encoding='utf8')
    print(json.dumps(summary,ensure_ascii=False),flush=True)


if __name__=='__main__':main()
