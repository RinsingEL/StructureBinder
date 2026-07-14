import type { ToolDefinition } from "../shared/types.js";

const decorationShapeSchema: Record<string, unknown> = {
  oneOf: [
    decorationVariant("target_mask", {}),
    decorationVariant("rectangle", {
      minU: integer("局部 U 最小值。"),
      minV: integer("局部 V 最小值。"),
      maxU: integer("局部 U 最大值。"),
      maxV: integer("局部 V 最大值。"),
    }, ["minU", "minV", "maxU", "maxV"]),
    decorationVariant("ellipse", {
      centerU: integer("局部 U 中心。"),
      centerV: integer("局部 V 中心。"),
      radiusU: positiveInteger("U 半径；与 radiusV 相等时为圆形。"),
      radiusV: positiveInteger("V 半径；与 radiusU 相等时为圆形。"),
    }, ["centerU", "centerV", "radiusU", "radiusV"]),
    decorationVariant("ring", {
      centerU: integer("局部 U 中心。"),
      centerV: integer("局部 V 中心。"),
      innerRadiusU: positiveInteger("U 内半径。"),
      innerRadiusV: positiveInteger("V 内半径。"),
      outerRadiusU: positiveInteger("U 外半径，必须大于内半径。"),
      outerRadiusV: positiveInteger("V 外半径，必须大于内半径。"),
    }, ["centerU", "centerV", "innerRadiusU", "innerRadiusV", "outerRadiusU", "outerRadiusV"]),
    decorationVariant("polygon", {
      vertices: {
        type: "array",
        minItems: 3,
        description: "局部 U/V 顶点；不是世界坐标。",
        items: strictObject({ u: integer("局部 U。"), v: integer("局部 V。") }, ["u", "v"]),
      },
    }, ["vertices"]),
  ],
};

const decorationPatternSchema: Record<string, unknown> = {
  oneOf: [
    decorationVariant("uniform_fill", {
      paletteSlotId: nonEmptyString("要填充的 content palette slot。"),
    }, ["paletteSlotId"]),
    decorationVariant("cross_section_repeat", {
      axis: { type: "string", enum: ["u", "v"] },
      offsetBlocks: integer("横断面相对局部原点偏移。"),
      bands: {
        type: "array",
        minItems: 1,
        items: strictObject({
          paletteSlotId: nonEmptyString("该带使用的 content palette slot。"),
          widthBlocks: positiveInteger("带宽。"),
        }, ["paletteSlotId", "widthBlocks"]),
      },
    }, ["axis", "offsetBlocks", "bands"]),
    decorationVariant("parallel_rows", {
      axis: { type: "string", enum: ["u", "v"] },
      paletteSlotId: nonEmptyString("行列使用的 content palette slot。"),
      rowWidthBlocks: positiveInteger("单行宽度。"),
      spacingBlocks: positiveInteger("行列周期，必须不小于行宽。"),
      offsetBlocks: integer("相对局部原点偏移。"),
    }, ["axis", "paletteSlotId", "rowWidthBlocks", "spacingBlocks", "offsetBlocks"]),
    decorationVariant("edge_repeat", {
      paletteSlotId: nonEmptyString("边缘节点使用的 content palette slot。"),
      spacingBlocks: positiveInteger("沿边间距。"),
      offsetBlocks: integer("沿边起始偏移。"),
    }, ["paletteSlotId", "spacingBlocks", "offsetBlocks"]),
    decorationVariant("grid_repeat", {
      paletteSlotId: nonEmptyString("网格节点使用的 content palette slot。"),
      spacingUBlocks: positiveInteger("U 方向间距。"),
      spacingVBlocks: positiveInteger("V 方向间距。"),
      offsetUBlocks: integer("U 方向偏移。"),
      offsetVBlocks: integer("V 方向偏移。"),
    }, ["paletteSlotId", "spacingUBlocks", "spacingVBlocks", "offsetUBlocks", "offsetVBlocks"]),
    decorationVariant("deterministic_scatter", {
      paletteSlotId: nonEmptyString("散点使用的 content palette slot。"),
      cellSizeBlocks: positiveInteger("确定性散点网格尺寸。"),
      densityPermille: { type: "integer", minimum: 0, maximum: 1000, description: "千分比密度。" },
    }, ["paletteSlotId", "cellSizeBlocks", "densityPermille"]),
  ],
};

const decorationContentPaletteSchema = strictObject({
  slots: {
    type: "array",
    minItems: 1,
    items: strictObject({
      slotId: nonEmptyString("Pattern 引用的稳定 slot ID。"),
      phase: { type: "string", enum: ["skeleton", "surface", "major", "minor"] },
      entries: {
        type: "array",
        minItems: 1,
        description: "填写当前 style profile 暴露的语义槽位，例如 market_stall、field_border；不填写具体 NBT/content ID。",
        items: strictObject({
          contentRef: nonEmptyString("来自 city_query_decoration_catalog 的 semanticRefs。"),
          weight: { type: "number", exclusiveMinimum: 0 },
        }, ["contentRef", "weight"]),
      },
      required: { type: "boolean" },
    }, ["slotId", "phase", "entries", "required"]),
  },
}, ["slots"]);

const decorationProgramPlanSchema = strictObject({
  schemaVersion: { type: "string", enum: ["city_decoration_program_plan.v0.2"] },
  cityId: nonEmptyString("必须与 citySeedId 对应的 City 一致。"),
  catalogHash: nonEmptyString("city_query_decoration_catalog 返回的当前 catalogHash。"),
  styleProfileId: nonEmptyString("city_query_decoration_catalog 返回的 styleProfileId。"),
  styleProfileHash: nonEmptyString("所选 styleProfile 对应的 styleProfileHash。"),
  programs: {
    type: "array",
    minItems: 1,
    items: strictObject({
      programId: nonEmptyString("稳定 program ID。"),
      targetArea: strictObject({
        sourceType: {
          type: "string",
          enum: ["patch"],
          description: "首期只允许 patch；不得提交 targetBounds/memberBounds。",
        },
        ref: nonEmptyString("D3 landform patch ref。"),
        insetBlocks: { type: "integer", minimum: 0 },
      }, ["sourceType", "ref", "insetBlocks"]),
      coordinateFrame: strictObject({
        originMode: { type: "string", enum: ["target_centroid"] },
        orientationMode: { type: "string", enum: ["patch_long_axis"] },
        quarterTurns: { type: "integer", minimum: 0, maximum: 3 },
        offsetUBlocks: integer("局部 U 偏移。"),
        offsetVBlocks: integer("局部 V 偏移。"),
      }, ["originMode", "orientationMode", "quarterTurns", "offsetUBlocks", "offsetVBlocks"]),
      shape: decorationShapeSchema,
      pattern: decorationPatternSchema,
      contentPalette: decorationContentPaletteSchema,
      terrainPolicy: strictObject({
        maxSlopeDelta: { type: "integer", minimum: 0 },
        allowWater: { type: "boolean" },
        invalidTerrainAction: { type: "string", enum: ["skip", "clip"] },
      }, ["maxSlopeDelta", "allowWater", "invalidTerrainAction"]),
      conflictPolicy: strictObject({
        onConflict: { type: "string", enum: ["skip", "replace_lower_priority"] },
        clearanceBlocks: { type: "integer", minimum: 0 },
      }, ["onConflict", "clearanceBlocks"]),
      priority: integer("跨 program 优先级，高值先执行。"),
      seed: integer("全局固定随机种子；使用 JavaScript 安全整数。"),
    }, ["programId", "targetArea", "coordinateFrame", "shape", "pattern", "contentPalette",
      "terrainPolicy", "conflictPolicy", "priority", "seed"]),
  },
}, ["schemaVersion", "cityId", "catalogHash", "styleProfileId", "styleProfileHash", "programs"]);

