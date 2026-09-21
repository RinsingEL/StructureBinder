"""Sand-sea civic buildings with independently authored plans and uses."""
import math

from .model import Model
from .components import shell,window,arch_front,bench,pendant,shelf,crate_stack
from .samples import railing


def base(number,name,size,terrain,notes,*,ground=1,roof=8,role="key"):
    m=Model(f"DS-{number:02d}-v01",name,size,family=f"DS-{number:02d}",civilization="沙海星象文明",role=role,terrain=terrain)
    m.meta.update(source=f"tools/structure_studio/studio/desert.py:desert({number})",roof_min_y=roof,
                  floors=[dict(name="使用空间",y=ground,max_y=ground+5)],design_notes=notes,differences=notes,
                  preview_context=dict(kind="flat",land_surface_y=ground,bed_y=ground-3,padding=4,surface="sand"))
    return m


def pad(m,x0,z0,x1,z1,f=1):
    m.box((x0,0,z0),(x1,f,z1),"cut_sandstone")
    m.box((x0,f+1,z0),(x1,min(m.size[1]-1,f+18),z1),"air")
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):m.set(x,f,z,"smooth_sandstone" if (x+z)%7 else "sandstone")


def room_shell(m,x0,z0,x1,z1,*,f=1,wall="smooth_sandstone",roof=True):
    shell(m,(x0,f,z0),(x1,f+6,z1),wall,"smooth_sandstone")
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,f+1,z),(x,f+7,z),"cut_sandstone")
    if roof:
        m.box((x0,f+7,z0),(x1,f+7,z1),"smooth_sandstone")
        for z in (z0,z1):m.box((x0,f+8,z),(x1,f+8,z),"sandstone_wall")
        for x in (x0,x1):m.box((x,f+8,z0),(x,f+8,z1),"sandstone_wall")
    for z in (z0,z1):
        m.box((x0,f+5,z),(x1,f+5,z),"blue_terracotta")
    window(m,(x0+2,f+2,z1),(min(x1-2,x0+3),f+3,z1),color="cyan_stained_glass")


def dome(m,cx,cz,y,r=5,material="blue_terracotta"):
    radii=[r,r,max(1,r-1),max(1,r-2),1]
    for dy,rad in enumerate(radii):
        for x in range(cx-rad,cx+rad+1):
            for z in range(cz-rad,cz+rad+1):
                dist=(x-cx)**2+(z-cz)**2
                if dist<=rad*rad:m.set(x,y+dy,z,material if dist>(rad-1.4)**2 or dy==4 else "air")
    m.set(cx,y+5,cz,"gold_block");m.set(cx,y+6,cz,"lightning_rod[facing=up]")


def windtower(m,x,z,y=9):
    m.box((x,y,z),(x+3,y+5,z+3),"cut_sandstone")
    m.box((x+1,y,z+1),(x+2,y+4,z+2),"air")
    for zz in (z,z+3):m.box((x+1,y+3,zz),(x+2,y+4,zz),"air")
    for xx in (x,x+3):m.box((xx,y+3,z+1),(xx,y+4,z+2),"air")
    m.box((x-1,y+6,z-1),(x+4,y+6,z+4),"smooth_sandstone_slab[type=bottom]")


