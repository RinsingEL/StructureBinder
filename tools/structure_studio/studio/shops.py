"""Seven authored street businesses; the bakery remains in samples.py.

Shared masonry, windows and furniture are components. Footprints, circulation,
rooms and business equipment are authored separately for each variant.
"""
from nbtlib import Byte, Compound, List, String

from .model import Model
from .samples import gable, railing
from .components import shell, window, bench, pendant, hip_roof, shelf, crate_stack


def base(number,name,size,plot,notes):
    m=Model(f"SR-F01-v{number:02d}",name,size,family="SR-F01",civilization="蒸汽铁路文明",role="fill",
            terrain={"选址":"铁路聚落步行商业街；需要对应行业的客源和补给",
                     "地块":plot,"地坪":"石基地坪 Y=1，室内脚底 Y=2；北侧台阶连接街面",
                     "保留空间":"保留门前步行与后勤开口；模板外和未写入的空格不参与清地"})
    m.meta.update(source=f"tools/structure_studio/studio/shops.py:shop({number})",roof_min_y=8,
                  floors=[dict(name="店面与工作区",y=1,max_y=6)],
                  differences=[notes],design_notes=[notes,"与铁路街区共同使用砖石、木作、金属屋面和有支撑的店招。"],
                  preview_context=dict(kind="flat",land_surface_y=1,bed_y=-1,padding=3))
    return m


def pavilion(m,x0,z0,x1,z1,*,wall="bricks",roof="gable",roofmat="dark_oak"):
    m.box((x0-1,0,z0-1),(x1+1,0,z1+1),"stone_bricks")
    shell(m,(x0,1,z0),(x1,7,z1),wall,"spruce_planks")
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,2,z),(x,7,z),"stripped_dark_oak_log[axis=y]")
    for z in (z0,z1):m.box((x0,7,z),(x1,7,z),"dark_oak_log[axis=x]")
    for x in (x0,x1):m.box((x,7,z0),(x,7,z1),"dark_oak_log[axis=z]")
    for z in range(z0+3,z1-1,6):
        for x in (x0,x1):window(m,(x,3,z),(x,5,min(z+2,z1-2)),"z")
    window(m,(x0+2,3,z1),(min(x0+5,x1-2),5,z1))
    if roof=="gable":
        gable(m,x0-1,x1+1,z0-1,z1+1,8,roofmat,wall)
        middle=(x0+x1)//2;ridge=8+min(middle-x0+1,x1+1-middle)
        for z in (z0,z1):
            m.box((middle,8,z),(middle,ridge-1,z),"dark_oak_log[axis=y]")
            m.box((middle-2,9,z),(middle+2,9,z),"dark_oak_log[axis=x]")
            window(m,(middle,10,z),(middle,11,z))
    elif roof=="hip":hip_roof(m,x0-1,x1+1,z0-1,z1+1,8,roofmat)


def frontage(m,x0,x1,z,door,*,canopy=None):
    for a,b in ((x0+1,door-2),(door+2,x1-1)):
        if a<=b:window(m,(a,3,z),(b,5,z))
    m.door(door,2,z,facing="north")
    m.box((door-1,0,z-2),(door+1,0,z-1),"stone_bricks")
    m.box((door-1,1,z-1),(door+1,1,z-1),"polished_andesite")
    for x in range(door-1,door+2):m.set(x,0,z-2,"stone_brick_stairs[facing=south]")
    if canopy:
        for x in range(x0,x1+1):
            color=canopy if x%2 else "white"
            m.box((x,6,z-2),(x,6,z-1),f"{color}_wool")
        for x in (x0,x1):
            m.box((x,0,z-2),(x,0,z-2),"stone_bricks")
            m.box((x,1,z-2),(x,5,z-2),"dark_oak_fence")
    m.point("front","entrance",(door,2,z-1),"店面入口",facing="north")
    m.meta["connections"].append(dict(kind="pedestrian",pos=[door,1,z-2],direction="north",clearance=[3,3],note="前廊台阶需衔接街面"))


def counter(m,x,z,width=4,*,name="收银与接待",key="counter"):
    m.box((x,2,z),(x+width-1,2,z),"dark_oak_planks")
    m.set(x+width-1,3,z,"light_weighted_pressure_plate[power=0]")
    m.point(key,"work",(x+1,2,z),name,approach=(x+1,2,z+1))


