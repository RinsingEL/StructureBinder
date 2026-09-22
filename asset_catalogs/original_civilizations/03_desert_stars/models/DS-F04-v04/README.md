# DS-F04-v04 · 四面通风货亭

33×18×29 格，2210 个可见方块；3 个房间或作业区、6 个使用与交通标记。分段帐顶下保留中央南北穿行道，货堆分置左右，登记和饮水歇脚集中在右侧。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_yards.py](../../../../../tools/structure_studio/studio/oasis_yards.py)，`trade_shelter(4)`。在工具目录执行 `python -m studio.build DS-F04-v04`。

填充结构；货箱、登记讲台、休息长凳和饮水点都有使用站位；梁柱支承可在通道视角核对。空间标记包括：左侧周转货区、右侧登记与歇脚、中央穿行通道。

- **选址**：依托商路与聚落交易需求，货物需能从街道搬入，棚周保留通风和行走
- **地块**：大跨度分段帐顶覆盖左右货堆，中央穿行，角部设登记和饮水歇脚。
- **接地**：使用地坪 Y=1，脚底 Y=2；不自动削坡或补齐外部通路
- **边界**：模板包含内部地坪和封边，未写入的凹角与外部格保持原环境

已完成当前 NBT 外观、内饰或生产布置、作者标记与已标站位的离线验收。商品、登记和寄货是原版方块的空间表达，未接入交易、物流或库存玩法。未启动 MC，未接入国度 Mod 运行时目录。
