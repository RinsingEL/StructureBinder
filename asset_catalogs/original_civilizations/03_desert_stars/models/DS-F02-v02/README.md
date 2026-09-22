# DS-F02-v02 · 四岛分水菜园

27×12×27 格，1254 个可见方块；1 个房间或作业区、3 个使用与交通标记。四个种植岛围绕十字作业道，每岛再由渠分小畦，轮廓与窄条田有明显差别。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_life.py](../../../../../tools/structure_studio/studio/oasis_life.py)，`garden(2)`。在工具目录执行 `python -m studio.build DS-F02-v02`。

填充结构；四种作物、中央储水处和田头堆肥分别标记；作业路径连接各岛。空间标记包括：灌渠种植与维护。

- **选址**：有可靠引水与适宜土壤的绿洲；不在无水沙海仅凭文明标签放置
- **形状**：四块种植岛由十字作业道分隔，交叉水渠贴近每块菜畦。
- **地面**：作业道脚底 Y=2，有限地块内备土与灌溉
- **落地方式**：独立固定模板田块，包含封边与作业道；不替代 Landscape 自然生成大田

已完成离线外观、内饰或生产布置、作者标记与入口至已标站位的碰撞通路初筛。需要实际可靠供水和适宜土壤；离线检查不证明游戏内生长、照度或流体更新。未启动 MC；尚未接入国度 Mod 运行时目录。
