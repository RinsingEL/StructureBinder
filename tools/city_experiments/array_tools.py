#!/usr/bin/env python3
"""Offline array tools: CLI or MCP stdio. Java computes all placement geometry."""
import argparse, base64, copy, hashlib, json, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT.parent / 'designer_territoryMod/experiments/第三题_学院岛坐标布局_20261008'
ALGORITHMS = ['GRID','LINEAR','COURTYARD','COMPACT','ORGANIC_COMPACT','CENTER_SYMMETRIC','CONTIGUOUS']
POINT = {'type':'object','properties':{'x':{'type':'integer'},'z':{'type':'integer'}},'required':['x','z'],'additionalProperties':False}
ARRAY = {'type':'object','properties':{'arrayId':{'type':'string'},'function':{'type':'string'},'anchorBlock':POINT,'algorithm':{'type':'string','enum':ALGORITHMS},'directionDegrees':{'type':'integer','enum':[0,90,180,270]},'rotation':{'type':'integer','enum':[0,90,180,270]},'densityClass':{'type':'string','enum':['SPARSE','BALANCED','DENSE']},'count':{'type':'integer','minimum':1,'maximum':1024},'coreAssetId':{'type':'string'},'requiredAssetIds':{'type':'array','items':{'type':'string'}},'fill':{'type':'array','items':{'type':'object','properties':{'assetId':{'type':'string'},'weight':{'type':'number','exclusiveMinimum':0}},'required':['assetId','weight'],'additionalProperties':False}}},'required':['arrayId','anchorBlock','algorithm','count'],'additionalProperties':False}
COMPOSITION = {'type':'object','properties':{'compositionId':{'type':'string'},'anchorBlock':POINT,'algorithm':{'type':'string','enum':[a for a in ALGORITHMS if a!='CONTIGUOUS']},'directionDegrees':{'type':'integer','enum':[0,90,180,270]},'densityClass':{'type':'string','enum':['SPARSE','BALANCED','DENSE']},'memberIds':{'type':'array','minItems':1,'items':{'type':'string'}}},'required':['compositionId','anchorBlock','algorithm','memberIds'],'additionalProperties':False}
def schema(props, required): return {'type':'object','properties':props,'required':required,'additionalProperties':False}
TOOLS = [
 {'name':'array_preview','description':'Use production Java geometry for one array at explicit world coordinates. Core is pinned to anchorBlock; courtyard anchor is its composition reference. Density controls production spacing; direction and template rotation are separate. Mock assets use proposed footprints. Returns overview/local images and D3 diagnostics, never world construction.','inputSchema':schema({'array':ARRAY,'seed':{'type':'integer'}},['array'])},
 {'name':'layout_preview','description':'Combine complete arrays and nested compositions, run production Java geometry and scene-wide collisions. Saves an independent experiment revision. No roads are automatically built; D3 water and relief are diagnostic, not game acceptance.','inputSchema':schema({'arrays':{'type':'array','minItems':1,'items':ARRAY},'compositions':{'type':'array','items':COMPOSITION},'seed':{'type':'integer'}},['arrays'])},
 {'name':'layout_update','description':'Replace/add full arrays or compositions by stable ID, or explicitly remove IDs. Requires current baseRevision. Returns new geometry, images, changed members and the actually used missing-assets list. Unmodified uncomposed arrays keep deterministic coordinates.','inputSchema':schema({'baseRevision':{'type':'integer'},'arrays':{'type':'array','items':ARRAY},'compositions':{'type':'array','items':COMPOSITION},'removeIds':{'type':'array','items':{'type':'string'}}},['baseRevision'])}]

def validate(value, spec, path='$'):
    kind=spec.get('type')
    valid={'object':isinstance(value,dict),'array':isinstance(value,list),'string':isinstance(value,str),'integer':type(value) is int,'number':type(value) in (int,float)}
    if kind and not valid[kind]: raise ValueError(f'{path}: expected {kind}')
    if 'enum' in spec and value not in spec['enum']: raise ValueError(f'{path}: unsupported value {value}')
    if kind=='object':
        for k in spec.get('required',[]):
            if k not in value: raise ValueError(f'{path}.{k}: required')
        props=spec['properties']
        for k,v in value.items():
            if k not in props: raise ValueError(f'{path}.{k}: unknown field')
            validate(v,props[k],path+'.'+k)
    if kind=='array':
        if len(value)<spec.get('minItems',0): raise ValueError(f'{path}: empty list')
        for i,v in enumerate(value): validate(v,spec['items'],f'{path}[{i}]')
    if kind in ('integer','number'):
        import math
        if not math.isfinite(value) or value<spec.get('minimum',-float('inf')) or value>spec.get('maximum',float('inf')) or value<=spec.get('exclusiveMinimum',-float('inf')): raise ValueError(f'{path}: invalid number')
    if kind=='string' and not value: raise ValueError(f'{path}: empty string')

