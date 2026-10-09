"""Check exported geometry and a manual wing/gallery/wing join, not city planning.

Run from the Studio folder: python scripts/check-academy-wings.py.
The join rotates actual NBT block states, then audits walking from one entrance.
"""
import json
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from studio.model import read_structure, write_json, sha256
from studio.navigation import audit
from studio.server import CATALOG, TOOL


ROOT=CATALOG/'R02_arcane_reborn'/'models'
AIR={'minecraft:air','minecraft:cave_air','minecraft:void_air'}
DIRECTIONS={'north':(0,-1),'south':(0,1),'west':(-1,0),'east':(1,0)}
registry=json.loads((TOOL/'.cache/registry.json').read_text(encoding='utf-8'))
models={}
reports=[]
for number in range(34,41):
    key=f'MG-{number:02d}-v01';folder=ROOT/key
    data=read_structure(folder/'structure.nbt')
    meta=json.loads((folder/'author.json').read_text(encoding='utf-8'))
    report=json.loads((folder/'validation.json').read_text(encoding='utf-8'))
    assert report['nbt_sha256']==meta['nbt_sha256']==data['sha256'],key
    assert report['passed'] and report['navigation']['passed'] and not report['warnings'],key
    grid={tuple(b['pos']):data['palette'][b['state']] for b in data['blocks']}
    for port in meta['connections']:
        x,y,z=port['pos'];dx,dz=DIRECTIONS[port['direction']]
        assert port['standing_y']==y and port['width']==3 and port['height']==4,(key,port)
        # At the edge and one step inward: solid threshold and 3x4 clear lane.
        for inward in (0,1):
            for lateral in range(-1,2):
                xx=x-dx*inward+(lateral if dz else 0)
                zz=z-dz*inward+(lateral if dx else 0)
                assert grid[(xx,y-1,zz)]['name'] not in AIR,(key,port,'floor')
                for dy in range(4):
                    assert grid.get((xx,y+dy,zz),{'name':'minecraft:air'})['name'] in AIR,(key,port,'clearance')
    assert (meta['campus_consumer']=='infrastructure')==(number>=39),key
    models[key]=(data,meta)
    reports.append({'id':key,'nbt_sha256':data['sha256'],'author_sha256':sha256(folder/'author.json'),
        'ports':len(meta['connections']),'highest_standing_y':report['navigation']['highest_standing_y'],
        'navigation_passed':True,'clearance_passed':True})


def rotated_state(value):
    """Clockwise quarter turn: (x,z)->(d-1-z,x), including directional states."""
    value={'name':value['name'],'properties':dict(value['properties'])};p=value['properties']
    clockwise={'north':'east','east':'south','south':'west','west':'north'}
    if p.get('facing') in clockwise:p['facing']=clockwise[p['facing']]
    if p.get('axis') in ('x','z'):p['axis']='z' if p['axis']=='x' else 'x'
    sides={d:p[d] for d in clockwise if d in p}
    for old,v in sides.items():p[clockwise[old]]=v
    return value


joins=[]
for connector in ('MG-39-v01','MG-40-v01'):
    wing,wingmeta=models['MG-34-v01'];gallery,gallerymeta=models[connector]
    leftwidth=wing['size'][0];length=gallery['size'][2];rightx=leftwidth+length
    # The rotated south port faces the left wing's east port, both at Z=16.
    zoffset=16-5;grid={}
    for data,rotate,offset in ((wing,False,(0,0)),(gallery,True,(leftwidth,zoffset)),(wing,False,(rightx,0))):
        for block in data['blocks']:
            x,y,z=block['pos']
            if rotate:x,z=data['size'][2]-1-z,x
            pos=(x+offset[0],y,z+offset[1])
            assert pos not in grid,('overlap',connector,pos)
            value=data['palette'][block['state']]
            grid[pos]=rotated_state(value) if rotate else value
    # Verify an uninterrupted supported centre line including both seams.
    for y in ((2,12) if connector=='MG-40-v01' else (2,)):
        for x in range(leftwidth-1,rightx+1):
            assert grid[(x,y-1,16)]['name'] not in AIR,(connector,'seam floor',x,y)
            for dy in range(4):assert grid.get((x,y+dy,16),{'name':'minecraft:air'})['name'] in AIR,(connector,'seam headroom',x,y)
    palette=[];indexes={};blocks=[]
    for pos,value in grid.items():
        token=(value['name'],tuple(sorted(value['properties'].items())))
        if token not in indexes:indexes[token]=len(palette);palette.append(value)
        blocks.append({'pos':list(pos),'state':indexes[token]})
    # Only one entry, so passing requires reaching the other wing via the gallery.
    points=[{'id':'left-entry','kind':'entrance','pos':[23,2,0],'approach':[23,2,2]},
            {'id':'right-ground','kind':'circulation','pos':[rightx+23,2,3]},
            {'id':'right-upper','kind':'circulation','pos':[rightx+23,12,16]}]
    combined={'size':[rightx+wing['size'][0],49,29],'palette':palette,'blocks':blocks}
    result=audit(combined,{'points':points},registry)
    assert result['passed'],(connector,result)
    joins.append({'connector':connector,'rotation_degrees':90,'placements':[
        {'id':'MG-34-v01','offset':[0,0,0]}, {'id':connector,'offset':[leftwidth,0,zoffset]},
        {'id':'MG-34-v01','offset':[rightx,0,0]}],
        'standing_y':[2,12] if connector=='MG-40-v01' else [2],
        'seams_passed':True,'navigation':result})
output=TOOL.parents[1]/'runtime/campus-workbench/academy-wings-check.json'
write_json(output,{'schema':'academy-wings.offline-check.v1','assets':reports,'manual_joins':joins,
    'scope':'实际导出 NBT 的端口净空及手工拼接通路；不代表自动规划或实机消费已接入。'})
print(json.dumps({'assets':len(reports),'manual_joins':len(joins),'passed':True,'output':str(output)},ensure_ascii=False))
