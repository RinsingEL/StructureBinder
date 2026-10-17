"""Read-only-source RFH1 builder. SPDX-License-Identifier: GPL-3.0-or-later.
Initialization is a separate init_samples.py command. Builds never write source/,
draft/, or authoring/. Generated editor projects live in build/projects/.
"""
from pathlib import Path
import argparse
import base64
import hashlib
import io
import json
import math
import uuid
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
NS=uuid.UUID('c6fc5419-a325-4ca6-b1e9-b0e8b286f4c4')
FACES=('north','east','south','west','up','down')
REGIONS={'skin':1,'cloth':2,'secondary':3,'trim':4,'leather':5,'metal':6,'lens':7,'hair':8,'eye':9}
COMPONENTS={}; CATALOG={}; RIG_DATA={}; POSE_DATA={}; BODIES={}; PAL={}; UV={}; TEXTURES={}

def dump(path,obj):
    p=ROOT/path
    if Path(path).parts[0] not in ('build','export','preview'):
        raise ValueError('Build may not write authoring input: '+str(path))
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def save_image(path,im):
    if Path(path).parts[0] not in ('build','export','preview'):raise ValueError('Build output outside generated directories')
    p=ROOT/path;p.parent.mkdir(parents=True,exist_ok=True);im.save(p)

def read_json(path):return json.loads((ROOT/path).read_text(encoding='utf-8'))
def uid(name):return str(uuid.uuid5(NS,name))
def rgb(h):return np.array(tuple(bytes.fromhex(h.lstrip('#'))),dtype=float)

def input_hashes():
    return {p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
            for folder in ('source','draft','authoring') for p in sorted((ROOT/folder).rglob('*')) if p.is_file()}

def source_texture(ref):
    prefix='realmfolk:appearance/textures/'
    if not ref.startswith(prefix):raise ValueError('Unsupported texture namespace/path: '+ref)
    rel=Path(ref[len(prefix):])
    if rel.is_absolute() or '..' in rel.parts:raise ValueError('Invalid texture path: '+ref)
    p=ROOT/'source/textures'/rel
    if not p.is_file():raise FileNotFoundError(p)
    return p

def image_source(ref):
    if ref not in TEXTURES:
        with Image.open(source_texture(ref)) as im:TEXTURES[ref]=im.convert('RGBA')
    return TEXTURES[ref].copy()

def load_sources():
    global RIG_DATA,POSE_DATA,BODIES,PAL,UV
    COMPONENTS.clear();CATALOG.clear();TEXTURES.clear()
    RIG_DATA=read_json('source/rig.json');POSE_DATA=read_json('source/poses.json')
    UV=read_json('source/uv_layout.json')['parts'];BODIES=RIG_DATA['variants']
    PAL=read_json('draft/pools.json')['palettes']
    for entry in read_json('draft/catalog.json')['assets']:
        if entry['id'] in CATALOG:raise ValueError('Duplicate catalog ID '+entry['id'])
        CATALOG[entry['id']]=entry
    for p in sorted((ROOT/'source/components').glob('*.json')):
        data=json.loads(p.read_text(encoding='utf-8'))
        if data['id'] in COMPONENTS:raise ValueError('Duplicate component ID '+data['id'])
        COMPONENTS[data['id']]=data
        for mat in data['materials'].values():
            source_texture(mat['texture']);source_texture(mat['regionMask'])
    if set(CATALOG)!=set(COMPONENTS):raise ValueError('Catalog/component ID mismatch')
    snaps=[json.loads(p.read_text(encoding='utf-8')) for p in sorted((ROOT/'draft/resolved').glob('*.json'))]
    if not snaps:raise ValueError('No resolved snapshots; initialize explicitly in an empty library')
    return snaps

def rig(body):return RIG_DATA['variants'][body]

def box(name,bone,lo,hi,region,part='torso',detail='fabric'):
    return {'name':name,'bone':bone,'from':lo,'to':hi,'uv':UV[part],'region':region,'detail':detail}

