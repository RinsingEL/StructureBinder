"""Caravan animal courts, irrigated trellises and trading shelters.

These are independent fixed footprints. Animal behaviour and grape farming are
not inferred from vanilla architectural representations or player-path checks.
"""
from .model import Model
from .components import bench,crate_stack,pendant
from .desert import room_shell,table
from .oasis_life import paved
from .samples import railing


PEN_PLANS=[
    ('路旁小兽栏',(29,18,29),'单兽栏与侧面值守小屋，动物由宽边廊进入，饮水和饲料分置。'),
    ('双栏护理内院',(39,18,37),'两座兽栏沿西侧排列，东侧分别设置值守室和饲料库，中央留宽护理院。'),
    ('长廊鞍具兽舍',(34,24,37),'长屋顶覆盖分栏与中央通道，侧接鞍具房，前端另留卸载等候区。'),
    ('转角换乘兽院',(41,18,32),'L 形地块避开外部街角，兽栏接南北通道与后部横向换乘通道，提供北、东两个宽口。'),
]
VINE_PLANS=[
    ('窄渠长葡萄棚',(15,13,35),'狭长棚架沿渠展开，两侧藤床之间留一条连续采收道。'),
    ('院墙短藤棚',(29,13,27),'短横棚依北侧院墙布置，南侧保留分拣、歇凉和家用采收庭院，从西侧进出。'),
    ('折巷转角葡萄棚',(37,13,32),'两段棚架沿 L 形地块展开，转角设采收台，外侧凹角保留街巷。'),
    ('双列采收葡萄园',(34,14,35),'两列长藤棚夹中央作业道，后段设独立分拣遮棚、货筐和取水处。'),
]
STALL_PLANS=[
    ('香料单摊',(16,13,17),'小幅遮棚包住一条香料柜台，顾客在前、摊主与收纳在后。'),
    ('夹道双摊',(28,14,22),'香料与布料两摊朝中央小巷开口，背后各有备货位，遮棚高低错开。'),
    ('墙边寄货棚',(22,15,29),'单边厚墙承托斜顶，前端交易、后段寄货，东侧保留手推货物的侧向开口。'),
    ('四面通风货亭',(33,18,29),'大跨度分段帐顶覆盖左右货堆，中央穿行，角部设登记和饮水歇脚。'),
]


def base(family,variant,plans,usage):
    name,size,description=plans[variant-1]
    m=Model(f'{family}-v{variant:02d}',name,size,family=family,civilization='沙海星象文明',role='fill',terrain={
        '选址':usage,'地块':description,'接地':'使用地坪 Y=1，脚底 Y=2；不自动削坡或补齐外部通路',
        '边界':'模板包含内部地坪和封边，未写入的凹角与外部格保持原环境'})
    source={'DS-02':'beast_yard','DS-F03':'vineyard','DS-F04':'trade_shelter'}[family]
    m.meta.update(source=f'tools/structure_studio/studio/oasis_yards.py:{source}({variant})',
        design_notes=[description],differences=[description],roof_min_y=6,
        floors=[dict(name='地坪、布置与通路',y=1,max_y=4)],
        preview_context=dict(kind='flat',land_surface_y=2,bed_y=-1,padding=3,surface='sand'))
    return m


def ground(m,*,corner=None):
    w,_,d=m.size
    cells={(x,z) for x in range(2,w-2) for z in range(3,d-2)}
    if corner:
        split_x,split_z=corner;cells={(x,z) for x,z in cells if x<=split_x or z>=split_z}
    paved(m,cells)


def entry(m,key,x,z,*,facing='north',width=3,animals=False):
    m.point(key,'entrance',(x,2,z),'动物与人员宽口' if animals else '人员与货物入口',facing=facing)
    m.meta['connections'].append(dict(kind='animal' if animals else 'pedestrian',pos=[x,1,z],direction=facing,
        clearance=[width,5 if animals else 3],note='作者预留几何接口；需另外核对外部地面及具体实体尺寸'))


