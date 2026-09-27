"""Independent Mediterranean shop interiors, stairs and shaded building primitives."""

from .model import Model

from .components import shell,bench,shelf

def use(m,key,name,x,z,ax=None,az=None,y=3,kind='work'):
    m.point(key,kind,(x,y,z),name,approach=(x if ax is None else ax,y,z+1 if az is None else az),look_at=[x,y+1,z])

def counter(m,key,name,x,z,n=3,y=3,block='crafting_table'):
    m.box((x,y,z),(x+n-1,y,z),'spruce_planks');m.set(x,y,z,block);m.set(x+n-1,y+1,z,'lantern')
    use(m,key,name,x,z,y=y)

def stores(m,key,name,x,z,n=3,y=3,books=False):
    shelf(m,x,y,z,n,material='spruce',contents='bookshelf' if books else 'barrel')
    dz=1 if m.blocks.get((x,y,z+1),('minecraft:air',()))[0]=='minecraft:air' else -1
    use(m,key,name,x,z,x,z+dz,y,kind='storage')

def sleep(m,key,x,z,n=2,y=3):
    for i in range(n):
        xx=x+3*i;m.bed(xx,y,z,'cyan');m.set(xx,y,z+2,'barrel[facing=up]')
        use(m,key+str(i),'床位与个人衣物',xx,z,xx+1,z,y,kind='bed')

def dining(m,x,z,n=4,y=3):
    m.box((x,y,z),(x+n-1,y,z),'spruce_planks');m.set(x+n-1,y+1,z,'flower_pot')
    bench(m,x,y,z+2,n,wood='spruce')

def partition(m,x0,z,x1,door,y=3):
    m.box((x0,y,z),(x1,y+3,z),'calcite');m.door(door,y,z,wood='spruce')

def zone(m,key,name,x0,z0,x1,z1,purpose,y=3):m.room(key,name,(x0,y,z0),(x1,y+4,z1),purpose)

def stair(m,x,z,y=3,rise=5):
    for i in range(rise):
        m.box((x,y,z+i),(x+1,y+i,z+i),'stone_bricks');m.box((x,y+i,z+i),(x+1,y+i,z+i),'stone_brick_stairs[facing=south]')
    for xx in (x-1,x+2):m.box((xx,y+rise,z),(xx,y+rise,z+rise-1),'stone_brick_wall[north=low,south=low,east=none,west=none,up=true]')

def upper(m,x0,z0,x1,z1,sx,sz):
    m.box((x0,8,z0),(x1,8,z1),'spruce_planks')
    m.box((sx,8,sz),(sx+1,9,sz+5),'air');stair(m,sx,sz,rise=6)
    m.box((sx,9,sz-1),(sx+1,9,sz-1),'stone_brick_wall[east=low,west=low,up=true]')
    for xx in (sx-1,sx+2):
        m.set(xx,9,sz-1,'stone_brick_wall[north=none,south=low,east=low,west=low,up=true]')
    m.meta['floors']=[dict(name='下层经营作业',y=2,max_y=7),dict(name='上层家庭起居',y=8,max_y=13)]



def base(key,name,w,d):
    m=Model(key,name,(w,30,d),family='WT-F01',civilization='地中海',role='fill',terrain={
        '选址':'稳定排水良好的普通街巷地块；商铺不要求临水或码头。',
        '高程':'外部铺地顶面和一层脚底Y=3，上层脚底Y=9。',
        '边界':'建筑、遮阳棚、院墙和经营内饰均独立完整，街面需接入口。'})
    m.box((1,0,1),(w-2,2,d-2),'sandstone');m.box((1,2,1),(w-2,2,d-2),'smooth_sandstone')
    m.box((1,3,1),(w-2,29,d-2),'air')
    m.meta.update(source='tools/structure_studio/studio/mediterranean_shops.py:BUILDERS',ground_plane=dict(y=3,note='外部米石铺地顶面与首层入口脚底Y=3'),roof_min_y=25,
        floors=[dict(name='街铺与庭院',y=2,max_y=7)],preview_context=dict(kind='flat',land_surface_y=3,padding=4,surface='grass'))
    return m


