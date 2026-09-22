"""Six root homes and six homes with independently planned forestry yards."""
from functools import partial
from .forest_symbiosis import base,ground,path,tree,lodge,pergola,entry,living,station,storage
from .components import shelf,bench
from .samples import railing


PLANS={
 1:((37,28,31),[(5,7,21,23,2)],(28,20,20,4),(25,4,33,11),'根旁单户','单户紧凑生活屋与东侧完整板根，共用前庭作为林路缓冲'),
 2:((43,28,36),[(5,7,27,19,2)],(34,26,21,4),(6,25,23,32),'长叶宅','横向长屋面向开阔林缘，后院承担户外家务；适合宽而浅的住宅地块'),
 3:((47,28,38),[(4,7,20,24,2),(26,7,42,24,2)],(23,30,21,3),(5,29,17,34),'双生根院','两户独立门户与生活设施，共用中央通路；树根位于通路末端而非门口'),
 4:((43,28,44),[(4,6,20,20,2),(4,26,20,40,2)],(31,31,22,4),(27,6,38,17),'回叶院居','两进独立居住空间围绕东向开敞院落；适合纵深林隙和多人家族'),
 5:((45,32,38),[(14,13,32,29,7)],(37,27,26,4),(17,4,29,9),'枝下高台宅','高台主体落在可见木柱上，长阶和桥台完整落地；不依靠攀梯进入住宅'),
 6:((47,29,42),[(4,6,20,21,2),(27,23,43,38,4)],(36,12,23,4),(5,28,19,36),'分台根居','南侧升高两格的第二生活单元，由实体缓阶连接；仅适合预先整备的低差坡台'),
}


def furnished_home(m,key,x0,z0,x1,z1,f=2,beds=1):
    """A household occupies the room, with a central route and a screened bedroom."""
    y=f+1;mid=(x0+x1)//2;split=z1-5
    # The kitchen is an L of washable preparation, stove, water and dry storage.
    m.box((x1-2,y,z0+2),(x1-2,y,z0+6),'spruce_planks')
    m.box((mid+2,y,z0+2),(x1-2,y,z0+2),'spruce_planks')
    station(m,key+'cook',x1-2,y,z0+3,'smoker[facing=west]','厨房炉灶与连续备餐台',approach=(x1-3,y,z0+3))
    m.box((x1-2,y+1,z0+3),(x1-2,f+12,z0+3),'cobblestone')
    m.set(x1-2,y,z0+5,'water_cauldron[level=3]')
    m.set(mid+2,y,z0+2,'barrel[facing=south]')
    m.set(x1-3,y+1,z0+2,'flower_pot')
    m.set(x1-2,y+1,z0+6,'lantern')
    # Long, usable dining table and opposed seating occupy the front-left bay.
    tx=x0+3;tz=z0+(3 if split-z0<9 else 4);tw=max(3,mid-x0-4)
    m.box((tx,y,tz),(tx+tw-1,y,tz+1),'spruce_slab[type=top]')
    bench(m,tx,y,tz-1,tw,'south');bench(m,tx,y,tz+2,tw,'north')
    m.set(tx+tw-1,y+1,tz,'flower_pot')
    m.point(key+'dining','work',(tx+1,y,tz),'家庭用餐与共桌家务',approach=(tx-1,y,tz))
    # A partition with an actual door gives sleeping occupants privacy.
    m.box((x0+1,y,split),(mid-2,y+2,split),'spruce_planks')
    m.door(mid-3,y,split,'spruce','north')
    m.box((mid-2,y,split+1),(mid-2,y+2,z1-1),'spruce_planks')
    for i in range(beds):
        bx=x0+2+i*3
        m.bed(bx,y,z1-2,'green','north')
        m.point(key+'bed'+str(i),'sleep',(bx,y,z1-2),'屏风后独立床位',approach=(bx+1,y,z1-2))
        m.set(bx,y,z1-1,'barrel[facing=up]')
    m.set(x0+1,y+1,split+1,'lantern')
    # Wardrobe and a reading corner face each other across the rear family bay.
    storage(m,key+'clothes',mid,y,z1-1,max(3,x1-mid-2),'分格衣物与家庭布品柜')
    m.meta['points'][-1]['approach']=[mid,y,z1-2]
    rx=mid+2;rz=split+1
    m.box((rx,y,rz),(x1-3,y,rz),'spruce_slab[type=top]')
    m.set(rx,y+1,rz,'lantern');m.set(x1-3,y,rz,'lectern[facing=south]')
    bench(m,rx,y,rz+2,max(2,x1-rx-2),'north')
    m.point(key+'read','work',(x1-3,y,rz),'起居读写与缝补长桌',approach=(x1-2,y,rz))
    # Extra depth becomes a domestic work niche, rather than an empty hall.
    if split-z0>=10:
        station(m,key+'mend',x0+2,y,split-2,'loom[facing=east]','日常衣物修补',approach=(x0+3,y,split-2))
        m.set(x0+2,y,split-3,'barrel[facing=up]')
        m.box((mid+2,f,split-2),(x1-4,f,split-1),'moss_block')
    m.room(key+'day','厨房餐叙与家务',(x0+1,y,z0+1),(x1-1,f+5,split-1),'连续厨房操作台、水盆、食品柜和成组餐桌椅；入口中轴连接后部起居')
    m.room(key+'night','睡眠与起居分区',(x0+1,y,split),(x1-1,f+5,z1-1),'有门屏风保护床位；衣物柜、读写桌和座椅组成后部家庭空间')


