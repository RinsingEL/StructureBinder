import type { ToolDefinition } from "../shared/types.js";

export const gisTools: ToolDefinition[] = [
  {
    name: "gis_status",
    description: "读取 Geomantia GIS 本地调试接口状态、debug 目录和在线玩家位置。",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "gis_refresh",
    description: "执行一次真实世界 GIS 半径刷新，返回 runId、统计和调试产物路径。",
    inputSchema: {
      type: "object",
      properties: {
        radiusChunks: { type: "number", description: "刷新半径，单位 chunk，默认 8，范围 1-64。" },
        cellStepBlocks: {
          type: "number",
          description: "可选 AtlasCell 步长，单位 block，默认 4，范围 1-256，需整除 GIS region 方块尺寸。",
        },
        sampleMode: {
          type: "string",
          enum: ["prior", "observedIfLoaded", "verifySurface"],
          description: "采样模式，默认 prior。",
        },
        centerBlockX: { type: "number", description: "可选中心方块 X；省略时使用指定/首个在线玩家位置。" },
        centerBlockZ: { type: "number", description: "可选中心方块 Z；需与 centerBlockX 同时提供。" },
        dimensionId: { type: "string", description: "可选维度 ID，例如 minecraft:overworld。" },
        playerName: { type: "string", description: "可选玩家名；用于选择玩家位置和维度。" },
      },
    },
  },
  {
    name: "gis_test_run",
    description: "运行 GIS 合成验收用例，默认 mixed，返回测试报告与产物路径。",
    inputSchema: {
      type: "object",
      properties: {
        caseId: {
          type: "string",
          enum: ["plain", "mountain", "water", "mixed"],
          description: "GIS 合成验收用例，默认 mixed。",
        },
      },
    },
  },
  {
    name: "gis_chunk_generation_benchmark_start",
    description: "由服务端主动生成一片新 Chunk，启动同面积地形生成性能基准。会真实生成并可能保存区块。",
    inputSchema: {
      type: "object",
      properties: {
        confirmGenerateChunks: {
          type: "boolean",
          description: "必须显式传 true，确认本次操作会生成并可能保存真实 Chunk。",
        },
        radiusChunks: { type: "number", description: "方形目标半径，默认 32，范围 1-32。" },
        timeoutSeconds: { type: "number", description: "超时秒数，默认 900，范围 30-1800。" },
        requireFresh: { type: "boolean", description: "默认 true；目标范围已有加载 Chunk 时拒绝启动。" },
        centerBlockX: { type: "number", description: "目标中心方块 X；省略时使用指定/首个在线玩家位置。" },
        centerBlockZ: { type: "number", description: "目标中心方块 Z；需与 centerBlockX 同时提供。" },
        dimensionId: { type: "string", description: "可选维度 ID，例如 minecraft:overworld。" },
        playerName: { type: "string", description: "可选玩家名；用于选择玩家位置和维度。" },
      },
      required: ["confirmGenerateChunks"],
    },
  },
  {
    name: "gis_chunk_generation_benchmark_status",
    description: "查询当前或最近一次服务端 Chunk 生成基准的进度、分位时间和报告路径。",
    inputSchema: {
      type: "object",
      properties: {
        jobId: { type: "string", description: "可选任务 ID；省略时返回当前或最近一次任务。" },
      },
    },
  },
];