def canvas(m,x0,z0,x1,z1,*,y=7,colors=('orange','white'),base_y=2):
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,base_y,z),(x,y-1,z),'stripped_acacia_log[axis=y]')
        m.box((x,y-1,z0),(x,y-1,z1),'stripped_acacia_log[axis=z]')
    for x in range(x0,x1+1):
        m.box((x,y,z0),(x,y,z1),colors[(x-x0)//2%len(colors)]+'_wool')


def fence_pen(m,key,x0,z0,x1,z1,*,gate_z):
    for x in (x0,x1):railing(m,(x,2,z0),(x,2,z1),'acacia','z')
    for z in (z0,z1):railing(m,(x0,2,z),(x1,2,z),'acacia','x')
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,2,z),(x,3,z),'stripped_acacia_log[axis=y]')
    m.box((x1,2,gate_z-1),(x1,3,gate_z+1),'air')
    for z in (gate_z-2,gate_z+2):m.box((x1,2,z),(x1,4,z),'stripped_acacia_log[axis=y]')
    for z in range(gate_z-1,gate_z+2):m.set(x1,2,z,'acacia_fence_gate[facing=east,open=true]')
    # Three gates are authored open; entity behaviour remains a game-side check.
    for x in range(x0+1,x1):
        for z in range(z0+1,z1):m.set(x,1,z,'podzol' if (x*19+z*31+x*z)%23<3 else 'coarse_dirt')
    m.box((x0+2,2,z1-3),(x0+3,3,z1-2),'hay_block[axis=y]')
    for z in (z0+2,z0+3):m.set(x1-2,2,z,'water_cauldron[level=3]')
    m.point(key+'_feed','work',(x0+3,2,z1-3),key+'饲料',approach=(x0+4,2,z1-3))
    m.point(key+'_water','work',(x1-2,2,z0+3),key+'饮水',approach=(x1-3,2,z0+3))
    m.point(key+'_gate','circulation',(x1,2,gate_z),key+'牵引宽口',look_at=[x0+3,3,gate_z])
    m.room(key,'遮阳兽栏 · '+key,(x0+1,2,z0+1),(x1,6,z1-1),'兽栏、饲料、饮水与牵引通道')


def keeper(m,x0,z0,x1,z1,*,side='west'):
    room_shell(m,x0,z0,x1,z1)
    dx=x0 if side=='west' else (x0+x1)//2;dz=(z0+z1)//2 if side=='west' else z0
    m.door(dx,2,dz,facing=side)
    m.bed(x1-2,2,z1-2,color='orange',facing='north')
    m.point('keeper_bed','bed',(x1-2,2,z1-2),'值守卧床',approach=(x1-3,2,z1-2))
    m.set(x0+2,2,z0+2,'lectern[facing=south]');m.point('register','work',(x0+2,2,z0+2),'换乘登记',approach=(x0+2,2,z0+3))
    m.set(x1-1,2,z0+1,'barrel[facing=west]');m.set(x1-1,2,z1-1,'water_cauldron[level=3]')
    m.set(x1-1,4,z0+2,'tripwire_hook[facing=west]')
    pendant(m,x0+3,6,z0+2,8)
    m.room('keeper','换乘值守室',(x0+1,2,z0+1),(x1-1,6,z1-1),'登记、夜间值守、个人物品与取水')


def tack_room(m,x0,z0,x1,z1):
    room_shell(m,x0,z0,x1,z1);m.door(x0,2,z0+3,facing='west')
    table(m,x0+2,2,z0+2,x1-x0-3,'brown')
    for z in range(z0+5,z1-1,3):
        m.set(x1-1,2,z,'barrel[facing=west]')
        m.set(x1-1,4,z,'tripwire_hook[facing=west]')
    m.set(x0+2,2,z1-2,'crafting_table');m.set(x0+4,2,z1-2,'grindstone[face=floor,facing=north]')
    m.point('tack','work',(x0+2,2,z1-2),'鞍具整理与维修',approach=(x0+2,2,z1-3))
    m.room('tack','鞍具储藏与护理房',(x0+1,2,z0+1),(x1-1,6,z1-1),'挂具、储物、维修工作台与整理桌')


