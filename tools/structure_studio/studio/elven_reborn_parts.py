"""New elven architecture: cut-corner plans, pointed arches and living terraces.

Only generic Model primitives are shared with other cultures. All geometry here
is newly authored for the 2026-10-05 reference and has no old FS-layout fallback.
"""
import math
from .model import Model

STONE='smooth_quartz'
EDGE='quartz_pillar'
ROOF='dark_prismarine'


def base(n,name,size,terms,role='key',tags=()):
    m=Model(f'EL-{n:02d}-v01',name,size,family=f'EL-{n:02d}',civilization='精灵新制',role=role,
            terrain={'选址':'稳定平地或已施工的岩台，外部步行地坪与 Y=2 对齐；保留尖顶和树冠全尺寸净空。',
                     '风格':'细长白石尖拱、绿色尖顶、切角平面、露台与枝叶结合。'})
    w,h,d=size
    m.box((0,0,0),(w-1,1,d-1),'stone_bricks')
    m.box((1,1,1),(w-2,1,d-2),'moss_block')
    m.box((0,2,0),(w-1,h-1,d-1),'air')
    m.meta.update(source='tools/structure_studio/studio/elven_reborn.py:BUILDERS',function_terms=terms,
                  asset_tags=list(tags),ground_plane={'y':2,'note':'本地 Y=2 为地面行走脚底；室内和露台的高差均有实体台阶。'},
                  roof_min_y=12,floors=[{'name':'地面与室内','y':1,'max_y':7}],
                  preview_context={'kind':'flat','land_surface_y':2,'padding':3,'surface':'grass'},
                  design_notes=['2026-10-05 从零设计；只参考用户五风格示意图的精灵建筑语言，不继承旧精灵布局。',
                                '尖拱、露台和青绿高尖顶属于建筑结构；树冠不代替真实墙体、楼梯或通路。'])
    return m


def path(m,x0,z0,x1,z1,y=1):m.box((x0,y,z0),(x1,y,z1),'smooth_quartz')


def oct_cells(x0,z0,x1,z1,cut=3):
    return {(x,z) for x in range(x0,x1+1) for z in range(z0,z1+1)
            if min(x-x0,x1-x)+min(z-z0,z1-z)>=cut}


def pavilion(m,key,name,bounds,floor=2,height=9,cut=3,roof_height=9):
    x0,z0,x1,z1=bounds;cells=oct_cells(*bounds,cut)
    boundary={p for p in cells if any((p[0]+dx,p[1]+dz) not in cells for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)])}
    for x,z in cells:
        for y in range(1,floor+1):m.set(x,y,z,'polished_diorite' if y<floor else 'birch_planks')
        if (x,z) in boundary:
            for y in range(floor+1,floor+height+1):m.set(x,y,z,'calcite')
            m.set(x,floor,z,STONE)
        else:
            for y in range(floor+1,floor+height+1):m.set(x,y,z,'air')
    # Long window bays stop well below the pointed roof.
    for x in range(x0+cut+2,x1-cut,5):
        for z in (z0,z1):arch(m,x,z,floor+2,3,height-3,glass=True,axis='x')
    for z in range(z0+cut+2,z1-cut,5):
        for x in (x0,x1):arch(m,z,x,floor+2,3,height-3,glass=True,axis='z')
    for x,z in sorted(boundary):
        if (x in (x0,x1) and z in (z0+cut,z1-cut)) or (z in (z0,z1) and x in (x0+cut,x1-cut)):
            for y in range(floor+1,floor+height+2):m.set(x,y,z,EDGE)
            m.set(x,floor+height+2,z,'quartz_slab')
    spire_roof(m,bounds,floor+height+1,roof_height,cut)
    # Follow the stepped roof at the actual wall ring, closing the bearing gap.
    for x,z in boundary:
        roof_y=next((yy for yy in range(floor+height+1,floor+height+roof_height+2)
                     if m.blocks.get((x,yy,z),('minecraft:air',))[0] in ('minecraft:dark_prismarine','minecraft:waxed_oxidized_cut_copper')),None)
        if roof_y is not None:
            for yy in range(floor+height+1,roof_y):m.set(x,yy,z,STONE)
    m.room(key,name,(x0+cut+1,floor+1,z0+cut+1),(x1-cut-1,floor+height-1,z1-cut-1),name)
    return cells


def spire_roof(m,bounds,eave,height,cut=3):
    x0,z0,x1,z1=bounds;cx=(x0+x1)//2;cz=(z0+z1)//2
    # Hollow nested outlines rise steeply and end in a slender ridge/spire.
    rx=(x1-x0)/2+1;rz=(z1-z0)/2+1
    for dy in range(height+1):
        t=dy/height
        ax=max(0,int(round(rx*(1-t))));az=max(0,int(round(rz*(1-t))))
        c=min(max(0,cut-int(t*cut)),ax,az)
        cells=oct_cells(cx-ax,cz-az,cx+ax,cz+az,c)
        next_t=min(1,(dy+1)/height)
        nx=max(0,int(round(rx*(1-next_t))));nz=max(0,int(round(rz*(1-next_t))))
        nc=min(max(0,cut-int(next_t*cut)),nx,nz)
        next_cells=oct_cells(cx-nx,cz-nz,cx+nx,cz+nz,nc)
        for x,z in cells:
            if dy==height or (x,z) not in next_cells or any((x+dx,z+dz) not in cells for dx,dz in ((1,0),(-1,0),(0,1),(0,-1))):
                m.set(x,eave+dy,z,ROOF)
        # Pale raised ribs give the silhouette a fine framework.
        for x,z in ((cx-ax+c,cz-az),(cx+ax-c,cz-az),(cx-ax+c,cz+az),(cx+ax-c,cz+az)):
            if 0<=x<m.size[0] and 0<=z<m.size[2]:m.set(x,eave+dy,z,'waxed_oxidized_cut_copper')
    m.set(cx,eave+height+1,cz,'end_rod')
    m.set(cx,eave+height+2,cz,'gold_block')
    m.set(cx,eave+height+3,cz,'end_rod')


