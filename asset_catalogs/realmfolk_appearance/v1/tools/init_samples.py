"""Explicit new-library initializer. Refuses any existing source or draft tree. Offline only; no Minecraft/Blockbench runtime dependency.
SPDX-License-Identifier: GPL-3.0-or-later
Run from any directory. Never writes to src/main/resources or a world save.
"""
from pathlib import Path
import base64
import hashlib
import io
import json
import math
import uuid
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
NS = uuid.UUID('c6fc5419-a325-4ca6-b1e9-b0e8b286f4c4')
BODIES = {'standard': (8, 4, 3, 3.5), 'slender': (7, 3.5, 2.5, 3), 'sturdy': (10, 5, 4, 4.5)}
FACES = ('north', 'east', 'south', 'west', 'up', 'down')
# Fixed face slots: shared semantic UVs across every body. No vanilla-skin UV claim.
PARTS = ['head', 'torso', 'right_upper_arm', 'left_upper_arm', 'right_forearm', 'left_forearm',
         'right_thigh', 'left_thigh', 'right_shin', 'left_shin']
UV = {name: {face: [j * 16, i * 12, j * 16 + 12, i * 12 + 10]
             for j, face in enumerate(FACES)} for i, name in enumerate(PARTS)}
PAL = {
 'academy': {'cloth': '#303c72', 'secondary': '#dad6bf', 'trim': '#d8b968', 'leather': '#655044', 'metal': '#c7ac68', 'lens': '#8fc8c7'},
 'steam': {'cloth': '#654331', 'secondary': '#d5c39b', 'trim': '#bd783f', 'leather': '#382d2b', 'metal': '#c99b4d', 'lens': '#65b1af'},
 'elven': {'cloth': '#365e50', 'secondary': '#b4c7a4', 'trim': '#e0c27e', 'leather': '#6e4b37', 'metal': '#c5bd91', 'lens': '#a4d0c0'},
}
SKIN = {'warm_ochre': '#ba805f', 'deep_umber': '#704638', 'light_peach': '#dfb496', 'olive': '#a48d66',
        'copper': '#975940', 'rose': '#c89b8e', 'cool_brown': '#8e7067', 'fantasy_ash': '#8e9e9c'}
HAIR = {'ink': '#252b32', 'umber': '#533e31', 'silver': '#c6c3b4', 'copper': '#a65c38', 'wheat': '#be9954', 'plum': '#594555'}
REGIONS = {'skin': 1, 'cloth': 2, 'secondary': 3, 'trim': 4, 'leather': 5, 'metal': 6, 'lens': 7, 'hair': 8, 'eye': 9}
COMPONENTS = {}
CATALOG = {}


def dump(path, obj):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def save_image(path, im):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    im.save(p)


def uid(name):
    return str(uuid.uuid5(NS, name))


def rgb(h):
    return np.array(tuple(bytes.fromhex(h.lstrip('#'))), dtype=float)


def rig(body):
    w, d, a, leg = BODIES[body]
    armx, legx = (w + a) / 2, leg / 2 + .15
    bones = {'root': (None, [0, 0, 0]), 'pelvis': ('root', [0, 12, 0]), 'spine': ('pelvis', [0, 12, 0]),
             'head': ('spine', [0, 22, 0]), 'hair_tail': ('head', [0, 23, 3.8])}
    for side, s in [('right', -1), ('left', 1)]:
        bones[f'{side}_upper_arm'] = ('spine', [s * armx, 21, 0])
        bones[f'{side}_forearm'] = (f'{side}_upper_arm', [s * armx, 16, 0])
        bones[f'{side}_thigh'] = ('pelvis', [s * legx, 12, 0])
        bones[f'{side}_shin'] = (f'{side}_thigh', [s * legx, 6, 0])
    sockets = {
      'hand_r': {'bone': 'right_forearm', 'position': [-armx, 11.2, -.5], 'rotation': [0, 0, 0]},
      'hand_l': {'bone': 'left_forearm', 'position': [armx, 11.2, -.5], 'rotation': [0, 0, 0]},
      'head_top': {'bone': 'head', 'position': [0, 30, 0]},
      'face': {'bone': 'head', 'position': [0, 26, -4.15]},
      'neck': {'bone': 'spine', 'position': [0, 21.6, 0]},
      'chest': {'bone': 'spine', 'position': [-w/3, 19, -d/2-.2]},
      'waist': {'bone': 'pelvis', 'position': [0, 12.6, 0]},
      'hip_r': {'bone': 'pelvis', 'position': [-w/2-.5, 12, 0]},
      'back': {'bone': 'spine', 'position': [0, 18, d/2+.3]},
      'seat_contact': {'bone': 'pelvis', 'position': [0, 12, .5]},
      'sleep_head': {'bone': 'head', 'position': [0, 26, 0]},
    }
    return {'bones': {n: {'parent': p, 'pivot': v} for n, (p, v) in bones.items()}, 'sockets': sockets}


