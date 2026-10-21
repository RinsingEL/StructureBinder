"""Compact European buildings: authored rooms, actual circulation, no parcel plinth.

Run from tools/structure_studio: python -m studio.european_village [asset IDs].
Old European assets are never overwritten by this module.
"""
from functools import partial
from pathlib import Path
import argparse
import json

from .model import Model, write_json
from .european_revision2 import roof
from .server import TOOL, CATALOG
from .validate import validate

DEST = 'R04_european_reborn'


def base(number, variant, name, w, d, h=18, terms=('家庭居住',)):
    family = f'EU-{number:02d}'
    m = Model(f'{family}-v{variant:02d}', name, (w, h, d), family=family,
              civilization='欧洲中世纪新制', role='fill')
    m.meta.update(function_terms=list(terms), workplace_policy='authored',
        source='tools/structure_studio/studio/european_village.py:BUILDERS',
        ground_plane={'y': 2, 'note': '首层脚底 Y=2；仅本体基础与门前踏步，院落另组。'},
        terrain={'选址': '建筑基础轮廓需稳定承载；门前与街面脚底高程 Y=2，屋檐不可侵入相邻建筑。'},
        preview_context={'kind': 'flat', 'land_surface_y': 2, 'padding': 3},
        design_notes=['2026-10-21 紧凑日常建筑；实际房间与通路决定容量；无整块地块平台。'],
        differences=[], floors=[{'name':'首层', 'y':1,'max_y':5}], roof_min_y=6)
    return m


def room_shell(m, bounds, floor=1, height=4, plaster='white_terracotta', name='生活室'):
    x0,z0,x1,z1=bounds
    m.box((x0,0,z0),(x1,floor,z1),'cobblestone') if floor==1 else None
    m.box((x0,floor,z0),(x1,floor,z1),'spruce_planks')
    m.box((x0,floor+1,z0),(x1,floor+height,z1),plaster)
    m.box((x0+1,floor+1,z0+1),(x1-1,floor+height,z1-1),'air')
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,floor+1,z),(x,floor+height,z),'dark_oak_log')
    for z in (z0,z1):m.box((x0,floor+height,z),(x1,floor+height,z),'dark_oak_log[axis=x]')
    for x in (x0,x1):m.box((x,floor+height,z0),(x,floor+height,z1),'dark_oak_log[axis=z]')
    m.room(f'room_{len(m.meta["rooms"])}',name,(x0+1,floor+1,z0+1),(x1-1,floor+height,z1-1),name)


def entry(m,x,z=2,y=2,side=False):
    m.door(x,y,z,facing='east' if side else 'south')
    p=(x-1,y,z) if side else (x,y,z-1)
    m.set(p[0],y-1,p[2],'cobblestone')
    m.point(f'entry_{len(m.meta["points"])}','entrance',p,'门前步行入口',facing='west' if side else 'north')


def windows(m,bounds,y=3):
    x0,z0,x1,z1=bounds
    for x in (x0,x1):
        for z in (z0+2,z1-2):
            m.set(x,y,z,'glass_pane');m.set(x,y-1,z,'spruce_planks')
    for x in (x0+2,x1-2):m.set(x,y,z1,'glass_pane')
    for x in (x0+2,x1-2):m.set(x,y,z0,'glass_pane')


def cross_roof(m,bounds,eave,material='brick',clip_eaves=False):
    class Transposed:
        def set(self,x,y,z,block):
            if clip_eaves and not 0<=z<m.size[0]:return
            block=block.replace('facing=east','facing=south').replace('facing=west','facing=north')
            if 'axis=x' in block:block=block.replace('axis=x','axis=z')
            elif 'axis=z' in block:block=block.replace('axis=z','axis=x')
            m.set(z,y,x,block)
        def box(self,a,b,block):
            for x in range(a[0],b[0]+1):
                for y in range(a[1],b[1]+1):
                    for z in range(a[2],b[2]+1):self.set(x,y,z,block)
    x0,z0,x1,z1=bounds;roof(Transposed(),(z0,x0,z1,x1),eave,material)


def awning(m,x0,x1,z,y=5):
    for x in range(x0,x1+1):
        m.set(x,y,z,'spruce_slab')
        m.set(x,y,z+1,'spruce_stairs[facing=south]')
    for x in (x0,x1):
        m.set(x,y-1,z,'spruce_fence')