def front(m,x,z,f=1,*,width=3,key="front",name="主入口"):
    for px in range(x-width//2,x+width//2+1):
        m.set(px,f-1,z-2,"sandstone_stairs[facing=south]")
        m.set(px,f,z-1,"smooth_sandstone")
    m.point(key,"entrance",(x,f+1,z-1),name,facing="north")
    m.meta["connections"].append(dict(kind="pedestrian",pos=[x,f,z-2],direction="north",clearance=[width,4],note="外部街面衔接台阶，不自动随地形移动入口"))


def table(m,x,y,z,w=3,color="blue"):
    m.box((x,y,z),(x+w-1,y,z),"birch_slab[type=top]")
    for px in range(x,x+w):m.set(px,y+1,z,f"{color}_carpet")


def bedroom(m,key,x0,z0,x1,z1,*,f=1,color="cyan",two=True):
    middle=(x0+x1)//2
    m.box((middle-1,f,z0+4),(middle+1,f,z1-2),"cyan_terracotta" if two else "brown_terracotta")
    m.bed(x0+2,f+1,z1-2,color=color,facing="north")
    m.point(key+"_bed1","bed",(x0+2,f+1,z1-2),key+"床位",approach=(x0+3,f+1,z1-2))
    if two:
        m.bed(x1-2,f+1,z1-2,color=color,facing="north")
        m.point(key+"_bed2","bed",(x1-2,f+1,z1-2),key+"第二床位",approach=(x1-3,f+1,z1-2))
    m.set(x0+1,f+1,z0+1,"barrel[facing=east]")
    m.set(x1-1,f+1,z0+1,"water_cauldron[level=3]")
    m.set((x0+x1)//2,f+1,z0+2,"birch_slab[type=top]")
    m.set((x0+x1)//2,f+2,z0+2,"lantern")
    m.box((x0+2,f+1,z0+1),(x0+4,f+2,z0+1),"birch_planks")


def caravanserai():
    m=base(1,"月井商队大驿站",(53,23,51),{"选址":"有可靠水源的商路补给节点，不因沙地外观自动成立",
        "地形":"约 47×43 格可整备平缓地块，北侧保留旅人与货运动线","接地":"地坪 Y=1、室内与院内脚底 Y=2",
        "组合":"自身含客房、饮食、存货与庭院；动物换乘仍由外部兽栏承担"},
        ["四边厚墙围合月井内庭，北侧拱门进入；四间双床客房与餐饮后勤各自分区。","蓝色穹顶、赭色门楼与分段遮阳廊区别于铁路街区。"])
    pad(m,3,5,49,47)
    # Independent perimeter rooms, with shaded circulation outside their doors.
    for spec in ((4,6,20,15),(31,6,48,15),(4,17,14,27),(4,29,14,38),(38,17,48,27),(38,29,48,38),(4,40,16,46),(18,40,32,46),(34,40,48,46)):
        room_shell(m,*spec)
    room_shell(m,21,6,30,15,wall="orange_terracotta")
    arch_front(m,22,2,6,8,7);arch_front(m,22,2,15,8,7)
    m.box((23,2,7),(28,6,14),"air")
    front(m,26,6,width=5)
    m.door(12,2,15,facing="south");m.door(40,2,15,facing="south")
    for key,a,b,c,d,side in (("west_a",4,17,14,27,"west"),("west_b",4,29,14,38,"west"),("east_a",38,17,48,27,"east"),("east_b",38,29,48,38,"east")):
        m.door(c if side=="west" else a,2,b+2,facing="east" if side=="west" else "west")
        bedroom(m,key,a,b,c,d)
        m.room(key,"双床客房 · "+key,(a+1,2,b+1),(c-1,6,d-1),"睡眠、行李与取水")
    for x in (10,25,41):m.door(x,2,40,facing="north")
    # Colonnades deliberately keep a broad inner court, rather than filling it.
    for x in (16,36):
        for z in (17,23,29,35,39):m.box((x,2,z),(x,6,z),"cut_sandstone")
        m.box((x-1,7,16),(x+1,7,39),"smooth_sandstone_slab[type=top]")
    for x in range(18,35,4):
        for z in (16,39):m.box((x,2,z),(x,6,z),"cut_sandstone")
    m.box((17,7,15),(35,7,17),"smooth_sandstone_slab[type=top]")
    m.box((17,7,38),(35,7,40),"smooth_sandstone_slab[type=top]")
    m.box((23,1,24),(29,1,30),"blue_terracotta");m.box((24,1,25),(28,1,29),"water[level=0]")
    for x in (23,29):m.box((x,2,24),(x,2,30),"smooth_sandstone_slab[type=bottom]")
    for z in (24,30):m.box((24,2,z),(28,2,z),"smooth_sandstone_slab[type=bottom]")
    for x,z in ((20,21),(32,21),(20,33),(32,33)):
        m.set(x,2,z,"moss_block");m.set(x,3,z,"flowering_azalea")
    m.box((6,2,10),(12,2,10),"dark_oak_planks");m.set(7,2,10,"lectern[facing=south]")
    crate_stack(m,16,2,8,3,4,3)
    for z in (9,12):
        table(m,34,2,z,5,"orange");bench(m,34,2,z+2,5,"north","birch")
    m.set(6,2,44,"water_cauldron[level=3]");m.set(9,2,44,"water_cauldron[level=3]")
    m.box((12,2,42),(12,3,44),"barrel[facing=west]")
    for x in (21,23):m.set(x,2,45,"smoker[facing=north,lit=false]")
    m.box((21,3,45),(23,5,45),"sandstone");m.box((22,6,45),(22,13,45),"sandstone")
    table(m,26,2,43,4,"white");m.set(30,2,45,"water_cauldron[level=3]")
    for x in (36,40,44):crate_stack(m,x,2,43,2,2,2)
    for key,pos,approach,name in (("register",(7,2,10),(7,2,11),"旅人登记"),("water",(23,2,27),(22,2,27),"月井取水"),("kitchen",(21,2,45),(21,2,44),"公共厨房"),("wash",(9,2,44),(9,2,43),"洗漱取水"),("freight",(40,2,43),(40,2,42),"寄存货物")):
        m.point(key,"work",pos,name,approach=approach)
    for key,name,a,b,purpose in (("reception","登记与寄物",(5,2,7),(19,6,14),"旅人登记和货物接收"),("dining","公共餐厅",(32,2,7),(47,6,14),"餐桌、候坐与饮食"),("washroom","洗漱间",(5,2,41),(15,6,45),"取水、盥洗与用品"),("kitchen","公共厨房",(19,2,41),(31,6,45),"备餐、炉灶和用水"),("storage","货物寄存库",(35,2,41),(47,6,45),"货物储藏"),("court","月井内庭",(17,2,18),(35,6,37),"遮阳、饮水与集散")):
        m.room(key,name,a,b,purpose)
    dome(m,26,10,9,5);windtower(m,5,7);windtower(m,43,41)
    for x,z in ((10,12),(42,12),(25,43),(40,43),(16,26),(36,26)):pendant(m,x,6,z,8)
    return m


def cistern():
    m=base(3,"蓝柱地下蓄水厅",(36,25,40),{"选址":"可靠集水或引水系统的地下储水节点，需足够岩土覆深与防渗条件",
        "埋置":"模型 Y=14 对应地表脚底；主体底部 Y=0，预留约 14 格地下空间",
        "入口":"北侧值守门廊接下行楼梯，检修平台脚底 Y=3","水":"蓄水池为封闭原版水体，补水、排水与容量逻辑尚未接入"},
        ["地上小门房连接十一格下降楼梯；地下柱廊围绕蓄水池，检修通道和记录区在干侧。"],ground=14,roof=13)
    m.box((3,0,9),(32,2,36),"stone_bricks")
    shell(m,(3,2,9),(32,12,36),"sandstone","stone_bricks",ceiling="sandstone")
    m.box((9,1,17),(21,2,32),"water[level=0]")
    for x in (8,22):m.box((x,3,16),(x,3,33),"smooth_sandstone_slab[type=bottom]")
    for z in (16,33):m.box((9,3,z),(21,3,z),"smooth_sandstone_slab[type=bottom]")
    for x in (7,15,23):
        for z in (15,24,33):
            m.box((x,1,z),(x,10,z),"cut_sandstone");m.set(x,4,z,"blue_terracotta");m.set(x,10,z,"chiseled_sandstone")
    for z in (15,24,33):m.box((7,11,z),(23,11,z),"smooth_sandstone")
    room_shell(m,22,2,31,9,f=13)
    m.box((24,14,2),(28,17,2),"air");m.box((24,14,9),(28,17,9),"air")
    front(m,26,2,f=13,width=5)
    # Descend along the dry east side; clear headroom through the near roof.
    for i in range(11):
        y=13-i;z=10+i
        m.box((25,y+1,z),(27,min(21,y+4),z),"air")
        for x in (25,26,27):
            m.box((x,3,z),(x,y,z),"sandstone")
            m.set(x,y,z,"sandstone_stairs[facing=north]")
    m.box((25,3,21),(27,5,22),"air")
    m.set(29,3,29,"lectern[facing=west]");m.set(29,3,32,"barrel[facing=west]")
    m.set(30,3,25,"grindstone[face=floor,facing=west]")
    for y in (4,6,8,10):m.set(32,y,29,"blue_terracotta")
    m.set(22,3,20,"smooth_sandstone")
    m.set(22,4,20,"lever[face=floor,facing=east,powered=false]")
    m.point("down","circulation",(26,3,22),"地下楼梯出口",look_at=[15,5,25])
    m.point("gauge","work",(22,4,20),"取水与水位记录位置",approach=(23,3,20))
    m.point("register","work",(29,3,29),"蓄水管理记录",approach=(28,3,29))
    m.point("maintenance","work",(30,3,25),"检修工具",approach=(29,3,25))
    m.room("vault","地下柱廊与蓄水池",(4,3,10),(31,11,35),"有支撑顶板、封闭蓄水和周边干侧检修")
    m.room("records","管理与检修角",(24,3,24),(31,7,35),"水位记录和维修工具")
    m.room("gate","地上门房",(23,14,3),(30,18,8),"遮阳入口与地下登临")
    m.meta["floors"]=[dict(name="地上入口",y=13,max_y=19),dict(name="地下蓄水与检修",y=2,max_y=10)]
    for x,z in ((7,19),(23,19),(7,28),(23,28)):pendant(m,x,10,z,13)
    m.set(29,14,5,"barrel[facing=west]");m.set(29,15,5,"lantern")
    return m


def spice_market():
    m=base(6,"六帆香料市集厅",(48,23,39),{"选址":"绿洲贸易节点与实际商路，能容纳人流和后侧进货",
        "地形":"宽阔平缓商业地块；北侧入口与南侧补货口保持通行","配套":"内含六类铺位、称量与货仓，外部小摊按空地另选"},
        ["中央遮阳商街连通两翼交易铺位，后侧双货仓；布棚高差、半开放拱廊与蓝穹顶形成市集轮廓。"])
    pad(m,3,4,44,35)
    room_shell(m,4,24,22,34);room_shell(m,25,24,43,34)
    for x in (13,34):m.door(x,2,24,facing="north")
    for x in (8,13,18,28,33,38):crate_stack(m,x,2,31,3,2,3)
    for x in (5,11,17,29,35,41):
        for z in (8,22):m.box((x,2,z),(x,8,z),"cut_sandstone")
    for x in range(4,44):
        for z in range(7,24):
            height=10 if 13<=z<=17 else 9
            m.set(x,height,z,"white_wool" if (x//6)%2 else "orange_wool")
    # Transverse lintels carry the separate canvas bays.
    for x in (5,11,17,29,35,41):
        m.box((x,8,8),(x,8,22),"stripped_acacia_log[axis=z]")
        m.box((x,9,13),(x,9,17),"stripped_acacia_log[axis=z]")
    for z in (8,22):m.box((4,8,z),(43,8,z),"stripped_acacia_log[axis=x]")
    for i,(x,product) in enumerate(((6,"flower_pot"),(13,"hay_block"),(20,"yellow_wool"),(27,"barrel"),(34,"red_wool"),(40,"melon"))):
        z=15 if i%2==0 else 21
        width=min(5,44-x)
        m.box((x,2,z),(x+width-1,2,z),"stripped_acacia_log[axis=x]")
        m.set(x+1,3,z,product)
        if product.endswith("wool"):m.set(x+2,3,z,"blue_carpet")
        m.set(x+width-1,3,z,"lantern")
        if i%2==0:
            for xx in (x,x+2):m.set(xx,2,z-1,"barrel[facing=south]")
        else:
            for xx in (x+1,x+2):m.set(xx,2,z+1,"barrel[facing=north]")
        if i==0:
            for xx,plant in ((x,"fern"),(x+2,"red_mushroom"),(x+3,"dead_bush")):m.set(xx,3,z,"potted_"+plant)
        elif i==1:m.set(x+3,3,z,"pumpkin")
        m.point(f"stall_{i+1}","work",(x+1,2,z),["香料","粮食","布匹","饮水","染料","果蔬"][i]+"铺位",approach=(x+1,2,z+1 if z==15 else z-1))
    table(m,19,2,28,3,"blue");m.set(20,3,28,"light_weighted_pressure_plate[power=0]")
    m.point("weighing","work",(20,3,28),"称量与结算",approach=(20,2,27))
    m.set(28,2,27,"lectern[facing=east]");m.point("ledger","work",(28,2,27),"交易账目",approach=(29,2,27))
    room_shell(m,4,5,12,13,wall="orange_terracotta");arch_front(m,5,2,13,7,6)
    room_shell(m,35,5,43,13,wall="orange_terracotta");arch_front(m,36,2,13,7,6)
    dome(m,8,9,9,4);dome(m,39,9,9,4)
    # The end pavilions are rest/inspection alcoves, separate from the six stalls.
    bench(m,6,2,8,4,"south","birch");m.set(40,2,8,"water_cauldron[level=3]")
    m.point("rest","circulation",(9,2,11),"候行拱室",look_at=[8,3,8])
    m.point("water","work",(40,2,8),"公共取水",approach=(40,2,9))
    front(m,23,5,width=5)
    m.room("market","遮阳交易街",(5,2,14),(42,8,23),"六类摊铺的公共通行与停留")
    m.room("west_store","西货仓与称量",(5,2,25),(21,6,33),"寄存、称量与结算")
    m.room("east_store","东货仓与账房",(26,2,25),(42,6,33),"补货与交易账目")
    m.room("rest","候行拱室",(5,2,6),(11,6,12),"歇脚等候")
    m.room("water","饮水拱室",(36,2,6),(42,6,12),"公共饮水与看守")
    for x,z in ((15,15),(23,15),(32,15),(11,28),(37,28)):pendant(m,x,7,z,10)
    return m


def clinic():
    m=base(9,"清泉内庭医馆",(39,23,38),{"选址":"安静可达的居民或旅人服务地块，有可靠洁净用水与药材补给",
        "地形":"平缓地块，北入口到候诊院落，病床避开主通道","角色":"诊疗、休养与药材空间；不附加治疗机制或药效"},
        ["前庭候诊、侧翼诊室和药房、后部静养与值守；围合院落兼顾遮阳和互不穿行。"])
    pad(m,3,4,35,34)
    for spec in ((4,5,15,16),(23,5,34,16),(4,19,15,33),(18,23,34,33)):
        room_shell(m,*spec,wall="white_terracotta")
    m.door(15,2,12,facing="east");m.door(23,2,12,facing="west")
    m.door(15,2,21,facing="east");m.door(25,2,23,facing="north")
    # Two low exam couches with approach space, not decorative inaccessible beds.
    for x in (7,11):
        m.bed(x,2,12,color="light_blue",facing="north")
        m.point(f"exam_{x}","bed",(x,2,12),"诊室躺床",approach=(x+1,2,12))
    m.set(6,2,7,"water_cauldron[level=3]");table(m,9,2,7,4,"white")
    m.box((9,2,10),(9,4,13),"white_wool")
    m.box((7,1,9),(8,1,14),"light_blue_terracotta")
    m.box((11,1,9),(12,1,14),"light_blue_terracotta")
    shelf(m,25,2,7,7,contents="flower_pot")
    m.box((25,2,13),(29,2,13),"birch_planks");m.set(25,3,13,"potted_fern")
    m.set(28,2,13,"crafting_table");m.set(32,2,13,"barrel[facing=west]")
    m.point("pharmacy","work",(28,2,13),"药材整理",approach=(28,2,12))
    for z in (24,29):
        m.bed(7,2,z,color="white",facing="west")
        m.point(f"ward_{z}","bed",(7,2,z),"静养床位",approach=(8,2,z))
        m.set(10,2,z,"birch_slab[type=top]");m.set(10,3,z,"lantern")
    m.box((13,2,27),(13,4,31),"light_blue_wool")
    m.box((5,2,26),(8,4,26),"white_wool")
    m.set(14,2,30,"water_cauldron[level=3]")
    m.point("ward_wash","work",(14,2,30),"病房盥洗角",approach=(14,2,29))
    m.bed(30,2,29,color="cyan",facing="south");m.point("staff_bed","bed",(30,2,29),"值守床",approach=(29,2,29))
    m.set(20,2,31,"smoker[facing=north]");m.set(22,2,31,"water_cauldron[level=3]")
    m.set(20,2,26,"lectern[facing=south]");m.point("records","work",(20,2,26),"诊疗记录",approach=(20,2,27))
    shelf(m,25,2,32,3,contents="bookshelf")
    m.box((31,2,25),(33,3,25),"barrel[facing=south]")
    table(m,25,2,28,3,"white")
    bench(m,17,2,8,4,"south","birch");bench(m,17,2,18,4,"north","birch")
    for x,z in ((17,6),(21,6),(17,19),(21,19)):m.box((x,2,z),(x,6,z),"cut_sandstone")
    m.box((16,7,5),(22,7,20),"white_wool")
    m.set(19,2,14,"water_cauldron[level=3]")
    front(m,19,5,width=3)
    dome(m,9,10,9,4,"prismarine_bricks");windtower(m,29,26)
    for key,name,a,b,purpose in (("exam","诊室",(5,2,6),(14,6,15),"诊察躺床、取水与备品"),("pharmacy","药材房",(24,2,6),(33,6,15),"药材整理和存放"),("ward","静养病房",(5,2,20),(14,6,32),"独立休养床与遮挡洗漱角"),("staff","值守与记录",(19,2,24),(33,6,32),"值守睡眠、料理与档案"),("waiting","遮阳候诊庭",(16,2,6),(22,6,22),"候坐和饮水")):
        m.room(key,name,a,b,purpose)
    for x,z in ((9,9),(28,10),(10,21),(24,28)):pendant(m,x,6,z,8)
    return m


def observatory():
    m=base(10,"黄铜星仪台",(35,32,35),{"选址":"视野开阔且可安全登临的台地，避免树冠与高崖遮挡观测方向",
        "地形":"平缓台地上的独立观测塔，入口脚底 Y=2，二层 Y=9，观测露台 Y=16",
        "连接":"两段内部楼梯，保留入口步行和露台四周净空"},
        ["三层登临次序为接待计算、藏书值守、露天观测；砂岩塔身、蓝色腰线与黄铜星仪形成垂直轮廓。"],roof=16)
    pad(m,4,4,30,30)
    room_shell(m,6,6,28,28,roof=False)
    shell(m,(6,8,6),(28,14,28),"smooth_sandstone","birch_planks",ceiling="smooth_sandstone")
    for y in (7,14):
        for z in (6,28):m.box((6,y,z),(28,y,z),"blue_terracotta")
        for x in (6,28):m.box((x,y,6),(x,y,28),"blue_terracotta")
    for y in (3,10):
        for x in (10,16,22):
            window(m,(x,y,6),(x+2,y+2,6),color="cyan_stained_glass")
            window(m,(x,y,28),(x+2,y+2,28),color="cyan_stained_glass")
        for z in (10,18):
            for x in (6,28):window(m,(x,y,z),(x,y+2,z+2),"z","cyan_stained_glass")
    m.door(17,2,6,facing="north");front(m,17,6,width=3)
    table(m,12,2,12,7,"blue");m.set(15,3,12,"light_weighted_pressure_plate[power=0]")
    bench(m,12,2,15,7,"north","birch")
    m.box((8,2,25),(14,4,25),"bookshelf");m.set(19,2,25,"lectern[facing=north]")
    m.point("survey","work",(15,3,12),"星图计算台",approach=(15,2,11))
    m.point("records","work",(19,2,25),"历法与观测记录",approach=(19,2,24))
    # Separate sides prevent crossing flights from sharing insufficient headroom.
    m.box((24,8,11),(25,8,21),"air")
    for i in range(7):
        y=2+i;z=20-i
        for x in (24,25):
            m.box((x,2,z),(x,y,z),"sandstone");m.set(x,y,z,"sandstone_stairs[facing=north]")
    m.box((24,8,11),(25,8,13),"birch_planks")
    railing(m,(23,9,14),(23,9,21),wood="birch",axis="z")
    m.box((8,15,11),(9,15,22),"air")
    for i in range(7):
        y=9+i;z=13+i
        for x in (8,9):
            m.box((x,9,z),(x,y,z),"sandstone");m.set(x,y,z,"sandstone_stairs[facing=south]")
    m.box((8,15,20),(9,15,22),"smooth_sandstone")
    railing(m,(10,16,12),(10,16,19),wood="birch",axis="z")
    m.box((13,9,7),(13,13,20),"white_terracotta");m.door(13,9,11,facing="east")
    m.box((15,9,26),(26,11,26),"bookshelf")
    m.bed(19,9,19,color="blue",facing="south");m.point("keeper_bed","bed",(19,9,19),"观测者床位",approach=(20,9,19))
    table(m,17,9,11,4,"cyan");m.set(17,10,11,"potted_dead_bush")
    m.set(25,9,9,"water_cauldron[level=3]");m.set(26,9,9,"barrel[facing=south]")
    for z in (6,28):m.box((6,16,z),(28,16,z),"sandstone_wall")
    for x in (6,28):m.box((x,16,6),(x,16,28),"sandstone_wall")
    for x,z in ((7,7),(27,7),(7,27),(27,27)):
        m.box((x,16,z),(x,19,z),"cut_sandstone");m.set(x,20,z,"lantern")
    m.box((16,16,17),(18,18,19),"waxed_cut_copper")
    for i in range(6):m.set(17,19+i//2,16+i,"waxed_cut_copper")
    m.set(17,21,22,"cyan_stained_glass")
    m.set(20,16,18,"lectern[facing=east]")
    # Two large meridian rings make the armillary instrument legible from afar.
    for step in range(72):
        angle=step*math.tau/72;horizontal=round(5*math.cos(angle));vertical=round(5*math.sin(angle))
        material="gold_block" if step%18==0 else "waxed_cut_copper"
        m.set(17+horizontal,22+vertical,18,material)
        m.set(17,22+vertical,18+horizontal,material)
    m.set(17,21,18,"lightning_rod[facing=up]");m.set(17,22,18,"gold_block")
    for deg in range(0,360,30):
        x=17+round(7*math.cos(math.radians(deg)));z=17+round(7*math.sin(math.radians(deg)))
        m.set(x,15,z,"blue_terracotta")
    m.point("first_landing","circulation",(24,9,12),"藏书层楼梯口",look_at=[20,10,15])
    m.point("deck_landing","circulation",(8,16,21),"观测露台楼梯口",look_at=[17,20,18])
    m.point("instrument","work",(20,16,18),"星仪观测与记录",approach=(21,16,18))
    m.box((15,9,22),(17,10,24),"bookshelf")
    m.box((21,9,22),(22,9,24),"birch_slab[type=top]")
    m.set(22,10,23,"lantern")
    m.box((18,8,17),(21,8,21),"blue_terracotta")
    m.box((12,1,10),(19,1,16),"light_blue_terracotta")
    m.room("calculation","接待与星图计算",(7,2,7),(27,6,27),"计算桌、记录与上楼")
    m.room("library","藏书和值守",(14,9,7),(27,13,27),"观测藏书、起居与床位")
    m.room("gallery","二层登临廊",(7,9,7),(12,14,26),"通向露台的另一段楼梯")
    m.room("deck","露天观测层",(7,16,7),(27,28,27),"星仪、刻度与开阔观测面")
    m.meta["floors"]=[dict(name="接待计算",y=1,max_y=6),dict(name="藏书值守",y=8,max_y=13),dict(name="观测露台",y=15,max_y=27)]
    m.meta["design_notes"].append("星仪是原版方块构成的空间装置，不代表天文或望远镜机制已接入。")
    for x,y,z in ((17,6,17),(20,13,14),(18,13,23)):pendant(m,x,y,z,15)
    return m


def buried_inn():
    m=base(11,"沙脊旧驿站",(40,19,37),{"选址":"曾有商路的干旱积沙地段，历史线路转移具有解释力",
        "地形":"旧院落沿西侧半埋，东侧房间和中部通道仍可探索","入口":"北侧缺口进入；积沙封闭旧西翼，不声称任意埋藏仍可通行"},
        ["损毁驿院以砂堆改变西翼边界，东侧旧客房和货仓保留生活遗物，后墙夹室留下旧账册。"],role="structure")
    pad(m,3,4,36,33)
    for spec in ((22,6,35,17),(22,20,35,32),(4,23,12,32)):
        room_shell(m,*spec,wall="sandstone")
    m.door(22,2,10,facing="west");m.door(22,2,24,facing="west");m.door(12,2,27,facing="east")
    bedroom(m,"old_room",22,6,35,17,color="brown",two=False)
    crate_stack(m,29,2,28,4,3,2);m.set(25,2,22,"crafting_table")
    m.set(6,2,29,"lectern[facing=east]");m.box((5,2,24),(8,3,24),"bookshelf")
    m.point("records","work",(6,2,29),"旧商路账册",approach=(7,2,29))
    m.point("stores","work",(30,2,28),"遗留货物",approach=(30,2,27))
    # Collapsed west frontage remains a substantial ruin, with supported sand.
    for x in range(3,14):
        for z in range(6,24):
            height=max(2,round(6.3-.42*(x-3)-.05*abs(z-14)+.6*math.sin(z*.6+x*.7)))
            if height>2:m.box((x,2,z),(x,height-1,z),"sandstone")
            m.set(x,height,z,"sand")
    for z in (6,17,22):m.box((12,2,z),(12,6,z),"cut_sandstone")
    for x,z,height in ((6,7,8),(8,7,7),(10,7,6),(5,16,7)):
        m.box((x,2,z),(x,height,z),"cut_sandstone")
    m.box((13,2,32),(21,5,32),"sandstone")
    for x,y,z in ((18,2,8),(17,2,18),(20,2,30),(31,9,20),(34,9,7)):
        m.set(x,y,z,"sandstone_slab[type=bottom]")
    m.box((25,8,7),(28,9,10),"air")
    m.box((4,8,23),(8,9,27),"air")
    m.set(25,2,13,"potted_dead_bush");m.set(34,2,24,"potted_dead_bush")
    for x,z in ((33,15),(33,7),(34,31),(26,31),(6,25)):m.set(x,2,z,"sand")
    for x in range(22,36):
        for z in (6,17,20,32):
            if (x+z)%3==0:m.set(x,6,z,"sandstone")
            if (x+2*z)%5==0:m.set(x,9,z,"air")
    m.set(18,2,21,"cauldron")
    front(m,18,5,width=3,name="旧门楼缺口")
    m.room("guest","残存客房",(23,2,7),(34,6,16),"旧床、行李与屋顶缺口")
    m.room("store","废弃货仓",(23,2,21),(34,6,31),"旧包装台和遗货")
    m.room("ledger","后墙账册夹室",(5,2,24),(11,6,31),"局部坍顶和仍可进入的记录室")
    m.room("court","积沙内院",(14,2,6),(21,6,31),"积沙改变通路，但保留中部探索路线")
    for x,z in ((25,10),(25,24),(10,28)):m.set(x,2,z,"lantern")
    return m


def ancient_well():
    m=base(12,"旧星井管理所",(28,25,29),{"选址":"具有持续水源解释的旧井节点，附近地层允许维护井壁",
        "埋置":"地表脚底对应模型 Y=6；井底水面在 Y=2，需保留井壁深度",
        "用途":"取水、记水档案和值守；后室保留早期管理设施"},
        ["石砌井圈位于半开放内院，横梁吊桶直对井口；档案与旧值守室围绕水源组织。"],ground=6,roof=12,role="structure")
    pad(m,3,3,24,25,f=5)
    room_shell(m,15,4,23,15,f=5);room_shell(m,4,18,23,24,f=5)
    m.door(15,6,11,facing="west");m.door(17,6,18,facing="north")
    m.box((8,1,8),(10,5,10),"air");m.box((8,1,8),(10,1,10),"water[level=0]")
    for x in (7,11):m.box((x,6,7),(x,6,11),"smooth_sandstone_slab[type=bottom]")
    for z in (7,11):m.box((8,6,z),(10,6,z),"smooth_sandstone_slab[type=bottom]")
    for x in (6,12):m.box((x,6,9),(x,11,9),"stripped_acacia_log[axis=y]")
    m.box((6,12,9),(12,12,9),"stripped_acacia_log[axis=x]")
    m.box((9,8,9),(9,11,9),"chain[axis=y]");m.set(9,7,9,"cauldron")
    m.set(13,6,9,"grindstone[face=floor,facing=north]")
    m.point("well","work",(11,6,10),"井口取水",approach=(12,6,10))
    m.point("winch","work",(13,6,9),"井架维护",approach=(13,6,10))
    m.set(20,6,7,"lectern[facing=south]");m.box((17,6,14),(22,8,14),"bookshelf")
    m.point("ledger","work",(20,6,7),"记水档案",approach=(20,6,8))
    m.bed(7,6,22,color="orange",facing="north");m.point("keeper_bed","bed",(7,6,22),"旧值守床",approach=(8,6,22))
    m.box((5,6,19),(6,7,19),"birch_planks")
    table(m,9,6,19,2,"brown");m.set(9,7,19,"lantern")
    m.box((12,6,19),(12,9,23),"sandstone");m.door(12,6,21,facing="east")
    m.set(19,6,22,"smoker[facing=north]");m.set(21,6,22,"water_cauldron[level=3]")
    m.set(16,6,22,"barrel[facing=north]")
    table(m,14,6,20,2,"white")
    m.point("service","work",(19,6,22),"值守生活台",approach=(19,6,21))
    table(m,6,6,15,5,"orange")
    bench(m,6,6,17,5,"north","birch")
    front(m,10,4,f=5,width=3)
    windtower(m,18,19,y=13)
    m.room("wellcourt","半开放井院",(4,6,4),(14,11,17),"井口、吊桶、维护与歇脚")
    m.room("archive","记水档案房",(16,6,5),(22,10,14),"水量记录与档案架")
    m.room("old_room","旧值守室",(5,6,19),(11,10,23),"保留旧床与早期室内布局")
    m.room("service","现用生活间",(13,6,19),(22,10,23),"炊事、存水与用品")
    m.meta["floors"]=[dict(name="井口与管理",y=5,max_y=10),dict(name="井壁剖面",y=0,max_y=6)]
    for x,z in ((18,10),(16,21)):pendant(m,x,10,z,12)
    return m


def desert(number):
    return {1:caravanserai,3:cistern,6:spice_market,9:clinic,10:observatory,11:buried_inn,12:ancient_well}[number]()
