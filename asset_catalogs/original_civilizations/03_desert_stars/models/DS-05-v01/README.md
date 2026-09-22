# DS-05-v01 · 前铺水庭商宅

41×24×39 格，5052 个可见方块；5 个房间或作业区、8 个使用与交通标记。临街双前房接横向水庭，后排厨房起居与双床卧室分开；圆顶、风塔和入口短篷形成不同高度。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_crafts.py](../../../../../tools/structure_studio/studio/oasis_crafts.py)，`merchant(1)`。在工具目录执行 `python -m studio.build DS-05-v01`。

填充结构；展示柜台、织物架、账台、候坐、家庭茶座、炊事、卧床和庭侧周转货都有布置与站位。空间标记包括：临街接待铺面、会客账房、家庭炊事与起居、家庭双床房、公共水庭。

- **选址**：可靠供水的贸易聚落，有顾客步行、货物卸载与家居生活需求
- **地块**：前排展示与账房接公共水庭，后排家居卧室分开，侧廊只存少量周转货物。
- **接地**：主入口与地面层脚底 Y=2；上层版二层脚底 Y=9
- **落地**：独立完整建筑与内部院落，不依靠运行时补齐；模板外与凹角未写入格保持原环境

已完成当前 NBT 外观、内饰、作者标记与已标站位的离线验收。交易、账记和库存仅作空间与标记表达，尚未接入经济玩法。未启动 MC，未接入国度 Mod 运行时目录。
