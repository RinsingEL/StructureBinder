# 建筑工作台

在冻结 D3 地形上构建真实 NBT 学院，并用生产 Java 生成地表、道路、台地与挡土、建筑支撑和路灯。它用于快速观察组团、尺度及施工效果。

新增[通用核心台地与设施试验](TERRACE_FEATURES.md)：展示分区可包含多个核心台地组，台地实际边缘和空区决定设施位置，风格配置提供材质与构件。学院与普通城镇共用同一规划器，左侧“试验方案”可切换并生成。

新增[有目的的嵌套组团与设计绿化](INTENTIONAL_GROUPS.md)：真实矩形排布、院落留空、逐栋入口朝向、食堂公共中心及程序花园。当前方案 23 栋，选择左侧“学院 · 公共广场与嵌套组团”可恢复。阵列选择器显示完整层级，选择节点可聚焦；整体组合不编辑单栋素材。显式矩形布局用 gapBlocks 控制净距，疏密选择仅用于旧布局。

## 启动与使用

在仓库根执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/city_experiments/start-workbench.ps1
```

打开 http://127.0.0.1:8767 。脚本构建网页、准备 Java 类路径并启动后台服务，PID 和日志保存在 `runtime/campus-workbench/`；已有服务时直接复用。服务仅监听本机。Python 需要 `tools/structure_studio/requirements.txt` 中的依赖，网页需要 Studio 的 node_modules 及本机 1.20.1 资源缓存；资源准备方法见 Structure Studio README。Java 使用本机 JDK 17，先离线准备，不下载或启动 Minecraft。

左侧选择阵列后，可修改布局、疏密、成员数、模板旋转及素材。嵌套组团可整体移动；父组团控制已嵌套子阵列的最终位置。“生成并查看”重新运行 Java 并载入新版本。素材替换只替换核心／首个指定配套／填充主项，其余指定成员保留；成员数包含核心和指定配套。

“编辑 / 导入完整方案”用于添加、删除阵列，修改嵌套关系、核心台地归属及跨区道路连接。“载入示范学院”仅将默认方案读入编辑框，点击应用后生成。支持导出完整方案和当前视角 PNG，也可切换分区、俯视、去屋顶、地形及标记。拖动旋转，滚轮缩放，右键平移。

“场景总览”聚焦精细建筑与施工结果；“地形总览”显示完整 1024×1024 的 D3 窗口、海岸水域和学院位置。外围按 D3 原始 16 格网格显示，不生成百万级详细方块。背景剔除学院施工窗口，避免覆盖台地开挖、道路和真实 NBT；地形开关同时控制详细地形与外围背景。D3 的 4096 个采样中有 2214 个水域，原本只显示学院内陆窗口才看不到海。

水域范围严格沿用 D3 标记；D3 无水面高程字段，背景水面仅用最高水域采样高程加一得到示意高度，页面明确标注。它不能用于码头或岸墙的高度验收。D3 边缘仍有陆地延伸，不代表已经扫描整座闭合岛屿。

服务重启保留最后成功版本。修改 Python 后重启服务；修改 Java 后重新运行 `prepareCityWorkbench`；修改网页后重新 `npm.cmd run build`。停止服务前可读取 `server.pid` 并核对该 PID 的命令行为本工作台，随后 `Stop-Process -Id <PID>`。

## Tailscale 访问

连接 Tailscale 并启动工作台后，在实现仓根执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/city_experiments/share-tailscale.ps1
```

脚本使用当前节点的 Tailscale DNS，通过 `tailscale serve --bg --http=8767 http://127.0.0.1:8767` 配置 Tailnet 内代理，打印远程入口；本机服务保持 loopback 监听。当前入口为 `http://device.tail9fccec.ts.net:8767/`，客户端需连接有访问权限的 Tailnet，主机需保持运行。该 HTTP Serve 入口使用节点 DNS 地址。

脚本将该地址加入 `runtime/campus-workbench/access.json` 的 `allowedOrigins`，工作台每次写请求读取配置，并要求 Origin 与 Host 对应和 `X-Campus-Write: build`；允许远程生成及导入 DEM。其他站点、缺少 Origin 或写标记会拒绝，既有本机入口继续可用。Serve 后台配置在 Tailscale 重启后恢复；工作台服务本身仍需通过启动脚本运行。关闭这一个代理可执行 `tailscale serve --http=8767 off`，不会重置其他 Serve 服务。