def read(path): return json.loads(Path(path).read_text())
def write(path,value): Path(path).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
def catalogue(experiment):
    real=read(experiment/'data/学院素材目录.json')['models']; fake=read(experiment/'data/假结构目录.json')['models'];assets=[]
    for m in real: assets.append({'assetId':m['structureRef'],'name':m['name'],'placeholder':False,**{k:m[k] for k in ['width','height','depth']},'functionTerms':m['functionTerms']})
    for m in fake: assets.append({'assetId':m['assetId'],'name':m['name'],'placeholder':True,**m['proposedSize'],'functionTerms':[m['function']]})
    return assets

def fingerprint(experiment):
    digest=hashlib.sha256()
    for name in ['D3地形原始数据.json','学院素材目录.json','假结构目录.json']: digest.update((experiment/'data'/name).read_bytes())
    return digest.hexdigest()

def run_java(request, folder):
    inp=folder/'java-input.json';out=folder/'java-result.json';write(inp,request)
    command=[str(ROOT/'gradlew'),'cityArrayExperiment','--no-daemon','--console=plain','-PexperimentRequest='+str(inp.resolve()),'-PexperimentOutput='+str(out.resolve())]
    completed=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    (folder/'java-execution.log').write_text(completed.stdout)
    if completed.returncode: raise RuntimeError('Java execution failed; see '+str(folder/'java-execution.log'))
    result=read(out)
    if not result.get('ok'): raise ValueError(result.get('error','Java rejected input'))
    return result

