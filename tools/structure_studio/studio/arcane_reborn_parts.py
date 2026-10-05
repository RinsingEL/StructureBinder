"""New Gothic academy vocabulary: pointed openings, steep roofs and real stairs.

No geometry is inherited from the rejected 07_arcane_academy assets.
"""
from math import hypot
from .model import Model

CATALOG_DIR = 'R02_arcane_reborn'
WALL = 'calcite'
TRIM = 'polished_andesite'
ROOF = 'purple_terracotta'


def base(number, name, size, *, role='key', tags=(), terms=()):
    m = Model(f'MG-{number:02d}-v01', name, size, family=f'MG-{number:02d}',
              civilization='魔法学院新制', role=role,
              terrain={'选址': '完整稳定基面，外部路面脚底与 Y=2 对齐，保留尖塔全高净空。',
                       '推荐情境': ['学院街区', '魔法学城']})
    w,h,d = size
    m.box((0,0,0),(w-1,1,d-1),'stone_bricks')
    m.box((0,2,0),(w-1,h-1,d-1),'air')
    m.box((1,1,1),(w-2,1,d-2),'smooth_stone')
    m.meta.update(source='tools/structure_studio/studio/arcane_reborn.py:BUILDERS',
        ground_plane={'y':2,'note':'街面和底层室内脚底 Y=2。高层由实体楼梯连接。'},
        preview_context={'kind':'flat','land_surface_y':2,'padding':3,'surface':'grass'},
        function_terms=list(terms),asset_tags=list(tags),
        floors=[{'name':'底层空间','y':1,'max_y':7}],roof_min_y=12,
        design_notes=['从用户五文明参考图提取魔法学院的尖塔群、深紫陡顶、尖拱与环廊，平面与尺寸独立设计。',
                      '紫晶、符文与仪器是原版方块静态表达；不声明魔法、传送或机器运行。'])
    return m


def rect(m,x0,z0,x1,z1,floor=1,top=12,material=WALL):
    m.box((x0,1,z0),(x1,floor,z1),'stone_bricks')
    m.box((x0,floor+1,z0),(x1,top,z1),material)
    m.box((x0+1,floor+1,z0+1),(x1-1,top,z1-1),'air')
    m.box((x0+1,floor,z0+1),(x1-1,floor,z1-1),'dark_oak_planks')
    for x in (x0,x1):
        for z in (z0,z1):
            m.box((x,floor+1,z),(x,top,z),TRIM)
    for z in (z0,z1):m.box((x0,top,z),(x1,top,z),'smooth_stone')


def gable(m,x0,z0,x1,z1,eave,axis='z',material=ROOF):
    """Steep 1:1 pitch, filled stone gables and dark outlined bargeboards."""
    lo,hi = (x0-1,x1+1) if axis=='z' else (z0-1,z1+1)
    a,b = (z0-1,z1+1) if axis=='z' else (x0-1,x1+1)
    put = (lambda u,v,y,c:m.set(u,y,v,c)) if axis=='z' else (lambda u,v,y,c:m.set(v,y,u,c))
    for u in (lo+1,hi-1):
        for v in range(a+1,b):put(u,v,eave,TRIM)
    for u in range(lo,hi+1):
        rise=min(u-lo,hi-u);yy=eave+rise
        for v in range(a,b+1):
            put(u,v,yy,'polished_blackstone_bricks' if v in (a,b) else material)
        for v in (a+1,b-1):
            for y in range(eave,yy):put(u,v,y,WALL)
        if rise==0:
            for v in range(a,b+1):put(u,v,yy-1,'smooth_stone_slab[type=top]')
    mid=(lo+hi)//2;peak=eave+min(mid-lo,hi-mid)
    for v in range(a,b+1):put(mid,v,peak+1,'polished_blackstone_brick_slab')


def arch(m,c,z,bottom,width=3,height=7,*,axis='x',glass=True):
    """Tall pointed arch, with a decreasing crown rather than a rectangular slot."""
    half=width//2
    for u in range(-half,half+1):
        top=bottom+height-1-abs(u)
        for y in range(bottom,top+1):
            x,zz=(c+u,z) if axis=='x' else (z,c+u)
            m.set(x,y,zz,'purple_stained_glass' if glass else 'air')
        x,zz=(c+u,z) if axis=='x' else (z,c+u)
        m.set(x,top+1,zz,TRIM)
    for u in (-half-1,half+1):
        x,zz=(c+u,z) if axis=='x' else (z,c+u)
        m.box((x,bottom,zz),(x,bottom+height-half,zz),TRIM)


