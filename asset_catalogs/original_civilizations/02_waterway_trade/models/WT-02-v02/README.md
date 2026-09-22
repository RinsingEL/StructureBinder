# WT-02-v02 · 双层分货栈

35×31×41 格，7337 个可见方块；5 个空间区、13 个使用或交通标记。填充角色 `planning_role.fill`。

大宗货和过秤在下层，七级内梯接干货楼层，楼后有独立值守账房与床位，楼梯口有护栏。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [岸线示意](previews/site-context.png)
- 可重建源码：[waterway_trade.py](../../../../../tools/structure_studio/studio/waterway_trade.py)；工具目录运行 `python -m studio.build WT-02-v02`。

空间标记：底层大宗货仓、上层干货分拣、上层账务值守、上层封箱、水侧装卸栈桥。

- **选址**：有实际水陆转运需求、避风且可以支撑固定木桩的岸段
- **地块**：下层大宗货与过秤，上层干货分拣及独立值守账房，七级内梯接两层，后岸留整条装卸栈桥。
- **高程**：主地坪方块 Y=3、脚底 Y=4；参考水面 Y=3，水岸在 Z=32；两层版本上层脚底 Y=11
- **落地**：保留明确岸壁与桩脚；模板外航行宽度、水深、洪水线及道路另按实际环境核对，不填平外部水道

已实际查看最终全部 27 张截图，完成外观、内饰、标记、通路和岸线条件的离线验收。未启动 MC，运行时导入及货运、经济、居民机制另行验收。
