"""New European town geometry: timber frames, steep gables and crenellated stone."""
from .model import Model

CATALOG_DIR='R04_european_reborn'


def base(n,name,size,terms,role='key',tags=()):
    m=Model(f'EU-{n:02d}-v01',name,size,family=f'EU-{n:02d}',civilization='欧洲中世纪新制',role=role)
    w,h,d=size
    m.box((0,0,0),(w-1,1,d-1),'stone_bricks')
    m.box((1,1,1),(w-2,1,d-2),'cobblestone')
    m.box((0,2,0),(w-1,h-1,d-1),'air')
    m.meta.update(source='tools/structure_studio/studio/european_reborn.py:BUILDERS',function_terms=list(terms),asset_tags=list(tags),
      terrain={'选址':'稳定平地或已施工承载台；外围道路脚底与本地 Y=2 对齐。','形体':'石砌防御与宗教建筑、木骨架城镇建筑独立设计；保留屋顶和塔楼净空。'},
      ground_plane={'y':2,'note':'外部步行地面；上层经实体楼梯连接。'},
      preview_context={'kind':'flat','land_surface_y':2,'padding':3,'surface':'grass'},
      floors=[{'name':'地面功能层','y':1,'max_y':7}],roof_min_y=10,
      design_notes=['从零编写欧洲中世纪新系列；城堡、教堂、会馆和市场有各自平面和承重结构，未从其他文明换皮生成。',
                    '室内陈设与机器、市场、礼仪用途为静态模型说明，未接入游戏行为。'])
    return m


def entry(m,x):m.point('street','entrance',(x,2,0),'外部街道',facing='north')


def work(m,key,x,y,z,name):m.point(key,'work',(x,y,z),name,approach=(x,y,z))


def shell(m,bounds,floor=1,height=7,material='stone_bricks'):
    x0,z0,x1,z1=bounds
    m.box((x0,floor,z0),(x1,floor,z1),'stone_bricks')
    m.box((x0,floor+1,z0),(x1,floor+height,z1),material)
    m.box((x0+1,floor+1,z0+1),(x1-1,floor+height,z1-1),'air')
    m.box((x0+1,floor,z0+1),(x1-1,floor,z1-1),'spruce_planks')


def gable(m,bounds,eave,material='brick',axis='z'):
    x0,z0,x1,z1=bounds
    # Continuous wall plates meet the inward-rising roof, not just its outer eave.
    for x in (x0,x1):
        m.box((x,eave-1,z0),(x,eave,z1),'dark_oak_log[axis=z]' if material=='brick' else 'stone_bricks')
    # z-axis ridge; stairs on opposing slopes and filled end gables.
    for x in range(x0-1,x1+2):
        rise=min(x-x0+1,x1+1-x);y=eave+rise
        face='east' if x<=(x0+x1)//2 else 'west'
        m.box((x,y,z0-1),(x,y,z1+1),material+'_stairs[facing='+face+']')
        if x0<=x<=x1:
            for z in (z0,z1):m.box((x,eave,z),(x,y,z),'white_terracotta' if material=='brick' else 'stone_bricks')
    cx=(x0+x1)//2
    if material=='brick':
        for z in (z0,z1):
            m.box((cx,eave,z),(cx,eave+(x1-x0)//2,z),'dark_oak_log')
            for x in range(x0,x1+1):
                yy=eave+min(x-x0,x1-x)
                m.set(x,yy,z,'dark_oak_log[axis=x]')
                if yy>=eave+4:m.set(x,eave+4,z,'dark_oak_log[axis=x]')
    m.box((cx,eave+(x1-x0)//2+2,z0-1),(cx,eave+(x1-x0)//2+2,z1+1),material+'_slab')


def timber(m,bounds,floor=1,height=7):
    x0,z0,x1,z1=bounds
    shell(m,bounds,floor,height,'white_terracotta')
    for x in sorted({x0,x1,*range(x0+5,x1,5)}):
        for z in (z0,z1):m.box((x,floor+1,z),(x,floor+height,z),'dark_oak_log[axis=y]')
    for z in sorted({z0,z1,*range(z0+5,z1,5)}):
        for x in (x0,x1):m.box((x,floor+1,z),(x,floor+height,z),'dark_oak_log[axis=y]')
    for y in (floor+1,floor+height):
        for z in (z0,z1):m.box((x0,y,z),(x1,y,z),'dark_oak_log[axis=x]')
        for x in (x0,x1):m.box((x,y,z0),(x,y,z1),'dark_oak_log[axis=z]')


def opening(m,x,z,y=2,width=3,height=5):
    r=width//2
    m.box((x-r,y,z),(x+r,y+height-1,z),'air')
    for dx in (-r-1,r+1):m.box((x+dx,y,z),(x+dx,y+height,z),'chiseled_stone_bricks')
    m.box((x-r,y+height,z),(x+r,y+height,z),'stone_brick_slab')


def window(m,x,y,z,axis='x',glass='glass_pane',height=3):
    if axis=='x':m.box((x,y,z),(x+1,y+height-1,z),glass)
    else:m.box((x,y,z),(x,y+height-1,z+1),glass)


def stairs(m,x0,x1,z0,base_y,steps):
    for i in range(steps):
        y=base_y+i;z=z0+i
        m.box((x0,1,z),(x1,y-1,z),'stone_bricks')
        m.box((x0,y,z),(x1,y,z),'stone_brick_stairs[facing=south]')
        m.box((x0,y+1,z),(x1,y+3,z),'air')


def railing(m,x0,z0,x1,z1,floor):
    connections='east=true,west=true' if z0==z1 else 'north=true,south=true'
    m.box((x0,floor+1,z0),(x1,floor+1,z1),'spruce_fence['+connections+']')


def crenels(m,bounds,floor):
    x0,z0,x1,z1=bounds
    for z in (z0,z1):
        m.box((x0,floor+1,z),(x1,floor+1,z),'stone_brick_wall')
        for x in range(x0,x1+1,3):m.box((x,floor+1,z),(min(x+1,x1),floor+2,z),'stone_bricks')
    for x in (x0,x1):
        m.box((x,floor+1,z0),(x,floor+1,z1),'stone_brick_wall')
        for z in range(z0,z1+1,3):m.box((x,floor+1,z),(x,floor+2,min(z+1,z1)),'stone_bricks')


def table(m,x,y,z,w=5,d=2):
    for xx in (x,x+w-1):
        for zz in (z,z+d-1):m.set(xx,y,zz,'oak_fence')
    m.box((x,y+1,z),(x+w-1,y+1,z+d-1),'oak_slab')
    for xx in range(x,x+w,2):
        m.set(xx,y,z-1,'spruce_stairs[facing=south]');m.set(xx,y,z+d,'spruce_stairs[facing=north]')


def bed(m,key,x,y,z):
    m.bed(x,y,z,'red','south');m.point(key,'rest',(x,y,z),'床位',approach=(x+1,y,z))


def lamp(m,x,z):
    m.box((x,2,z),(x,6,z),'dark_oak_fence');m.set(x,7,z,'dark_oak_slab')
    m.set(x+1,6,z,'dark_oak_fence');m.set(x+1,5,z,'lantern[hanging=true]')
