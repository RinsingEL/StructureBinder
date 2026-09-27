"""Six northern civic silhouettes from the user's 2026-09-27 reference board."""
from .model import Model
from .components import bench, shelf, pendant, crate_stack


def _base(n,name,w,d,h=35,shore=False):
    m=Model(f"NS-{n:02d}-v01",name,(w,h,d),family=f"NS-{n:02d}",civilization="北欧",
            role="key" if n in (1,4,10) else "structure",terrain={
                "选址":"寒地聚落或背风海岸，基础须稳定；保留完整出入口与工作面",
                "地面":"外部陆侧街面脚底Y=3；高台、露台和水面另记，不能取代此基准",
                "边界":"原版方块静态建筑，不模拟温度、潮汐、吊装或船舶运行"})
    m.box((1,0,1),(w-2,2,d-2),"cobblestone")
    m.box((1,2,1),(w-2,2,d-2),"gravel")
    m.box((1,3,1),(w-2,h-1,d-2),"air")
    m.meta.update(source=f"tools/structure_studio/studio/northern_reference.py:BUILDERS['NS-{n:02d}-v01']",
        roof_min_y=9,floors=[dict(name="陆侧生活作业层",y=2,max_y=7)],
        ground_plane=dict(y=3,note="陆侧入口外地面方块顶面，脚底Y=3；海水表面和内部高台分别记录，不用于接地。"),
        preview_context=dict(kind="flat",land_surface_y=3,bed_y=-2,padding=4,surface="snow"))
    if shore:
        m.box((1,0,1),(w-2,2,11),"air")
        m.box((1,0,1),(w-2,0,11),"water[level=0]")
        m.meta["terrain"]["海面"]="海在北侧-Z，模板水面上边界Y=1；岸边桩桥脚底Y=3，高于水面2格"
    return m


