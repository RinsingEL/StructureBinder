"""Loopback campus workbench: actual NBT and production Java in a D3 virtual world."""
import argparse, copy, hashlib, json, os, subprocess, sys, threading, time
from functools import lru_cache
from pathlib import Path
from urllib.parse import urlsplit

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/structure_studio'))
from studio.server import Handler as StudioHandler, TOOL, asset_paths
from studio.model import read_structure, state
from http.server import ThreadingHTTPServer
from array_tools import ARRAY, COMPOSITION, schema, validate
from dem_tools import validate_dem, install as install_dem

EXPERIMENT=ROOT.parent/'designer_territoryMod/experiments/第三题_学院岛坐标布局_20261008'
OUTPUT=ROOT/'runtime/campus-workbench'
CATALOG=ROOT/'asset_catalogs/original_civilizations/R02_arcane_reborn/models'
SCENE=Path(__file__).with_name('academy-scene.json')
POLICY=Path(__file__).with_name('asset-consumer-overrides.json')
LOCK=threading.Lock()
DISTRICT=schema({'districtId':{'type':'string'},'name':{'type':'string'},'coreMemberId':{'type':'string'},
    'arrayIds':{'type':'array','minItems':1,'maxItems':32,'items':{'type':'string'}},'platform':{'type':'boolean'}},
    ['districtId','name','coreMemberId','arrayIds','platform'])
SCENE_SCHEMA=schema({'seed':{'type':'integer'},'greenery':{'type':'boolean'},
    'publicSpaces':{'type':'array','maxItems':32,'items':schema({
        'nodeId':{'type':'string'},'kind':{'type':'string','enum':['GARDEN','PLAZA']},
        'flowerBlock':{'type':'string'},'hedgeBlock':{'type':'string'},'treeLeaves':{'type':'string'}},['nodeId','kind'])},
    'terraceFeatures':{'type':'boolean'},'foundationMarginBlocks':{'type':'integer','minimum':4,'maximum':12},
    'terraceGroups':{'type':'array','minItems':1,'maxItems':32,'items':schema({
        'terraceId':{'type':'string'},'districtId':{'type':'string'},'coreMemberId':{'type':'string'},
        'arrayIds':{'type':'array','minItems':1,'items':{'type':'string'}},'styleRef':{'type':'string'},
        'datumOffsetBlocks':{'type':'integer','minimum':-16,'maximum':16}},['terraceId','districtId','coreMemberId','arrayIds'])},
    'terraceStyles':{'type':'array','maxItems':16,'items':schema({
        'styleId':{'type':'string'},'name':{'type':'string'},'railBlock':{'type':'string'},'postBlock':{'type':'string'},'capBlock':{'type':'string'},
        'postSpacing':{'type':'integer','minimum':4,'maximum':32},'minimumExposure':{'type':'integer','minimum':0,'maximum':16},
        'maximumCorners':{'type':'integer','minimum':0,'maximum':16},'cornerSpacing':{'type':'integer','minimum':8,'maximum':128},
        'cornerAssetId':{'type':'string'},'courtAssetId':{'type':'string'},
        'materials':schema({slot:{'type':'string'} for group in json.loads((ROOT/'src/main/resources/geomantia/city_surface_appearances.json').read_text(encoding='utf-8')).values() for slot in group['slots']},[])},['styleId'])},
    'terrainSource':{'type':'string','enum':['D3_INTERPOLATED','DEM_STEP_1']},
    'arrays':{'type':'array','minItems':1,'maxItems':32,'items':ARRAY},
    'compositions':{'type':'array','maxItems':32,'items':COMPOSITION},
    'districts':{'type':'array','minItems':1,'maxItems':12,'items':DISTRICT},
    'connections':{'type':'array','maxItems':32,'items':schema({'from':{'type':'string'},'to':{'type':'string'}},['from','to'])}},
    ['arrays','compositions','districts','connections'])


class RevisionConflict(ValueError):pass


def allows_write_origin(headers,port):
    host=headers.get('Host','');origin=headers.get('Origin','')
    allowed={f'http://127.0.0.1:{port}',f'http://localhost:{port}'}
    access=OUTPUT/'access.json'
    if access.exists():
        try:allowed.update(read(access).get('allowedOrigins',[]))
        except (ValueError,OSError,TypeError):return False
    return origin in allowed and urlsplit(origin).netloc==host


