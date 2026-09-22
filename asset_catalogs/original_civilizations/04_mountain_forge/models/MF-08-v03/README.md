# MF-08-v03 · 楼上四床客栈

25×26×29 格，3930 个可见方块。双层窄街客栈有连续六级内梯及上层U形护栏。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_trade.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-08-v03`。

楼下登记寄存、炊事、餐桌、备餐回收与储架齐备；楼上四床分两端，中间长凳与洗漱位，梯井两角连接臂已在最终切片确认。空间标记：楼下餐饮与接待、楼上四床住宿。角色：`planning_role.fill`。

- **选址**：矿业居住组团或访客路线旁，有稳定干地及足够的日常给水与食物补给。
- **高程**：基础上边界及室内脚底 Y=2；入口街面需同高程。
- **保留空间**：完整独立建筑及院落，保留入口和装卸面，不依赖其他模板补墙。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
