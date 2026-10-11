"""Nested public-space composition. Recipes are data, not theme-specific branches."""
import math,random,heapq,json
from pathlib import Path
from array_tools import schema,validate
from studio.model import read_structure,state

I={'type':'integer','minimum':-128,'maximum':128}
NODE=schema({'id':{'type':'string','minLength':1},'x':I,'z':I,'rotation':{'type':'integer','enum':[0,90,180,270]},
    'layout':{'type':'string','enum':['POINT','LINE','GRID','RING']},'count':{'type':'integer','minimum':1,'maximum':48},
    'spacing':{'type':'integer','minimum':1,'maximum':64},'columns':{'type':'integer','minimum':1,'maximum':12},
    'radius':{'type':'integer','minimum':1,'maximum':60},'faceCenter':{'type':'boolean'},
    'assetId':{'type':'string'},'pool':{'type':'string'},'children':{'type':'array','minItems':1,'maxItems':32,'items':{}}},['id','x','z'])
SCHEMA=schema({'name':{'type':'string','minLength':1,'maxLength':100},'seed':{'type':'integer'},
    'plantingDensity':{'type':'number','minimum':0,'maximum':2},
    'plantingZones':{'type':'array','maxItems':24,'items':schema({'id':{'type':'string'},'x':I,'z':I,
        'width':{'type':'integer','minimum':3,'maximum':128},'depth':{'type':'integer','minimum':3,'maximum':128},
        'pool':{'type':'string'},'count':{'type':'integer','minimum':1,'maximum':150},
        'spacing':{'type':'integer','minimum':3,'maximum':24},'shape':{'type':'string','enum':['RECTANGLE','ELLIPSE','PATH_EDGE']}},['id','x','z','width','depth','pool','count','spacing','shape'])},
    'width':{'type':'integer','minimum':32,'maximum':128},'depth':{'type':'integer','minimum':32,'maximum':128},
    'ground':{'type':'string','enum':['GRASS','STONE']},'pathWidth':{'type':'integer','enum':[3,5,7]},
    'entries':{'type':'array','minItems':2,'maxItems':8,'items':schema({'x':I,'z':I},['x','z'])},
    'pools':{},'nodes':{'type':'array','maxItems':32,'items':NODE},
    'clearings':{'type':'array','maxItems':12,'items':schema({'x':I,'z':I,'width':{'type':'integer','minimum':1,'maximum':128},'depth':{'type':'integer','minimum':1,'maximum':128}},['x','z','width','depth'])}},
    ['name','seed','width','depth','ground','pathWidth','entries','pools','nodes','clearings'])

def examples():return json.loads(Path(__file__).with_name('space-examples.json').read_text(encoding='utf-8'))

