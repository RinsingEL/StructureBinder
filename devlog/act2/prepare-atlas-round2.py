"""Extract historical decisions and prepare authored preview images for round two."""
import argparse, collections, hashlib, json, zipfile
from pathlib import Path
from PIL import Image

HERE=Path(__file__).resolve().parent
ROOT=HERE.parent.parent
p=argparse.ArgumentParser();p.add_argument('archive',type=Path);p.add_argument('replay',type=Path);args=p.parse_args()
read=lambda p:json.loads(p.read_text(encoding='utf-8'))
z=zipfile.ZipFile(args.archive)
name=next(n for n in z.namelist() if n.endswith('/realm_coordinate_selections.json'))
selections=json.loads(z.read(name));base=read(HERE/'data/atlas-base.json')
quotes=['沿岸共储、船具维护和陆侧住宅','优先组织内陆议事、木作教学','较南部谷地具有更大的连续空间','此处锚点距边界更远、连续空间更大','与计划中的东部干燥内陆国度拉开距离','适合遮阴街巷、公议院落','固定领土']
reasons=[]
for realm,quote in zip(base['realms'],quotes):
    item=next(s for s in selections if s['realmId']==realm['id'])
    assert item['primaryBlock']=={'x':realm['x'],'z':realm['z']}
    assert len(quote)<=18 and (quote in item['reason'] or realm['fixed'])
    reasons.append({'id':realm['id'],'reason':item['reason'],'quote':quote,'fixed':realm['fixed']})
lines=args.replay.read_text(encoding='utf-8').splitlines()
summary=json.loads(lines[56])['payload']['output'][1]['text']
offset=summary.index('"styles":')+len('"styles":')
styles=json.JSONDecoder().raw_decode(summary[offset:])[0]
revision=read(HERE/'data/atlas-revision.json')
out=HERE/'assets/atlas-materials';out.mkdir(exist_ok=True)
materials={}
for ref,meta in revision['overviewMaterials'].items():
    folder=ROOT/'asset_catalogs/original_civilizations/R02_arcane_reborn/models'/ref.split(':')[1].upper().replace('-V','-v')
    author=read(folder/'author.json');source=folder/'previews/front.png'
    if not source.exists():
        materials[ref]={'name':author['name'].split(' · ')[0],'beds':sum(p.get('kind')=='bed' for p in author.get('points',[])),'width':author['size'][0],'depth':author['size'][2],'terms':meta['functionTerms'],'image':None,'source':source.relative_to(ROOT).as_posix()}
        continue
    im=Image.open(source).convert('RGBA');im=im.crop((3,3,im.width-3,im.height-30))
    # Remove only background connected to the edge, preserving dark roof pixels.
    px=im.load();w,h=im.size;bg=px[0,0][:3];seen=set();queue=collections.deque()
    for x in range(w):queue.extend([(x,0),(x,h-1)])
    for y in range(h):queue.extend([(0,y),(w-1,y)])
    while queue:
        x,y=queue.popleft()
        if (x,y) in seen or not (0<=x<w and 0<=y<h):continue
        seen.add((x,y))
        if max(abs(px[x,y][i]-bg[i]) for i in range(3))>24:continue
        px[x,y]=(0,0,0,0);queue.extend([(x-1,y),(x+1,y),(x,y-1),(x,y+1)])
    box=im.getbbox();assert box,ref
    im=im.crop(box);im.thumbnail((320,240));dest=out/(folder.name+'.webp');im.save(dest,'WEBP',quality=86)
    materials[ref]={'name':author['name'].split(' · ')[0],'beds':sum(p.get('kind')=='bed' for p in author.get('points',[])),
      'width':author['size'][0],'depth':author['size'][2],'terms':meta['functionTerms'],
      'image':dest.relative_to(HERE).as_posix(),'source':source.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(source.read_bytes()).hexdigest()}
groups=[('住宿',['住宿']),('教学',['教学','讲学','自习']),('研究',['研究','观测','实验']),('餐饮',['餐饮','用餐']),('公共服务',['接待','议事','医护','洗浴','生活服务'])]
piles=[]
for label,terms in groups:
    refs=[ref for ref,m in materials.items() if any(t in term for term in m['terms'] for t in terms)]
    if refs:piles.append({'label':label,'refs':refs,'count':len(refs)})
result={'reasons':reasons,'styles':styles,'materials':materials,'piles':piles,
 'provenance':{'selectionSource':name,'selectionSha256':hashlib.sha256(z.read(name)).hexdigest(),'styleSummaryLine':57,'profileReadLine':72,'profileOutputLine':74,'selectionMethod':'catalog identifier filtering, not a materials API call'}}
(HERE/'data/atlas-round2.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
print('Prepared',len(reasons),'verified reasons,',len(styles),'historical styles,',sum(bool(m['image']) for m in materials.values()),'cutout previews;',len(materials),'named materials.')
