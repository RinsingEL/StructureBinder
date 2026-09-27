"""Independent Gothic craft building primitives and reference-image exterior details."""


from .model import Model


from .components import shell,bench,shelf,pendant


def base(key,name,w,d,h=29):
    m=Model(key,name,(w,h,d),family=key.split('-v')[0],civilization='哥特',role='fill',terrain={
        '选址':'稳定、排水良好的居民地块，日照与通路按具体用途保留；哥特是建造风格而非地形限制。',
        '高程':'基础底Y=0、常规地面Y=2、脚底Y=3；抬高或双层版本另记。',
        '边界':'完整独立模板，保留外扶柱、檐口、采光和门外站位，不默认邻居补齐。'})
    m.box((1,0,1),(w-2,2,d-2),'stone_bricks');m.box((1,2,1),(w-2,2,d-2),'stone_bricks')
    m.box((1,3,1),(w-2,h-1,d-2),'air')
    m.meta['ground_plane']=dict(y=3,note='外部石铺院落顶面与入口脚底Y=3')
    m.meta.update(source='tools/structure_studio/studio/memorial_life.py:BUILDERS',roof_min_y=25,
        floors=[dict(name='生活作业层',y=2,max_y=7)],preview_context=dict(kind='flat',land_surface_y=3,padding=4,surface='grass'))
    return m


def pointed(m,c,z,y=4,axis='x',color='purple'):
    for u in (-1,0,1):
        top=y+3-abs(u)
        for yy in range(y,top+1):
            x,zz=(c+u,z) if axis=='x' else (c,z+u)
            m.set(x,yy,zz,'stone_bricks' if yy==top else f'{color}_stained_glass')


def house(m,x0,z0,x1,z1,y=2,height=5):
    top=y+height
    shell(m,(x0,y,z0),(x1,top,z1),'calcite','spruce_planks',ceiling='spruce_planks')
    m.meta.setdefault('craft_volumes',[]).append([x0,z0,x1,z1,y,height])
    for x in (x0,x1):
        for z in range(z0,z1+1,5):m.box((x,y+1,z),(x,top,z),'dark_oak_log[axis=y]')
        m.box((x,top,z0),(x,top,z1),'dark_oak_log[axis=z]')
        for z in range(z0+3,z1-1,5):pointed(m,x,z,y+2,axis='z',color='orange')
        if height>7:
            m.box((x,y+6,z0),(x,y+6,z1),'dark_oak_log[axis=z]')
            for z in range(z0+3,z1-1,5):pointed(m,x,z,y+8,axis='z',color='purple')
    for z in (z0,z1):
        m.box((x0,top,z),(x1,top,z),'dark_oak_log[axis=x]')
        for x in (x0,x1):m.box((x,y+1,z),(x,y+3,z),'stone_bricks')
    # Long workshops have a transverse ridge; narrow houses keep their street gable.
    along_x=(x1-x0)>(z1-z0)
    lo,hi=(z0,z1) if along_x else (x0,x1)
    run0,run1=(x0,x1) if along_x else (z0,z1)
    def place(u,yy,v,block):
        m.set(v,yy,u,block) if along_x else m.set(u,yy,v,block)
    for i in range((hi-lo+4)//2):
        a,b=lo-1+i,hi+1-i
        if a>b:break
        yy=top+1+i
        for v in range(run0-1,run1+2):
            edge=v in (run0-1,run1+1)
            mat='stone_brick' if edge else 'deepslate_tile'
            place(a,yy,v,mat+'_stairs[facing='+('south' if along_x else 'east')+']')
            place(b,yy,v,mat+'_stairs[facing='+('north' if along_x else 'west')+']')
        if i:
            for v in (run0,run1):
                for u in range(a,b+1):
                    for gy in range(top+1,yy+1):place(u,gy,v,'calcite')
    ridge=top+1+(hi-lo+2)//2
    center=(lo+hi)//2
    for v in range(run0-1,run1+2):
        place(center,ridge,v,'deepslate_tiles')
        place(center,ridge+1,v,'iron_bars')
    for v in (run0-1,run1+1):place(center,ridge+2,v,'stone_brick_wall')
    for v in (run0,run1):
        for yy in range(top+1,ridge):place(center,yy,v,'dark_oak_log[axis=y]')
        if ridge-top>=5:
            for yy in range(top+2,top+5):place(center,yy,v,'orange_stained_glass')
            place(center,top+5,v,'stone_bricks')
    # Warm side dormers break up broad roof planes without obstructing inhabited rooms.
    if run1-run0>=15:
        v=(run0+run1)//2
        u=lo+2;dy=top+4
        for du in (-1,0,1):
            for yy in range(dy,dy+3):place(u,yy,v+du,'dark_oak_planks' if du else 'orange_stained_glass')
            place(u,dy+3+int(du==0),v+du,'deepslate_tiles')
    for z in range(z0+4,z1,7):pendant(m,(x0+x1)//2,top-1,z,top+1)
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],top+1)


