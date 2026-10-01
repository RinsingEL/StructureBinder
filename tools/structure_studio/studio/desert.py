"""Sand-sea civic buildings with independently authored plans and uses."""
import math

from .model import Model
from .components import shell,window,arch_front,bench,pendant,shelf,crate_stack
from .samples import railing


def base(number,name,size,terrain,notes,*,ground=1,roof=8,role="key"):
    m=Model(f"DS-{number:02d}-v01",name,size,family=f"DS-{number:02d}",civilization="沙漠",role=role,terrain=terrain)
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
    m.box((x+1,y-1,z+1),(x+2,y-1,z+2),"air")
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
    from .desert_caravanserai import caravanserai as build_caravanserai
    return build_caravanserai()


def cistern():
    from .desert_cistern import cistern as build
    return build()


def spice_market():
    from .desert_market import spice_market as build
    return build()


def clinic():
    from .desert_clinic import clinic as build
    return build()


def observatory():
    from .desert_observatory import observatory as build
    return build()


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
    m.meta['ground_plane']=dict(y=1,note='外部积沙街面脚底Y=1，经门外半格台阶升至旧驿院脚底Y=2；内部砂堆不改变街面基准。')
    for x in range(17,20):m.set(x,1,3,'sandstone_stairs[facing=south]')
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
