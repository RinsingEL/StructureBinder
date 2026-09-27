"""Six reference-led northern home silhouettes, retaining proven household plans."""
from functools import partial
from . import northern_life as old


def _shift(m,dx=0,dz=0,*,east_only=False):
    def pos(p):
        return (p[0]+dx,p[1],p[2]+(dz if not east_only or p[0]>=16 else 0))
    m.blocks={pos(p):b for p,b in m.blocks.items()}
    m.block_entities={pos(p):b for p,b in m.block_entities.items()}
    for p in m.meta['points']:
        for field in ('pos','approach','look_at'):
            if field in p:p[field]=list(pos(p[field]))
    for r in m.meta['rooms']:
        r['min']=list(pos(r['min']));r['max']=list(pos(r['max']))
    for c in m.meta['connections']:
        if 'pos' in c:c['pos']=list(pos(c['pos']))


def _roof(m,x0,z0,x1,z1,start=9,axis='z'):
    """Closed, timber-edged gable supported by walls and continuous wall plates."""
    lo,hi=(x0-1,x1+1) if axis=='z' else (z0-1,z1+1)
    a0,a1=(z0-1,z1+1) if axis=='z' else (x0-1,x1+1)
    for cross in range(lo,hi+1):
        y=start+min(cross-lo,hi-cross)
        face=('east' if cross<(lo+hi)/2 else 'west') if axis=='z' else ('south' if cross<(lo+hi)/2 else 'north')
        for a in range(a0,a1+1):
            x,z=(cross,a) if axis=='z' else (a,cross)
            wood='dark_oak' if a in (a0,a1) or (a-a0)%7==0 else 'spruce'
            m.set(x,y,z,wood+'_stairs[facing='+face+']' if cross!=(lo+hi)//2 else 'dark_oak_log[axis='+axis+']')
        for a in (a0+1,a1-1):
            x,z=(cross,a) if axis=='z' else (a,cross)
            if lo<cross<hi:m.box((x,start-1,z),(x,y-1,z),'spruce_planks')
    for x in (x0,x1):m.box((x,start-1,z0),(x,start-1,z1),'dark_oak_log[axis=z]')
    for z in (z0,z1):m.box((x0,start-1,z),(x1,start-1,z),'dark_oak_log[axis=x]')
    mid=(lo+hi)//2;peak=start+(hi-lo)//2
    for a in (a0,a1):
        x,z=(mid,a) if axis=='z' else (a,mid)
        m.box((x,peak+1,z),(x,peak+2,z),'dark_oak_fence')


def _porch(m,x0,z0,x1,z1,roof=7):
    m.box((x0,2,z0),(x1,2,z1),'spruce_planks')
    for x in (x0,x1):m.box((x,3,z0),(x,roof-1,z0),'dark_oak_log[axis=y]')
    for z in (z0,z1):m.box((x0,roof-1,z),(x1,roof-1,z),'dark_oak_log[axis=x]')
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):m.set(x,roof,z,'spruce_slab[type=bottom]')
    for x in (x0+1,x1-1):m.set(x,roof-2,z1,'lantern[hanging=true]')