def validate_scene(scene,assets):
    validate(scene,SCENE_SCHEMA)
    if sum(a['count'] for a in scene['arrays'])>180:raise ValueError('工作台最多放置 180 个成员。')
    ids=[d['districtId'] for d in scene['districts']]
    if len(set(ids))!=len(ids):raise ValueError('分区 ID 重复。')
    if any(c['from'] not in ids or c['to'] not in ids or c['from']==c['to'] for c in scene['connections']):raise ValueError('道路连接需要两个不同的有效分区。')
    by_id={a['assetId']:a for a in assets}
    styles=scene.get('terraceStyles',[])
    if len({s['styleId'] for s in styles})!=len(styles):raise ValueError('台地风格 ID 重复。')
    registry=read(TOOL/'.cache/registry.json')
    for style in styles:
        for block in [style[k] for k in ('railBlock','postBlock','capBlock') if k in style]+list(style.get('materials',{}).values()):
            if block not in registry:raise ValueError('未知台地材质：'+block)
        for key in ('cornerAssetId','courtAssetId'):
            if key in style and (style[key] not in by_id or by_id[style[key]]['consumer']!='infrastructure'):
                raise ValueError('台地构件必须使用程序消费素材：'+style[key])
    terrace_ids=[g['terraceId'] for g in scene.get('terraceGroups',[])]
    if len(set(terrace_ids))!=len(terrace_ids):raise ValueError('台地 ID 重复。')
    if set(terrace_ids)&set(ids):
        # Different semantic scopes may share an id only for the legacy adapter.
        raise ValueError('显式台地 ID 请与展示分区 ID 分开。')
    for group in scene.get('terraceGroups',[]):
        if group['districtId'] not in ids:raise ValueError('台地引用未知展示分区。')
        if 'styleRef' in group and group['styleRef'] not in {s['styleId'] for s in styles}:raise ValueError('未知台地风格。')
    nodes={n.get('arrayId',n.get('compositionId')) for n in scene['arrays']+scene['compositions']}
    public=scene.get('publicSpaces',[])
    if len({p['nodeId'] for p in public})!=len(public):raise ValueError('公共空间重复。')
    for p in public:
        if p['nodeId'] not in nodes:raise ValueError('公共空间引用未知组团。')
        for k in ('flowerBlock','hedgeBlock','treeLeaves'):
            if k in p and p[k] not in registry:raise ValueError('未知绿化材质：'+p[k])
        for k in ('hedgeBlock','treeLeaves'):
            if k in p and not p[k].endswith('_leaves'):raise ValueError('绿篱与树冠需要叶片材质。')
    for a in scene['arrays']:
        references=([a['coreAssetId']] if 'coreAssetId' in a else [])+a.get('requiredAssetIds',[])+[f['assetId'] for f in a.get('fill',[])]
        for ref in references:
            if ref not in by_id:raise ValueError('未知结构：'+ref)
            if by_id[ref]['consumer']!='array':raise ValueError('基础设施由程序消费，不能作为阵列成员：'+ref)
        if a['count']<len(a.get('requiredAssetIds',[]))+('coreAssetId' in a):raise ValueError('成员数量不能小于核心和指定配套的数量。')


def read(path):return json.loads(Path(path).read_text(encoding='utf-8'))
def write(path,data):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_suffix(path.suffix+'.tmp');temp.write_text(json.dumps(data,ensure_ascii=False,separators=(',',':')),encoding='utf-8');temp.replace(path)