def frontage(m,bounds,floor=1):
    x0,z0,x1,z1=bounds
    for x in range(x0+3,x1,3):
        if x==m.size[0]//2:continue
        m.set(x,floor+1,z0,'dark_oak_log')
        m.set(x,floor+4,z0,'dark_oak_log')


def light(m,x,y,z):
    m.set(x,y+1,z,'dark_oak_log[axis=x]')
    m.set(x,y,z,'lantern[hanging=true]')


def bed(m,x,y,z,key,use='resident',color='red'):
    m.bed(x,y,z,color=color,facing='north')
    m.point(key,'bed',(x,y,z),'病床' if use=='care' else '客床' if use=='public' else '住床',
            approach=(x,y,z+1),use=use)


def station(m,x,y,z,block,name,approach=None):
    m.set(x,y,z,block)
    m.point(f'work_{len(m.meta["points"])}','work',(x,y,z),name,
            approach=approach or (x+1,y,z),use='staff')


def seat(m,x,y,z,face='east',use='public'):
    m.set(x,y,z,f'spruce_stairs[facing={face}]')
    dx,dz={'east':(1,0),'west':(-1,0),'north':(0,-1),'south':(0,1)}[face]
    m.point(f'seat_{len(m.meta["points"])}','seat',(x,y,z),'座位',
            approach=(x+dx,y,z+dz),use=use,adapter='marker_seat',seat_height=.5)


def hearth(m,x,z,y=2):
    m.set(x,y,z,'smoker[facing=east]')
    m.set(x,y+1,z,'bricks')
    for yy in range(y+2, min(m.size[1]-1, y+9)):
        m.set(x,yy,z,'bricks')
    m.set(x,min(m.size[1]-1,y+9),z,'brick_slab')


def stair(m,x,z,lower=1):
    # Five rises to the next full floor, with 2-block headroom and a top landing.
    for i in range(5):
        m.box((x,lower+1+i,z+i),(x,lower+4+i,z+i),'air')
        m.set(x,lower+1+i,z+i,'spruce_stairs[facing=south]')
    m.set(x,lower+5,z+5,'spruce_planks')
    m.point(f'landing_{len(m.meta["points"])}','circulation',(x,lower+6,z+5),'实体楼梯上层平台')


