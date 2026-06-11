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
        radiusChunks: { type: "number", description: "粗扫半径，单位 chunk，默认 8。" },
        cellStepBlocks: { type: "number", description: "粗 cell 步长，默认 128。" },
        sampleMode: { type: "string", enum: ["prior", "observedIfLoaded", "verifySurface"] },
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
        radiusChunks: { type: "number" },
        cellStepBlocks: { type: "number" },
        sampleMode: { type: "string", enum: ["prior", "observedIfLoaded", "verifySurface"] },
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
];