def entry(m,c,z,*,floor=1,width=3,side='north',key=None):
    arch(m,c,z,floor+1,width,6,glass=False)
    if key:
        dz=1 if side=='north' else -1
        m.point(key,'circulation',(c,floor+1,z+dz),'门内通道')


def buttress(m,x,z,top,side):
    dx,dz={'west':(-1,0),'east':(1,0),'north':(0,-1),'south':(0,1)}[side]
    for offset in range(3):
        m.box((x+dx*offset,2,z+dz*offset),(x+dx*offset,top-offset*3,z+dz*offset),TRIM)
        m.set(x+dx*offset,top-offset*3+1,z+dz*offset,'stone_brick_slab')


def spire(m,cx,cz,radius,start,height):
    """Octagonal roof, supported by the tower rim. Gold finial is solid."""
    for dy in range(height):
        r=max(0,int(radius*(height-dy)/height))
        for x in range(cx-r,cx+r+1):
            for z in range(cz-r,cz+r+1):
                if abs(x-cx)+abs(z-cz)>r+max(1,r//2):continue
                m.set(x,start+dy,z,'polished_blackstone_bricks' if x==cx or z==cz else ROOF)
    m.set(cx,start+height,cz,'gold_block')
    m.set(cx,start+height+1,cz,'lightning_rod')


def tower(m,cx,cz,r,top,*,floor=1,roof_height=10,windows=True):
    for x in range(cx-r,cx+r+1):
        for z in range(cz-r,cz+r+1):
            if abs(x-cx)+abs(z-cz)>r+r//2:continue
            edge=abs(x-cx)==r or abs(z-cz)==r or abs(x-cx)+abs(z-cz)==r+r//2
            m.box((x,1,z),(x,floor,z),'stone_bricks')
            if edge:m.box((x,floor+1,z),(x,top,z),WALL)
            else:m.box((x,floor+1,z),(x,top,z),'air')
            if edge:m.set(x,top+1,z,TRIM)
    if windows:
        for y in range(floor+3,top-3,7):
            arch(m,cx,cz-r,y,3,5);arch(m,cx,cz+r,y,3,5)
            arch(m,cz,cx-r,y,3,5,axis='z');arch(m,cz,cx+r,y,3,5,axis='z')
    spire(m,cx,cz,r+1,top+2,roof_height)


def straight_stair(m,x,z,y0,y1,width=3,*,facing='south'):
    """Stair block at y0+1 climbs from floor y0 to floor y1; clears headroom."""
    dx,dz={'south':(0,1),'north':(0,-1),'east':(1,0),'west':(-1,0)}[facing]
    for i in range(y1-y0):
        for u in range(width):
            xx=x+dx*i+(u if dx==0 else 0);zz=z+dz*i+(u if dz==0 else 0);y=y0+1+i
            m.box((xx,y0+1,zz),(xx,y,zz),'stone_bricks')
            m.set(xx,y,zz,f'stone_brick_stairs[facing={facing}]')
            m.box((xx,y+1,zz),(xx,y+4,zz),'air')


def work(m,key,x,y,z,name,block='lectern[facing=south]',side='south'):
    m.set(x,y,z,block)
    dx,dz={'south':(0,1),'north':(0,-1),'east':(1,0),'west':(-1,0)}[side]
    m.point(key,'work',(x,y,z),name,approach=(x+dx,y,z+dz),look_at=[x,y+1,z])


def bed(m,key,x,y,z,color='purple'):
    m.bed(x,y,z,color,facing='north')
    m.point(key,'bed',(x,y,z),'学生床位',approach=(x+1,y,z))
    m.set(x-1,y,z-1,'barrel')


def planter(m,x,z,flower='allium'):
    m.set(x,1,z,'grass_block');m.set(x,2,z,flower)


def lamp(m,x,z,y=2,height=4):
    m.set(x,y,z,'polished_blackstone_bricks')
    m.box((x,y+1,z),(x,y+height,z),'polished_blackstone_wall')
    m.set(x,y+height+1,z,'sea_lantern')
    m.set(x,y+height+2,z,'amethyst_cluster')


def circle(m,cx,cz,r,y,block,*,ring=False):
    for x in range(cx-r,cx+r+1):
        for z in range(cz-r,cz+r+1):
            d=hypot(x-cx,z-cz)
            if d<=r and (not ring or d>r-1.2):m.set(x,y,z,block)


def entrance(m,x,z=0):m.point('front','entrance',(x,2,z),'街面入口',facing='north')