def box(name, bone, lo, hi, region, part='torso', detail='fabric'):
    return {'name': name, 'bone': bone, 'from': lo, 'to': hi, 'uv': UV[part], 'region': region, 'detail': detail}


def body_boxes(body, inflate=0, region='skin', legs=True, head=True):
    w, d, a, l = BODIES[body]
    p = []
    def add(n, bone, lo, hi, part):
        p.append(box(n, bone, [v-inflate for v in lo], [v+inflate for v in hi], region, part, 'skin' if region=='skin' else 'fabric'))
    if head:
        add('head', 'head', [-4, 22, -4], [4, 30, 4], 'head')
    add('torso', 'spine', [-w/2, 12, -d/2], [w/2, 22, d/2], 'torso')
    for side, s in [('right', -1), ('left', 1)]:
        x = s*(w+a)/2
        add(side+'_upper_arm', side+'_upper_arm', [x-a/2, 16, -d/2], [x+a/2, 22, d/2], side+'_upper_arm')
        add(side+'_forearm', side+'_forearm', [x-a/2, 11, -d/2], [x+a/2, 16, d/2], side+'_forearm')
        if legs:
            x = s*(l/2+.15)
            add(side+'_thigh', side+'_thigh', [x-l/2, 6, -d/2], [x+l/2, 12, d/2], side+'_thigh')
            add(side+'_shin', side+'_shin', [x-l/2, 0, -d/2], [x+l/2, 6, d/2], side+'_shin')
    return p


def add_component(key, typ, variants, occupies, tags=None, professions=None, conflicts=None, hides=None, fallback=None, texture=None):
    asset_id = 'realmfolk:'+key
    regions = sorted({b['region'] for bs in variants.values() for b in bs})
    data = {'schema': 'realmfolk_component.draft1', 'id': asset_id, 'type': typ, 'skeleton': 'realmfolk:humanoid_v1',
            'variants': variants, 'material': texture or key.replace('/', '_')}
    data['materials'] = {region: {'texture': 'realmfolk:appearance/textures/'+data['material']+'__'+region+'.png',
                                  'regionMask': 'realmfolk:appearance/textures/'+data['material']+'__'+region+'_regions.png',
                                  'tintRegion': region} for region in regions}
    COMPONENTS[asset_id] = data
    dump('source/components/'+key.replace('/', '_')+'.json', data)
    return {'id': asset_id, 'type': typ, 'model': 'realmfolk:appearance/models/'+key+'.rfmodel.json' if variants else None,
            'texture': 'realmfolk:appearance/textures/'+data['material']+'.png', 'materials': data['materials'],
            'supports': {'skeletons': ['realmfolk:humanoid_v1'], 'bodies': list(variants) if variants else list(BODIES)},
            'occupies': occupies, 'styleTags': tags or ['shared'], 'professionTags': professions or ['any'], 'weight': 1,
            'colorRegions': sorted({b['region'] for bs in variants.values() for b in bs}) if variants else ['skin'],
            'excludes': conflicts or [], 'hideRules': hides or [], 'fallback': fallback,
            'lod': {'maxCubes': max([len(v) for v in variants.values()] or [0])},
            'status': 'offline_prototype', 'license': 'GPL-3.0-or-later'}


