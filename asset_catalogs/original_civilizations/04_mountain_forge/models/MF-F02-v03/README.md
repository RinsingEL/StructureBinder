# MF-F02-v03 · 折角L形菜畦

27×16×27 格，1390 个可见方块。长田与短田组成 L 形菜园，缺角保留日常操作空间。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_outside.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-F02-v03`。

两种作物、水沟、候歇长凳、堆肥桶与收获桶可见，操作区和两片田地互不挤占。空间标记：L形两畦。角色：`planning_role.fill`。

- **选址**：仅限具备足够土壤、光照、温度和稳定水分的地表地块，不放入无光山腹或永久冻土。
- **高程**：默认田埂脚底 Y=2；MF-F02-v02 后半单阶脚底 Y=3。
- **生产**：作物为原版外观与几何表达；未模拟生长tick、光照、温度或整合包产量。
- **边界**：独立模板田块有田埂和封边，不代表Landscape自然生成的大田。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
