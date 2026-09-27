"""Linear textile-roof bazaar, authored from the user's September reference."""
from .model import Model


def spice_market():
    m = Model("DS-06-v01", "六帆香料市集厅", (43, 21, 57), family="DS-06",
              civilization="沙漠", role="key", terrain={
                  "选址": "宽阔平缓商业地块，前方连接人行商街，后侧必须保留独立补货通路",
                  "地基": "43×57 格完整支撑；场坪顶面 y=1，出入口脚底 y=2",
                  "配套": "六类铺位、称量账房、双货仓、后侧装卸院及公共饮水休息点"})
    m.meta.update(source="tools/structure_studio/studio/desert_market.py:spice_market",
                  roof_min_y=8, floors=[dict(name="交易街与装卸层", y=2, max_y=7)],
                  design_notes=["参考图左案：南北线性商街以高起木架织物棚统领，拒绝围院盒子与双穹顶。",
                                "六种业态的低摊棚夹持宽街，后段双货仓分列，街尾通向装卸院。",
                                "连续承檐梁、逐级木肋支撑每片织物；前后均为无台阶进出。"],
                  differences=["纵向高棚、低摊与后仓形成三层轮廓；六类业态有独立陈设和补货站位。"],
                  preview_context=dict(kind="flat", land_surface_y=2, bed_y=-2, padding=5, surface="sand"))
    m.box((1, 0, 1), (41, 1, 55), "sandstone")
    m.box((1, 2, 1), (41, 20, 55), "air")
    for x in range(1, 42):
        for z in range(1, 56):
            m.set(x, 1, z, "smooth_sandstone" if 16 <= x <= 26 else
                  "cut_sandstone" if x in (7, 15, 27, 35) or z in (10, 45, 50) else "sandstone")
    # A low, broken perimeter frames the site without becoming a fortress.
    for x in (1, 41):
        m.box((x, 2, 5), (x, 2, 53), "cut_sandstone")
    for x0, x1 in ((1, 15), (27, 41)):
        m.box((x0, 2, 55), (x1, 2, 55), "cut_sandstone")

    def post(x, z, top):
        m.box((x, 2, z), (x, 2, z), "cut_sandstone")
        m.box((x, 3, z), (x, top, z), "stripped_dark_oak_log[axis=y]")

    # Narrow ridged hall: the roof steps up towards the central ridge.
    # The ribs are directly beneath the cloth, connected to continuous eave beams.
    colors = ("yellow", "orange", "red", "blue", "green", "orange")
    for x in range(14, 29):
        h = 16 - abs(x - 21) // 2
        for z in range(12, 43):
            band = min(5, (z - 12) // 5)
            color = "white" if (z - 12) % 5 == 0 else colors[band]
            m.set(x, h, z, color + "_wool")
    for x in (14, 28):
        m.box((x, 11, 12), (x, 12, 42), "dark_oak_planks")
    m.box((21, 17, 11), (21, 17, 43), "dark_oak_slab[type=bottom]")
    for z in (12, 17, 22, 27, 32, 37, 42):
        for x in (14, 28):
            post(x, z, 12)
            m.set(x, 10, z, "dark_oak_fence")
        for x in range(14, 29):
            h = 16 - abs(x - 21) // 2
            m.set(x, h, z, "dark_oak_planks")
            m.set(x, h - 1, z, "stripped_dark_oak_log[axis=x]")
        m.box((21, 13, z), (21, 15, z), "dark_oak_fence")
        m.set(21, 12, z, "lantern[hanging=true]")
    # Entrance timber bents have no beam across the walker's head height.
    for x in (14, 28):
        post(x, 7, 10)
        m.box((x, 9, 7), (x, 9, 12), "stripped_dark_oak_log[axis=z]")
        m.box((x, 8, 7), (x, 9, 9), "yellow_wool" if x == 14 else "red_wool")

    # Each open shop is lower than the central roof and has a distinct footprint.
    goods = ["香料", "粮食", "布匹", "饮水", "染料", "果蔬"]
    for i, (left, z0) in enumerate(((True, 13), (False, 13), (True, 22),
                                    (False, 22), (True, 31), (False, 31))):
        x0, x1 = (5, 13) if left else (29, 37)
        z1 = z0 + (6, 7, 6, 7, 6, 7)[i]
        outer, counter = (5, 12) if left else (37, 30)
        # Counter runs along the main street; shopkeepers enter from each end.
        m.box((outer, 2, z0), (outer, 5, z1), "smooth_sandstone")
        for z in (z0, z1):
            post(x0, z, 7)
            post(x1, z, 7)
            m.box((x0, 7, z), (x1, 7, z), "stripped_acacia_log[axis=x]")
        for x in (x0, x1):
            m.box((x, 7, z0), (x, 7, z1), "stripped_acacia_log[axis=z]")
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                m.set(x, 8, z, (colors[i] if (z-z0) % 4 else "white") + "_wool")
        m.box((counter, 2, z0 + 2), (counter, 2, z1 - 1), "stripped_acacia_log[axis=z]")
        m.set(counter, 3, z1 - 1, "lantern")
        back = 7 if left else 35
        m.set(back, 2, z0 + 2, "barrel[facing=up]")
        m.set(back, 2, z0 + 3, "barrel[facing=up]")
        m.set(back, 3, z0 + 2, "lantern")
        if i == 0:
            for dz, plant in ((2, "dead_bush"), (3, "fern"), (4, "red_mushroom")):
                m.set(counter, 3, z0 + dz, "potted_" + plant)
            m.set(back, 2, z0 + 5, "decorated_pot")
        elif i == 1:
            m.box((back - 1, 2, z0 + 4), (back, 3, z0 + 5), "hay_block[axis=y]")
            m.set(counter, 3, z0 + 2, "pumpkin")
            m.set(counter, 3, z0 + 3, "brown_carpet")
        elif i == 2:
            m.set(back, 2, z0 + 5, "loom[facing=east]")
            m.box((back + 1, 2, z0 + 4), (back + 1, 3, z0 + 5), "red_wool")
            m.set(counter, 3, z0 + 2, "yellow_carpet")
            m.set(counter, 3, z0 + 3, "blue_carpet")
        elif i == 3:
            m.set(back, 2, z0 + 5, "water_cauldron[level=3]")
            m.set(counter, 3, z0 + 2, "flower_pot")
            m.set(counter, 3, z0 + 3, "sea_pickle[pickles=3,waterlogged=false]")
        elif i == 4:
            m.set(back, 2, z0 + 5, "water_cauldron[level=3]")
            for dz, col in ((2, "red"), (3, "yellow"), (4, "blue")):
                m.set(counter, 3, z0 + dz, col + "_carpet")
            m.set(back + 1, 2, z0 + 4, "red_terracotta")
        else:
            m.set(counter, 3, z0 + 2, "melon")
            m.set(counter, 3, z0 + 3, "pumpkin")
            m.set(back - 1, 2, z0 + 5, "composter[level=4]")
        customer = counter + 1 if left else counter - 1
        worker = counter - 1 if left else counter + 1
        m.point(f"stall_{i+1}", "work", (counter, 3, z0+3), goods[i]+"铺位",
                approach=(customer, 2, z0+3))
        m.point(f"supply_{i+1}", "work", (back, 2, z0+3), goods[i]+"补货台",
                approach=(worker, 2, z0+3))
        m.room(f"stall_{i+1}", goods[i]+"铺", (x0+1, 2, z0+1), (x1-1, 7, z1-1),
               "面向主街交易；两端开口进入柜台后补货")

    # Rear stores have broad portals facing the street and loading apron.
    for left in (True, False):
        x0, x1 = (4, 15) if left else (27, 39)
        top = 10 if left else 8
        m.box((x0, 2, 41), (x1, top, 49), "smooth_sandstone")
        m.box((x0+1, 2, 42), (x1-1, top-1, 48), "air")
        m.box((x0, top+1, 41), (x1, top+1, 49), "smooth_sandstone_slab[type=bottom]")
        m.box((x0, 2, 41), (x1, 2, 41), "cut_sandstone")
        door = 10 if left else 33
        for z in (41, 49):
            m.box((door-1, 2, z), (door+1, 5, z), "air")
            m.box((door-2, 6, z), (door+2, 6, z), "stripped_acacia_log[axis=x]")
        side = x1 if left else x0
        m.box((side, 2, 44), (side, 5, 46), "air")
        for x in (x0+2, x1-2):
            for z in (43, 47):
                m.box((x, 2, z), (x, 3, z+1), "barrel[facing=up]")
        m.set(door, 6, 45, "lantern[hanging=true]")
        m.box((door, 7, 45), (door, top-1, 45), "chain[axis=y]")
        m.point("west_store" if left else "east_store", "circulation", (door, 2, 45),
                "香料布匹仓" if left else "粮果周转仓")
        m.room("west_store" if left else "east_store", "香料布匹仓" if left else "粮果周转仓",
               (x0+1, 2, 42), (x1-1, top-1, 48), "前后贯通的分类仓储，侧门连接商街")
        for x in (x0+3, x1-3):
            m.box((x, top-3, 49), (x, top-2, 49), "dark_oak_fence")

    # Weighing and accounts share the widened open end of the central hall.
    m.box((17, 2, 41), (19, 2, 41), "acacia_planks")
    m.set(18, 3, 41, "light_weighted_pressure_plate[power=0]")
    m.point("weighing", "work", (18, 3, 41), "货物称量", approach=(18, 2, 40))
    m.set(25, 2, 41, "lectern[facing=north]")
    m.set(26, 2, 41, "barrel[facing=up]")
    m.point("ledger", "work", (25, 2, 41), "交易账目与结算", approach=(25, 2, 40))
    # The front apron has seating and water, leaving the full central spine open.
    for x in (6, 34):
        m.box((x, 2, 7), (x+2, 2, 7), "acacia_stairs[facing=north]")
        m.set(x, 2, 5, "water_cauldron[level=3]")
    m.point("water", "work", (6, 2, 5), "公共饮水", approach=(7, 2, 5))
    m.point("rest", "circulation", (10, 2, 7), "候客休息", look_at=[7, 3, 7])
    for x in (6, 34):
        m.box((x, 2, 52), (x+2, 2, 53), "barrel[facing=up]")
        m.set(x, 3, 52, "barrel[facing=up]")
    m.room("market", "线性遮阳商街", (16, 2, 10), (26, 11, 42), "贯通两端的主客流及六类交易界面")
    m.room("loading", "后侧装卸院", (3, 2, 50), (39, 7, 54), "双货仓卸货、周转及外部补货接驳")
    m.point("loading", "circulation", (21, 2, 52), "装卸通路", look_at=[10, 3, 49])
    m.point("front", "entrance", (21, 2, 2), "商街主入口", facing="north")
    m.point("delivery", "entrance", (21, 2, 54), "后侧进货口", facing="south")
    m.meta["connections"] = [dict(kind="pedestrian", pos=[21,2,1], direction="north", clearance=[9,5], note="无阶商业街入口"),
                              dict(kind="service", pos=[21,2,55], direction="south", clearance=[9,5], note="后场补货接驳，禁止在出口堆货")]
    m.meta["ground_plane"] = {'y': 2, 'note': '前后无阶商街入口与场坪同层；门外场坪支撑方块 Y=1，地表上边界 Y=2。'}
    return m