def sign(m,x,z,material):
    m.box((x,6,z-3),(x,6,z-1),"dark_oak_fence[north=true,south=true]")
    m.box((x,4,z-3),(x,5,z-3),material)


def grocery():
    m=base(2,"红罐杂货铺与后仓",(26,21,28),"中等纵深临街平地，东侧保留进货窄道",
           "宽店面、独立后仓与侧门进货；货架和粮袋岛台之间保留顾客通路。")
    pavilion(m,3,6,21,24,roof="hip",roofmat="waxed_cut_copper")
    frontage(m,3,21,6,15,canopy="red")
    m.box((3,2,17),(21,6,17),"white_terracotta");m.door(18,2,17,facing="north")
    counter(m,6,13,6)
    for z in (9,12):
        m.set(4,2,z,"barrel[facing=east]");m.set(4,3,z,"barrel[facing=east]")
    shelf(m,5,2,16,7)
    m.box((12,1,7),(16,1,16),"oak_planks")
    m.box((6,2,9),(10,2,9),"stripped_spruce_log[axis=x]")
    for x,goods in ((6,"hay_block"),(8,"pumpkin"),(10,"melon")):m.set(x,3,9,goods)
    for x in (6,9,12):m.set(x,2,15,"barrel[facing=up]")
    m.box((19,2,14),(20,3,15),"barrel[facing=west]")
    m.box((18,2,9),(19,2,11),"spruce_planks")
    m.set(18,3,9,"pumpkin");m.set(19,3,11,"melon")
    for x in (6,9,12):crate_stack(m,x,2,21,2,2,2)
    m.set(5,2,19,"crafting_table")
    m.door(21,2,20,facing="east")
    m.box((22,0,18),(24,0,23),"stone_bricks");m.box((22,1,18),(23,1,23),"polished_andesite")
    for z in range(19,22):m.set(24,0,z,"stone_brick_stairs[facing=west]")
    m.point("delivery","entrance",(22,2,20),"后仓进货门",facing="east")
    m.point("packing","work",(5,2,19),"分拣包装台",approach=(6,2,19))
    m.room("sales","杂货店面",(4,2,7),(20,6,16),"分区陈列、柜台与顾客动线")
    m.room("stock","独立后仓",(4,2,18),(20,6,23),"粮食杂货储藏、包装与侧门进货")
    for x,z in ((9,9),(16,14),(16,21)):pendant(m,x,6,z,9)
    m.set(5,2,21,"smoker[facing=east,lit=false]")
    m.box((5,3,21),(5,17,21),"bricks");m.set(5,18,21,"brick_slab")
    m.point("stove","work",(5,2,21),"后仓小炉",approach=(5,2,20))
    sign(m,4,6,"red_terracotta")
    return m


def hardware():
    m=base(3,"铁砧工具铺与维修小院",(34,20,29),"宽地块；东侧开放维修与装卸院落",
           "窄长店屋连接半露天维修院，室内零件后仓和独立工具工位分开。")
    pavilion(m,3,6,15,25,roofmat="stone_brick")
    frontage(m,3,15,6,10)
    m.box((16,0,5),(31,0,26),"stone_bricks");m.box((16,1,5),(30,1,26),"andesite")
    m.box((16,2,5),(30,10,26),"air")
    for x in range(17,31):
        height=8-(x-17)//5
        m.box((x,height,14),(x,height,26),"waxed_cut_copper_slab[type=bottom]")
        if x in (22,27):m.box((x,height,14),(x,height,26),"waxed_cut_copper_stairs[facing=west]")
        for z in (15,25):m.set(x,height-1,z,"dark_oak_log[axis=x]")
    for x in (18,29):
        height=8-(x-17)//5
        for z in (15,25):m.box((x,2,z),(x,height-1,z),"dark_oak_log[axis=y]")
    railing(m,(18,2,5),(22,2,5));railing(m,(26,2,5),(30,2,5))
    railing(m,(30,2,5),(30,2,13),axis="z")
    for x in range(23,26):m.set(x,0,4,"stone_brick_stairs[facing=south]")
    m.box((23,1,4),(25,1,4),"stone_brick_slab[type=bottom]")
    m.point("yard_gate","entrance",(24,2,5),"维修小院入口",facing="north")
    m.box((15,2,13),(15,4,15),"air")
    m.box((3,2,19),(15,6,19),"white_terracotta");m.door(10,2,19,facing="north")
    counter(m,5,12,4,name="工具售卖与维修接单")
    shelf(m,4,2,18,5)
    m.box((4,2,8),(7,2,8),"polished_andesite")
    m.set(5,3,8,"grindstone[face=floor,facing=south]")
    m.set(7,3,8,"lantern")
    m.box((13,2,9),(14,2,11),"barrel[facing=west]")
    m.box((10,1,7),(12,1,18),"oak_planks")
    for x in (5,8,11):crate_stack(m,x,2,23,2,1,2)
    m.set(20,2,19,"anvil[facing=north]")
    m.set(24,2,19,"grindstone[face=floor,facing=north]")
    m.set(28,2,21,"smithing_table")
    m.box((19,2,24),(23,2,24),"stripped_spruce_log[axis=x]")
    m.set(19,3,24,"lantern")
    crate_stack(m,27,2,24,2,2,2)
    m.box((18,2,10),(20,2,12),"spruce_log[axis=x]")
    m.set(19,3,11,"stonecutter[facing=east]")
    m.point("stock_cutting","work",(19,3,11),"材料修整台",approach=(21,2,11))
    m.room("sales","工具店面",(4,2,7),(14,6,18),"展示、接待和修理订单")
    m.room("parts","零件后仓",(4,2,20),(14,6,24),"工具备件与待取物品")
    m.room("repair","有顶维修院",(18,2,15),(29,5,25),"铁砧、磨轮和工匠台，开放作业通路")
    for key,pos,approach,name in (("anvil",(20,2,19),(20,2,18),"铁砧修整"),("grinder",(24,2,19),(24,2,18),"刃具磨轮"),("smithing",(28,2,21),(27,2,21),"工具装配")):
        m.point(key,"work",pos,name,approach=approach)
    for x,z in ((9,9),(10,22),(25,22)):pendant(m,x,5,z,8)
    sign(m,4,6,"iron_block")
    return m


