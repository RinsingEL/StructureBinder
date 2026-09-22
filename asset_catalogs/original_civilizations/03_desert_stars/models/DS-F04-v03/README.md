# DS-F04-v03 · 墙边寄货棚

22×15×29 格，1289 个可见方块；2 个房间或作业区、4 个使用与交通标记。一侧厚墙与低坡铜顶形成寄货棚，前端登记、后端货箱布包，东侧保持开放。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_yards.py](../../../../../tools/structure_studio/studio/oasis_yards.py)，`trade_shelter(3)`。在工具目录执行 `python -m studio.build DS-F04-v03`。

填充结构；登记讲台、捆扎挂具、货箱和棚外歇脚长凳完整，寄存点可从侧面抵达。空间标记包括：棚前接货、后段货仓。

- **选址**：依托商路与聚落交易需求，货物需能从街道搬入，棚周保留通风和行走
- **地块**：单边厚墙承托斜顶，前端交易、后段寄货，东侧保留手推货物的侧向开口。
- **接地**：使用地坪 Y=1，脚底 Y=2；不自动削坡或补齐外部通路
- **边界**：模板包含内部地坪和封边，未写入的凹角与外部格保持原环境

已完成当前 NBT 外观、内饰或生产布置、作者标记与已标站位的离线验收。商品、登记和寄货是原版方块的空间表达，未接入交易、物流或库存玩法。未启动 MC，未接入国度 Mod 运行时目录。