def beast_yard(variant):
    m=base('DS-02',variant,PEN_PLANS,'依托商路补给点和可靠饮水，保持动物活动与人货搬运空间')
    ground(m,corner=(24,14) if variant==4 else None)
    m.meta['offline_limits'].append('兽栏不含活体动物；入口为宽口，动物高度、回转、牵引和围栏行为须另行验证。')
    if variant==1:
        fence_pen(m,'单栏',3,5,17,23,gate_z=15)
        canvas(m,4,6,16,11)
        keeper(m,19,17,25,25)
        m.set(22,2,10,'grindstone[face=floor,facing=north]')
        m.point('groom','work',(22,2,10),'露天护理架',approach=(22,2,9))
        m.room('handling','牵引与等候边廊',(18,2,4),(25,6,16),'从街道到栏口的宽通道与护理')
        entry(m,'entry',21,4,animals=True)
    elif variant==2:
        for key,z0,z1,gz in [('前栏',6,18,14),('后栏',20,32,28)]:
            fence_pen(m,key,4,z0,14,z1,gate_z=gz);canvas(m,5,z0+1,13,z0+5)
        keeper(m,26,6,34,16)
        room_shell(m,26,22,34,32);m.door(26,2,26,facing='west')
        m.box((28,2,24),(30,4,25),'hay_block[axis=y]');crate_stack(m,31,2,28,2,3,2)
        m.point('feed_store','work',(28,2,25),'集中饲料库',approach=(28,2,26))
        m.room('feed_store','干燥饲料库',(27,2,23),(33,6,31),'草料、精饲料与搬运空地')
        canvas(m,17,23,23,31,y=8)
        m.set(22,2,29,'grindstone[face=floor,facing=west]');m.point('care','work',(22,2,29),'内院护理',approach=(21,2,29))
        m.set(20,2,18,'water_cauldron[level=3]')
        m.room('court','护理内院',(16,2,5),(24,7,32),'牵引、饮水与遮阳护理')
        entry(m,'entry',20,4,animals=True,width=5)
    elif variant==3:
        for key,z0,z1,gz in [('前厩',7,17,12),('后厩',19,29,24)]:fence_pen(m,key,4,z0,10,z1,gate_z=gz)
        for x in (3,20):
            for z in (5,17,31):m.box((x,2,z),(x,7,z),'stripped_acacia_log[axis=y]')
            m.box((x,7,5),(x,7,31),'stripped_acacia_log[axis=z]')
        for x in range(2,22):
            edge=min(x-2,21-x);y=7+edge//3
            block='acacia_planks' if edge%3 or edge==9 else f'acacia_stairs[facing={"east" if x<12 else "west"}]'
            m.box((x,y,4),(x,y,32),block)
            if y>7:
                for z in (5,31):m.box((x,7,z),(x,y-1,z),'smooth_sandstone')
        tack_room(m,22,21,30,32)
        canvas(m,23,6,29,14,y=7)
        bench(m,24,2,12,3,'north','acacia')
        m.set(18,2,23,'water_cauldron[level=3]');m.set(18,2,16,'grindstone[face=floor,facing=west]')
        m.point('care','work',(18,2,16),'廊内护理架',approach=(17,2,16))
        m.room('aisle','兽舍中央通廊',(11,2,5),(19,6,31),'牵引、喂养、护理与后房出入')
        m.room('wait','遮阳等候区',(23,2,6),(29,6,14),'卸鞍后临时等候与人员歇脚')
        entry(m,'entry',14,4,animals=True,width=5);m.meta['roof_min_y']=7
    else:
        fence_pen(m,'转角栏',4,6,17,24,gate_z=18);canvas(m,5,7,16,12,y=7)
        keeper(m,25,15,34,23,side='north')
        m.set(32,2,27,'grindstone[face=floor,facing=north]');m.point('care','work',(32,2,27),'出发前护理',approach=(32,2,26))
        m.set(36,2,17,'barrel[facing=west]');m.set(36,2,18,'water_cauldron[level=3]')
        m.room('transfer','转角牵引通道',(18,2,4),(37,6,28),'北侧进入、东侧离开的内部转角通道')
        entry(m,'north_entry',21,4,animals=True);entry(m,'east_entry',38,26,facing='east',animals=True,width=5)
    return m


