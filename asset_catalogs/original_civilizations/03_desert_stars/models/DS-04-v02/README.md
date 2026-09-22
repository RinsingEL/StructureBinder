# DS-04-v02 · 双户共水宅

33×23×28 格，2987 个可见方块；5 个房间或作业区、6 个使用与交通标记。两侧独立住宅夹住窄庭，共享入口遮棚与后端取水处；两座风塔位置错开。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_life.py](../../../../../tools/structure_studio/studio/oasis_life.py)，`home(2)`。在工具目录执行 `python -m studio.build DS-04-v02`。

填充结构；两户各自具有炊事、用餐、睡眠与用品，不依靠穿越另一户出入；中央取水点可达。空间标记包括：西户起居、西户卧室、东户起居、东户卧室、共水窄庭。

- **选址**：有稳定生活用水的干热聚落，通风方向与遮阳不被邻屋完全堵塞
- **地块**：两户各有炊事和卧室，共享中央储水庭与遮阳入口，不通过另一户进入。
- **落地**：独立住宅模板，庭院及其硬地属于实际占地；未写入格保留原环境
- **高程**：主入口与生活空间脚底 Y=2

已完成离线外观、内饰或生产布置、作者标记与入口至已标站位的碰撞通路初筛。风塔表达连续通风空间，不是气流、温度或降温效果模拟。未启动 MC；尚未接入国度 Mod 运行时目录。