def compose_texture(asset,cube,params):
    binding=asset['materials'][cube['region']]
    ref=binding['texture']
    if asset['type']=='body':ref=CATALOG[params['skinAsset']]['texture']
    im=image_source(ref);shade=np.array(im,dtype=np.float64)
    mask=np.array(image_source(binding['regionMask']))[:,:,0]
    for region,index in REGIONS.items():
        pixels=mask==index
        shade[pixels,:3]*=rgb(params['colors'][region])/255
    im=Image.fromarray(shade.clip(0,255).astype(np.uint8))
    if asset['type']=='body':
        im=Image.alpha_composite(im,image_source(CATALOG[params['face']]['texture']))
        ea=np.array(image_source(CATALOG[params['eyes']]['texture']))
        iris=(ea[:,:,:3]==255).all(axis=2)&(ea[:,:,3]>0)
        ea[iris,:3]=rgb(params['colors']['eye']).astype(np.uint8)
        im=Image.alpha_composite(im,Image.fromarray(ea))
    return im

def assembly(params):
    ids=[params[k] for k in ['body','ears','hair','base','outer']]+params['accessories']
    out=[]
    for aid in ids:
        a=COMPONENTS[aid]
        for c in a['variants'][params['body'].split('/')[-1]]:
            out.append({**c,'assetId':aid,'materialKey':a['material']+'_'+c['region'], 'textureImage':compose_texture(a,c,params)})
    return out


def visible_cubes(cubes, action):
    result=[]
    for c in cubes:
        hidden=False
        for rule in CATALOG[c['assetId']]['hideRules']:
            if action in rule.get('poses',[]) and ('bones' not in rule or c['bone'] in rule['bones']):
                hidden=True
        if not hidden:result.append(c)
    return result


def export_files(params):
    name=params['citizenDemoId'].removeprefix('demo_'); body=params['body'].split('/')[-1]
    cubes=assembly(params); r=rig(body)
    textures={}
    for c in cubes:textures[c['materialKey']]=c['textureImage']
    names=list(textures)
    project={'meta':{'format_version':'4.10','model_format':'free','box_uv':False},'name':name,
             'resolution':{'width':128,'height':128},'elements':[],'outliner':[],'textures':[],
             'credit':'Original RFH1 offline prototype; GPL-3.0-or-later; not game validated'}
    for i,(key,im) in enumerate(textures.items()):
        buff=io.BytesIO();im.save(buff,format='PNG')
        project['textures'].append({'name':key+'.png','id':str(i),'uuid':uid(name+key),'width':128,'height':128,
                                   'uv_width':128,'uv_height':128,'source':'data:image/png;base64,'+base64.b64encode(buff.getvalue()).decode(),
                                   'internal':True,'mode':'bitmap','visible':True})
    groups={}
    for bone,b in r['bones'].items():
        groups[bone]={'name':bone,'origin':b['pivot'],'rotation':[0,0,0],'uuid':uid(name+bone),'export':True,'isOpen':True,'visibility':True,'children':[]}
    for bone,b in r['bones'].items():
        (project['outliner'] if b['parent'] is None else groups[b['parent']]['children']).append(groups[bone])
    for i,c in enumerate(cubes):
        cu=uid(name+str(i)+c['name']);groups[c['bone']]['children'].append(cu)
        project['elements'].append({'name':c['assetId'].split(':')[-1]+'.'+c['name'],'type':'cube','uuid':cu,
           'from':c['from'],'to':c['to'],'origin':r['bones'][c['bone']]['pivot'],'rotation':[0,0,0],
           'box_uv':False,'rescale':False,'shade':True,'inflate':0,'export':True,'visibility':True,
           'faces':{f:{'uv':c['uv'][f],'texture':names.index(c['materialKey'])} for f in FACES}})
    dump('build/projects/'+name+'.bbmodel',project)

