# DS-12-v01 · 旧星井管理所

28×25×29 格，4081 个可见方块；4 个房间或作业区、6 个使用与交通标记。石砌井圈位于半开放内院，横梁吊桶直对井口；档案与旧值守室围绕水源组织。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[desert.py](../../../../../tools/structure_studio/studio/desert.py)，`desert(12)`。在工具目录执行 `python -m studio.build DS-12-v01`。

- **选址**：具有持续水源解释的旧井节点，附近地层允许维护井壁
- **埋置**：地表脚底对应模型 Y=6；井底水面在 Y=2，需保留井壁深度
- **用途**：取水、记水档案和值守；后室保留早期管理设施

已完成离线外观、内饰、作者标记与入口至已标站位的碰撞通路初筛。原版方块表达相应用途；实际世界生成、玩法机制与国度 Mod 运行时目录接入需另行验证。地下与旧井模型必须按作者地表高程埋置，不能直接把模板最低层当作地表。
