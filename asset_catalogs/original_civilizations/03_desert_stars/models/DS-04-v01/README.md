# DS-04-v01 · 单户凉廊宅

23×23×25 格，1584 个可见方块；3 个房间或作业区、4 个使用与交通标记。前凉廊与后排两间室内形成紧凑小宅；橙白遮棚、蓝窗和风塔使前后体量清楚。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_life.py](../../../../../tools/structure_studio/studio/oasis_life.py)，`home(1)`。在工具目录执行 `python -m studio.build DS-04-v01`。

填充结构；炊事间有炉灶、饮水、桌凳和用品；卧室有床、衣柜与盥洗，庭院补充遮阳与储水。空间标记包括：炊事与起居、单户卧室、前院凉廊。

- **选址**：有稳定生活用水的干热聚落，通风方向与遮阳不被邻屋完全堵塞
- **地块**：前院凉廊接后排起居与卧室，小家庭独立生活，沿北侧街巷进入。
- **落地**：独立住宅模板，庭院及其硬地属于实际占地；未写入格保留原环境
- **高程**：主入口与生活空间脚底 Y=2

已完成离线外观、内饰或生产布置、作者标记与入口至已标站位的碰撞通路初筛。风塔表达连续通风空间，不是气流、温度或降温效果模拟。未启动 MC；尚未接入国度 Mod 运行时目录。
