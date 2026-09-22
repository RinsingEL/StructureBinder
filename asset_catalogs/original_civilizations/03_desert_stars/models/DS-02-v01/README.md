# DS-02-v01 · 路旁小兽栏

29×18×29 格，1656 个可见方块；3 个房间或作业区、7 个使用与交通标记。小栏、半幅遮棚和侧面值守室构成紧凑补给点；宽边廊从前端入口接到栏门。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_yards.py](../../../../../tools/structure_studio/studio/oasis_yards.py)，`beast_yard(1)`。在工具目录执行 `python -m studio.build DS-02-v01`。

填充结构；草料、水槽、露天护理架、值守卧床与登记台均已布置和标记。空间标记包括：遮阳兽栏 · 单栏、换乘值守室、牵引与等候边廊。

- **选址**：依托商路补给点和可靠饮水，保持动物活动与人货搬运空间
- **地块**：单兽栏与侧面值守小屋，动物由宽边廊进入，饮水和饲料分置。
- **接地**：使用地坪 Y=1，脚底 Y=2；不自动削坡或补齐外部通路
- **边界**：模板包含内部地坪和封边，未写入的凹角与外部格保持原环境

已完成当前 NBT 外观、内饰或生产布置、作者标记与已标站位的离线验收。未放置活体动物；已标站位的玩家通路检查不能证明动物牵引、回转或围栏行为。未启动 MC，未接入国度 Mod 运行时目录。