def entry(m,key,x,z,y=3):
    m.door(x,y,z,wood='dark_oak',facing='north')
    for xx in (x-1,x+1):m.set(xx,y+2,z,'polished_andesite')
    m.set(x,y+3,z,'stone_bricks')
    for xx in (x-2,x+2):
        m.set(xx,y+1,z-1,'lantern')
        m.set(xx,y,z-1,'stone_brick_wall')
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


def finish(m,note):
    exterior(m)
    # Inner courtyard doors remain walking targets, not independent street connections.
    internal = {'ML-04-v02': ['workentry'], 'ML-04-v04': ['homeentry'], 'ML-06-v01': ['homeentry'], 'ML-06-v02': ['workentry'], 'ML-06-v04': ['homeentry'], 'ML-09-v01': ['homeentry'], 'ML-09-v03': ['homeentry'], 'ML-F01-v04': ['homeentry'], 'ML-F01-v05': ['workentry'], 'ML-F01-v06': ['homeentry'], 'ML-F01-v08': ['workentry'], 'ML-08-v02': ['guestaentry', 'guestbentry'], 'ML-08-v04': ['staffentry']}
    for point in m.meta['points']:
        if point['id'] in internal.get(m.meta['id'], ()):
            point['kind']='circulation'
    m.meta['design_notes']=[note+' 按2026-09-27参考图重塑浅石木骨、深瓦浅边山墙、暖窗和院落。'];m.meta['differences']=[note]
    for r in m.meta['rooms']:
        names=[p['name'] for p in m.meta['points'] if all(r['min'][i]<=p['pos'][i]<=r['max'][i] for i in range(3))]
        if names:r['purpose']+='；设施：'+'、'.join(names)
    return m


def upper(m,x0,z0,x1,z1,sx,sz):
    m.box((x0,8,z0),(x1,8,z1),'spruce_planks')
    m.box((sx,8,sz),(sx+1,9,sz+5),'air');stair(m,sx,sz,rise=6)
    m.box((sx,9,sz-1),(sx+1,9,sz-1),'stone_brick_wall[east=low,west=low,up=true]')
    for xx in (sx-1,sx+2):
        m.set(xx,9,sz-1,'stone_brick_wall[north=none,south=low,east=low,west=low,up=true]')
    m.meta['floors']=[dict(name='下层作业起居',y=2,max_y=7),dict(name='上层家居钟务',y=8,max_y=13)]
    for xx in (x0-1,x1+1):
        for zz in range(z0+3,z1-1,6):pointed(m,xx,zz,10,axis='z')


def exterior(m):
    w,h,d=m.size
    # Continuous low stone and iron boundary; a generous street opening keeps every door reachable.
    entrances=[p['pos'][0] for p in m.meta['points'] if p['kind']=='entrance']
    for x in range(2,w-2):
        if any(abs(x-e)<=2 for e in entrances):continue
        m.set(x,3,1,'stone_brick_wall')
        m.set(x,4,1,'iron_bars')
    for x in (1,w-2):
        for z in range(2,d-1):
            m.set(x,3,z,'stone_brick_wall');m.set(x,4,z,'iron_bars')
        for z in range(2,d-1,7):
            m.box((x,3,z),(x,5,z),'stone_bricks');m.set(x,6,z,'lantern')
    for x in range(2,w-2):
        m.set(x,3,d-2,'stone_brick_wall');m.set(x,4,d-2,'iron_bars')
    for x,z in ((2,2),(w-3,2),(2,d-3),(w-3,d-3)):
        m.set(x,3,z,'barrel');m.set(x,4,z,'potted_allium')
    # Cap every masonry flue with a projecting pale stone crown.
    columns={}
    for (x,y,z),(name,_) in list(m.blocks.items()):
        if name in ('minecraft:stone_bricks','minecraft:bricks') and y>9:
            columns[(x,z)]=max(columns.get((x,z),0),y)
    for (x,z),y in columns.items():
        if m.blocks.get((x,y-3,z),('air',()))[0] in ('minecraft:stone_bricks','minecraft:bricks') and y+1<h:
            m.set(x,y+1,z,'stone_brick_slab[type=bottom]')