def geometry():
    entries = []
    for body in BODIES:
        entries.append(add_component('body/'+body, 'body', {body: body_boxes(body)}, ['body'], fallback=None if body=='standard' else 'realmfolk:body/standard', texture='body'))
    entries.append(add_component('skin/neutral', 'skin', {}, [], fallback=None, texture='skin'))
    for face in ['calm', 'freckled']:
        entries.append(add_component('face/'+face, 'face', {}, ['face_overlay'], fallback=None if face=='calm' else 'realmfolk:face/calm'))
    for eye in ['open', 'narrow']:
        entries.append(add_component('eyes/'+eye, 'eyes', {}, ['eye_overlay'], fallback=None if eye=='open' else 'realmfolk:eyes/open'))
    for hair in ['cropped', 'bob', 'tied']:
        variants = {}
        for body in BODIES:
            p = [box('crown', 'head', [-4.18,28.6,-4.18], [4.18,30.25,4.18], 'hair', 'head', 'hair')]
            if hair == 'cropped':
                p += [box('side_r','head',[-4.2,26,-2],[ -3.95,28.6,4.1],'hair','torso','hair'),
                      box('side_l','head',[3.95,26,-2],[4.2,28.6,4.1],'hair','torso','hair')]
            elif hair == 'bob':
                p += [box('side_r','head',[-4.4,22.5,-1.8],[-3.9,28.6,4.3],'hair','torso','hair'),
                      box('side_l','head',[3.9,22.5,-1.8],[4.4,28.6,4.3],'hair','torso','hair'),
                      box('back','head',[-3.9,22.5,3.9],[3.9,28.6,4.4],'hair','torso','hair')]
            else:
                p += [box('back','head',[-4.1,24,3.9],[4.1,28.6,4.4],'hair','torso','hair'),
                      box('tie','hair_tail',[-1.45,22.8,4.3],[1.45,24,5.7],'trim'),
                      box('tail','hair_tail',[-1.2,18.8,4.4],[1.2,22.8,5.5],'hair','torso','hair')]
            variants[body] = p
        entries.append(add_component('hair/'+hair,'hair',variants,['hair_crown','hair_sides']+(['hair_back_long'] if hair=='tied' else [])))
    entries.append(add_component('beard/short','beard',{b:[box('beard','head',[-3,22,-4.3],[3,24.2,-3.95],'hair','torso','hair')] for b in BODIES},['chin']))
    for ear in ['rounded','pointed']:
        variants = {}
        for body in BODIES:
            p=[]
            for s in [-1,1]:
                if ear=='rounded':
                    lo,hi=sorted([s*4,s*4.7])
                    p.append(box('ear_'+str(s),'head',[lo,24.3,-.8],[hi,26,.5],'skin','torso','skin'))
                else:
                    for i in range(3):
                        lo,hi=sorted([s*(4+i*.65),s*(4.8+i*.65)])
                        p.append(box('ear_'+str(s)+'_'+str(i),'head',[lo,24.5+i*.5,-.6],[hi,26.5+i*.35,.5],'skin','torso','skin'))
            variants[body]=p
        entries.append(add_component('ears/'+ear,'ears',variants,['ears'],fallback=None if ear=='rounded' else 'realmfolk:ears/rounded'))
    base = {b:body_boxes(b,.15,'secondary',head=False) for b in BODIES}
    for bs in base.values():
        for c in bs:
            if c['name'].endswith('_forearm'): c['from'][1]=12.5
            if c['name'].endswith(('_thigh','_shin')):
                c['from'][0]+=.08;c['to'][0]-=.08;c['region']='leather'
            if c['name'].endswith('_shin'):c['from'][1]=.05
    entries.append(add_component('base/tunic','base_clothing',base,['base_clothing'],fallback=None))
    for style in PAL:
        variants={}
        for b,(w,d,a,l) in BODIES.items():
            p=[]
            if style=='academy':
                p += [box('robe_bodice','spine',[-w/2-.3,12,-d/2-.3],[w/2+.3,21.5,d/2+.3],'cloth')]
                # An open-front, open-back split robe: each leg's panel follows its own thigh and shin.
                for side,s in [('right',-1),('left',1)]:
                    x=s*(l/2+.15)
                    for part,bot,top in [('thigh',6,12),('shin',.65,6)]:
                        p.append(box('robe_'+side+'_'+part,side+'_'+part,[x-l/2+(.2 if s==1 else -.3),bot,-d/2-.4],
                                     [x+l/2+(.3 if s==1 else -.2),top,-d/2-.12],'cloth',side+'_'+part))
                # Stole is two strips; no rigid full-body skirt or rear chair flap.
                for s in [-1,1]:
                    p.append(box('stole_'+str(s),'spine',[s*w*.24-.5,12.5,-d/2-.55],[s*w*.24+.5,21.6,-d/2-.32],'trim'))
            elif style=='steam':
                for s in [-1,1]:
                    x0,x1=sorted([s*.8,s*(w/2+.35)])
                    p.append(box('jacket_front_'+str(s),'spine',[x0,12,-d/2-.4],[x1,21.4,-d/2-.15],'cloth'))
                    p.append(box('lapel_'+str(s),'spine',[s*1.2-.35,18.5,-d/2-.65],[s*1.2+.35,21.5,-d/2-.4],'trim'))
                p.append(box('jacket_back','spine',[-w/2-.35,12,d/2+.15],[w/2+.35,21.4,d/2+.4],'cloth'))
                for side,s in [('right',-1),('left',1)]:
                    x=s*(w+a)/2
                    p.append(box('sleeve_'+side,side+'_upper_arm',[x-a/2-.3,16.3,-d/2-.3],[x+a/2+.3,21.5,d/2+.3],'cloth',side+'_upper_arm'))
            else:
                p.append(box('wrap_bodice','spine',[-w/2-.3,12,-d/2-.3],[w/2+.3,21.5,d/2+.3],'cloth'))
                for i in range(5):
                    y=13+i*1.5; x=-w*.3+i*w*.13
                    p.append(box('diagonal_leaf_'+str(i),'spine',[x-.65,y,-d/2-.58],[x+.65,y+1.5,-d/2-.32],'secondary'))
                for side,s in [('right',-1),('left',1)]:
                    x=s*(l/2+.15)
                    p.append(box('split_leaf_'+side,side+'_thigh',[x-l/2-.25,7,-d/2-.42],[x+l/2+.25,12,-d/2-.16],'cloth',side+'_thigh'))
            variants[b]=p
        entries.append(add_component('outer/'+style, 'outer_clothing', variants, ['outer_clothing'], [style], fallback=None))
    variants={}
    for b in BODIES:
        variants[b]=[box('cap_brim','head',[-4.7,30.45,-4.7],[4.7,30.85,4.7],'cloth','head'),
                     box('cap_crown','head',[-3.8,30.85,-3.8],[3.8,32.1,3.8],'cloth','torso'),
                     box('cap_badge','head',[-.5,30.86,-3.95],[.5,31.75,-3.8],'metal')]
    entries.append(add_component('accessory/scholar_cap','headwear',variants,['headwear'],['academy'],['scholar','any'],
                                 conflicts=[{'withTag':'hair_tall','action':'reject'}],
                                 hides=[{'poses':['sleep'],'action':'hide_geometry','restoreOnExit':True}]))
    entries.append(add_component('accessory/goggles','eyewear',{b:[
        box('rim_r','head',[-3.5,25.5,-4.7],[-.5,28,-4.3],'metal'),
        box('rim_l','head',[.5,25.5,-4.7],[3.5,28,-4.3],'metal'),
        box('lens_r','head',[-3.1,25.9,-4.78],[-.9,27.6,-4.68],'lens'),
        box('lens_l','head',[.9,25.9,-4.78],[3.1,27.6,-4.68],'lens'),
        box('bridge','head',[-.5,26.2,-4.6],[.5,26.8,-4.3],'metal')] for b in BODIES},['eyewear'],['steam']))
    for accessory in ['belt','pouch','backpack','badge','scarf']:
        variants={}
        for b,(w,d,a,l) in BODIES.items():
            if accessory=='belt':
                p=[box('belt','pelvis',[-w/2-.45,12,-d/2-.6],[w/2+.45,13,d/2+.6],'leather'),
                   box('buckle','pelvis',[-.65,12,-d/2-.85],[.65,13.1,-d/2-.6],'metal')]
            elif accessory=='pouch':
                p=[box('pouch','pelvis',[-w/2-2,10.5,-1.8],[-w/2-.35,13.1,1.8],'leather'),
                   box('flap','pelvis',[-w/2-2.1,12.1,-1.95],[-w/2-.25,13.25,-1.75],'metal')]
            elif accessory=='backpack':
                p=[box('pack','spine',[-3,13,d/2+.7],[3,20,d/2+3.5],'leather'),
                   box('pack_flap','spine',[-3.15,18,d/2+3.5],[3.15,20.2,d/2+3.8],'cloth')]
                for s in [-1,1]:
                    p.append(box('strap_'+str(s),'spine',[s*w*.3-.3,14,-d/2-.7],[s*w*.3+.3,21.8,-d/2-.5],'leather'))
            elif accessory=='badge':
                p=[box('badge','spine',[-w/2+.65,18,-d/2-.9],[-w/2+1.8,19.6,-d/2-.65],'metal')]
            else:
                p=[box('scarf_front','spine',[-2.8,20.5,-d/2-.8],[2.8,22,-d/2-.4],'secondary'),
                   box('scarf_tail','spine',[1.1,17,-d/2-.82],[2.3,20.5,-d/2-.5],'secondary')]
            variants[b]=p
        conflicts=[{'withSlot':'cape','action':'reject'},{'withSlot':'hair_back_long','action':'reject'}] if accessory=='backpack' else []
        hides=[{'poses':['sit','sleep'],'action':'hide_geometry','restoreOnExit':True}] if accessory in ['backpack','pouch'] else []
        slots={'belt':['waist'],'pouch':['hip_r'],'backpack':['back'],'badge':['chest_badge'],'scarf':['neckwear']}[accessory]
        entries.append(add_component('accessory/'+accessory,'accessory',variants,slots,conflicts=conflicts,hides=hides))
    # Tags are asset traits, never species predicates.
    for e in entries:
        e['compatibilityTags'] = ['hair_low'] if e['id'].startswith('realmfolk:hair/') else []
        if e['id']=='realmfolk:hair/tied':
            e['compatibilityTags'].append('hair_back_long')
            e['hideRules']=[{'poses':['sit','sleep'],'bones':['hair_tail'],'action':'hide_geometry','restoreOnExit':True,
                              'meaning':'束发状态隐藏垂尾，后脑发壳保留；不更换持久化发型 ID'}]
    dump('draft/catalog.json',{'schema':'realmfolk_appearance_catalog.draft1','runtimeSupported':False,'assets':entries})
    CATALOG.update({e['id']: e for e in entries})
    return entries


