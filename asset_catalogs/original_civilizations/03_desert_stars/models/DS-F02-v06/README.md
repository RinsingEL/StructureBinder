# DS-F02-v06 · 两台等高渠田

33×12×29 格，1791 个可见方块；1 个房间或作业区、4 个使用与交通标记。前低后高两台，沿各台等高方向布渠；凹角避让外部道路，中央三格短阶连接高差。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_life.py](../../../../../tools/structure_studio/studio/oasis_life.py)，`garden(6)`。在工具目录执行 `python -m studio.build DS-F02-v06`。

填充结构；上下台维护点分别标记，作物与水源高程随台面改变，工具位在上台边缘。空间标记包括：灌渠种植与维护。

- **选址**：有可靠引水与适宜土壤的绿洲；不在无水沙海仅凭文明标签放置
- **形状**：前低后高一格的两台菜田，沿等高方向布渠，中央短阶贯通作业。
- **地面**：下台脚底 Y=2，上台 Y=3，沿 +Z 方向抬升
- **落地方式**：独立固定模板田块，包含封边与作业道；不替代 Landscape 自然生成大田

已完成离线外观、内饰或生产布置、作者标记与入口至已标站位的碰撞通路初筛。需要实际可靠供水和适宜土壤；离线检查不证明游戏内生长、照度或流体更新。未启动 MC；尚未接入国度 Mod 运行时目录。
