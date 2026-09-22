# DS-05-v03 · 双庭分客商宅

49×24×43 格，6453 个可见方块；7 个房间或作业区、9 个使用与交通标记。中央圆顶账房分开两庭，前庭水池会客、后庭遮棚家宴，两侧分别为铺面货仓及家庭生活。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_crafts.py](../../../../../tools/structure_studio/studio/oasis_crafts.py)，`merchant(3)`。在工具目录执行 `python -m studio.build DS-05-v03`。

填充结构；会商席、织物陈列、小货仓、独立厨房起居、双床卧室、后庭长桌长凳和盆栽完整。空间标记包括：临街接待铺面、后庭起居、后庭双床房、会客账房、贸易小货仓、前庭会商、后庭家宴。

- **选址**：可靠供水的贸易聚落，有顾客步行、货物卸载与家居生活需求
- **地块**：中央账房分隔前后两庭，前庭会商与存货，后庭家宴与起居，侧廊连接两者。
- **接地**：主入口与地面层脚底 Y=2；上层版二层脚底 Y=9
- **落地**：独立完整建筑与内部院落，不依靠运行时补齐；模板外与凹角未写入格保持原环境

已完成当前 NBT 外观、内饰、作者标记与已标站位的离线验收。交易、账记和库存仅作空间与标记表达，尚未接入经济玩法。未启动 MC，未接入国度 Mod 运行时目录。
