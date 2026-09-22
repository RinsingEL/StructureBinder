# MF-F02-v05 · 碎石分区混作田

29×16×29 格，1604 个可见方块。十字碎石主路分出四块独立作物田，便于分作物管理。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_outside.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-F02-v05`。

四类作物各有水沟，中心用水盆和路端堆肥点可达；宽十字通路与完整灌溉覆盖均已确认。空间标记：四块碎石间小田。角色：`planning_role.fill`。

- **选址**：仅限具备足够土壤、光照、温度和稳定水分的地表地块，不放入无光山腹或永久冻土。
- **高程**：默认田埂脚底 Y=2；MF-F02-v02 后半单阶脚底 Y=3。
- **生产**：作物为原版外观与几何表达；未模拟生长tick、光照、温度或整合包产量。
- **边界**：独立模板田块有田埂和封边，不代表Landscape自然生成的大田。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
