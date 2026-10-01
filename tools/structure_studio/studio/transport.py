"""First core transit assets: explicit passenger/cargo plans and site edges."""
import math

from .model import Model
from .components import arch_front,bench,column,crate_stack,hip_roof,pendant,shelf,shell,window


def steam_station():
    m=Model("SR-01-v01","铜钟总站 · 客货终到站",(55,29,49),family="SR-01",civilization="蒸汽朋克",role="key",terrain={
        "选址":"有实际铁路终到需求的平缓交通节点；线路仅从 +Z 侧接入", "入口高程":"站前地面脚底 Y=1，站房与站台 Y=2；站前宽阶衔接",
        "运输":"双股道终到线；左侧售票候车，右侧行包和货运院，旅客不穿越运行股道", "接地":"实体站房石基与站台承台；整平范围限所建站坪",
        "保留空间":"+Z 两条线路净空、-Z 站前步行、东侧货运院；不放入无线路支撑的普通住宅地块"})
    m.meta.update(roof_min_y=9,floors=[dict(name="站房 · 站台与货运院",y=1,max_y=5)],
        design_notes=["砖石侧翼围合挑高售票大厅，铜钟塔标示主入口；铆接感深色拱肋托起玻璃列车棚。", "股道在站房后端终止；旅客沿站台与横向前厅活动，货物经东侧独立装卸院。"],
        differences=["终到站：两条直线股道、三个连续站台、前端横向连廊、独立货运院。"],
        source="tools/structure_studio/studio/transport.py:steam_station")
    # Site pads retain an explicit rail corridor rather than a generic building box.
    m.box((2,0,2),(45,0,23),"stone_bricks")
    m.box((3,1,4),(44,1,22),"polished_andesite")
    m.box((45,0,5),(52,0,22),"stone_bricks")
    m.box((45,1,6),(51,1,21),"polished_andesite")
    m.box((3,0,23),(45,0,48),"gravel")
    m.box((3,2,23),(45,18,48),"air")
    for a,b in ((3,14),(20,32),(38,45)):
        m.box((a,1,23),(b,1,47),"stone_bricks")
        for edge in (a,b):m.box((edge,1,23),(edge,1,47),"yellow_terracotta")
    for x in (17,35):
        m.box((x-1,0,23),(x+1,0,48),"coarse_dirt")
        for z in range(23,49):
            if z%2==1:m.box((x-1,0,z),(x+1,0,z),"dark_oak_log[axis=x]")
            m.set(x,1,z,"rail[shape=north_south,waterlogged=false]")
        m.box((x-1,1,22),(x+1,2,22),"polished_blackstone_bricks")
        m.set(x,3,22,"ochre_froglight[axis=y]")
    shell(m,(4,1,5),(18,7,20),"bricks",floor="spruce_planks",ceiling="dark_oak_planks")
    shell(m,(30,1,5),(43,7,20),"bricks",floor="spruce_planks",ceiling="dark_oak_planks")
    shell(m,(19,1,5),(29,10,21),"bricks",floor="polished_andesite")
    m.box((18,2,12),(19,4,15),"air")
    m.box((29,2,12),(30,4,15),"air")
    m.box((22,2,21),(26,5,21),"air")
    # Central paving runner and platform opening.
    for x in range(20,29):
        for z in range(6,21):
            if (x+z)%2==0:m.set(x,1,z,"smooth_stone")
    m.box((22,1,2),(26,1,4),"polished_andesite")
    for x in range(21,28):m.set(x,0,1,"stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
    arch_front(m,21,2,5,7,8,"smooth_quartz")
    # Brick pilasters, strong horizontal cornice, tall industrial glazing.
    for x in (4,10,18,19,29,30,36,43):
        column(m,x,4,1,7,"stone_bricks","chiseled_stone_bricks")
    for x in range(4,44):m.set(x,8,4,"stone_brick_slab[type=top,waterlogged=false]")
    for x0 in (5,12,31,38):
        window(m,(x0,3,5),(x0+3,5,5))
        m.box((x0,2,4),(x0+3,2,4),"smooth_quartz_slab[type=top,waterlogged=false]")
    for x in (4,43):
        for z in (8,14):window(m,(x,3,z),(x,5,z+3),axis="z")
    for x0 in (6,12,32,38):window(m,(x0,3,20),(x0+3,5,20))
    m.door(11,2,20,wood="dark_oak",facing="south",hinge="left")
    m.door(12,2,20,wood="dark_oak",facing="south",hinge="right")
    # Tall hall fanlight, crosshead clock tower, copper tiered roofs.
    window(m,(22,8,5),(26,10,5),color="light_blue_stained_glass")
    hip_roof(m,3,19,4,21,9,tiers=5)
    hip_roof(m,29,44,4,21,9,tiers=5)
    hip_roof(m,18,30,4,22,11,tiers=5)
    shell(m,(21,12,6),(27,23,12),"stone_bricks",floor="stone_bricks",ceiling="stone_bricks")
    for x in (21,27):
        for z in (6,12):m.box((x,13,z),(x,24,z),"polished_andesite")
    for z in (5,13):
        m.box((22,18,z),(26,22,z),"gold_block")
        m.box((23,18,z),(25,22,z),"white_concrete")
        m.box((22,19,z),(26,21,z),"white_concrete")
        m.set(24,20,z,"black_concrete");m.set(24,21,z,"black_concrete");m.set(25,20,z,"black_concrete")
    hip_roof(m,20,28,5,13,25,material="waxed_oxidized_cut_copper",tiers=3)
    m.set(24,28,9,"lightning_rod[facing=up,powered=false,waterlogged=false]")
    # Ticketing: two staffed counters, secure back office, storage and waiting seats.
    m.box((14,2,8),(14,2,13),"dark_oak_planks")
    m.set(14,3,9,"light_weighted_pressure_plate[power=0]")
    m.set(14,3,12,"light_weighted_pressure_plate[power=0]")
    shelf(m,6,2,19,4,contents="bookshelf")
    m.box((5,2,16),(8,2,16),"dark_oak_planks")
    m.set(6,2,16,"lectern[facing=north,has_book=false,powered=false]")
    m.set(8,3,16,"lantern[hanging=false,waterlogged=false]")
    for z in (8,12):bench(m,7,2,z,3,facing="east")
    for z in (8,17):
        bench(m,21,2,z,2,facing="south" if z==8 else "north")
        bench(m,26,2,z,2,facing="south" if z==8 else "north")
    for x,z in ((9,10),(9,17),(35,10),(35,17)):pendant(m,x,6,z,8)
    for z in (9,16):pendant(m,24,8,z,14)
    # Enclosed station office beside an open baggage room; cargo has an east exit.
    m.box((38,2,6),(38,6,19),"white_terracotta")
    m.door(38,2,15,wood="dark_oak",facing="west")
    m.door(43,2,15,wood="dark_oak",facing="east")
    m.box((30,2,17),(30,4,19),"air")
    crate_stack(m,32,2,7,3,3,2);crate_stack(m,32,2,16,3,3,2)
    m.box((35,2,11),(36,2,13),"spruce_planks")
    m.set(36,3,12,"stone_pressure_plate[powered=false]")
    m.box((40,2,8),(42,2,8),"dark_oak_planks")
    m.set(41,2,8,"lectern[facing=south,has_book=false,powered=false]")
    m.set(40,3,8,"lantern[hanging=false,waterlogged=false]")
    m.set(41,2,10,"dark_oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
    shelf(m,40,2,18,3,contents="bookshelf")
    # Covered freight yard beside its real station door.
    for x,z in ((46,7),(51,7),(46,20),(51,20)):column(m,x,z,2,6,"stripped_spruce_log","spruce_planks")
    m.box((45,7,6),(52,7,21),"dark_oak_slab[type=top,waterlogged=false]")
    crate_stack(m,47,2,8,3,3,3);crate_stack(m,47,2,18,3,2,2)
    # Barrel-vault train shed. Glass skin and structural ribs follow the same curve.
    for x in range(3,46):
        roof_y=9+round(8*math.sqrt(max(0,1-((x-24)/21)**2)))
        for z in range(23,48):
            m.set(x,roof_y,z,"polished_deepslate" if z in (23,29,35,41,47) else "light_blue_stained_glass")
    for z in (23,29,35,41,47):
        for x in (3,45):column(m,x,z,2,9,"polished_deepslate","chiseled_deepslate")
        for x in (8,26,41):pendant(m,x,7,z,9+round(8*math.sqrt(max(0,1-((x-24)/21)**2))))
    for z in (27,34,41):
        bench(m,7,2,z,4,facing="south")
        bench(m,24,2,z,5,facing="north")
        bench(m,40,2,z,3,facing="south")
    for x in (11,30,42):m.set(x,2,45,"barrel[facing=up,open=false]")
    # Purpose-labelled spaces and actual stand positions.
    m.room("hall","主候车大厅",(20,2,6),(28,10,20),"站前入口、候车、两翼分流与站台横向联系")
    m.room("ticket","售票与票务间",(5,2,6),(17,7,19),"售票柜台、账务、票据储存与等候")
    m.room("baggage","行李寄存与理货",(31,2,6),(37,7,19),"称量、行李存放、理货桌")
    m.room("office","站务办公室",(39,2,6),(42,7,19),"值班、调度记录、档案")
    m.room("freight","东侧货运雨棚",(46,2,7),(51,6,20),"陆侧运货、短时堆放与装卸")
    m.room("platforms","玻璃棚站台",(4,2,23),(44,8,46),"三座连续站台与双股道终到端；连接点单独标记")
    m.point("front","entrance",(24,2,4),"站前主入口",facing="north")
    m.point("cargo","entrance",(50,2,15),"陆侧货运入口",facing="east")
    m.point("ticket_1","work",(14,2,9),"售票柜台",approach=(15,2,9))
    m.point("ticket_staff","work",(14,2,12),"票务工作人员",approach=(13,2,12))
    m.point("scale","work",(36,2,12),"行包称量",approach=(37,2,12))
    m.point("dispatcher","work",(41,2,8),"站务桌",approach=(41,2,9))
    m.point("platform_1","circulation",(12,2,38),"一站台",look_at=[17,2,38])
    m.point("platform_2","circulation",(22,2,38),"二站台",look_at=[17,2,38])
    m.point("platform_3","circulation",(39,2,38),"三站台",look_at=[35,2,38])
    m.meta["connections"]=[dict(kind="rail",pos=[x,1,48],direction="south",clearance=[3,6],note="本体股道止于 Z=23；外接线路需同高程与轴线") for x in (17,35)]
    m.meta["connections"].append(dict(kind="pedestrian",pos=[24,1,1],direction="north",clearance=[5,4],note="站前台阶衔接街面"))
    return m


def passenger_quay():
    m=Model("WT-01-v01","蓝帆水门 · 客运码头",(45,23,43),family="WT-01",civilization="地中海",role="key",terrain={
        "选址":"平缓河岸或避风内港，岸线沿 X，水域位于 +Z；仅用于足够深、宽的可停靠水面",
        "高程":"陆侧与木码头脚底 Y=4；参考水面 Y=3；桩脚到 Y=0，需要浅岸河床继续承接",
        "功能":"陆侧售票候船，侧翼站务与行李；中央登船廊通往两条栈桥；右岸小吊机处理行包",
        "支撑":"石砌岸台只到 Z=20；水侧保持桩架、横梁和码头开口，不填平河道",
        "保留空间":"两条栈桥之间及 +Z 端为航行与靠泊空间，不能塞入沿街填充池"})
    m.meta.update(roof_min_y=11,floors=[dict(name="候船厅与临水栈桥",y=3,max_y=7)],
        design_notes=["浅色拱廊、蓝绿檐口与橙陶屋面围合面向水面的候船庭院。", "两条桩架栈桥之间留出水道，蓝白帆布廊遮雨；岸侧石基与水侧木桩有明确分界。"],
        differences=["双栈桥浅岸客运码头；中部候船，东翼行李站务，临水遮雨连廊。"],
        ground_plane=dict(y=4,note="陆侧街面与木码头顶面脚底Y=4；参考水面Y=3、浅岸河床支撑另行核对。"),
        preview_context=dict(kind="shore",shore_z=21,land_surface_y=4,water_surface_y=3,bed_y=-1,padding=5),
        source="tools/structure_studio/studio/transport.py:passenger_quay")
    # Shore-contact geometry and quay wall; water is environment, not baked in.
    m.box((3,0,2),(41,3,20),"sandstone")
    m.box((3,3,2),(41,3,20),"smooth_sandstone")
    m.box((3,1,20),(41,2,20),"cut_sandstone")
    for x in range(4,42,5):column(m,x,20,0,3,"stone_bricks","smooth_sandstone")
    shell(m,(5,3,5),(25,10,17),"white_terracotta",floor="birch_planks",ceiling="birch_planks")
    shell(m,(28,3,5),(39,9,16),"white_terracotta",floor="birch_planks",ceiling="birch_planks")
    for x in range(5,26):
        m.set(x,4,5,"cyan_terracotta");m.set(x,10,5,"cyan_terracotta")
        m.set(x,4,17,"cyan_terracotta");m.set(x,10,17,"cyan_terracotta")
    for x in (5,25):
        m.box((x,4,5),(x,10,17),"smooth_sandstone")
    # Three large north windows surround the stepped entrance arch.
    for x0 in (7,19):window(m,(x0,6,5),(x0+3,8,5),color="light_blue_stained_glass")
    arch_front(m,12,4,5,7,7,"smooth_sandstone")
    for x0 in (5,12,18,25):
        column(m,x0,4,4,10,"smooth_sandstone","chiseled_sandstone")
    m.box((12,3,2),(18,3,4),"cut_sandstone")
    # The water facade is a real open arcade, not a sealed panorama window.
    for x in (6,12,18):arch_front(m,x,4,17,7,7,"smooth_sandstone")
    for z in (7,12):window(m,(5,6,z),(5,8,z+2),axis="z")
    hip_roof(m,4,26,4,18,12,material="brick",tiers=5)
    hip_roof(m,27,40,4,17,11,material="dark_prismarine",tiers=5)
    for x in range(4,27):
        m.set(x,11,4,"dark_prismarine_slab[type=top,waterlogged=false]")
        m.set(x,11,18,"dark_prismarine_slab[type=top,waterlogged=false]")
    # Ticket niche, luggage racks and facing waiting benches.
    m.box((6,4,11),(10,4,11),"birch_planks")
    m.set(7,5,11,"light_weighted_pressure_plate[power=0]")
    m.set(9,5,11,"flower_pot")
    shelf(m,6,4,15,4,"birch",contents="bookshelf")
    for z in (8,13):bench(m,15,4,z,6,facing="south" if z==8 else "north",wood="birch")
    for x,z in ((9,8),(15,10),(22,10)):pendant(m,x,9,z,11)
    m.box((22,4,7),(23,5,9),"barrel[facing=north,open=false]")
    # Landward service wing: an actual door and partition separate crew and cargo.
    window(m,(30,6,5),(32,8,5));window(m,(35,6,5),(37,8,5))
    m.door(28,4,11,wood="birch",facing="west")
    m.door(34,4,16,wood="birch",facing="south")
    m.box((34,4,6),(34,8,15),"cyan_terracotta")
    m.door(34,4,11,wood="birch",facing="east")
    m.box((35,4,7),(38,4,7),"birch_planks")
    m.set(36,4,7,"lectern[facing=south,has_book=false,powered=false]")
    m.set(38,5,7,"lantern[hanging=false,waterlogged=false]")
    m.set(36,4,9,"birch_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
    shelf(m,35,4,14,3,"birch",contents="bookshelf")
    crate_stack(m,30,4,7,3,2,2);crate_stack(m,30,4,13,2,2,2)
    pendant(m,31,8,10,10)
    # Pier deck and cross-walk. Omitted below-deck cells retain existing water.
    m.box((5,3,21),(36,3,25),"spruce_planks")
    for x0,x1 in ((6,12),(29,35)):
        m.box((x0,3,26),(x1,3,40),"spruce_planks")
        m.box((x0,4,26),(x1,10,40),"air")
        for z in (22,28,34,40):
            for x in (x0,x1):
                m.box((x,0,z),(x,4,z),"stripped_dark_oak_log[axis=y]")
                m.set(x,5,z,"lantern[hanging=false,waterlogged=false]")
            m.box((x0,2,z),(x1,2,z),"dark_oak_log[axis=x]")
        for z in range(26,40):
            if z in (28,34) or 30<=z<=32:continue
            for x in (x0,x1):
                m.set(x,4,z,"spruce_fence[east=false,west=false,north=true,south=true,waterlogged=false]")
        # Docking openings are at Z=30..32 on both sides, kept free of fences.
        for x in (x0,x1):m.set(x,4,31,"stone_button[face=floor,facing=east,powered=false]")
    # Covered boarding promenade with a striped sail canopy.
    for x in (6,12,18,24,30,35):
        for z in (21,25):column(m,x,z,4,7,"stripped_birch_log","birch_planks")
    for x in range(5,37):
        for z in range(20,27):
            m.set(x,8+(1 if z in (22,23,24) else 0),z,"white_wool" if x%4<2 else "light_blue_wool")
    for x in (9,21,33):pendant(m,x,7,23,9)
    # Small manual baggage crane. It has a grounded mast, reach and suspended hook.
    m.box((40,4,17),(40,12,17),"stripped_dark_oak_log[axis=y]")
    m.box((35,12,17),(41,12,17),"dark_oak_log[axis=x]")
    m.box((35,8,17),(35,11,17),"chain[axis=y,waterlogged=false]")
    m.set(35,7,17,"grindstone[face=ceiling,facing=north]")
    m.set(40,4,18,"stonecutter[facing=south]")
    # Dock signal mast and blue pennant; no lighthouse interior is implied.
    m.box((4,4,19),(4,18,19),"stripped_birch_log[axis=y]")
    m.box((4,18,19),(8,18,19),"birch_fence[east=true,west=true,north=false,south=false,waterlogged=false]")
    m.box((5,15,19),(7,17,19),"blue_wool")
    m.box((8,16,19),(9,17,19),"white_wool")
    m.set(4,19,19,"lantern[hanging=false,waterlogged=false]")
    m.room("waiting","拱廊候船厅",(6,4,6),(24,10,16),"售票、行李寄存、双向候船座位和面水拱廊")
    m.room("baggage","行李间",(29,4,6),(33,9,15),"暂存、搬运与遮雨交接")
    m.room("harbormaster","站务室",(35,4,6),(38,9,15),"班次记录、档案与值守")
    m.room("promenade","登船遮雨廊",(6,4,21),(35,7,25),"陆水衔接与两条栈桥分流")
    m.point("land","entrance",(15,4,4),"陆侧入口",facing="north")
    m.point("ticket","work",(7,4,11),"水路售票",approach=(7,4,10))
    m.point("register","work",(36,4,7),"船班登记",approach=(36,4,8))
    m.point("luggage","storage",(31,4,13),"行李架",approach=(31,4,12))
    m.point("crane","work",(40,4,18),"行包小吊机",approach=(39,4,18))
    m.point("west_pier","circulation",(9,4,37),"西栈桥",look_at=[9,4,27])
    m.point("east_pier","circulation",(32,4,37),"东栈桥",look_at=[32,4,27])
    m.meta["connections"]=[dict(kind="pedestrian",pos=[15,4,2],direction="north",clearance=[5,4],note="岸侧街面需同高程"),
        dict(kind="boat",pos=[13,3,31],direction="east",clearance=[12,5],note="参考水面 Y=3；泊位在双栈桥之间，外部交通另行接入"),
        dict(kind="boat",pos=[28,3,31],direction="west",clearance=[12,5],note="只标记靠泊开口与水侧净空，不创建运输逻辑")]
    return m