def trellis(m,key,x0,z0,x1,z1,*,axis='z'):
    """Five-wide arbour: soil under two rows, central aisle, outer water rills."""
    start,end=(z0,z1) if axis=='z' else (x0,x1)
    cross0,cross1=(x0,x1) if axis=='z' else (z0,z1)
    assert cross1-cross0==4
    point=lambda cross,along:(cross,along) if axis=='z' else (along,cross)
    for c in (cross0,cross1):
        for t in range(start,end+1):
            x,z=point(c,t);m.set(x,1,z,'rooted_dirt')
            wx,wz=point(c-1 if c==cross0 else c+1,t);m.set(wx,1,wz,'water[level=0]')
            m.set(x,5,z,f'stripped_acacia_log[axis={axis}]')
            for cc in (c-1,c,c+1):
                lx,lz=point(cc,t)
                if (t+cc)%7:m.set(lx,6,lz,'oak_leaves[persistent=true,distance=1]')
        stations=list(range(start,end+1,6))
        if end not in stations:stations.append(end)
        for t in stations:
            x,z=point(c,t);m.box((x,2,z),(x,4,z),'acacia_fence')
            lx,lz=point(c-1 if c==cross0 else c+1,t)
            m.set(lx,5,lz,'oak_leaves[persistent=true,distance=1]')
        for t in range(start+3,end,6):
            x,z=point(c,t);m.set(x,4,z,'amethyst_cluster[facing=down,waterlogged=false]')
    for t in range(start,end+1,6):
        xa,za=point(cross0,t);xb,zb=point(cross1,t)
        m.box((xa,5,za),(xb,5,zb),f'stripped_acacia_log[axis={"x" if axis=="z" else "z"}]')
    cx,cz=point(cross0+2,start+2);px,pz=point(cross0,start+3)
    m.point(key+'_harvest','work',(px,4,pz),'棚下采收 · '+key,approach=(cx,2,cz),look_at=[px+0.5,4,pz+0.5])
    m.room(key,'葡萄棚 · '+key,(x0-1,2,z0-1),(x1+1,6,z1+1),'土床、备水渠、棚架与采收道；原版方块视觉表达')


