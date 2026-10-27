# 学院真实展开数据

## WTD 首轮动效新增数据（2026-10-27）

- `atlas-base.json`：从修改前的独立 `wtd-atlas.html` 原样提取世界、领土及局部地图载荷；图片与学院几何仍引用原有独立资源。原始本机目录缺失时无需更换案例即可重新构建。
- `atlas-revision.json`：七国特点摘要及原设定、地形块原始编号、16 区公开声明与最后修订请求、素材用途标签、南宿院选取请求。来源文件名和会话哈希见 `provenance`，不附原始对话或内部推理。
- 地形来自既有学院岛备份；导入时将每格类型与原页面逐格比较，完全一致才接受。国度 ID 与中文名也与原页面逐项比较。
- 已核对会话第55行读取上下文素材摘要，第72行读取学院作者标签（第74行返回），第80行才声明总览。页面将标签放在声明之前，统计 overviewMaterials；第84行按区查询的24种素材另存 materials，不提前冒充总览标签。第64行失败只说明未声明区不能按区查询。同一素材可有多个用途，画面展示常见用途节选。
- `designs` 保存各区公开修订请求摘要，最终展示几何仍取原有 revision 74；16 区请求中的组 ID 完整覆盖86个唯一计划单元。D7 是压缩回放，不是实际并发工具执行；外框仅为占地归并近似。

常规构建：在实现仓根目录运行 `python devlog/act2/build-atlas.py`。离线逻辑检查：`node devlog/act2/check-atlas.cjs`。后者不启动浏览器，不能替代字体、排版和动效的视觉验收。

需要重新导入时运行 `python devlog/act2/prepare-atlas-revision.py <学院岛备份.zip> <原始回放.jsonl>`；它只提取公开工具字段，并验证既有地图身份。

2026-10-27 从用户提供的 steps 目录导入。原始资料保存在项目 `run-data/devlog/academy-20261020/steps/`，复制校验清单为旁边的 `import-manifest.json`。共 591 个文件、247,416,266 字节；逐文件 SHA-256 与复制前后源目录一致，源目录未移动、未修改。

原始资料沿用仓库已有 `run-data/` 忽略规则，不混入演示源码。可供独立页面构建使用的精简数据为 [academy-20261020.json](academy-20261020.json)，约 302 KiB；页面已接入；演示用道路、庭院与体素素材卡另由 `prepare-academy.py` 导出到 `academy-animation.json`。

## 身份与完整性

- 城市：`city_jingchao_capital`。
- Context：`sha256:baf5f9fb0f6e88971804af68d624b7a2490aa85d2d8a96c286c78b19f0436367`，与 2026-10-20 原会话、三会话报告一致。
- D4 `structure_anchor_plan.json`：86 个展开单元。
- D7 `placed_structure_ledger.json`：86 条记录；原日志恢复的前两条教学楼记录，其模板、hash、位置、actualFootprint、templateDatumY 均一致。
- `design_versions/`：71 个历史版本。精简文件保留全部版本索引，以及本次回放所需的八份几何快照。
- `city_blueprint_draft.json` 标记 `superseded_by_acceptance` 是已接受方案的状态，不能只看该文件就判断缺失建筑数据。

## 回放快照

revision 是保存后的工作流版本，不是请求中提交的旧 workflowRevision。

| revision | 全城展开单元 | 南宿院结果 |
| --- | ---: | --- |
| 61 | 41 | 初次申请四栋，实际保留一栋 |
| 62 | 41 | 修改停止条件后，仍保留一栋 |
| 63 | 44 | 调整核心及配套，实际保留四栋 |
| 69 | 83 | 平移前，四栋 |
| 70 | 83 | 四栋各向东12、向南6；位置与占地框四条边均已核对 |
| 71 | 83 | 改生活巷：一个核心 + 三栋小宿舍配套 |
| 73 | 86 | 北院补餐饮后 |
| 74 | 86 | 最后保存版本 |

## 使用边界

- `snapshots[].anchors` 用于设计过程回放；`d7.placedStructures` 用于施工后的状态。不能把最终状态提前放进早期步骤。
- 单元不仅包括建筑，也包括种植、育苗等内容；86 不应直接写成“86栋建筑”。
- footprint 是程序记录的矩形占地包围框，不是墙体、屋檐或建筑非空气体素的精细轮廓。
- D7 账本存在不代表整个施工流程无错误，验收仍需单独核对质量报告和 land-use owner completion。
- 所有选取快照保留原 versionId、源文件相对路径、SHA-256 和 createdAt；原始数据没有为了演示被重写。
