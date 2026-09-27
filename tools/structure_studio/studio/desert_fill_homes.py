"""Six inhabited sandstone homes, separately composed from the user's image 1."""
from .model import Model
from .components import bench, pendant

SAND = "smooth_sandstone"
NAMES = ["单户小宅", "双户共院宅", "曲折内庭宅", "窄巷叠居宅", "转角住宅", "分台住宅"]
SIZES = [(24,22,26),(34,22,30),(32,22,32),(18,26,32),(30,22,28),(33,26,34)]
NOTES = [
    "前院木凉棚、后排起居，单侧上层卧室与开敞屋顶露台；侧阶直接登台。",
    "两户各占一翼，前低后高；双外阶、独立炊事和上层卧室，共享中央水庭。",
    "折入门廊围合内庭，左起居后厨房，右侧屋顶露台与后排高卧室错落相接。",
    "窄长三层：底层家务、中层睡眠、顶层小书房与露台，侧巷两段外阶连续可达。",
    "转角L形双翼包住二层凹台，两街门口通向共享起居，角部凉棚成为建筑重心。",
    "前庭、第一大露台和高层风庭逐层后退，连续外阶串联起居、睡眠和家务。",
]


def _base(v):
    m=Model(f"DS-04-v{v:02d}",NAMES[v-1],SIZES[v-1],family="DS-04",civilization="沙漠",role="fill",
        terrain={"选址":"有可靠生活用水的干热聚落，外阶和遮阳廊保持通行",
                 "地块":NOTES[v-1],"落地":"整备平地；院内、门外街面脚底均Y=2，上层露台由自带楼梯连接"})
    m.meta.update(source=f"tools/structure_studio/studio/desert_fill_homes.py:home({v})",
        design_notes=[NOTES[v-1],"砂岩框架、局部赭色灰泥嵌面、木凉棚和小幅布帘；楼梯与家具按玩家尺度布置。"],
        differences=[NOTES[v-1]],ground_plane={"y":2,"note":"门外砂岩铺地顶面与院内首层同高，脚底Y=2；外阶从该面登上屋顶，不以露台或门槛替代外部地面。"},
        roof_min_y=7,floors=[dict(name="首层与院落",y=1,max_y=6),dict(name="二层与露台",y=7,max_y=12)],
        preview_context=dict(kind="flat",land_surface_y=2,bed_y=-1,padding=3,surface="sand"))
    w,h,d=m.size
    m.box((2,0,2),(w-3,1,d-3),"cut_sandstone")
    m.box((2,2,2),(w-3,h-1,d-3),"air")
    for x in range(2,w-2):
        for z in range(2,d-2):m.set(x,1,z,SAND if (x+2*z)%9 else "sandstone")
    return m


def _house(m,x0,z0,x1,z1,f=1,top=None):
    top=f+6 if top is None else top
    m.box((x0,f,z0),(x1,top,z1),SAND)
    m.box((x0+1,f+1,z0+1),(x1-1,top-1,z1-1),"air")
    # Plaster belongs to selected wall segments, never a red frame around each window.
    if x1-x0>=7:m.box((x1-4,f+1,z1),(x1-2,top-2,z1),"terracotta")
    if z1-z0>=7:m.box((x0,f+1,z1-5),(x0,top-1,z1-2),"terracotta")
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,f+1,z),(x,top,z),"cut_sandstone")
    for z in (z0,z1):
        for x in range(x0+3,x1-1,5):
            _window(m,x,z,f,outward=-1 if z==z0 else 1)
    for x in (x0,x1):
        for z in range(z0+3,z1-1,6):_window(m,x,z,f,axis="z",outward=-1 if x==x0 else 1)
    for x in range(x0+1,x1):
        for z in range(z0+1,z1):
            if (x+z)%5==0:m.set(x,top,z,"cut_sandstone")
    _rail(m,x0,z0,x1,z1,top)


