"""Import a D3-bound step=1 DEM, or ask the running original world to export one."""
import argparse, hashlib, json, os, sys, urllib.request, urllib.error
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
EXPERIMENT=ROOT.parent/'designer_territoryMod/experiments/第三题_学院岛坐标布局_20261008'
TERRAIN=EXPERIMENT/'data/D3地形原始数据.json'
REVIEW=EXPERIMENT/'data/D3地貌原始数据.json'


def validate_dem(dem,terrain_path=TERRAIN,review_path=REVIEW):
    terrain=json.loads(terrain_path.read_text(encoding='utf-8'));review=json.loads(review_path.read_text(encoding='utf-8'))
    if not isinstance(dem,dict) or dem.get('schema')!='city_terrain_dem.v1' or type(dem.get('step')) is not int or dem['step']!=1 or dem.get('order')!='z_then_x':raise ValueError('需要 city_terrain_dem.v1、step=1、z_then_x 的逐格 DEM。')
    expected='sha256:'+hashlib.sha256(terrain_path.read_bytes()).hexdigest()
    if dem.get('cityId')!=terrain['cityId'] or dem.get('d3TerrainHash')!=expected:raise ValueError('DEM 城市或 D3 哈希不匹配。')
    source=dem.get('source',{})
    if source.get('kind')!='generator_native_height_field' or source.get('sourceFingerprint')!=review['terrainProvider']['sourceFingerprint']:raise ValueError('DEM 必须来自这份 D3 的原生成器和配置。')
    bounds=dem.get('bounds',{});keys=('minX','minZ','maxX','maxZ')
    if any(type(bounds.get(k)) is not int for k in keys):raise ValueError('DEM 边界必须是整数。')
    w=bounds['maxX']-bounds['minX']+1;d=bounds['maxZ']-bounds['minZ']+1
    if w<=0 or d<=0 or w*d>1048576:raise ValueError('DEM 范围无效或超过 1048576 格。')
    tb=terrain['planningBounds']
    if bounds['minX']<tb['minX'] or bounds['minZ']<tb['minZ'] or bounds['maxX']>tb['maxX'] or bounds['maxZ']>tb['maxZ']:raise ValueError('DEM 超出 D3 城市范围。')
    ground=dem.get('groundY');water=dem.get('waterSurfaceY')
    if not isinstance(ground,list) or not isinstance(water,list) or len(ground)!=w*d or len(water)!=w*d:raise ValueError('DEM 必须完整覆盖每一格，不能用缺失值代替。')
    for g,y in zip(ground,water):
        if type(g) is not int or not -64<=g<320 or y is not None and (type(y) is not int or not g<=y<320):raise ValueError('DEM 地面或水面高度无效。')
    return {'bounds':bounds,'step':1,'columns':w*d,'source':source,'d3TerrainHash':expected}


def install(dem,output):
    from workbench import write
    summary=validate_dem(dem);path=Path(output)/'terrain/dem-step1.json';write(path,dem)
    summary['sha256']=hashlib.sha256(path.read_bytes()).hexdigest();write(path.with_name('manifest.json'),summary)
    return summary


def main():
    p=argparse.ArgumentParser(description=__doc__);s=p.add_subparsers(dest='action',required=True)
    local=s.add_parser('import');local.add_argument('file',type=Path)
    scan=s.add_parser('scan');scan.add_argument('--api-url',default=os.environ.get('GEOMANTIA_MC_API_URL','http://127.0.0.1:5000'))
    p.add_argument('--output',type=Path,default=ROOT/'runtime/campus-workbench');a=p.parse_args()
    try:
        if a.action=='import':dem=json.loads(a.file.read_text(encoding='utf-8'))
        else:
            identity=json.loads((EXPERIMENT/'来源与校验.json').read_text(encoding='utf-8'))
            url=a.api_url.rstrip('/')+'/realm/city/export_terrain_dem'
            request=urllib.request.Request(url,data=json.dumps({'runId':identity['sourceRunId'],'citySeedId':identity['sourceCityId'],'step':1}).encode(),headers={'Content-Type':'application/json','X-Geomantia-Agent-View':'true'})
            with urllib.request.urlopen(request,timeout=600) as response:result=json.load(response)
            if not result.get('ok'):raise ValueError(result.get('error','DEM 扫描失败。'))
            dem=json.loads(Path(result['demPath']).read_text(encoding='utf-8'))
        print(json.dumps(install(dem,a.output),ensure_ascii=False,indent=2))
    except (OSError,ValueError,KeyError,urllib.error.URLError) as e:
        print('DEM 未导入：'+str(e),file=sys.stderr);sys.exit(1)


if __name__=='__main__':main()