def vineyard(variant):
    m=base('DS-F03',variant,VINE_PLANS,'只用于整合包设定允许种植葡萄、具可靠灌溉和适宜土壤的绿洲')
    ground(m,corner=(13,17) if variant==3 else None)
    m.meta['terrain']['作物表达']='原版木架、常驻树叶和倒挂紫晶簇作为葡萄果串外观；非可收获作物，不声明生长或产量'
    m.meta['offline_limits'].append('紫色方块果串是视觉表达；原版藤架不会自动长葡萄，须按整合包作物另行替换或接入。')
    if variant==1:
        trellis(m,'长棚',5,6,9,27)
        m.set(11,2,30,'barrel[facing=north]');m.set(11,2,28,'composter[level=5]')
        m.point('tools','work',(11,2,30),'采收筐',approach=(10,2,30))
        entry(m,'entry',7,3)
    elif variant==2:
        for x in (3,25):m.box((x,2,4),(x,3,23),'smooth_sandstone')
        for z in (4,23):m.box((3,2,z),(25,3,z),'smooth_sandstone')
        m.box((3,4,4),(25,5,4),'cut_sandstone')
        m.box((3,2,14),(3,3,16),'air')
        trellis(m,'墙棚',6,7,22,11,axis='x')
        table(m,15,2,19,6,'purple');bench(m,6,2,19,5,'north','acacia')
        m.set(23,2,19,'barrel[facing=west]');m.set(23,2,21,'water_cauldron[level=3]')
        m.point('sort','work',(17,2,19),'家用分拣桌',approach=(17,2,18))
        m.room('court','采收小院',(4,2,13),(24,6,22),'分拣、歇凉与采收筐')
        entry(m,'entry',3,15,facing='west')
    elif variant==3:
        trellis(m,'纵棚',5,6,9,24);trellis(m,'横棚',16,20,29,24,axis='x')
        table(m,11,2,27,4,'purple');m.set(27,2,28,'barrel[facing=north]')
        m.point('sort','work',(12,2,27),'转角采收台',approach=(12,2,26))
        m.room('corner','转角作业位',(10,2,25),(30,6,28),'连接两段棚架的转运与分拣')
        entry(m,'north_entry',7,3);entry(m,'east_entry',33,22,facing='east')
    else:
        trellis(m,'西棚',5,7,9,27);trellis(m,'东棚',23,7,27,27)
        canvas(m,13,20,19,29,y=8,colors=('purple','white'))
        table(m,14,2,24,4,'purple');crate_stack(m,14,2,27,4,2,1)
        m.set(16,2,9,'water_cauldron[level=3]')
        m.point('sort','work',(15,2,24),'集中分拣台',approach=(15,2,23))
        m.point('water','work',(16,2,9),'作业道储水',approach=(16,2,8))
        m.room('packing','采收分拣棚',(13,2,20),(19,7,29),'分拣、周转筐与装运')
        entry(m,'entry',16,4,width=5)
    return m