def compose(plan,assets,rotate_state):
    validate(plan,SCHEMA)
    if not isinstance(plan['pools'],dict):raise ValueError('素材池须为对象。')
    byid={a['assetId']:a for a in assets};rng=random.Random(plan['seed'])
    ids=set();node_count=0
    def check(nodes,depth=0):
        nonlocal node_count
        if depth>5:raise ValueError('嵌套最多六层。')
        for n in nodes:
            validate(n,NODE);node_count+=1
            if node_count>128:raise ValueError('最多 128 个节点。')
            if n['id'] in ids:raise ValueError('节点 ID 重复：'+n['id'])
            ids.add(n['id'])
            if sum(k in n for k in ('assetId','pool','children'))!=1:raise ValueError('节点须且只能选择素材、素材池或子组。')
            if n.get('layout','POINT')=='POINT' and n.get('count',1)!=1:raise ValueError('单点节点数量须为 1。')
            if 'pool' in n and n['pool'] not in plan['pools']:raise ValueError('未知素材池。')
            if 'children' in n:check(n['children'],depth+1)
    check(plan['nodes'])
    for key,rows in plan['pools'].items():
        if not isinstance(rows,list) or not rows:raise ValueError('素材池不能为空。')
        for row in rows:
            validate(row,schema({'assetId':{'type':'string'},'weight':{'type':'number','exclusiveMinimum':0}},['assetId','weight']))
            if not math.isfinite(row['weight']) or row['weight']<=0:raise ValueError('权重必须为有限正数。')
    w,d=plan['width'],plan['depth'];cells={};owners={};placed=[];occupied=set();reserved=set();models={}
    def put(x,y,z,s,owner):
        if isinstance(s,str):name,props=state(s);s=dict(name=name,properties=dict(props))
        if s['name'] in ('minecraft:air','minecraft:cave_air','minecraft:void_air'):return
        cells[x,y,z]=s;owners[x,y,z]=owner
    for x in range(w):
        for z in range(d):
            put(x,0,z,'minecraft:stone_bricks','ground')
            put(x,1,z,'minecraft:grass_block' if plan['ground']=='GRASS' else 'minecraft:smooth_stone','ground')
    for c in plan['clearings']:
        mask={(x,z) for x in range(c['x'],c['x']+c['width']) for z in range(c['z'],c['z']+c['depth'])}
        if any(not(0<=x<w and 0<=z<d) for x,z in mask):raise ValueError('留白区越界。')
        reserved|=mask
        for x,z in mask:put(x,1,z,'minecraft:polished_andesite','clearing')
    def turn(x,z,r):return [(x,z),(-z,x),(-x,-z),(z,-x)][r//90]
    def visit(nodes,ox,oz,rot,path):
        for n in nodes:
            nx,nz=turn(n['x'],n['z'],rot);cx,cz=ox+nx,oz+nz;nr=(rot+n.get('rotation',0))%360
            for i in range(n.get('count',1)):
                layout=n.get('layout','POINT');dx=dz=0;r=nr
                if layout=='LINE':dx=i*n.get('spacing',12)
                if layout=='GRID':dx=(i%n.get('columns',2))*n.get('spacing',12);dz=(i//n.get('columns',2))*n.get('spacing',12)
                if layout=='RING':
                    angle=2*math.pi*i/n.get('count',1);dx=round(math.sin(angle)*n.get('radius',20));dz=round(-math.cos(angle)*n.get('radius',20))
                    if n.get('faceCenter'):r=(nr+round((math.degrees(angle)+180)/90)*90)%360
                dx,dz=turn(dx,dz,nr);x,z=cx+dx,cz+dz;ident=f"{path}/{n['id']}:{i+1}"
                if 'children' in n:visit(n['children'],x,z,r,ident);continue
                if len(placed)>=200:raise ValueError('最多 200 个素材实例。')
                options=[dict(assetId=n['assetId'],weight=1)] if 'assetId' in n else plan['pools'][n['pool']]
                choices=sorted(options,key=lambda a:-math.log(max(rng.random(),1e-12))/a['weight'])
                for candidate in choices:
                    aid=candidate['assetId'];a=byid.get(aid)
                    if not a or not (a['consumer']=='infrastructure' or 'landscape' in a.get('tags',[])):raise ValueError('场地成员须为配套或景观素材：'+aid)
                    if aid not in models:models[aid]=read_structure(Path(a['path'])/'structure.nbt')
                    m=models[aid]
                    if m['sha256']!=a['nbtSha256']:raise ValueError('素材已变化，请刷新目录。')
                    aw,ah,ad=m['size'];rw,rd=(ad,aw) if r%180 else (aw,ad);ax,az=x-rw//2,z-rd//2
                    mask={(xx,zz) for xx in range(ax,ax+rw) for zz in range(az,az+rd)}
                    margin={(xx+dx,zz+dz) for xx,zz in mask for dx,dz in ((0,0),*[(1,0),(-1,0),(0,1),(0,-1)])}
                    if any(not(1<=xx<w-1 and 1<=zz<d-1) for xx,zz in mask) or margin&occupied or mask&reserved:continue
                    base=2-a['groundPlane']
                    if base<0:raise ValueError('素材地面基准不适合当前场地。')
                    for b in m['blocks']:
                        bx,by,bz=b['pos'];rx,rz=[(bx,bz),(ad-1-bz,bx),(aw-1-bx,ad-1-bz),(bz,aw-1-bx)][r//90]
                        put(ax+rx,base+by,az+rz,rotate_state(m['palette'][b['state']],r),ident)
                    occupied.update(mask);placed.append(dict(id=ident,assetId=aid,x=ax,z=az,width=rw,depth=rd,rotation=r,nbtSha256=m['sha256']))
                    break
                else:raise ValueError('成员越界、碰撞或侵占留白：'+ident+'；请调整位置、间距或尺寸。')
    visit(plan['nodes'],0,0,0,'space')
    half=plan['pathWidth']//2;blocked={(x+dx,z+dz) for x,z in occupied for dx in range(-half-1,half+2) for dz in range(-half-1,half+2)}
    entries=[(p['x'],p['z']) for p in plan['entries']]
    if len(set(entries))!=len(entries):raise ValueError('入口不能重复。')
    def valid(p):return half<=p[0]<w-half and half<=p[1]<d-half and p not in blocked
    if any(not valid(p) for p in entries):raise ValueError('入口越界或被素材占用，请保留完整通路宽度。')
    network={entries[0]};routes=[]
    for end in entries[1:]:
        # A* to the first entrance; existing paths are cheaper, encouraging shared walks.
        start=entries[0];queue=[(0,0,start)];cost={start:0};prev={start:None}
        while queue:
            _,g,p=heapq.heappop(queue)
            if g!=cost[p]:continue
            if p==end:break
            for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)):
                q=p[0]+dx,p[1]+dz
                if not valid(q):continue
                ng=g+(1 if q in network else 2)
                if ng<cost.get(q,10**9):cost[q]=ng;prev[q]=p;heapq.heappush(queue,(ng+abs(q[0]-end[0])+abs(q[1]-end[1]),ng,q))
        if end not in prev:raise ValueError('入口之间无法连通，请调整成员位置。')
        route=[];p=end
        while p is not None:route.append(p);network.add(p);p=prev[p]
        routes.append(route)
    for x,z in network:
        for dx in range(-half,half+1):
            for dz in range(-half,half+1):put(x+dx,1,z+dz,'minecraft:polished_andesite','path')
    from space_planting import plant_zones
    planting=plant_zones(plan,byid,cells,owners,put,placed,reserved,rotate_state)
    placed.extend(planting['placements'])
    palette=[];lookup={};blocks=[]
    for p,s in cells.items():
        key=s['name'],tuple(sorted(s['properties'].items()))
        if key not in lookup:lookup[key]=len(palette);palette.append(s)
        blocks.append(dict(pos=list(p),state=lookup[key],layer=owners[p]))
    return dict(plan=plan,world=dict(size=[w,max(p[1] for p in cells)+1,d],origin=[0,0,0],palette=palette,blocks=blocks),
        placements=placed,planting=planting,routes=routes,summary=dict(members=len(placed),plants=len(planting['placements']),routes=len(routes),blocks=len(blocks)),
        limits='独立平地构图；不新增核心台地。局部抬高、城市接入和游戏内流体尚未接入。')
