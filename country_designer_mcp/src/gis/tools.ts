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
];
