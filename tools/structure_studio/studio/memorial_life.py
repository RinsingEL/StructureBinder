"""Small Gothic households with clock service and cemetery care as real work."""
from .model import Model
from .components import shell,bench,shelf,pendant


def base(key,name,w,d,h=29):
    m=Model(key,name,(w,h,d),family=key.split('-v')[0],civilization='哥特',role='fill',terrain={
        '选址':'稳定、排水良好的居民地块，日照与通路按具体用途保留；哥特是建造风格而非地形限制。',
        '高程':'基础底Y=0、常规地面Y=2、脚底Y=3；抬高或双层版本另记。',
        '边界':'完整独立模板，保留外扶柱、檐口、采光和门外站位，不默认邻居补齐。'})
    m.box((1,0,1),(w-2,2,d-2),'stone_bricks');m.box((1,2,1),(w-2,2,d-2),'stone_bricks')
    m.box((1,3,1),(w-2,h-1,d-2),'air')
    m.meta.update(source='tools/structure_studio/studio/memorial_life.py:BUILDERS',roof_min_y=25,
        ground_plane=dict(y=3,note='外部场坪顶面与脚底Y=3，铺地方块Y=2；内部上层及高台不改变接地基准。'),
        floors=[dict(name='生活作业层',y=2,max_y=7)],preview_context=dict(kind='flat',land_surface_y=3,padding=4,surface='grass'))
    return m


def pointed(m,c,z,y=4,axis='x',color='purple'):
    for u in (-1,0,1):
        top=y+3-abs(u)
        for yy in range(y,top+1):
            x,zz=(c+u,z) if axis=='x' else (c,z+u)
            block='stone_bricks' if yy==top else ('yellow_stained_glass' if u==0 else f'{color}_stained_glass')
            m.set(x,yy,zz,block)