def export_resources():
    for aid,a in COMPONENTS.items():
        if not a['variants']:continue
        export={**a,'schema':'realmfolk_rfmodel.draft1','unit':'1/16 block',
                'coordinateSystem':'RH +X character-left, +Y up, front -Z','runtimeSupported':False,
                'variantCoordinates':'global rest coordinates; bone pivot local conversion required'}
        dump('export/proposed/assets/realmfolk/appearance/models/'+aid.split(':')[1]+'.rfmodel.json',export)
    dst=ROOT/'export/proposed/assets/realmfolk/appearance/textures';dst.mkdir(parents=True,exist_ok=True)
    for p in (ROOT/'source/textures').rglob('*.png'):
        target=dst/p.relative_to(ROOT/'source/textures');target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(p.read_bytes())
    dump('export/proposed/assets/realmfolk/appearance/rig.json',RIG_DATA)
    dump('export/proposed/assets/realmfolk/appearance/poses.json',POSE_DATA)

def pose_state(name,phase=0,body='standard'):
    sample=POSE_DATA['variants'][body][name]
    clip=sample.get('clip')
    if clip:
        frames=clip['frames'];position=(phase%1)*len(frames) if clip.get('loop') else min(max(phase,0),1)*(len(frames)-1)
        index=int(math.floor(position));weight=position-index;a=frames[index];bb=frames[(index+1)%len(frames)]
        def blend(key,default):
            if isinstance(default,dict):
                av=a.get(key,{});bv=bb.get(key,{})
                return {n:((1-weight)*np.array(av.get(n,[0,0,0]))+weight*np.array(bv.get(n,[0,0,0]))).tolist() for n in av.keys()|bv.keys()}
            return ((1-weight)*np.array(a[key])+weight*np.array(bb[key])).tolist()
        return blend('rotations',{}),np.array(blend('rootTranslation',[])),blend('boneTranslations',{})
    return sample['rotations'],np.array(sample['rootTranslation'],float),sample.get('boneTranslations',{})

def pose(name,phase=0,body='standard'):
    angles,offset,_=pose_state(name,phase,body);return angles,offset

def pose_translations(name,body='standard',phase=0):return pose_state(name,phase,body)[2]

def rotation(degrees):
    x,y,z=np.radians(degrees); cx,sx=math.cos(x),math.sin(x);cy,sy=math.cos(y),math.sin(y);cz,sz=math.cos(z),math.sin(z)
    rx=np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]])
    ry=np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]])
    rz=np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]])
    return rz@ry@rx


def matrices(r, angles, offset, translations=None):
    result={}
    for name,b in r['bones'].items():
        parent=b['parent'];pivot=np.array(b['pivot'],float); parentpivot=np.array(r['bones'][parent]['pivot'],float) if parent else np.zeros(3)
        local=np.eye(4);local[:3,:3]=rotation(angles.get(name,[0,0,0]));local[:3,3]=pivot-parentpivot+np.array((translations or {}).get(name,[0,0,0]))
        result[name]=(result[parent] if parent else np.eye(4))@local
    for m in result.values():m[:3,3]+=offset
    return result


def world_box(c, r, transforms):
    lo,hi=c['from'],c['to']
    v=np.array([[lo[0],lo[1],lo[2]],[hi[0],lo[1],lo[2]],[hi[0],hi[1],lo[2]],[lo[0],hi[1],lo[2]],
                [lo[0],lo[1],hi[2]],[hi[0],lo[1],hi[2]],[hi[0],hi[1],hi[2]],[lo[0],hi[1],hi[2]]])
    m=transforms[c['bone']]; piv=np.array(r['bones'][c['bone']]['pivot'])
    return (v-piv)@m[:3,:3].T+m[:3,3]


def fixture(lo,hi,color):
    c=box('fixture','root',lo,hi,'cloth');c['assetId']='preview:fixture'
    c['textureImage']=Image.new('RGBA',(128,128),color);return c


FACE_VERTS={'north':[0,1,2,3],'east':[1,5,6,2],'south':[5,4,7,6],'west':[4,0,3,7],'up':[3,2,6,7],'down':[4,5,1,0]}


