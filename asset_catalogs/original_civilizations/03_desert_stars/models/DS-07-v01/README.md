# DS-07-v01 · 单窑玻璃作坊

29×24×37 格，3307 个可见方块；3 个房间或作业区、9 个使用与交通标记。单厅工作坊设一座独立贯通烟道砖窑，后院另设休息间和燃料棚，入口短篷与侧窗区分前厅。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_crafts.py](../../../../../tools/structure_studio/studio/oasis_crafts.py)，`glassworks(1)`。在工具目录执行 `python -m studio.build DS-07-v01`。

填充结构；前部原砂与材料架、窑前装料、窑后维护、模具台、器具、水盆和冷却架组成完整使用顺序。空间标记包括：单窑加工厅、后院休息间、燃料棚。

- **选址**：具砂料、燃料、供水与外运条件的生产地块，热作业区和烟道周围保持净空
- **地块**：单窑设于工作厅后部，原料近前口、加工与冷却靠侧墙，后院另有休息与燃料棚。
- **接地**：主入口与地面层脚底 Y=2；上层版二层脚底 Y=9
- **落地**：独立完整建筑与内部院落，不依靠运行时补齐；模板外与凹角未写入格保持原环境

已完成当前 NBT 外观、内饰、作者标记与已标站位的离线验收。砖窑用原版熔炉表达，设备与玻璃陈列不表示自定义配方、热工、机器或自动生产已接入。未启动 MC，未接入国度 Mod 运行时目录。
