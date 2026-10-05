"""Medieval town revision: jettied framing, Romanesque stone and inhabited defenses."""
import math
from .revision_geometry import base,line,curve,arch,steps,arrival,work,table,shelf,bed,tree,disk,ring,window_rose

STONE='stone_bricks'
WOOD='dark_oak_log'


def european(n,name,w,h,d,terms,role='key',tags=()):
    m=base(f'EU-{n:02d}',name,(w,h,d),'欧洲中世纪新制',role=role,tags=tags,terms=terms)
    m.meta['source']='tools/structure_studio/studio/european_revision2.py:BUILDERS'
    m.box((3,1,3),(w-4,1,d-4),'cobblestone')
    return m


def shell(m,bounds,floor=1,height=8,mat=STONE):
    x0,z0,x1,z1=bounds
    m.box((x0,floor,z0),(x1,floor,z1),'spruce_planks')
    for x in (x0,x1):m.box((x,floor+1,z0),(x,floor+height,z1),mat)
    for z in (z0,z1):m.box((x0,floor+1,z),(x1,floor+height,z),mat)
    for y in (floor+1,floor+height):
        for x in (x0,x1):m.box((x,y,z0),(x,y,z1),STONE)
        for z in (z0,z1):m.box((x0,y,z),(x1,y,z),STONE)


def frame(m,bounds,floor=1,height=8,plaster='white_terracotta'):
    x0,z0,x1,z1=bounds;shell(m,bounds,floor,height,plaster)
    for z in (z0,z1):
        for x in sorted({x0,x1,*range(x0+5,x1,5)}):m.box((x,floor+1,z),(x,floor+height,z),WOOD)
        for x in range(x0,x1-4,5):
            line(m,(x+1,floor+2,z),(x+4,floor+height-1,z),'dark_oak_wood')
        for y in (floor+1,floor+height):m.box((x0,y,z),(x1,y,z),'dark_oak_log[axis=x]')
    for x in (x0,x1):
        for z in sorted({z0,z1,*range(z0+5,z1,5)}):m.box((x,floor+1,z),(x,floor+height,z),WOOD)
        for z in range(z0,z1-4,5):line(m,(x,floor+2,z+1),(x,floor+height-1,z+4),'dark_oak_wood')
        for y in (floor+1,floor+height):m.box((x,y,z0),(x,y,z1),'dark_oak_log[axis=z]')
    # Closely spaced corbels reveal the overhanging upper wall line.
    if floor>1:
        for z in (z0,z1):
            for x in range(x0+2,x1,3):m.set(x,floor-1,z,'dark_oak_stairs[facing='+('south' if z==z0 else 'north')+',half=top]')