def cottage(v=1):
    w,d=(9,11) if v==1 else (9,13)
    m=base(27,v,'木架小屋 · '+('端门双床与灶角' if v==1 else '侧门长屋与储物间'),w,d)
    b=(1,2,w-2,d-2);room_shell(m,b);windows(m,b)
    roof(m,b,6)
    entry(m,w//2) if v==1 else entry(m,1,5,side=True)
    for x in (3,5):bed(m,x,2,d-4,f'bed_{x}')
    hearth(m,2,3);m.set(2,2,6 if v==2 else 5,'barrel[facing=east]')
    seat(m,w-3,2,4,'west',use='resident')
    light(m,4,5,5)
    if v==1:awning(m,3,5,1)
    m.meta['differences']=['端门与侧门改变入户路线；床位、灶间、储物均为实际方块。']
    return m


def family(v=1):
    w,d=(13,15) if v==1 else (11,15)
    m=base(28,v,'半木架家宅 · '+('双层山墙四床' if v==1 else '窄面侧廊六床'),w,d,23)
    b=(1,2,w-2,d-2)
    room_shell(m,b,name='起居与灶间');windows(m,b)
    room_shell(m,b,6,name='家庭寝室');windows(m,b,8);roof(m,b,11)
    entry(m,5);stair(m,w-3,4)
    for x in (3,5,7) if v==2 else (3,5):
        bed(m,x,7,d-4,f'upper_bed_{x}')
    if v==1:
        for x in (3,5):bed(m,x,7,6,f'front_bed_{x}')
    else:
        for x in (3,5,7):bed(m,x,7,6,f'front_bed_{x}')
    hearth(m,2,3);m.box((2,2,8),(2,3,10),'barrel[facing=east]')
    for z in (5,7):seat(m,3,2,z,use='resident')
    m.box((5,2,5),(5,2,7),'spruce_planks')
    light(m,5,5,9);light(m,5,10,8)
    frontage(m,b,6)
    m.set(3,8,2,'glass_pane');m.set(4,8,2,'glass_pane')
    m.set(w-4,8,2,'glass_pane');m.set(w-5,8,2,'glass_pane')
    m.meta.update(floors=[{'name':'起居灶间','y':1,'max_y':5},{'name':'家庭寝室','y':6,'max_y':10}],roof_min_y=11,
                  differences=['双层实际楼板与直跑楼梯；寝室和起居分层，楼梯平台保持净空。'])
    return m


def shop(v=1):
    w,d=(9,11) if v!=2 else (11,11)
    m=base(33,v,'小商铺 · '+{1:'杂货前店后仓',2:'工具转角售卖',3:'药草采光柜台'}[v],w,d,17,('零售',))
    b=(1,2,w-2,d-2);room_shell(m,b,name='售卖与储货');windows(m,b);roof(m,b,6)
    entry(m,5)
    goods={1:'barrel[facing=east]',2:'smithing_table',3:'brewing_stand'}[v]
    m.box((2,2,4),(2,2,d-3),'spruce_planks')
    for z in (5,7):m.set(2,3,z,goods)
    station(m,w-3,2,d-3,'barrel[facing=north]','店员理货与售卖',(w-4,2,d-3))
    if v==2:
        m.box((w-2,3,4),(w-2,3,6),'air')
        m.box((w-2,2,4),(w-2,2,6),'spruce_slab[type=top]')
    if v==3:
        for z in (4,6):m.set(1,3,z,'glass')
        m.set(2,3,4,'potted_fern')
    light(m,4,5,6)
    # A glazed sales window, lower canopy and external ledge distinguish the shop.
    m.box((2,3,2),(3,3,2),'glass_pane')
    m.box((2,2,1),(3,2,1),'spruce_slab[type=top]')
    awning(m,1,w-2,0)
    m.meta['differences']=['商品对应陈列与操作面；工具版增加侧窗售卖面，药草版增加采光和药柜。']
    return m


def farmhouse(v=1):
    w,d=13,15
    m=base(40,v,'农舍 · '+('低矮横厅与四床后室' if v==1 else '分隔寝室与侧入灶间'),w,d,19)
    b=(1,2,11,13);room_shell(m,b,name='农家起居');windows(m,b);cross_roof(m,b,6,'stone_brick' if v==2 else 'brick')
    entry(m,6) if v==1 else entry(m,1,5,side=True)
    m.box((2,2,8),(10,4,8),'spruce_planks');m.box((6,2,8),(6,3,8),'air')
    for x in (3,5,7,9):bed(m,x,2,11,f'bed_{x}')
    hearth(m,2,3);m.set(2,2,6,'barrel[facing=east]')
    for z in (4,6):seat(m,9,2,z,'west',use='resident')
    m.box((7,2,4),(7,2,6),'spruce_planks')
    light(m,6,5,5);light(m,6,5,10)
    awning(m,4,8,0);frontage(m,b)
    m.meta['differences']=['前厅与后部四床寝室实体隔断；谷仓和农院独立，不捆绑底板。']
    return m


def carpenter(v=1):
    m=base(42,v,'木匠作坊 · '+('临街封闭工房' if v==1 else '林边开敞工棚'),11,13,18,('木器制作',))
    b=(1,2,9,11);room_shell(m,b,name='锯台与工具间');windows(m,b);roof(m,b,6,'stone_brick')
    entry(m,5)
    m.box((3,3,2),(7,4,2),'air')
    m.box((3,2,2),(7,2,2),'spruce_slab[type=top]')
    entry(m,5)
    if v==2:
        m.box((2,2,2),(8,4,2),'air');m.meta['points']=[]
        m.set(5,1,1,'cobblestone');m.point('entry','entrance',(5,2,1),'工棚开放入口')
        m.box((1,2,4),(1,4,8),'air')
    station(m,2,2,5,'crafting_table','木工装配台')
    station(m,8,2,7,'stonecutter[facing=west]','锯切占位工台',(7,2,7))
    m.box((2,2,9),(4,2,10),'stripped_spruce_log[axis=z]')
    m.set(8,2,4,'barrel[facing=west]');light(m,5,5,6)
    m.meta['differences']=['实际双操作面与中央搬运道；林边版拆开前墙和侧墙形成开敞工作面。']
    return m


def chapel(v=1):
    m=base(43,v,'村庄礼拜堂 · '+('山墙钟架与单厅' if v==1 else '侧塔与石砌小堂'),11,17,23,('礼仪','集会'))
    b=(1,2,9,15);room_shell(m,b,height=5,plaster='stone_bricks',name='单厅礼拜空间')
    windows(m,b,4);roof(m,b,7,'stone_brick');entry(m,5)
    for z in (5,8,11):
        for x in (3,7):seat(m,x,2,z,'east' if x==3 else 'west')
    station(m,5,2,14,'lectern[facing=north]','礼拜主持',(5,2,13))
    for z in (5,10,14):light(m,5,6,z)
    for x in (0,10):
        for z in (4,9,14):
            m.box((x,0,z),(x,3,z),'stone_bricks')
            m.set(x,4,z,'stone_brick_stairs[facing='+('east' if x==0 else 'west')+']')
    m.box((4,5,2),(6,6,2),'yellow_stained_glass')
    bx=5 if v==1 else 2
    for x in (bx-1,bx+1):m.box((x,2,3),(x,13,3),'dark_oak_log')
    if v==2:
        m.box((1,2,2),(3,10,4),'stone_bricks')
        m.box((2,3,2),(2,8,2),'glass_pane')
        m.box((1,11,2),(3,11,4),'stone_bricks')
    m.box((bx-1,14,3),(bx+1,14,3),'dark_oak_log[axis=x]')
    m.set(bx,13,3,'bell[attachment=ceiling]')
    for x in range(bx-2,bx+3):
        m.box((x,15+2-abs(x-bx),2),(x,15+2-abs(x-bx),4),'stone_brick_stairs[facing='+('east' if x<=bx else 'west')+']')
    m.meta.update(roof_min_y=7,floors=[{'name':'礼拜厅','y':1,'max_y':6}],
                  differences=['六席、中央通道和主持站位，村级小堂不缩印大型教堂。'])
    return m


BUILDERS={}
for number,fn,count in ((27,cottage,2),(28,family,2),(33,shop,3),(40,farmhouse,2),(42,carpenter,2),(43,chapel,2)):
    for v in range(1,count+1):BUILDERS[f'EU-{number:02d}-v{v:02d}']=partial(fn,v)


def finalize(fn):
    m=fn()
    # Export explicit neighbor states: the offline NBT viewer does not run world
    # block updates. Unconnected fences otherwise look like isolated posts.
    number=int(m.meta['family'].split('-')[1])
    if number>=27 or number in (5,21):
        directions={'north':(0,-1),'south':(0,1),'west':(-1,0),'east':(1,0)}
        for (x,y,z),(name,props) in list(m.blocks.items()):
            if not name.endswith(('_fence','_pane')):continue
            values=dict(props)
            for direction,(dx,dz) in directions.items():
                neighbor=m.blocks.get((x+dx,y,z+dz),('minecraft:air',()))[0]
                solid=neighbor.endswith(('_planks','_log','_wood','_terracotta','_bricks',':bricks',':cobblestone',':stone',':glass'))
                compatible=neighbor.endswith(('_fence','_fence_gate')) if name.endswith('_fence') else neighbor.endswith('_pane')
                values[direction]='true' if solid or compatible else 'false'
            m.blocks[(x,y,z)]=(name,tuple(sorted(values.items())))
    top=max(y for (x,y,z),(name,props) in m.blocks.items() if name!='minecraft:air')
    top=max(top,max((r['max'][1] for r in m.meta['rooms']),default=0))
    m.blocks={p:b for p,b in m.blocks.items() if p[1]<=top}
    m.size=(m.size[0],top+1,m.size[2]);m.meta['size']=list(m.size)
    return m


def get_builders():
    from .european_services import BUILDERS as SERVICES
    from .european_regions import BUILDERS as REGIONS
    from .european_revisions import BUILDERS as REVISIONS
    return {k:partial(finalize,fn) for k,fn in (BUILDERS|SERVICES|REGIONS|REVISIONS).items()}


def main():
    builders=get_builders()
    p=argparse.ArgumentParser();p.add_argument('ids',nargs='*');args=p.parse_args()
    ids=args.ids or list(builders)
    registry=json.loads((TOOL/'.cache/registry.json').read_text(encoding='utf-8'))
    results=[]
    for key in ids:
        m=builders[key]();dest=CATALOG/DEST/'models'/key
        m.export(dest,registry);r=validate(dest,registry)
        row={'id':key,'size':m.size,'passed':r['passed'],'warnings':r['warnings'],'errors':r['errors'],'navigation':r['navigation']}
        results.append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
    write_json(TOOL/'runtime/european-build.json',results)
    raise SystemExit(0 if all(r['passed'] and not r['warnings'] and r['navigation']['passed'] for r in results) else 1)


if __name__=='__main__':main()
