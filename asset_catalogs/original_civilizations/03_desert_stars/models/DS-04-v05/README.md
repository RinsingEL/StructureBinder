# DS-04-v05 · 转角遮阳宅

29×23×27 格，1974 个可见方块；3 个房间或作业区、5 个使用与交通标记。L 形住宅留下街角凹口；北入口与东侧客宿入口不同，未写入的凹角不清除外部地形。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_life.py](../../../../../tools/structure_studio/studio/oasis_life.py)，`home(5)`。在工具目录执行 `python -m studio.build DS-04-v05`。

填充结构；临街起居、后卧室与转角客宿储藏串接；常住和客宿均有床位、生活用品及可达站位。空间标记包括：临街起居、后排卧室、转角客宿与储藏。

- **选址**：有稳定生活用水的干热聚落，通风方向与遮阳不被邻屋完全堵塞
- **地块**：L 形住宅有北侧和东侧两个入口，前凹角留给外部街巷，后翼兼作客宿与储藏。
- **落地**：独立住宅模板，庭院及其硬地属于实际占地；未写入格保留原环境
- **高程**：主入口与生活空间脚底 Y=2

已完成离线外观、内饰或生产布置、作者标记与入口至已标站位的碰撞通路初筛。风塔表达连续通风空间，不是气流、温度或降温效果模拟。未启动 MC；尚未接入国度 Mod 运行时目录。