def bookseller():
    m=base(4,"转角书报铺与阅览窗",(28,20,25),"西北街角平地；北、西两侧保留人行道",
           "两面临街入口、L 形柜台与后部阅览桌；书架围合而非前店后仓布局。")
    pavilion(m,6,6,22,20,wall="white_terracotta",roof="hip",roofmat="waxed_oxidized_cut_copper")
    m.box((2,0,2),(24,0,4),"stone_bricks");m.box((2,1,2),(24,1,4),"polished_andesite")
    m.box((2,0,5),(4,0,22),"stone_bricks");m.box((2,1,5),(4,1,22),"polished_andesite")
    frontage(m,6,22,6,17,canopy="blue")
    m.box((4,1,12),(5,1,16),"polished_andesite");m.door(6,2,14,facing="west")
    m.point("corner","entrance",(5,2,14),"西侧街角入口",facing="west")
    m.meta["connections"].append(dict(kind="pedestrian",pos=[3,2,14],direction="west",clearance=[3,3],note="另一条街的人行道连接"))
    counter(m,9,10,4,name="书报销售与订阅")
    m.box((9,2,11),(9,2,12),"dark_oak_planks")
    m.box((7,2,19),(21,4,19),"bookshelf")
    m.box((21,2,9),(21,4,17),"bookshelf")
    bench(m,11,2,16,3,"north")
    m.box((11,2,14),(13,2,14),"birch_slab[type=top]")
    m.box((16,2,14),(17,3,16),"bookshelf")
    m.box((16,4,14),(17,4,16),"dark_oak_slab[type=bottom]")
    m.box((10,1,13),(14,1,17),"dark_oak_planks")
    m.set(19,2,8,"barrel[facing=up]");m.set(19,3,8,"lantern")
    bench(m,11,2,3,3,"north")
    # A corner lantern rises from the actual masonry corner to a small copper cap.
    m.box((6,8,6),(9,13,9),"white_terracotta")
    window(m,(7,11,6),(8,12,6));window(m,(6,11,7),(6,12,8),"z")
    hip_roof(m,5,10,5,10,14,"waxed_oxidized_cut_copper")
    m.set(12,3,14,"lantern")
    m.set(7,2,10,"lectern[facing=east,has_book=false]")
    m.point("reading","work",(12,2,14),"阅读桌",approach=(12,2,15))
    m.point("papers","work",(7,2,10),"当日报刊架",approach=(8,2,10))
    m.room("shop","转角书报店",(7,2,7),(20,6,12),"报刊展示、订阅与接待")
    m.room("reading","窗边阅览区",(7,2,13),(20,6,19),"书架、长桌和座位")
    for x,z in ((13,8),(17,16)):pendant(m,x,6,z,9)
    sign(m,7,6,"bookshelf")
    return m