def _hall(m,x0,z0,x1,z1,f=2,wallh=4,axis="z",roof="spruce",open_ends=False):
    top=f+wallh
    m.box((x0,f,z0),(x1,top,z1),"spruce_planks")
    m.box((x0+1,f+1,z0+1),(x1-1,top,z1-1),"air")
    if open_ends:
        for z in (z0,z1):m.box((x0+2,f+1,z),(x1-2,top,z),"air")
    for x in (x0,x1):
        for z in range(z0,z1+1,5):m.box((x,f+1,z),(x,top+1,z),"stripped_dark_oak_log[axis=y]")
        m.box((x,top+1,z0),(x,top+2,z1),"dark_oak_log[axis=z]")
    for z in (z0,z1):
        m.box((x0,top+1,z),(x1,top+1,z),"dark_oak_log[axis=x]")
    lo,hi=(x0-1,x1+1) if axis=="z" else (z0-1,z1+1)
    along0,along1=(z0-1,z1+1) if axis=="z" else (x0-1,x1+1)
    ridge=top+2+(hi-lo)//2
    for cross in range(lo,hi+1):
        y=top+2+min(cross-lo,hi-cross)
        face=("east" if cross<(lo+hi)/2 else "west") if axis=="z" else ("south" if cross<(lo+hi)/2 else "north")
        for a in range(along0,along1+1):
            material="dark_oak" if (a-along0)%8==0 else roof
            block=f"{material}_stairs[facing={face}]" if cross not in ((lo+hi)//2,) else ("mossy_stone_bricks" if material=="mossy_stone_brick" else material+"_planks")
            x,z=(cross,a) if axis=="z" else (a,cross)
            m.set(x,y,z,block)
        if lo<cross<hi:
            for a in (along0+1,along1-1):
                x,z=(cross,a) if axis=="z" else (a,cross)
                m.box((x,top+2,z),(x,y-1,z),"spruce_planks") if y>top+2 else None
    for z in range(z0+3,z1-1,6):
        for x in (x0,x1):m.box((x,f+2,z),(x,f+3,z+1),"glass_pane[east=false,west=false,north=true,south=true]")
    # Ridge carvings are restrained; load-bearing posts and the slope do the work.
    cx,cz=(x0+x1)//2,(z0+z1)//2
    for a in (along0,along1):
        x,z=(cx,a) if axis=="z" else (a,cz)
        m.set(x,ridge+1,z,"dark_oak_fence")
    return ridge


def _door(m,x,z,f=2,axis="x"):
    if axis=="x":m.box((x-1,f+1,z),(x+1,f+3,z),"air")
    else:m.box((x,f+1,z-1),(x,f+3,z+1),"air")
    m.door(x,f+1,z,wood="spruce",facing="north" if axis=="x" else "east")


def _entry(m,x,z,y=3,facing="north"):
    m.point("front","entrance",(x,y,z),"陆侧主入口",facing=facing)
    m.meta["connections"].append(dict(kind="pedestrian",pos=[x,y,z],direction=facing,clearance=[3,3],note="陆侧脚底Y=3接外部街面"))


def _use(m,key,name,x,z,ax,az,y=3,kind="work"):
    m.point(key,kind,(x,y,z),name,approach=(ax,y,az))


def _beds(m,key,x,z,y=3,n=2):
    for i in range(n):
        xx=x+3*i;m.bed(xx,y,z,color="brown",facing="north")
        _use(m,key+str(i),"轮休床位",xx,z,xx+1,z,y,kind="bed")


def _cook(m,x,z,y=3):
    m.set(x,y,z,"smoker[facing=north]");m.set(x+2,y,z,"water_cauldron[level=3]")
    m.set(x+4,y,z,"barrel[facing=north]")
    upper=max(yy for (xx,yy,zz),b in m.blocks.items() if xx==x and zz==z and b[0]!="minecraft:air")+2
    m.box((x,y+1,z),(x,upper,z),"cobblestone");m.set(x,upper+1,z,"cobblestone_wall")
    _use(m,f"cook_{x}_{z}","热食与供水",x,z,x,z-1,y)


def _table(m,x,z,y=3,w=4):
    m.box((x,y,z),(x+w-1,y,z),"spruce_slab[type=top]")
    bench(m,x,y,z-2,w,"south","spruce");bench(m,x,y,z+2,w,"north","spruce")


def _deck(m,x0,z0,x1,z1,f=2):
    m.box((x0,f,z0),(x1,f,z1),"spruce_planks")
    for x in (x0,x1):
        for z in range(z0,z1+1,5):m.box((x,0,z),(x,f+1,z),"spruce_log[axis=y]")


def _steps(m,x,z,w,f,rise,key):
    for i in range(rise):
        m.box((x,0,z+i),(x+w-1,f+i+1,z+i),"stone_bricks")
        for xx in range(x,x+w):m.set(xx,f+i+1,z+i,"stone_brick_stairs[facing=south]")
        for xx in (x-1,x+w):
            m.box((xx,0,z+i),(xx,f+i+1,z+i),"cobblestone")
            m.set(xx,f+i+2,z+i,"cobblestone_wall")
    m.box((x,f+rise,z+rise),(x+w-1,f+rise,z+rise+1),"stone_bricks")
    m.point(key,"circulation",(x+w//2,f+rise+1,z+rise),"石阶上平台")


def _finish(m,note):
    m.meta["design_notes"]=[note];m.meta["differences"]=[note]
    return m


def boathouse():
    m=_base(1,"长跨滑道长船船屋",43,47,36,True)
    _hall(m,7,14,29,42,wallh=7,open_ends=True)
    _deck(m,6,8,30,14);_deck(m,31,7,39,27)
    _hall(m,31,23,39,39,wallh=3)
    _door(m,29,33,axis="z");_door(m,31,33,axis="z");_door(m,39,30,axis="z")
    _entry(m,40,30,facing="east")
    for z in range(4,16):
        for x in (14,22):m.set(x,2,z,"stripped_spruce_log[axis=z]")
        if z%3==0:m.box((13,1,z),(23,1,z),"dark_oak_log[axis=x]")
    for z in range(16,40):
        half=1 if z in (16,17,38,39) else 3
        m.box((18-half,3,z),(18+half,4,z),"dark_oak_planks")
        for x in (18-half,18+half):m.set(x,5,z,"spruce_stairs[facing="+("east" if x<18 else "west")+"]")
        if z%4==0:m.box((15,5,z),(21,5,z),"spruce_slab[type=bottom]")
    for z in (15,40):m.box((18,4,z),(18,8,z),"stripped_dark_oak_log[axis=y]")
    for z in (18,27,36):
        m.box((12,3,z),(24,3,z),"spruce_log[axis=x]")
        m.box((8,3,z),(8,10,z),"spruce_log[axis=y]");m.box((28,3,z),(28,10,z),"spruce_log[axis=y]")
        m.box((8,11,z),(28,11,z),"dark_oak_log[axis=x]")
    shelf(m,9,3,39,4);m.set(26,3,19,"anvil[facing=north]")
    _use(m,"hull","长船捻缝检修",15,26,12,26);_use(m,"anvil","紧固件铆修",26,19,26,20)
    m.set(10,3,17,"crafting_table");_use(m,"joiner","船板刨修",10,17,10,18)
    _beds(m,"watch",33,29,n=2);_cook(m,33,37)
    m.set(34,3,24,"crafting_table");_use(m,"tools","干燥工具修配",34,24,34,25)
    crate_stack(m,33,3,13,3,4,2)
    m.room("slip","长跨船体和双舷检修廊",(8,3,15),(28,10,41),"大开口滑道、长船托架和双侧作业通路")
    m.room("watch","侧翼修配与轮休",(32,3,24),(38,6,38),"两床、热食、工具与备用件")
    m.meta["terrain"]["船体"]="船体为静态修造展示；北侧滑道对水，东侧陆门为主入口，不将水面当步行入口"
    return _finish(m,"单座连续高跨长船屋占据主体，船首直对北侧双轨滑道；低侧翼与桩桥承担木工、轮休和补给。")


def supply_station():
    m=_base(2,"低檐围院冰原补给站",43,39,27)
    for x in (3,39):m.box((x,3,5),(x,6,35),"stone_bricks")
    m.box((3,3,5),(39,6,5),"stone_bricks");m.box((18,3,5),(24,6,5),"air")
    _hall(m,5,24,37,34,wallh=3,axis="x",roof="mossy_stone_brick")
    _hall(m,5,9,14,22,wallh=3,roof="mossy_stone_brick")
    _hall(m,29,10,37,22,wallh=3,roof="mossy_stone_brick")
    _door(m,21,24);_door(m,14,16,axis="z");_door(m,29,16,axis="z");_entry(m,21,4)
    _beds(m,"bunk",7,31,n=3);_cook(m,29,32);_table(m,19,29)
    shelf(m,6,3,10,5);shelf(m,6,3,20,5)
    _use(m,"stores","冬储粮与防寒装备",7,10,7,12,kind="storage")
    m.set(33,3,13,"crafting_table");m.set(33,3,19,"grindstone[face=floor,facing=west]")
    _use(m,"repair","雪橇器具维护",33,13,33,14)
    for x,z in ((18,13),(24,19)):
        m.box((x,3,z),(x+3,3,z+4),"spruce_slab[type=bottom]")
        for xx in (x,x+3):m.box((xx,2,z),(xx,2,z+5),"dark_oak_log[axis=z]")
        crate_stack(m,x+1,4,z+1,2,2,2)
    _use(m,"dispatch","院内补给装卸",18,13,17,13)
    for x in (17,25):
        m.box((x,3,5),(x,8,5),"stone_bricks");m.set(x,9,5,"lantern")
    m.room("yard","低墙补给院",(15,3,6),(28,8,23),"双雪橇、装卸、分发和避风集散")
    m.room("lodge","背风长屋",(6,3,25),(36,7,33),"三床轮休、公共餐席和热食")
    return _finish(m,"低矮三翼围绕宽补给院，石墙门口与两辆装货雪橇形成前场；背风长屋和两侧粮仓/修具室分别使用。")


def bathhouse():
    m=_base(4,"岸桥冷池公共浴屋",43,40,29,True)
    _deck(m,4,7,38,24);_deck(m,18,3,38,12)
    _hall(m,5,24,36,35,wallh=4,axis="x")
    _hall(m,5,13,16,24,wallh=4)
    _hall(m,25,14,36,24,wallh=4)
    _door(m,21,35);_entry(m,21,36,facing="south")
    _door(m,11,24);_door(m,30,24);_door(m,16,18,axis="z");_door(m,25,18,axis="z")
    m.box((17,2,17),(24,2,20),"spruce_planks")
    m.box((17,7,17),(24,7,20),"spruce_slab[type=bottom]")
    for x in (17,24):m.box((x,3,17),(x,6,17),"spruce_log[axis=y]")
    shelf(m,7,3,15,6);bench(m,7,3,20,6,"north","spruce")
    _use(m,"linen","干衣与毛巾柜",8,15,8,17,kind="storage")
    m.set(14,3,15,"lectern[facing=south]");_use(m,"attend","更衣接待",14,15,14,16)
    for z in (17,21):bench(m,27,3,z,6,"south","spruce")
    _cook(m,28,23);_use(m,"steam","炉旁暖浴坐席",28,21,28,20)
    m.box((8,2,27),(16,2,32),"stone_bricks");m.box((9,2,28),(15,2,31),"water[level=0]")
    _use(m,"warm_pool","室内温浴池边",9,28,8,28)
    _table(m,24,29);shelf(m,18,3,33,4)
    m.box((23,0,3),(36,2,11),"stone_bricks")
    m.box((24,1,4),(35,2,10),"air");m.box((24,1,4),(35,1,10),"water[level=0]")
    _use(m,"cold_pool","岸边冷池与清洁平台",24,7,23,7)
    for x in (23,36):m.box((x,3,3),(x,3,11),"spruce_fence[north=true,south=true]")
    m.box((24,3,3),(35,3,3),"spruce_fence[east=true,west=true]")
    m.set(23,3,7,"air")
    m.room("change","临岸更衣翼",(6,3,14),(15,7,23),"衣柜、接待与更衣长凳")
    m.room("warm","后部温浴与坐休厅",(6,3,25),(35,7,34),"温池、茶桌、布品与通向两翼的干道")
    m.room("sauna","侧翼热炉浴室",(26,3,15),(35,7,23),"分列热浴长凳与独立烟道")
    m.meta["terrain"]["冷池"]="室外冷池水面Y=2，比木甲板脚底Y=3低一格；海面Y=1，均不替代陆侧地面Y=3"
    return _finish(m,"三段岸边浴屋由木桥连通，低桩桥前伸到有石壁的露天冷池；衣物、热炉坐浴、室内温池和茶歇分别安排。")


def epic_hall():
    m=_base(10,"陡顶炉席史诗会堂",41,42,37)
    m.box((3,0,8),(37,5,38),"stone_bricks")
    _hall(m,9,14,31,36,f=5,wallh=8,roof="dark_oak")
    _hall(m,3,20,9,34,f=5,wallh=4)
    _hall(m,31,20,37,34,f=5,wallh=4)
    _hall(m,16,8,24,14,f=5,wallh=4)
    _steps(m,17,5,7,2,3,"entry_stairs");_door(m,20,8,5);_door(m,20,14,5)
    _door(m,9,25,5,axis="z");_door(m,31,25,5,axis="z");_entry(m,20,4)
    for x in (12,24):
        for z in (21,29):_table(m,x,z,y=6,w=4)
    m.set(20,6,24,"campfire[lit=false]")
    for x in (19,21):m.set(x,6,24,"stone_brick_slab[type=bottom]")
    m.set(20,6,34,"lectern[facing=north]");_use(m,"story","史诗讲席",20,34,20,33,6)
    _use(m,"feast","公共宴席与听众通路",15,21,17,21,6)
    _cook(m,32,32,6);shelf(m,32,6,21,4)
    _beds(m,"guest",4,32,y=6,n=1);shelf(m,4,6,21,4,contents="bookshelf")
    _use(m,"archive","谱系卷册",5,21,5,23,6,kind="storage")
    for z in (16,25,34):
        for x in (10,30):m.box((x,6,z),(x,15,z),"dark_oak_log[axis=y]")
        m.box((10,15,z),(30,15,z),"dark_oak_log[axis=x]")
        pendant(m,20,12,z,15)
    for x in (13,27):m.box((x,9,13),(x,11,13),"red_wool")
    for x in (13,27):
        m.box((x,3,5),(x,5,5),"stone_bricks");m.set(x,6,5,"campfire[lit=false]")
    m.room("hall","陡顶宴席与演述主厅",(10,6,15),(30,14,35),"四组宴席、中央炉位、后端讲席与高跨木构")
    m.room("services","低翼厨房与档案",(4,6,21),(36,10,33),"西侧档案和轮休、东侧厨房与食物，不占中央听众通道")
    m.meta["floors"]=[dict(name="会堂抬高主层",y=5,max_y=13)]
    m.meta["roof_min_y"]=12
    return _finish(m,"高陡主厅、低双侧翼、前门风斗和三级台阶构成清晰主次；四组宴席让中央炉位到史诗讲席保持贯通。")


def _tent(m,x0,z0,x1,z1):
    _deck(m,x0,z0,x1,z1)
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,3,z),(x,6,z),"spruce_log[axis=y]")
    for x in range(x0,x1+1):
        y=6+min(x-x0,x1-x)
        m.box((x,y,z0),(x,y,z1),"white_wool")
    m.box((x0,3,z1),(x1,5,z1),"spruce_planks")


def salvage_camp():
    m=_base(11,"残船吊架回收营",45,43,30,True)
    _deck(m,4,5,32,16);_deck(m,9,13,32,38)
    # An open, broken hull with exposed ribs, not another roofed hall.
    m.box((22,3,11),(22,4,35),"dark_oak_log[axis=z]")
    for z in range(11,36):
        half=2 if z in (11,12,34,35) else 5
        m.box((22-half,4,z),(22+half,4,z),"dark_oak_planks")
        if z%4==0:
            for x in (22-half,22+half):
                m.box((x,5,z),(x,8+(z%3),z),"stripped_spruce_log[axis=y]")
            m.box((22-half,5,z),(22+half,5,z),"spruce_log[axis=x]")
        elif z%3:
            m.set(22-half,5,z,"dark_oak_planks");m.set(22+half,5,z,"dark_oak_planks")
    m.box((22,4,35),(22,14,35),"dark_oak_log[axis=y]")
    for x,z in ((11,12),(31,29)):
        m.box((x,0,z),(x,15,z),"spruce_log[axis=y]")
        m.box((x,15,z),(min(40,x+9),15,z),"dark_oak_log[axis=x]")
        hook=min(40,x+8);m.box((hook,8,z),(hook,14,z),"chain[axis=y]")
        m.set(hook,7,z,"barrel[facing=up]")
        for i in range(1,5):m.set(x+i,15-i,z,"spruce_stairs[facing=west]")
    _tent(m,34,12,42,20);_tent(m,34,25,42,36)
    _beds(m,"camp",35,18,n=2);_cook(m,35,35)
    m.set(36,3,27,"crafting_table");m.set(40,3,28,"lectern[facing=west]")
    _use(m,"sort","回收分类与修配",36,27,36,28)
    _use(m,"ledger","回收归属登记",40,28,39,28)
    for z in (20,26):crate_stack(m,11,3,z,3,3,2)
    m.box((6,3,30),(8,4,37),"stripped_spruce_log[axis=z]")
    _use(m,"timber","晾干回收船板",8,33,10,33,kind="storage")
    _use(m,"wreck","船肋旁回收走道",17,23,15,23)
    _entry(m,39,38,facing="south")
    m.room("wreck","开放残船与吊架",(9,3,10),(32,16,37),"残船、双吊臂、分拣箱、回收船板及环绕干道")
    m.room("tents","帆布轮休与登记棚",(35,3,13),(41,7,35),"两床、热食、手工具及回收记录")
    m.meta["terrain"]["选用"]="只用于有残船的避风岸边；吊架和船骸为静态表达，不能当作可运行起重机械"
    return _finish(m,"整栋取消封闭大屋，以露天破船骨架和双悬臂吊架为主体，侧边两张帆布棚承担轮休、登记与修配，前方桩桥接浅湾。")


def abandoned_watch():
    m=_base(12,"错层残墙旧海防哨",35,39,36)
    m.box((3,0,11),(31,6,36),"stone_bricks")
    m.box((3,0,23),(31,10,36),"cobblestone")
    _steps(m,14,7,5,2,4,"lower_steps")
    _steps(m,23,17,3,6,4,"upper_steps")
    _hall(m,5,14,15,23,f=6,wallh=3)
    _door(m,10,14,6)
    # Keep the lower truss and strip selected roof panels to expose storm damage.
    for x in range(5,11):
        for z in range(18,23):
            for y in range(12,19):
                if (x+z)%3!=0:m.set(x,y,z,"air")
    _beds(m,"oldbunk",6,21,y=7,n=1)
    m.set(13,7,20,"barrel[facing=west]");_use(m,"oldgear","封存旧装备",13,20,12,20,7,kind="storage")
    m.set(6,7,16,"lectern[facing=east]");_use(m,"log","旧值守日志",6,16,7,16,7)
    m.box((21,10,25),(29,18,35),"stone_bricks")
    m.box((22,11,26),(28,17,34),"air")
    _door(m,25,25,10)
    for x in (21,29):
        for z in (28,32):m.box((x,13,z),(x,15,z),"air")
    _steps(m,17,24,3,10,8,"tower_stair")
    m.box((19,18,32),(23,18,33),"spruce_planks")
    _hall(m,21,25,29,35,f=18,wallh=4)
    m.box((19,18,34),(20,18,34),"spruce_planks")
    m.box((19,19,34),(20,19,34),"spruce_fence[east=true,west=true,north=false,south=false]")
    for x in (21,29):m.box((x,20,27),(x,21,33),"air")
    _door(m,21,33,18,axis="z")
    m.set(27,19,32,"lectern[facing=west]");_use(m,"watch","高台海图观测",27,32,26,32,19)
    m.set(25,11,32,"barrel[facing=north]");_use(m,"tower_store","石塔旧信号用品",25,32,25,31,11,kind="storage")
    for x in (3,31):
        for z in range(11,37):
            floor=10 if z>=23 else 6
            m.box((x,floor+1,z),(x,floor+2+(z%5==0),z),"mossy_stone_bricks")
    m.box((4,11,36),(30,12,36),"cracked_stone_bricks")
    for x in range(4,14):m.box((x,11,28),(x,12+(x%4),28),"cracked_stone_bricks")
    for x,z in ((6,25),(9,26),(13,32),(27,13)):m.set(x,11 if z>=23 else 7,z,"mossy_cobblestone")
    _entry(m,16,4)
    m.room("ruin","破顶旧营房",(6,7,15),(14,11,22),"旧床、日志、工具与破损木屋顶")
    m.room("tower","残石塔底层",(22,11,26),(28,17,34),"旧信号储物与石塔内部")
    m.room("lookout","上层木观测台",(22,19,26),(28,23,34),"可由外阶及桥到达的观测台与海图桌")
    m.meta["floors"]=[dict(name="下院旧营房",y=6,max_y=11),dict(name="上院及石塔",y=10,max_y=17),dict(name="观测台",y=18,max_y=24)]
    m.meta["roof_min_y"]=11
    return _finish(m,"残损石墙沿三段高差叠起，破屋与完整可达木望台分开；下院、上院和外阶桥连接而非平地摆塔，保留旧值守用途而非新堡垒。")


BUILDERS={f"NS-{n:02d}-v01":fn for n,fn in ((1,boathouse),(2,supply_station),(4,bathhouse),(10,epic_hall),(11,salvage_camp),(12,abandoned_watch))}
