# NS-04-v01 · 风斗围院公共浴屋

35×24×29 格。双陡顶干湿翼通过后方短廊连接，前院避风；外墙、端架及檐口相接，供热侧门单独接维护院。

已查看完整23图：登记布品、两组更衣柜和长凳、脏布洗台、下沉浴池、冲洗盆、热后坐休与炉间燃料分区完整；干湿转换廊门贯通。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与导航](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去顶](previews/roof-off.png) · [首层平面](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/northern_seafarers.py)，独立重建：在 tools/structure_studio 执行 `python runtime/northern_build.py NS-04-v01`。

作者角色：`planning_role.key`。空间：风斗接待与布品发放、干燥更衣与洗衣间、供热浴池与休息厅、避风干湿转换廊。

- **选址**：稳定的寒地海湾背风岸台；避开潮涌、雪崩、海冰推挤和行洪通道。
- **高程**：干燥主层脚底 Y=3，建筑基础底 Y=0；外部步行地面需接主层。
- **保留空间**：完整独立模板，连同檐口、通路和本体配套保留；不可贴邻堵住工作面。
- **风雪**：厚木围护、短风斗和陡坡屋面表达避风防雪；不模拟风雪、温度或雪荷载。
- **供水供热**：浴池与水盆仅静态清水；须有可用淡水与燃料，炉火烟道独立，排水处理和水温机制另行接入。

当前版本 23 张完整截图已实际查看并归档，NBT/作者双哈希匹配。未启动 Minecraft；不代表运行时生成、交通、机器、流体或温度已接入。

在 `tools/structure_studio` 运行 `python -m studio.build NS-04-v01` 可从源码重建。