def cobbler():
    m=base(5,"窄巷修鞋铺",(18,18,29),"窄长街巷平地；单面进出，内部直达后场",
           "十一格宽的深铺面，前部接件、后部修补清洗及小休息角；屋顶天窗照向工作区。")
    pavilion(m,3,5,13,25,roofmat="dark_oak")
    frontage(m,3,13,5,10)
    m.box((3,2,13),(13,6,13),"white_terracotta");m.door(10,2,13,facing="north")
    counter(m,4,10,4,name="接件与交付")
    shelf(m,4,2,7,3)
    m.set(12,2,8,"spruce_stairs[facing=west]")
    m.box((7,1,6),(10,1,12),"oak_planks")
    m.box((11,2,16),(12,3,18),"barrel[facing=west]")
    m.box((4,2,17),(4,2,21),"spruce_planks")
    m.set(4,2,18,"crafting_table");m.set(4,3,20,"flower_pot")
    m.set(6,2,24,"water_cauldron[level=3]");m.set(4,2,24,"barrel[facing=east]")
    m.set(5,2,15,"barrel[facing=south]");m.set(5,3,15,"barrel[facing=south]")
    bench(m,9,2,23,2,"north")
    m.set(9,2,21,"spruce_slab[type=top]");m.set(9,3,21,"lantern")
    for x in (7,8,9):
        y=8+min(x-2,14-x)
        m.box((x,y,17),(x,y,19),"glass")
    m.point("repair","work",(4,2,18),"修补台",approach=(5,2,18))
    m.point("wash","work",(6,2,24),"清洗与整理",approach=(6,2,23))
    m.room("sales","接件前店",(4,2,6),(12,6,12),"鞋靴接件、交付和待取架")
    m.room("work","修补后场",(4,2,14),(12,6,24),"工作台、清洗、原料与休息角")
    pendant(m,9,5,8,8);pendant(m,10,5,17,8)
    sign(m,4,5,"brown_terracotta")
    return m


def tailor():
    m=base(6,"折院裁缝铺",(28,22,30),"L 形平地；内凹院角保留给邻地或后勤",
           "横向陈列店面连接后伸裁剪坊；试衣角、布料堆架和三处织作台各有空间。")
    pavilion(m,3,5,23,15,wall="white_terracotta",roof="hip",roofmat="brick")
    pavilion(m,3,15,13,26,wall="white_terracotta",roofmat="dark_oak")
    frontage(m,3,23,5,18,canopy="green")
    m.box((9,2,15),(11,4,15),"air")
    counter(m,14,10,4,name="量体接待与交衣")
    for i,color in enumerate(("green","yellow","red","blue")):
        m.set(5+i,2,8,f"{color}_wool");m.set(5+i,3,8,f"{color}_carpet")
    shelf(m,5,2,13,5,contents="white_wool")
    m.box((11,2,8),(11,2,11),"birch_planks")
    for z,color in ((8,"light_blue"),(10,"pink")):m.set(11,3,z,color+"_carpet")
    m.box((13,1,7),(17,1,13),"dark_oak_planks")
    m.set(21,2,8,"spruce_stairs[facing=west]")
    m.box((19,2,12),(19,5,14),"green_wool")
    m.box((20,2,14),(22,2,14),"spruce_slab[type=top]")
    m.set(22,2,12,"barrel[facing=north]")
    m.point("fitting","circulation",(21,2,13),"试衣角",look_at=[20,3,14])
    m.box((6,2,18),(10,2,19),"spruce_planks")
    m.box((6,3,18),(10,3,18),"white_carpet")
    shelf(m,4,2,16,3,contents="white_wool")
    m.box((11,2,21),(12,3,22),"barrel[facing=west]")
    for x in (4,7,10):m.set(x,2,24,"loom[facing=north]")
    for x in (4,7):m.point(f"loom_{x}","work",(x,2,24),"织作与整理台",approach=(x,2,23))
    m.point("cutting","work",(8,2,18),"裁剪台",approach=(8,2,17))
    m.room("sales","布料与接待",(4,2,6),(18,6,14),"布料陈列、订单与交衣")
    m.room("fitting","试衣角",(20,2,11),(22,5,14),"遮挡与衣物暂存")
    m.room("sewing","裁剪织作坊",(4,2,16),(12,6,25),"裁剪长桌、织机和整理")
    for x,z in ((12,9),(20,10),(8,21)):pendant(m,x,6,z,9)
    sign(m,4,5,"green_wool")
    return m