def roof(m,bounds,eave,material='brick'):
    x0,z0,x1,z1=bounds;cx=(x0+x1)//2
    for x in range(x0-1,x1+2):
        rise=min(x-x0+1,x1+1-x);y=eave+rise
        face='east' if x<=cx else 'west'
        m.box((x,y,z0-1),(x,y,z1+1),material+'_stairs[facing='+face+']')
        if x0<=x<=x1:
            for z in (z0,z1):m.box((x,eave-1,z),(x,y,z),'white_terracotta' if material=='brick' else STONE)
    for z in (z0,z1):
        if material=='brick':
            m.box((cx,eave,z),(cx,eave+(x1-x0)//2,z),WOOD)
            for x in range(x0,x1+1):
                yy=eave+min(x-x0,x1-x);m.set(x,yy,z,'dark_oak_wood')
                if yy>=eave+4:m.set(x,eave+3,z,'dark_oak_log[axis=x]')
    for x in (x0,x1):m.box((x,eave-1,z0),(x,eave,z1),'dark_oak_log[axis=z]' if material=='brick' else STONE)
    m.box((cx,eave+(x1-x0)//2+2,z0-1),(cx,eave+(x1-x0)//2+2,z1+1),material+'_slab')


def window(m,c,z,y,width=3,height=3,axis='x'):
    def put(x,yy,zz,b):m.set(x,yy,zz,b) if axis=='x' else m.set(zz,yy,x,b)
    r=width//2
    for xx in range(c-r,c+r+1):
        for yy in range(y,y+height):put(xx,yy,z,'glass_pane')
        put(xx,y-1,z,'spruce_slab')
        put(xx,y+height,z,'dark_oak_log[axis=x]' if axis=='x' else 'dark_oak_log[axis=z]')
    for xx in (c-r-1,c+r+1):
        for yy in range(y,y+height):put(xx,yy,z,'spruce_trapdoor[facing=north,open=true]')


def doorway(m,c,z,y=2,width=3,height=5):
    arch(m,c,z,y,width,height-2,2,STONE,kind='round')


def parapet(m,bounds,floor):
    x0,z0,x1,z1=bounds
    for z in (z0,z1):
        m.box((x0,floor+1,z),(x1,floor+1,z),STONE)
        for x in range(x0,x1+1,3):m.box((x,floor+2,z),(min(x+1,x1),floor+3,z),STONE)
    for x in (x0,x1):
        m.box((x,floor+1,z0),(x,floor+1,z1),STONE)
        for z in range(z0,z1+1,3):m.box((x,floor+2,z),(x,floor+3,min(z+1,z1)),STONE)


def round_tower(m,cx,cz,r,top,cone=False):
    for y in range(2,top+1):
        ring(m,cx,cz,y,r,r,STONE,2)
        if y%7==0:ring(m,cx,cz,y,r+1,r+1,'stone_brick_slab[type=top]',2)
    disk(m,cx,cz,top,r,STONE)
    if cone:
        for dy in range(r+5):
            rr=max(0,r+1-dy);disk(m,cx,cz,top+1+dy,rr,'bricks')
    else:
        ring(m,cx,cz,top+1,r,r,STONE)
        for k in range(12):
            a=k*math.pi/6;x=cx+round(r*math.cos(a));z=cz+round(r*math.sin(a));m.box((x,top+2,z),(x,top+3,z),STONE)
    for y in range(5,top-3,7):
        for z in (cz-r,cz+r):m.box((cx,y,z),(cx,y+2,z),'iron_bars')
        for x in (cx-r,cx+r):m.box((x,y,cz),(x,y+2,cz),'iron_bars')


def castle():
    m=european(1,'城门主堡 · 圆塔环墙与三层领主楼',85,59,87,('防御','驻守','议事'))
    arrival(m,42)
    for bounds in ((9,17,75,20),(9,73,75,76),(9,20,12,73),(72,20,75,73)):
        x0,z0,x1,z1=bounds;m.box((x0,2,z0),(x1,12,z1),STONE);parapet(m,bounds,12)
    for cx,cz in ((12,20),(72,20),(12,73),(72,73)):round_tower(m,cx,cz,6,18)
    for z in (17,18,19,20):arch(m,42,z,2,9,5,5,STONE,kind='round')
    for x in (32,52):round_tower(m,x,18,5,27,True)
    # Wall patrol is accessed by a dedicated internal stair and a dry landing.
    steps(m,14,16,42,1,12);m.box((10,12,53),(16,12,56),STONE)
    m.box((12,13,53),(16,16,56),'air');work(m,'wall',11,13,57,'环墙巡逻通道')
    shell(m,(28,42,56,69),1,25)
    for y in (9,17,26):m.box((29,y,43),(55,y,68),'spruce_planks' if y<26 else STONE)
    steps(m,31,33,46,1,9);steps(m,49,51,60,9,17,south=False);steps(m,31,33,46,17,26)
    doorway(m,42,42,2,5,7);parapet(m,(28,42,56,69),26)
    for y in (5,13,21):
        for x in (34,42,50):window(m,x,42,y,3,4);window(m,x,69,y,3,4)
        for z in (49,60):window(m,z,28,y,3,4,axis='z');window(m,z,56,y,3,4,axis='z')
    for x in (27,57):
        for z in (41,70):round_tower(m,x,z,3,32,True)
    for x in (38,46):bed(m,f'guard_{x}',x,2,64,'red')
    table(m,36,10,62,13,'oak');table(m,36,18,62,13,'oak')
    work(m,'council',42,10,58,'领主楼议事层');work(m,'private',42,18,58,'上层会见厅');work(m,'roof',42,27,60,'主堡瞭望台')
    for x in (29,54):m.box((x,20,41),(x+1,24,41),'red_wool')
    m.room('hall','领主楼',(30,2,44),(54,25,67),'真实三层主楼与瞭望平台')
    m.meta.update(roof_min_y=28,floors=[{'name':'内院驻守','y':1,'max_y':8},{'name':'议事与环墙','y':9,'max_y':16},{'name':'上层会见','y':17,'max_y':25},{'name':'瞭望台','y':26,'max_y':35}])
    return m


def church():
    m=european(2,'十字钟塔教堂 · 圆券侧廊与后部半圆祭堂',65,61,81,('礼仪','集会'))
    arrival(m,32);shell(m,(22,24,42,66),1,23)
    roof(m,(22,24,42,66),25,'deepslate_tile')
    for bounds in ((12,29,21,63),(43,29,52,63),(6,44,21,56),(43,44,58,56)):
        shell(m,bounds,1,10);roof(m,bounds,12,'stone_brick')
    for x in (21,22,42,43):
        for z in (35,49,59):arch(m,z,x,2,7,4,4,STONE,axis='z',kind='round')
    for z in (34,42,59):
        for x in (22,42):arch(m,z,x,15,3,3,3,STONE,axis='z',fill='yellow_stained_glass',kind='round')
        for x in (10,54):
            m.box((x,2,z),(x+1,11,z+1),STONE);m.box((x,12,z),(x+1,12,z+1),'stone_brick_slab')
    round_tower(m,32,64,9,17)
    m.box((28,2,55),(36,13,66),'air')
    m.box((25,2,61),(39,3,69),STONE);steps(m,29,35,59,1,3)
    table(m,29,4,66,7,'oak');work(m,'altar',32,4,64,'半圆后祭堂')
    shell(m,(25,9,39,24),1,34);doorway(m,32,9,2,5,8);doorway(m,32,24,2,5,8)
    m.box((26,26,10),(38,26,23),STONE)
    for z in (9,24):arch(m,32,z,27,7,2,4,STONE,kind='round')
    for x in (25,39):arch(m,16,x,27,7,2,4,STONE,axis='z',kind='round')
    roof(m,(25,9,39,24),36,'deepslate_tile');m.set(32,32,16,'bell[attachment=ceiling]')
    m.box((30,33,16),(34,33,16),'dark_oak_log[axis=x]')
    window_rose(m,32,19,8,4,'yellow_stained_glass',STONE)
    for x in (24,40):
        m.box((x,2,8),(x,35,9),'polished_andesite')
        for y in (10,25,34):m.box((x-1,y,7),(x+1,y,10),'stone_brick_slab[type=top]')
    for y in (10,25,34):
        for z in (8,25):m.box((24,y,z),(40,y,z),'stone_brick_slab[type=top]')
        for x in (24,40):m.box((x,y,8),(x,y,25),'stone_brick_slab[type=top]')
    for x in (27,37):arch(m,x,8,11,3,2,3,'polished_andesite',depth=2,fill='yellow_stained_glass',kind='round')
    for z in (31,37,43,49,55):
        for x in (26,35):m.box((x,2,z),(x+3,2,z),'oak_stairs[facing=south]')
    m.room('nave','十字中殿',(24,2,26),(40,21,58),'圆券侧廊、分列会众席与后祭堂')
    m.meta['roof_min_y']=25
    return m


def town_hall():
    m=european(3,'市政会馆 · 石券底廊与三山墙挑楼',61,49,57,('公共办事','议事','文献保管'))
    arrival(m,30);shell(m,(10,16,50,46),1,7)
    for x in (18,30,42):arch(m,x,16,2,7,2,3,STONE,kind='round')
    frame(m,(9,15,51,47),8,9)
    for bounds in ((9,15,22,47),(23,15,37,47),(38,15,51,47)):roof(m,bounds,18,'brick')
    steps(m,13,15,24,1,8)
    for x in (16,30,44):window(m,x,15,11,3,4);window(m,x,47,11,3,4)
    for x in (9,51):
        for z in (24,36):window(m,z,x,11,3,4,axis='z')
    for x in (19,34):table(m,x,2,34,7,'oak')
    table(m,21,9,34,17,'oak');shelf(m,21,9,43,17,'spruce')
    m.box((28,20,14),(32,24,14),'white_wool');m.set(30,22,13,'gold_block')
    work(m,'service',30,2,29,'底廊办事窗口');work(m,'council',30,9,30,'挑楼议事厅')
    m.room('civic','市政议事厅',(12,9,19),(48,16,44),'三座山墙覆盖的宽阔木构议事层')
    m.meta.update(roof_min_y=18,floors=[{'name':'办事底廊','y':1,'max_y':7},{'name':'议事挑楼','y':8,'max_y':17}])
    return m


def market():
    m=european(4,'木构市场大厅 · 三跨锤梁与连续摊廊',59,43,65,('集市交易','货物暂存'))
    arrival(m,29)
    for z in (13,25,37,49,55):
        for x in (10,21,37,48):m.box((x,2,z),(x,14 if x in (21,37) else 9,z),WOOD)
        m.box((21,15,z),(37,15,z),'dark_oak_log[axis=x]')
        for x in (21,37):line(m,(x,10,z),(29,22,z),'dark_oak_wood')
        for a,b in ((10,21),(37,48)):
            line(m,(a,8,z),(a+5,14,z),'dark_oak_wood')
            line(m,(b-1,8,z),(a+5,14,z),'dark_oak_wood')
    for bounds,eave in [((10,13,20,55),10),((21,13,37,55),16),((38,13,48,55),10)]:roof(m,bounds,eave,'brick')
    for x in (13,40):
        for z in (20,32,44):
            table(m,x,2,z,6,'oak');m.set(x+1,4,z,'barrel');m.set(x+4,4,z,'melon')
            work(m,f'stall_{x}_{z}',x+3,2,z-3,'独立摊位交易面')
    work(m,'aisle',29,2,32,'主市场通路');m.room('market','三跨市场厅',(12,2,15),(46,14,53),'高跨通路与两侧低跨摊廊')
    m.meta['roof_min_y']=17
    return m


BUILDERS={f'EU-{i:02d}-v01':f for i,f in ((1,castle),(2,church),(3,town_hall),(4,market))}


def street_house():
    m=european(5,'挑楼街屋 · 窄面三层与逐级出挑',35,43,47,('居住','家庭生活'),role='fill')
    arrival(m,17);shell(m,(10,12,24,35),1,7)
    frame(m,(9,11,25,36),8,7);frame(m,(8,10,26,37),15,7);roof(m,(8,10,26,37),23)
    doorway(m,17,12);steps(m,11,13,18,1,8);steps(m,21,23,30,8,15,south=False)
    for y,z in ((11,11),(18,10)):
        for x in (13,21):window(m,x,z,y);window(m,x,37 if y==18 else 36,y)
    window(m,22,10,4,3,3,axis='z');m.box((25,2,29),(27,29,31),STONE)
    m.set(24,2,29,'smoker');table(m,15,2,30,5,'oak')
    bed(m,'bed1',16,9,32,'red');bed(m,'bed2',16,16,31,'blue')
    work(m,'home',17,2,19,'街屋起居厅');m.room('home','逐层挑楼',(11,2,14),(23,21,34),'真实三层窄面住宅与两段室内楼梯')
    m.meta.update(roof_min_y=23,floors=[{'name':'起居层','y':1,'max_y':7},{'name':'二层卧室','y':8,'max_y':14},{'name':'三层卧室','y':15,'max_y':22}]);return m


def courtyard_inn():
    m=european(6,'三层庭院客栈 · 马车门洞与木廊客房',71,52,75,('旅宿','餐饮'),role='fill')
    arrival(m,35)
    for bounds in ((8,16,62,29),(8,30,25,62),(45,30,62,62),(26,51,44,62)):
        shell(m,bounds,1,7);frame(m,bounds,8,7);frame(m,bounds,15,7)
        if bounds==(8,16,62,29):
            for a,b in ((8,25),(26,44),(45,62)):roof(m,(a,16,b,29),23)
        else:roof(m,bounds,23)
    for z in range(16,30):arch(m,35,z,2,9,3,4,STONE,kind='round')
    for y in (8,15):
        m.box((25,y,29),(29,y,51),'spruce_planks');m.box((41,y,29),(45,y,51),'spruce_planks');m.box((25,y,47),(45,y,51),'spruce_planks')
        for x in (29,41):m.box((x,y+1,30),(x,y+1,46),'spruce_fence')
        m.box((30,y+1,47),(40,y+1,47),'spruce_fence')
    for x in (27,43):
        for z in (30,40,49):m.box((x,2,z),(x,15,z),WOOD)
    for floor,x0 in ((1,10),(8,19)):
        steps(m,x0,x0+2,32,floor,floor+7)
        m.box((x0,floor+8,39),(x0+2,floor+10,41),'air')
    for y in (2,9,16):
        for z in (35,44,56):
            arch(m,z,25,y,3,2,2,WOOD,axis='z');arch(m,z,45,y,3,2,2,WOOD,axis='z')
        for x in (17,53):
            for z in (40,55):bed(m,f'guest_{x}_{y}_{z}',x,y,z,'red')
    for y in (11,18):
        for x in (16,26,44,54):window(m,x,16,y)
        for z in (35,45,56):window(m,z,8,y,3,3,axis='z');window(m,z,62,y,3,3,axis='z')
    for x in (15,46):table(m,x,2,24,10,'oak')
    for y in (9,16):work(m,f'gallery_{y}',27,y,44,'内院客房木廊')
    disk(m,35,39,1,4,STONE);m.box((34,2,38),(36,3,40),'stone_bricks');m.set(35,3,39,'water')
    m.room('court','马车庭院',(30,2,31),(40,20,46),'穿过前楼门洞进入三层环绕庭院')
    m.meta.update(roof_min_y=23,floors=[{'name':'餐厅与内院','y':1,'max_y':7},{'name':'二层客房','y':8,'max_y':14},{'name':'三层客房','y':15,'max_y':22}]);return m


def smithy():
    m=european(7,'铁匠作坊 · 敞口锻棚与石砌双烟道',49,37,51,('锻造','修理'),role='fill')
    arrival(m,24);shell(m,(25,17,41,41),1,9);roof(m,(25,17,41,41),11)
    frame(m,(7,17,24,41),1,8);m.box((9,2,17),(22,7,17),'air');roof(m,(7,17,24,41),10)
    for x in (24,25):arch(m,28,x,2,5,2,3,STONE,axis='z',kind='round')
    for x in (30,37):
        m.box((x-2,2,34),(x+2,6,39),STONE);arch(m,x,34,2,3,1,2,'bricks',depth=2,kind='round')
        m.set(x,2,37,'campfire[lit=true]');m.box((x-1,7,36),(x+1,29,38),'bricks')
        m.box((x-2,29,35),(x+2,30,39),'brick_slab')
    for x,z in ((12,25),(20,34)):m.set(x,2,z,'anvil');m.set(x+2,2,z,'water_cauldron[level=3]')
    m.box((9,2,38),(20,3,39),'barrel');work(m,'forge',33,2,29,'双炉锻造位');work(m,'repair',16,2,27,'敞棚修理位')
    m.room('forge','开放锻棚',(9,2,20),(39,9,39),'锻棚、储料房与突出屋顶的双砖烟道');return m


def stone_bridge():
    m=european(8,'三券石桥 · 厚墩圆拱与桥头避让台',35,31,67,('步行通行',),role='structure',tags=('infrastructure',))
    arrival(m,17);m.box((0,1,21),(34,1,45),'water')
    m.box((9,2,18),(25,9,49),STONE)
    for z in (23,34,45):arch(m,z,9,2,7,1,5,STONE,axis='z',depth=17,kind='round')
    m.box((8,10,18),(26,10,49),'stone_bricks');steps(m,12,22,9,1,10);steps(m,12,22,58,1,10,south=False)
    for x in (8,26):
        m.box((x,11,18),(x,12,49),STONE)
        for z in (19,31,37,48):m.box((x-1,10,z-1),(x+1,10,z+1),STONE);m.set(x,13,z,'lantern')
    work(m,'bridge',17,11,34,'三券桥桥面');m.meta['roof_min_y']=16;return m


def street_lamp():
    m=european(9,'铁吊街灯 · 斜撑木柱与双悬灯笼',17,24,17,('街道照明',),role='structure',tags=('infrastructure',))
    arrival(m,8);m.box((7,2,7),(9,3,9),STONE);m.box((8,4,8),(8,16,8),WOOD)
    m.box((3,16,8),(13,16,8),'dark_oak_log[axis=x]')
    for x in (3,13):
        line(m,(8,11,8),(x,16,8),'dark_oak_wood');m.box((x,13,8),(x,15,8),'chain');m.set(x,12,8,'lantern[hanging=true]')
    work(m,'lamp',8,2,5,'灯柱维护位');return m


def fountain():
    m=european(10,'八角喷泉 · 公共取水池与狮口石柱',35,29,35,('景观','公共取水'),role='structure',tags=('landscape',))
    arrival(m,17)
    for x in range(5,30):
        for z in range(5,30):
            a,b=abs(x-17),abs(z-17)
            if max(a,b)<=11 and a+b<=17:
                m.set(x,1,z,'stone_bricks');m.set(x,2,z,'water' if max(a,b)<=9 and a+b<=14 else 'smooth_stone')
    m.box((15,2,15),(19,6,19),STONE);m.box((16,7,16),(18,15,18),'chiseled_stone_bricks')
    disk(m,17,17,11,5,STONE);disk(m,17,17,12,5,STONE);disk(m,17,17,12,4,'water')
    for dx,dz in ((-2,0),(2,0),(0,-2),(0,2)):m.set(17+dx,6,17+dz,'chiseled_stone_bricks')
    m.set(17,16,17,'stone_brick_wall');work(m,'water',17,2,4,'街角取水池');return m


def oak_square():
    m=european(11,'广场橡树 · 环树长椅与集会空地',43,41,43,('景观','休憩'),role='structure',tags=('landscape',))
    arrival(m,21);disk(m,21,23,1,8,'grass_block');tree(m,21,23,24,8)
    for x,z in ((13,13),(26,13),(13,32),(26,32)):m.box((x,2,z),(x+3,2,z),'spruce_stairs[facing=south]')
    for x,z in ((7,10),(35,10),(7,35),(35,35)):m.set(x,2,z,'cobblestone_wall');m.set(x,3,z,'lantern')
    work(m,'rest',21,2,11,'树荫集会空地');return m


def cottage_garden():
    m=european(12,'宅旁花园 · 木篱门架与分格香草畦',39,26,41,('景观','园艺养护'),role='structure',tags=('landscape',))
    arrival(m,19)
    for x in (4,34):m.box((x,2,6),(x,2,35),'oak_fence')
    for z in (6,35):m.box((4,2,z),(34,2,z),'oak_fence')
    m.box((17,2,6),(21,2,6),'air')
    for x in (16,22):m.box((x,2,6),(x,8,6),'oak_log')
    m.box((15,8,5),(23,8,7),'oak_slab');m.box((15,9,5),(23,9,7),'flowering_azalea_leaves[persistent=true]')
    for x in (8,25):
        for z in (12,24):
            m.box((x,1,z),(x+5,1,z+5),'farmland[moisture=7]')
            for xx in range(x,x+6):
                for zz in range(z,z+6):m.set(xx,2,zz,'carrots[age=7]' if z==12 else 'potatoes[age=7]')
    m.box((17,1,6),(21,1,33),'gravel');tree(m,10,32,15,3)
    work(m,'garden',20,2,20,'香草园护理通道');return m


def monastery():
    m=european(13,'修道院 · 圆券四面回廊与独立小堂',77,48,79,('礼仪','文献保管','集体居住'))
    arrival(m,38)
    for bounds in ((8,17,23,67),(54,17,69,67),(24,17,53,29),(24,56,53,67)):
        shell(m,bounds,1,9);roof(m,bounds,11,'stone_brick')
    # Continuous sheltered arcade, with a real open central garden.
    for x in (26,51):
        for z in range(31,57,6):arch(m,z,x,2,5,3,3,STONE,axis='z',fill=None,kind='round')
        m.box((x,9,28),(x,9,59),STONE)
    for z in (32,54):
        for x in range(28,53,6):arch(m,x,z,2,5,3,3,STONE,fill=None,kind='round')
        m.box((24,9,z),(54,9,z),STONE)
    m.box((23,10,29),(27,10,56),'stone_brick_slab');m.box((50,10,29),(54,10,56),'stone_brick_slab')
    m.box((27,10,29),(50,10,33),'stone_brick_slab');m.box((27,10,53),(50,10,56),'stone_brick_slab')
    doorway(m,38,17,2,5,6);doorway(m,38,29,2,5,6)
    for z in (37,49):arch(m,z,23,2,3,2,3,STONE,axis='z',kind='round');arch(m,z,54,2,3,2,3,STONE,axis='z',kind='round')
    for z in (35,45,57):bed(m,f'monk_{z}',13,2,z,'brown');shelf(m,58,2,z,6,'oak')
    for x in (30,42):table(m,x,2,62,7,'oak')
    disk(m,38,43,1,7,'grass_block');m.box((36,2,41),(40,3,45),STONE);m.set(38,3,43,'water')
    round_tower(m,61,22,4,25,True)
    work(m,'cloister',29,2,40,'圆券修道回廊');work(m,'scriptorium',60,2,41,'抄写与藏书室')
    m.room('cloister','内院回廊',(25,2,30),(52,9,56),'连续可行走的四面圆券廊与中央水庭');return m


def manor():
    m=european(14,'领主庄园 · 石厅木翼与坡顶门屋',79,51,83,('居住','正式接见','庄园管理'))
    arrival(m,39)
    shell(m,(24,35,54,69),1,16);roof(m,(24,35,54,69),18)
    for bounds in ((9,31,23,64),(55,40,69,69)):
        shell(m,bounds,1,7);frame(m,bounds,8,8);roof(m,bounds,17)
    for z in (43,57):
        for x in (23,24,54,55):arch(m,z,x,2,5,2,3,STONE,axis='z',kind='round')
    m.box((10,8,32),(22,8,63),'spruce_planks');steps(m,12,14,35,1,8)
    for z in (45,56):bed(m,f'lord_{z}',18,9,z,'red');window(m,z,9,11,3,3,axis='z')
    for x in (31,47):window(m,x,35,6,5,7);window(m,x,69,6,5,7)
    for z in (44,55,64):
        m.box((25,2,z),(25,13,z),WOOD);m.box((53,2,z),(53,13,z),WOOD)
        line(m,(25,12,z),(39,26,z),'dark_oak_wood');line(m,(53,12,z),(39,26,z),'dark_oak_wood')
    doorway(m,39,35,2,5,7);table(m,31,2,57,17,'oak');work(m,'audience',39,2,47,'庄园大石厅')
    for x in (9,69):m.box((x,2,10),(x,4,31),STONE)
    m.box((9,2,10),(69,4,10),STONE);shell(m,(31,7,47,17),1,7);roof(m,(31,7,47,17),9)
    for z in (7,17):doorway(m,39,z,2,5,6)
    for x in (17,61):tree(m,x,22,17,4)
    work(m,'room',18,9,50,'庄园卧室木翼');m.room('great_hall','庄园大石厅',(27,2,38),(51,16,66),'敞露木桁架石厅连通两侧生活翼')
    m.meta.update(roof_min_y=18,floors=[{'name':'石厅与服务翼','y':1,'max_y':7},{'name':'卧室木翼','y':8,'max_y':16}]);return m


def guildhall():
    m=european(15,'商人行会馆 · 阶梯山墙与双层秤量厅',57,48,61,('商贸管理','集会','货物称量'))
    arrival(m,28);shell(m,(12,15,44,49),1,8,'bricks');frame(m,(11,14,45,50),9,9,'yellow_terracotta')
    roof(m,(11,14,45,50),19)
    for z in (13,51):
        for x in range(10,47):
            yy=20+(17-abs(x-28))//3*3
            if yy>=20:m.box((x,19,z),(x,yy,z),'bricks');m.set(x,yy+1,z,'stone_brick_slab')
        for x,y in ((20,22),(28,29),(36,22)):window(m,x,z,y,3,3)
    for x in (19,28,37):doorway(m,x,15,2,5,6);window(m,x,14,12,3,4)
    steps(m,15,17,25,1,9)
    for x in (25,37):table(m,x-3,2,33,7,'oak')
    table(m,23,10,36,13,'oak');shelf(m,23,10,46,13,'oak')
    m.box((33,2,23),(33,7,23),WOOD);m.box((29,7,23),(37,7,23),'dark_oak_log[axis=x]')
    for x in (29,37):m.box((x,5,23),(x,6,23),'chain');m.set(x,4,23,'heavy_weighted_pressure_plate');m.set(x,3,23,'iron_block')
    work(m,'weigh',32,2,27,'商业秤量厅');work(m,'guild',28,10,30,'行会集会厅')
    m.room('guild','行会上厅',(14,10,18),(42,17,47),'宽敞会议厅与独立账册架');m.meta.update(roof_min_y=19,floors=[{'name':'交易称量层','y':1,'max_y':8},{'name':'行会集会层','y':9,'max_y':18}]);return m


def clock_tower():
    m=european(16,'城镇钟楼 · 石基木钟室与四面表盘',39,62,43,('报时','公共瞭望'))
    arrival(m,19);shell(m,(11,11,27,31),1,30)
    for floor in (9,17,25):m.box((12,floor,12),(26,floor,30),'spruce_planks')
    for sf,ef,x,z,south in ((1,9,13,14,True),(9,17,23,27,False),(17,25,13,14,True)):
        steps(m,x,x+2,z,sf,ef,south=south)
    doorway(m,19,11,2,5,7);frame(m,(10,10,28,32),31,10)
    for z in (10,32):arch(m,19,z,33,9,2,4,WOOD,kind='round')
    for x in (10,28):arch(m,21,x,33,11,2,4,WOOD,axis='z',kind='round')
    for z in (10,32):
        m.box((15,25,z),(23,29,z),'white_wool');m.box((19,26,z-1 if z==10 else z+1),(19,29,z-1 if z==10 else z+1),'black_concrete')
        m.box((19,27,z-1 if z==10 else z+1),(22,27,z-1 if z==10 else z+1),'black_concrete')
    roof(m,(10,10,28,32),42,'deepslate_tile');m.box((16,39,21),(22,39,21),'dark_oak_log[axis=x]');m.set(19,38,21,'bell[attachment=ceiling]')
    for y in (5,13,21):window(m,20,11,y,3,3)
    work(m,'clock',19,26,23,'钟楼上部检修层');m.meta.update(roof_min_y=42,floors=[{'name':'入口','y':1,'max_y':8},{'name':'第一平台','y':9,'max_y':16},{'name':'第二平台','y':17,'max_y':24},{'name':'报时检修','y':25,'max_y':40}]);return m


def watermill():
    m=european(17,'水车磨坊 · 外露轮轴与挑楼磨粮间',57,47,61,('粮食加工','货物暂存'),role='fill')
    arrival(m,23);m.box((39,1,0),(53,1,60),'water')
    shell(m,(10,15,37,46),1,8);frame(m,(9,14,38,47),9,8);roof(m,(9,14,38,47),18)
    doorway(m,23,15,2,5,6);steps(m,13,15,22,1,9)
    # Wheel stands in the water race. Both rims, buckets and the axle are modeled.
    for x in (42,46):
        for y in range(2,23):
            for z in range(23,46):
                d=math.hypot(y-12,z-34)
                if 9<=d<=10:m.set(x,y,z,'dark_oak_wood')
        for k in range(8):
            a=k*math.pi/4;line(m,(x,12,34),(x,12+round(10*math.sin(a)),34+round(10*math.cos(a))),'oak_log')
    m.box((31,12,34),(48,12,34),'dark_oak_log[axis=x]')
    for k in range(16):
        a=k*math.pi/8;y=12+round(10*math.sin(a));z=34+round(10*math.cos(a));m.box((42,y,z),(46,y,z),'oak_planks')
    for x,z in ((23,28),(31,39)):disk(m,x,z,2,3,'stone');m.set(x,3,z,'grindstone[face=floor]')
    for x in (20,29):m.box((x,10,42),(x+3,11,44),'hay_block');window(m,x,14,12,3,3)
    work(m,'mill',24,2,34,'轮轴磨粮间');work(m,'grain',24,10,33,'上层储粮与筛料间')
    m.room('mill','水轮磨粮房',(12,2,18),(35,8,44),'真实外露轮轴穿墙并连接上层工作架');m.meta.update(roof_min_y=18,floors=[{'name':'磨粮间','y':1,'max_y':8},{'name':'储粮间','y':9,'max_y':17}]);return m


def bakery():
    m=european(18,'双窑面包坊 · 前店木屋与后部圆窑',49,39,53,('食品制作','零售'),role='fill')
    arrival(m,24);frame(m,(10,12,38,31),1,8);roof(m,(10,12,38,31),10)
    doorway(m,24,12,2,5,5)
    for cx in (16,32):
        for y,r in ((2,7),(3,7),(4,6),(5,6),(6,5),(7,4),(8,3)):
            disk(m,cx,38,y,r,'bricks')
        m.box((cx-2,2,32),(cx+2,4,40),'air');m.set(cx,2,39,'campfire[lit=true]')
        arch(m,cx,31,2,3,1,2,'bricks',depth=3,kind='round')
        m.box((cx-1,8,38),(cx+1,26,40),'bricks');m.box((cx-2,26,37),(cx+2,27,41),'brick_slab')
    for x in (16,29):table(m,x-2,2,24,6,'oak')
    for x in (15,33):window(m,x,12,4)
    work(m,'bake',24,2,27,'双炉备料烘焙位');work(m,'shop',24,2,18,'前店交付柜台');return m


def dyer():
    m=european(19,'织布染坊 · 开窗织厅与露天染缸',57,37,55,('纺织','手工制作'),role='fill')
    arrival(m,28);frame(m,(8,14,31,43),1,10);roof(m,(8,14,31,43),12)
    doorway(m,20,14,2,5,6)
    for z in (22,32):
        for x in (8,31):window(m,z,x,5,5,4,axis='z')
        for x in (14,25):m.set(x,2,z,'loom');m.box((x-1,3,z+1),(x+1,5,z+1),'white_wool')
    for x in (38,47):
        for z in (22,33):m.box((x-2,2,z-2),(x+2,3,z+2),'bricks');m.box((x-1,3,z-1),(x+1,3,z+1),'water')
    for x in (35,51):m.box((x,2,43),(x,9,43),'oak_log')
    m.box((35,9,43),(51,9,43),'chain');m.box((38,5,43),(42,8,43),'blue_wool');m.box((46,5,43),(49,8,43),'red_wool')
    arch(m,36,31,2,3,2,3,WOOD,axis='z');work(m,'weave',20,2,28,'开窗织造厅');work(m,'dye',42,2,28,'露天染缸通道');return m


def warehouse():
    m=european(20,'临街货仓 · 石基高仓与山墙吊货架',51,47,65,('货物仓储','装卸'),role='fill')
    arrival(m,25);shell(m,(10,14,40,53),1,8);frame(m,(10,14,40,53),9,9);roof(m,(10,14,40,53),19)
    doorway(m,25,14,2,7,7);arch(m,25,14,10,5,3,4,WOOD)
    steps(m,13,15,23,1,9)
    for y in (2,10):
        for x in (18,31):
            for z in (35,46):m.box((x,y,z),(x+3,y+2,z+3),'barrel')
        work(m,f'aisle_{y}',25,y,40,'货仓贯通通道')
    m.box((25,25,7),(25,25,20),'dark_oak_log[axis=z]');line(m,(25,18,14),(25,25,7),'dark_oak_wood')
    m.box((25,15,7),(25,24,7),'chain');m.box((23,14,5),(27,14,9),'spruce_planks')
    for z in (25,38,48):window(m,z,10,12,3,3,axis='z');window(m,z,40,12,3,3,axis='z')
    m.room('warehouse','双层货仓',(12,2,17),(38,17,51),'室内楼梯、成组货垛与山墙吊货口');m.meta.update(roof_min_y=19,floors=[{'name':'装卸层','y':1,'max_y':8},{'name':'高仓层','y':9,'max_y':18}]);return m


def barracks():
    m=european(21,'守卫营房 · 石屋寝室与操练前院',61,38,67,('驻守','集体居住','训练'),role='fill')
    arrival(m,30);shell(m,(10,33,50,56),1,8)
    for bounds in ((10,33,29,56),(30,33,50,56)):roof(m,bounds,10,'deepslate_tile')
    doorway(m,30,33,2,5,6)
    for x in (17,25,35,43):bed(m,f'guard_{x}',x,2,50,'blue');window(m,x,56,4)
    for x in (11,49):
        m.box((x,2,10),(x,5,32),STONE)
        for z in (14,23):m.box((x,6,z),(x,7,z),STONE)
    for x in (18,42):
        m.box((x,2,18),(x,5,18),'oak_log');m.box((x-2,4,18),(x+2,4,18),'oak_log[axis=x]')
    table(m,22,2,40,17,'oak');work(m,'training',30,2,22,'营房操练场');work(m,'guard',30,2,45,'寝室与值班厅');return m


def town_gate():
    m=european(22,'双塔城门 · 圆塔箭窗与巡逻门楼',65,49,49,('防御','步行通行'),role='structure',tags=('infrastructure',))
    arrival(m,32)
    for x in (18,46):round_tower(m,x,25,9,27,True)
    shell(m,(24,19,40,32),1,16);m.box((24,10,19),(40,10,32),STONE)
    for z in range(19,33):arch(m,32,z,2,7,3,4,STONE,kind='round')
    parapet(m,(24,19,40,32),17);m.box((25,17,20),(39,17,31),STONE)
    steps(m,27,29,35,1,10,south=False);m.box((27,11,26),(29,13,33),'air')
    steps(m,35,37,29,10,17,south=False)
    for x in (24,40):m.box((x,2,36),(x,11,36),STONE)
    work(m,'gate',32,2,16,'城门检查位');work(m,'patrol',32,18,24,'双塔间上层巡逻台')
    m.meta.update(roof_min_y=28,floors=[{'name':'穿行门洞','y':1,'max_y':9},{'name':'门楼中层','y':10,'max_y':16},{'name':'巡逻台','y':17,'max_y':27}]);return m


def river_port():
    m=european(23,'吊机河港 · 三角桁架与木桩栈桥',67,45,65,('装卸','货物暂存'),role='structure',tags=('infrastructure',))
    arrival(m,33);m.box((0,1,37),(66,1,64),'water');m.box((8,2,12),(58,3,39),STONE);steps(m,29,37,10,1,3)
    for x in (15,49):
        m.box((x-4,3,38),(x+4,3,59),'spruce_planks')
        for z in (42,50,58):
            for xx in (x-4,x+4):m.box((xx,1,z),(xx,5,z),'oak_log')
        work(m,f'berth_{x}',x,4,53,'河港靠泊装卸栈桥')
    m.box((28,4,28),(30,28,30),WOOD);m.box((28,28,25),(30,28,51),'dark_oak_log[axis=z]')
    line(m,(29,12,29),(29,28,49),'dark_oak_wood',1);m.box((29,9,49),(29,27,49),'chain');m.box((26,8,46),(32,8,52),'spruce_planks')
    for x,z in ((13,18),(44,19),(48,29)):m.box((x,4,z),(x+5,6,z+4),'barrel')
    work(m,'crane',33,4,30,'三角起重架控制位');m.meta['roof_min_y']=29;return m


def orchard():
    m=european(24,'果园花篱 · 列植矮果树与藤架小径',49,31,55,('景观','果树养护'),role='structure',tags=('landscape',))
    arrival(m,24)
    for x in (5,43):m.box((x,2,6),(x,2,49),'oak_fence')
    for z in (6,49):m.box((5,2,z),(43,2,z),'oak_fence')
    m.box((22,2,6),(26,2,6),'air');m.box((22,1,6),(26,1,49),'gravel')
    for x in (13,35):
        for z in (17,37):
            tree(m,x,z,14,4,'oak','flowering_azalea_leaves')
            m.set(x-1,2,z+2,'composter[level=4]')
    for z in (9,19,29,39):
        for x in (20,28):m.box((x,2,z),(x,8,z),'oak_log')
        m.box((20,8,z),(28,8,z),'oak_log[axis=x]')
    for x in (20,28):m.box((x,8,9),(x,8,39),'oak_leaves[persistent=true]')
    work(m,'orchard',24,2,27,'果树与藤架养护道');return m


BUILDERS.update({f'EU-{i:02d}-v01':f for i,f in ((5,street_house),(6,courtyard_inn),(7,smithy),(8,stone_bridge),(9,street_lamp),(10,fountain),(11,oak_square),(12,cottage_garden),(13,monastery),(14,manor),(15,guildhall),(16,clock_tower),(17,watermill),(18,bakery),(19,dyer),(20,warehouse),(21,barracks),(22,town_gate),(23,river_port),(24,orchard))})