def texture_files():
    # Grey shade and red-channel region-index mask are separate editable PNGs.
    for asset in COMPONENTS.values():
        mat=asset['material']
        p=ROOT/'source/textures'/f'{mat}.png'
        if mat in ['skin','face_calm','face_freckled','eyes_open','eyes_narrow']:
            continue
        im=Image.new('RGBA',(128,128)); mask=Image.new('RGBA',(128,128))
        di,dm=ImageDraw.Draw(im),ImageDraw.Draw(mask)
        for bs in asset['variants'].values():
            for b in bs:
                for face,rect in b['uv'].items():
                    x,y,x1,y1=rect; region=b['region']
                    # Each texture has one material; meshes may select a different logical region.
                    di.rectangle((x,y,x1-1,y1-1),fill=(224,224,224,255))
                    dm.rectangle((x,y,x1-1,y1-1),fill=(REGIONS[region],0,0,255))
                    if b['detail']=='hair':
                        for xx in range(x,x1,3):
                            di.line((xx,y,xx,y1-1),fill=(180,180,180,255))
                    elif b['detail']=='fabric':
                        di.line((x,y1-1,x1-1,y1-1),fill=(165,165,165,255))
                        for xx in range(x+1,x1-1,3):
                            di.point((xx,y1-2),fill=(250,250,250,255))
                    else:
                        di.line((x,y1-1,x1-1,y1-1),fill=(205,205,205,255))
        save_image('source/textures/'+mat+'.png',im)
        # Independent per-region material bindings prevent one reused UV cell from
        # assigning two different tint indices. Mixed materials never share one mask.
        for region in asset['materials']:
            save_image('source/textures/'+mat+'__'+region+'.png',im)
            rm=Image.new('RGBA',(128,128)); ra=np.array(rm); alpha=np.array(im)[:,:,3]
            ra[:,:,0]=REGIONS[region];ra[:,:,3]=alpha
            save_image('source/textures/'+mat+'__'+region+'_regions.png',Image.fromarray(ra))
    # skin uses the body UV, overlaid by face and eyes before rendering.
    body=Image.open(ROOT/'source/textures/body.png').convert('RGBA')
    save_image('source/textures/skin.png',body)
    x,y,_,_=UV['head']['north']
    for face in ['calm','freckled']:
        im=Image.new('RGBA',(128,128)); d=ImageDraw.Draw(im)
        d.line((x+5,y+7,x+6,y+7),fill=(86,57,51,210))
        d.line((x+2,y+2,x+4,y+2),fill=(64,51,43,210));d.line((x+7,y+2,x+9,y+2),fill=(64,51,43,210))
        if face=='freckled':
            for xx,yy in [(2,6),(3,5),(8,5),(9,6)]:d.point((x+xx,y+yy),fill=(99,58,44,165))
        save_image('source/textures/face_'+face+'.png',im)
    for eye in ['open','narrow']:
        im=Image.new('RGBA',(128,128));d=ImageDraw.Draw(im)
        for xx in [2,7]:
            d.rectangle((x+xx,y+3,x+xx+2,y+4 if eye=='open' else y+3),fill=(236,230,213,255))
            d.point((x+xx+1,y+3),fill=(255,255,255,255))
            if eye=='open':d.point((x+xx+1,y+4),fill=(55,75,65,255))
        save_image('source/textures/eyes_'+eye+'.png',im)
    # Annotated guide is a preview, not a texture.
    guide=Image.new('RGB',(1024,1024),'#16202c'); d=ImageDraw.Draw(guide)
    for i,part in enumerate(PARTS):
        for face,(x,y,x1,y1) in UV[part].items():
            d.rectangle((x*8,y*8,x1*8-1,y1*8-1),outline='#b5bacc',width=2)
            d.text((x*8+3,y*8+4),part.replace('right_','R.').replace('left_','L.')+'\n'+face,font=font(11),fill='#e0d4b5')
    save_image('preview/uv_guide.png',guide)
    dump('source/uv_layout.json',{'resolution':[128,128],'rectSemantics':'half-open pixels [u0,v0,u1,v1]','parts':UV,
                                 'note':'Empty 4px horizontal/2px vertical gutters per cell. Unequal aspect per body is intentional; no vanilla skin compatibility.'})