@lru_cache(maxsize=4)
def terrain_context(directory):
    """Keep the frozen D3 overview separate from the detailed construction window."""
    source=EXPERIMENT/'data/D3地形原始数据.json';raw=source.read_bytes();field=json.loads(raw)
    result=read(Path(directory)/'java-result.json');columns=result['terrainColumns']
    water=[c for c in field['cells'] if c['water']]
    return dict(schema='campus_terrain_context.v1',bounds=field['planningBounds'],step=field['cellStepBlocks'],
        sourceHash='sha256:'+hashlib.sha256(raw).hexdigest(),
        samples=[[c['blockMinX'],c['blockMinZ'],c['elevation'],c['water']] for c in field['cells']],
        detailedBounds=dict(minX=min(c[0] for c in columns),maxX=max(c[0] for c in columns),
                            minZ=min(c[1] for c in columns),maxZ=max(c[1] for c in columns)),
        waterSampleCount=len(water),waterDisplayY=max((c['elevation'] for c in water),default=0)+1,
        waterDisplayMode='INFERRED_DISPLAY_LEVEL',
        detail='外围使用完整 D3 16 格采样；水域范围来自 D3，水面高度为示意。')


def catalog():
    rows=[]
    policy=read(POLICY) if POLICY.exists() else {}
    for ident,folder in sorted(asset_paths().items()):
        if not (folder/'author.json').is_file():continue
        meta=read(folder/'author.json');w,h,d=meta['size'];tags=meta.get('asset_tags',[])
        consumer=meta.get('placement_consumer',meta.get('campus_consumer',policy.get(ident,'infrastructure' if 'infrastructure' in tags else 'array')))
        entrances=[]
        for p in meta.get('points',[]):
            if p['kind']!='entrance':continue
            direction=p.get('direction') or p.get('facing')
            if not direction and p.get('approach'):
                dx=p['approach'][0]-p['pos'][0];dz=p['approach'][2]-p['pos'][2]
                direction=('west' if dx>0 else 'east') if abs(dx)>abs(dz) else ('north' if dz>0 else 'south')
            if not direction:continue
            entrances.append(dict(id=p['id'],x=p['pos'][0],z=p['pos'][2],direction=direction.upper()))
        rows.append(dict(assetId='studio:'+folder.name.lower(),id=folder.name,name=meta['name'],width=w,height=h,depth=d,
            path=str(folder),style=meta.get('civilization',''),
            placeholder=False,functionTerms=meta.get('function_terms',[]),consumer=consumer,entrances=entrances,
            groundPlane=meta.get('ground_plane',{}).get('y',2),naturalGround=meta.get('natural_ground',False),
            roofMinY=meta.get('roof_min_y'),nbtSha256=meta['nbt_sha256'],layoutClearance=1 if meta.get('natural_ground',False) else 0))
    return rows


def prepare():
    env=os.environ.copy()
    if os.name=='nt' and Path('C:/Program Files/Java/jdk-17').exists():env['JAVA_HOME']='C:/Program Files/Java/jdk-17'
    proc=subprocess.run([str(ROOT/('gradlew.bat' if os.name=='nt' else 'gradlew')),'prepareCityWorkbench','--offline','--no-daemon','--console=plain'],cwd=ROOT,env=env,capture_output=True)
    OUTPUT.mkdir(parents=True,exist_ok=True);(OUTPUT/'prepare.log').write_bytes(proc.stdout+proc.stderr)
    if proc.returncode:raise RuntimeError('Java preparation failed; see '+str(OUTPUT/'prepare.log'))


def rotate_state(palette,rotation):
    palette=copy.deepcopy(palette);props=palette['properties'];dirs=['north','east','south','west'];turns=rotation//90
    connections={k:props[k] for k in dirs if k in props}
    if props.get('facing') in dirs:props['facing']=dirs[(dirs.index(props['facing'])+turns)%4]
    if props.get('axis') in ['x','z'] and turns%2:props['axis']='z' if props['axis']=='x' else 'x'
    if 'rotation' in props:props['rotation']=str((int(props['rotation'])+turns*4)%16)
    for key in list(props):
        if key in dirs:props.pop(key)
    for key,value in connections.items():props[dirs[(dirs.index(key)+turns)%4]]=value
    return palette


