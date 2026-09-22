# DS-05-v02 · 转角双门商宅

37×24×41 格，4010 个可见方块；5 个房间或作业区、9 个使用与交通标记。L 形地块顺北巷与东侧卸货口展开，内部窄庭连接铺面和住宅，前凹角保持不写入。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_crafts.py](../../../../../tools/structure_studio/studio/oasis_crafts.py)，`merchant(2)`。在工具目录执行 `python -m studio.build DS-05-v02`。

填充结构；临街展示、侧门账记、小货箱、家庭茶座、厨房和双床卧室完整，两入口可抵达所有已标站位。空间标记包括：临街接待铺面、家庭起居、会客账房、后翼卧室、转角生活窄庭。

- **选址**：可靠供水的贸易聚落，有顾客步行、货物卸载与家居生活需求
- **地块**：L 形沿街地块，前铺面向北巷，账房从东侧接货，家居沿内部窄庭相连。
- **接地**：主入口与地面层脚底 Y=2；上层版二层脚底 Y=9
- **落地**：独立完整建筑与内部院落，不依靠运行时补齐；模板外与凹角未写入格保持原环境

已完成当前 NBT 外观、内饰、作者标记与已标站位的离线验收。交易、账记和库存仅作空间与标记表达，尚未接入经济玩法。未启动 MC，未接入国度 Mod 运行时目录。
