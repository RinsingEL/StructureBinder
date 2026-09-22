# MF-F02-v06 · 背风采光小菜棚

23×16×27 格，1774 个可见方块。三面挡风矮墙和透光顶棚围合薯田，正面保持开口。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_outside.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-F02-v06`。

种植行、三条水沟、木柱和玻璃顶清晰分层，田埂与入口留空；透光棚仅表达环境防护，不宣称额外生长机制。空间标记：采光背风菜棚。角色：`planning_role.fill`。

- **选址**：仅限具备足够土壤、光照、温度和稳定水分的地表地块，不放入无光山腹或永久冻土。
- **高程**：默认田埂脚底 Y=2；MF-F02-v02 后半单阶脚底 Y=3。
- **生产**：作物为原版外观与几何表达；未模拟生长tick、光照、温度或整合包产量。
- **边界**：独立模板田块有田埂和封边，不代表Landscape自然生成的大田。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