def window(m,x,y,z,axis='x'):
    for u in (-1,0,1):
        xx,zz=(x+u,z) if axis=='x' else (x,z+u)
        for yy in (y,y+1):m.set(xx,yy,zz,'cyan_terracotta' if u else 'glass_pane')
        m.set(xx,y-1,zz,'smooth_sandstone')
        m.set(xx,y+2,zz,'spruce_slab[type=bottom]')


def roof(m,x0,z0,x1,z1,top):
    along_x=x1-x0>z1-z0
    lo,hi=(z0,z1) if along_x else (x0,x1)
    r0,r1=(x0,x1) if along_x else (z0,z1)
    def place(u,y,v,b):m.set(v,y,u,b) if along_x else m.set(u,y,v,b)
    for i in range((hi-lo+4)//2):
        a,b=lo-1+i,hi+1-i
        if a>b:break
        yy=top+1+i//2
        for v in range(r0-1,r1+2):
            mat='brick_slab[type=bottom]' if i%2==0 else 'bricks'
            place(a,yy,v,mat);place(b,yy,v,mat)
        for v in (r0,r1):
            for u in range(a,b+1):
                for y in range(top+1,yy):place(u,y,v,'smooth_sandstone')
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],top+1)


def house(m,x0,z0,x1,z1,height=5,opened=False):
    top=2+height
    shell(m,(x0,2,z0),(x1,top,z1),'smooth_sandstone','birch_planks',ceiling='birch_planks')
    for x in (x0,x1):
        m.box((x,3,z0),(x,top,z0),'cut_sandstone');m.box((x,3,z1),(x,top,z1),'cut_sandstone')
        for z in range(z0+4,z1-1,6):window(m,x,4,z,'z')
        if height>7:
            for z in range(z0+4,z1-1,6):window(m,x,10,z,'z')
    for z in (z0,z1):
        for x in range(x0+4,x1-1,6):window(m,x,4,z)
        if height>7:
            for x in range(x0+4,x1-1,6):window(m,x,10,z)
    if opened:
        m.box((x0+1,3,z0),(x1-1,6,z0),'air')
        m.box((x0,7,z0),(x1,7,z0),'spruce_log[axis=x]')
        for x in range(x0+5,x1-1,6):m.box((x,3,z0),(x,6,z0),'spruce_log[axis=y]')
    roof(m,x0,z0,x1,z1,top)


def entry(m,key,x,z):
    m.door(x,3,z,wood='spruce',facing='north');m.point(key,'entrance',(x,3,z-1),'木门临街入口',facing='north')
    m.set(x-1,5,z-1,'lantern');m.set(x+1,5,z-1,'lantern')


def canopy(m,x0,z0,x1,z1,color='white',tile=False):
    for x in (x0,x1):
        m.box((x,3,z0),(x,6,z0),'spruce_log[axis=y]')
    for z in (z0,z1):m.box((x0,7,z),(x1,7,z),'spruce_log[axis=x]')
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):m.set(x,8,z,'brick_slab[type=bottom]' if tile else ('white_wool' if x%4<2 else color+'_wool'))
    for x in range(x0+2,x1,4):m.set(x,6,z0,'lantern[hanging=true]')
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],8)


def cook(m,key,x,z,y=3):
    counter(m,key,'家庭烹饪洗涤',x,z,4,y,'smoker[facing=south]');m.set(x+2,y,z,'water_cauldron[level=3]')


def home(m,x,z,key='home',y=3):
    cook(m,key+'cook',x,z,y);dining(m,x+1,z+5,4,y);sleep(m,key+'bed',x+1,z+10,2,y)
    stores(m,key+'linen','家庭衣物被服',x+1,z+13,5,y)
    zone(m,key,'独立家庭起居寝室',x,z,x+7,z+14,'完整餐厨、双床、床柜和衣物',y)


def yard(m):
    w,h,d=m.size
    for x in (1,w-2):
        for z in range(2,d-1):m.set(x,3,z,'sandstone_wall')
    for x in range(2,w-2):m.set(x,3,d-2,'sandstone_wall')
    for x,z in ((2,2),(w-3,2),(2,d-3),(w-3,d-3)):
        m.set(x,3,z,'barrel');m.set(x,4,z,'potted_fern')
    m.meta['static_facilities']='烘炉、织造、器物、绳帆与商品为原版方块静态表达。'
