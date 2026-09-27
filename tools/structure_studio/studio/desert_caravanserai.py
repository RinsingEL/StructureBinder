"""DS-01: a continuous courtyard inn, authored from the September reference."""
from .components import bench, crate_stack


def caravanserai():
    from .desert import base, pad, front, table, windtower
    m = base(1, "月井商队大驿站", (53, 23, 51), {
        "选址": "有可靠水源的商路补给节点",
        "地形": "约 47×45 格可整备平缓地块，北侧保留旅人与货运动线",
        "接地": "地坪 Y=1，室内与院内脚底 Y=2",
        "组合": "客房、餐饮、存货与庭院完整；动物换乘由外部兽栏承担"}, [
        "参考对称围院方案：连续厚墙建筑翼围合内庭，深门楼与后部高厅形成主次。",
        "客房共享平整屋面，赭色连续内庭拱廊遮阴；一座风塔与局部高低体量取代逐间装饰。"], roof=7)
    m.meta["source"] = "tools/structure_studio/studio/desert_caravanserai.py:caravanserai"
    pad(m, 3, 3, 49, 47)

    def wing(x0, z0, x1, z1, roof):
        m.box((x0, 2, z0), (x1, roof, z1), "smooth_sandstone")
        m.box((x0+2, 2, z0+2), (x1-2, roof-1, z1-2), "air")
        # Broad solid parapets belong to the wing, never to individual rooms.
        for z in (z0, z1):
            m.box((x0, roof+1, z), (x1, roof+1, z), "smooth_sandstone_slab[type=bottom]")
        for x in (x0, x1):
            m.box((x, roof+1, z0), (x, roof+1, z1), "smooth_sandstone_slab[type=bottom]")

    wing(4, 6, 48, 15, 7)
    wing(4, 14, 14, 46, 8)
    wing(38, 14, 48, 46, 9)
    wing(14, 37, 38, 46, 10)
    wing(18, 37, 34, 46, 14)
    # Room interiors are cut into the connected wings, with no exterior gaps.
    for x0, x1, side in ((6, 12, "west"), (40, 46, "east")):
        for za, zb in ((16, 16), (26, 27), (36, 38)):
            m.box((x0, 2, za), (x1, 7, zb), "smooth_sandstone")
        for i, (z0, z1) in enumerate(((17, 25), (28, 35)), 1):
            m.box((x0, 2, z0), (x1, 7, z1), "air")
            wallx = 13 if side == "west" else 38
            m.box((wallx, 2, z0+2), (wallx+1, 4, z0+3), "air")
            for j, x in enumerate((x0+1, x1-1), 1):
                m.bed(x, 2, z1-1, color="orange", facing="north")
                m.point(f"{side}_{i}_bed{j}", "bed", (x, 2, z1-1), "客房床位", approach=(x+1 if j==1 else x-1, 2, z1-1))
            m.set(x0, 2, z0+1, "barrel[facing=south]")
            m.set(x1, 2, z0+1, "water_cauldron[level=3]")
            m.set((x0+x1)//2, 2, z1, "birch_slab[type=top]")
            m.set((x0+x1)//2, 3, z1, "lantern")
            m.room(f"{side}_{i}", "双床客房", (x0, 2, z0), (x1, 6, z1), "睡眠、行李、取水")
    # Low entrance wing contains reception and luggage, split by a deep portal.
    m.box((6, 2, 8), (19, 6, 13), "air")
    m.box((33, 2, 8), (46, 6, 13), "air")
    for x in (18, 33):
        m.box((x, 2, 14), (x+1, 4, 15), "air")
    wing(21, 4, 31, 15, 11)
    m.box((23, 2, 4), (29, 5, 15), "air")
    m.box((24, 6, 4), (28, 7, 15), "air")
    m.box((25, 8, 4), (27, 8, 15), "air")
    front(m, 26, 4, width=5)
    # Rise from the authored outside ground (feet Y=1) to the gate (Y=2).
    for x in range(24, 29):
        m.set(x, 1, 3, "sandstone_stairs[facing=south]")
    m.box((7, 2, 10), (14, 2, 10), "stripped_acacia_log[axis=x]")
    m.set(9, 3, 10, "lectern[facing=south]")
    bench(m, 7, 2, 12, 4, "north", "acacia")
    for x in (34, 39, 44): crate_stack(m, x, 2, 9, 2, 3, 2)
    m.point("register", "work", (9, 3, 10), "旅人登记", approach=(9, 2, 11))
    m.point("freight", "work", (39, 2, 9), "寄存货物", approach=(39, 2, 12))
    m.room("reception", "登记候坐厅", (6, 2, 8), (19, 6, 13), "登记、歇脚与接待")
    m.room("storage", "行李货物寄存", (33, 2, 8), (46, 6, 13), "货物寄存与交接")

    # Rear high hall and flanking service rooms open directly onto the court.
    m.box((20, 2, 39), (32, 13, 44), "air")
    m.box((23, 2, 37), (29, 6, 38), "air")
    m.box((24, 7, 37), (28, 8, 38), "air")
    for x0, x1 in ((6, 17), (35, 46)):
        m.box((x0, 2, 39), (x1, 7, 44), "air")
    for x in (10, 40):
        m.box((x, 2, 36), (x+2, 4, 38), "air")
    # Service doors turn inward to the shaded arcade, without crossing bedrooms.
    m.box((13, 2, 39), (14, 4, 41), "air")
    m.box((38, 2, 39), (39, 4, 41), "air")
    m.box((15, 2, 37), (17, 4, 39), "air")
    m.box((35, 2, 37), (37, 4, 39), "air")
    for x in (21, 28):
        table(m, x, 2, 41, 3, "orange")
        bench(m, x, 2, 43, 3, "north", "acacia")
    for x in (7, 9): m.set(x, 2, 44, "smoker[facing=north,lit=false]")
    m.box((7, 3, 44), (9, 5, 44), "sandstone")
    m.box((8, 6, 44), (8, 11, 44), "sandstone")
    table(m, 12, 2, 42, 4, "white")
    m.set(16, 2, 44, "water_cauldron[level=3]")
    for x in (37, 41, 45): m.set(x, 2, 44, "water_cauldron[level=3]")
    m.set(45, 2, 40, "barrel[facing=west]")
    m.point("kitchen", "work", (7, 2, 44), "公共厨房", approach=(7, 2, 43))
    m.point("wash", "work", (41, 2, 44), "洗漱取水", approach=(41, 2, 43))
    m.room("dining", "高厅公共餐厅", (20, 2, 39), (32, 13, 44), "共同用餐与商旅交谈")
    m.room("kitchen", "西翼厨房", (6, 2, 39), (17, 7, 44), "备餐、炉灶和用水")
    m.room("washroom", "东翼洗漱间", (35, 2, 39), (46, 7, 44), "盥洗、取水与用品")

    # A single continuous ochre arcade: square piers and stepped arch shoulders.
    for x in (17, 35):
        m.box((x, 6, 16), (x, 6, 36), "terracotta")
        m.box((min(x,14 if x==17 else 38), 7, 16), (max(x,14 if x==17 else 38), 7, 36), "smooth_sandstone")
        for z in (16, 21, 26, 31, 36):
            m.box((x, 2, z), (x, 5, z), "terracotta")
            for dz in (-1, 1): m.set(x, 5, z+dz, "terracotta")
    for z in (16, 36):
        m.box((17, 6, z), (35, 6, z), "terracotta")
        m.box((17, 7, z if z==16 else 35), (35, 7, z+1), "smooth_sandstone")
        for x in (17, 22, 30, 35):
            m.box((x, 2, z), (x, 5, z), "terracotta")
            for dx in (-1, 1): m.set(x+dx, 5, z, "terracotta")
    # Small off-axis water basin preserves the entrance-to-hall freight route.
    m.box((20, 1, 25), (24, 1, 29), "cut_sandstone")
    m.box((21, 1, 26), (23, 1, 28), "water[level=0]")
    for x in (20, 24): m.box((x, 2, 25), (x, 2, 29), "smooth_sandstone_slab[type=bottom]")
    for z in (25, 29): m.box((21, 2, z), (23, 2, z), "smooth_sandstone_slab[type=bottom]")
    m.point("water", "work", (24, 2, 27), "月井取水", approach=(25, 2, 27))
    bench(m, 29, 2, 32, 4, "north", "acacia")
    m.room("court", "月井内庭与遮阴廊", (15, 2, 16), (37, 6, 36), "搬货、集散、遮阴、饮水")
    # Deep narrow openings and wall feet provide relief without a coloured belt.
    for x in (9, 16, 36, 43):
        m.box((x, 4, 6), (x, 5, 7), "air")
    for z in (19, 24, 30, 34, 41):
        for x0 in (4, 47): m.box((x0, 4, z), (x0+1, 5, z), "air")
    for x in (21, 25, 29, 32):
        m.box((x, 10, 37), (x, 11, 38), "air")
        m.box((x, 10, 45), (x, 11, 46), "air")
    for x in (4, 48): m.box((x, 2, 6), (x, 2, 46), "sandstone")
    m.box((4, 2, 6), (20, 2, 6), "sandstone")
    m.box((32, 2, 6), (48, 2, 6), "sandstone")
    windtower(m, 28, 40, y=15)
    for x, z in ((15, 19), (37, 19), (15, 33), (37, 33), (26, 41), (11, 40), (40, 40)):
        m.set(x, 6, z, "lantern[hanging=true]")
    for x, z, top in ((26, 41, 13), (11, 40, 7), (40, 40, 8)):
        m.box((x, 7, z), (x, top, z), "chain[axis=y]")
    m.meta["ground_plane"] = {'y': 1, 'note': '外部街面上边界 Y=1；室内与院内脚底 Y=2。门前朝南阶梯连续衔接外街面与门内平台。'}
    return m