def trade_shelter(variant):
    m=base('DS-F04',variant,STALL_PLANS,'依托商路与聚落交易需求，货物需能从街道搬入，棚周保留通风和行走')
    ground(m)
    if variant==1:
        canvas(m,3,5,12,13,y=7)
        for x in range(4,11):m.set(x,2,8,'barrel[facing=up]');m.set(x,3,8,('orange','yellow','brown')[x%3]+'_carpet')
        for x in (4,10):m.set(x,3,8,'flower_pot')
        crate_stack(m,4,2,11,3,2,2);m.set(10,2,12,'lectern[facing=north]')
        m.point('sell','work',(7,2,8),'香料柜台',approach=(7,2,9))
        m.point('buy','work',(7,2,8),'柜台顾客位',approach=(7,2,7))
        m.point('ledger','work',(10,2,12),'收货账记',approach=(10,2,11))
        m.room('stall','香料售卖与备货',(3,2,5),(12,6,13),'前柜台、后收纳与侧向出入')
        entry(m,'entry',7,3)
    elif variant==2:
        canvas(m,3,5,10,17,y=7);canvas(m,17,5,24,17,y=8,colors=('cyan','white'))
        for z in range(7,15):
            m.set(9,2,z,'barrel[facing=up]');m.set(9,3,z,('yellow','orange')[z%2]+'_carpet')
            m.set(18,2,z,'acacia_planks');m.set(18,3,z,('cyan','blue','purple')[z%3]+'_wool')
        crate_stack(m,4,2,14,2,2,2);crate_stack(m,22,2,14,2,2,2)
        for key,x,z,ax,label in [('spice',9,10,8,'香料摊主'),('cloth',18,10,19,'布匹摊主'),('spice_buy',9,10,10,'香料顾客'),('cloth_buy',18,10,17,'布匹顾客')]:
            m.point(key,'work',(x,2,z),label,approach=(ax,2,z))
        m.room('spice','香料摊',(3,2,5),(10,6,17),'香料桶、柜台与后备货')
        m.room('cloth','布匹摊',(17,2,5),(24,7,17),'布料展示、柜台与后备货')
        m.room('lane','交易小巷',(11,2,4),(16,6,18),'两侧柜台前的公共通道')
        entry(m,'entry',13,3,width=5)
    elif variant==3:
        m.box((4,2,5),(4,7,24),'cut_sandstone')
        for z in (5,14,24):m.box((14,2,z),(14,4,z),'stripped_acacia_log[axis=y]')
        for x in range(4,15):
            y=7-(x-4)//4
            m.box((x,y,5),(x,y,24),'waxed_cut_copper')
            if x%4==3:m.box((x,y,5),(x,y,24),'waxed_cut_copper_stairs[facing=west]')
        for z in (5,14,24):m.box((5,4,z),(14,4,z),'stripped_acacia_log[axis=x]')
        table(m,7,2,7,5,'orange');m.set(9,2,7,'lectern[facing=south]');m.set(9,3,7,'air')
        crate_stack(m,6,2,18,3,5,3)
        m.box((11,2,20),(12,3,22),'brown_wool')
        for z in (9,12,21):m.set(5,4,z,'tripwire_hook[facing=east]')
        bench(m,16,2,22,3,'north','acacia')
        m.point('trade','work',(9,2,7),'寄货登记桌',approach=(9,2,8))
        m.point('stock','work',(8,2,19),'后段寄存箱',approach=(9,2,19))
        m.room('counter','棚前接货',(5,2,6),(13,5,15),'寄货登记、捆扎挂具与搬运通道')
        m.room('stores','后段货仓',(5,2,16),(13,5,23),'分区货箱与布包')
        entry(m,'entry',9,3);entry(m,'side',18,14,facing='east')
        m.meta['roof_min_y']=5
    else:
        for x in (4,15,28):
            beam_y=9 if x==15 else 7
            for z in (6,22):m.box((x,2,z),(x,beam_y,z),'stripped_acacia_log[axis=y]')
            m.box((x,beam_y,6),(x,beam_y,22),'stripped_acacia_log[axis=z]')
        for x in range(3,30):
            y=8+min((x-3)//4,(29-x)//4,2)
            for z in range(5,24):m.set(x,y,z,('orange','white')[(z-5)//3%2]+'_wool')
        for z in (6,14,22):
            m.box((4,7,z),(28,7,z),'stripped_acacia_log[axis=x]')
            for x in range(7,26,4):
                top=7+min((x-3)//4,(29-x)//4,2)
                if top>=8:m.box((x,8,z),(x,top,z),'stripped_acacia_log[axis=y]')
        crate_stack(m,6,2,8,4,4,3);crate_stack(m,6,2,16,4,5,3)
        crate_stack(m,23,2,16,4,3,2)
        table(m,21,2,10,6,'orange');m.set(22,2,10,'lectern[facing=north]');m.set(22,3,10,'air')
        bench(m,20,2,21,4,'north','acacia');m.set(27,2,21,'water_cauldron[level=3]')
        m.point('register','work',(22,2,10),'货亭登记',approach=(22,2,9))
        m.point('cargo','work',(9,2,10),'周转货堆',approach=(10,2,10))
        m.point('water','work',(27,2,21),'搬运工饮水',approach=(27,2,20))
        m.point('through','circulation',(16,2,17),'中央货运通道',look_at=[16,4,8])
        m.room('stock','左侧周转货区',(5,2,7),(11,6,21),'分类码放货箱与搬运边道')
        m.room('service','右侧登记与歇脚',(20,2,7),(28,6,22),'账记、货箱、休息和饮水')
        m.room('aisle','中央穿行通道',(12,2,5),(19,6,24),'南北贯通的人货通道')
        entry(m,'north_entry',16,3,width=5);entry(m,'south_entry',16,25,facing='south',width=5)
        m.meta['roof_min_y']=7
    return m
