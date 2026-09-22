# DS-F02-v04 · 斜巷阶边田

25×12×30 格，706 个可见方块；1 个房间或作业区、2 个使用与交通标记。随斜巷边界逐级加宽，田埂和水渠随宽度调整，凹边外没有模板清地操作。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_life.py](../../../../../tools/structure_studio/studio/oasis_life.py)，`garden(4)`。在工具目录执行 `python -m studio.build DS-F02-v04`。

填充结构；小麦与胡萝卜混植，维护通道贯通田头；窄端与边角仍有可用水源。空间标记包括：灌渠种植与维护。

- **选址**：有可靠引水与适宜土壤的绿洲；不在无水沙海仅凭文明标签放置
- **形状**：随道路边角逐段展开的非矩形田，纵渠随宽度增减，凹边不清地。
- **地面**：作业道脚底 Y=2，有限地块内备土与灌溉
- **落地方式**：独立固定模板田块，包含封边与作业道；不替代 Landscape 自然生成大田

已完成离线外观、内饰或生产布置、作者标记与入口至已标站位的碰撞通路初筛。需要实际可靠供水和适宜土壤；离线检查不证明游戏内生长、照度或流体更新。未启动 MC；尚未接入国度 Mod 运行时目录。
