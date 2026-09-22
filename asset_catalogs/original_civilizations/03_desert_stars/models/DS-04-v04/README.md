# DS-04-v04 · 窄巷叠居宅

17×31×31 格，2222 个可见方块；3 个房间或作业区、5 个使用与交通标记。窄长两层体量适合巷道；立面窗与檐板区分上下层，风塔抬到上层屋顶。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_life.py](../../../../../tools/structure_studio/studio/oasis_life.py)，`home(4)`。在工具目录执行 `python -m studio.build DS-04-v04`。

填充结构；底层炊事、餐桌与凉凳，上层睡眠、阅读、衣物晾晒；楼梯开口及栏杆留出通路。空间标记包括：底层炊事与凉室、上层卧室、上层阅读凉廊。

- **选址**：有稳定生活用水的干热聚落，通风方向与遮阳不被邻屋完全堵塞
- **地块**：窄长占地，底层炊事与凉室，上层睡眠、阅读和晾晒；内部楼梯连接两层。
- **落地**：独立住宅模板，庭院及其硬地属于实际占地；未写入格保留原环境
- **高程**：主入口与底层脚底 Y=2；叠居版二层脚底 Y=9

已完成离线外观、内饰或生产布置、作者标记与入口至已标站位的碰撞通路初筛。风塔表达连续通风空间，不是气流、温度或降温效果模拟。未启动 MC；尚未接入国度 Mod 运行时目录。
