import type { ToolDefinition } from "../shared/types.js";

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
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_profile_structure_envelopes",
    description: "City 结构大小区间回归：对指定 configured structure 做非写世界 bbox 采样，输出 validSamples、bboxGroups、generationConfigHash、P95/P99/maxObserved 与预览。需在 D4 前运行。",
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
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource"],
    },
  },
  {
    name: "city_plan_d4",
    description: "City D4: 提交 AI/Codex 基于 D3 patch 真值生成的 StructureAnchorPlan，校验 TerraSense 白名单、anchor、envelope facts 与防撞；固定/近固定结构可走 bboxGroups+smallClearance，非固定结构走 P95/P99。旧 PatchGroupPlan/function zone payload 会被拒绝。",
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
    description: "City D4 v2 顺序候选选择：选择当前 slot 的一个 candidate，优先冻结 estimatedSafetyEnvelope（缺失时回退 estimatedCollisionEnvelope），更新 session，并记录 agentThinkTimeMs。quickPreflight 本轮 deferred_to_d6。",
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
    description: "City D5: 基于 D4 StructureAnchorMap 生成 reservation mask 与预览；默认生成 v2 D3 patch 贴边 wall reservation corridor 并合入禁植被/禁自然结构 mask；road/build 仍为空占位，不修改世界。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId（来自 city_seed_registry.json）。" },
        wallVersion: {
          type: "string",
          enum: ["v3", "v2", "v1_debug"],
          description: "城墙 reservation 版本；默认 v2。v3 使用结构种子 patch region hull；v1_debug 使用旧矩形调试墙带。",
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
    name: "city_execute_d5",
    description: "City D5 Execute: 必须先有 D6 locked materialization plan；激活 locked reservation mask registry 与 worldgen-time planned structure registry；正式路径不主动执行 WorldEdit 道路/清理，避免提前生成目标 chunk。必须显式传 confirmWorldMutation=true；mask/worldgen hook 不可用会 hard fail。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        confirmWorldMutation: { type: "boolean", description: "必须为 true；否则拒绝真实改世界。" },
        roadProvider: {
          type: "string",
          enum: ["auto", "roadweaver", "worldedit_debug", "none"],
          description: "道路提供者；默认 auto。RoadWeaver 存在则注册连接，缺失时 auto 保留 D7 WorldEdit 调试 fallback。",
        },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId", "confirmWorldMutation"],
    },
  },
  {
    name: "city_plan_d6",
    description: "City D6: 读取 D4/D5 结构 anchor 与 reservation mask，做 non-mutating configured-structure probe，锁定 actualFootprint、actualBBoxGroupKey、expectedStartSignature 与 lockedCollisionEnvelope，并用 locked bbox 做最终防撞；不要求 chunk loaded，不修改世界。",
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
    description: "City 城墙规划：默认 v2 读取 D5 wall reservation、D7 placed ledger 和世界实际 RoadWeaver 路面裁门；wallVersion=v3 使用结构种子城市外环 hull + 道路聚类裁门；wallVersion=v1_debug 可生成旧矩形调试墙。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        wallVersion: {
          type: "string",
          enum: ["v3", "v2", "v1_debug"],
          description: "城墙版本；默认 v2。v3 使用 structure-seeded patch region hull + road gate clusters。",
        },
        wallBoundaryMode: { type: "string", description: "v3 兼容字段；推荐 structure_seeded_patch_region_hull。" },
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
        naturalBoundaryMinDeltaBlocks: { type: "number", description: "wallTerrainPolicy=v3.1 天然峭壁边界最小高差，默认 17。" },
        embeddedSlopeTower: { type: "boolean", description: "wallTerrainPolicy=v3.1 嵌坡/峭壁边界是否放塔楼或石砌封头，默认 true。" },
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