def _rail(m,x0,z0,x1,z1,f):
    for z in (z0,z1):m.box((x0,f+1,z),(x1,f+1,z),SAND)
    for x in (x0,x1):m.box((x,f+1,z0),(x,f+1,z1),SAND)
    for z in (z0,z1):
        for x in range(x0+2,x1-1):
            if (x-x0)%6 in (2,3,4):m.set(x,f+1,z,"spruce_fence[east=true,west=true]")
    for x in (x0,x1):
        for z in range(z0+2,z1-1):
            if (z-z0)%6 in (2,3,4):m.set(x,f+1,z,"spruce_fence[north=true,south=true]")


def _opening(m,x,z,f=1,axis="x",door=False):
    if axis=="x":m.box((x-1,f+1,z),(x+1,f+3,z),"air")
    else:m.box((x,f+1,z-1),(x,f+3,z+1),"air")
    if door:
        for side in (-1,1):
            dx,dz=(side,0) if axis=="x" else (0,side)
            m.box((x+dx,f+1,z+dz),(x+dx,f+3,z+dz),"cut_sandstone")
            m.set(x+dx,f+4,z+dz,"chiseled_sandstone")
        m.set(x,f+3,z,"chiseled_sandstone")
        m.door(x,f+1,z,wood="spruce",facing="north" if axis=="x" else "east")
        if axis=="x":
            m.box((x-1,f+1,z-1),(x+1,f+3,z-1),"air")
            m.box((x-1,f+4,z-1),(x+1,f+4,z-1),"spruce_slab[type=bottom]")
            for side in (-1,1):m.set(x+side,f+3,z-1,"spruce_stairs[facing=south,half=top]")
        else:
            for side in (-1,1):m.box((x+side,f+1,z-1),(x+side,f+3,z+1),"air")
            m.box((x+1,f+4,z-1),(x+1,f+4,z+1),"spruce_slab[type=bottom]")


def _window(m,x,z,f=1,axis="x",outward=-1):
    m.box((x,f+2,z),(x,f+3,z),"air")
    # Shutters project from the wall; unlike doors, the sill starts above floor level.
    if axis=="x":
        for xx in (x-1,x+1):
            for yy in (f+2,f+3):m.set(xx,yy,z+outward,"spruce_trapdoor[facing=north,half=bottom,open=true]")
        m.box((x-1,f+4,z+outward),(x+1,f+4,z+outward),"dark_oak_slab[type=bottom]")
        m.set(x,f+1,z+outward,"sandstone_slab[type=top]")
    else:
        for zz in (z-1,z+1):
            for yy in (f+2,f+3):m.set(x+outward,yy,zz,"spruce_trapdoor[facing=east,half=bottom,open=true]")
        m.box((x+outward,f+4,z-1),(x+outward,f+4,z+1),"dark_oak_slab[type=bottom]")


def _stairs(m,key,x0,z,x1,f,rise=6):
    for i in range(rise):
        y=f+i+1
        m.box((x0,1,z+i),(x1,y,z+i),SAND)
        for x in range(x0,x1+1):m.set(x,y,z+i,"sandstone_stairs[facing=south]")
        for x in (x0-1,x1+1):
            m.box((x,1,z+i),(x,y,z+i),"cut_sandstone")
            m.set(x,y+1,z+i,"sandstone_wall")
    m.box((x0,f+rise,z+rise),(x1,f+rise,z+rise+2),SAND)
    m.box((x0,f+rise+1,z+rise),(x1,f+rise+3,z+rise+2),"air")
    m.point(key,"circulation",((x0+x1)//2,f+rise+1,z+rise+1),"外阶上平台",look_at=[(x0+x1)//2-3,f+rise+2,z+rise+1])


def _shade(m,x0,z0,x1,z1,f=1,cloth=False):
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,f+1,z),(x,f+4,z),"stripped_dark_oak_log[axis=y]")
    variant=int(m.meta["id"][-2:])
    palette={1:("spruce_slab[type=bottom]","oak_trapdoor[half=bottom,open=false]"),
             2:("white_wool","white_terracotta"),3:("white_terracotta","brown_wool"),
             4:("white_wool","light_gray_wool"),5:("white_wool","white_terracotta"),
             6:("white_terracotta","brown_wool")}
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            material=palette[variant][1 if x in (x0,x1) else 0] if cloth else (
                "spruce_slab[type=bottom]" if (z-z0)%2==0 else "oak_trapdoor[half=bottom,open=false]")
            m.set(x,f+5,z,material)
    m.box((x0,f+4,z0),(x1,f+4,z0),"stripped_dark_oak_log[axis=x]")
    m.box((x0,f+4,z1),(x1,f+4,z1),"stripped_dark_oak_log[axis=x]")
    pendant(m,x0+1,f+3,z0+1,f+5)


