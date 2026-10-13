from pathlib import Path
p=Path('tools/city_experiments/WORKBENCH.md');s=p.read_text(encoding='utf-8').replace('## 启动与使用','新增[有目的的嵌套组团与设计绿化](INTENTIONAL_GROUPS.md)：真实矩形排布、院落留空、逐栋入口朝向、食堂公共中心及程序花园。当前方案 23 栋，选择左侧“学院 · 公共广场与嵌套组团”可恢复。阵列选择器显示完整层级，选择节点可聚焦；整体组合不编辑单栋素材。显式矩形布局用 gapBlocks 控制净距，疏密选择仅用于旧布局。\n\n## 启动与使用',1);p.write_text(s,encoding='utf-8')
p=Path('tools/city_experiments/README.md');s=p.read_text(encoding='utf-8');s+='\n新增 [有目的的嵌套组团](INTENTIONAL_GROUPS.md)：Java 实验矩形排布与公共道路骨架，Python 设计绿化合成；23 栋教学/生活示例。\n';p.write_text(s,encoding='utf-8')
p=Path('asset_catalogs/original_civilizations/R02_arcane_reborn/README.md');s=p.read_text(encoding='utf-8').replace('当前共 42 项：','当前共 47 项：').replace('程序台地设施 2 项（MG-41/42）。','程序台地设施 2 项（MG-41/42）、生活教学补充 3 项（MG-43～45）、独立收口变体 2 项（MG-34/37-v02）。');p.write_text(s,encoding='utf-8')
p=Path('asset_catalogs/original_civilizations/R02_arcane_reborn/建筑清单.md');s=p.read_text(encoding='utf-8');s+='''\n## 公共中心试验补充（2026-10-09）

以下为真实 NBT 离线素材，尺寸顺序宽×深×高。实际图审与技术验证已通过，作者 draft，未宣称游戏内行为验收。

| ID | 用途 | 尺寸 | 消费方 |
| --- | --- | --- | --- |
| MG-43-v01 | 小实验室：双侧实验台、器材柜 | 23×25×24 | 阵列 |
| MG-44-v01 | 小洗浴房：更衣柜、双池 | 23×25×24 | 阵列 |
| MG-45-v01 | 小商店：门前棚摊、柜台、货箱 | 25×25×24 | 阵列 |
| MG-34-v02 | 原教学翼楼的独立护栏阳台收口版 | 47×29×49 | 阵列 |
| MG-37-v02 | 原双翼宿舍的独立护栏阳台收口版 | 45×39×43 | 阵列 |

原 v01 连接素材保留；程序花园不作为完整结构地块参与此次阵列。
''';p.write_text(s,encoding='utf-8')
for name in ('tools/structure_studio/README.md','E:/Mod_Dev/designer_territoryMod/docs/tools/structure_studio/README.md'):
 p=Path(name);s=p.read_text(encoding='utf-8').replace('191','196');s+='\n公共中心试验增加 MG-43～45 与 MG-34/37-v02：小实验室、洗浴房、小商店和完整阳台收口变体。使用 campus_daily.py 重建；原 v01 可连接素材保留。\n';p.write_text(s,encoding='utf-8')
p=Path('tools/city_experiments/java/com/rinsing/geomantia/systems/city/application/CityArrayExperiment.java');s=p.read_text(encoding='utf-8').replace('Geometry comes from production planners; no world or Forge lifecycle is started.', 'Legacy geometry uses production planners; opt-in rectangle packing is experimental. No Forge lifecycle is started.');p.write_text(s,encoding='utf-8')
