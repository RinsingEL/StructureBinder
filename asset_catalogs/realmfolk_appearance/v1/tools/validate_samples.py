"""Validate the offline RFH1 artifact contract; does not start Minecraft or Blockbench.
SPDX-License-Identifier: GPL-3.0-or-later
"""
import base64
import hashlib
import io
import json
import math
import re
from pathlib import Path
import numpy as np
from PIL import Image
import build_samples as b

ROOT=b.ROOT
CHECKS=[]


def check(ok, what):
    if not ok:raise AssertionError(what)
    CHECKS.append(what)


def load(path):
    return json.loads((ROOT/path).read_text(encoding='utf-8'))


def resource(path):
    check(bool(re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9_./-]+',path)),'resource ID syntax: '+path)
    ns,rel=path.split(':',1)
    check('..' not in rel.split('/'),'resource path stays inside namespace: '+path)
    return ROOT/'export/proposed/assets'/ns/rel


def resolved_ids(s):
    return [s[k] for k in ['body','skinAsset','face','eyes','ears','hair','base','outer']]+s['accessories']


def conflicts(ids,catalog):
    errors=[]
    occupied={};tags=set()
    for aid in ids:
        a=catalog[aid]
        tags.update(a['compatibilityTags'])
        for slot in a['occupies']:
            if slot in occupied:errors.append('duplicate slot '+slot)
            occupied[slot]=aid
    for aid in ids:
        for rule in catalog[aid]['excludes']:
            if rule.get('withSlot') in occupied or rule.get('withTag') in tags:
                errors.append(aid+' exclusion matched')
    return errors


def obb_overlap(vertices, lo, hi, tolerance=.2):
    """Separating axis test against proxy AABB. Ignore <=.2u cloth contact.
    This checks only furniture, never claims all clothing/self intersections solved.
    """
    vertices=np.array(vertices);lo=np.array(lo);hi=np.array(hi)
    corners=np.array([[x,y,z] for x in [lo[0],hi[0]] for y in [lo[1],hi[1]] for z in [lo[2],hi[2]]])
    axes=[vertices[1]-vertices[0],vertices[3]-vertices[0],vertices[4]-vertices[0]]
    bases=np.eye(3);axes=axes+list(bases)+[np.cross(a,bb) for a in axes for bb in bases]
    for a in axes:
        length=np.linalg.norm(a)
        if length<1e-8:continue
        a=a/length;p=vertices@a;q=corners@a
        if min(p.max(),q.max())-max(p.min(),q.min())<=tolerance:return False
    return True


def validate():
    b.load_sources()
    catalog_data=load('draft/catalog.json');catalog={a['id']:a for a in catalog_data['assets']}
    check(len(catalog)==len(catalog_data['assets'])==25,'25 unique asset IDs')
    b.CATALOG.update(catalog)
    for p in (ROOT/'source/components').glob('*.json'):
        a=json.loads(p.read_text(encoding='utf-8'));b.COMPONENTS[a['id']]=a
    check(set(b.COMPONENTS)==set(catalog),'catalog and component source IDs agree')
    rigdata=load('source/rig.json')
    check(rigdata['runtimeSupported'] is False,'rig explicitly marked unsupported by runtime')
    for body,r in rigdata['variants'].items():
        check(len(r['bones'])==13,body+' has 13 bones')
        done=set()
        for name,bone in r['bones'].items():
            check(bone['parent'] is None or bone['parent'] in done,body+' parent order / no cycle '+name)
            check(np.isfinite(bone['pivot']).all(),body+' finite pivot '+name);done.add(name)
        for name,s in r['sockets'].items():check(s['bone'] in done,body+' valid socket '+name)
    for aid,a in catalog.items():
        check(math.isfinite(a['weight']) and a['weight']>0,aid+' finite positive weight')
        check(a['status']=='offline_prototype',aid+' status label')
        if a['fallback']:
            seen={aid};cur=a['fallback']
            while cur:
                check(cur in catalog and cur not in seen,aid+' fallback terminates')
                seen.add(cur);cur=catalog[cur]['fallback']
        if a['model']:check(resource(a['model']).is_file(),aid+' exported model exists')
        check(resource(a['texture']).is_file(),aid+' texture exists')
        comp=b.COMPONENTS[aid]
        for region,mat in a['materials'].items():
            tex=resource(mat['texture']);mask=resource(mat['regionMask'])
            check(tex.is_file() and mask.is_file(),aid+' material refs exist '+region)
            ti=np.array(Image.open(tex).convert('RGBA'));mi=np.array(Image.open(mask).convert('RGBA'))
            check(ti.shape==(128,128,4) and mi.shape==ti.shape,aid+' material size '+region)
            check(np.array_equal(ti[:,:,3],mi[:,:,3]),aid+' material alpha agrees '+region)
            check(np.all(mi[:,:,0][mi[:,:,3]>0]==b.REGIONS[region]),aid+' mask tint index '+region)
        for body,cubes in comp['variants'].items():
            check(body in a['supports']['bodies'],aid+' supported body '+body)
            for cube in cubes:
                check(cube['bone'] in rigdata['variants'][body]['bones'],aid+' valid cube bone '+cube['name'])
                check(np.all(np.array(cube['to'])>np.array(cube['from'])),aid+' positive cube '+cube['name'])
                check(cube['region'] in comp['materials'],aid+' cube material '+cube['name'])
                for face,uv in cube['uv'].items():
                    check(0<=uv[0]<uv[2]<=128 and 0<=uv[1]<uv[3]<=128,aid+' UV bounds '+cube['name']+'.'+face)
    uv=load('source/uv_layout.json')['parts'];rects=[rect for faces in uv.values() for rect in faces.values()]
    for i,a in enumerate(rects):
        for c in rects[i+1:]:
            check(min(a[2],c[2])<=max(a[0],c[0]) or min(a[3],c[3])<=max(a[1],c[1]),'body UV semantic cells nonoverlap')
    stats=[];proxy_issues=[];sample_count=0
    for file in sorted((ROOT/'draft/resolved').glob('*.json')):
        s=json.loads(file.read_text(encoding='utf-8'));ids=resolved_ids(s);body=s['body'].split('/')[-1]
        check(all(i in catalog for i in ids),s['citizenDemoId']+' resolved IDs exist')
        check(not conflicts(ids,catalog),s['citizenDemoId']+' no slot/exclusion conflicts')
        check(set(s['colors'])==set(b.PAL[s['palette']])|{'skin','hair','eye'},s['citizenDemoId']+' saves concrete colors')
        check(all(re.fullmatch(r'#[0-9a-fA-F]{6}',v) for v in s['colors'].values()),s['citizenDemoId']+' valid hex colors')
        for aid in ids:check(body in catalog[aid]['supports']['bodies'],s['citizenDemoId']+' compatible '+aid)
        cubes=b.assembly(s);r=rigdata['variants'][body]
        stats.append({'id':s['citizenDemoId'],'cubes':len(cubes),'materialBindings':len({c['materialKey'] for c in cubes})})
        check(len(cubes)<=96,s['citizenDemoId']+' cube budget <=96')
        check(len({c['materialKey'] for c in cubes})<=16,s['citizenDemoId']+' material binding budget <=16')
        # Concrete color values remain unchanged even if a pool palette is edited later.
        before=np.array(next(c for c in cubes if c['region']=='cloth')['textureImage']);old=b.PAL[s['palette']]['cloth'];b.PAL[s['palette']]['cloth']='#ff00ff'
        after=np.array(next(c for c in b.assembly(s) if c['region']=='cloth')['textureImage']);b.PAL[s['palette']]['cloth']=old
        check(np.array_equal(before,after),s['citizenDemoId']+' stored colors not re-read from palette')
        for action in ['stand','walk','sit','sleep','hold','eat','work']:
            for step in range(8):
                phase=step/8;angles,offset=b.pose(action,phase,body);m=b.matrices(r,angles,offset,b.pose_translations(action,body,phase))
                visible=b.visible_cubes(cubes,action);sample_count+=1
                check(all(np.isfinite(v).all() for v in m.values()),s['citizenDemoId']+' finite pose '+action)
                if action in ['sit','sleep']:
                    check(not any(c['assetId'] in ['realmfolk:accessory/backpack','realmfolk:accessory/pouch'] for c in visible),s['citizenDemoId']+' packs hidden '+action)
                    check(not any(c['assetId']=='realmfolk:hair/tied' and c['bone']=='hair_tail' for c in visible),s['citizenDemoId']+' tail hidden '+action)
                    proxies=[('seat',[-6,5,.2],[6,7,5]),('chair_back',[-6,7,4],[6,16.5,5.2])] if action=='sit' else [('mattress',[-7,4,-17],[7,5,19]),('pillow',[-6,5,8],[6,5.6,17])]
                    for c in visible:
                        verts=b.world_box(c,r,m)
                        for pname,lo,hi in proxies:
                            if obb_overlap(verts,lo,hi):proxy_issues.append({'id':s['citizenDemoId'],'action':action,'step':step,'cube':c['assetId']+'/'+c['name'],'proxy':pname})
        check(len(b.visible_cubes(cubes,'stand'))==len(cubes),s['citizenDemoId']+' restores visibility after pose')
        pj=load('build/projects/'+file.stem+'.bbmodel');ids_seen=set();element_ids=set()
        for e in pj['elements']:
            check(e['uuid'] not in ids_seen,file.stem+' unique cube UUID');ids_seen.add(e['uuid']);element_ids.add(e['uuid'])
            check(all(isinstance(f['texture'],int) and 0<=f['texture']<len(pj['textures']) for f in e['faces'].values()),file.stem+' face texture indexes')
        found=[]
        def walk(nodes):
            for node in nodes:
                if isinstance(node,str):found.append(node)
                else:
                    check(node['uuid'] not in ids_seen,file.stem+' unique group UUID');ids_seen.add(node['uuid']);walk(node['children'])
        walk(pj['outliner'])
        check(set(found)==element_ids and len(found)==len(element_ids),file.stem+' outliner owns every cube exactly once')
        for tex in pj['textures']:
            decoded=Image.open(io.BytesIO(base64.b64decode(tex['source'].split(',',1)[1])));check(decoded.size==(128,128),file.stem+' embedded PNG decodes')
    bad=['realmfolk:hair/tied','realmfolk:accessory/backpack']
    check(bool(conflicts(bad,catalog)),'negative fixture: tied hair + backpack rejected')
    check(bool(conflicts(['realmfolk:accessory/belt','realmfolk:accessory/belt'],catalog)),'negative fixture: duplicate waist rejected')
    for path in (ROOT/'preview').glob('*.png'):
        im=Image.open(path);im.verify();check(True,'preview PNG decodes '+path.name)
    for path in (ROOT/'preview').glob('*.gif'):
        im=Image.open(path);check(im.n_frames>=24,'GIF contains continuous samples '+path.name)
    hashes={}
    for folder in ['source','draft','export/proposed','preview','build/projects']:
        for p in sorted((ROOT/folder).rglob('*')):
            if p.is_file():hashes[p.relative_to(ROOT).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
    report={'schema':'realmfolk_asset_verification.draft1','date':'2026-10-16','checksPassed':len(CHECKS),'assetIds':len(catalog),'completeCombinations':len(stats),
            'poseSamples':sample_count,'assemblyStats':stats,'proxyCollisionToleranceU':.2,'proxyCollisionIssues':proxy_issues,
            'staticPreview':'generated_and_structurally_checked; human/model visual review reported in planning docs',
            'offlineAnimation':'7 actions, 8 samples per action per assembly; loops only walk/eat/work; hard cuts between actions',
            'notVerified':['Blockbench editor open/import/export','Minecraft RFH1 loading','real furniture collision','real item grip','motion transitions','renderer performance','network/save integration','all beard and eye variants in complete outfits'],
            'sha256':hashes}
    check(b.input_hashes()==load('build/build_manifest.json')['inputSha256'],'build input hashes still match canonical authoring files')
    report['checksPassed']=len(CHECKS)
    b.dump('build/verification.json',report)
    print(json.dumps({k:report[k] for k in ['checksPassed','assetIds','completeCombinations','poseSamples','assemblyStats','proxyCollisionToleranceU']},ensure_ascii=False))
    print('Proxy collision issues:',len(proxy_issues),'(not a game collision test). See build/verification.json.')
    if proxy_issues:raise AssertionError('Proxy furniture intersections remain; inspect report before delivery')


if __name__=='__main__':validate()