def compose(result,assets):
    by_id={a['assetId']:a for a in assets};cells={};sources={};models={}
    def put(x,y,z,s,layer):
        if isinstance(s,str):name,props=state(s);s={'name':name,'properties':dict(props)}
        key=(x,y,z)
        if s['name'] in ['minecraft:air','minecraft:cave_air','minecraft:void_air']:cells.pop(key,None);sources.pop(key,None)
        else:cells[key]=s;sources[key]=layer
        if len(cells)>1200000:raise ValueError('场景超过 120 万方块，请缩减阵列后重试。')
    for row in result['terrainColumns']:
        x,z,y,block=row[:4];ground=row[4] if len(row)>4 else y
        if block=='minecraft:water':
            put(x,ground,z,'minecraft:gravel','terrain')
            for yy in range(ground+1,y):put(x,yy,z,'minecraft:water','terrain')
        for dy in range(1,3):put(x,ground-dy,z,'minecraft:dirt' if block!='minecraft:water' else 'minecraft:gravel','terrain')
        put(x,y,z,block,'terrain')
    for x,y,z,s,kind in result['mutations']:put(x,y,z,s,'infrastructure')
    for member in result['members']:
        asset=by_id[member['assetId']]
        if asset['id'] not in models:models[asset['id']]=read_structure(Path(asset['path'])/'structure.nbt')
        model=models[asset['id']]
        if model['sha256']!=asset['nbtSha256']:raise ValueError('NBT changed during build: '+asset['id'])
        w,_,d=model['size'];ox=member['originBlock']['x'];oz=member['originBlock']['z'];rotation=member['rotation'];base=member['surfaceY']+1-asset['groundPlane']
        for b in model['blocks']:
            x,y,z=b['pos'];rx,rz=x,z
            if rotation==90:rx,rz=d-1-z,x
            elif rotation==180:rx,rz=w-1-x,d-1-z
            elif rotation==270:rx,rz=z,w-1-x
            if asset['naturalGround']:
                # Agriculture follows each column. This is a virtual preview adaptation, not a runtime NBT transform.
                samples=result['_groundIndex'];ground=samples.get((ox+rx,oz+rz),member['surfaceY']);yy=ground+1-asset['groundPlane']+y
            else:yy=base+y
            s=rotate_state(model['palette'][b['state']],rotation)
            put(ox+rx,yy,oz+rz,s,'building:'+member['memberId'])
        member['nbtSha256']=model['sha256'];member['placementBaseY']=base
    from designed_landscape import apply_public_spaces
    result['publicSpacePlans']=apply_public_spaces(result, cells, sources, put)
    fixture_checks=[]
    for fixture in result.get('fixtures',[]):
        asset=by_id[fixture['assetId']]
        model=models.setdefault(asset['id'],read_structure(Path(asset['path'])/'structure.nbt'))
        if model['sha256']!=asset['nbtSha256']:raise ValueError('构件 NBT 在生成时改变：'+asset['id'])
        ox,oz=fixture['originBlock']['x'],fixture['originBlock']['z'];base=fixture['placementBaseY'];rotation=fixture['rotation'];w,_,d=model['size']
        box=(ox,oz,ox+fixture['width']-1,oz+fixture['depth']-1)
        for member in result['members']:
            mx,mz=member['originBlock']['x'],member['originBlock']['z']
            if box[0]<=mx+member['width'] and box[2]>=mx-1 and box[1]<=mz+member['depth'] and box[3]>=mz-1:raise ValueError('构件侵入建筑净空。')
        for band in result['roadBands']:
            b=band['bounds']
            if box[0]<=b['maxX']+2 and box[2]>=b['minX']-2 and box[1]<=b['maxZ']+2 and box[3]>=b['minZ']-2:raise ValueError('构件侵入道路净空。')
        for z in range(box[1],box[3]+1):
            for x in range(box[0],box[2]+1):
                floor=cells.get((x,fixture['surfaceY'],z),{})
                if floor.get('name','minecraft:air') in ('minecraft:air','minecraft:water'):raise ValueError('构件没有完整台地承载。')
        for b in model['blocks']:
            x,y,z=b['pos'];rx,rz=x,z
            if rotation==90:rx,rz=d-1-z,x
            elif rotation==180:rx,rz=w-1-x,d-1-z
            elif rotation==270:rx,rz=z,w-1-x
            if sources.get((ox+rx,base+y,oz+rz),'').startswith('building:'):raise ValueError('构件写入建筑。')
            put(ox+rx,base+y,oz+rz,rotate_state(model['palette'][b['state']],rotation),'fixture:'+fixture['placementId'])
        fixture['nbtSha256']=model['sha256']
        fixture_checks.append(dict(placementId=fixture['placementId'],supported=True,buildingClearance=True,roadClearance=True,nbtSha256=model['sha256']))
    result['fixtureChecks']=fixture_checks
    minx=min(p[0] for p in cells);miny=min(p[1] for p in cells);minz=min(p[2] for p in cells)
    size=[max(p[i] for p in cells)-v+1 for i,v in enumerate([minx,miny,minz])]
    palette=[];lookup={};blocks=[]
    for p,s in cells.items():
        key=(s['name'],tuple(sorted(s['properties'].items())))
        if key not in lookup:lookup[key]=len(palette);palette.append(s)
        blocks.append({'pos':[p[0]-minx,p[1]-miny,p[2]-minz],'state':lookup[key],'layer':sources[p]})
    return dict(size=size,origin=[minx,miny,minz],palette=palette,blocks=blocks)