def snapshots():
    pools={'schema':'realmfolk_appearance_pools.draft1','runtimeSupported':False,
      'order':['civilization_default','city_patch','profession_bias','individual_draw','key_person_override'],
      'merge':{'object':'recursive','array':'replace','omitted':'inherit','null':'reject; use explicit empty array'},
      'seed':'SHA256(worldSeed|cityInstance|citizenUUID|appearanceVersion|slot); sorted IDs; per-slot stream',
      'palettes':PAL,'skinPalette':SKIN,'hairPalette':HAIR,
      'civilizations':{},
      'cityPatches':{'academy_mixed_demo':{'style':'academy','migrationShare':0.25,'allowedStyles':['academy','steam','elven'],
                       'note':'Illustrative city composition only; species is not inferred from style.'}},
      'professionBias':{'resident':{'maxAccessories':1},'laborer':{'prefer':['realmfolk:accessory/belt','realmfolk:accessory/pouch'],
                          'reject':['realmfolk:accessory/scholar_cap'],'maxAccessories':2},
                        'scholar':{'prefer':['realmfolk:accessory/badge','realmfolk:accessory/scholar_cap'],'maxAccessories':3}},
      'solver':{'order':['body','skin','face','eyes','ears','hair','beard','base','outer','accessories'],
                'maxAttemptsPerSlot':16,'requiredFallbacks':{'body':'realmfolk:body/standard','skin':'realmfolk:skin/neutral',
                  'face':'realmfolk:face/calm','eyes':'realmfolk:eyes/open','ears':'realmfolk:ears/rounded','base':'realmfolk:base/tunic'},
                'optionalFailure':'omit and record reason','persistResolvedIds':True,'palettePolicy':'one palette per outfit; skin/hair/iris independent'}}
    for style in PAL:
        pools['civilizations'][style]={'styleTags':[style,'shared'],'bodyWeights':{'standard':6,'slender':3,'sturdy':3},
             'skinWeights':{s:1 for s in SKIN if s!='fantasy_ash'},'earWeights':{'rounded':4,'pointed':1},
             'hairWeights':{'cropped':3,'bob':2,'tied':2},'palette':style,'outer':'realmfolk:outer/'+style,
             'speciesPolicy':'separate authored demographic pool; never derive from architecture', 'presentationWeights':{'neutral':1,'masculine':1,'feminine':1}}
    dump('draft/pools.json',pools)
    all_snaps=[]
    for style in PAL:
        for body in BODIES:
            hair={'academy':'bob','steam':'cropped','elven':'tied'}[style]
            access={'academy':['scholar_cap','belt','badge'],'steam':['goggles','belt','pouch','backpack'],'elven':['scarf','belt','badge']}[style]
            # Deliberate cross-body/color examples; no species mapping.
            p={'schema':'realmfolk_resolved_appearance.draft1','citizenDemoId':f'demo_{style}_{body}',
               'appearanceVersion':1,'catalogRevision':'draft-1','skeleton':'realmfolk:humanoid_v1',
               'body':'realmfolk:body/'+body,'skinAsset':'realmfolk:skin/neutral','skin':{'standard':'warm_ochre','slender':'deep_umber','sturdy':'light_peach'}[body],
               'face':'realmfolk:face/'+('freckled' if body=='slender' else 'calm'), 'eyes':'realmfolk:eyes/open',
               'ears':'realmfolk:ears/'+('pointed' if body=='slender' else 'rounded'),'hair':'realmfolk:hair/'+hair,
               'hairColor':{'academy':'ink','steam':'umber','elven':'silver'}[style],'eyeColor':'#5c867f',
               'base':'realmfolk:base/tunic','outer':'realmfolk:outer/'+style,'palette':style,
               'accessories':['realmfolk:accessory/'+a for a in access],
               'visualScale':1.0,'presentation':'neutral','species':'unspecified',
               'generation':{'method':'authored_reference_combination','notRandomPopulation':True},
               'immutable':True,'migrationPolicy':'preserve_ids; missing optional draw fallback only, do not overwrite original IDs'}
            p['colors']={**PAL[style], 'skin':SKIN[p['skin']], 'hair':HAIR[p['hairColor']], 'eye':p['eyeColor']}
            p['optionalSlots']={'beard':[]}
            dump(f'draft/resolved/{style}_{body}.json',p);all_snaps.append(p)
    return all_snaps


