"""Acceptance: an authored cuboid corner and PNG pixel survive real builds.
Uses isolated copies of the current author sources, never edits the user's originals.
SPDX-License-Identifier: GPL-3.0-or-later
"""
from pathlib import Path
import base64
import hashlib
import io
import json
import shutil
import subprocess
import sys
import tempfile
import numpy as np
from PIL import Image, ImageChops
import build_samples as b

LIBRARY=b.ROOT
EVIDENCE=LIBRARY/'verification/edit_rebuild'


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def run():
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    scratch=LIBRARY/'build/scratch';scratch.mkdir(parents=True,exist_ok=True)
    assert scratch.resolve().is_relative_to(LIBRARY.resolve())
    original=b.input_hashes()
    records=[]
    with tempfile.TemporaryDirectory(prefix='edit_rebuild_',dir=scratch) as directory:
        fixture=Path(directory);assert fixture.resolve().is_relative_to(scratch.resolve())
        for folder in ['source','draft','authoring']:shutil.copytree(LIBRARY/folder,fixture/folder)
        b.ROOT=fixture
        model=fixture/'source/components/outer_academy.json'
        texture=fixture/'source/textures/outer_academy__cloth.png'
        original_model=model.read_bytes();original_texture=texture.read_bytes()
        original_json=json.loads(original_model)
        corner=original_json['variants']['standard'][0]['from'][0]
        original_pixel=Image.open(texture).getpixel((6,17))
        for stage,vertex,pixel in [('before',False,False),('vertex_only',True,False),('pixel_only',False,True),('both',True,True)]:
            model.write_bytes(original_model);texture.write_bytes(original_texture)
            if vertex:
                data=json.loads(original_model);data['variants']['standard'][0]['from'][0]=corner-.25
                model.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
            if pixel:
                with Image.open(texture) as img:
                    edited=img.convert('RGBA');edited.putpixel((6,17),(0,0,0,255));edited.save(texture)
            before=b.input_hashes();b.build('academy_standard');after=b.input_hashes()
            assert before==after,'Build overwrote author input at '+stage
            exported=fixture/'export/proposed/assets/realmfolk/appearance/models/outer/academy.rfmodel.json'
            data=json.loads(exported.read_text(encoding='utf-8'))
            assert data['variants']['standard'][0]['from'][0]==corner-(.25 if vertex else 0)
            exported_texture=fixture/'export/proposed/assets/realmfolk/appearance/textures/outer_academy__cloth.png'
            assert sha(exported_texture)==sha(texture),'Export PNG is not byte-identical to edited source'
            assert Image.open(exported_texture).getpixel((6,17))==((0,0,0,255) if pixel else original_pixel)
            project=fixture/'build/projects/academy_standard.bbmodel'
            pj=json.loads(project.read_text(encoding='utf-8'))
            cube=next(c for c in pj['elements'] if c['name']=='outer/academy.robe_bodice')
            assert cube['from'][0]==corner-(.25 if vertex else 0),'Generated editor geometry stale'
            tex=pj['textures'][cube['faces']['north']['texture']]
            im=Image.open(io.BytesIO(base64.b64decode(tex['source'].split(',',1)[1])))
            if pixel:assert im.getpixel((6,17))[:3]==(0,0,0),'Baked editor pixel stale'
            preview=fixture/'preview/academy_standard.png'
            folder=EVIDENCE/stage;folder.mkdir(exist_ok=True)
            for p in [model,texture,exported,exported_texture,project,preview]:
                dest=folder/p.relative_to(fixture);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest)
            records.append({'stage':stage,'sourceHashesBefore':before,'sourceHashesAfter':after,
                'fromCornerX':data['variants']['standard'][0]['from'][0],
                'texturePixel':list(Image.open(exported_texture).getpixel((6,17))),
                'exportModelSha256':sha(exported),'exportTextureSha256':sha(exported_texture),'projectSha256':sha(project),'previewSha256':sha(preview)})
        baseline=Image.open(EVIDENCE/'before/preview/academy_standard.png').convert('RGB')
        changed={}
        for stage in ['vertex_only','pixel_only','both']:
            image=Image.open(EVIDENCE/stage/'preview/academy_standard.png').convert('RGB')
            difference=ImageChops.difference(baseline,image)
            changed[stage]=int(np.any(np.array(difference)!=0,axis=2).sum())
            assert changed[stage]>0,stage+' failed to affect rendered preview'
            difference.save(EVIDENCE/(stage+'_preview_diff.png'))
        b.build('academy_standard')
        assert sha(fixture/'preview/academy_standard.png')==records[-1]['previewSha256'],'Repeat build is not deterministic'
        prior=b.input_hashes()
        refusal=subprocess.run([sys.executable,str(LIBRARY/'tools/init_samples.py'),'--root',str(fixture)],capture_output=True,text=True)
        assert refusal.returncode!=0 and b.input_hashes()==prior,'Initializer overwrote existing input tree'
        empty=fixture/'empty';empty.mkdir();b.ROOT=empty
        try:b.build()
        except FileNotFoundError:pass
        else:raise AssertionError('Missing sources silently initialized')
        assert not list(empty.iterdir()),'Builder wrote to uninitialized root'
        fresh=fixture/'fresh'
        seeded=subprocess.run([sys.executable,str(LIBRARY/'tools/init_samples.py'),'--root',str(fresh)],capture_output=True,text=True)
        assert seeded.returncode==0,seeded.stderr
        b.ROOT=fresh;b.build('academy_standard')
    b.ROOT=LIBRARY
    assert b.input_hashes()==original,'Acceptance test changed real authoring inputs'
    report={'schema':'realmfolk_edit_rebuild_acceptance.v1','passed':True,
       'fixture':'isolated copy of current canonical sources, not synthetic code-only mocks',
       'edit':{'component':'realmfolk:outer/academy','body':'standard','cube':'robe_bodice','corner':'from','axis':'X','before':corner,'after':corner-.25,
               'note':'RFH1 cuboids store from/to corner points. Editing this corner coordinate changes the cuboid face, not an independently deformable mesh vertex.',
               'texture':'outer_academy__cloth.png','pixel':[6,17],'beforePixel':list(original_pixel),'afterPixel':[0,0,0,255]},
       'stages':records,'changedPreviewPixelsVsBaseline':changed,'repeatedBuildDeterministic':True,
       'initializerRefusesExistingTree':True,'builderRefusesMissingSources':True,'initializerBuildsFreshTree':True,'realSourcesUnchanged':True}
    (LIBRARY/'verification/edit_rebuild.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('PASS: corner/pixel retained; RFH1, PNG, embedded editor project and rendered preview change independently.',changed)


if __name__=='__main__':run()