export const realmTools: ToolDefinition[] = [
  {
    name: "realm_status",
    description: "读取 Geomantia 国度规划 W/T 调试接口状态、最近 run 和产物目录。",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "realm_w_refresh",
    description: "执行 W 粗扫，生成 WorldSurveyContext、WorldPatchMap 和带网格坐标预览图。",
    inputSchema: {
      type: "object",
      properties: {
        planningRadiusBlocks: { type: "number", description: "配置的 W 最大扫描半径，单位 block，默认 8192。" },
        radiusChunks: { type: "number", description: "兼容字段：粗扫半径，单位 chunk；未传 planningRadiusBlocks 时换算。" },
        cellStepBlocks: { type: "number", description: "粗 cell 步长，默认 128。" },
        microSampleStrideBlocks: { type: "number", description: "W cell 内 micro-sampling 步长，默认 32 block。" },
        localSlopeRadiusBlocks: { type: "number", description: "micro sample 周边局部坡度半径，默认 8 block。" },
        runTagAudit: { type: "boolean", description: "开发期调试：W 完成后抽样局部精扫并输出 tag_audit_report。" },
        tagAuditSampleCount: { type: "number", description: "Tag Audit 抽样点数量，默认 120。" },
        tagAuditSampleSeed: { type: "string", description: "Tag Audit 抽样 seed；同一 run 可换 seed 抽另一批点。" },
        tagAuditRadiusBlocks: { type: "number", description: "Tag Audit 局部精扫半径，默认 32 block。" },
        tagAuditStrideBlocks: { type: "number", description: "Tag Audit 局部精扫步长，默认 4 block。" },
        tagAuditSlopeRadiusBlocks: { type: "number", description: "Tag Audit 局部坡度半径，默认 4 block。" },
        sampleMode: { type: "string", enum: ["prior", "observedIfLoaded", "verifySurface"] },
        qualityMode: { type: "string", enum: ["smoke", "strict"], description: "验收质量模式；v1.2 默认 strict。" },
        resumePolicy: { type: "string", enum: ["use_cache", "rescan", "use_cache_strict"] },
        centerBlockX: { type: "number" },
        centerBlockZ: { type: "number" },
        dimensionId: { type: "string" },
        playerName: { type: "string" },
        runId: { type: "string" },
        worldTheme: { type: "object" },
      },
    },
  },
  {
    name: "realm_t1_prepare",
    description: "基于 W 产物生成 RealmProfile 和带网格坐标候选图包。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        realmProfiles: { type: "array" },
        realmCount: { type: "number" },
        targetContinentId: { type: "string" },
        allowAiDraftProfile: { type: "boolean" },
      },
      required: ["runId"],
    },
  },
  {
    name: "realm_t2_select_coordinate",
    description: "提交候选图上的 grid 坐标，校验并生成 RealmSeed 与 CapitalCitySeed。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        realmId: { type: "string" },
        gridX: { type: "number" },
        gridZ: { type: "number" },
        alternates: { type: "array" },
        reason: { type: "string" },
        selectedBy: { type: "string", enum: ["ai", "human", "debug"] },
        allowSnap: { type: "boolean" },
      },
      required: ["runId", "realmId", "gridX", "gridZ"],
    },
  },
  {
    name: "realm_t3_expand",
    description: "运行 T3 多国度粗 cell 扩张，输出 RealmTerritoryMap 和国境预览图。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        normalizationGroup: { type: "string" },
        allowUnclaimedLand: { type: "boolean" },
        qualityMode: { type: "string", enum: ["smoke", "strict"], description: "T3 质量模式；v1.2 默认 strict。" },
        expansionModel: { type: "string", enum: ["quota_frontier", "action_budget"], description: "T3 扩张模型；strict 默认 action_budget，smoke 默认 quota_frontier。" },
      },
      required: ["runId"],
    },
  },
  {
    name: "realm_t4_build_registry",
    description: "生成 T4 CitySeedRegistry 和城市种子预览图。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        cityPlanningMode: { type: "string", enum: ["auto", "strict"], description: "T4 城市规划模式；v1.2 默认 strict。" },
      },
      required: ["runId"],
    },
  },
  {
    name: "realm_run_acceptance",
    description: "运行 W -> T4 端到端验收闭环，默认自动选择候选坐标并输出 acceptance_report。",
    inputSchema: {
      type: "object",
      properties: {
        planningRadiusBlocks: { type: "number" },
        radiusChunks: { type: "number" },
        cellStepBlocks: { type: "number" },
        microSampleStrideBlocks: { type: "number" },
        localSlopeRadiusBlocks: { type: "number" },
        runTagAudit: { type: "boolean" },
        tagAuditSampleCount: { type: "number" },
        tagAuditSampleSeed: { type: "string" },
        tagAuditRadiusBlocks: { type: "number" },
        tagAuditStrideBlocks: { type: "number" },
        tagAuditSlopeRadiusBlocks: { type: "number" },
        sampleMode: { type: "string", enum: ["prior", "observedIfLoaded", "verifySurface"] },
        qualityMode: { type: "string", enum: ["smoke", "strict"] },
        expansionModel: { type: "string", enum: ["quota_frontier", "action_budget"] },
        cityPlanningMode: { type: "string", enum: ["auto", "strict"] },
        resumePolicy: { type: "string", enum: ["use_cache", "rescan", "use_cache_strict"] },
        centerBlockX: { type: "number" },
        centerBlockZ: { type: "number" },
        dimensionId: { type: "string" },
        playerName: { type: "string" },
        runId: { type: "string" },
        realmCount: { type: "number" },
        realmProfiles: { type: "array" },
        autoSelectCoordinates: { type: "boolean" },
      },
    },
  },
  {
    name: "realm_debug_command",
    description: "开发调试：通过 Minecraft server command dispatcher 执行一条命令，例如 tp/time/weather/gamemode。必须显式 confirmCommandExecution=true；默认拦截 stop/reload/op/ban 等高风险管理命令，除非 allowUnsafeCommand=true。",
    inputSchema: {
      type: "object",
      properties: {
        command: {
          type: "string",
          description: "要执行的 Minecraft 命令，可带或不带开头 /；必须是单行。",
        },
        confirmCommandExecution: {
          type: "boolean",
          description: "必须为 true；用于确认这是有副作用的调试命令执行。",
        },
        sourceMode: {
          type: "string",
          enum: ["auto", "player", "server"],
          description: "命令源；默认 auto。player 使用玩家上下文，server 使用服务端上下文。",
        },
        playerName: {
          type: "string",
          description: "玩家名；用于 sourceMode=player 或 auto 下选择玩家上下文，例如 Rinsing。",
        },
        dimensionId: {
          type: "string",
          description: "维度 ID；省略时使用玩家维度或 overworld。",
        },
        saveAfter: {
          type: "boolean",
          description: "执行后是否请求保存世界，默认 false。TP 通常不需要。",
        },
        allowUnsafeCommand: {
          type: "boolean",
          description: "允许执行 stop/reload/op/ban 等高风险管理命令，默认 false。",
        },
      },
      required: ["command", "confirmCommandExecution"],
    },
  },
  {
    name: "city_plan_d2",
    description: "City D2: 基于已有 W/T run 的 CitySeed 构建 CitySiteContext（城市局部上下文）。需提供 runId 和 citySeedId。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId（来自 city_seed_registry.json）。" },
        cellStepBlocks: { type: "number", description: "可选覆盖；未传时从 run 的 world_survey_manifest.json 恢复 W/T 采样步长。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_plan_d3",
    description: "City D3: 构建 CityLandformReviewPackage（城市地貌审查包），包含真实渲染 review PNG、GIS patch 标签、成员 cell 薄索引和 AI 上下文。需提供 runId 和 citySeedId，会触发局部 GIS 刷新。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId（来自 city_seed_registry.json）。" },
        cellStepBlocks: { type: "number", description: "可选覆盖；未传时从 run 的 world_survey_manifest.json 恢复 W/T 采样步长。" },
        patchScanPaddingBlocks: { type: "number", description: "D3 patch 上下文额外扫描 padding，默认 128；用于让结构边界和 v4 城墙仍落在已扫描 patch 内。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_profile_structure_envelopes",
    description: "City 结构大小区间回归：cache-backed dry-run profiling，对指定 configured structure 做非写世界 bbox 采样，输出 validSamples、bboxGroups、generationConfigHash、P95/P99/maxObserved 与预览。需在 D4 前运行。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        terrasenseProfileSource: {
          type: "object",
          description: "schemaVersion=terrasense_structure_profile_source.v0.1；sourceType=structure_profile_jsonl 或 debug_catalog。",
        },
        structureIds: {
          type: "array",
          description: "要采样的 configured structure id 列表；为空时采样 catalog 全部结构。",
          items: { type: "string" },
        },
        sampleCount: { type: "number", description: "每个结构采样次数，默认 256。" },
        cacheMode: { type: "string", enum: ["use_cache", "rescan"], description: "profiling cache 模式；默认 use_cache，rescan 强制重算。" },
        forceRefresh: { type: "boolean", description: "true 时忽略本地 profile cache 并重新 dry-run 采样。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource"],
    },
  },
  {
    name: "city_plan_d4",
    description: "City D4: 提交 AI/Codex 基于 D3 patch 真值生成的 StructureAnchorPlan，校验 TerraSense 白名单、anchor、envelope facts 与防撞；固定/近固定结构可走 bboxGroups+smallClearance，非固定结构走 P95 collision，mask 由 collision+maskMargin 派生。旧 PatchGroupPlan/function zone payload 会被拒绝。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId（来自 city_seed_registry.json）。" },
        terrasenseProfileSource: {
          type: "object",
          description: "schemaVersion=terrasense_structure_profile_source.v0.1；sourceType=structure_profile_jsonl 或 debug_catalog。",
        },
        structureAnchorPlan: {
          type: "object",
          description: "schemaVersion=city_structure_anchor_plan.v0.1；anchors[] 包含 anchorId、structureId、sourcePatchIds、anchorBlock{x,z}、rotation、intentTerms、priority、roadAccessIntent；可选 envelopeGroupKey、smallClearanceBlocks。",
        },
        structureEnvelopeFactsSource: {
          type: "object",
          description: "可选；factsPath 指向 structure_envelope_facts.json。未传时读取当前 run/city 默认产物。",
        },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource", "structureAnchorPlan"],
    },
  },
  {
    name: "city_plan_d4_candidates",
    description: "City D4 候选闭环：提交设计 slot 和 patch/距离意图，按 D3 patch、TerraSense profile、envelope facts 生成少量安全 anchor 候选点和预览；不直接进入 D5。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        terrasenseProfileSource: {
          type: "object",
          description: "schemaVersion=terrasense_structure_profile_source.v0.1；sourceType=structure_profile_jsonl 或 debug_catalog。",
        },
        designSlotPlan: {
          type: "object",
          description: "schemaVersion=city_d4_design_slot_plan.v0.1；placementOrder 与 slots[]，slot 含 slotId、displayRole、candidatePatchRefs、structureId 或 structureIds、relationHints。",
        },
        structureEnvelopeFactsSource: {
          type: "object",
          description: "可选；factsPath 指向 structure_envelope_facts.json。未传时读取当前 run/city 默认产物。",
        },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource", "designSlotPlan"],
    },
  },
  {
    name: "city_plan_d4_array_candidates",
    description: "City D4 阵列候选：提交 arrayCandidatePlan，按 D3 patch、TerraSense profile、envelope facts 生成 3-5 组已防碰撞的批量结构候选；不沿道路、不改世界。候选组内含 expandedStructureAnchorPlan，可直接交给 city_plan_d4。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        terrasenseProfileSource: {
          type: "object",
          description: "schemaVersion=terrasense_structure_profile_source.v0.1；sourceType=structure_profile_jsonl 或 debug_catalog。",
        },
        arrayCandidatePlan: {
          type: "object",
          description: "schemaVersion=city_d4_array_candidate_plan.v0.1；必填 cityId、arrayId、candidatePatchRefs[]、structureIds[]、arrayCount；可选 displayRole、patterns[]=loose_cluster/patch_axis_band/scattered/compound_cluster/grid/courtyard/l_shape/u_shape/organic_compact、compoundCluster={shape,rows,columns,spacingBlocks}。",
        },
        structureEnvelopeFactsSource: {
          type: "object",
          description: "可选；factsPath 指向 structure_envelope_facts.json。未传时读取当前 run/city 默认产物。",
        },
        occupiedStructureAnchorMapSource: {
          type: "object",
          description: "可选；anchorMapPath 或 structureAnchorMapPath 指向既有 structure_anchor_map.json，用于避开已选结构。",
        },
        occupiedEnvelopes: {
          type: "array",
          description: "可选；额外 occupied envelope，可直接写 {minX,minZ,maxX,maxZ} 或 {blockBounds:{...}}。",
        },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource", "arrayCandidatePlan"],
    },
  },
  {
    name: "city_create_d4_array_layout_loop",
    description: "City D4 阵列 loop 创建：显式传 schemaVersion=city_d4_array_layout_plan.v0.4、planningMode=array_candidate_selection_loop_v0_4，创建只读候选/选择闭环初始 state；不生成阵列、不改 occupied。v0.4 不切默认 workflow。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        citySeedId: { type: "string" },
        terrasenseProfileSource: { type: "object", description: "TerraSense structure profile 来源。" },
        arrayLayoutPlan: { type: "object", description: "v0.4 计划；layoutPlans 必须为空，后续每轮通过候选工具提交一个阵列主题。" },
        structureEnvelopeFactsSource: { type: "object" },
        baseStructureAnchorPlanSource: { type: "object", description: "可选 base anchor plan。" },
        occupiedStructureAnchorMapSource: { type: "object", description: "已 Plan 的 anchor map；其 collisionEnvelope 初始化 focus 可用 occupied。" },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource", "arrayLayoutPlan"],
    },
  },
  {
    name: "city_query_d4_array_expansion_space",
    description: "City D4 v0.4/v0.5 外扩空间查询：常规连续外扩以已 Plan 的 focusRef、direction 和可选 expansionPolicy 从父结构 D2 body 前沿生成近中远候选带；D3 patch 仅返回地形筛选与归属。targetPatchRef 仅保留为显式兼容约束。只读、不预留。newFunctionalArea=true 保持独立全局 patch 搜索。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        citySeedId: { type: "string" },
        stateId: { type: "string", description: "建议传当前 loop stateId，防止读取过期 state。" },
        arrayExpansionRequest: {
          type: "object",
          description: "常规连续外扩传 focusRef={anchorId 或 arrayId}、direction=north|south|east|west|northeast|northwest|southeast|southwest，以及可选 expansionPolicy={actualBodyGapMin,actualBodyGapMax,frontierExpansionStepBlocks,frontierMaxExpansionRounds}；省略 targetPatchRef 即按父 bbox 前沿搜索，显式传 targetPatchRef 仅走兼容约束。全局新功能区只传 newFunctionalArea=true。",
        },
        arrayLayoutLoopStateSource: { type: "object" },
      },
      required: ["runId", "citySeedId", "arrayExpansionRequest"],
    },
  },
  {
    name: "city_plan_d4_array_expansion_candidates",
    description: "City D4 v0.4/v0.5 外扩候选：常规路径基于父结构 D2 body bbox、方向和 expansionPolicy 生成 3-5 组连续候选，D3 patch 只做后置地形筛选；未传 targetPatchRef 时优先近圈，近圈无完整候选才外扩并写原因。显式新功能区路径仍先查询 globalPatchCandidates[]，再传 selectedGlobalPatchRef。候选不修改 loop state、occupied、array zones 或剩余空间。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        citySeedId: { type: "string" },
        terrasenseProfileSource: { type: "object" },
        stateId: { type: "string" },
        arrayExpansionRequest: {
          type: "object",
          description: "常规连续外扩传 focusRef、direction、可选 expansionPolicy 和 nextArrayLayoutPlanItem；不传 targetPatchRef 即按父 bbox 前沿搜索，显式 targetPatchRef 仅保留兼容约束。全局新功能区传 newFunctionalArea=true + selectedGlobalPatchRef。二者都可传 candidateCount=3..5、minCandidateCount；nextArrayLayoutPlanItem 支持 compound_cluster、guide_line_dual_side、plaza_ring 或 composite_array（composite 保留 childLayoutPlans）。",
        },
        arrayLayoutLoopStateSource: { type: "object" },
        structureEnvelopeFactsSource: { type: "object" },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource", "arrayExpansionRequest"],
    },
  },
  {
    name: "city_select_d4_array_expansion_candidate",
    description: "City D4 v0.4 外扩候选提交：选中一整组候选后原子写入 loop state、collision occupied、array zones、剩余空间和 trace。默认必须传 candidateId；autoSelectHighestScore=true 才允许显式快测自动选择，并记录 decisionSource。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        citySeedId: { type: "string" },
        stateId: { type: "string" },
        candidateId: { type: "string", description: "默认必填；来自外扩候选集合。" },
        autoSelectHighestScore: { type: "boolean", description: "仅快测显式开启；省略或 false 时不会自动选择。" },
        selectionReason: { type: "string" },
        arrayExpansionCandidateSetSource: { type: "object" },
        arrayLayoutLoopStateSource: { type: "object" },
        structureEnvelopeFactsSource: { type: "object" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_finalize_d4_array_layout_loop",
    description: "City D4 阵列 loop finalize：把已选 v0.4 阵列和 base key anchors 写为标准 StructureAnchorPlan/Map，供 D5/D6/D7 消费。未选候选不会进入标准 D4。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        citySeedId: { type: "string" },
        terrasenseProfileSource: { type: "object" },
        stateId: { type: "string" },
        arrayLayoutLoopStateSource: { type: "object" },
        structureEnvelopeFactsSource: { type: "object" },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource"],
    },
  },
  {
    name: "city_create_d4_design_loop_state",
    description: "City D4 多轮设计 loop state：基于 D3 patch 创建 design loop state artifact；只写状态，不触发 D5/D6/dressing/roads/worldgen。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        planningMode: { type: "string", description: "可选；默认 d4_multi_round_design_loop_v0_1。" },
        designLoopOptions: {
          type: "object",
          description: "可选；可传 cityId、planningMode 等状态创建选项。",
        },
        baseStructureAnchorMapSource: {
          type: "object",
          description: "可选；anchorMapPath 或 structureAnchorMapPath 指向既有 structure_anchor_map.json，用 collisionEnvelope/bodyEnvelope 初始化 occupiedField。",
        },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_read_d4_design_loop_state",
    description: "City D4 多轮设计 loop state 读取：返回当前 state、occupiedField、functionZones、arrayZones、patchAvailability、nextAiContextSummary 和 trace；只读，不触发提交阶段。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        designLoopStateSource: {
          type: "object",
          description: "可选；designLoopStatePath、loopStatePath 或 statePath 指向 d4_design_loop_state.json。",
        },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_append_d4_design_loop_round",
    description: "City D4 多轮设计 loop 追加一轮：读取当前 state，追加本轮 anchors/placedStructures/zones，并用 collisionEnvelope/bodyEnvelope 写回 occupiedField；不触发 D5/D6/dressing/roads/worldgen。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        stateId: { type: "string", description: "可选；用于 stale state 校验。" },
        designLoopRound: {
          type: "object",
          description: "本轮增量；可含 roundId、anchors[]、placedStructures[]、functionZones、arrayZones、nextAiContextSummary、executionTrace。anchors/placedStructures 必须带 collisionEnvelope 或 bodyEnvelope。",
        },
        designLoopStateSource: {
          type: "object",
          description: "可选；designLoopStatePath、loopStatePath 或 statePath 指向 d4_design_loop_state.json。",
        },
      },
      required: ["runId", "citySeedId", "designLoopRound"],
    },
  },
  {
    name: "city_write_d4_design_loop_state",
    description: "City D4 多轮设计 loop state 写回：校验并重写 state artifact 及拆分产物；只做状态 write-back，不触发提交阶段。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        stateId: { type: "string", description: "可选；用于 stale state 校验。" },
        designLoopState: {
          type: "object",
          description: "schemaVersion=city_d4_design_loop_state.v0.1 的完整 state；write-back 会重建 occupiedField 派生产物。",
        },
      },
      required: ["runId", "citySeedId", "designLoopState"],
    },
  },
  {
    name: "city_plan_d4_structure_cluster_groups",
    description: "City D4 结构群整组候选：提交 DesignSlotPlan，一次生成多组完整 slot 落脚方案；预览图中一种颜色代表一整组，bbox 默认不画在主图里。每组含 expandedStructureAnchorPlan，可整组选中后进入标准 D4。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        terrasenseProfileSource: {
          type: "object",
          description: "schemaVersion=terrasense_structure_profile_source.v0.1；sourceType=structure_profile_jsonl 或 debug_catalog。",
        },
        designSlotPlan: {
          type: "object",
          description: "schemaVersion=city_d4_design_slot_plan.v0.1；placementOrder 与 slots[]，slot 含 slotId、displayRole、candidatePatchRefs、structureId 或 structureIds、relationHints。",
        },
        structureEnvelopeFactsSource: {
          type: "object",
          description: "可选；factsPath 指向 structure_envelope_facts.json。未传时读取当前 run/city 默认产物。",
        },
        groupCount: { type: "number", description: "可选；返回完整候选组数量，默认 5。" },
        candidatesPerSlot: { type: "number", description: "可选；每个 slot 用于 beam 扩展的候选数，默认 5。" },
        beamWidth: { type: "number", description: "可选；beam search 保留的 partial group 数，默认 groupCount*candidatesPerSlot。" },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource", "designSlotPlan"],
    },
  },
  {
    name: "city_select_d4_candidates",
    description: "City D4 候选选择：读取 anchor_candidate_set，提交 AnchorSelectionPlan，生成标准 StructureAnchorPlan/StructureAnchorMap，并继续复用 D5-D7 主链。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        terrasenseProfileSource: {
          type: "object",
          description: "schemaVersion=terrasense_structure_profile_source.v0.1；sourceType=structure_profile_jsonl 或 debug_catalog。",
        },
        anchorSelectionPlan: {
          type: "object",
          description: "schemaVersion=city_d4_anchor_selection_plan.v0.1；selectedCandidates[] 含 slotId、candidateId、anchorId、selectionReason。",
        },
        anchorCandidateSetSource: {
          type: "object",
          description: "可选；candidateSetPath 指向 anchor_candidate_set.json。未传时读取当前 run/city 默认候选产物。",
        },
        structureEnvelopeFactsSource: {
          type: "object",
          description: "可选；factsPath 指向 structure_envelope_facts.json。未传时读取当前 run/city 默认产物。",
        },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource", "anchorSelectionPlan"],
    },
  },
  {
    name: "city_select_d4_structure_cluster_group",
    description: "City D4 结构群整组选中：按 groupCandidateId 从 structure_cluster_group_candidate_set.json 选中一整组，展开为标准 StructureAnchorPlan/StructureAnchorMap，后续 D5-D7 不需要特殊分支。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        terrasenseProfileSource: {
          type: "object",
          description: "schemaVersion=terrasense_structure_profile_source.v0.1；sourceType=structure_profile_jsonl 或 debug_catalog。",
        },
        groupCandidateId: {
          type: "string",
          description: "来自 city_plan_d4_structure_cluster_groups 返回的 groupCandidateId。",
        },
        structureClusterGroupCandidateSetSource: {
          type: "object",
          description: "可选；candidateSetPath 或 structureClusterGroupCandidateSetPath 指向 structure_cluster_group_candidate_set.json。未传时读取当前 run/city 默认产物。",
        },
        structureEnvelopeFactsSource: {
          type: "object",
          description: "可选；factsPath 指向 structure_envelope_facts.json。未传时读取当前 run/city 默认产物。",
        },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource", "groupCandidateId"],
    },
  },
  {
    name: "city_create_d4_candidate_session",
    description: "City D4 v2 顺序候选 session：提交 DesignSlotPlan，创建逐 slot 生成/选择/冻结的 D4 candidate session，并开始记录 D4 设计耗时。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        sessionId: { type: "string", description: "可选 sessionId；未传时使用 cityId_d4_session。" },
        terrasenseProfileSource: {
          type: "object",
          description: "schemaVersion=terrasense_structure_profile_source.v0.1；sourceType=structure_profile_jsonl 或 debug_catalog。",
        },
        designSlotPlan: {
          type: "object",
          description: "schemaVersion=city_d4_design_slot_plan.v0.1；placementOrder 与 slots[]，slot 含 slotId、displayRole、candidatePatchRefs、structureId 或 structureIds、relationHints。",
        },
        structureEnvelopeFactsSource: {
          type: "object",
          description: "可选；factsPath 指向 structure_envelope_facts.json。未传时读取当前 run/city 默认产物。",
        },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource", "designSlotPlan"],
    },
  },
  {
    name: "city_plan_d4_next_candidates",
    description: "City D4 v2 顺序候选：只为当前未选择 slot 生成 3-5 个候选，候选会避开 session 已冻结 occupied envelopes。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        sessionId: { type: "string", description: "兼容字段；当前实现按 run/city 读取默认 session artifact。" },
        structureEnvelopeFactsSource: {
          type: "object",
          description: "可选；factsPath 指向 structure_envelope_facts.json。未传时读取当前 run/city 默认产物。",
        },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_select_d4_candidate",
    description: "City D4 v2 顺序候选选择：选择当前 slot 的一个 candidate，冻结 estimatedCollisionEnvelope 作为 occupiedField；结构大小诊断回到 profile/maxObserved facts，quickPreflight 本轮 deferred_to_d6。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        sessionId: { type: "string", description: "可选；用于校验当前 D4 candidate session。" },
        slotId: { type: "string", description: "必须等于当前 session currentSlotId。" },
        candidateId: { type: "string", description: "来自 city_plan_d4_next_candidates 返回的 candidateId。" },
        anchorId: { type: "string", description: "可选；未传时使用 slotId_01。" },
        selectionReason: { type: "string", description: "人/AI 选择理由，进入 trace。" },
        quickPreflight: {
          type: "boolean",
          description: "本轮接受但不执行 MC probe；返回 quickPreflightStatus=deferred_to_d6。",
        },
      },
      required: ["runId", "citySeedId", "slotId", "candidateId"],
    },
  },
  {
    name: "city_finalize_d4_candidate_session",
    description: "City D4 v2 finalize：所有 slot 选择完毕后，把 session selectedAnchors 转为标准 StructureAnchorPlan，并调用现有 D4 hard validation 输出 StructureAnchorMap。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        sessionId: { type: "string", description: "可选；用于校验当前 D4 candidate session。" },
        terrasenseProfileSource: {
          type: "object",
          description: "schemaVersion=terrasense_structure_profile_source.v0.1；sourceType=structure_profile_jsonl 或 debug_catalog。",
        },
        structureEnvelopeFactsSource: {
          type: "object",
          description: "可选；factsPath 指向 structure_envelope_facts.json。未传时读取当前 run/city 默认产物。",
        },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource"],
    },
  },
  {
    name: "city_plan_d5",
    description: "City D5: 读取最终 D4 StructureAnchorMap，按 collisionEnvelope + maskMarginBlocks 生成轻量 reservation mask 预案；忽略旧 safetyEnvelope 字段；road/build 仍为空占位，不生成真实道路或修改世界。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId（来自 city_seed_registry.json）。" },
        wallVersion: {
          type: "string",
          enum: ["v5", "v4", "v3", "v2", "v1_debug"],
          description: "城墙 reservation 版本；默认 v2。v5 在 D5 固定最终 wallLine/mask，D7 后只做高度适配；v4 在 D5 生成 reservation/mask 上下文，D7 后按 actualFootprint land ring 生成墙图；v3 使用结构种子 patch region hull；v1_debug 使用旧矩形调试墙带。",
        },
        wallMarginBlocks: { type: "number", description: "墙带生成外扩距离，默认 24。" },
        segmentLengthBlocks: { type: "number", description: "墙段基础长度，默认 15。" },
        wallCorridorHalfWidthBlocks: { type: "number", description: "墙带 corridor 半宽，默认 4。" },
        wallBreathingRoomBlocks: { type: "number", description: "v3 城市外环对结构 footprint/source patch 的呼吸空间，默认 24。" },
        patchExpansionMaxRounds: { type: "number", description: "v3 patch 邻接扩张最大轮数，默认 4。" },
        concavityOpeningMaxBlocks: { type: "number", description: "v3 凹陷填充开口阈值，默认 64。" },
        concavityDepthRatioMin: { type: "number", description: "v3 凹陷填充深宽比阈值，默认 0.6。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_query_decoration_catalog",
    description: "查询 City Decoration v0.2 素材目录与 style profiles。AI 从所选 profile 的 semanticRefs 引用内容；只读，不返回原始 NBT 或任意 block operation。",
    inputSchema: strictObject({}, []),
  },
  {
    name: "city_upgrade_default_decoration_catalog",
    description: "显式升级未改动的 Geomantia 默认装饰目录，为水渠补下坡收尾内容。会备份旧 content index、停用旧 hash 的 active decoration plans，但不改 ledger 或已落地方块；完成后必须重新查询 catalog、重规划并重新激活。",
    inputSchema: strictObject({
      confirmConfigMutation: { type: "boolean", description: "必须为 true；否则不修改运行时 config。" },
    }, ["confirmConfigMutation"]),
  },
  {
    name: "city_probe_decoration_terrain",
    description: "只读检查已编译 City Decoration 的真实已加载区块地表。返回每个 program / palette slot 的加载覆盖、高度起伏、相邻槽位高差和连续带剖面；不会生成区块、不会写世界，也不会阻止后续 activate。",
    inputSchema: strictObject({
      runId: nonEmptyString("已有 W/T run ID。"),
      citySeedId: nonEmptyString("目标城市种子的 citySeedId。"),
      dimensionId: nonEmptyString("可选；省略时使用 run 记录的维度。"),
      playerName: nonEmptyString("可选；用于定位玩家当前维度。"),
    }, ["runId", "citySeedId"]),
  },
  {
    name: "city_query_structure_catalog",
    description: "按 TerraSense 已审核结构标签检索模板。支持 canonical termId，或来自 terrasenseProfileSource.vocabularySnapshotPath 的中文标签/别名；只读，不触发 D4-D7 或世界写入。",
    inputSchema: strictObject({
      terrasenseProfileSource: {
        type: "object",
        description: "schemaVersion=terrasense_structure_profile_source.v0.1；sourceType=structure_profile_jsonl 或 debug_catalog。中文标签/别名检索需附 vocabularySnapshotPath。",
      },
      allOfTerms: {
        type: "array",
        description: "AND：候选必须同时拥有的 TerraSense term；可用 canonical termId 或词表标签/别名。",
        items: nonEmptyString("TerraSense canonical termId、词表 display label 或 alias。"),
      },
      anyOfTerms: {
        type: "array",
        description: "OR：候选至少拥有一个的 TerraSense term。",
        items: nonEmptyString("TerraSense canonical termId、词表 display label 或 alias。"),
      },
      excludeTerms: {
        type: "array",
        description: "排除：拥有任一 term 的候选不会返回。",
        items: nonEmptyString("TerraSense canonical termId、词表 display label 或 alias。"),
      },
      limit: { type: "integer", minimum: 1, maximum: 100, description: "最多返回数量，默认 20。" },
    }, ["terrasenseProfileSource"]),
  },
  {
    name: "city_query_template_metadata",
    description: "从当前 Minecraft StructureTemplateManager 只读校验指定 templateRef，返回运行时 NBT 的尺寸、内容 hash 与来源；不加载目标区块，不执行 D4-D7，也不放置方块。",
    inputSchema: strictObject({
      templateRefs: {
        type: "array",
        minItems: 1,
        maxItems: 32,
        description: "待校验的 ResourceLocation，例如 geomantia:d6d7_fixture/house。",
        items: nonEmptyString("有效的 template ResourceLocation。"),
      },
      dimensionId: nonEmptyString("可选；省略时取玩家当前维度。"),
      playerName: nonEmptyString("可选；用于定位当前维度。"),
    }, ["templateRefs"]),
  },
  {
    name: "city_plan_city_dressing",
    description: "City DecorationProgram v0.2 规划：选择 style profile 并提交语义 contentRef；程序在规划期解析为具体 prefab，再从 D3 patch 解析 mask 与局部坐标系。禁止 v0.1 dressingBrushPlan、targetBounds/memberBounds、世界 origin/x/z、内联 NBT 和 block operation。",
    inputSchema: strictObject({
      runId: nonEmptyString("已有 W/T run ID。"),
      citySeedId: nonEmptyString("目标城市种子的 citySeedId。"),
      decorationProgramPlan: decorationProgramPlanSchema,
    }, ["runId", "citySeedId", "decorationProgramPlan"]),
  },
  {
    name: "city_execute_d5",
    description: "City D5 Execute: 必须先有完整 D6 locked materialization plan；用 D6 locked collision/actualFootprint/signature 激活 reservation mask registry 与 worldgen-time planned structure registry；正式路径不主动执行 WorldEdit 道路/清理，避免提前生成目标 chunk。必须显式传 confirmWorldMutation=true；mask/worldgen hook 不可用会 hard fail。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        confirmWorldMutation: { type: "boolean", description: "必须为 true；否则拒绝真实改世界。" },
        roadProvider: {
          type: "string",
          enum: ["auto", "roadweaver", "worldedit_debug", "none"],
          description: "道路提供者；默认 auto。RoadWeaver 存在则注册连接，缺失时 auto 跳过道路并记录 ROADWEAVER_UNAVAILABLE；只有 worldedit_debug 会铺旧版调试路。",
        },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId", "confirmWorldMutation"],
    },
  },
  {
    name: "city_plan_d6",
    description: "City D6: 读取最终 D4/D5 结构 anchor 与 reservation mask，做 non-mutating configured-structure probe，锁定 actualFootprint、pieceBoxes、lockedActualFootprint、lockedBBoxGroupKey、expectedStartSignature 与 lockedCollisionEnvelope，并用 locked bbox 做最终防撞；不要求 chunk loaded，不修改世界。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_execute_d7",
    description: "City Execute D7: 保留入口名但正式语义为 worldgen ledger 检查。executeStructurePlacement=true 不再 late paste；未生成 chunk 返回 WAITING_FOR_WORLDGEN，已生成未记录返回 STRUCTURE_CHUNK_ALREADY_GENERATED；所有 ledger 完整后基于真实 actualFootprint 生成道路/边界后处理。debugLateMaterialize=true 才允许旧诊断 paste。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        executeStructurePlacement: { type: "boolean", description: "true 时检查 worldgen ledger/状态；正式路径不 late paste。" },
        debugLateMaterialize: { type: "boolean", description: "开发诊断开关；true 时才允许旧 StructureStart.placeInChunk 路径，trace 会标记 lateMaterialization=true。" },
        worldSeed: { type: "number", description: "可选；未传时使用当前世界 seed 参与 seeded random。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_plan_city_walls",
    description: "City 城墙规划：默认 v2 读取 D5 wall reservation、D7 placed ledger 和世界实际 RoadWeaver 路面裁门；wallVersion=v5 只读取 D5 final wallLine，不改线，只用 surface cache 做高度/水体落墙判断；wallVersion=v4 使用 actualFootprint 硬约束 + D5 cityDomain cell 轻量贴形墙图（wallNodes/wallUnits/nodeConnectorUnits + datum + terrainContourEvents）；wallVersion=v3 使用结构种子城市外环 hull + 道路聚类裁门；wallVersion=v1_debug 可生成旧矩形调试墙。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        wallVersion: {
          type: "string",
          enum: ["v5", "v4", "v3", "v2", "v1_debug"],
          description: "城墙版本；默认 v2。v5 使用 D5 final wallLine + 1-block surface median，不在 D7 后重算平面；v4 使用 actualFootprint land ring graph；v3 使用 structure-seeded patch region hull + road gate clusters。",
        },
        wallBoundaryMode: { type: "string", description: "兼容字段；v4 推荐 actual_footprint_land_ring，v3 推荐 structure_seeded_patch_region_hull。" },
        wallMarginBlocks: { type: "number", description: "actualFootprint union 外扩距离，默认 24。" },
        segmentLengthBlocks: { type: "number", description: "城墙 straight segment 长度，默认 15。" },
        wallCorridorHalfWidthBlocks: { type: "number", description: "D5 wall reservation 墙带半宽，默认 4；此接口仅记录兼容，D5 阶段生效。" },
        gateWidthBlocks: { type: "number", description: "城门缺口宽度，默认 9。" },
        roadScanMarginBlocks: { type: "number", description: "扫描实际 RoadWeaver 路面的墙带外扩距离，默认 8。" },
        roadProtectionMarginBlocks: { type: "number", description: "道路保护边距，默认 2。" },
        maxFoundationDepthBlocks: { type: "number", description: "foundation 最大向下补齐深度，默认 8。" },
        maxSegmentHeightDeltaBlocks: { type: "number", description: "单段最大可接受高差，默认 7。" },
        gateClusterRadiusBlocks: { type: "number", description: "v3 raw road-wall intersections 聚类半径，默认 24。" },
        terrainFitUnitLengthBlocks: { type: "number", description: "v3 墙段地形适配 unit 长度，默认 5。" },
        wallTerrainPolicy: {
          type: "string",
          enum: ["v3", "v3.1"],
          description: "v3 城墙执行层地形策略；默认 v3。v3.1 会把 8-16 高差生成阶梯墙，高差更大时尝试嵌坡或天然峭壁边界。",
        },
        wallDesignPolicy: {
          type: "string",
          enum: ["v3", "v3.2", "v3.3"],
          description: "v3 城墙规划层设计策略；v3.2 启用天然边界、道路趋势开门、独立 gatehouse 和可用塔节点；v3.3 增加近路投影开门。",
        },
        minGateSpacingBlocks: { type: "number", description: "wallDesignPolicy=v3.2/v3.3 城门最小间距，默认 48。" },
        minGateRoadLengthBlocks: { type: "number", description: "wallDesignPolicy=v3.2/v3.3 道路趋势最小长度，默认 24。" },
        roadProjectionMaxDistanceBlocks: { type: "number", description: "wallDesignPolicy=v3.3 近路投影开门最大距离，默认 32。" },
        naturalWaterBoundaryMinAreaBlocks: { type: "number", description: "wallDesignPolicy=v3.2/v3.3 大片水体天然边界最小 patch 面积，默认 4096。" },
        flatMaxDeltaBlocks: { type: "number", description: "wallTerrainPolicy=v3.1 低高差阈值，默认 7。" },
        steppedMaxDeltaBlocks: { type: "number", description: "wallTerrainPolicy=v3.1 阶梯墙最大高差，默认 16。" },
        mountainProbeDistanceBlocks: { type: "number", description: "wallTerrainPolicy=v3.1 嵌坡山体侧探测距离，默认 6。" },
        naturalBoundaryMinDeltaBlocks: { type: "number", description: "wallTerrainPolicy=v3.1 天然峭壁边界最小高差；wallVersion=v5 时为高差天然屏障断墙阈值，默认 17。" },
        embeddedSlopeTower: { type: "boolean", description: "wallTerrainPolicy=v3.1 嵌坡/峭壁边界是否放塔楼或石砌封头，默认 true。" },
        wallUnitLengthBlocks: { type: "number", description: "wallVersion=v5 的 placement unit 默认 8；wallVersion=v4 的 graph unit 默认 16，和 step 对齐。" },
        nominalWallHeightBlocks: { type: "number", description: "wallVersion=v5 名义墙高，默认 9。" },
        heightSegmentMaxDeltaBlocks: { type: "number", description: "wallVersion=v5 分段统一墙顶高度的并段高差阈值，默认 7；同段内共用 surface median datum。" },
        heightSteppedTransitionMaxDeltaBlocks: { type: "number", description: "wallVersion=v5 段间阶梯过渡最大高差，默认 16；超过 naturalBoundaryMinDeltaBlocks 则不筑墙。" },
        waterRunMinBlocks: { type: "number", description: "wallVersion=v5 连续水体边界判定最小长度，默认 32 blocks。" },
        waterFluidRatioMin: { type: "number", description: "wallVersion=v5 单 unit fluid 覆盖率阈值，默认 0.8。" },
        waterRunMinUnits: { type: "number", description: "wallVersion=v4 连续多少个 unit 命中水体才判定为湖/海并退避，默认 3。" },
        waterRetreatMaxCells: { type: "number", description: "wallVersion=v4 水体退避最大 unit 步数，默认 4。" },
        structureWallBreathingRoomBlocks: { type: "number", description: "wallVersion=v4 actualFootprint union 外扩呼吸距离，默认 32。" },
        heightDatumClampBlocks: { type: "number", description: "wallVersion=v4 targetY 相对 cityWallDatumY 的夹取范围，默认 ±6。" },
        localMedianWindowUnits: { type: "number", description: "wallVersion=v4 局部高度 median 窗口，默认 3 个 wall unit。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_execute_city_walls",
    description: "City 城墙执行：读取 city_wall_plan，用原版/Forge setBlock 放置临时石砖城墙和塔楼；v3 可开启 debugScan 输出地形/mask/gap 报告；需要 confirmWorldMutation=true。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        confirmWorldMutation: { type: "boolean", description: "必须为 true；否则拒绝真实改世界。" },
        debugScan: { type: "boolean", description: "v3 调试开关；true 时输出 step=1 地形扫描、mask 冲突和 gap 报告。" },
        debugScanStepBlocks: { type: "number", description: "debug scan 步长，默认 1。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId", "confirmWorldMutation"],
    },
  },
  {
    name: "city_run_workflow",
    description: "City 快速验收 workflow：串联 D3 -> envelope profiling -> final D4 -> plan_d5 轻量预案 -> D6 lock -> execute_d5 locked 激活 -> D7 ledger 检查，并可选规划/执行城墙；默认 D4 key_then_array：关键结构逐个定锚，再按 array_fill 阵列填充。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        terrasenseProfileSource: {
          type: "object",
          description: "结构 profile 来源；首次跑 profiling / D4 finalize 时需要。",
        },
        designSlotPlan: {
          type: "object",
          description: "D4 设计 slot plan；默认 key_then_array 模式下 slot 可设置 placementStrategy=key_structure|single_ai_selected|array_fill。array_fill 需要 arrayCount，可选 variantSelectionMode=seeded_random|weighted_random|round_robin。",
        },
        structureIds: {
          type: "array",
          description: "需要 profiling 的顶层 configured structure id 列表。",
          items: { type: "string" },
        },
        structureEnvelopeFactsSource: {
          type: "object",
          description: "可选；factsPath 指向已有 structure_envelope_facts.json。",
        },
        d4CandidateMode: {
          type: "string",
          enum: ["key_then_array", "array_layout_loop_v0_2", "array_layout_loop_v0_3", "sequential_session", "structure_cluster_groups"],
          description: "D4 workflow 模式；默认 key_then_array，强制先处理 key_structure/single_ai_selected slot，再处理 array_fill slot；array_layout_loop_v0_2/v0_3 为显式多轮阵列布局 replay；sequential_session 和 structure_cluster_groups 仅作显式调试/兼容路径。",
        },
        groupCount: { type: "number", description: "结构群整组候选数量，默认 5。" },
        candidatesPerSlot: { type: "number", description: "结构群整组候选每个 slot 的扩展候选数，默认 5。" },
        beamWidth: { type: "number", description: "结构群整组候选 beam width，默认 groupCount*candidatesPerSlot。" },
        sessionId: { type: "string", description: "可选 D4 sessionId。" },
        sampleCount: { type: "number", description: "每结构 envelope profiling 样本数，默认 256。" },
        cellStepBlocks: { type: "number", description: "D3 cell step，未传则使用默认。" },
        patchScanPaddingBlocks: { type: "number", description: "D3 patch 上下文额外扫描 padding，默认 128；workflow 首跑 D3 时用于覆盖结构和城墙 breathing room。" },
        skipExisting: { type: "boolean", description: "默认 true；已有 artifact 时跳过对应步骤，用于等待 worldgen 后快速续跑。" },
        confirmWorldMutation: { type: "boolean", description: "true 才执行 D5 激活 mask/registry；未传时 workflow 会停在 waiting_for_confirmation。" },
        roadProvider: {
          type: "string",
          enum: ["auto", "roadweaver", "worldedit_debug", "none"],
          description: "传给 city_execute_d5 的道路提供者，默认 auto；缺 RoadWeaver 时不再自动铺旧路，旧版调试路必须显式传 worldedit_debug。",
        },
        planWalls: { type: "boolean", description: "D7 ledger 完整后是否调用 city_plan_city_walls。" },
        executeWalls: { type: "boolean", description: "planWalls 后是否执行 city_execute_city_walls。" },
        debugScan: { type: "boolean", description: "执行城墙时是否输出地形/mask/gap debug scan，默认 true。" },
        debugScanStepBlocks: { type: "number", description: "debug scan 步长，默认 1。" },
        wallVersion: { type: "string", enum: ["v5", "v4", "v3", "v2", "v1_debug"], description: "D5/D7 城墙版本，默认 v3；测试 v5 时显式传 v5。" },
        wallTerrainPolicy: { type: "string", enum: ["v3", "v3.1"], description: "城墙地形策略。" },
        wallDesignPolicy: { type: "string", enum: ["v3", "v3.2", "v3.3"], description: "城门/天然边界设计策略。" },
        wallMarginBlocks: { type: "number" },
        segmentLengthBlocks: { type: "number" },
        gateWidthBlocks: { type: "number" },
        wallCorridorHalfWidthBlocks: { type: "number" },
        wallBreathingRoomBlocks: { type: "number" },
        patchExpansionMaxRounds: { type: "number" },
        concavityOpeningMaxBlocks: { type: "number" },
        concavityDepthRatioMin: { type: "number" },
        gateClusterRadiusBlocks: { type: "number" },
        terrainFitUnitLengthBlocks: { type: "number" },
        minGateSpacingBlocks: { type: "number" },
        minGateRoadLengthBlocks: { type: "number" },
        roadProjectionMaxDistanceBlocks: { type: "number" },
        wallUnitLengthBlocks: { type: "number" },
        nominalWallHeightBlocks: { type: "number" },
        heightSegmentMaxDeltaBlocks: { type: "number" },
        heightSteppedTransitionMaxDeltaBlocks: { type: "number" },
        naturalBoundaryMinDeltaBlocks: { type: "number" },
        waterRunMinBlocks: { type: "number" },
        waterFluidRatioMin: { type: "number" },
        waterRunMinUnits: { type: "number" },
        waterRetreatMaxCells: { type: "number" },
        structureWallBreathingRoomBlocks: { type: "number" },
        heightDatumClampBlocks: { type: "number" },
        localMedianWindowUnits: { type: "number" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "realm_tag_audit",
    description: "对已有 sealed W run 单独执行 Tag Audit 抽样局部精扫，不重跑 W/T 主链。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 sealed W/T run ID。" },
        tagAuditSampleCount: { type: "number", description: "Tag Audit 抽样点数量，默认 120。" },
        tagAuditSampleSeed: { type: "string", description: "Tag Audit 抽样 seed；同一 run 可换 seed 抽另一批点。" },
        tagAuditRadiusBlocks: { type: "number", description: "Tag Audit 局部精扫半径，默认 32 block。" },
        tagAuditStrideBlocks: { type: "number", description: "Tag Audit 局部精扫步长，默认 4 block。" },
        tagAuditSlopeRadiusBlocks: { type: "number", description: "Tag Audit 局部坡度半径，默认 4 block。" },
        dimensionId: { type: "string", description: "可选；省略时从 run 的 world_survey_manifest.json 恢复。" },
        playerName: { type: "string", description: "可选；用于没有 dimensionId 时选择玩家维度。" },
      },
      required: ["runId"],
    },
  },
];

function decorationVariant(type: string, params: Record<string, unknown>, required: string[] = []) {
  return strictObject({
    type: { type: "string", enum: [type] },
    params: strictObject(params, required),
  }, ["type", "params"]);
}

function strictObject(properties: Record<string, unknown>, required: string[]) {
  return {
    type: "object",
    properties,
    required,
    additionalProperties: false,
  };
}

function nonEmptyString(description: string) {
  return { type: "string", minLength: 1, description };
}

function integer(description: string) {
  return { type: "integer", description };
}

function positiveInteger(description: string) {
  return { type: "integer", minimum: 1, description };
}