def build(scene,base_revision=None):
    OUTPUT.mkdir(parents=True,exist_ok=True);state_file=OUTPUT/'state.json';previous=read(state_file) if state_file.exists() else {'revision':0}
    if base_revision is not None and base_revision!=previous['revision']:raise RevisionConflict('场景已有新版本，请刷新后重试。')
    assets=catalog();validate_scene(scene,assets)
    terrain_info={'mode':'D3_INTERPOLATED','step':16,'detail':'D3 真实采样，逐格高度由双线性插值得到。'}
    dem_path=OUTPUT/'terrain/dem-step1.json'
    if scene.get('terrainSource')=='DEM_STEP_1':
        if not dem_path.exists():raise ValueError('尚未导入逐格 DEM，请先从原存档扫描或导入。')
        terrain_info={**validate_dem(read(dem_path)),'mode':'DEM_STEP_1','detail':'逐格生成器地面与水面高度；不插值。'}
    revision=previous['revision']+1;folder=OUTPUT/'revisions'/f'r{revision:04d}'
    if folder.exists():folder=folder.with_name(folder.name+'-'+str(time.time_ns()))
    folder.mkdir(parents=True,exist_ok=False)
    request={**scene,'assets':assets,'terrainPath':str(EXPERIMENT/'data/D3地形原始数据.json')};write(folder/'request.json',request)
    if scene.get('terrainSource')=='DEM_STEP_1':
        # Freeze the DEM with the revision so a later import cannot change this build's input.
        write(folder/'dem-step1.json',read(dem_path));request['demPath']=str(folder/'dem-step1.json');write(folder/'request.json',request)
    java=Path(os.environ.get('JAVA_HOME','C:/Program Files/Java/jdk-17'))/'bin'/('java.exe' if os.name=='nt' else 'java')
    if not java.exists():java='java'
    proc=subprocess.run([str(java),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',(ROOT/'build/city-workbench-classpath.txt').read_text(),
        'com.rinsing.geomantia.systems.city.application.CityCampusExperiment',str(folder/'request.json'),str(folder/'java-result.json')],cwd=ROOT,capture_output=True)
    (folder/'java.log').write_bytes(proc.stdout+proc.stderr)
    if proc.returncode:raise RuntimeError('Java build failed; see '+str(folder/'java.log'))
    result=read(folder/'java-result.json')
    if not result.get('ok'):raise ValueError(result.get('error','Java rejected scene'))
    result['_groundIndex']={(row[0],row[1]):row[2] for row in result['terrainColumns']};world=compose(result,assets);del result['_groundIndex']
    lamps=sum(1 for b in world['blocks'] if world['palette'][b['state']]['name']=='minecraft:lantern')
    walls=sum(r.get('foundationDiagnostics',{}).get('terraceEdgePreparedCount',0) for r in result['execution'])
    metadata=dict(revision=revision,scene=scene,terrain=terrain_info,members=result['members'],terraces=result['terraces'],roadPlan=result['roadPlan'],streetReview=result['streetReview'],
        publicSpacePlans=result.get('publicSpacePlans',[]),layoutSpaces=result.get('layoutSpaces',[]),fixtures=result.get('fixtures',[]),fixtureChecks=result.get('fixtureChecks',[]),terraceFeaturePlans=result.get('terraceFeaturePlans',{}),
        summary=dict(buildings=len(result['members']),terraces=len(result['terraces']),roadSegments=len(result['roadBands']),lamps=lamps,wallBlocks=walls,
                     fixtures=len(result.get('fixtures',[])),terraceFeatureBlocks=sum(len(p['blocks']) for p in result.get('terraceFeaturePlans',{}).values()),
                     blockCount=len(world['blocks']),owners=len(result['execution'])),
        terrainMode=result['terrainMode'],generationReady=False,evidenceDirectory=str(folder))
    write(folder/'world.json',world);write(folder/'metadata.json',metadata);write(folder/'scene.json',scene)
    write(state_file,{'revision':revision,'directory':str(folder),'scene':scene});return metadata


