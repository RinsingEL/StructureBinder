"""Elven revision: branching piers, petal vaults, open loggias and garden terraces."""
import math
from .revision_geometry import base,line,curve,arch,steps,arrival,work,table,shelf,bed,tree,ring,disk

WHITE='smooth_quartz'
RIB='quartz_pillar'
LEAF='waxed_oxidized_cut_copper'


def elf(n,name,w,h,d,terms,role='key',tags=()):
    m=base(f'EL-{n:02d}',name,(w,h,d),'精灵新制',role=role,tags=tags,terms=terms)
    m.meta['source']='tools/structure_studio/studio/elven_revision2.py:BUILDERS'
    return m


def branch(m,x,z,floor,top,spread=4,axis='x'):
    m.box((x-1,floor,z-1),(x+1,floor,z+1),WHITE)
    m.box((x,floor+1,z),(x,top-4,z),RIB)
    for sign in (-1,1):
        end=(x+sign*spread,top,z) if axis=='x' else (x,top,z+sign*spread)
        mid=(x+sign,top-1,z) if axis=='x' else (x,top-1,z+sign)
        curve(m,((x,top-6,z),mid,end),WHITE)
    for dx,dz in ((-1,0),(1,0),(0,-1),(0,1)):
        line(m,(x+dx*2,floor,z+dz*2),(x,floor+4,z),WHITE)


def leaf_vault(m,cx,cz,rx,rz,eave,height):
    # An elongated pointed leaf canopy: curved in section and tapered at each end.
    for z in range(cz-rz,cz+rz+1):
        t=abs(z-cz)/rz;rr=max(1,round(rx*math.sqrt(max(0,1-t*t))))
        for x in range(cx-rr,cx+rr+1):
            y=eave+round(height*(1-abs(x-cx)/max(1,rr))**1.65)+round(2*(1-t))
            m.set(x,y,z,LEAF)
            # Join steep voxel courses into a closed roof skin.
            nx=min(abs(x-cx)+1,rr);ny=eave+round(height*(1-nx/max(1,rr))**1.65)+round(2*(1-t))
            if y>ny:m.box((x,ny,z),(x,y,z),LEAF)
        if z%6==cz%6:
            for x in range(cx-rr,cx+rr+1):
                y=eave+round(height*(1-abs(x-cx)/rr)**1.65)+round(2*(1-t))
                nx=min(abs(x-cx)+1,rr);ny=eave+round(height*(1-nx/rr)**1.65)+round(2*(1-t))
                m.box((x,min(y,ny),z),(x,y+1,z),WHITE)
    for z in range(cz-rz,cz+rz+1):
        y=eave+height+round(2*(1-abs(z-cz)/rz))+1;m.set(cx,y,z,RIB)
    for z in (cz-rz,cz+rz):
        m.box((cx,eave+height,z),(cx,eave+height+4,z),'end_rod')


