"""Translate authored group layouts onto verified dry ground; never move individual members."""
import json
import subprocess
import sys
from pathlib import Path
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import workbench as w


def main(case):
    folder = w.ROOT / 'runtime/city-subjects-20261010' / case / 'site-selection'
    folder.mkdir(parents=True, exist_ok=True)
    path = HERE / (case + '.json')
    scene = w.read(path)
    request = dict(scene, assets=w.catalog(), terrainPath=str(w.EXPERIMENT/'data/D3地形原始数据.json'))
    w.write(folder/'request.json', request)
    subprocess.run(['C:/Program Files/Java/jdk-17/bin/java.exe', '-Dfile.encoding=UTF-8', '-cp',
                    (w.ROOT/'build/city-workbench-classpath.txt').read_text(),
                    'com.rinsing.geomantia.systems.city.application.CityArrayExperiment',
                    str(folder/'request.json'), str(folder/'geometry.json')], check=True)
    result = w.read(folder/'geometry.json')
    if not result['ok']:
        raise ValueError(result)
    members = result['members']
    if any(m['collisionsWith'] for m in members):
        raise ValueError('Authored layout has a collision.')
    dem = w.read(w.ROOT/'runtime/campus-workbench/terrain/dem-step1.json')
    b = dem['bounds']; width = b['maxX']-b['minX']+1; depth = b['maxZ']-b['minZ']+1
    heights = np.array(dem['groundY']).reshape(depth, width)
    wet = np.array([v is not None for v in dem['waterSurfaceY']], dtype=np.int32).reshape(depth,width)
    d3 = w.read(w.EXPERIMENT/'data/D3地形原始数据.json')
    for c in d3['cells']:
        if c.get('water') or not c.get('sampled', True):
            x=c['blockMinX']-b['minX']; z=c['blockMinZ']-b['minZ']
            wet[z:z+16,x:x+16]=1
    integral = np.pad(wet, ((1,0),(1,0))).cumsum(0).cumsum(1)
    rects = [(m['originBlock']['x'],m['originBlock']['z'],m['width'],m['depth']) for m in members]
    candidates=[]
    for dz in range(-256,257,8):
        for dx in range(-256,257,8):
            valid=True
            ys=[]
            for x,z,sx,sz in rects:
                x0=x+dx-b['minX']-10; z0=z+dz-b['minZ']-10; x1=x0+sx+20; z1=z0+sz+20
                if x0<24 or z0<24 or x1>=width-24 or z1>=depth-24:
                    valid=False; break
                if integral[z1,x1]-integral[z0,x1]-integral[z1,x0]+integral[z0,x0]:
                    valid=False; break
                ys.append(float(np.median(heights[z0:z1,x0:x1])))
            if valid:
                score=(max(ys)-min(ys))*8 + (abs(dx)+abs(dz))*.03
                candidates.append((score,dx,dz,ys))
    if not candidates:
        raise ValueError('No dry site fits this complete layout; revise the design.')
    score,dx,dz,ys=min(candidates)
    children={i for c in scene['compositions'] for i in c['memberIds']}
    for n in scene['arrays']+scene['compositions']:
        if n.get('arrayId',n.get('compositionId')) not in children:
            n['anchorBlock']['x']+=dx; n['anchorBlock']['z']+=dz
    path.write_text(json.dumps(scene,ensure_ascii=False,indent=2),encoding='utf-8')
    w.write(folder/'selection.json',dict(translation=[dx,dz],candidateCount=len(candidates),
            medianGroundHeights=ys,method='Whole-layout translation; D3 and DEM dry footprints plus 10-block margin; prefer moderate relief.'))
    print(case, 'translation', dx,dz,'ground heights',ys,flush=True)


if __name__=='__main__':main(sys.argv[1])