def pose(name, phase=0, body='standard'):
    p={};t=[0,0,0]
    if name=='walk':
        angle=math.sin(phase*2*math.pi)*28
        p={'right_thigh':[angle,0,0],'left_thigh':[-angle,0,0],
           'right_shin':[max(0,-angle)*.8,0,0],'left_shin':[max(0,angle)*.8,0,0],
           'right_upper_arm':[-angle*.8,0,0],'left_upper_arm':[angle*.8,0,0],
           'right_forearm':[-8,0,0],'left_forearm':[-8,0,0]}
    if name=='sit':
        p={'right_thigh':[90,0,-4],'left_thigh':[90,0,4],'right_shin':[-90,0,0],'left_shin':[-90,0,0],
           'right_upper_arm':[30,0,-10],'left_upper_arm':[30,0,10],'right_forearm':[45,0,0],'left_forearm':[45,0,0],
           'hair_tail':[-25,0,0]};t=[0,-5,0]
    if name=='sleep':p={'root':[90,0,0],'hair_tail':[0,0,0]};t=[0,8.1,-15]
    if name in ['hold','eat','work']:
        p={'right_upper_arm':[45,0,-8],'right_forearm':[50,0,0]}
    if name=='eat':
        p={'right_upper_arm':[80,{'standard':-40,'slender':-34,'sturdy':-52}[body],0],
           'right_forearm':[65+math.sin(phase*2*math.pi)*4,0,0],'head':[3,0,0]}
    if name=='work':
        p={'spine':[8,0,0],'right_upper_arm':[50+math.sin(phase*2*math.pi)*24,0,-10],'right_forearm':[40,0,0],
           'left_upper_arm':[25,0,10],'left_forearm':[40,0,0]}
    return p,np.array(t,dtype=float)


