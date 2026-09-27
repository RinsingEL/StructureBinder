"""Small Gothic households with clock service and cemetery care as real work."""
from .model import Model
from .components import shell,bench,shelf,pendant


def base(key,name,w,d,h=29):
    m=Model(key,name,(w,h,d),family=key.split('-v')[0],civilization='哥特',role='fill',terrain={
        '选址':'稳定、排水良好的居民地块，日照与通路按具体用途保留；哥特是建造风格而非地形限制。',
        '高程':'基础底Y=0、常规地面Y=2、脚底Y=3；抬高或双层版本另记。',
        '边界':'完整独立模板，保留外扶柱、檐口、采光和门外站位，不默认邻居补齐。'})
    m.box((1,0,1),(w-2,2,d-2),'stone_bricks');m.box((1,2,1),(w-2,2,d-2),'gravel')
    m.box((1,3,1),(w-2,h-1,d-2),'air')
    m.meta.update(source='tools/structure_studio/studio/memorial_life.py:BUILDERS',roof_min_y=25,
        floors=[dict(name='生活作业层',y=2,max_y=7)],preview_context=dict(kind='flat',land_surface_y=3,padding=4,surface='grass'))
    return m


def pointed(m,c,z,y=4,axis='x',color='purple'):
    for u in (-1,0,1):
        top=y+3-abs(u)
        for yy in range(y,top+1):
            x,zz=(c+u,z) if axis=='x' else (c,z+u)
            m.set(x,yy,zz,'polished_andesite' if yy==top else f'{color}_stained_glass')


