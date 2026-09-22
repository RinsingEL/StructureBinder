# DS-02-v02 · 双栏护理内院

39×18×37 格，3347 个可见方块；5 个房间或作业区、11 个使用与交通标记。西侧两栏、东侧两个房间与中央护理棚分别承担动物、存货和人员用途，庭院主通道保持宽敞。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_yards.py](../../../../../tools/structure_studio/studio/oasis_yards.py)，`beast_yard(2)`。在工具目录执行 `python -m studio.build DS-02-v02`。

填充结构；两栏各有饲料和饮水，另有集中饲料库、护理点及带卧床的登记值守室。空间标记包括：遮阳兽栏 · 前栏、遮阳兽栏 · 后栏、换乘值守室、干燥饲料库、护理内院。

- **选址**：依托商路补给点和可靠饮水，保持动物活动与人货搬运空间
- **地块**：两座兽栏沿西侧排列，东侧分别设置值守室和饲料库，中央留宽护理院。
- **接地**：使用地坪 Y=1，脚底 Y=2；不自动削坡或补齐外部通路
- **边界**：模板包含内部地坪和封边，未写入的凹角与外部格保持原环境

已完成当前 NBT 外观、内饰或生产布置、作者标记与已标站位的离线验收。未放置活体动物；已标站位的玩家通路检查不能证明动物牵引、回转或围栏行为。未启动 MC，未接入国度 Mod 运行时目录。