def raster(cubes, r, transforms, width, height, yaw=-25, scale=6.0, center_y=16):
    # Orthographic depth-buffered triangles, point-sampled real PNG UVs.
    bg=np.array([20,29,40,255],np.uint8);canvas=np.broadcast_to(bg,(height,width,4)).copy();zbuf=np.full((height,width),-1e9)
    camera=rotation([12,yaw,0]);light=np.array([-.35,.8,-.45]);light/=np.linalg.norm(light)
    for c in cubes:
        if c['assetId']=='preview:fixture':
            v=world_box(c,{'bones':{'root':{'pivot':[0,0,0]}}},{'root':np.eye(4)})
        else:v=world_box(c,r,transforms)
        cam=(v-np.array([0,center_y,0]))@camera.T
        screen=np.column_stack([cam[:,0]*scale+width/2,-cam[:,1]*scale+height/2,-cam[:,2]])
        tex=np.array(c['textureImage']);
        for face,indices in FACE_VERTS.items():
            pts=v[indices];normal=np.cross(pts[1]-pts[0],pts[3]-pts[0]);normal/=max(np.linalg.norm(normal),1e-8)
            # Listed winding points inward for some faces; use fixed outward normals transformed from cube basis.
            norm={'north':[0,0,-1],'east':[1,0,0],'south':[0,0,1],'west':[-1,0,0],'up':[0,1,0],'down':[0,-1,0]}[face]
            normal=np.array(norm,dtype=float)
            if c['assetId']!='preview:fixture':normal=transforms[c['bone']][:3,:3]@normal
            if (camera@normal)[2]>=0:continue
            shade=.58+.42*max(0,float(normal@light))
            u0,v0,u1,v1=c['uv'][face]
            uv=np.array([[u0,v1-1],[u1-1,v1-1],[u1-1,v0],[u0,v0]],float)
            for tri in [[0,1,2],[0,2,3]]:
                s=screen[np.array(indices)[tri]];u=uv[tri]
                xmin=max(0,int(np.floor(s[:,0].min())));xmax=min(width-1,int(np.ceil(s[:,0].max())))
                ymin=max(0,int(np.floor(s[:,1].min())));ymax=min(height-1,int(np.ceil(s[:,1].max())))
                if xmin>xmax or ymin>ymax:continue
                yy,xx=np.mgrid[ymin:ymax+1,xmin:xmax+1];xx=xx+.5;yy=yy+.5
                den=(s[1,1]-s[2,1])*(s[0,0]-s[2,0])+(s[2,0]-s[1,0])*(s[0,1]-s[2,1])
                if abs(den)<1e-8:continue
                w0=((s[1,1]-s[2,1])*(xx-s[2,0])+(s[2,0]-s[1,0])*(yy-s[2,1]))/den
                w1=((s[2,1]-s[0,1])*(xx-s[2,0])+(s[0,0]-s[2,0])*(yy-s[2,1]))/den;w2=1-w0-w1
                depth=w0*s[0,2]+w1*s[1,2]+w2*s[2,2]
                uu=(w0*u[0,0]+w1*u[1,0]+w2*u[2,0]).astype(int).clip(0,127)
                vv=(w0*u[0,1]+w1*u[1,1]+w2*u[2,1]).astype(int).clip(0,127)
                pixels=tex[vv,uu].copy();pixels[:,:,:3]=(pixels[:,:,:3]*shade).astype(np.uint8)
                mask=(w0>=-1e-5)&(w1>=-1e-5)&(w2>=-1e-5)&(depth>zbuf[ymin:ymax+1,xmin:xmax+1])&(pixels[:,:,3]>127)
                canvas[ymin:ymax+1,xmin:xmax+1][mask]=pixels[mask]
                zbuf[ymin:ymax+1,xmin:xmax+1][mask]=depth[mask]
    return Image.fromarray(canvas)


