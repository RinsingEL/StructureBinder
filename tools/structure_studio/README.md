# 国度 · 结构工坊

不启动 Minecraft 的原创结构制作与验收工具。用 Python 编写结构与空间标记，导出原版 1.20.1 NBT，再由本地浏览器读取**导出后的 NBT**预览。不是方块编辑器的占位界面：旋转、缩放、平移、XYZ 剖切、去屋顶、楼层切片、房间聚焦、标记与室内漫游均已实现。

正式需求与契约入口：`E:\Mod_Dev\designer_territoryMod\docs\tools\structure_studio\README.md`。

## 启动

在本目录运行，首次安装依赖并准备本机已有的 **1.20.1 client.jar**：

```powershell
python -m pip install -r requirements.txt
npm.cmd ci
node scripts/export_registry.mjs
python cli.py prepare --jar '本机的 Minecraft 1.20.1 client.jar 路径'
npm.cmd run build
python -m studio.samples
python cli.py serve
```

打开 `http://127.0.0.1:8765`。以后启动只需要最后一条命令；修改前端后重新 build，修改建模源码后重新生成对应资产。

也可以运行 `start.ps1 -ClientJar <路径>`，它只在缓存或编译产物不存在时准备资源，在前台启动服务。资源准备从本机 JAR 提取模型、方块状态与原始纹理到被 Git 忽略的 `.cache/`，不打包或下载游戏资源。运行服务仅监听 `127.0.0.1`，只提供读取。

## 建模与验收

- `studio/model.py`：体素、门床、空间与工作点；确定性压缩 NBT。明确写入的 air 会清除房间，未写入的位置保持不动。
- `studio/samples.py`：兼容性样板与第一间面包铺的可重建源码。
- `studio/build.py`：按编号、结构族或 `all` 重建已实现的作者资产；`transport.py` 制作交通核心，`agriculture.py` 制作六种独立农田布局，`components.py` 只提供部件。
- `studio/shops.py`：七种不同业态的铁路街区商铺，独立设计占地、房间、行业设备与生活空间；与面包铺一起构成首组八种商铺。
- `studio/desert.py`：沙海文明的围院驿站、地下蓄水厅、市集、医馆、星象台及两种历史设施，分别记录水源、埋置、商路和观测条件。
- `studio/oasis_life.py`：六种风塔住宅与六种灌溉田，分别覆盖单户、双户、内庭、叠居、街角、分台住宅，以及窄条、分水、内院、边角、分拣棚与阶台田。
- `studio/validate.py`：重新读取 NBT，检查边界、调色板、状态、门床配对、标记与哈希。
- `studio/navigation.py`：使用 1.20.1 碰撞盒的离线步行初筛，检查入口到标记站位；0.6×1.8 玩家体积、0.6 台阶高度。木门按玩家可打开处理，铁门保持原状。不模拟跳跃、游泳、爬梯和游戏逻辑。
- `scripts/capture.mjs`：真实浏览器自动拍摄外观、分层、房间和标记，遇到渲染错误或缺纹理则失败。**拍图不自动批准审美**。
- `web/site.js`：按作者条件显示独立的平地、岸线或微坡示意，可选草地、沙地、红沙、雪地或灰化土表层，地形不写入 NBT。
- `studio/publish.py`：在实际看图并写下审查结论后归档证据；拒绝过期截图、缺失图像和未解决的数据/通路问题。

```powershell
python -m unittest discover -s tests -v
python cli.py validate SR-F01-v01
npx.cmd playwright install chromium
npm.cmd run capture -- SR-F01-v01
node scripts/smoke.mjs
```

按编号或结构族重建：`python -m studio.build SR-01 WT-01 SR-F02`。`all` 只重建**已有作者源码**的资产，不会将清单中未建模的条目自动变成模型。

农田检查基于重新读取的 NBT 核对四类原版作物的耕地支撑和 4 格范围水源，避免只相信作者声明。显示的地形是理想条件示意，截图会注明“不写入 NBT”。

杜鹃与开花杜鹃的底座另按原版 1.20.1 的 dirt 标签、黏土或耕地检查。这不是对所有植物或所有邻居更新规则的模拟。按编号构建遇到数据错误、通路失败或警告时返回非零退出码。

已有 Chromium 可通过 `STUDIO_CHROMIUM` 指定可执行文件，省去下载。`STUDIO_URL` 可覆盖本地服务地址。截图默认到 `runtime/captures/<编号>/`；也可传入第二个输出目录参数。截图清单携带 NBT 和作者 JSON 的 SHA-256，模型或标记变更后必须重拍、重审。

实际查看截图后，编写 JSON 数组记录 `id`、`reviewer`、`checks` 和 `reviewed_views`，可附 `revisions` 与 `limitations`。执行 `python -m studio.publish runtime/reviews.json` 会复核当前哈希及数据结果，再将截图归档到资产 `previews/` 并写入独立的 `review.json`。截图清单中的 `visual_review: pending` 仅表示拍摄工具自身未作视觉批准，最终结论以匹配当前哈希的 `review.json` 为准。

NBT、作者 JSON、报告及归档截图先写入同目录临时文件，完成后替换目标，避免读者读到截断文件；替换失败会保留旧文件并报告错误。各文件独立替换，整个资产批次不是文件系统事务，完成状态仍须核对双哈希。

## 当前范围

支持本库的原版 1.20.1 方块与实体方块外形。灯光为统一查看光照，纹理动画使用首帧，颜色使用预览器默认色；不替代 Minecraft 的光照、流体、邻居更新、红石或模组机器测试。漫游允许穿墙，通路证据来自独立碰撞初筛和剖面检查。标记是作者记录，不会自动修改国度 Mod 的运行时模板目录。

已经制作商住与多业态商铺、客货车站、客运码头、农田、沙海公共设施、风塔住宅与绿洲灌溉田，并支持环境示意和同族对照，**整份文明清单仍在制作中**。唯一进度源位于 `asset_catalogs/original_civilizations/` 对应核心与填充清单。