def florist():
    m=base(7,"玻璃花草铺与换盆间",(29,19,24),"较浅宽地块；温室需要采光，街前保留种植展示角",
           "透明人字顶花房、砖砌换盆附房和室外花畦；种植展示与工作通路分离。")
    pavilion(m,3,6,15,19,wall="white_terracotta",roof="none")
    for x in (3,15):window(m,(x,3,7),(x,6,18),"z")
    window(m,(4,3,19),(14,6,19))
    for x in range(2,17):
        y=8+min(x-2,16-x)
        for z in range(5,21):m.set(x,y,z,"dark_oak_planks" if z%5==0 else "glass")
        for z in (6,19):
            if y>8:m.box((x,8,z),(x,y-1,z),"glass")
    for z in (6,10,15,19):
        for x in (3,15):m.box((x,2,z),(x,7,z),"dark_oak_log[axis=y]")
    pavilion(m,16,11,25,19,roof="hip",roofmat="waxed_cut_copper")
    frontage(m,3,15,6,8)
    m.box((15,2,15),(16,4,17),"air")
    m.box((17,0,4),(25,0,9),"stone_bricks");m.box((17,1,4),(25,1,9),"polished_andesite")
    m.box((19,1,5),(23,1,7),"dirt")
    for x in range(19,24):
        for z in (5,7):m.set(x,2,z,"allium" if x%2 else "cornflower")
    for z in range(8,18,2):
        m.set(4,2,z,"spruce_slab[type=top]")
        m.set(4,3,z,"potted_fern" if z%4 else "potted_dandelion")
    m.box((11,2,9),(12,2,14),"birch_planks")
    for x,z,plant in ((11,10,"poppy"),(12,12,"blue_orchid"),(11,14,"azure_bluet")):
        m.set(x,3,z,"potted_"+plant)
    for x,z,plant in ((11,9,"fern"),(12,10,"allium"),(11,12,"cornflower"),(12,14,"lily_of_the_valley")):
        m.set(x,3,z,"potted_"+plant)
    m.box((6,1,14),(7,1,16),"dirt")
    for x in (6,7):
        for z in (14,16):m.set(x,2,z,"azalea")
    m.box((6,1,8),(9,1,12),"oak_planks")
    m.set(14,2,8,"moss_block");m.set(14,3,8,"flowering_azalea")
    counter(m,9,17,4,name="花草售卖与订单")
    shelf(m,18,2,18,5,contents="flower_pot")
    m.set(24,2,16,"crafting_table");m.set(23,2,12,"water_cauldron[level=3]")
    m.set(20,2,12,"composter[level=7]")
    m.point("potting","work",(24,2,16),"换盆工作台",approach=(23,2,16))
    m.point("water","work",(23,2,12),"浇灌取水",approach=(23,2,13))
    m.room("greenhouse","玻璃花房",(4,2,7),(14,7,18),"采光陈列、花草售卖与顾客通路")
    m.room("potting","换盆附房",(17,2,12),(24,6,18),"盆器、换盆、堆肥与取水")
    pendant(m,8,6,12,12);pendant(m,21,6,15,8)
    sign(m,4,6,"moss_block")
    return m


