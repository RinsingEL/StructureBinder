# MF-05-v05 · 工场上居连廊宅

29×23×30 格，3365 个可见方块。低工场和四柱材料棚并置，后方高家屋横向封边，矮棚去顶阈值已修正。 出檐承托修复后已实际复查外观连接。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_life.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-05-v05`。

工作、书写、材料周转与后方二床、餐厨和储物完整；模型各室和低棚内部可在切片看清。空间标记：低位工场与记账、低位材料连棚、后部高台家庭屋。角色：`planning_role.fill`。

- **选址**：仅用于明确向 +Z 升高一格的山地地块；宽阶与后方高台均须承托，不可任意镜像后沿用高程。
- **高程**：低位工作与街门 Y=2，高台家庭脚底 Y=3；+Z 从 Z=17 起抬高一格，三宽短阶位置固定。
- **保留空间**：完整独立建筑及院落，保留入口和装卸面，不依赖其他模板补墙。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
