# 城市主体快速对照 · 2026-10-10

在同一份学院岛 D3 与逐格 DEM 上，复用既有素材、Java 排布、道路、共享台地和真实 NBT 拼合。每版 8 栋、3 个核心功能区、3 个共享台地；保留学院 R29，没有改正式 Minecraft 生成器。

| 方案 | 组织意图 | 查看 |
| --- | --- | --- |
| 集市商镇 | 市场、面包坊、仓库、住宅围合交易广场，旁接行会织造与客栈街屋 | http://127.0.0.1:8771 |
| 工匠聚落 | 高炉、武备工坊、仓库集中，旁接铸币切磨与工人生活组 | http://127.0.0.1:8772 |
| 园艺疗养聚落 | 疗养、园艺、住宅围合花园，旁接访客接待和酿造储存组 | http://127.0.0.1:8773 |

场景 JSON 为可复现输入；subjects.json 保存意图。run.py 为各版提供独立输出和服务。fit.py 初次选址需要 numpy：先用真实 Java 求组团几何，再在 D3/DEM 上检验全部建筑占地及 10 格边缘无水，整体平移布局，优先适度高差。当前 JSON 已保存选址，不必重复运行 fit。

仓库根运行（其他两版将 market 替换为 craft / woodland，端口用 8772 / 8773）：

```powershell
python tools/city_experiments/quick_subjects/run.py market --build
python tools/city_experiments/quick_subjects/run.py market --serve --port 8771
```

输出为 runtime/city-subjects-20261010/<case>/，成功目录由 state.json 指定；失败商镇初次水域选址保留。详细地形使用逐格 DEM，外围仍为 D3。三服务运行时，可运行 `node tools/city_experiments/quick_subjects/capture.cjs` 重拍并检查浏览器。比较图在 runtime/city-subjects-20261010/comparison.png。

## 验证与观察

- 三版浏览器检查通过，无脚本、缺失纹理或渲染错误；同时检查三个核心、三个台地，各台地唯一核心及统一高度。已实际查看三版全景。
- 商镇 36 段道路，工匠 29 段，园艺疗养 31 段。段数不代表质量。
- 用既有 check-campus-walk.py 检查，商镇和工匠各 7 条真实方块步行路线通过。园艺版 ±2 格裁剪下 6/7 通过；仅将检查裁剪扩至 ±6 格后 7/7 通过，没有添加或修改世界方块。原失败证据保留，不能宣称原窄路检查通过。
- 步行证据位于对应 revision；截图与浏览器证据位于各 case/captures。

三版有交易院落、生产组、疗养花园等局部组织差异，但整体仍是一侧主体、另一侧两个配套组，宏观轮廓相似。更换素材显著改变外观，不足以证明动态文明设计。

选址只验证干地、空间与高差，不证明矿产、森林或产业需求存在；未生成外部交通、码头或产业模拟。generationReady=false，不能代替游戏内验收。跨地貌规划仍需独立验证。