def house(m,x0,z0,x1,z1,y=2,height=5):
    top=y+height
    shell(m,(x0,y,z0),(x1,top,z1),'calcite','spruce_planks',ceiling='spruce_planks')
    for x in (x0,x1):
        for z in range(z0,z1+1,5):
            m.box((x,y+1,z),(x,top,z),'dark_oak_log[axis=y]')
        m.box((x,top,z0),(x,top,z1),'dark_oak_log[axis=z]')
        for z in range(z0+3,z1-1,5):pointed(m,x,z,y+2,axis='z')
    for z in (z0,z1):
        m.box((x0,top,z),(x1,top,z),'dark_oak_log[axis=x]')
        for x in (x0,x1):
            m.box((x,y+1,z-1 if z==z0 else z+1),(x,y+3,z-1 if z==z0 else z+1),'stone_bricks')
    for i in range((x1-x0+4)//2):
        a,b=x0-1+i,x1+1-i
        if a>b:break
        for z in range(z0-1,z1+2):
            m.set(a,top+1+i,z,'deepslate_tile_stairs[facing=east]')
            m.set(b,top+1+i,z,'deepslate_tile_stairs[facing=west]')
        if i:
            for z in (z0,z1):m.box((a,top+1,z),(b,top+i,z),'calcite')
    center=(x0+x1)//2
    for z in (z0,z1):
        m.box((center,top+1,z),(center,top+(x1-x0)//2,z),'dark_oak_log[axis=y]')
        m.set(center,top+(x1-x0)//2+1,z,'stone_brick_wall')
    for x in (x0,x1):m.box((x,top+1,z0),(x,top+1,z1),'dark_oak_log[axis=z]')
    # Light stepped verges, warm upper glazing and a continuous iron ridge.
    ridge=top+1+(x1-x0+2)//2
    for z in (z0-1,z1+1):
        for i in range((x1-x0+4)//2):
            a,b=x0-1+i,x1+1-i
            if a>b:break
            m.set(a,top+2+i,z,'stone_brick_slab[type=bottom]')
            m.set(b,top+2+i,z,'stone_brick_slab[type=bottom]')
    if x1-x0>=8:
        pointed(m,center,z0,top+1,color='yellow')
    for z in range(z0,z1+1):
        m.set(center,ridge+1,z,'iron_bars[north=true,south=true,east=false,west=false]')
    cx=x1-2;cz=z1-2
    for yy in range(top+1,ridge+4):m.set(cx,yy,cz,'stone_bricks')
    m.set(cx,ridge+4,cz,'stone_brick_slab[type=bottom]')
    for z in range(z0+4,z1,7):pendant(m,center,top-1,z,top+1)
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],top+1)


def entry(m,key,x,z,y=3):
    m.door(x,y,z,wood='dark_oak',facing='north')
    for xx in (x-1,x+1):m.set(xx,y+2,z,'polished_andesite')
    m.set(x,y+3,z,'polished_andesite')
    m.point(key,'entrance',(x,y,z-1),'尖楣入户门',facing='north')


def use(m,key,name,x,z,ax=None,az=None,y=3,kind='work'):
    m.point(key,kind,(x,y,z),name,approach=(x if ax is None else ax,y,z+1 if az is None else az),look_at=[x,y+1,z])


def counter(m,key,name,x,z,n=3,y=3,block='crafting_table'):
    m.box((x,y,z),(x+n-1,y,z),'dark_oak_planks');m.set(x,y,z,block);m.set(x+n-1,y+1,z,'lantern')
    use(m,key,name,x,z,y=y)


def stores(m,key,name,x,z,n=3,y=3,books=False):
    shelf(m,x,y,z,n,material='dark_oak',contents='bookshelf' if books else 'barrel')
    dz=1 if m.blocks.get((x,y,z+1),('minecraft:air',()))[0]=='minecraft:air' else -1
    use(m,key,name,x,z,x,z+dz,y,kind='storage')


def sleep(m,key,x,z,n=2,y=3):
    for i in range(n):
        xx=x+3*i;m.bed(xx,y,z,'blue');m.set(xx,y,z+2,'barrel[facing=up]')
        use(m,key+str(i),'床位与个人衣物',xx,z,xx+1,z,y,kind='bed')


def cook(m,key,x,z,y=3):
    counter(m,key,'备餐、热食与洗涤',x,z,4,y,'smoker[facing=south,lit=false]');m.set(x+2,y,z,'water_cauldron[level=3]')
    roof=max(yy for (xx,yy,zz),b in m.blocks.items() if xx==x and zz==z and b[0]!='minecraft:air')
    m.box((x,y+1,z),(x,roof+2,z),'stone_bricks')


def dining(m,x,z,n=4,y=3):
    m.box((x,y,z),(x+n-1,y,z),'dark_oak_planks');m.set(x+n-1,y+1,z,'flower_pot')
    bench(m,x,y,z+2,n,wood='spruce')


def partition(m,x0,z,x1,door,y=3):
    m.box((x0,y,z),(x1,y+3,z),'calcite');m.door(door,y,z,wood='dark_oak')


def zone(m,key,name,x0,z0,x1,z1,purpose,y=3):m.room(key,name,(x0,y,z0),(x1,y+4,z1),purpose)


def stair(m,x,z,y=3,rise=5):
    for i in range(rise):
        m.box((x,y,z+i),(x+1,y+i,z+i),'stone_bricks');m.box((x,y+i,z+i),(x+1,y+i,z+i),'stone_brick_stairs[facing=south]')
    for xx in (x-1,x+2):m.box((xx,y+rise,z),(xx,y+rise,z+rise-1),'stone_brick_wall[north=low,south=low,east=none,west=none,up=true]')


def bellwork(m,key,x,z):
    counter(m,key,'钟务校时、零件与交班记录',x,z,4,block='cartography_table')
    m.set(x+2,4,z,'bell[attachment=floor,facing=north,powered=false]')
    stores(m,key+'parts','钟绳、润滑与备用小件',x,z+4,4)


def finish(m,note):
    m.meta['design_notes']=[note];m.meta['differences']=[note]
    for r in m.meta['rooms']:
        names=[p['name'] for p in m.meta['points'] if all(r['min'][i]<=p['pos'][i]<=r['max'][i] for i in range(3))]
        if names:r['purpose']+='；设施：'+'、'.join(names)
    return m
