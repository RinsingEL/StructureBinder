# DS-02-v04 · 转角换乘兽院

41×18×32 格，2209 个可见方块；3 个房间或作业区、8 个使用与交通标记。L 形地块围绕街角展开，北侧宽口接纵向牵引路，后段转向东侧出口，凹角不写入。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_yards.py](../../../../../tools/structure_studio/studio/oasis_yards.py)，`beast_yard(4)`。在工具目录执行 `python -m studio.build DS-02-v04`。

填充结构；兽栏、遮阳、取水、值守房和出发前护理点完整，转角通路未被后房家具占用。空间标记包括：遮阳兽栏 · 转角栏、换乘值守室、转角牵引通道。

- **选址**：依托商路补给点和可靠饮水，保持动物活动与人货搬运空间
- **地块**：L 形地块避开外部街角，兽栏接南北通道与后部横向换乘通道，提供北、东两个宽口。
- **接地**：使用地坪 Y=1，脚底 Y=2；不自动削坡或补齐外部通路
- **边界**：模板包含内部地坪和封边，未写入的凹角与外部格保持原环境

已完成当前 NBT 外观、内饰或生产布置、作者标记与已标站位的离线验收。未放置活体动物；已标站位的玩家通路检查不能证明动物牵引、回转或围栏行为。未启动 MC，未接入国度 Mod 运行时目录。
