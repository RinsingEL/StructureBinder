# WT-02-v03 · 街水贯通双仓

43×25×39 格，7314 个可见方块；5 个空间区、7 个使用或交通标记。填充角色 `planning_role.fill`。

小账房、船材仓、绳帆仓各自开门，中间通巷不穿过货架；木材、帆布、货箱和封包台清楚可辨。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [岸线示意](previews/site-context.png)
- 可重建源码：[waterway_trade.py](../../../../../tools/structure_studio/studio/waterway_trade.py)；工具目录运行 `python -m studio.build WT-02-v03`。

空间标记：独立街侧账房、西侧船材仓、东侧绳帆仓、街水贯通巷、水侧装卸栈桥。

- **选址**：有实际水陆转运需求、避风且可以支撑固定木桩的岸段
- **地块**：两条货仓夹三格贯通搬运巷，街侧小账房独立开门，分别存船材与绳帆，后端合流到长栈桥。
- **高程**：主地坪方块 Y=3、脚底 Y=4；参考水面 Y=3，水岸在 Z=30；两层版本上层脚底 Y=11
- **落地**：保留明确岸壁与桩脚；模板外航行宽度、水深、洪水线及道路另按实际环境核对，不填平外部水道

已实际查看最终全部 20 张截图，完成外观、内饰、标记、通路和岸线条件的离线验收。未启动 MC，运行时导入及货运、经济、居民机制另行验收。