def _life(m,key,x0,z0,x1,z1,f=1):
    y=f+1
    m.set(x0,y,z1,"smoker[facing=north]")
    m.set(x0+2,y,z1,"water_cauldron[level=3]")
    m.set(x1,y,z1,"barrel[facing=north]")
    m.box((x0+1,y+1,z1),(x0+1,f+5,z1),"sandstone")
    m.point(key+"_cook","work",(x0,y,z1),"家庭炊事",approach=(x0,y,z1-1))
    m.point(key+"_store","work",(x1,y,z1),"家用储物",approach=(x1-1,y,z1))
    tx=min(x0+3,x1-2);tz=z0+2
    m.box((tx,y,tz),(tx+2,y,tz),"acacia_slab[type=top]")
    bench(m,tx,y,tz+2,3,"north","acacia")
    m.set(tx,y,tz-1,"orange_carpet")
    m.room(key,"家庭炊事与起居",(x0,y,z0),(x1,f+5,z1),"烹饪、用水、餐桌、坐席和储物")
    pendant(m,x0+1,f+4,z0+1,f+6)


def _bedroom(m,key,x0,z0,x1,z1,f=7,two=True):
    for i,x in enumerate((x0+1,x1-1) if two else (x0+1,)):
        m.bed(x,f+1,z1-1,color="orange" if i==0 else "white",facing="north")
        m.point(f"{key}_bed{i+1}","bed",(x,f+1,z1-1),"卧室床位",approach=(x+1 if i==0 else x-1,f+1,z1-1))
    m.set(x1,f+1,z0,"barrel[facing=north]")
    m.set(x0,f+1,z0,"bookshelf")
    m.room(key,"睡眠与衣物",(x0,f+1,z0),(x1,f+5,z1),"独立睡眠、衣物与阅读")
    pendant(m,(x0+x1)//2,f+4,z0+1,f+6)


def _gate(m,x,z):
    m.point("front","entrance",(x,2,z),"街面主入口",facing="north")
    m.meta["connections"].append(dict(kind="pedestrian",pos=[x,2,z],direction="north",clearance=[3,3],note="门外铺地与外部脚底基准Y=2对齐"))


def _yard(m,x0,z0,x1,z1,gate):
    for x in (x0,x1):m.box((x,2,z0),(x,3,z1),SAND)
    m.box((x0,2,z0),(x1,3,z0),SAND)
    m.box((gate-1,2,z0),(gate+1,4,z0),"air")
    for x in (x0,x1):m.set(x,4,z0,"sandstone_slab[type=bottom]")


def _pot(m,x,y,z):
    m.set(x,y,z,"terracotta");m.set(x,y+1,z,"potted_dead_bush")


def _green(m,x,y,z):
    m.set(x,y,z,"flower_pot")
    m.set(x,y+1,z,"jungle_leaves[persistent=true]")
    m.set(x,y+2,z,"azalea_leaves[persistent=true]")


def _rug(m,x,z,w,d,f=1):
    for xx in range(x,x+w):
        for zz in range(z,z+d):
            m.set(xx,f+1,zz,"red_carpet" if xx in (x,x+w-1) or zz in (z,z+d-1) else "orange_carpet")


def _drying(m,x0,x1,z,f):
    for x in (x0,x1):m.box((x,f+1,z),(x,f+3,z),"spruce_fence")
    m.box((x0+1,f+3,z),(x1-1,f+3,z),"spruce_fence[east=true,west=true]")
    for x in range(x0+1,x1,2):m.set(x,f+2,z,"white_wool")


def _tea(m,x,z,f):
    m.set(x,f+1,z,"spruce_slab[type=top]")
    m.set(x,f+2,z,"flower_pot")
    m.set(x-1,f+1,z,"spruce_stairs[facing=east]")
    m.set(x+1,f+1,z,"spruce_stairs[facing=west]")


def _single(m):
    _house(m,4,11,17,22)
    _house(m,4,15,10,22,f=7)
    _opening(m,12,11,door=True);_opening(m,10,18,f=7,axis="z",door=True)
    _life(m,"living",5,12,16,21)
    _bedroom(m,"bedroom",5,16,9,21,two=False)
    _stairs(m,"terrace_stair",18,5,20,1)
    m.box((17,8,11),(17,10,14),"air")
    m.box((18,7,14),(20,7,21),SAND)
    for x in (20,):m.box((x,8,14),(x,8,22),SAND)
    m.box((18,8,22),(20,8,22),SAND)
    m.box((20,8,11),(20,8,13),SAND)
    _shade(m,12,16,16,21,7)
    bench(m,12,8,20,3,"north","acacia")
    m.point("roof_seat","circulation",(14,8,18),"屋顶凉棚",look_at=[14,9,20])
    _yard(m,3,3,20,10,13);_shade(m,4,4,9,9)
    bench(m,5,2,7,3,"south","acacia");_gate(m,13,3)
    for x,z in ((5,10),(16,10),(19,4)):_pot(m,x,2,z)
    _window(m,7,11);_window(m,7,15,7);_window(m,17,17,axis="z")
    _rug(m,11,6,5,3);_green(m,4,2,10);_green(m,16,8,15)
    _drying(m,11,15,13,7)
    m.set(19,8,19,"water_cauldron[level=3]");m.set(19,8,20,"barrel[facing=north]")
    m.point("roof_wash","work",(19,8,19),"露台洗衣",approach=(18,8,19))
    _tea(m,7,8,1)
    m.set(9,2,10,"barrel[facing=north]")
    # A broad shaded ground window and a small upper slit give this home its own rhythm.
    m.box((6,3,11),(8,4,11),"air");m.box((6,3,10),(8,4,10),"air")
    m.box((5,5,10),(9,5,10),"dark_oak_slab[type=bottom]")


def _twins(m):
    for key,a,b,sx,shade in (("west",3,11,12,True),("east",22,30,19,False)):
        _house(m,a,7,b,26)
        _house(m,a,18,b,26,7)
        _opening(m,(a+b)//2,7,door=True)
        _opening(m,b if key=="west" else a,13,axis="z",door=True)
        _opening(m,(a+b)//2,18,7,door=True)
        _life(m,key+"_living",a+1,8,b-1,24)
        _bedroom(m,key+"_bedroom",a+1,19,b-1,25)
        _stairs(m,key+"_stair",sx,10,sx+2,1)
        xx=b if key=="west" else a
        m.box((xx,8,16),(xx,10,17),"air")
        guard=sx+2 if key=="west" else sx
        m.box((guard,8,16),(guard,8,18),SAND)
        m.box((sx,8,18),(sx+2,8,18),SAND)
        _shade(m,a+1,9,b-1,14,7,cloth=shade)
        bench(m,a+2,8,13,3,"north","acacia")
        _window(m,a+2,7);_window(m,a+2,18,7)
    _yard(m,2,3,31,6,16);_gate(m,16,3)
    m.box((15,1,22),(18,1,25),"cut_sandstone")
    m.box((16,1,23),(17,1,24),"water[level=0]")
    m.point("water","work",(15,2,23),"共院取水",approach=(15,2,22))
    for x in (15,18):_pot(m,x,2,7)
    m.room("court","两户共水院",(12,2,4),(21,6,26),"共享取水与两户各自独立登楼")
    for x in (13,20):_green(m,x,2,25)
    for a in (4,23):
        m.set(a,2,18,"loom[facing=east]")
        m.set(a+2,2,18,"barrel[facing=north]")
        _rug(m,a+2,10,3,3)
        m.set(a,8,10,"barrel[facing=east]")
        m.set(a+2,8,16,"water_cauldron[level=3]")
        m.point(f"roof_wash_{a}","work",(a+2,8,16),"各户露台洗晒",approach=(a+2,8,15))
    _drying(m,5,9,17,7)
    _tea(m,27,11,7)


def _courtyard(m):
    _house(m,3,5,17,10)
    _house(m,3,10,11,28)
    _house(m,11,22,28,28)
    _house(m,22,10,28,22)
    _house(m,13,22,28,28,7)
    _opening(m,13,5,door=True);_opening(m,13,10)
    _opening(m,11,17,axis="z",door=True)
    _opening(m,16,22,door=True)
    _opening(m,22,14,axis="z",door=True)
    _opening(m,11,24,axis="z")
    _opening(m,19,22,7,door=True)
    _life(m,"living",4,12,10,27)
    _bedroom(m,"bedroom",14,23,27,27)
    _stairs(m,"inner_stair",18,15,20,1)
    m.box((21,7,20),(28,7,22),SAND)
    m.box((21,8,20),(22,10,21),"air")
    _shade(m,23,12,27,18,7,cloth=True)
    bench(m,23,8,17,3,"north","acacia")
    m.point("upper_court","circulation",(25,8,15),"内庭上层凉台",look_at=[17,4,16])
    for x,z in ((5,7),(15,25),(25,20)):m.set(x,2,z,"barrel[facing=north]")
    bench(m,5,2,7,3,"south","acacia")
    m.box((13,1,16),(15,1,18),"water[level=0]")
    _pot(m,12,2,12);_pot(m,17,2,12)
    _gate(m,13,4)
    for x in (6,25):_window(m,x,22,7)
    _window(m,6,5);_window(m,3,19,axis="z")
    m.room("court","曲折内庭",(12,2,11),(21,6,21),"折入门厅、水盆和环院通行")
    bench(m,23,2,19,3,"north","spruce")
    m.set(27,2,12,"bookshelf");m.set(27,2,16,"barrel[facing=west]")
    m.point("cool_store","work",(27,2,16),"凉室家用储物",approach=(26,2,16))
    _green(m,12,2,20);_green(m,23,8,20);_rug(m,14,12,3,3)
    m.set(26,8,20,"barrel[facing=north]")
    m.set(24,8,19,"spruce_slab[type=top]");m.set(24,9,19,"flower_pot")
    m.point("terrace_herbs","work",(26,8,20),"凉台干货与香草",approach=(26,8,19))
    m.set(15,2,25,"crafting_table");m.set(16,2,25,"barrel[facing=north]")


def _stacked(m):
    _house(m,3,7,11,27)
    _house(m,3,7,11,27,7)
    _house(m,3,20,11,27,13)
    _opening(m,7,7,door=True);_opening(m,11,14,7,axis="z",door=True)
    _opening(m,11,24,13,axis="z",door=True);_opening(m,7,20,13,door=True)
    _life(m,"living",4,8,10,26)
    _bedroom(m,"bedroom",4,18,10,26)
    m.box((12,7,12),(15,7,27),SAND)
    m.box((15,8,12),(15,8,27),SAND)
    _stairs(m,"first_stair",12,6,14,1)
    _stairs(m,"second_stair",12,17,14,7)
    m.box((11,14,23),(11,16,25),"air")
    m.box((14,14,23),(14,14,25),SAND)
    m.box((12,14,25),(14,14,25),SAND)
    m.box((12,8,27),(15,8,27),SAND)
    _shade(m,4,9,10,16,13,cloth=True)
    bench(m,5,14,14,3,"north","acacia")
    m.set(5,14,25,"bookshelf");m.set(9,14,25,"barrel[facing=north]")
    m.point("reading","work",(5,14,25),"顶层书房",approach=(6,14,25))
    m.point("roof","circulation",(7,14,18),"顶层晾晒露台",look_at=[7,15,12])
    m.room("attic","顶层书房",(4,14,21),(10,18,26),"阅读、储藏和露台出入")
    m.meta["floors"].append(dict(name="顶层风庭",y=13,max_y=18))
    _shade(m,4,3,9,5,cloth=True);_gate(m,7,3)
    for f in (1,7):_window(m,4,7,f);_window(m,3,18,f,axis="z")
    for x,z in ((3,5),(10,5),(15,15)):_pot(m,x,2 if z==5 else 8,z)
    m.box((4,8,11),(6,8,11),"spruce_slab[type=top]")
    m.set(4,9,11,"flower_pot");m.set(9,8,10,"bookshelf")
    bench(m,5,8,14,3,"north","spruce")
    _green(m,4,14,18);_rug(m,5,15,3,3,7)
    _drying(m,5,9,17,13)
    m.set(9,14,18,"water_cauldron[level=3]")
    m.set(5,14,22,"lectern[facing=south]")
    m.set(8,14,24,"spruce_stairs[facing=north]")
    m.set(8,14,23,"spruce_slab[type=top]");m.set(8,15,23,"flower_pot")
    m.point("attic_desk","work",(5,14,22),"顶层阅读桌",approach=(5,14,23))
    m.set(4,2,18,"barrel[facing=east]");m.set(4,2,19,"crafting_table")
    bench(m,5,2,16,3,"south","spruce")
    # A single tall upper opening follows the narrow vertical house frontage.
    m.box((4,9,7),(10,12,7),SAND);m.box((3,9,6),(10,12,6),"air")
    m.box((6,9,7),(6,11,7),"air")
    for x in (5,7):
        m.box((x,9,6),(x,11,6),"spruce_trapdoor[facing=north,half=bottom,open=true]")
    m.box((5,12,6),(7,12,6),"dark_oak_slab[type=bottom]")


def _corner(m):
    _house(m,3,6,13,24)
    _house(m,13,15,25,24)
    _house(m,3,6,10,24,7)
    _house(m,10,19,25,24,7)
    _opening(m,8,6,door=True);_opening(m,25,18,axis="z",door=True)
    _opening(m,13,19,axis="z")
    _opening(m,10,15,7,axis="z",door=True);_opening(m,17,19,7,door=True)
    _life(m,"living",4,7,12,23)
    _bedroom(m,"bedroom",11,20,24,23)
    _stairs(m,"corner_stair",22,9,24,1)
    m.box((22,8,15),(24,10,17),"air")
    m.box((11,8,19),(13,10,19),"air")
    _shade(m,12,15,19,18,7)
    bench(m,14,8,17,3,"north","acacia")
    m.point("balcony","circulation",(17,8,16),"转角会客露台",look_at=[15,9,17])
    m.set(6,8,22,"bookshelf");m.set(7,8,21,"barrel[facing=north]")
    m.point("study","work",(6,8,22),"侧翼书物",approach=(6,8,21))
    m.room("study","侧翼上层家务",(4,8,7),(9,12,23),"储物、阅读与屋顶露台连接")
    m.set(20,2,22,"barrel[facing=north]")
    bench(m,16,2,20,3,"north","acacia")
    _shade(m,5,3,10,5,cloth=True)
    _gate(m,8,3)
    m.point("side","entrance",(26,2,18),"侧街入口",facing="east")
    _window(m,6,6,7);_window(m,25,21,7,axis="z")
    for x,z in ((4,4),(12,5),(25,14)):_pot(m,x,2,z)
    m.box((5,8,11),(7,8,11),"spruce_slab[type=top]")
    m.set(5,9,11,"lantern");bench(m,5,8,13,3,"north","spruce")
    _green(m,20,8,18);_green(m,14,2,16);_rug(m,16,8,4,3)
    m.set(16,8,15,"spruce_slab[type=top]");m.set(16,9,15,"flower_pot")
    m.set(20,8,16,"barrel[facing=west]")
    m.point("terrace_tea","work",(16,8,15),"转角待客茶桌",approach=(16,8,16))
    m.box((15,8,18),(18,8,18),"spruce_fence[east=true,west=true]")
    m.set(17,8,18,"air")


def _terraced(m):
    _house(m,3,12,25,30)
    _house(m,3,21,25,30,7)
    _house(m,4,24,13,30,13)
    _opening(m,12,12,door=True)
    _opening(m,16,21,7,door=True);_opening(m,13,27,13,axis="z",door=True)
    _life(m,"living",4,13,24,29)
    _bedroom(m,"bedroom",4,22,24,29)
    _stairs(m,"lower_stair",26,6,28,1)
    m.box((25,8,12),(25,10,15),"air")
    m.box((28,8,12),(28,8,14),SAND)
    m.box((26,8,14),(28,8,14),SAND)
    _stairs(m,"upper_stair",22,15,24,7)
    m.box((22,14,21),(24,16,23),"air")
    _shade(m,15,25,22,29,13,cloth=True)
    bench(m,17,14,28,3,"north","acacia")
    m.set(6,14,28,"barrel[facing=north]");m.set(6,14,25,"loom[facing=east]")
    m.point("loom","work",(6,14,25),"上台织补",approach=(7,14,25))
    m.point("high_terrace","circulation",(17,14,26),"高层凉棚",look_at=[15,15,28])
    m.room("workroom","高台家务房",(5,14,25),(12,18,29),"织补、储物和风庭")
    m.meta["floors"].append(dict(name="第三层风庭",y=13,max_y=18))
    _shade(m,5,14,12,18,7)
    bench(m,6,8,17,3,"north","acacia")
    m.point("middle_terrace","circulation",(9,8,16),"中层家人凉台",look_at=[9,9,17])
    _yard(m,3,3,25,11,13);_shade(m,5,5,10,9,cloth=True)
    bench(m,6,2,8,3,"north","acacia");_gate(m,13,3)
    for x,z in ((4,10),(23,10),(19,14)):_pot(m,x,2 if z==10 else 8,z)
    _window(m,7,12);_window(m,7,21,7);_window(m,8,24,13)
    # Lower store alcove and upper wash screen subdivide the deep stepped plan.
    m.box((17,2,24),(17,5,28),"cut_sandstone")
    m.set(20,2,27,"loom[facing=north]");m.set(22,2,27,"barrel[facing=north]")
    m.point("lower_work","work",(20,2,27),"底层编织家务",approach=(20,2,26))
    m.box((14,8,25),(14,11,28),"cut_sandstone")
    m.set(12,8,27,"water_cauldron[level=3]")
    _green(m,5,8,19);_green(m,24,14,29);_rug(m,12,6,6,3)
    _drying(m,16,20,22,13)
    m.set(23,14,28,"barrel[facing=west]")
    m.set(18,14,26,"spruce_slab[type=top]");m.set(18,15,26,"flower_pot")
    m.set(14,8,19,"water_cauldron[level=3]");m.set(16,8,19,"barrel[facing=north]")
    m.point("middle_wash","work",(14,8,19),"中台洗晒",approach=(14,8,18))
    bench(m,17,2,18,4,"south","spruce")
    m.set(19,2,16,"spruce_slab[type=top]");m.set(19,3,16,"flower_pot")
    # Three separated bays flank the lower door; the east bay is a paired opening.
    m.box((4,3,12),(24,6,12),SAND);m.box((4,3,11),(24,6,11),"air")
    for x in (6,18,22):_window(m,x,12)
    m.box((18,3,12),(19,4,12),"air");m.box((18,3,11),(19,4,11),"air")
    _opening(m,12,12,door=True)


def home(variant):
    if variant not in range(1,7):raise ValueError("DS-04 variant must be 1..6")
    m=_base(variant)
    (_single,_twins,_courtyard,_stacked,_corner,_terraced)[variant-1](m)
    return m