def pose_translations(name):
    # A head offset supports the supine pose without sinking the thick head into the mattress.
    return {'head':[0,0,-2]} if name=='sleep' else {}


def font(size):
    for f in ['C:/Windows/Fonts/msyh.ttc','C:/Windows/Fonts/segoeui.ttf']:
        if Path(f).exists():return ImageFont.truetype(f,size)
    return ImageFont.load_default(size=size)



def main():
    import argparse
    global ROOT
    parser=argparse.ArgumentParser(description='Initialize sample authoring sources in an empty library ONLY.')
    parser.add_argument('--root',type=Path,default=ROOT)
    args=parser.parse_args();ROOT=args.root.resolve()
    if (ROOT/'source').exists() or (ROOT/'draft').exists():
        parser.error('Refusing initialization: source/ or draft/ already exists. Use build_samples.py to rebuild.')
    dump('source/rig.json',{'schema':'realmfolk_rig.draft1','id':'realmfolk:humanoid_v1','unit':'1/16 block',
       'axes':'+X character-left; +Y up; front -Z; right-handed','restHeight':30,'rotationOrder':'local X then Y then Z; Rz*Ry*Rx',
       'pivots':'global rest coordinates; convert to parent local on load','bodyDimensions':BODIES,
       'bodyDimensionOrder':['torsoWidth','torsoDepth','armWidth','legWidth'],
       'variants':{b:rig(b) for b in BODIES},'runtimeSupported':False})
    geometry();texture_files();snapshots()
    actions=['stand','walk','sit','sleep','hold','eat','work']
    poses={'schema':'realmfolk_pose_fixture.draft1','runtimeSupported':False,'angles':'degrees local XYZ','variants':{}}
    for body in BODIES:
        poses['variants'][body]={}
        for name in actions:
            sample={'rotations':pose(name,.18,body)[0],'rootTranslation':pose(name,.18,body)[1].tolist(),'boneTranslations':pose_translations(name)}
            if name in ['walk','eat','work']:
                sample['clip']={'loop':True,'frames':[{'rotations':pose(name,i/8,body)[0],
                    'rootTranslation':pose(name,i/8,body)[1].tolist(),'boneTranslations':pose_translations(name)} for i in range(8)]}
            poses['variants'][body][name]=sample
    dump('source/poses.json',poses)
    print('Initialized authoring sources only. Next: build_samples.py --root',ROOT)

if __name__=='__main__':main()