def home(v):
    builders=(old.short_longhouse,old.paired_longhouse,old.communal_hearth,old.elbow_longhouse,old.court_longhouse,old.two_storey_longhouse)
    m=builders[v-1]()
    names=('紧凑单户短长屋','双户错位长屋','高厅低翼共炉屋','L形家务院住宅','双屋围院共食宅','阁楼双层长屋')
    notes=('单户山墙长屋，前置低门廊与侧边柴棚，前炊食后分寝。','两户山墙前后错位，分别有低门廊、厨房和双床后寝。','高起窄中厅与左右低屋翼，共炉主厅贯穿四间单寝。','横向长厅与纵向寝翼折成L形，围出独立有顶家务院。','两座独立山墙长屋以低后廊围住共食院，睡眠与炊事分别成翼。','陡坡双层长屋，内部楼梯上阁寝，前阳台及低门廊表达层次。')
    if v==2:
        m.size=(m.size[0],m.size[1]+3,m.size[2]+3);m.meta['size']=list(m.size)
        _shift(m,dz=3,east_only=True)
        m.box((1,0,1),(m.size[0]-2,2,m.size[2]-2),'cobblestone')
        # Re-pave the common forecourt after staggering the east household.
        m.box((2,2,1),(28,2,4),'gravel')
    # Replace the excessively broad single roof with the reference's distinct silhouettes.
    if v in (3,4,5):
        for p in list(m.blocks):
            if p[1]>= (9 if v==3 else 8):m.set(*p,'air')
        if v==3:
            _roof(m,3,3,10,25,start=10);_roof(m,18,3,25,25,start=10)
            for x in (10,18):m.box((x,9,3),(x,15,25),'spruce_planks')
            for z in (3,25):m.box((10,9,z),(18,15,z),'spruce_planks')
            _roof(m,10,3,18,25,start=16)
            m.meta['roof_min_y']=9
        elif v==4:
            _roof(m,3,3,24,13,axis='x');_roof(m,3,13,13,25)
        else:
            _roof(m,3,3,13,26);_roof(m,21,3,29,15)
            _roof(m,13,20,29,26,start=8,axis='x')
    # Expand the base around the original fully furnished and navigable plan.
    oldw,oldh,oldd=m.size
    m.size=(oldw+6,max(oldh+3,32),oldd+8);m.meta['size']=list(m.size)
    _shift(m,3,4)
    # Only extend the foundation around the original platform, preserving indoor timber floors.
    for x in range(1,m.size[0]-1):
        for z in range(1,m.size[2]-1):
            if (x,2,z) not in m.blocks:
                m.box((x,0,z),(x,1,z),'cobblestone');m.set(x,2,z,'gravel')
    if v==1:
        _porch(m,8,4,16,6);_porch(m,19,13,21,22)
        m.box((20,3,18),(20,4,21),'spruce_log[axis=z]')
    elif v==2:
        _porch(m,7,5,15,8);_porch(m,21,8,29,11)
    elif v==3:
        _porch(m,14,4,20,6,roof=9)
        m.set(14,8,4,'red_wool')
    elif v==4:
        _porch(m,17,4,24,6)
    elif v==5:
        _porch(m,8,4,14,6);_porch(m,25,4,31,6)
    else:
        _porch(m,9,4,17,6)
        m.box((9,8,4),(17,8,6),'spruce_planks')
        for x in (9,17):m.box((x,3,4),(x,7,4),'dark_oak_log[axis=y]')
        m.box((9,9,4),(17,9,4),'spruce_fence[east=true,west=true]')
        for x in (9,17):m.box((x,9,5),(x,9,6),'spruce_fence[north=true,south=true]')
        m.door(13,9,7,wood='spruce',facing='north')
        m.point('balcony','circulation',(13,9,5),'阁楼前阳台',look_at=[13,10,2])
        _porch(m,21,12,22,23,roof=7)
    # Stone wall feet and renewed stove chimneys give a shared family vocabulary.
    for p,b in list(m.blocks.items()):
        if p[1]==3 and b[0] in ('minecraft:spruce_planks','minecraft:dark_oak_log'):
            x,y,z=p
            if any(m.blocks.get((x+dx,y,z+dz),('minecraft:air',()))[0] in ('minecraft:air','minecraft:gravel') for dx,dz in ((1,0),(-1,0),(0,1),(0,-1))):
                if b[0]=='minecraft:spruce_planks':m.set(x,y,z,'cobblestone')
        if b[0]=='minecraft:smoker':
            x,y,z=p;top=max(yy for (xx,yy,zz),block in m.blocks.items() if xx==x and zz==z and block[0]!='minecraft:air')
            m.box((x,y+1,z),(x,min(top+2,m.size[1]-2),z),'cobblestone')
    m.meta.update(name=names[v-1],source=f'tools/structure_studio/studio/northern_fill_homes.py:home({v})',
        planning_role='planning_role.fill',ground_plane=dict(y=3,note='外部院落与街面由Y2砾石/石基顶面承托，站立脚底Y3；阁楼与阳台另行计高。'),
        function_terms=['住宅','家庭居住'],design_notes=[notes[v-1]],differences=[notes[v-1]],
        preview_context=dict(kind='flat',land_surface_y=3,bed_y=-2,padding=3,surface='snow'))
    m.meta['terrain']['选址']='稳定、排水良好的背风聚落地块，完整承托石基并留出门前通路；住宅不要求临海。'
    for p in m.meta['points']:
        if p['kind']=='entrance':
            m.meta['connections'].append(dict(kind='pedestrian',pos=p['pos'],direction='north',clearance=[2,3],note='外部街面脚底Y3，同高进入木门廊'))
    return m


BUILDERS={f'NS-05-v{v:02d}':partial(home,v) for v in range(1,7)}