def home(v,forester=False):
    size,houses,t,work,title,note=PLANS[v]
    family='FS-06' if forester else 'FS-01'
    m=base(f'{family}-v{v:02d}',title+(' · 林务住宅' if forester else ' · 树根住宅'),size,note)
    m.meta['source']='tools/structure_studio/studio/forest_life.py:home'
    ground(m,2,2,size[0]-3,size[2]-3)
    if v==6:ground(m,24,21,44,39,4)
    for i,(x0,z0,x1,z1,f) in enumerate(houses):
        lodge(m,x0,z0,x1,z1,f,'hip' if v in (1,4,5) else 'gable')
        furnished_home(m,'unit'+str(i),x0,z0,x1,z1,f,beds=2 if v in (2,5) else 1)
        if v!=5:path(m,(x0+x1)//2-1,z0-3,(x0+x1)//2+1,z0-1,f)
    if v==5:
        rail_nodes=set()
        m.box((14,0,13),(32,6,29),'air')
        m.box((14,0,13),(32,1,29),'mossy_cobblestone');m.box((14,2,13),(32,2,29),'moss_block')
        for x in (14,23,32):
            for z in (13,21,29):m.box((x,0,z),(x,6,z),'dark_oak_log[axis=y]')
        for z in (13,21,29):m.box((14,6,z),(32,6,z),'dark_oak_log[axis=x]')
        for z in range(4,9):
            y=z-1
            m.box((7,2,z),(11,y,z),'mossy_cobblestone')
            for x in range(7,12):m.set(x,y,z,'spruce_stairs[facing=south]')
            for x in (6,12):
                m.box((x,0,z),(x,y,z),'mossy_cobblestone')
                # Adjacent steps overlap at one height, so horizontal fence rails meet.
                for ry in range(y+1,y+2 if z==8 else y+3):rail_nodes.add((x,ry,z))
        m.box((7,7,9),(24,7,12),'spruce_planks')
        for x,z in ((7,9),(7,12),(16,9),(24,9)):
            m.box((x,0,z),(x,6,z),'dark_oak_log[axis=y]')
        m.box((6,0,9),(6,6,9),'dark_oak_log[axis=y]');m.set(6,7,9,'spruce_planks')
        rail_nodes.add((6,8,9))
        rail_nodes.update((x,8,9) for x in range(12,25))
        rail_nodes.update((x,8,12) for x in range(7,21))
        rail_nodes.update((7,8,z) for z in range(9,12))
        rail_nodes.update((24,8,z) for z in range(10,13))
        directions={'north':(0,-1),'south':(0,1),'east':(1,0),'west':(-1,0)}
        for x,y,z in sorted(rail_nodes):
            links=[]
            for side,(dx,dz) in directions.items():
                connected=(x+dx,y,z+dz) in rail_nodes or ((x,y,z)==(24,8,12) and side=='south')
                links.append(f'{side}={str(connected).lower()}')
            m.set(x,y,z,'spruce_fence['+','.join(links)+',waterlogged=false]')
        m.point('upper','circulation',(22,8,11),'高台门廊')
        m.meta.update(roof_min_y=13,floors=[dict(name='林下柱脚与到达',y=2,max_y=6),dict(name='高台住宅',y=7,max_y=12)])
        entry(m,9,2)
    elif v==6:
        path(m,23,2,25,18)
        for z,y in ((19,3),(20,4)):
            m.box((23,2,z),(28,y,z),'mossy_cobblestone')
            for x in range(23,29):m.set(x,y,z,'spruce_stairs[facing=south]')
        # Continuous top landing reaches the high unit's north door.
        path(m,24,21,36,22,4)
        m.point('terrace','circulation',(25,5,22),'坡台换层接驳')
        m.meta['floors']=[dict(name='低台家庭与前院',y=2,max_y=7),dict(name='高台家庭',y=4,max_y=9)]
        m.meta['preview_context'].update(kind='slope',rise=2,run=20,slope_origin_z=19)
        entry(m,24,2)
    else:entry(m,23 if v in (3,4) else 13,2)
    tree(m,*t)
    if forester:
        x0,z0,x1,z1=work
        pergola(m,x0,z0,x1,z1)
        station(m,'repair',x0+2,3,z0+2,'crafting_table','林务工具保养台')
        m.set(x1-2,3,z0+2,'grindstone[face=floor,facing=south]')
        storage(m,'field',x0+2,3,z1-1,max(3,x1-x0-3),'林务绳索、界标与调查物资')
        station(m,'survey',x1-2,3,z0+3,'cartography_table','林区与采伐边界记录',approach=(x1-3,3,z0+3))
        m.room('yard','独立林务工作棚',(x0+1,3,z0+1),(x1-1,7,z1-1),'工具保养、调查记录和物资储藏；与家庭床灶分开')
        if v==5:m.meta['roof_min_y']=8
    else:
        # Domestic outdoor use, different from the staffed forestry workshops.
        x0,z0,x1,z1=work
        path(m,x0,z0,x1,z1)
        bench(m,x0+2,3,z1-1,min(4,x1-x0-3),'north')
        m.set(x0+1,3,z0+1,'water_cauldron[level=3]')
        m.set(x1-1,3,z0+1,'composter')
        station(m,'housework',x0+2,3,z0+2,'loom[facing=south]','户外缝补与编织家务台')
    m.meta['differences']=[note,'林务型额外具备独立工具棚和林区记录作业区。' if forester else '生活型外院用于饮水、家务和休息。']
    m.meta['design_notes']=['床、炉灶、水盆、储物和家用桌椅随每户实际布置；树体与根盘作为完整静态构图交付。']
    return m


BUILDERS={f'{family}-v{i:02d}':partial(home,i,forester) for family,forester in [('FS-01',False),('FS-06',True)] for i in range(1,7)}