def arch(m,c,z,y,width,height,*,glass=False,axis='x'):
    half=width//2
    def put(u,v,b):m.set(u,v,z,b) if axis=='x' else m.set(z,v,u,b)
    for u in range(c-half-1,c+half+2):
        for yy in range(y,y+height+1):put(u,yy,STONE)
    for off in range(-half,half+1):
        ceiling=y+height-abs(off)-1
        for yy in range(y,ceiling+1):put(c+off,yy,'light_blue_stained_glass' if glass else 'air')
    put(c,y+height+1,'quartz_stairs[facing=south]' if axis=='x' else 'quartz_stairs[facing=east]')


def entrance(m,key,x,z,floor,width=3,*,height=7,side='north'):
    arch(m,x,z,floor+1,width,height)
    step_n=floor-1
    for i in range(step_n):
        zz=z-step_n+i;yy=2+i
        m.box((x-width//2,1,zz),(x+width//2,yy-1,zz),'polished_diorite')
        m.box((x-width//2,yy,zz),(x+width//2,yy,zz),'quartz_stairs[facing=south]')
    m.point(key,'circulation',(x,floor+1,z),key)


def stair_north(m,x0,x1,z0,base_y,steps):
    for i in range(steps):
        z=z0+i;y=base_y+i
        if y>1:m.box((x0,1,z),(x1,y-1,z),'polished_diorite')
        m.box((x0,y,z),(x1,y,z),'quartz_stairs[facing=south]')
        m.box((x0,y+1,z),(x1,y+3,z),'air')


def rail(m,x0,z0,x1,z1,floor):
    m.box((x0,floor+1,z0),(x1,floor+1,z1),'quartz_slab')
    for x,z in ((x0,z0),(x1,z1)):m.set(x,floor+1,z,'quartz_pillar')


def branch_tree(m,x,z,height=13,radius=5,*,silver=True):
    trunk='birch_log' if silver else 'stripped_oak_log'
    leaves='birch_leaves[persistent=true]' if silver else 'azalea_leaves[persistent=true]'
    for y in range(2,height+1):
        bend=1 if y>height*0.65 else 0
        m.set(x+bend,y,z,trunk)
        if y<5:m.set(x,y,z+1,trunk)
    clusters=[]
    for dx,dz,dy in ((-radius,0,-3),(radius,1,-2),(-2,-radius,-1),(1,radius,-3),(0,0,2)):
        for t in range(1,max(abs(dx),abs(dz))+1):
            n=max(abs(dx),abs(dz));xx=x+round(dx*t/n);zz=z+round(dz*t/n);yy=height-4+round((dy+4)*t/n)
            m.set(xx,yy,zz,trunk)
        clusters.append((x+dx,height+dy,z+dz))
    for cx,cy,cz in clusters:
        for dx in range(-3,4):
            for dz in range(-3,4):
                for dy in range(-2,3):
                    if dx*dx+dz*dz+dy*dy*2<=11 and m.inside((cx+dx,cy+dy,cz+dz)):
                        if (cx+dx,cy+dy,cz+dz) not in m.blocks or m.blocks[(cx+dx,cy+dy,cz+dz)][0]=='minecraft:air':
                            m.set(cx+dx,cy+dy,cz+dz,leaves)
    for dx,dz in ((-1,0),(1,0),(0,-1),(0,1)):
        m.set(x+dx,2,z+dz,'moss_block')


def planter(m,x,z,flower='allium'):
    m.set(x,1,z,'moss_block');m.set(x,2,z,flower)


def seat(m,x,y,z,facing='south'):
    m.set(x,y,z,f'spruce_stairs[facing={facing}]')


def desk(m,x,y,z):
    m.set(x,y,z,'birch_planks');m.set(x,y+1,z,'lantern')


def bookcase(m,x0,z0,x1,z1,y=3,height=3):
    m.box((x0,y,z0),(x1,y+height-1,z1),'bookshelf')


def lamp(m,x,z,y=2):
    m.box((x,y,z),(x,y+4,z),'quartz_pillar')
    m.set(x,y+5,z,'quartz_slab')
    m.set(x-1,y+4,z,'quartz_stairs[facing=east,half=top]')
    m.set(x+1,y+4,z,'quartz_stairs[facing=west,half=top]')
    m.set(x-1,y+3,z,'soul_lantern[hanging=true]');m.set(x+1,y+3,z,'soul_lantern[hanging=true]')