def render(params, action='stand', phase=.18, size=(300,340), yaw=-25):
    body=params['body'].split('/')[-1];r=rig(body);angles,offset=pose(action,phase,body);trans=matrices(r,angles,offset,pose_translations(action,body,phase))
    cubes=visible_cubes(assembly(params),action)
    fixtures=[]
    if action=='sit':
        fixtures=[fixture([-6,5,.2],[6,7,5],'#725846'),fixture([-6,7,4],[6,16.5,5.2],'#7c6250')]
        for x in [-5,4]:
            for z in [0,4]:fixtures.append(fixture([x,0,z],[x+1,5,z+1],'#534235'))
    if action=='sleep':fixtures=[fixture([-7,2,-17],[7,4,19],'#544537'),fixture([-7,4,-17],[7,5,19],'#b4b9ad'),
                                fixture([-6,5,8],[6,5.6,17],'#d0c9b5')]
    if action=='work':fixtures=[fixture([-10,10,-12],[10,11,-9],'#716355')]
    if action in ['hold','eat','work']:
        socket=r['sockets']['hand_r'];m=trans[socket['bone']]
        v=np.array(socket['position'])-np.array(r['bones'][socket['bone']]['pivot'])
        pos=m[:3,:3]@v+m[:3,3]
        prop=box('temporary_prop',socket['bone'],[socket['position'][0]-.6,socket['position'][1]-.6,socket['position'][2]-2.8],
                 [socket['position'][0]+.6,socket['position'][1]+.6,socket['position'][2]+.8],'cloth')
        prop['assetId']='preview:held_prop';prop['textureImage']=Image.new('RGBA',(128,128),{'hold':'#b79b6a','eat':'#c8914f','work':'#8cabb3'}[action]);cubes.append(prop)
    w,h=size;scale=min(w/43,h/42)
    if action=='sleep' and yaw==-25:yaw=-65
    im=raster(cubes+fixtures,r,trans,w,h,yaw,scale,9 if action=='sleep' else 13 if action=='sit' else 16)
    return im


def font(size):
    for f in ['C:/Windows/Fonts/msyh.ttc','C:/Windows/Fonts/segoeui.ttf']:
        if Path(f).exists():return ImageFont.truetype(f,size)
    return ImageFont.load_default(size=size)


def label(im,text,pos=(16,12),size=18,color='#e8dfca'):
    ImageDraw.Draw(im).text(pos,text,font=font(size),fill=color)


def previews(snaps):
    sheet=Image.new('RGB',(1440,1280),'#101923')
    label(sheet,'REALMFOLK / 可组合居民外观 · RFH1',size=30,pos=(42,24))
    label(sheet,'离线几何预览  /  三风格 × 三体型  /  非游戏截图',size=17,pos=(44,70),color='#9baebb')
    styles=list(PAL);bodies=list(BODIES)
    for i,style in enumerate(styles):
        for j,body in enumerate(bodies):
            p=next(p for p in snaps if p['palette']==style and p['body'].endswith('/'+body))
            tile=render(p,size=(450,330));x=35+j*470;y=125+i*365
            sheet.paste(tile.convert('RGB'),(x,y))
            label(sheet,{'academy':'魔法学院 / 分片袍','steam':'蒸汽朋克 / 短外套','elven':'精灵风格 / 叶形衣襟'}[style]+' · '+{'standard':'标准','slender':'纤细','sturdy':'壮实'}[body],(x+12,y+325),17)
    label(sheet,'肤色、耳形与体型交叉演示；服饰风格不决定种族。装饰包在坐姿 / 睡姿临时隐藏。',(44,1240),16,'#a6b9bf')
    save_image('preview/lineup.png',sheet)
    p=next(p for p in snaps if p['palette']=='academy' and p['body'].endswith('/standard'))
    turn=Image.new('RGB',(1440,550),'#101923');label(turn,'学院完整组合 / 标准体型 / 同一源几何的四向检查',(28,18),25)
    for i,(yaw,name) in enumerate([(0,'正面'),(-45,'前侧'),(-90,'侧面'),(180,'背面')]):
        turn.paste(render(p,size=(340,420),yaw=yaw).convert('RGB'),(20+i*350,65));label(turn,name,(145+i*350,490),18)
    label(turn,'离线预览；并未验证 Minecraft 渲染加载。',(28,523),14,'#9baebb');save_image('preview/academy_turnaround.png',turn)
    actions=['stand','walk','sit','sleep','hold','eat','work'];action_names=['站立','行走','坐下','睡觉','持物','进食','工作']
    for style in styles:
        sheet=Image.new('RGB',(1750,950),'#101923');label(sheet,f'{style.upper()} / 七姿态 × 三体型 · 离线关节采样',(22,18),24)
        for j,body in enumerate(bodies):
            p=next(p for p in snaps if p['palette']==style and p['body'].endswith('/'+body))
            for i,action in enumerate(actions):
                tile=render(p,action,size=(240,250));sheet.paste(tile.convert('RGB'),(i*250+5,j*280+74))
                label(sheet,{'standard':'标准','slender':'纤细','sturdy':'壮实'}[body]+' / '+action_names[i],(i*250+18,j*280+320),15)
        label(sheet,'家具为本工具代理方块；临时持物为代理形状。GIF 验证骨骼连续运动，不等于现有生活行为已接入。',(22,923),15,'#9baebb')
        save_image(f'preview/poses_{style}.png',sheet)
        body={'academy':'standard','steam':'sturdy','elven':'slender'}[style]
        p=next(p for p in snaps if p['palette']==style and p['body'].endswith('/'+body))
        frames=[]
        for action in actions:
            for step in range(8):
                frame=render(p,action,step/8,size=(420,410)).convert('RGB')
                label(frame,f'{style} / {body} / {action}',(12,10),18)
                label(frame,'离线骨骼动画 · 非游戏录屏',(12,382),15,'#acb9be');frames.append(frame)
        frames[0].save(ROOT/f'preview/motion_{style}.gif',save_all=True,append_images=frames[1:],duration=140,loop=0,optimize=False,disposal=2)
    detail=Image.new('RGB',(1440,820),'#101923');label(detail,'配饰接触面 / 离线代理家具检查',(22,18),25)
    cases=[('steam','sturdy','stand',150,'背包 / 壮实背面'),('steam','sturdy','sit',-90,'坐下 / 包与腰包隐藏'),
           ('steam','sturdy','sleep',-65,'睡觉 / 包与腰包隐藏'),('elven','slender','stand',150,'束发 / 纤细背面'),
           ('elven','slender','sit',-90,'坐下 / 垂尾束起'),('academy','standard','sit',-90,'分片袍 / 椅面侧视')]
    for i,(style,b,action,yaw,name) in enumerate(cases):
        p=next(p for p in snaps if p['palette']==style and p['body'].endswith('/'+b))
        x=i%3*480;y=i//3*375+60
        detail.paste(render(p,action,size=(470,315),yaw=yaw).convert('RGB'),(x,y));label(detail,name,(x+18,y+325),18)
    save_image('preview/accessory_checks.png',detail)