def open_hall(m,cx,cz,rx,rz,floor=3,eave=16,height=15):
    # A raised oval deck follows the canopy; no generic rectangular room shell.
    for x in range(cx-rx-2,cx+rx+3):
        for z in range(cz-rz-2,cz+rz+3):
            if ((x-cx)/(rx+2))**2+((z-cz)/(rz+2))**2<=1:
                m.box((x,1,z),(x,floor,z),'polished_diorite')
                m.set(x,floor,z,'birch_planks')
    for zz in (-rz+4,0,rz-4):
        rr=max(1,round(rx*math.sqrt(max(0,1-(zz/rz)**2))))
        for sign in (-1,1):branch(m,cx+sign*rr,cz+zz,floor,eave+2,3,axis='z')
        # Paired curved ribs run continuously from each springing point to the ridge.
        for sign in (-1,1):curve(m,((cx+sign*rr,eave+1,cz+zz),(cx+sign*rr//2,eave+height,cz+zz),(cx,eave+height+2,cz+zz)),WHITE)
    leaf_vault(m,cx,cz,rx,rz,eave,height)


def spire(m,cx,cz,r,bottom,top):
    # An open arcaded tower, with slender corner piers and petal-crowned silhouette.
    for x,z in ((cx-r,cz-r),(cx+r,cz-r),(cx-r,cz+r),(cx+r,cz+r)):
        branch(m,x,z,bottom,top,2,axis='x')
    for z in (cz-r,cz+r):arch(m,cx,z,top-9,2*r-1,0,8,WHITE,fill=None)
    for x in (cx-r,cx+r):arch(m,cz,x,top-9,2*r-1,0,8,WHITE,axis='z',fill=None)
    leaf_vault(m,cx,cz,r+2,r+2,top,12)


def rail(m,x0,z0,x1,z1,y):
    axis='x' if z0==z1 else 'z'
    m.box((x0,y+1,z0),(x1,y+1,z1),'quartz_slab')
    for p in range((x0 if axis=='x' else z0),(x1 if axis=='x' else z1)+1,4):
        x,z=(p,z0) if axis=='x' else (x0,p)
        m.set(x,y+1,z,RIB);m.set(x,y+2,z,'quartz_slab')


def council():
    m=elf(1,'白枝议庭 · 叶脉穹廊与双层露台',73,69,83,('议事','正式接见'))
    arrival(m,36)
    m.box((32,1,0),(40,1,78),WHITE)
    open_hall(m,36,49,17,23,floor=4,eave=22,height=19)
    steps(m,32,40,20,1,4,'quartz')
    # The stair lands on a deck tongue that joins the oval council floor.
    m.box((31,1,23),(41,4,32),'polished_diorite');m.box((31,4,23),(41,4,32),'birch_planks')
    spire(m,15,26,3,1,30);spire(m,57,26,3,1,38)
    # Twin lower garden loggias connect to the high hall through open side arches.
    for cx in (14,58):
        open_hall(m,cx,54,7,14,floor=2,eave=11,height=11)
        steps(m,cx-2,cx+2,38,1,2,'quartz')
    # Real branching columns carry the upper rear balcony and its cross ribs.
    m.box((24,12,61),(48,12,68),WHITE)
    for x in (25,36,47):branch(m,x,64,4,11,3)
    rail(m,27,61,48,61,12)
    steps(m,24,26,53,4,12,'quartz')
    m.box((24,13,61),(26,16,64),'air')
    for x in (28,39):table(m,x,5,47,5,'birch')
    work(m,'assembly',36,5,43,'开敞议事厅中央')
    work(m,'upper',36,13,63,'上层观礼与会谈露台')
    table(m,32,13,66,7,'birch')
    for cx in (16,56):
        disk(m,cx,13,1,6,WHITE);disk(m,cx,13,1,4,'water')
        tree(m,cx,72,17,4,'birch','birch_leaves')
    for x,z in ((8,14),(64,14),(28,72),(44,72)):
        m.set(x,2,z,'flowering_azalea')
    m.room('council','白枝穹廊',(25,5,34),(47,20,59),'沿叶脉承重拱肋展开的开放议事厅')
    m.room('balcony','后部观礼廊',(28,13,62),(46,18,67),'分叉柱托起的实体上层露台')
    m.meta.update(roof_min_y=24,floors=[{'name':'主庭与侧廊','y':4,'max_y':11},{'name':'观礼露台','y':12,'max_y':21}])
    m.meta['design_notes']+=['白石分叉柱沿细長叶脉顶展开；屋顶轮廓、柱距与地坪同为长椭圆组织。','双门塔、主穹廊、低侧廊与后部实用露台形成四级高差；花木仅作为庭园配景。']
    return m


BUILDERS={'EL-01-v01':council}


def pod(m,cx,cz,rx,rz,floor=2,eave=11,height=12,entry_steps=True):
    open_hall(m,cx,cz,rx,rz,floor,eave,height)
    cells={(x,z) for x in range(cx-rx,cx+rx+1) for z in range(cz-rz,cz+rz+1)
           if ((x-cx)/rx)**2+((z-cz)/rz)**2<=1}
    edge={p for p in cells if any((p[0]+dx,p[1]+dz) not in cells for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)))}
    for x,z in edge:
        limit=next((y for y in range(eave,eave+height+5) if m.blocks.get((x,y,z),('minecraft:air',))[0]!='minecraft:air'),eave)
        for y in range(floor+1,limit):m.set(x,y,z,WHITE if y==floor+1 or (x+z)%7==0 else 'light_blue_stained_glass')
    # Front threshold follows the narrow leaf tip; opened after all infill glazing.
    for z in range(cz-rz,cz-rz+3):arch(m,cx,z,floor+1,3,3,4,WHITE)
    if entry_steps:steps(m,cx-1,cx+1,cz-rz-2-(floor-1),1,floor,'quartz')
    return cells


def ring_floor(m,cx,cz,rx,rz,y,inner=0):
    for x in range(cx-rx,cx+rx+1):
        for z in range(cz-rz,cz+rz+1):
            if ((x-cx)/rx)**2+((z-cz)/rz)**2<=1 and (x-cx)**2+(z-cz)**2>=inner*inner:
                m.set(x,y,z,WHITE)


def sanctuary():
    m=elf(2,'月泉圣所 · 八瓣拱肋与露天圣池',65,62,69,('礼仪','纪念','水景观赏'))
    arrival(m,32);m.box((29,1,0),(35,1,34),WHITE)
    ring_floor(m,32,37,25,25,1)
    disk(m,32,37,2,11,WHITE);disk(m,32,37,3,10,WHITE);disk(m,32,37,3,8,'water')
    for k in range(8):
        a=k*math.pi/4;dx=round(19*math.cos(a));dz=round(19*math.sin(a))
        branch(m,32+dx,37+dz,1,22,4,axis='x' if abs(dx)<abs(dz) else 'z')
        curve(m,((32+dx,22,37+dz),(32+round(dx*.8),43,37+round(dz*.8)),(32,47,37)),WHITE)
        # Green petal-shaped keels project between the rib and the outer colonnade.
        for dy in range(11):
            t=dy/11;x=32+round(dx*(1-.28*t));z=37+round(dz*(1-.28*t))
            m.set(x,23+dy,z,LEAF)
    m.box((31,45,36),(33,47,38),'gold_block');m.set(32,48,37,'end_rod')
    for x,z in ((14,19),(50,19),(14,55),(50,55)):tree(m,x,z,16,3,'birch','azalea_leaves')
    work(m,'moon',32,2,24,'月泉前礼仪站位')
    m.room('moonwell','八瓣圣池庭',(18,2,23),(46,43,51),'八根分叉柱与汇聚拱肋围绕露天圣池')
    m.meta['roof_min_y']=49
    return m


def archive():
    m=elf(3,'悬廊藏卷馆 · 双叶书塔与跨庭阅览廊',71,63,69,('文献保管','阅读'))
    arrival(m,35);m.box((32,1,0),(38,1,63),WHITE)
    for cx in (20,50):
        pod(m,cx,37,11,22,floor=2,eave=24,height=18)
        # Occupied upper library decks are separate from the leaf roof space.
        ring_floor(m,cx,37,9,18,11)
        steps(m,cx-5,cx-3,24,2,11,'quartz')
        for y in (3,12):
            for z in (35,48):shelf(m,cx-4,y,z,7,'birch')
        work(m,f'books_{cx}',cx+3,12,42,'上层藏卷与阅览')
    m.box((27,11,38),(43,11,44),WHITE)
    for z in (38,44):rail(m,28,z,42,z,11)
    for x in (29,41):m.box((x,12,39),(x,15,43),'air')
    for x in (30,40):branch(m,x,41,1,10,3)
    table(m,32,12,41,7,'birch')
    for x in (18,48):steps(m,x,x+4,13,1,2,'quartz')
    m.room('bridge','跨庭阅览桥',(30,12,39),(40,17,43),'实体桥廊连接两侧藏卷塔')
    m.meta.update(roof_min_y=26,floors=[{'name':'下层藏卷','y':2,'max_y':10},{'name':'上层阅览桥','y':11,'max_y':23}])
    return m


def arts_hall():
    m=elf(4,'林间艺学堂 · 扇形观席与悬叶舞台',65,49,73,('表演','教学','集会'))
    arrival(m,32);m.box((29,1,0),(35,1,63),WHITE)
    open_hall(m,32,49,19,14,floor=4,eave=18,height=16)
    steps(m,29,35,30,1,4,'quartz');m.box((27,1,33),(37,4,39),'polished_diorite')
    for radius,y in ((15,2),(20,3),(25,4)):
        for k in range(13):
            a=math.pi+math.pi*k/12;x=32+round(radius*math.cos(a));z=42+round(radius*math.sin(a))
            if abs(x-32)>4:
                m.box((x-1,1,z),(x+1,y,z),WHITE);m.box((x-1,y+1,z),(x+1,y+1,z),'birch_stairs[facing=south]')
    for x in (9,55):tree(m,x,55,20,4,'birch','birch_leaves')
    for x in (23,41):m.set(x,5,55,'note_block')
    work(m,'stage',32,5,45,'讲演与表演舞台')
    m.room('stage','叶顶舞台',(20,5,40),(44,17,57),'扇形露天观席面向高台叶脉穹廊')
    m.meta.update(roof_min_y=20,floors=[{'name':'舞台与观席','y':1,'max_y':12}])
    return m


def home():
    m=elf(5,'叶顶小宅 · 玻璃花窗与起居露台',39,43,49,('家庭居住',),role='self_contained')
    arrival(m,19);m.box((17,1,0),(21,1,36),WHITE)
    pod(m,19,29,11,15,floor=2,eave=11,height=13)
    bed(m,'bed1',16,3,35,'white');bed(m,'bed2',23,3,35,'white')
    m.box((12,3,32),(26,7,32),'birch_planks');m.box((18,3,32),(20,5,32),'air')
    table(m,16,3,24,6,'birch');m.set(11,3,28,'smoker');m.set(12,3,28,'water_cauldron[level=3]')
    for x in (9,29):m.set(x,1,12,'moss_block');m.set(x,2,12,'flowering_azalea')
    work(m,'living',19,3,28,'起居与分隔寝居')
    m.room('home','花窗小宅',(12,3,20),(26,10,37),'玻璃叶壳内的起居、炊事与独立寝居')
    m.meta.update(roof_min_y=13,floors=[{'name':'起居层','y':2,'max_y':10}])
    return m


def shop():
    m=elf(6,'花廊商铺 · 分叉货亭与后部储间',47,39,49,('商品零售','货物暂存'),role='fill')
    arrival(m,23);m.box((20,1,0),(26,1,41),WHITE)
    open_hall(m,23,23,15,12,floor=2,eave=12,height=11)
    steps(m,21,25,8,1,2,'quartz')
    pod(m,23,37,9,8,floor=2,eave=9,height=9)
    for x in (14,27):table(m,x,3,24,6,'birch')
    shelf(m,18,3,40,9,'birch','barrel')
    for x in (11,34):
        m.set(x,1,12,'moss_block');m.set(x,2,12,'flowering_azalea')
    work(m,'sales',23,3,24,'开放花廊交易通道');work(m,'stock',23,3,36,'后部储货亭')
    m.room('sales','开敞交易廊',(12,3,18),(34,11,28),'双列柜台与独立后部储间')
    m.meta.update(roof_min_y=14,floors=[{'name':'交易与储货','y':2,'max_y':10}])
    return m


def inn():
    m=elf(7,'双层桥廊旅舍 · 高低客亭与分叉平台',71,59,75,('旅客住宿','餐饮'),role='self_contained')
    arrival(m,35);m.box((32,1,0),(38,1,68),WHITE)
    open_hall(m,35,25,14,15,floor=2,eave=13,height=14)
    steps(m,33,37,7,1,2,'quartz')
    for cx in (17,53):
        pod(m,cx,51,11,17,floor=9,eave=23,height=13,entry_steps=False)
        # Raise the guest floor on visible branching piers, clearing the solid lower podium.
        cells={(x,z) for x in range(cx-13,cx+14) for z in range(32,70) if ((x-cx)/13)**2+((z-51)/19)**2<=1}
        for x,z in cells:m.box((x,2,z),(x,8,z),'air')
        for x,z in ((cx-7,42),(cx+7,42),(cx-7,60),(cx+7,60)):branch(m,x,z,1,8,3)
        steps(m,cx-1,cx+1,26,1,9,'quartz')
        m.box((cx-2,9,34),(cx+2,9,37),WHITE)
        for z in (48,59):
            bed(m,f'bed_{cx}_{z}',cx-4,10,z,'white');bed(m,f'bed_b_{cx}_{z}',cx+4,10,z,'white')
        m.box((cx-8,10,53),(cx+8,15,53),'birch_planks');m.box((cx-1,10,53),(cx+1,13,53),'air')
        work(m,f'guest_{cx}',cx,10,44,'分隔客房通道')
    m.box((26,9,47),(44,9,51),WHITE)
    for z in (47,51):rail(m,27,z,43,z,9)
    for x in (27,28,42,43):m.box((x,10,48),(x,14,50),'air')
    for x in (30,40):branch(m,x,49,1,8,3)
    for x in (26,37):table(m,x,3,25,6,'birch')
    work(m,'lobby',35,3,20,'地面接待与餐廊');work(m,'bridge',35,10,49,'连接客亭的高廊')
    m.room('guest','高台客亭与桥廊',(10,10,38),(60,21,64),'实体台阶连接的八床旅舍')
    m.meta.update(roof_min_y=25,floors=[{'name':'接待与架空花园','y':2,'max_y':8},{'name':'客亭与桥廊','y':9,'max_y':22}])
    return m


BUILDERS.update({f'EL-{i:02d}-v01':f for i,f in ((2,sanctuary),(3,archive),(4,arts_hall),(5,home),(6,shop),(7,inn))})


def palace():
    m=elf(13,'双塔王庭 · 三级露台与白枝王座廊',85,78,87,('正式接见','礼仪','居住'))
    arrival(m,42);m.box((38,1,0),(46,1,82),WHITE)
    open_hall(m,42,53,20,24,floor=6,eave=31,height=23)
    steps(m,37,47,19,1,6,'quartz');m.box((35,1,24),(49,6,33),WHITE)
    for cx in (20,64):
        pod(m,cx,50,10,20,floor=3,eave=21,height=18)
        spire(m,cx,24,4,1,43 if cx==20 else 51)
    m.box((30,15,64),(54,15,71),WHITE)
    for x in (31,42,53):branch(m,x,67,6,14,4)
    steps(m,30,32,55,6,15,'quartz');rail(m,33,64,54,64,15)
    table(m,38,16,68,7,'birch');work(m,'upper',42,16,66,'王庭上层会谈廊')
    m.box((38,7,54),(46,8,60),WHITE);steps(m,40,44,52,6,8,'quartz')
    m.box((41,9,58),(43,12,59),WHITE);m.set(42,13,59,'gold_block')
    work(m,'audience',42,7,48,'前部接见通道')
    for cx in (17,67):bed(m,f'private_{cx}',cx,4,57,'white')
    for x,z in ((14,72),(70,72),(11,12),(73,12)):tree(m,x,z,20,4,'birch','birch_leaves')
    m.room('court','三级王庭',(28,7,35),(56,29,62),'宽外阶、高挑王座廊和上层会谈露台')
    m.meta.update(roof_min_y=33,floors=[{'name':'王庭与侧居','y':3,'max_y':14},{'name':'上层会谈廊','y':15,'max_y':29}])
    return m


def memorial():
    m=elf(14,'永歌纪念庭 · 环树双拱与月石碑',63,54,65,('纪念','礼仪'))
    arrival(m,31);m.box((28,1,0),(34,1,51),WHITE)
    ring_floor(m,31,35,23,23,1)
    for k in range(8):
        a=k*math.pi/4;x=31+round(20*math.cos(a));z=35+round(20*math.sin(a))
        branch(m,x,z,1,20,4)
    for z in (21,49):arch(m,31,z,12,27,0,16,WHITE,fill=None)
    for x in (17,45):arch(m,35,x,12,27,0,16,WHITE,axis='z',fill=None)
    tree(m,31,38,30,8,'birch','birch_leaves')
    for x,z in ((22,30),(40,30),(22,45),(40,45)):
        m.box((x-1,2,z),(x+1,5,z),WHITE);m.set(x,6,z,'quartz_stairs[facing=north]')
    work(m,'memorial',31,2,25,'环树纪念仪式站位');m.meta['roof_min_y']=43
    return m


def archery():
    m=elf(15,'弓卫训练庭 · 三道靶廊与高台观训席',63,43,71,('军事训练','驻守'))
    arrival(m,31);m.box((27,1,0),(35,1,64),WHITE)
    for x in (17,31,45):
        m.box((x-4,1,22),(x+4,1,60),'moss_block')
        branch(m,x-5,59,1,13,3);branch(m,x+5,59,1,13,3)
        arch(m,x,59,9,9,0,5,WHITE,fill=None)
        m.box((x-2,2,61),(x+2,5,61),'birch_planks');m.set(x,4,60,'target')
        work(m,f'range_{x}',x,2,30,'独立练弓站位')
    open_hall(m,31,16,22,9,floor=4,eave=13,height=10)
    steps(m,28,34,3,1,4,'quartz');m.box((27,4,6),(35,4,9),WHITE)
    for x in (17,39):table(m,x,5,16,6,'birch')
    work(m,'instructor',31,5,18,'高台观训与讲解席')
    m.room('training','三道练弓庭',(12,2,24),(50,12,62),'开敞训练通道与独立后靶墙')
    m.meta['roof_min_y']=15
    return m


def healing():
    m=elf(16,'灵木疗养庭 · 四叶寝亭与中央泉径',73,43,73,('医疗','休养','园艺养护'))
    arrival(m,36);m.box((33,1,0),(39,1,68),WHITE);m.box((8,1,35),(64,1,39),WHITE)
    for i,(cx,cz,rx,rz) in enumerate(((18,27,9,16),(54,27,9,16),(18,55,9,11),(54,55,9,11))):
        pod(m,cx,cz,rx,rz,floor=2,eave=11,height=12)
        for x in (cx-3,cx+3):bed(m,f'patient_{i}_{x}',x,3,cz+4,'white')
        work(m,f'care_{i}',cx,3,cz-2,'疗养寝亭照护位')
    for a,b,wall,stepx,face in ((24,31,26,32,'west'),(41,48,45,40,'east')):
        m.box((a,2,50),(b,2,52),'birch_planks')
        m.box((a,3,50),(b,7,52),'air')
        arch(m,51,wall,3,3,3,4,WHITE,axis='z',depth=2)
        m.box((stepx,2,50),(stepx,2,52),'quartz_stairs[facing='+face+']')
    disk(m,36,40,2,7,WHITE);disk(m,36,40,3,6,WHITE);disk(m,36,40,3,4,'water')
    for x,z in ((12,10),(60,10),(31,62),(41,62)):
        m.set(x,1,z,'moss_block');m.set(x,2,z,'flowering_azalea')
    m.room('garden','中央疗养泉庭',(27,2,23),(45,11,59),'四叶寝亭围成有通路的安静泉庭')
    m.meta['roof_min_y']=13
    return m


def weaving():
    m=elf(17,'织叶作坊 · 长翼织架与染线凉廊',55,42,57,('纺织','手工制作'),role='fill')
    arrival(m,27);m.box((25,1,0),(29,1,50),WHITE)
    pod(m,20,33,12,18,floor=2,eave=14,height=13)
    open_hall(m,42,33,6,17,floor=2,eave=10,height=9)
    steps(m,40,44,13,1,2,'quartz')
    for x,z in ((15,25),(24,25),(15,37),(24,37)):
        m.set(x,3,z,'loom');m.set(x,4,z,'white_wool');m.box((x-1,3,z+1),(x+1,6,z+1),'birch_fence')
    for z in (27,35,43):m.set(42,3,z,'water_cauldron[level=3]');m.set(44,3,z,'barrel')
    work(m,'weave',20,3,31,'主翼织造工位');work(m,'dye',40,3,31,'染线与清洗凉廊')
    m.room('weaving','长翼织架厅',(13,3,22),(27,12,43),'成组织架与独立染线侧廊')
    return m


def nectar():
    m=elf(18,'花露酿坊 · 花架前庭与双叶发酵室',61,43,59,('酿酒','食品制作'),role='fill')
    arrival(m,30);m.box((28,1,0),(32,1,53),WHITE)
    for cx in (18,43):
        pod(m,cx,37,10,17,floor=2,eave=12,height=12)
        for z in (31,42):m.box((cx-5,3,z),(cx-3,5,z+2),'barrel');m.box((cx+3,3,z),(cx+5,5,z+2),'barrel')
        work(m,f'ferment_{cx}',cx,3,36,'发酵与品鉴工作间')
    for x in (12,24,36,48):
        branch(m,x,12,1,10,4)
        m.box((x-3,10,9),(x+3,10,15),'flowering_azalea_leaves[persistent=true]')
    for x in (18,30,42):arch(m,x,12,7,9,0,5,WHITE,fill=None)
    m.room('pergola','花露前庭',(10,2,7),(50,12,18),'花架与发酵双室分离的生产前庭')
    return m


def fruit_store():
    m=elf(19,'林果货仓 · 叶脊高架与通风储层',49,47,59,('货物仓储','食品保管'),role='fill')
    arrival(m,24);m.box((22,1,0),(26,1,54),WHITE)
    pod(m,24,34,15,21,floor=3,eave=18,height=15)
    for y in (4,11):
        if y==11:ring_floor(m,24,34,12,16,10)
        for x in (16,29):
            for z in (31,41):m.box((x,y,z),(x+3,y+2,z+2),'barrel')
    steps(m,19,21,20,3,10,'quartz')
    work(m,'stock',24,4,36,'林果收纳主通道');work(m,'loft',24,11,36,'通风储藏上层')
    m.room('stock','双层通风货仓',(14,4,23),(34,16,46),'底层分列货架与实体上层储藏')
    m.meta.update(roof_min_y=20,floors=[{'name':'下层货仓','y':3,'max_y':9},{'name':'上层储藏','y':10,'max_y':17}])
    return m


def gardener():
    m=elf(20,'园艺工房 · 开瓣苗床与工具小亭',53,38,57,('园艺养护','苗木培育'),role='fill')
    arrival(m,26);m.box((24,1,0),(28,1,52),WHITE)
    pod(m,26,39,11,12,floor=2,eave=10,height=10)
    shelf(m,21,3,42,9,'birch','barrel');m.set(23,3,36,'crafting_table');m.set(29,3,36,'water_cauldron[level=3]')
    for cx in (13,39):
        branch(m,cx,21,1,12,4);leaf_vault(m,cx,21,8,12,12,8)
        for x in (cx-4,cx+3):
            for z in (15,21,27):m.set(x,1,z,'moss_block');m.set(x,2,z,'flowering_azalea')
    work(m,'tools',26,3,37,'园艺工具与盆栽工房')
    m.room('nursery','开瓣苗床',(5,2,10),(47,11,29),'两瓣独立雨棚覆盖的育苗空间')
    return m


def watch_home():
    m=elf(21,'林缘守望居 · 高枝寝亭与下层岗廊',47,57,57,('驻守','居住'),role='self_contained')
    arrival(m,23);m.box((21,1,0),(25,1,50),WHITE)
    pod(m,23,34,12,17,floor=12,eave=26,height=15,entry_steps=False)
    for x in range(9,38):
        for z in range(15,54):
            if ((x-23)/14)**2+((z-34)/19)**2<=1:m.box((x,2,z),(x,11,z),'air')
    for x,z in ((15,25),(31,25),(15,43),(31,43)):branch(m,x,z,1,11,4)
    steps(m,21,25,4,1,12,'quartz')
    m.box((20,12,15),(26,12,19),WHITE)
    bed(m,'guard1',19,13,41,'white');bed(m,'guard2',27,13,41,'white')
    table(m,19,13,29,7,'birch');work(m,'watch',23,13,23,'高枝守望与接待')
    table(m,17,2,33,4,'birch');m.set(28,2,36,'barrel')
    m.room('watch','高枝守望居',(15,13,24),(31,24,44),'实体长阶连接的寝居与守望亭')
    m.meta.update(roof_min_y=28,floors=[{'name':'下层岗廊','y':1,'max_y':10},{'name':'守望寝居','y':12,'max_y':25}])
    return m


BUILDERS.update({f'EL-{i:02d}-v01':f for i,f in ((13,palace),(14,memorial),(15,archery),(16,healing),(17,weaving),(18,nectar),(19,fruit_store),(20,gardener),(21,watch_home))})


def bridge():
    m=elf(8,'白枝细拱桥 · 双肋桥腹与花栏',31,34,57,('步行过桥',),role='structure',tags=('infrastructure',))
    arrival(m,15);m.box((0,1,16),(30,1,40),'water')
    for x in (10,20):
        arch(m,28,x,2,29,0,6,WHITE,axis='z',fill=None,rib=1)
        curve(m,((x,2,12),(x,15,28),(x,2,44)),RIB)
    m.box((10,8,12),(20,8,44),WHITE)
    steps(m,12,18,5,1,8,'quartz');steps(m,12,18,51,1,8,'quartz',south=False)
    for x in (10,20):
        for z in range(13,44,6):
            branch(m,x,z,8,15,2,axis='z')
        m.box((x,12,12),(x,12,44),'quartz_slab')
    for z in (18,38):arch(m,15,z,13,9,0,9,WHITE,fill=None)
    for x in (9,21):
        for z in (18,38):m.set(x,9,z,'moss_block');m.set(x,10,z,'flowering_azalea')
    work(m,'bridge',15,9,28,'白枝桥中央');m.meta.update(roof_min_y=26,floors=[{'name':'过桥通路','y':8,'max_y':23}])
    return m


def lamp():
    m=elf(9,'叶脉路灯 · 分叉枝托与垂瓣光盏',19,28,19,('道路照明',),role='structure',tags=('infrastructure',))
    arrival(m,9);branch(m,9,9,1,15,5)
    for x in (4,14):
        m.box((x,12,9),(x,14,9),'chain');m.set(x,11,9,'soul_lantern[hanging=true]')
        leaf_vault(m,x,9,2,4,16,5)
    m.meta['roof_min_y']=24
    return m


def ancient_tree():
    m=elf(10,'盘根古树 · 弯枝华盖与环根坐席',53,49,53,('树木观赏','休憩'),role='structure',tags=('landscape',))
    arrival(m,26);ring(m,26,28,1,17,17,WHITE,2)
    tree(m,25,28,31,10,'oak','azalea_leaves')
    for dx,dz in ((-10,0),(10,0),(0,-10),(0,10)):
        curve(m,((25+dx,2,28+dz),(25+dx//2,6,28+dz//2),(25,13,28)),'stripped_oak_log',1)
    for x,z in ((14,15),(35,15),(13,39),(36,39)):
        m.box((x,2,z),(x+2,2,z),'birch_stairs[facing=south]' if z<28 else 'birch_stairs[facing=north]')
    m.meta['roof_min_y']=43
    return m


def flowerbed():
    m=elf(11,'月牙花坛 · 镂拱围边与高低花簇',31,24,33,('花草观赏',),role='structure',tags=('landscape',))
    arrival(m,15)
    for x in range(4,27):
        for z in range(6,29):
            d=(x-15)**2+(z-17)**2;inner=(x-20)**2+(z-14)**2
            if d<=11**2 and inner>9**2:
                m.set(x,2,z,WHITE);m.set(x,3,z,'moss_block')
                if (x+2*z)%4==0:m.set(x,4,z,'allium' if z%2 else 'lily_of_the_valley')
    for x,z in ((8,10),(7,21),(18,26)):
        branch(m,x,z,1,9,3);arch(m,x,z,7,5,0,4,WHITE,fill=None)
        for dx in (-2,0,2):m.set(x+dx,10,z,'azalea_leaves[persistent=true]')
    m.meta['roof_min_y']=17
    return m


def spring_pool():
    m=elf(12,'阶泉水庭 · 三瓣水盆与曲肋泉亭',47,39,49,('水景观赏','休憩'),role='structure',tags=('landscape',))
    arrival(m,23)
    for cx,cz,y,r in ((23,32,2,12),(15,20,5,7),(31,20,8,6)):
        for yy in (y,y+1):disk(m,cx,cz,yy,r,WHITE)
        disk(m,cx,cz,y+1,r-2,'water')
        m.box((cx-1,2,cz-1),(cx+1,y,cz+1),RIB)
    for x in (9,37):branch(m,x,31,1,17,4)
    arch(m,23,31,15,25,0,12,WHITE,fill=None)
    m.meta['roof_min_y']=31
    return m


def gate():
    m=elf(22,'林门与步廊 · 双白枝塔及三瓣门拱',57,59,37,('出入通行',),role='structure',tags=('infrastructure',))
    arrival(m,28);m.box((24,1,0),(32,1,36),WHITE)
    for cx in (12,44):spire(m,cx,21,4,1,29 if cx==12 else 35)
    for z,rise in ((15,16),(21,21),(27,16)):
        for x in (19,37):branch(m,x,z,1,14,4)
        arch(m,28,z,12,17,0,rise,WHITE,fill=None)
    for x in (8,48):
        for z in (8,31):m.set(x,1,z,'moss_block');m.set(x,2,z,'flowering_azalea')
    work(m,'passage',28,2,21,'三瓣门拱下通路');m.meta['roof_min_y']=51
    return m


def quay():
    m=elf(23,'临水花埠 · 弧形栈道与叶顶候船亭',61,43,63,('水运接驳','候船'),role='structure',tags=('infrastructure',))
    arrival(m,30);m.box((0,1,32),(60,1,62),'water')
    open_hall(m,30,23,17,12,floor=2,eave=13,height=12)
    steps(m,28,32,8,1,2,'quartz')
    for z in range(33,59):
        shift=round(4*math.sin((z-33)/25*math.pi))
        for cx in (13+shift,47-shift):
            m.box((cx-2,2,z),(cx+2,2,z),WHITE)
            if z%6==0:
                for x in (cx-2,cx+2):m.set(x,3,z,'quartz_pillar')
    m.box((13,2,32),(47,2,35),WHITE)
    for x in (18,36):table(m,x,3,24,6,'birch')
    work(m,'waiting',30,3,28,'叶顶候船亭');work(m,'berth1',13,3,58,'左侧弧道泊位');work(m,'berth2',47,3,58,'右侧弧道泊位')
    m.meta.update(roof_min_y=15,floors=[{'name':'花埠','y':2,'max_y':12}])
    return m


def silver_willow():
    m=elf(24,'银冠垂枝树 · 分簇树冠与帘状枝叶',45,43,45,('树木观赏',),role='structure',tags=('landscape',))
    arrival(m,22);tree(m,22,25,28,8,'birch','birch_leaves')
    for dx,dz,drop in ((-10,0,12),(10,2,14),(-2,-10,10),(2,10,13),(-8,-7,8),(8,8,9)):
        curve(m,((22,22,25),(22+dx,30,25+dz),(22+dx,26,25+dz)),'birch_log')
        for y in range(26-drop,28):
            m.set(22+dx,y,25+dz,'birch_leaves[persistent=true]')
            if y%3:m.set(23+dx,y,25+dz,'birch_leaves[persistent=true]')
    m.meta['roof_min_y']=37
    return m


BUILDERS.update({f'EL-{i:02d}-v01':f for i,f in ((8,bridge),(9,lamp),(10,ancient_tree),(11,flowerbed),(12,spring_pool),(22,gate),(23,quay),(24,silver_willow))})
