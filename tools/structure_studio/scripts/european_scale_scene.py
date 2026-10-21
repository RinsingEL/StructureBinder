"""Build a reproducible six-building scale fixture, never a runtime city template."""
import sys
from pathlib import Path
from copy import deepcopy
import json

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from studio.european_village import get_builders
from studio.model import Model
from studio.server import TOOL
from studio.validate import validate


def main():
    builders=get_builders()
    layout=[('EU-27-v01',3,5),('EU-28-v01',20,5),('EU-33-v01',41,5),
            ('EU-40-v01',3,27),('EU-42-v01',24,27),('EU-43-v01',43,27)]
    m=Model('EU-SCALE-SCENE','欧洲紧凑建筑 · 同尺度对照场景',(60,24,48),civilization='fixture',role='structure')
    m.box((0,0,0),(59,0,47),'dirt');m.box((0,1,0),(59,1,47),'grass_block')
    for z in (2,24):m.box((0,1,z),(59,1,z+2),'gravel')
    for key,dx,dz in layout:
        part=builders[key]()
        for (x,y,z),state in part.blocks.items():m.blocks[(x+dx,y,z+dz)]=state
        for (x,y,z),nbt in part.block_entities.items():m.block_entities[(x+dx,y,z+dz)]=deepcopy(nbt)
        for p in part.meta['points']:
            p=deepcopy(p);p['id']=key+'_'+p['id']
            for field in ('pos','approach'):
                if field in p:p[field][0]+=dx;p[field][2]+=dz
            m.meta['points'].append(p)
    m.meta.update(ground_plane={'y':2,'note':'对照场景统一地面，仅用于离线同尺度查看。'},
                  floors=[{'name':'统一地面','y':1,'max_y':5}],roof_min_y=6,
                  function_terms=['尺度对照'],workplace_policy='authored',
                  terrain={'选址':'离线展示场景，不导入城市模板。'},
                  design_notes=['统一相机同时查看六栋实物；不是规划生成、人口验收或游戏证据。'])
    registry=json.loads((TOOL/'.cache/registry.json').read_text(encoding='utf-8'))
    dest=TOOL/'fixtures/EU-SCALE-SCENE';m.export(dest,registry)
    result=validate(dest,registry)
    print(json.dumps({k:result[k] for k in ('passed','warnings','errors')},ensure_ascii=False))


if __name__=='__main__':main()
