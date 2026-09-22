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
- `studio/oasis_yards.py`：换乘兽栏、葡萄棚和货棚小摊各四种，区分牵引、护理、采收、交易与寄货空间；葡萄果串采用原版紫晶簇外观，实际作物与动物行为另行接入。
- `studio/oasis_crafts.py`：商人庭院宅与玻璃工坊各四种；家居、会商、货物、窑前操作和窑后维护分别标记，覆盖转角、双庭、两层和分台布局。
- `studio/oasis_hospitality.py`：四种旅人客栈与八种日常商铺；客房、餐饮、盥洗、维修及行业内饰分别布置，覆盖围院、窄街楼铺、转角院和微坡分台。
- `studio/waterway.py`：造船坞、船闸管理所、港口海关、水利会馆、旧泵房与旧水神庙；分别布置生产、记录、货运、教学、维护和生活空间，记录岸线或上下游高程条件。
- `studio/waterway_trade.py`：临水货栈与沿河商住楼各四种；窄岸、双层、贯通巷、验货院、街角裁帆、前低后高和阶岸院宅分别设计内饰、搬运路线、家庭空间及岸线接驳。
- `studio/mountain_forge.py`：山腹矿运、提升、锻炉、行会、纪念、旧矿口与排水核心；石台、矿道和高差条件分别记录，机械使用原版方块表达静态外形。
- `studio/forest_symbiosis.py`：种子档案、林间兽驿、议事、市集、学堂及两处历史设施；保留根盘和林隙用途，房间与树旁通路分别标记。
- `studio/cloud_navigation.py`：泊塔、船库、导航、气象、议事及两处历史设施；落地柱梁、台地高差、外阶护栏和特殊交通接驳分别核对。
- `studio/mountain_life.py`：六种矿工宿舍与六种工匠住宅；按多人、独室、院落和双层分别组织生活、工位与连续楼梯护栏。
- `studio/mountain_trade.py`：宝石商行、矿料仓与炉边酒馆各四种，以及八种行业商铺；区分检验、原料、加工、陈列、寄存和生活后勤。
- `studio/mountain_outside.py`：六种山地田与四种矿业配套棚；按梯台、窄地、温室及存料维修条件布置生产设施。
- `studio/forest_life.py`：树根住宅与守林居所各六种；根盘旁通路、分户睡眠、厨房餐席、读写及家务空间随布局分别标记。
- `studio/forest_crafts.py`：菌菇、药草、蜂蜜工坊各四种与八种林间商铺；原料、分拣、加工、交易和店主生活分区。
- `studio/forest_gardens.py`：六种林间田、四种藤架与四种工具棚；保持根盘、采收路径及组装、晾材、药材分拣和维修发料工位。
- `studio/cloud_living.py`：六种悬臂住宅、六种船员宿舍和四种旅店；稳定岩台支撑、外阶、客房与生活内饰分别设计。
- `studio/cloud_food.py`：四种温室、六种台地田及四种棚亭；种植、灌溉、收获和避风休憩按台地条件标记。
- `studio/cloud_market.py`：四种航运仓与八种商铺； 货运通道、行业操作台、陈列交易与商住后勤分开布置。
- `studio/arcane_academy.py`：传送驿站、检疫、晶核配给、学院、卷藏与两处专项；隔离、教学、研究与驻守空间分别标记，传送及供能为静态表达。
- `studio/arcane_gardens.py`：六种灌溉试验田、四种藤架与四种研究配套棚；独立构建，不依赖正在返工的学院住宅和商铺模块。
- `studio/tidal_coral.py`、`studio/tidal_civic_details.py`：海洋幻想港口、考察站、学馆、议事庭与两处专项；贝壳拱顶、干湿分舱、潮位和海床支撑分别表达。
- `studio/steppe_caravans.py`：大帐集市、议事家帐、祖灵石阵、旧营遗存及山口营；圆帐、货车侧带、仪式场和通路视野分别设计。
- `studio/northern_seafarers.py`：船屋、浴屋、史诗会堂与补给、回收、海防专项；岸线、风雪遮蔽、高差及维修生活分别设计。
- `studio/validate.py`：重新读取 NBT，检查边界、调色板、状态、门床配对、作者角色取值、标记与哈希；专项使用 structure，重复填充使用 fill。
- `studio/navigation.py`：使用 1.20.1 碰撞盒的离线步行初筛，检查入口到标记站位；0.6×1.8 玩家体积、0.6 台阶高度。木门按玩家可打开处理，铁门保持原状。不模拟跳跃、游泳、爬梯和游戏逻辑。
- `scripts/capture.mjs`：真实浏览器自动拍摄外观、分层、房间和标记，遇到渲染错误或缺纹理则失败。**拍图不自动批准审美**。
- `web/site.js`：按作者条件显示独立的平地、岸线、微坡或两级航道示意，可选草地、沙地、红沙、雪地或灰化土表层，地形不写入 NBT。
- `studio/publish.py`：在实际看图并写下审查结论后归档证据；拒绝过期截图、缺失图像和未解决的数据/通路问题。