class Handler(StudioHandler):
    def do_GET(self):
        path=urlsplit(self.path).path
        if path=='/api/campus':
            state_file=OUTPUT/'state.json'
            if not state_file.exists():return self.json({'error':'请先生成学院。'},404)
            folder=Path(read(state_file)['directory']);return self.json({'metadata':read(folder/'metadata.json'),'world':read(folder/'world.json'),'catalog':catalog()})
        if path=='/api/campus/catalog':return self.json(catalog())
        if path=='/api/campus/default':return self.json(read(SCENE))
        if path=='/api/campus/examples':return self.json([
            {'id':'campus','name':'学院 · 公共广场与嵌套组团','scene':read(Path(__file__).with_name('campus-groups.json'))},
            {'id':'original','name':'原始学院示范','scene':read(SCENE)},
            {'id':'terraces','name':'学院 · 通用台地设施试验','scene':read(Path(__file__).with_name('terrace-trial.json'))},
            {'id':'town','name':'城镇 · 同一机制另一套风格','scene':read(Path(__file__).with_name('town-terrace-trial.json'))}])
        if path=='/api/campus/terrain':
            manifest=OUTPUT/'terrain/manifest.json';return self.json({'demAvailable':manifest.exists(),'dem':read(manifest) if manifest.exists() else None})
        if path=='/api/campus/terrain/context':
            state_file=OUTPUT/'state.json'
            if not state_file.exists():return self.json({'error':'请先生成学院。'},404)
            return self.json(terrain_context(read(state_file)['directory']))
        if path=='/':return self.file(TOOL/'dist','campus.html')
        return super().do_GET()
    def do_POST(self):
        route=urlsplit(self.path).path
        if route not in ['/api/campus/build','/api/campus/terrain/import']:return super().do_POST()
        port=self.server.server_address[1]
        if not allows_write_origin(self.headers,port) or self.headers.get('X-Campus-Write')!='build':return self.json({'error':'请从已配置的工作台地址生成。'},403)
        if not LOCK.acquire(blocking=False):return self.json({'error':'正在生成，请稍候。'},409)
        try:
            length=int(self.headers.get('Content-Length','0'))
            if not 0<length<=(24000000 if route.endswith('/import') else 200000):raise ValueError('数据大小超出限制。')
            data=json.loads(self.rfile.read(length))
            if not isinstance(data,dict):raise ValueError('请求必须是方案对象。')
            if route.endswith('/import'):return self.json(install_dem(data,OUTPUT))
            if type(data.get('baseRevision')) is not int:raise ValueError('baseRevision 必须是整数。')
            return self.json(build(data['scene'],data['baseRevision']))
        except RevisionConflict as e:return self.json({'error':str(e)},409)
        except (ValueError,KeyError,RuntimeError,OSError) as e:return self.json({'error':str(e)},400)
        finally:LOCK.release()


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=8767);p.add_argument('--build',action='store_true');p.add_argument('--build-only',action='store_true');p.add_argument('--prepare',action='store_true');a=p.parse_args()
    if a.prepare or not (ROOT/'build/city-workbench-classpath.txt').exists():prepare()
    if a.build or a.build_only or not (OUTPUT/'state.json').exists():print(json.dumps(build(read(SCENE))['summary'],ensure_ascii=False),flush=True)
    if a.build_only:sys.exit(0)
    server=ThreadingHTTPServer(('127.0.0.1',a.port),Handler);print(f'Campus workbench: http://127.0.0.1:{a.port}',flush=True);server.serve_forever()