def render(result, terrain, folder):
    try: from PIL import Image, ImageDraw, ImageFont
    except ImportError: raise RuntimeError('Pillow is required for preview rendering; use the bundled Python or the Studio virtualenv')
    font_path='/System/Library/Fonts/STHeiti Medium.ttc'
    font=ImageFont.truetype(font_path,17) if Path(font_path).exists() else ImageFont.load_default()
    colors={'water':'#65b6e4','shore':'#efdc93','plain':'#91bf86','slope':'#ddad78','terrace':'#bccc82'}
    palette=['#7253a3','#286dac','#ba6942','#498467','#99762f','#ab4b77']
    ids=list(dict.fromkeys(m['arrayId'] for m in result['members']))
    def draw_map(bounds,filename,members):
        lowx,lowz,highx,highz=bounds;scale=min(1050/max(1,highx-lowx),1050/max(1,highz-lowz));L=85;T=75;W=int((highx-lowx)*scale);H=int((highz-lowz)*scale)
        im=Image.new('RGB',(W+330,H+155),'#f5f2e9');d=ImageDraw.Draw(im)
        def box(x,z,w,depth):return (L+(x-lowx)*scale,T+(z-lowz)*scale,L+(x+w-lowx)*scale,T+(z+depth-lowz)*scale)
        d.text((L,15),'离线 Java 阵列实验 · 占地预览（非施工图）',font=font,fill='#273441')
        for c in terrain['cells']:
            x,z=c['blockMinX'],c['blockMinZ']
            if x+16>lowx and x<highx and z+16>lowz and z<highz:d.rectangle(box(max(x,lowx),max(z,lowz),min(x+16,highx)-max(x,lowx),min(z+16,highz)-max(z,lowz)),fill=colors[c['landformType']])
        tick=128 if highx-lowx>600 else 32
        for x in range((lowx//tick+1)*tick,highx,tick):
            at=L+(x-lowx)*scale;d.line((at,T,at,T+H),fill='#bcc3b2');d.text((at-22,T-25),str(x),font=font,fill='#273441')
        for z in range((lowz//tick+1)*tick,highz,tick):
            at=T+(z-lowz)*scale;d.line((L,at,L+W,at),fill='#bcc3b2');d.text((5,at-10),str(z),font=font,fill='#273441')
        for m in members:
            p=m['originBlock'];r=box(p['x'],p['z'],m['width'],m['depth']);color='#bd302c' if m['issues'] else palette[ids.index(m['arrayId'])%len(palette)]
            if m['placeholder']:
                for a,b in [((r[0],r[1]),(r[2],r[1])),((r[0],r[3]),(r[2],r[3])),((r[0],r[1]),(r[0],r[3])),((r[2],r[1]),(r[2],r[3]))]:
                    length=max(abs(b[0]-a[0]),abs(b[1]-a[1]));steps=max(1,int(length))
                    for i in range(0,steps,10):
                        f=i/steps;g=min(i+6,steps)/steps;d.line((a[0]+(b[0]-a[0])*f,a[1]+(b[1]-a[1])*f,a[0]+(b[0]-a[0])*g,a[1]+(b[1]-a[1])*g),fill=color,width=3)
            else:d.rectangle(r,outline=color,width=3)
            if m['engineeringNeeds']:d.line((r[0],r[1],r[2],r[3]),fill='#9a570f',width=2)
            label=m['name'] if filename.startswith('array-') else m['memberId'].rsplit(':',1)[-1];d.text((r[0]+3,r[1]+3),label,font=font,fill='#202a36')
        for i,id in enumerate(ids):d.text((L+W+12,T+i*28),id,font=font,fill=palette[i%len(palette)])
        d.text((L,T+H+23),'实线：已有素材  虚线：（假结构）  红色：冲突/越界',font=font,fill='#273441');d.text((L,T+H+50),'斜线：水域或地形工程待复核；位置来自项目 Java 阵列代码',font=font,fill='#273441')
        im.save(folder/filename)
    b=terrain['planningBounds'];draw_map((b['minX'],b['minZ'],b['maxX']+1,b['maxZ']+1),'overview.png',result['members'])
    locals=[]
    for i,id in enumerate(ids):
        members=[m for m in result['members'] if m['arrayId']==id];margin=32
        loX=min(m['originBlock']['x'] for m in members)-margin;loZ=min(m['originBlock']['z'] for m in members)-margin
        hiX=max(m['originBlock']['x']+m['width'] for m in members)+margin;hiZ=max(m['originBlock']['z']+m['depth'] for m in members)+margin
        name=f'array-{i+1:03}.png';draw_map((loX,loZ,hiX,hiZ),name,members);locals.append({'arrayId':id,'path':str((folder/name).resolve())})
    focus=None
    if result['members']:
        bb=[(m['originBlock']['x'],m['originBlock']['z'],m['originBlock']['x']+m['width'],m['originBlock']['z']+m['depth']) for m in result['members']]
        draw_map((min(b[0] for b in bb)-32,min(b[1] for b in bb)-32,max(b[2] for b in bb)+32,max(b[3] for b in bb)+32),'layout-focus.png',result['members'])
        focus=str((folder/'layout-focus.png').resolve())
    return {'overview':str((folder/'overview.png').resolve()),'focus':focus,'arrays':locals}

def invoke(tool, args, experiment, output):
    definition=next((t for t in TOOLS if t['name']==tool),None)
    if not definition: raise ValueError('Unknown tool: '+tool)
    validate(args,definition['inputSchema']);output.mkdir(parents=True,exist_ok=True)
    stamp=fingerprint(experiment);state_path=output/'state.json';previous=None
    if tool=='layout_update':
        if not state_path.exists():raise ValueError('No current layout; call layout_preview first')
        previous=read(state_path)
        if args['baseRevision']!=previous['revision']:raise ValueError('Stale baseRevision')
        if stamp!=previous['inputFingerprint']:raise ValueError('Experiment inputs changed; create a new layout_preview')
        scene=copy.deepcopy(previous['scene']);remove=set(args.get('removeIds',[]));known={n.get('arrayId',n.get('compositionId')) for k in ['arrays','compositions'] for n in scene[k]}
        if remove-known:raise ValueError('Unknown removeIds: '+str(sorted(remove-known)))
        for kind,key in [('arrays','arrayId'),('compositions','compositionId')]:
            changes={n[key]:n for n in args.get(kind,[])}
            if len(changes)!=len(args.get(kind,[])):raise ValueError('Duplicate update IDs')
            if remove & changes.keys():raise ValueError('Cannot update and remove the same ID')
            scene[kind]=[changes.pop(n[key],n) for n in scene[kind] if n[key] not in remove]+list(changes.values())
    else:scene={'arrays':[args['array']] if tool=='array_preview' else args['arrays'],'compositions':args.get('compositions',[]),'seed':args.get('seed',20261008)}
    sequence=len(list(output.glob('call-*')))+1;folder=output/f'call-{sequence:04}-{tool}';folder.mkdir(exist_ok=False);write(folder/'request.json',args)
    assets=catalogue(experiment);by_asset={a['assetId']:a for a in assets}
    request={**scene,'assets':assets,'terrainPath':str((experiment/'data/D3地形原始数据.json').resolve())}
    result=run_java(request,folder);images=render(result,read(request['terrainPath']),folder)
    missing={}
    for m in result['members']:
        if m['placeholder']:
            entry=missing.setdefault(m['assetId'],{'assetId':m['assetId'],'name':m['name'],'plannedCount':0,'conflictCount':0,'functions':set(),'size':{k:by_asset[m['assetId']][k] for k in ['width','height','depth']}});entry['plannedCount']+=1;entry['conflictCount']+=bool(m['issues']);entry['functions'].add(m['function'])
    for entry in missing.values():entry['functions']=sorted(entry['functions'])
    write(folder/'missing-assets.json',list(missing.values()))
    old={m['memberId']:m for m in previous['members']} if previous else {}
    delta=[m['memberId'] for m in result['members'] if m['memberId'] not in old or m!=old[m['memberId']]]
    version=(read(state_path)['revision'] if state_path.exists() else 0)+(tool!='array_preview')
    response={'ok':True,'tool':tool,'revision':version if tool!='array_preview' else None,'executionMode':result['executionMode'],'generationReady':False,'previews':images,'members':result['members'],'missingAssets':list(missing.values()),'changedMemberIds':delta,'removedMemberIds':sorted(set(old)-{m['memberId'] for m in result['members']}),'evidenceDirectory':str(folder.resolve()),'summary':{'planned':len(result['members']),'conflicts':sum(bool(m['issues']) for m in result['members']),'engineeringReview':sum(bool(m['engineeringNeeds']) for m in result['members'])}}
    write(folder/'response.json',response)
    if tool!='array_preview':write(state_path,{'revision':version,'inputFingerprint':stamp,'scene':scene,'members':result['members']})
    return response

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--experiment',type=Path,default=DEFAULT);p.add_argument('--output',type=Path);p.add_argument('--tool',choices=[t['name'] for t in TOOLS]);p.add_argument('--request',type=Path);p.add_argument('--list-tools',action='store_true');p.add_argument('--stdio',action='store_true');a=p.parse_args();output=a.output or a.experiment/'result/array-tools'
    if a.list_tools:print(json.dumps({'tools':TOOLS},ensure_ascii=False,indent=2));return
    if a.stdio:
        for line in sys.stdin:
            msg=None
            try:
                msg=json.loads(line);method=msg.get('method');ident=msg.get('id')
                if ident is None:continue
                if method=='initialize':res={'protocolVersion':msg.get('params',{}).get('protocolVersion','2024-11-05'),'capabilities':{'tools':{}},'serverInfo':{'name':'academy-array-experiment','version':'1.0.0'}}
                elif method=='ping':res={}
                elif method=='tools/list':res={'tools':TOOLS}
                elif method=='tools/call':
                    params=msg['params'];r=invoke(params['name'],params.get('arguments',{}),a.experiment,output);content=[{'type':'text','text':json.dumps(r,ensure_ascii=False)}]
                    for path in [r['previews']['overview']]+([r['previews']['focus']] if r['previews']['focus'] else []):content.append({'type':'image','mimeType':'image/png','data':base64.b64encode(Path(path).read_bytes()).decode()})
                    res={'content':content,'isError':False}
                else:print(json.dumps({'jsonrpc':'2.0','id':ident,'error':{'code':-32601,'message':'Method not found'}}),flush=True);continue
                print(json.dumps({'jsonrpc':'2.0','id':ident,'result':res},ensure_ascii=False),flush=True)
            except Exception as e:
                print(json.dumps({'jsonrpc':'2.0','id':msg.get('id') if isinstance(locals().get('msg'),dict) else None,'result':{'content':[{'type':'text','text':str(e)}],'isError':True}},ensure_ascii=False),flush=True)
        return
    if not a.tool or not a.request:p.error('--tool and --request are required')
    try:print(json.dumps(invoke(a.tool,read(a.request),a.experiment,output),ensure_ascii=False,indent=2))
    except Exception as e:print(json.dumps({'ok':False,'error':str(e)},ensure_ascii=False));sys.exit(1)
if __name__=='__main__':main()