def house(m,x0,z0,x1,z1,y=2,height=5):
    top=y+height
    shell(m,(x0,y,z0),(x1,top,z1),'calcite','spruce_planks',ceiling='spruce_planks')
    for x in (x0,x1):
        for z in range(z0,z1+1,5):m.box((x,y+1,z),(x,top,z),'dark_oak_log[axis=y]')
        m.box((x,top,z0),(x,top,z1),'dark_oak_log[axis=z]')
        for z in range(z0+3,z1-1,5):pointed(m,x,z,y+2,axis='z')
    for z in (z0,z1):
        m.box((x0,top,z),(x1,top,z),'dark_oak_log[axis=x]')
        for x in (x0,x1):
            m.box((x,y+1,z),(x,top,z),'dark_oak_log[axis=y]')
            m.box((x,y+1,z-1 if z==z0 else z+1),(x,y+3,z-1 if z==z0 else z+1),'stone_bricks')
        for x in range(x0+3,x1-1,6):pointed(m,x,z,y+2)
    if height>=10:
        for z in (z0,z1):
            m.box((x0,y+6,z),(x1,y+6,z),'dark_oak_log[axis=x]')
            pointed(m,(x0+x1)//2,z,y+8)
            for xx in range((x0+x1)//2-1,(x0+x1)//2+2):
                zz=z-1 if z==z0 else z+1
                m.set(xx,y+7,zz,'dark_oak_slab')
        for x in (x0,x1):m.box((x,y+6,z0),(x,y+6,z1),'dark_oak_log[axis=z]')
    # Use the shorter span for a human-scale roof, so transverse wings have
    # a transverse ridge rather than one enormous pyramid-like gable.
    sideways=(x1-x0)>(z1-z0)
    lo,hi=(z0,z1) if sideways else (x0,x1)
    start,end=(x0,x1) if sideways else (z0,z1)
    def put(u,yy,v,block):
        m.set(v,yy,u,block) if sideways else m.set(u,yy,v,block)
    def fill(u0,yy0,v,u1,yy1,block):
        for u in range(u0,u1+1):
            for yy in range(yy0,yy1+1):put(u,yy,v,block)
    ridge=top+1
    for i in range((hi-lo+4)//2):
        a,b=lo-1+i,hi+1-i
        if a>b:break
        ridge=top+1+i
        for v in range(start-1,end+2):
            put(a,ridge,v,'deepslate_tile_stairs[facing='+('south' if sideways else 'east')+']')
            put(b,ridge,v,'deepslate_tile_stairs[facing='+('north' if sideways else 'west')+']')
        if i:
            for v in (start,end):fill(a,top+1,v,b,ridge-1,'calcite')
        for v in (start-1,end+1):
            put(a,ridge,v,'stone_brick_stairs[facing='+('south' if sideways else 'east')+']')
            put(b,ridge,v,'stone_brick_stairs[facing='+('north' if sideways else 'west')+']')
    center=(lo+hi)//2
    for v in range(start,end+1):
        put(center,ridge+1,v,'deepslate_tiles')
        put(center,ridge+2,v,'iron_bars['+('east=true,west=true' if sideways else 'north=true,south=true')+']')
    for v in (start-1,end+1):
        put(center,ridge+1,v,'stone_brick_wall')
        put(center,ridge+2,v,'stone_brick_wall')
    for v in (start,end):
        if ridge-top>=4:
            for yy in range(top+1,ridge):put(center,yy,v,'dark_oak_log[axis=y]')
            put(center,top+2,v,'yellow_stained_glass')
            put(center,top+3,v,'purple_stained_glass')
    # Small paired dormers break up long roof slopes without blocking rooms.
    if end-start>=10 and hi-lo>=8:
        for v in range(start+4,end-2,8):
            u=lo+2
            for vv in range(v-1,v+2):
                for yy in range(top+3,top+6):put(u,yy,vv,'dark_oak_planks')
                put(u,top+6-abs(vv-v),vv,'deepslate_tiles')
            put(u,top+4,v,'yellow_stained_glass')
            put(u,top+5,v,'stone_brick_slab')
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],top+1)
    m.meta.setdefault('reference_roofs',[]).append([x0,z0,x1,z1,top,ridge])
    for z in range(z0+4,z1,7):pendant(m,(x0+x1)//2,top-1,z,top+1)


def entry(m,key,x,z,y=3):
    m.door(x,y,z,wood='dark_oak',facing='north')
    for xx in (x-1,x+1):m.set(xx,y+2,z,'polished_andesite')
    m.set(x,y+3,z,'stone_bricks')
    for xx in (x-2,x+2):
        m.set(xx,y+2,z-1,'lantern[hanging=true]')
        m.set(xx,y+3,z-1,'dark_oak_fence')
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
    roof=min(m.size[1]-3,max(yy for (xx,yy,zz),b in m.blocks.items() if b[0]!='minecraft:air'))
    m.box((x,y+1,z),(x,roof+1,z),'stone_bricks')
    m.set(x,roof+2,z,'stone_brick_wall')


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
    m.meta['design_notes']=[note,'按2026-09-27效果图重绘：浅石深木、陡瓦白边、铁脊老虎窗、紫金彩窗与灯柱围院。'];m.meta['differences']=[note]
    w,h,d=m.size
    gates={int(p['pos'][0]) for p in m.meta['points'] if p['kind']=='entrance'}
    for x in range(2,w-2):
        for z in (2,d-3):
            if z==2 and (x<=6 or x>=w-7 or any(abs(x-g)<=2 for g in gates)):continue
            if m.blocks.get((x,3,z),('minecraft:air',()))[0]!='minecraft:air':continue
            m.set(x,3,z,'stone_bricks');m.set(x,4,z,'iron_bars[east=true,west=true]')
            if x%5==2:
                m.box((x,3,z),(x,5,z),'stone_bricks');m.set(x,6,z,'lantern')
    for z in range(3,d-3):
        for x in (2,w-3):
            if m.blocks.get((x,3,z),('minecraft:air',()))[0]!='minecraft:air':continue
            m.set(x,3,z,'stone_bricks');m.set(x,4,z,'iron_bars[north=true,south=true]')
            if z%6==3:
                m.box((x,3,z),(x,5,z),'stone_bricks');m.set(x,6,z,'stone_brick_slab')
    # Front gable planters remain beside entrances, never on their approach.
    for x0,z0,x1,z1,top,ridge in m.meta.pop('reference_roofs',[]):
        for x in (x0+1,x1-1):
            z=z0-1
            if any(abs(x-p['pos'][0])<=1 and abs(z-p['pos'][2])<=1 for p in m.meta['points']):continue
            if m.blocks.get((x,3,z),('minecraft:air',()))[0]=='minecraft:air':
                m.set(x,3,z,'barrel');m.set(x,4,z,'flower_pot')

    # Rear/service room doors are reached through the courtyard, not straight
    # through the front buildings; keep them as internal circulation markers.
    courtyard_doors={'ML-03-v05':{'home'},'ML-03-v06':{'clockentry'},
                     'ML-07-v01':{'toolentry'},'ML-07-v02':{'tools'}}
    for point in m.meta['points']:
        if point['id'] in courtyard_doors.get(m.meta['id'],set()):
            point['kind']='circulation';point['name']='院内房门：'+point['name']
    for r in m.meta['rooms']:
        names=[p['name'] for p in m.meta['points'] if all(r['min'][i]<=p['pos'][i]<=r['max'][i] for i in range(3))]
        if names:r['purpose']+='；设施：'+'、'.join(names)
    return m


def clock_short():
    m=base('ML-03-v01','短街守钟人家庭宅',33,29,35)
    house(m,4,4,16,24,height=11);entry(m,'entry',10,4);upper(m,5,5,15,23,12,10)
    house(m,20,13,28,24);entry(m,'clockentry',24,13)
    cook(m,'cook',5,6);dining(m,6,11,4);stores(m,'pantry','家庭餐具食粮',5,20,4)
    bellwork(m,'clock',21,16)
    sleep(m,'family',6,19,2,y=9);stores(m,'linen','被服与家庭粮食',6,22,5,y=9)
    counter(m,'reading','上层家庭读写',6,6,4,y=9,block='lectern[facing=south]')
    zone(m,'living','下层炊食起居',5,5,15,23,'餐厨、楼梯与家庭储存')
    zone(m,'family','上层家庭寝居',5,5,15,23,'双床、衣物与读写',y=9)
    zone(m,'work','低檐钟具工作附屋',21,14,27,23,'与家庭高主楼隔院相望的钟务台和零件库')
    return finish(m,'依参考图：窄高双层尖顶家宅与右侧低钟坊，高低山墙面向前院；家庭楼梯和楼上双床实际可达。')


def caretaker_court():
    m=base('ML-07-v01','看护家庭与独立工具院',31,29)
    house(m,4,4,16,25,height=11);upper(m,5,5,15,24,12,10);house(m,22,13,27,25);entry(m,'entry',10,4);entry(m,'toolentry',24,13)
    cook(m,'cook',5,6);dining(m,6,11,4);sleep(m,'family',6,19,2,y=9)
    stores(m,'linen','家用被服与衣物',6,23,4,y=9);counter(m,'records','家庭值守与墓区记录',11,16,4,block='lectern[facing=south,has_book=false]')
    stores(m,'tools','清扫、除草与维修工具',23,16,3);counter(m,'repair','工具维护与换柄',23,21,3)
    m.box((21,2,5),(27,2,9),'moss_block');m.set(22,3,6,'flowering_azalea');bench(m,21,3,10,5,wood='spruce')
    m.point('court','circulation',(19,3,10),'居住与工具屋间院路',look_at=[24,4,13])
    zone(m,'living','家庭起居餐厨',5,5,15,13,'正常三人家庭炊食')
    zone(m,'sleep','上层家庭双床寝区',5,15,15,24,'个人床柜、被服',y=9)
    zone(m,'tools','独立干燥工具屋',23,14,26,24,'工具归还、维护与存放，不占家庭餐厨')
    return finish(m,'依参考图：双层高主屋与低工具屋隔院分置，上层双床家居有实梯；下层完整餐厨与值守，前院留维护与休憩空间。')


BUILDERS={'ML-03-v01':clock_short,'ML-07-v01':caretaker_court}

def upper(m,x0,z0,x1,z1,sx,sz):
    m.box((x0,8,z0),(x1,8,z1),'spruce_planks')
    m.box((sx,8,sz),(sx+1,9,sz+5),'air');stair(m,sx,sz,rise=6)
    m.box((sx,9,sz-1),(sx+1,9,sz-1),'stone_brick_wall[east=low,west=low,up=true]')
    for xx in (sx-1,sx+2):
        m.set(xx,9,sz-1,'stone_brick_wall[north=none,south=low,east=low,west=low,up=true]')
    m.meta['floors']=[dict(name='下层作业起居',y=2,max_y=7),dict(name='上层家居钟务',y=8,max_y=13)]
    for xx in (x0-1,x1+1):
        for zz in range(z0+3,z1-1,6):pointed(m,xx,zz,10,axis='z')


def clock_wing():
    m=base('ML-03-v02','横厅与侧钟务翼宅',33,28)
    house(m,4,4,27,14);house(m,4,14,16,24)
    m.box((8,3,14),(12,6,14),'air');entry(m,'entry',23,4)
    cook(m,'cook',18,6);dining(m,19,10,5)
    m.box((16,3,5),(16,6,13),'calcite');m.door(16,3,10,wood='dark_oak',facing='east')
    bellwork(m,'clock',5,6);stores(m,'ledger','值勤图谱与维修日志',10,11,4,books=True)
    sleep(m,'beds',6,20,3);stores(m,'linen','家用衣物被服',7,23,6)
    zone(m,'clock','横厅西侧钟务室',5,5,15,13,'修理、图谱与零件成组靠近侧翼')
    zone(m,'living','朝街炊食厅',17,5,26,13,'入口及家庭共食，侧门入钟务室')
    zone(m,'beds','后伸安静寝翼',5,15,15,23,'三床及共用被服，跨宽门联系横厅')
    return finish(m,'横向餐厨与钟务并列，寝翼向后伸成L形；生活、钟务与夜间睡眠具有三个真正分隔的空间。')


def clock_tower():
    m=base('ML-03-v03','钟务附楼双层家宅',35,30,35)
    house(m,4,4,16,25,height=11);entry(m,'entry',10,4);upper(m,5,5,15,24,12,11)
    house(m,20,12,30,25);entry(m,'clockentry',25,12)
    cook(m,'cook',5,6);dining(m,6,11,4);stores(m,'pantry','家庭备粮',5,21,4)
    bellwork(m,'clock',21,15);counter(m,'records','钟务交班记录',21,22,4,block='lectern[facing=south]')
    sleep(m,'beds',6,20,2,y=9);stores(m,'linen','上层家庭被服',6,23,5,y=9)
    counter(m,'reading','上层读写',6,7,4,y=9,block='lectern[facing=south]')
    zone(m,'ground','高宅家庭餐厨',5,5,15,24,'主楼起居餐厨与侧楼梯')
    zone(m,'upper','上层双床家庭',5,5,15,24,'双床、衣物与读写',y=9)
    zone(m,'clock','低檐独立钟务附楼',21,13,29,24,'前校时后记录，宽工作门向庭院')
    return finish(m,'依参考图：高瘦双层主宅与宽低钟务翼并置，屋脊高差和开敞院路形成主次体量。')


def clock_pair():
    m=base('ML-03-v04','双户夹钟务院',37,30)
    for side,x in enumerate((4,23)):
        house(m,x,4,x+9,26);entry(m,'entry'+str(side),x+4,4)
        partition(m,x+1,15,x+8,x+4);cook(m,'cook'+str(side),x+1,6);dining(m,x+2,11,4)
        sleep(m,'beds'+str(side),x+2,20,2);stores(m,'linen'+str(side),'本户床品与衣物',x+2,24,5)
        zone(m,'home'+str(side),'独立双床家庭'+str(side+1),x+1,5,x+8,25,'独立餐厨、双床及衣物，不借邻户生活设施')
    house(m,16,16,20,26);entry(m,'clockentry',18,16)
    counter(m,'clock','共享校时工作桌',17,19,2,block='cartography_table');m.set(18,4,19,'bell[attachment=floor]')
    stores(m,'parts','共有钟件与值班记录',17,23,2)
    zone(m,'shared','院后窄钟务房',17,17,19,25,'双户轮值共享工作桌与备件')
    m.point('court','circulation',(18,3,10),'双户与钟务房共同前院')
    return finish(m,'两条独立双床长宅夹出共用院路，院后小钟务房单独进入；每户均有完整餐厨和床柜，公共值班不穿私室。')


def clock_terrace():
    m=base('ML-03-v05','前低钟坊后高家宅',25,37)
    house(m,4,4,20,16);entry(m,'entry',12,4)
    m.box((4,0,21),(20,4,32),'stone_bricks');house(m,4,21,20,32,y=4);entry(m,'home',12,21,y=5)
    for y,z in ((3,18),(4,19)):
        m.box((10,y,z),(14,y,z),'stone_brick_stairs[facing=south]')
    m.box((10,0,20),(14,4,20),'stone_bricks')
    bellwork(m,'clock',5,6);counter(m,'records','交班及钟楼记录',14,7,5,block='lectern[facing=south]');stores(m,'rope','干燥钟绳备件',14,12,5)
    cook(m,'cook',5,23,y=5);dining(m,6,27,4,y=5);sleep(m,'beds',14,28,2,y=5)
    stores(m,'linen','家用被服',15,31,4,y=5)
    zone(m,'work','前低钟务屋',5,5,19,15,'校时、修理、干件及交班')
    zone(m,'home','后高日常宅',5,22,19,31,'高台干燥家居，厨房餐桌与双床分置两侧',y=5)
    m.meta['floors']=[dict(name='前低作业层',y=2,max_y=7),dict(name='后高家居层',y=4,max_y=9)]
    return finish(m,'前低工作屋与后高家庭屋间有两级实阶和平台；后屋整体抬高两格，适应小台地而非以同一水平盒替代坡地。')


def clock_three():
    m=base('ML-03-v06','钟务三屋围庭宅',35,35)
    house(m,4,4,16,15);house(m,22,4,30,26);house(m,4,22,16,30)
    entry(m,'livingentry',10,4);entry(m,'sleepentry',26,4);entry(m,'clockentry',10,22)
    cook(m,'cook',5,6);dining(m,6,11,5);stores(m,'pantry','全家食粮餐具',12,12,3)
    sleep(m,'beds',23,10,2);sleep(m,'bedsrear',23,20,2);partition(m,23,15,29,26);stores(m,'linen','共用被服',23,24,5)
    counter(m,'clock','独立安静钟务桌',5,24,5,block='cartography_table');m.set(8,4,24,'bell[attachment=floor]');stores(m,'parts','钟务零件库',5,28,5)
    bench(m,9,3,18,5,wood='spruce');m.box((17,2,17),(20,2,22),'moss_block');m.set(18,3,20,'flowering_azalea')
    zone(m,'eat','独立餐厨屋',5,5,15,14,'完整家庭炊食与备粮')
    zone(m,'sleep','双寝房纵翼',23,5,29,25,'两个两床寝间与中央过道')
    zone(m,'clock','后院钟务屋',5,23,15,29,'安静工作、备件与院前交班')
    return finish(m,'餐厨、两间寝室与钟务分成三屋围庭，四床家庭经开敞庭院往来，工作噪声与日常炊食物理分离。')


def caretaker_duplex():
    m=base('ML-07-v02','横向双户共用值守宅',37,29)
    house(m,4,4,17,18);house(m,19,4,32,18);house(m,13,21,23,25)
    for i,x in enumerate((5,20)):
        entry(m,'entry'+str(i),x+5,4);cook(m,'cook'+str(i),x,6);dining(m,x+5,7,4)
        sleep(m,'beds'+str(i),x+1,14,2);stores(m,'linen'+str(i),'本户衣物被服',x+7,15,4)
        zone(m,'home'+str(i),'独立双床横向家庭'+str(i+1),x,5,x+11,17,'前部炊食和后部双床、侧衣物柜')
    m.box((18,3,5),(18,6,17),'calcite');entry(m,'tools',18,21)
    counter(m,'records','轮值与墓园记录',14,23,3,block='lectern[facing=south]');stores(m,'toolstore','共用清扫养护工具',20,23,3)
    zone(m,'tools','共用后院值守间',14,22,22,24,'两户独立生活，共享后院值班与工具')
    return finish(m,'宽横屋用实体中墙分成两户，各有双床餐厨；共用值守间独立设在后院，入口与住宅生活动线分开。')


def caretaker_elbow():
    m=base('ML-07-v03','折角宅与雨天清洗廊',31,32)
    house(m,4,4,25,14);house(m,4,14,14,27);entry(m,'entry',20,4)
    m.box((7,3,14),(11,6,14),'air');m.box((15,3,15),(16,5,17),'air');cook(m,'cook',16,6);dining(m,17,10,5)
    counter(m,'records','值守书桌与地图',5,6,5,block='cartography_table');stores(m,'gear','室内干燥工具柜',5,11,5)
    sleep(m,'beds',6,21,2);stores(m,'linen','家庭被服',6,25,6)
    for x in (18,26):m.box((x,3,19),(x,6,19),'dark_oak_log[axis=y]')
    m.box((17,7,16),(27,7,22),'deepslate_tile_slab');m.box((17,2,16),(27,2,22),'stone_bricks')
    m.set(20,3,18,'water_cauldron[level=3]');counter(m,'repair','湿工具清洗换柄',23,18,3);use(m,'wash','泥污工具清洗盆',20,18)
    zone(m,'main','横向起居与值守厅',5,5,24,13,'东侧餐厨、西侧记录及干柜')
    zone(m,'bed','折后双床寝翼',5,15,13,26,'双床及被服远离院中湿作业')
    zone(m,'wash','开敞遮雨清洗廊',18,17,26,21,'先清洗维护工具，再入干柜')
    return finish(m,'L形寝居围出工作小院，雨棚下单独组织沾泥工具清洗与换柄，干燥记录和家庭双床留在实体屋内。')


def caretaker_upper():
    m=base('ML-07-v04','下层值守上层家居宅',25,30,35)
    house(m,4,4,20,26,height=11);entry(m,'entry',12,4);upper(m,5,5,19,25,16,13)
    counter(m,'record','墓区登记与来访问询',5,6,6,block='lectern[facing=south]');bench(m,6,3,10,5,wood='spruce')
    partition(m,5,15,15,11);stores(m,'tools','清扫与修整分柜',5,18,5);counter(m,'repair','后部工具维护',5,23,5)
    cook(m,'cook',5,6,y=9);dining(m,6,11,5,y=9);partition(m,5,16,15,11,y=9)
    sleep(m,'beds',6,21,3,y=9);stores(m,'linen','上层被服',6,24,5,y=9)
    zone(m,'duty','登记接待及后工具间',5,5,15,25,'前部来访停留，后部工具储存维修')
    zone(m,'family','上层完整三床家居',5,5,19,25,'独立炊食、隔屏三床与被服，不把家庭当作值守宿舍',y=9)
    return finish(m,'紧凑两层值守宅，下层来访登记与后部工具间，上层三人家庭餐厨寝居完整；侧实梯与护栏形成唯一清晰上下通路。')


def caretaker_raised():
    m=base('ML-07-v05','台地家庭宅与前工具院',29,35)
    m.box((4,0,15),(24,4,30),'stone_bricks');house(m,4,15,24,30,y=4);entry(m,'home',14,15,y=5)
    for yy,zz in ((3,12),(4,13)):m.box((12,yy,zz),(16,yy,zz),'stone_brick_stairs[facing=south]')
    m.box((12,0,14),(16,4,14),'stone_bricks')
    house(m,4,4,11,10);entry(m,'toolentry',8,4);stores(m,'tools','前院工作工具',5,7,5)
    counter(m,'washwork','露天清洗与修整',19,7,5);m.set(19,3,6,'water_cauldron[level=3]')
    cook(m,'cook',5,17,y=5);dining(m,7,22,5,y=5)
    m.box((15,5,16),(15,8,29),'calcite');m.door(15,5,22,wood='dark_oak',facing='east')
    sleep(m,'beds',17,21,2,y=5);stores(m,'linen','寝室被服',17,26,5,y=5);counter(m,'record','干燥记录桌',6,28,5,y=5,block='lectern[facing=south]')
    zone(m,'tools','低院清洗与工具屋',5,5,23,10,'泥污作业留在台阶下，工具屋与清洗台分开')
    zone(m,'living','高台家庭起居与记录',5,16,14,29,'餐厨、记录和家庭生活整体抬高两格',y=5)
    zone(m,'beds','高台独立双床寝间',16,16,23,29,'侧门入寝间并有长被服柜',y=5)
    m.meta['floors']=[dict(name='低院作业面',y=2,max_y=7),dict(name='高台家庭层',y=4,max_y=9)]
    return finish(m,'前低工具院与后高干燥家庭形成真实两格台差；高台用侧隔墙区分起居记录与双床寝室，泥污工具不经过家庭门。')


def caretaker_corridor():
    m=base('ML-07-v06','通廊三间值守家庭宅',23,38)
    house(m,4,4,18,13);house(m,4,14,18,23,height=6);house(m,4,24,18,33,height=7);
    m.box((5,3,13),(17,6,14),'air');m.box((5,3,23),(17,6,24),'air');entry(m,'entry',14,4)
    m.box((12,3,14),(12,6,32),'calcite')
    for zz in (19,27):m.door(12,3,zz,wood='dark_oak',facing='east')
    partition(m,5,14,11,8);partition(m,5,23,11,8)
    cook(m,'cook',5,6);dining(m,10,10,5)
    sleep(m,'bedsa',6,19,2);sleep(m,'bedsb',6,28,2)
    stores(m,'tools','走廊侧养护工具',14,17,3);counter(m,'records','后廊值班记录',14,23,3,block='lectern[facing=south]');stores(m,'linen','后廊共用被服',14,30,3)
    zone(m,'living','前端宽餐厨厅',5,5,17,13,'炊食与入户共用厅')
    zone(m,'sleepa','中部双床间',5,15,11,22,'两床与各自床柜，可从侧廊独立进出')
    zone(m,'sleepb','后部双床间',5,24,11,32,'第二双床间独立侧门')
    zone(m,'corridor','侧向长值守廊',13,14,17,32,'工具、记录、被服与贯通交通相隔一格以上')
    return finish(m,'窄长宅采用宽前厅、侧廊和两个独立双床间，四人家庭夜间进出不穿过他人床位；廊侧工具与记录成套设置。')


BUILDERS.update({f'ML-03-v{i+2:02}':f for i,f in enumerate((clock_wing,clock_tower,clock_pair,clock_terrace,clock_three))})
BUILDERS.update({f'ML-07-v{i+2:02}':f for i,f in enumerate((caretaker_duplex,caretaker_elbow,caretaker_upper,caretaker_raised,caretaker_corridor))})
