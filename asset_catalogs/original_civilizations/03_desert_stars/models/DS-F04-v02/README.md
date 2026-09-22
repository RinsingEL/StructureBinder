# DS-F04-v02 · 夹道双摊

28×14×22 格，1156 个可见方块；3 个房间或作业区、5 个使用与交通标记。香料和布匹双摊隔巷相对，两片高低错开的遮棚与中央开放巷道构成独立平面。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_yards.py](../../../../../tools/structure_studio/studio/oasis_yards.py)，`trade_shelter(2)`。在工具目录执行 `python -m studio.build DS-F04-v02`。

填充结构；两种商品陈列和后备货区不同，两个摊主、两个顾客站位均可达。空间标记包括：香料摊、布匹摊、交易小巷。

- **选址**：依托商路与聚落交易需求，货物需能从街道搬入，棚周保留通风和行走
- **地块**：香料与布料两摊朝中央小巷开口，背后各有备货位，遮棚高低错开。
- **接地**：使用地坪 Y=1，脚底 Y=2；不自动削坡或补齐外部通路
- **边界**：模板包含内部地坪和封边，未写入的凹角与外部格保持原环境

已完成当前 NBT 外观、内饰或生产布置、作者标记与已标站位的离线验收。商品、登记和寄货是原版方块的空间表达，未接入交易、物流或库存玩法。未启动 MC，未接入国度 Mod 运行时目录。
