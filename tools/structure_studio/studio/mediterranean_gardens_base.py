"""Independent pale masonry, low terracotta roofs and garden work furnishings."""
from .model import Model
from .components import shell, bench, shelf


def base(key,name,w,d,h=22):
    m=Model(key,name,(w,h,d),family=key.split('-v')[0],civilization='地中海',role='fill',terrain={'选址':'稳定排水的聚落地块；种植版本需要日照与灌溉供水，不要求贴临水岸。','高程':'外部铺地顶面Y=3；内部阶台另行标记。','边界':'独立低院墙、红瓦棚和可通行入口，不依赖相邻建筑。'})
    m.box((1,0,1),(w-2,2,d-2),'sandstone')
    m.box((1,3,1),(w-2,h-1,d-2),'air')
    for x in range(1,w-1):
        for z in range(1,d-1):m.set(x,2,z,'smooth_sandstone' if (x+2*z)%5 else 'cut_sandstone')
    for x in range(1,w-1):
        for z in (1,d-2):
            if z==1 and abs(x-w//2)<3:continue
            m.set(x,3,z,'sandstone');m.set(x,4,z,'sandstone_slab[type=bottom]')
    for z in range(2,d-2):
        for x in (1,w-2):m.set(x,3,z,'sandstone');m.set(x,4,z,'sandstone_slab[type=bottom]')
    for x in (w//2-3,w//2+3):
        m.box((x,3,1),(x,4,1),'cut_sandstone');m.set(x,5,1,'lantern')
    m.point('entry','entrance',(w//2,3,2),'前院入口',facing='north')
    m.meta.update(source='tools/structure_studio/studio/mediterranean_gardens.py:BUILDERS',roof_min_y=18,ground_plane={'y':3,'note':'院门外铺地顶面，阶台为内部高差。'},floors=[dict(name='庭院作业层',y=2,max_y=7)],preview_context=dict(kind='flat',land_surface_y=3,padding=4,surface='grass'))
    family=key.split('-v')[0]
    m.meta['function_terms']={'WT-F02':['农田种植'],'WT-F03':['棚架种植'],'WT-F04':['工具存放','货物暂存']}[family]
    return m


def use(m,key,name,x,z,ax=None,az=None,y=3,kind='work'):
    m.point(key,kind,(x,y,z),name,approach=(x if ax is None else ax,y,z+1 if az is None else az),look_at=[x,y+1,z])


def zone(m,key,name,x0,z0,x1,z1,purpose,y=3):m.room(key,name,(x0,y,z0),(x1,y+4,z1),purpose)


def counter(m,key,name,x,z,n=3,y=3,block='crafting_table'):
    m.box((x,y,z),(x+n-1,y,z),'spruce_planks');m.set(x,y,z,block);m.set(x+n-1,y+1,z,'lantern')
    use(m,key,name,x,z,y=y)


def stores(m,key,name,x,z,n=3,y=3):
    shelf(m,x,y,z,n,material='spruce',contents='barrel')
    dz=1 if m.blocks.get((x,y,z+1),('minecraft:air',()))[0]=='minecraft:air' else -1
    use(m,key,name,x,z,x,z+dz,y=y,kind='storage')


def entry(m,key,x,z,y=3):
    m.door(x,y,z,wood='spruce',facing='north');m.set(x,y+2,z,'stripped_spruce_log[axis=x]')
    m.point(key,'entrance',(x,y,z-1),'木门入口',facing='north')


def roof(m,x0,z0,x1,z1,top):
    mid=(x0+x1)//2
    for x in range(x0-1,x1+2):
        edge=min(x-x0+1,x1+1-x);yy=top+1+edge//2
        m.box((x,yy,z0-1),(x,yy,z1+1),'brick_slab[type='+('top' if edge%2 else 'bottom')+']')
        # Solid eave/rafter contact supports the low pitched tiled cap.
        if x0<=x<=x1:
            for z in (z0,z1):
                if yy>top+1:m.box((x,top+1,z),(x,yy-1,z),'smooth_sandstone')
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],top+1)


def house(m,x0,z0,x1,z1,y=2,height=4):
    top=y+height
    shell(m,(x0,y,z0),(x1,top,z1),'smooth_sandstone','spruce_planks',ceiling='spruce_planks')
    for x in (x0,x1):
        for z in range(z0+3,z1-1,5):
            m.set(x,y+2,z,'glass');m.set(x,y+3,z,'glass')
            m.set(x,y+2,z-1,'warped_trapdoor[facing=north,open=true]')
            m.set(x,y+2,z+1,'warped_trapdoor[facing=south,open=true]')
    m.box((x0,top,z0),(x1,top,z0),'stripped_spruce_log[axis=x]')
    roof(m,x0,z0,x1,z1,top)


def lean(m,x0,z0,x1,z1,y=2):
    m.box((x0,y+1,z1),(x1,y+4,z1),'smooth_sandstone')
    for x in sorted(set([*range(x0,x1+1,5),x1])):
        m.box((x,y+1,z0),(x,y+4,z0),'stripped_spruce_log[axis=y]')
        m.set(x,y+1,z0,'cut_sandstone')
        if x<x1:m.set(x+1,y+3,z0,'spruce_stairs[facing=west,half=top]')
    m.box((x0,y+4,z0),(x1,y+4,z0),'stripped_spruce_log[axis=x]')
    for z in range(z0-1,z1+2):
        dy=(z-z0+1)//2;yy=y+5+dy//2
        m.box((x0-1,yy,z),(x1+1,yy,z),'brick_slab[type='+('top' if dy%2 else 'bottom')+']')
    for x in range(x0+2,x1,5):m.set(x,y+3,z1-1,'lantern[hanging=true]')
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],y+5)


def pergola(m,x0,z0,x1,z1,y=2):
    for x in (x0,x1):
        for z in sorted(set([*range(z0,z1+1,5),z1])):
            m.box((x,y+1,z),(x,y+4,z),'oak_fence')
            m.set(x,y+1,z,'cut_sandstone')
            m.set(x,y+5,z,'oak_slab[type=bottom]')
        m.box((x,y+4,z0),(x,y+4,z1),'oak_slab[type=top]')
    for z in range(z0,z1+1,3):
        m.box((x0,y+5,z),(x1,y+5,z),'oak_slab[type=bottom]')
        for x in range(x0,x1+1):
            if (x+z)%3:m.set(x,y+6,z,'oak_leaves[persistent=true,distance=1]')
        for x in (x0+1,x1-1):m.set(x,y+4,z,'amethyst_cluster[facing=down]')
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],y+5)
    m.meta['grape_expression']='木棚叶幕与下垂紫晶簇表达葡萄外观；未接入葡萄作物产量与采收机制。'


def basin(m,x,z,y=2):
    m.box((x-1,y+1,z-1),(x+1,y+1,z+1),'sandstone')
    m.set(x,y+1,z,'water[level=0]')


def finish(m,note):
    m.meta['design_notes']=[note];m.meta['differences']=[note]
    return m
