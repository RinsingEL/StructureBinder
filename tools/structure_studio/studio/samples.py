"""Hand-authored first assets; reusable primitives retain explicit room intent."""
import json

from .model import Model
from .server import CATALOG, TOOL
from .validate import validate


def pane(m, x, y, z, axis="x", color="glass"):
    links = "east=true,west=true,north=false,south=false" if axis == "x" else "east=false,west=false,north=true,south=true"
    m.set(x, y, z, f"{color}_pane[{links},waterlogged=false]")


def railing(m, a, b, wood="spruce", axis="x"):
    links = "east=true,west=true,north=false,south=false" if axis == "x" else "east=false,west=false,north=true,south=true"
    m.box(a, b, f"{wood}_fence[{links},waterlogged=false]")


def gable(m, x0, x1, z0, z1, y0, material="dark_oak", wall="white_terracotta"):
    """Full roof envelope with stair eaves; ridge runs along Z."""
    for x in range(x0, x1 + 1):
        lift = min(x-x0, x1-x)
        y = y0 + lift
        facing = "east" if x <= (x0+x1)/2 else "west"
        for z in range(z0, z1 + 1):
            m.set(x,y,z,f"{material}_stairs[facing={facing},half=bottom,shape=straight,waterlogged=false]")
        if x0 < x < x1:
            for z in (z0+1,z1-1):
                if y > y0:
                    m.box((x,y0,z),(x,y-1,z),wall)


