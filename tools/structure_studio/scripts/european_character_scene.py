"""Same-scale street and grounded viewer positions for four character studies."""
import sys,json
from pathlib import Path
from copy import deepcopy
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from studio.european_character import BUILDERS
from studio.model import Model
from studio.server import TOOL
from studio.validate import validate

m=Model('EU-CHARACTER-STREET','四栋特色重设计 · 同尺度街景',(98,28,39),civilization='fixture',role='structure')
m.box((0,0,0),(97,0,38),'dirt');m.box((0,1,0),(97,1,38),'grass_block')
m.box((0,1,4),(97,1,7),'gravel')
# The dock sits beside real water blocks in this authored fixture, not a claim of worldgen.
m.box((88,0,10),(97,0,38),'gravel');m.box((88,1,10),(97,1,38),'water')
layout=[('EU-27-v05',3),('EU-32-v02',24),('EU-07-v02',50),('EU-55-v02',75)]
for key,dx in layout:
    part=BUILDERS[key]()
    for (x,y,z),block in part.blocks.items():m.blocks[(x+dx,y,z+8)]=block
    for (x,y,z),nbt in part.block_entities.items():m.block_entities[(x+dx,y,z+8)]=deepcopy(nbt)
    for point in part.meta['points']:
        p=deepcopy(point);p['id']=key+'_'+p['id']
        for field in ('pos','approach'):
            if field in p:p[field][0]+=dx;p[field][2]+=8
        m.meta['points'].append(p)
    m.point(key+'_street_view','circulation',(dx+part.size[0]//2,2,1),'街上玩家高度观察',
            facing='north',look_at=[dx+part.size[0]//2,7,18])
m.meta.update(workplace_policy='authored',function_terms=['尺度对照'],
    ground_plane={'y':2,'note':'统一街面脚底Y=2，仅作者手工布置的对照场景。'},
    terrain={'选址':'离线手工尺度对照，不是生成结果。'},
    floors=[{'name':'街面','y':1,'max_y':5}],roof_min_y=25,
    design_notes=['四栋统一尺度；街面观察点眼高由Studio漫游相机给出，非真实游戏截图。'])
registry=json.loads((TOOL/'.cache/registry.json').read_text(encoding='utf-8'))
dest=TOOL/'fixtures/EU-CHARACTER-STREET';m.export(dest,registry);r=validate(dest,registry)
print(json.dumps({k:r[k] for k in ('passed','warnings','errors')},ensure_ascii=False))
