# WT-05-v02 · 转角帆布店宅

37×31×38 格，5897 个可见方块；4 个空间区、12 个使用或交通标记。填充角色 `planning_role.fill`。

L形低翼布置织机和裁帆台，高翼下层店铺、上层双床家居，衣物架位于前墙且不覆盖床头。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [岸线示意](previews/site-context.png)
- 可重建源码：[waterway_trade.py](../../../../../tools/structure_studio/studio/waterway_trade.py)；工具目录运行 `python -m studio.build WT-05-v02`。

空间标记：家庭起居与睡眠、北街帆布铺、东巷裁帆间、水侧装卸栈桥。

- **选址**：具有实际街巷与家庭生活用水的岸上商住地块
- **地块**：L 形店宅西翼两层，北街陈列与楼上家庭生活，后侧低檐裁帆间向东巷开门，内角保留院落。
- **高程**：主地坪方块 Y=3、脚底 Y=4；参考水面 Y=3，水岸在 Z=31；两层版本上层脚底 Y=11
- **落地**：保留明确岸壁与桩脚；模板外航行宽度、水深、洪水线及道路另按实际环境核对，不填平外部水道

已实际查看最终全部 24 张截图，完成外观、内饰、标记、通路和岸线条件的离线验收。未启动 MC，运行时导入及货运、经济、居民机制另行验收。