def apothecary():
    m=base(8,"药草铺与书房小宅",(24,25,28),"纵深商住地块；上层住宅由室内楼梯连接",
           "前部售卖、后部调配、侧墙内部楼梯；二层起居、独立卧室与配方书房。")
    pavilion(m,3,6,19,23,roof="none")
    shell(m,(3,7,6),(19,12,23),"white_terracotta","dark_oak_planks")
    for x in (3,11,19):
        for z in (6,23):m.box((x,8,z),(x,12,z),"dark_oak_log[axis=y]")
    for z in (6,14,23):
        for x in (3,19):m.box((x,8,z),(x,12,z),"dark_oak_log[axis=y]")
    for x in (5,6,7,13,14,15):
        for z in (6,23):window(m,(x,9,z),(x,10,z))
    for z in (9,18):
        for x in (3,19):window(m,(x,9,z),(x,10,z+2),"z")
    frontage(m,3,19,6,12,canopy="purple")
    gable(m,2,20,5,24,13,"dark_oak","white_terracotta")
    m.meta.update(roof_min_y=13,floors=[dict(name="药草售卖与调配",y=1,max_y=6),dict(name="住宅与配方书房",y=7,max_y=11)])
    m.box((3,2,14),(15,6,14),"white_terracotta");m.door(12,2,14,facing="north")
    counter(m,6,11,5,name="药草售卖")
    m.box((11,1,7),(13,1,13),"oak_planks")
    m.box((4,2,12),(4,4,13),"barrel[facing=east]")
    m.box((14,2,9),(15,2,11),"birch_planks")
    for z,plant in ((9,"fern"),(11,"red_mushroom")):m.set(14,3,z,"potted_"+plant)
    shelf(m,4,2,8,4,contents="bookshelf")
    m.box((5,2,21),(9,2,21),"polished_andesite")
    bottles=List[Compound]([Compound({"Slot":Byte(slot),"Count":Byte(1),"id":String("minecraft:potion"),
                                    "tag":Compound({"Potion":String("minecraft:water")})}) for slot in (0,2)])
    m.set(6,3,21,"brewing_stand[has_bottle_0=true,has_bottle_1=false,has_bottle_2=true]",
          {"id":String("minecraft:brewing_stand"),"Items":bottles})
    m.set(9,3,21,"potted_fern");m.set(4,2,18,"water_cauldron[level=3]")
    shelf(m,10,2,22,5)
    m.point("brewing","work",(6,3,21),"药草调配台",approach=(6,2,20))
    m.box((17,7,12),(18,7,20),"air")
    for i in range(6):
        y=2+i;z=19-i
        for x in (17,18):
            m.set(x,y,z,"spruce_stairs[facing=north]")
            if y>2:m.box((x,2,z),(x,y-1,z),"spruce_planks")
    m.box((17,7,12),(18,7,13),"dark_oak_planks")
    railing(m,(16,8,14),(16,8,20),axis="z")
    m.point("stair_bottom","circulation",(17,2,20),"楼梯下口",look_at=[17,6,15])
    m.point("stair_top","circulation",(17,8,13),"楼梯上口",look_at=[17,4,18])
    m.box((4,8,14),(15,11,14),"white_terracotta")
    m.box((11,8,15),(11,11,22),"white_terracotta")
    m.door(8,8,14,facing="north");m.door(13,8,14,facing="north")
    m.bed(5,8,20,color="purple",facing="south")
    m.set(9,8,21,"barrel[facing=north]");m.box((5,8,17),(9,8,17),"purple_carpet")
    m.box((4,8,8),(4,10,11),"bookshelf")
    bench(m,7,8,9,3,"south")
    m.box((7,8,11),(9,8,11),"spruce_slab[type=top]")
    m.set(9,9,11,"potted_fern")
    m.set(13,8,8,"water_cauldron[level=3]");m.set(14,8,8,"smoker[facing=south,lit=false]")
    m.set(15,8,8,"barrel[facing=south]");m.set(15,9,8,"lantern")
    m.point("cooking","work",(14,8,8),"家用炉与洗漱",approach=(14,8,9))
    for z in (6,23):
        m.box((3,12,z),(19,12,z),"dark_oak_log[axis=x]")
        m.box((11,13,z),(11,20,z),"dark_oak_log[axis=y]")
        m.box((7,15,z),(15,15,z),"dark_oak_log[axis=x]")
        window(m,(10,16,z),(12,17,z))
    m.set(13,8,20,"lectern[facing=north,has_book=false]")
    m.box((12,8,22),(15,10,22),"bookshelf")
    m.point("bed","bed",(5,8,20),"店主床位",approach=(6,8,20))
    m.point("study","work",(13,8,20),"配方阅读桌",approach=(13,8,19))
    m.room("sales","药草前店",(4,2,7),(15,6,13),"药草与配方售卖")
    m.room("mixing","调配后场",(4,2,15),(15,6,22),"调配、水与原料存储")
    m.room("living","上层起居",(4,8,7),(15,11,13),"阅读与起居桌")
    m.room("bedroom","店主卧室",(4,8,15),(10,11,22),"床位与衣物存放")
    m.room("study","配方书房",(12,8,15),(15,11,22),"书架与配方台")
    for x,y,z in ((11,5,9),(12,5,18),(12,11,10),(8,11,19),(14,11,17)):pendant(m,x,y,z,13)
    sign(m,4,6,"purple_terracotta")
    return m


def shop(number):
    return {2:grocery,3:hardware,4:bookseller,5:cobbler,6:tailor,7:florist,8:apothecary}[number]()
