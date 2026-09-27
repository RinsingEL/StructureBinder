"""Reference sheet 4: distinct winter stores and inns, retaining working interiors."""
from functools import partial
from . import northern_crafts as old


def roof(m,x0,z0,x1,z1,eave):
    """Supported gable with timber end trusses; all heights are block coordinates."""
    for x in (x0,x1):
        m.box((x,7,z0),(x,eave,z1),'spruce_planks')
        m.box((x,eave,z0),(x,eave,z1),'dark_oak_log[axis=z]')
    for z in (z0,z1):
        m.box((x0,7,z),(x1,eave,z),'spruce_planks')
        m.box((x0,eave,z),(x1,eave,z),'dark_oak_log[axis=x]')
    m.box((x0,eave,z0),(x1,eave,z1),'spruce_planks')
    mid=(x0+x1)//2
    for i in range((x1-x0+4)//2):
        a,b=x0-1+i,x1+1-i
        if a>b:break
        y=eave+i+1
        for z in range(z0-1,z1+2):
            if a==b:m.set(a,y,z,'dark_oak_log[axis=z]')
            else:
                m.set(a,y,z,'spruce_stairs[facing=east]')
                m.set(b,y,z,'spruce_stairs[facing=west]')
        if i:
            for z in (z0,z1):m.box((a,eave+1,z),(b,y,z),'spruce_planks')
    for z in (z0,z1):
        m.box((mid,eave+1,z),(mid,eave+(x1-x0)//2+1,z),'dark_oak_log[axis=y]')
        for i in range(1,(x1-x0)//2+1):
            for x in (x0+i,x1-i):m.set(x,eave+i,z,'dark_oak_log[axis=x]')
        for x in (mid-2,mid+2):m.set(x,eave+2,z,'light_blue_stained_glass')
    if eave>=14:
        for x in (x0,x1):
            for z in range(z0+3,z1,5):
                m.box((x,10,z),(x,11,z+1),'light_blue_stained_glass')
                m.box((x,9,z),(x,9,z+1),'dark_oak_log[axis=z]')


def shade(m,x0,z0,x1,z1,y=7):
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,3,z),(x,y,z),'dark_oak_log[axis=y]')
    m.box((x0,y,z0),(x1,y,z1),'spruce_slab[type=top]')
    for z in (z0,z1):m.box((x0,y,z),(x1,y,z),'dark_oak_log[axis=x]')


def crane(m,x,z):
    m.box((x,3,z),(x,12,z),'stripped_spruce_log[axis=y]')
    m.box((x-4,12,z),(x,12,z),'dark_oak_log[axis=x]')
    m.box((x-4,8,z),(x-4,11,z),'chain[axis=y]')
    m.set(x-4,7,z,'barrel[facing=up]')


def build(family,v):
    funcs=([old.reserve_small,old.reserve_cells,old.reserve_upper,old.reserve_cross]
           if family=='NS-08' else [old.inn_small,old.inn_cells,old.inn_upper,old.inn_court])
    m=funcs[v-1]()
    names=(['紧凑石基粮仓','四仓配发院','两层窄仓与吊货梁','分屋卸货冬储场'] if family=='NS-08'
           else ['路边前廊客栈','高厅低翼长客栈','两层港街廊栈','分屋避风院客栈'])
    # Preserve the lower envelope, every workstation and the second-storey stairwell.
    cutoff=14 if v==3 else 8
    flues=[(x,z) for (x,y,z),b in m.blocks.items() if y==cutoff and b[0]=='minecraft:cobblestone']
    m.box((1,cutoff,1),(m.size[0]-2,m.size[1]-1,m.size[2]-2),'air')
    if family=='NS-08':
        if v==1:
            roof(m,3,3,21,23,8);shade(m,4,1,20,3)
        elif v==2:
            for x,z in ((3,3),(21,3),(3,16),(21,16)):roof(m,x,z,x+10,z+9,7)
            # Clear central shed, retaining the actual reception counter below.
            m.box((15,6,5),(18,12,21),'air')
            crane(m,19,24)
        elif v==3:
            roof(m,3,3,17,25,14);crane(m,18,8)
            shade(m,5,1,15,3,8)
        else:
            roof(m,3,3,15,26,9);roof(m,23,3,31,15,7)
            shade(m,20,19,31,26);crane(m,19,24)
    else:
        if v==1:
            roof(m,3,3,21,16,8);roof(m,3,17,21,27,10)
            shade(m,4,1,20,3)
        elif v==2:
            # Three actual roof masses: central public hall and low sleeping wings.
            roof(m,3,3,9,29,7);roof(m,21,3,27,29,7)
            roof(m,10,3,20,29,11)
            for x in (10,20):m.box((x,3,3),(x,11,3),'dark_oak_log[axis=y]')
            shade(m,11,1,19,3)
        elif v==3:
            roof(m,3,3,17,25,14)
            # Projecting upper frontage over a deep weather porch.
            shade(m,3,1,17,3,8)
            m.box((3,9,1),(17,9,1),'spruce_fence')
            m.box((3,9,1),(3,9,3),'spruce_fence')
            m.box((17,9,1),(17,9,3),'spruce_fence')
            m.door(10,9,3,facing='north')
        else:
            roof(m,3,3,15,28,10);roof(m,23,3,31,15,8)
            roof(m,19,21,31,28,7);shade(m,18,7,20,17)
    # Restored continuous kitchen flues pass the revised roofs.
    for x,z in flues:
        highest=max(y for (xx,y,zz),b in m.blocks.items() if xx==x and zz==z and b[0]!='minecraft:air')
        m.box((x,cutoff,z),(x,highest+1,z),'cobblestone')
        m.set(x,highest+2,z,'cobblestone_slab[type=bottom]')
    m.meta.update(name=names[v-1],source=f'tools/structure_studio/studio/northern_fill_storage_inns.py:build({family!r},{v})',
        ground_plane=dict(y=3,note='外部稳定街面与模板场坪顶面齐平，站立脚底Y=3；基础方块在其下'),
        function_terms=['仓储','物资配给'] if family=='NS-08' else ['旅宿','餐饮'],
        roof_min_y=cutoff,
        design_notes=[f'参考北欧图4：{names[v-1]}；复用完整可达的分类储物或食宿内饰，重组屋顶和装卸/入口体量。'],
        differences=[names[v-1]])
    m.meta['terrain']['选址']='干燥稳定地基，外部街面接Y=3；仓储需外部货源，客栈需食品及饮水供给；不强制滨海选址。'
    p=next(p for p in m.meta['points'] if p['kind']=='entrance')
    m.meta['connections']=[dict(kind='pedestrian',pos=p['pos'],direction=p['facing'],clearance=[1,2],note='已标入口与外部Y=3街面接驳')]
    return m


BUILDERS={f'{family}-v{v:02d}':partial(build,family,v) for family in ('NS-08','NS-09') for v in range(1,5)}
