# 公共空间正式 Java 接入

启用入口是 `CityBlueprint.outdoorPlan.publicRealm`（`GENERATE` 模式）。未提供时保留原有城市行为。通过 `city_d4_example` 查询 `caseId=public_realm` 可获得素材尺寸、地面基准、哈希、池和三种完整构图示例。

```json
{
  "schema": "city_public_realm.v1",
  "catalog": "academy",
  "seed": 20261011,
  "spaces": [],
  "greenery": {
    "pools": {"trees":"courtyard_trees", "shrubs":"low_shrubs", "flowers":"flower_patches", "hedges":"hedge_segments", "planters":"planters"},
    "spacing": {"trees":13,"shrubs":5,"flowers":4,"hedges":8,"planters":24},
    "slotLimits": {"trees":80,"shrubs":95,"flowers":220,"hedges":65,"planters":40},
    "maxPlacements":500,
    "layeredPlanting":true
  },
  "fringe": {
    "width":20,
    "pools":{"trees":"outer_trees","shrubs":"low_shrubs","flowers":"flower_patches"},
    "spacing":{"trees":10,"shrubs":6,"flowers":4},
    "slotLimits":{"trees":150,"shrubs":120,"flowers":230},
    "maxPlacements":500,
    "layeredPlanting":true
  }
}
```

- `spaces[] = {id, owner, origin?:{x,z}, plan}`。`plan` 直接使用 `space-examples.json` 的 plan 结构；owner 是户外编译后 publicSpaces 的 owner（有 districtDesigns 时为 districtId）。省略 origin 会居中；同一 owner 有多个院落时须用 origin 明确选择。场地必须完全处于已接受的平坦公共地面，不增加建筑核心或台地。
- 构图支持 POINT/LINE/GRID/RING、六层嵌套、相对平移和四向旋转、朝向中心、加权素材池、留白、多入口道路，以及 RECTANGLE/ELLIPSE/PATH_EDGE 种植区。树冠下低层种植仍检查真实体积；池容量不足只生成能放下的数量并报告。
- `externalAccess = {id,width,waypoints:[{x,z,surfaceY}],landing?:{minX,minZ,maxX,maxZ},harborReservation?:{minX,minZ,maxX,maxZ}}`。世界坐标和步行面高度由 AI 指定；高度精度 0.5，路宽 3/5/7，每段轴向、最长 512，坡度不超过 0.5/格，土方不超过 8。起点须连接原道路；避让建筑、水域、未知采样和 D5 保留区。接岸平台最大 65×65；港口仅预留，不生成码头或填海。
- 外围绿化从采样边缘向城市外围识别，排除封闭庭院，保留地形高度，避水与陡变，向外分片减密。只使用已有可承载地面；运行时地形不一致、体积被占或根部悬空会报告施工失败，不静默清除建筑。

正式链路：D4 蓝图/分区配置 → D6 后 `LandUsePlanningService` → `CityPublicRealmPlanner` → 带哈希的 SurfacePrintPlan → `CityLandUseChunkCompiler` → `CityLandUseChunkExecutor`。`quality_report.json.publicRealm` 保存配置、实际素材/坐标/旋转、NBT 与作者哈希、分类数量、容量警告及出口信息。启用时 D6 旧自动种树退出，防止重复占位。地基先写，模板覆盖层后写，保留楼梯、半砖、树叶、灯笼等全部状态。

运行时资源由真实 NBT 导出，当前包含 MG-50～61 和三个构图例引用的 MG-10/29/42，共 15 个素材、5417 个非空气方块。不把整座花园预制成单一模板。增加素材后运行 `python tools/city_experiments/export_public_realm.py`；`--check` 校验提交的资源与作者/NBT/池一致。方块实体和生物实体需单独适配，导出时拒绝静默丢弃。

Java 与 Python 使用各自确定性随机序列，同一 Java 配置可重复，跨语言不承诺相同抽样坐标。冻结模板在所属区块事务内预检、写入和回滚；沿用既有逐区块施工模型，不提供整座跨区块城市的单一事务。自动测试包含三例、冻结往返、负坐标、30 个所属区块实际写入、道路/水域/保留区拒绝及生产入口；不替代真实 Minecraft 世界验收。

聚焦验证：

```powershell
.\gradlew.bat test -x processHarnessResources --offline --no-daemon --tests '*CityPublicRealmPlannerTest' --tests '*CityOutdoorBlueprintCompilerTest' --tests '*CityLandUseChunkExecutorTest' --tests '*CityLandUseChunkCompilerTest' --tests '*CityBlueprintCodecTest' --tests '*CityD4WorkflowTest' --tests '*ProviderPlanningToolCatalogTest' --tests '*CityPublicGreeneryPlannerTest'
python tools/city_experiments/export_public_realm.py --check
```

`processHarnessResources` 与本次生产功能无关，本轮不重打既有 harness 资源。