网络代理若截获 Tailnet DNS 请求，需要将此域名设为直连。浏览器回归可设置 `CAMPUS_URL` 为打印的地址，设置 `CAMPUS_NO_PROXY=1` 只让测试浏览器绕过代理，再运行 `node scripts/campus-smoke.mjs`；不改变机器代理设置。

参考：[Tailscale Serve CLI](https://tailscale.com/docs/reference/tailscale-cli/serve)。

## 当前示范学院

默认方案在 `academy-scene.json`，17 个建筑和地块：

| 分区 | 核心与配套 | 地形处理 |
| --- | --- | --- |
| 教学 | 星穹礼堂、中等教学楼、小课堂、阅读小庭 | 核心带两层嵌套配套，共用一处连通的 72 高程台地 |
| 生活 | 小食堂、四栋小宿舍 | 核心带宿舍阵列，共用一处连通的 64 高程台地 |
| 农林 | 小温室、工具房、四块试种田、两块苗圃 | 保持自然地形，田块与苗圃之间留一格通道 |

当前 Java 结果为 42 段主路、街巷和入口接驳，13 盏路灯；188 个区块施工报告均为 APPLIED。教学到生活、生活到农林的显式跨区连接均生成。数字随方案参数变化。

新增九项可重建真实 NBT：MG-25 中等教学楼、MG-26 小课堂、MG-27 宿舍、MG-28 食堂、MG-29 小庭、MG-30 试种田、MG-31 温室、MG-32 工具房、MG-33 小树苗圃。作者源码为 `tools/structure_studio/studio/academy_support.py`，在 Studio 目录执行 `python -m studio.academy_support`，也可通过 `studio.build` 按 MG 编号重建。方块状态、门床配对、植物底座、灌溉和离线通路已检查，作者生命周期保持 draft，未标记游戏内验收通过。

## 实现与方案接口

| 能力 | 复用入口 |
| --- | --- |
| 阵列和嵌套 | CityBlueprintGroupLayoutPlanner、CityContiguousLayoutPlanner |
| 主路和街巷 | CityMainRoadPlanner、CityInternalStreetPlanner，读取作者入口并旋转到世界坐标 |
| 地表和核心台地 | CityDistrictPlanner、CityLandUseSurfacePrintPlanner、CityFoundationElevationPlanner.planForCore |
| 挡土和路灯 | CityLandUseChunkCompiler / CityLandUseChunkExecutor 原有施工规则 |
| 建筑底板支撑 | CityFoundationSupportSettings.column，与模板实际放置共用规则 |
| 公共绿化 | CityPublicGreeneryPlanner 原算法，低密度矮植物预设 |
| 三维结构 | Structure Studio 资源缓存与 Deepslate，读取实际 NBT 和 Java 方块写入 |

`CityCampusExperiment` 放在独立实验 sourceSet；正式模组仍使用原入口。本次增加可显式传入“areaId → 核心占地”的 SurfacePrint 重载，工作台实际传入；没有改成正式 D4 全编译或替换游戏现有规划入口。单成员组不创建额外网格街道，农业地块不逐块接入城市主路。

方案包含 `arrays`、`compositions`、`districts`、`connections`，可选 `seed`、`greenery`。阵列字段与 `array_tools.py` 一致；组合支持 `coreNodeId`。每个分区需要唯一 `districtId`、显示名 `name`、实际 CORE 成员 `coreMemberId`、互斥的叶阵列 `arrayIds` 和 `platform` 开关。每个阵列必须归属一个分区。`connections` 为分区间 `{from,to}` 列表。

工作台从实时作者目录读取尺寸、入口、地面基准和 NBT 哈希。`infrastructure` 标签与独立树木素材划归程序消费，从选择器排除，服务端也拒绝放进阵列。小庭和苗圃是完整功能地块，可作为组团配套；其中植物随地块制作，公共空间散点绿化由 Java 生成。桥梁、城门、码头的专门消费系统本轮未接入。

MG-34～40 补充五项多层组团建筑与两项连廊，详见[第一批组团素材](../../asset_catalogs/original_civilizations/R02_arcane_reborn/组团素材第一批.md)。刷新网页即可在建筑选择器选用 MG-34～38；MG-39/40 为程序消费件，仅在 `/index.html?asset=MG-40-v01` 结构库页面预览。作者连接端口已做净空和手工拼接通路检查，自动连接尚未接入，当前 R7 也未重排。

后续教学区尺度试摆已发布为 R8，使用礼堂、两栋直翼、研究书楼、两间小课堂与两块小庭。可通过 `/?district=teaching` 直接聚焦分区，详见[教学区试摆说明与方案](TEACHING_TRIAL.md)。该版本先手工摆位比较尺寸，默认示范配方继续保留原 R7，连廊自动拼装未接入。

GET `/api/campus` 返回当前世界、元信息与目录；GET `/api/campus/default` 读取示范方案；POST `/api/campus/build` 接收 `{scene,baseRevision}`。写请求要求来自本机或显式配置的工作台同源地址，并带 `X-Campus-Write: build`。旧版本返回 409；非法成员、碰撞、水域、越界或未知结构会拒绝。失败证据保留，但不会发布为新场景。限制为 180 成员、20 万地形柱及 120 万可见方块。

GET `/api/campus/terrain/context` 提供完整 D3 的源文件哈希、bounds、step、`[x,z,elevation,water]` 采样、当前详细窗口边界及示意水面说明；只供网页背景使用，不改变 Java 施工数据。外围即使在逐格 DEM 模式下也仍使用 D3。

启动准备后可用 `python tools/city_experiments/workbench.py --build-only` 生成默认场景，不启动服务。每轮证据在 `runtime/campus-workbench/revisions/rXXXX/`：请求、方案、Java 输出与日志、SurfacePrint、区块报告、拼合三维世界和摘要。只有成功写完所有文件后才更新 `state.json`。

## 验证与边界

新增独立[场地构图控制台](SPACE_COMPOSER.md)：`/spaces` 可比较星泉广场、阅读花园、仪式庭院，编辑嵌套节点、素材池、留白及入口通路；不会覆盖学院岛版本。

### 城区外围与对外出口

可选 `outerGreenery:{width:20,maximumPlacements:260}` 从核心台地与连接台地的合并外缘生成种植带。外部连通域排除封闭院落；保留原有高度和两格岸边净空，避让道路、港口预留区及已有设施。使用既有外围树、灌木、花丛模板池，分片概率随离城距离降低，尺寸和支撑不合格即跳过。先完成内部模板再放外围模板，避免外围抢占院内绿化名额。全局 `greenery:false` 关闭外围种植。

可选 `externalAccess` 由 AI 根据本次地理信息选择并写入明确路线，不硬编码学院坐标：`waypoints` 为轴向 `{x,z,surface}` 序列（surface 为计划行走面高度，允许半格），`width` 为 3/5/7；`landing` 与 `harborReservation` 使用闭合 minX/maxX/minZ/maxZ 矩形。起点必须接现有道路；允许起点半格踏步。程序检查采样陆地、建筑/设施净空、道路高度、最大八格土方，按半格台阶施工并开通沿线边缘护栏。港口预留范围只用于避让，不自动建造码头或填海。它是工作台独立对外道路试验，报告 `externalAccessPlan` 与原生产 Java 内部道路报告分开记录。

第三题 R31 选择住宿区东侧道路 `(-2830,5394)`，向东后折向南至 `(-2760,5413)`；沿坡连接东南小海湾。岸边承接平台 11×11，新增道路/平台共 551 柱；外围增加 141 棵树、119 处灌木。内部 174 处模板、23 栋建筑、4 个核心台地及设施保持 R30 一致。原有 22 条建筑间路线和新增港口承接路线全部通过离线碰撞通行检查，浏览器截图位于 `runtime/academy-fringe-20261011/applied/`。

### 配置绿化模板池

学院组团默认方案通过 `greeneryTemplates` 引用素材目录下的 `R02_arcane_reborn/greenery-pools.json`。`pools` 将 `trees/gapTrees/shrubs/hedges/flowers/planters` 映射到池名；修改目录中的候选与权重即可调整选择。`spacing` 控制各类中心间距，`maximumPlacements` 限制总量。取消配置保留旧版程序植物；`greenery:false` 同时关闭两种植物模式。

生成时冻结池目录，并校验 NBT、作者文件哈希、尺寸与地面基准。固定种子加权选择真实 NBT，只在既有花床和组团连接绿地内落位；完整包围盒避开建筑、设施、道路、公共通路和已放植物，尺寸不合适则换候选或跳过，不清除已有方块。树木默认分别使用庭院池和外围池。生成报告 `greeneryTemplatePlan` 记录实际素材、旋转、坐标与哈希。

可选 `greeneryTemplates.layeredPlanting:true` 允许灌木和花丛在树冠投影内寻找实际净空，仍检查模板全部体积，不能穿过树干或低树冠。树与树、花槽和绿篱继续保守避让。`slotLimits` 可分别限制 trees/planters/hedges/shrubs/flowers 数量，防止先放的类别占满总预算；花丛优先靠近已有通路及设施边界。`outerGreenery` 可独立指定 trees/shrubs/flowers 的 `spacing` 与 `slotLimits`，不再只能使用固定外围间距。缺省保持旧版行为。

2026-10-11 已应用第三题学院岛 R30：174 处模板绿化（树 22、灌木 49、绿篱 62、花丛 8、花槽 33），保持 R29 的 23 栋建筑、4 个台地、76 段道路及设施几何，22 条通行检查通过。证据位于 `runtime/academy-greenery-20261011/`。本入口仍为离线工作台，尚未接入正式游戏生成。

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
./gradlew.bat test -x processHarnessResources --offline --no-daemon --tests '*CityFoundationElevationPlannerTest' --tests '*CityFoundationSupportSettingsTest' --tests '*CityLandUseSurfacePrintPlannerTest' --tests '*CityLandUseChunkExecutorTest' --tests '*CityPublicGreeneryPlannerTest'
python tools/city_experiments/test_workbench.py
python tools/city_experiments/test_array_tools.py
```

网页验证在 Studio 目录运行 `node scripts/campus-smoke.mjs ../../runtime/campus-workbench/captures-final --rebuild`；它实际修改宿舍数并生成，检查旧版本拒绝，使用方案编辑器恢复默认，再核对三区、去屋顶及原 Studio 页面。预览图片与浏览器证据落在该目录。

当前仓库自带 Harness 运行时指纹与 build.mjs 不一致，普通 test 任务会被它的资源校验阻断。上述聚焦 City 测试跳过该无关附加模块的资源打包，仍编译 Java 并执行 62 项相关测试；没有重建或修改 Harness 发行包。

D3 是 16 格采样，虚拟世界使用双线性插值高度；农业 NBT 按每柱高度贴合，仅用于预览。区块执行使用宽松的内存世界，未模拟原版流体、光照、植被 tick、实体寻路、已有玩家结构或真实世界替换资格。generationReady 始终为 false；不把预览连通、NBT 离线检查或 APPLIED 报告当成游戏内验收。旧实验 D3、冻结目录及历史结果保留。

## 逐格 DEM 备用入口

本阶段默认继续用 step=16 的 D3，足以比较学院构图、建筑尺度、嵌套组团及台地主次。逐格 DEM 接口作为后续细部核对的备用能力，不要求现在扫描。

原存档在另一台电脑；本机尚无对应 DEM，界面“逐格 DEM”保持不可选。使用更新后的模组打开原存档后，可 POST `/realm/city/export_terrain_dem`，请求 `{runId:"provider_3d25631f_r19200",citySeedId:"city_jingchao_capital",step:1}`。接口校验世界身份和冻结 D3 的生成器指纹，复用 RTF 二维高度查询逐格导出地面和水面；不生成 Chunk，不改变 D3、审批或设计队列，采样运行于 API worker。当前只支持与 D3 同源的 RTF 生成器，不等同于读取已生成区块的最终方块地表。

原存档机器可在实现仓根运行 `python tools/city_experiments/dem_tools.py scan`，默认连接本机 5000 端口；返回和导入的文件保存在 `runtime/campus-workbench/terrain/`。将 `dem-step1.json` 带到工作台机器，用网页“导入逐格 DEM”或 `python tools/city_experiments/dem_tools.py import <文件>` 导入。再选择“逐格 DEM”并生成。导入要求城市、D3 原始文件 SHA-256 和生成器指纹一致；缺格、不完整覆盖当前场景及 24 格地形边界、错误水面高度均拒绝，不静默退回插值。每轮生成另存实际使用的 DEM 快照。

DEM 的 `city_terrain_dem.v1` 保存闭合 bounds、step=1、order=z_then_x、source、d3TerrainHash，以及连续 groundY / waterSurfaceY 数组；无水格使用 null。最多 1048576 格。离线回归 `python tools/city_experiments/test_dem.py` 使用临时合成夹具验证一格深坑、水面和相邻高度不被插值，夹具不发布为真实扫描数据。

当前主路规划和核心台地设计高程仍来自 D3；选择 DEM 后，虚拟世界逐格地表、建筑下方支撑和区块施工的原地面采样使用 DEM。它提高预览细节，不自动将正式 D3 规划改为逐格规划。