```powershell
python -m unittest discover -s tests -v
node --test tests/site.test.mjs
python cli.py validate SR-F01-v01
npx.cmd playwright install chromium
npm.cmd run capture -- SR-F01-v01
node scripts/smoke.mjs
```

按编号或结构族重建：`python -m studio.build SR-01 WT-01 SR-F02`。`all` 只重建**已有作者源码**的资产，不会将清单中未建模的条目自动变成模型。

农田检查基于重新读取的 NBT 核对四类原版作物的耕地支撑和 4 格范围水源，避免只相信作者声明。显示的地形是理想条件示意，截图会注明“不写入 NBT”。

船闸使用 `canal` 条件示意：限定航道 X 范围，以 `upper_end_z` 划分上游与下游参考水面，方便检查高差、岸壁和桥梁净空。模型中的闸门、闸室、水泵与水工展示均为静态几何，水位展示不模拟实际流体或船舶通行。

杜鹃与开花杜鹃的底座另按原版 1.20.1 的 dirt 标签、黏土或耕地检查。这不是对所有植物或所有邻居更新规则的模拟。按编号构建遇到数据错误、通路失败或警告时返回非零退出码。

已有 Chromium 可通过 `STUDIO_CHROMIUM` 指定可执行文件，省去下载。`STUDIO_URL` 可覆盖本地服务地址。截图默认到 `runtime/captures/<编号>/`；也可传入第二个输出目录参数。截图清单携带 NBT 和作者 JSON 的 SHA-256，模型或标记变更后必须重拍、重审。

屋顶复核按实际墙线、柱线检查支撑高程：外挑坡顶在内缩后的高度与檐口不同，必须有连续承檐梁；半砖要按上下半位置检查接触。远景外观与通路通过不能代替这项剖面检查。

实际查看截图后，编写 JSON 数组记录 `id`、`reviewer`、`checks` 和 `reviewed_views`，可附 `revisions` 与 `limitations`。执行 `python -m studio.publish runtime/reviews.json` 会复核当前哈希及数据结果，再将截图归档到资产 `previews/` 并写入独立的 `review.json`。截图清单中的 `visual_review: pending` 仅表示拍摄工具自身未作视觉批准，最终结论以匹配当前哈希的 `review.json` 为准。

NBT、作者 JSON、报告及归档截图先写入同目录临时文件，完成后替换目标，避免读者读到截断文件；替换失败会保留旧文件并报告错误。各文件独立替换，整个资产批次不是文件系统事务，完成状态仍须核对双哈希。

## 当前范围

支持本库的原版 1.20.1 方块与实体方块外形。灯光为统一查看光照，纹理动画使用首帧，颜色使用预览器默认色；不替代 Minecraft 的光照、流体、邻居更新、红石或模组机器测试。漫游允许穿墙，通路证据来自独立碰撞初筛和剖面检查。标记是作者记录，不会自动修改国度 Mod 的运行时模板目录。

已经制作商住与多业态商铺、客货车站、客运码头、农田、沙海公共设施、风塔住宅与绿洲灌溉田，以及水网修造、船闸、海关、水务与历史设施，并支持环境示意和同族对照，**整份文明清单仍在制作中**。唯一进度源位于 `asset_catalogs/original_civilizations/` 对应核心与填充清单。