def bakery():
    m=Model("SR-F01-v01", "站前面包铺与店主住宅", (23,23,25), family="SR-F01",
            civilization="铜钟公国", role="fill", terrain={
                "地块":"临街平地，主体地坪 Y=1，入口站位 Y=2", "资源":"服务车站步行街；烘焙需要粮食与供水",
                "接地":"主体石基覆盖 X=3..17、Z=6..20；右侧后勤小院", "保留空间":"正面 Z=0..5 为门廊与步行；不沿边界穿过房屋"})
    m.meta.update(roof_min_y=13, floors=[dict(name="一层 · 面包铺与厨房",y=1,max_y=5),dict(name="二层 · 店主住宅",y=7,max_y=11)],
                  design_notes=["砖石店面、奶油色上层、深色陡坡屋顶；院内备货，前店后厨、楼上生活。", "门前宽台阶接入街面，右侧内部楼梯连到二层。"],
                  differences=["前店后厨＋楼上双卧室；沿侧墙的内部直跑楼梯；独立后勤小院。"])
    # Complete ground/contact; omit the unused outer air so surrounding terrain is retained.
    m.box((2,0,3),(20,0,22),"stone_bricks")
    m.box((3,1,6),(17,1,20),"polished_andesite")
    m.box((3,2,6),(17,6,20),"bricks")
    m.box((4,2,7),(16,6,19),"air")
    m.box((3,7,6),(17,7,20),"spruce_planks")
    m.box((3,8,6),(17,12,20),"white_terracotta")
    m.box((4,8,7),(16,12,19),"air")
    # Interior parquet with inset runner.
    m.box((4,1,7),(16,1,19),"oak_planks")
    m.box((10,1,7),(12,1,16),"dark_oak_planks")
    for x in (3,9,11,17):
        m.box((x,2,6),(x,12,6),"stripped_spruce_log[axis=y]")
        m.box((x,8,20),(x,12,20),"stripped_spruce_log[axis=y]")
    for z in (6,13,20):
        for x in (3,17):
            m.box((x,8,z),(x,12,z),"stripped_spruce_log[axis=y]")
    for y in (7,12):
        m.box((3,y,6),(17,y,6),"dark_oak_log[axis=x]")
        m.box((3,y,20),(17,y,20),"dark_oak_log[axis=x]")
        for x in (3,17):m.box((x,y,6),(x,y,20),"dark_oak_log[axis=z]")
    # Shop windows with heavy brick sills; panes connect explicitly.
    for x0,x1 in ((4,8),(12,16)):
        for x in range(x0,x1+1):
            for y in range(3,6):pane(m,x,y,6)
        m.box((x0,2,5),(x1,2,5),"stone_brick_slab[type=bottom,waterlogged=false]")
    for x in (5,6,7,13,14,15):
        for y in (9,10):pane(m,x,y,6)
        m.set(x,8,5,"spruce_trapdoor[facing=south,half=top,open=false,powered=false,waterlogged=false]")
    for z0 in (9,16):
        for z in range(z0,z0+3):
            for y in (3,4,9,10):
                for x in (3,17):pane(m,x,y,z,axis="z")
    m.door(10,2,6,facing="north")
    m.door(17,2,18,facing="east")
    m.box((9,1,4),(11,1,5),"stone_bricks")
    for x in range(8,13):m.set(x,0,3,"stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
    # Alternating cream/ochre canvas over front; no imaginary custom blocks.
    for x in range(3,18):
        color="yellow" if x%2 else "white"
        m.box((x,6,3),(x,6,5),f"{color}_wool")
        m.set(x,5,3,f"{color}_wool")
    for x in (3,17):
        m.box((x,1,3),(x,5,3),"spruce_fence[north=true,east=false,south=false,west=false,waterlogged=false]")
        m.set(x,5,4,"lantern[hanging=true,waterlogged=false]")
    # Front sales area, kitchen dividing beam and opening.
    m.box((4,2,11),(8,2,11),"spruce_planks")
    m.set(5,3,11,"cake[bites=0]")
    m.set(7,3,11,"cake[bites=2]")
    for z in (8,9):m.set(4,2,z,"spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
    m.set(6,2,8,"spruce_fence[east=false,west=false,north=false,south=false,waterlogged=false]")
    m.set(6,3,8,"spruce_pressure_plate[powered=false]")
    for x in (5,7,13):
        m.set(x,5,9,"lantern[hanging=true,waterlogged=false]")
        m.set(x,6,9,"chain[axis=y,waterlogged=false]")
    m.box((4,2,15),(8,5,15),"stripped_spruce_log[axis=y]")
    m.box((4,3,15),(8,4,15),"air")
    for x in (5,7):
        m.set(x,2,18,"smoker[facing=north,lit=false]")
        m.set(x,3,18,"bricks")
        m.set(x,2,19,"bricks")
    m.box((5,4,18),(7,4,19),"bricks")
    m.box((6,5,19),(6,21,19),"bricks")
    m.set(6,22,19,"brick_slab[type=bottom,waterlogged=false]")
    m.set(10,2,19,"cauldron")
    m.set(11,2,19,"barrel[facing=up,open=false]")
    m.set(12,2,19,"crafting_table")
    for x in (13,14):m.set(x,2,19,"barrel[facing=north,open=false]")
    # Real stairwell: six treads, opening extends past the landing for headroom.
    m.box((15,7,9),(16,7,16),"air")
    for i in range(6):
        z=15-i;y=2+i
        for x in (15,16):
            m.set(x,y,z,"spruce_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
            if y>2:m.box((x,2,z),(x,y-1,z),"spruce_planks")
    railing(m,(14,8,9),(14,8,16),axis="z")
    m.box((15,7,7),(16,7,9),"spruce_planks")
    # Upstairs: shared front living room and two enclosed bedrooms at the rear.
    m.box((4,8,14),(14,11,14),"white_terracotta")
    m.box((10,8,15),(10,11,19),"white_terracotta")
    m.box((4,11,14),(14,11,14),"dark_oak_log[axis=x]")
    m.box((10,11,15),(10,11,19),"dark_oak_log[axis=z]")
    m.door(7,8,14,facing="north")
    m.door(12,8,14,facing="north")
    m.bed(5,8,18,color="green",facing="south")
    m.bed(12,8,18,color="yellow",facing="south")
    for x in (8,14):m.set(x,8,19,"barrel[facing=north,open=false]")
    for x in (7,13):
        m.set(x,11,17,"lantern[hanging=true,waterlogged=false]")
        m.box((x,12,17),(x,17,17),"chain[axis=y,waterlogged=false]")
    m.box((4,8,8),(4,9,11),"bookshelf")
    for x in (6,7):m.set(x,8,8,"spruce_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
    m.set(6,8,10,"spruce_fence[east=false,west=false,north=false,south=false,waterlogged=false]")
    m.set(6,9,10,"spruce_pressure_plate[powered=false]")
    m.box((11,8,8),(12,8,8),"spruce_planks")
    m.set(11,9,8,"flower_pot")
    m.set(12,9,8,"lantern[hanging=false,waterlogged=false]")
    m.set(11,8,10,"spruce_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
    # Back bedroom windows; chimney is intentionally outside bedroom footprints.
    for x in (5,6,12,13):
        for y in (9,10):pane(m,x,y,20)
    gable(m,2,18,5,21,13)
    # Timbered gables and detailed rear elevation keep the silhouette legible.
    for z in (6,20):
        m.box((10,13,z),(10,20,z),"dark_oak_log[axis=y]")
        m.box((7,15,z),(13,15,z),"dark_oak_log[axis=x]")
    for x in (4,8,12,16):
        for z in (5,21):
            m.box((x,9,z),(x,10,z),f"spruce_trapdoor[facing={'south' if z==5 else 'north'},half=bottom,open=true,powered=false,waterlogged=false]")
    for x in (4,9,14):
        m.box((x,2,21),(x,5,21),"bricks")
        m.set(x,6,21,"stone_brick_slab[type=bottom,waterlogged=false]")
    for x in (6,7,11,12):
        for y in (3,4):pane(m,x,y,20)
    # A framed attic opening, accessible attic is not claimed.
    m.box((9,14,6),(11,16,6),"dark_oak_planks")
    for y in (14,15):pane(m,10,y,6)
    # Preparation and family dining spaces have distinct furniture.
    m.box((10,2,16),(12,2,16),"spruce_planks")
    m.set(10,3,16,"flower_pot")
    m.set(12,3,16,"light_weighted_pressure_plate[power=0]")
    m.set(4,2,17,"barrel[facing=east,open=false]")
    m.set(4,3,17,"barrel[facing=east,open=false]")
    m.box((8,8,10),(9,8,10),"spruce_fence[east=true,west=true,north=false,south=false,waterlogged=false]")
    m.box((8,9,10),(9,9,10),"spruce_pressure_plate[powered=false]")
    for x in (8,9):m.set(x,8,12,"spruce_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
    m.box((5,8,16),(8,8,16),"green_carpet")
    m.box((11,8,16),(13,8,16),"yellow_carpet")
    # Side yard: retained masonry curb, water barrel, fuel stack and packing bench.
    for z in range(9,22):m.set(20,1,z,"stone_brick_wall[up=true,north=low,south=low,east=none,west=none,waterlogged=false]")
    m.box((18,1,20),(19,2,21),"spruce_log[axis=x]")
    m.set(19,1,15,"water_cauldron[level=3]")
    m.set(19,1,12,"barrel[facing=up,open=false]")
    m.set(19,2,12,"lantern[hanging=false,waterlogged=false]")
    m.room("sales","前店",(4,2,7),(13,5,14),"售卖、顾客等候与窗边座位")
    m.room("kitchen","烘焙后厨",(4,2,16),(14,5,19),"烘炉、备料、水与成品储藏")
    m.room("living","楼上起居室",(4,8,7),(13,11,13),"阅读、家庭餐桌与起居")
    m.room("bedroom_a","主卧",(4,8,15),(9,11,19),"睡眠与衣物收纳")
    m.room("bedroom_b","次卧",(11,8,15),(14,11,19),"独立客房与储物")
    m.point("front","entrance",(10,2,5),"店面入口",facing="north")
    m.point("service","entrance",(18,1,18),"后勤院门",facing="east")
    m.point("counter","work",(6,3,11),"售卖柜台",approach=(6,2,12))
    m.point("oven","work",(5,2,18),"烘焙炉",approach=(5,2,17))
    m.point("stair_bottom","circulation",(15,2,16),"楼梯下口",look_at=[15,6,11])
    m.point("stair_top","circulation",(15,8,9),"楼梯上口",look_at=[15,4,14])
    m.point("bed_a","bed",(5,8,18),"主卧床位",approach=(6,8,18))
    m.point("bed_b","bed",(12,8,18),"次卧床位",approach=(13,8,18))
    m.meta["connections"]=[dict(kind="pedestrian",pos=[10,1,3],direction="north",clearance=[3,3],note="街面高程 Y=1；通过门前台阶上到 Y=2")]
    return m


def fixture():
    m=Model("FIXTURE-01", "方块兼容性样板", (22,9,15))
    m.box((0,0,0),(21,0,14),"polished_andesite")
    for i,material in enumerate(("oak","spruce","brick","quartz")):
        x=2+i*5
        for j,facing in enumerate(("north","east","south","west")):
            m.set(x+j,1,2,f"{material}_stairs[facing={facing},half=bottom,shape=straight,waterlogged=false]")
        for j,typ in enumerate(("bottom","top","double")):
            m.set(x+j,1,4,f"{material}_slab[type={typ},waterlogged=false]")
    for x,wood in ((2,"oak"),(5,"spruce"),(8,"dark_oak")):
        m.door(x,1,7,wood=wood,facing="south")
    m.bed(11,1,7,"red","north");m.bed(14,1,7,"blue","east")
    m.set(18,1,7,"chest[facing=south,type=single,waterlogged=false]")
    railing(m,(2,1,10),(7,1,10))
    for x in range(9,15):pane(m,x,1,10)
    m.set(16,1,10,"farmland[moisture=7]");m.set(16,2,10,"wheat[age=7]")
    m.set(18,1,10,"flower_pot");m.set(20,1,10,"lantern[hanging=false,waterlogged=false]")
    m.meta["design_notes"]=["用于核对楼梯旋转、半砖、双层门、床、箱子、栅栏、玻璃、作物与灯具。"]
    return m


def build_samples():
    registry=json.loads((TOOL/".cache/registry.json").read_text(encoding="utf-8"))
    for m,base in ((fixture(),TOOL/"fixtures"),(bakery(),CATALOG/"01_steam_rail/models")):
        destination=base/m.meta["id"]
        m.export(destination,registry)
        report=validate(destination,registry)
        print(m.meta["id"],report["passed"],report["visible_blocks"],report["errors"],report["warnings"])
        if not report["passed"]:raise RuntimeError("Sample NBT validation failed")


if __name__=="__main__":build_samples()