def uv_preview():
    guide=Image.new('RGB',(1024,1024),'#16202c');d=ImageDraw.Draw(guide)
    for part,faces in UV.items():
        for face,(x,y,x1,y1) in faces.items():
            d.rectangle((x*8,y*8,x1*8-1,y1*8-1),outline='#b5bacc',width=2)
            d.text((x*8+3,y*8+4),part.replace('right_','R.').replace('left_','L.')+'\n'+face,font=font(11),fill='#e0d4b5')
    save_image('preview/uv_guide.png',guide)

def build(assembly_name=None):
    before=input_hashes();snaps=load_sources()
    selected=snaps if assembly_name is None else [s for s in snaps if s['citizenDemoId']=='demo_'+assembly_name]
    if not selected:raise ValueError('Unknown assembly '+str(assembly_name))
    export_resources()
    for s in selected:
        export_files(s)
        save_image('preview/'+s['citizenDemoId'].removeprefix('demo_')+'.png',render(s,size=(450,420)))
    if assembly_name is None:previews(snaps)
    uv_preview()
    after=input_hashes()
    if before!=after:raise RuntimeError('Build changed authoring inputs')
    dump('build/build_manifest.json',{'schema':'realmfolk_build.v1','sourceReadOnly':True,
         'inputSha256':before,'assemblies':[s['citizenDemoId'] for s in selected]})
    print('Built',len(selected),'assemblies from existing sources; source/draft/authoring byte hashes preserved.')

def main():
    global ROOT
    parser=argparse.ArgumentParser(description='Rebuild exports and previews from existing authoring sources. Never initializes.')
    parser.add_argument('--root',type=Path,default=ROOT);parser.add_argument('--assembly')
    args=parser.parse_args();ROOT=args.root.resolve();build(args.assembly)

if __name__=='__main__':main()
