#!/usr/bin/env node
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import axios from "axios";
import * as fs from "fs";
import * as path from "path";

const MC_API_URL = "http://localhost:5000";
// 明确文件保存在当前运行目录下
const LORE_FILE = path.join(process.cwd(), "world_lore.json");

const server = new Server(
  {
    name: "minecraft-world-architect",
    version: "3.8.0", // Bump version
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

// 保存逻辑
function appendLore(data: any) {
    try {
        let existing = [];
        if (fs.existsSync(LORE_FILE)) {
            const content = fs.readFileSync(LORE_FILE, 'utf-8');
            if (content.trim()) existing = JSON.parse(content);
        }
        // 增加时间戳
        existing.push({ 
            timestamp: new Date().toISOString(), 
            ...data 
        });
        fs.writeFileSync(LORE_FILE, JSON.stringify(existing, null, 2));
        console.error(`[System] Lore saved to ${LORE_FILE}`);
    } catch (e) {
        console.error("[System] Failed to save lore:", e);
    }
}

function pickFirst(obj: any, keys: string[]) {
  if (!obj) return undefined;
  for (const key of keys) {
    if (obj[key] !== undefined) return obj[key];
  }
  return undefined;
}

function compactObject(obj: Record<string, any>) {
  const compact: Record<string, any> = {};
  for (const [key, value] of Object.entries(obj)) {
    if (value !== undefined) compact[key] = value;
  }
  return compact;
}

function normalizeDensityValue(value: any) {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string") return value;
  const normalized = value.trim().toLowerCase();
  if (normalized === "medium") return "mid";
  return normalized;
}

function normalizeWallConfig(raw: any) {
  if (!raw || typeof raw !== "object") return undefined;
  const wall = compactObject({
    type: pickFirst(raw, ["type", "类型"]),
    thickness_blocks: pickFirst(raw, ["thickness_blocks", "厚度方块"]),
    gate_count: pickFirst(raw, ["gate_count", "城门数量"]),
  });
  return Object.keys(wall).length > 0 ? wall : undefined;
}

function normalizeLayerConfigs(raw: any) {
  if (!Array.isArray(raw)) return undefined;
  const layers: any[] = [];
  for (const entry of raw) {
    if (!entry || typeof entry !== "object") continue;
    const wallRaw = pickFirst(entry, ["wall", "墙体"]);
    const densityRaw = pickFirst(entry, ["density", "功能密度"]);
    const layer = compactObject({
      name: pickFirst(entry, ["name", "层名"]),
      type: pickFirst(entry, ["type", "层类型"]),
      density: normalizeDensityValue(densityRaw),
      ecology: pickFirst(entry, ["ecology", "生态策略", "ecology_policy"]),
      wall_layer: pickFirst(entry, ["wall_layer", "是否墙层"]),
      wall: normalizeWallConfig(wallRaw),
    });
    if (Object.keys(layer).length > 0) layers.push(layer);
  }
  return layers.length > 0 ? layers : undefined;
}

server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      // --- 0. 记忆读取 ---
      {
        name: "read_history_lore",
        description: 
          "【Step 0】在开始设计前，先检查本地是否有已保存的历史设定。\n" +
          "如果有历史设定，你可以选择直接复用这些国家，或者在此基础上增加新国家。",
        inputSchema: { type: "object", properties: {} },
      },

      // --- 1. 宏观认知 ---
      {
        name: "get_world_atlas",
        description: "【Step 1】获取世界地图数据（含大陆与海洋）。请优先寻找 'has_detail': true 的区域。",
        inputSchema: { type: "object", properties: {} },
      },

      // --- 2. 资源库 ---
      {
        name: "list_available_structures",
        description: "查询可用的 NBT 建筑结构。",
        inputSchema: {
          type: "object",
          properties: {
            search_query: { type: "string" },
            limit: { type: "number" }
          },
        },
      },

      // --- 3. 概念设计 ---
      {
        name: "draft_nation_concept",
        description: 
          "【Step 2】设计并记录国家设定。此操作会将设定【永久保存】到本地硬盘。\n" +
          "如果不调用此工具，你的设计将不会被保存。\n" +
          "请规划好 planned_index 和 total_planned 来控制生成数量。",
        inputSchema: {
          type: "object",
          properties: {
            concept_name: { type: "string", description: "国家名称" },
            preferred_region_id: { type: "number", description: "目标大陆 ID" },
            theme: { type: "string", description: "详细的文明背景故事、种族特性、建筑风格" },
            capital_requirements: { type: "string", description: "对首都地形的具体要求" },
            planned_index: { type: "number", description: "当前第几个 (1-based)" },
            total_planned: { type: "number", description: "计划总数" }
          },
          required: ["concept_name", "preferred_region_id", "theme", "planned_index", "total_planned"]
        },
      },

      // --- 4. 微观选址 ---
      {
        name: "scan_local_candidates",
        description: 
          "【Step 3 / 支线任务】寻找符合地理条件的坐标点。\n" +
          "模式 A (找首都)：提供 `region_id`，在整个大陆范围内寻找。\n" +
          "模式 B (找分城)：提供 `territory_id`，仅在已建立的领土范围内寻找。",
        inputSchema: {
          type: "object",
          properties: {
            region_id: { type: "number", description: "模式 A: 目标大陆 ID" },
            territory_id: { type: "string", description: "模式 B: 目标领土 ID" },
            min_slope: { type: "number" },
            max_slope: { type: "number" },
            min_tpi: { type: "number" },
            max_tpi: { type: "number" },
            limit: { type: "number" }
          },
        },
      },

      // --- 5. 领土规划 ---
      {
        name: "establish_territory",
        description: 
          "【Step 4】建立领土边界。\n" +
          "注意：必须提供 region_id 且与 scan_local_candidates 一致。",
        inputSchema: {
          type: "object",
          properties: {
            id: { type: "string" },
            name: { type: "string" },
            region_id: { type: "number", description: "所在的大陆 ID" },
            capital_x: { type: "number" },
            capital_z: { type: "number" },
            power: { type: "number" },
            mountain_cost: { type: "number" },
            water_cost: { type: "number" },
            color: { type: "string" }
          },
          required: ["id", "name", "region_id", "capital_x", "capital_z", "power"],
        },
      },

      // --- 5.5 领土分析 ---
      {
        name: "get_territory_status",
        description: 
          "【Step 4.5】获取详细的国情报告（面积、邻国方位、大陆占比、生态构成）。\n" +
          "在建立完所有国家后调用，用于完善世界观叙事。",
        inputSchema: { type: "object", properties: {} },
      },

      // --- 6. 城市建设 ---
      {
        name: "establish_city",
        description: 
          "【Step 5】在领土内建立一座城市。\n" +
          "程序将自动生成城市形状，并按层级列表划分（至少 CORE/BUFFER，可含多层 URBAN 与 RING）。\n" +
          "请确保 center_x/z 位于 territory_id 领土内。",
        inputSchema: {
          type: "object",
          properties: {
            territory_id: { type: "string", description: "所属领土 ID" },
            continent_id: { type: "number", description: "所属大陆 ID" },
            center_x: { type: "number", description: "市中心 Block X" },
            center_z: { type: "number", description: "市中心 Block Z" },
            target_chunk_count: { type: "number", description: "城市规模(区块数)。小镇:50-100, 大城:200-500" },
            bias: { 
                type: "string", 
                enum: ["balanced", "north", "south", "east", "west", "coastal", "inland"],
                description: "扩张倾向"
            },
            density: {
                type: "string",
                enum: ["low", "mid", "medium", "high", "1"],
                description: "整体密度 (可选)"
            },
            ecology_policy: {
                type: "string",
                enum: ["preserve", "adaptive", "clear"],
                description: "生态策略"
            },
            ecology: {
                type: "string",
                enum: ["preserve", "adaptive", "clear"],
                description: "生态策略 (别名)"
            },
            layer_count: {
                type: "number",
                description: "层级数量 (3-10)"
            },
            layer_thresholds: {
                type: "array",
                items: { type: "number" },
                description: "层级阈值 (长度=层级数量-1)"
            },
            layers: {
                type: "array",
                description: "层配置列表",
                items: {
                    type: "object",
                    properties: {
                        name: { type: "string", description: "层名" },
                        type: { type: "string", description: "层类型 (CORE/URBAN/RING/BUFFER)" },
                        density: { type: "string", enum: ["high", "mid", "low", "1"], description: "功能密度" },
                        ecology: { type: "string", enum: ["preserve", "adaptive", "clear"], description: "生态策略" },
                        wall_layer: { type: "boolean", description: "是否墙层" },
                        wall: {
                            type: "object",
                            properties: {
                                type: { type: "string", description: "墙体类型" },
                                thickness_blocks: { type: "number", description: "厚度方块" },
                                gate_count: {
                                    type: "array",
                                    items: { type: "number" },
                                    description: "城门数量区间"
                                }
                            }
                        }
                    }
                }
            }
          },
          required: ["territory_id", "center_x", "center_z", "target_chunk_count"]
        },
      },

      // --- 7. 建设执行 ---
      {
        name: "place_structure",
        description: "【Step 6】在特定坐标放置具体的 NBT 结构（通常用于放置市中心核心建筑）。",
        inputSchema: {
          type: "object",
          properties: {
            x: { type: "number" },
            z: { type: "number" },
            structure_id: { type: "string" },
          },
          required: ["x", "z", "structure_id"],
        },
      },

      // --- 8. 城市阶段1数据 ---
      {
        name: "city_stage1_data",
        description: "获取城市阶段1摘要数据（含多边形统计）。",
        inputSchema: {
          type: "object",
          properties: {
            city_id: { type: "string" }
          },
          required: ["city_id"]
        },
      },
      {
        name: "city_stage2_data",
        description: "获取城市阶段2高度意图配置。",
        inputSchema: {
          type: "object",
          properties: {
            city_id: { type: "string" }
          },
          required: ["city_id"]
        },
      },
    ],
  };
});

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  try {
    switch (request.params.name) {
      case "read_history_lore": {
        if (fs.existsSync(LORE_FILE)) {
            const content = fs.readFileSync(LORE_FILE, 'utf-8');
            const data = JSON.parse(content);
            return { 
                content: [{ 
                    type: "text", 
                    text: `Found ${data.length} existing records in '${LORE_FILE}'.\n${JSON.stringify(data, null, 2)}` 
                }] 
            };
        } else {
            return { content: [{ type: "text", text: "No history found." }] };
        }
      }

      case "get_world_atlas": {
        const res = await axios.get(`${MC_API_URL}/continents`);
        return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
      }

      case "list_available_structures": {
        const args = request.params.arguments as any;
        const res = await axios.get(`${MC_API_URL}/structures`);
        const all = res.data as any[];
        const query = (args.search_query || "").toLowerCase();
        const filtered = all.filter((s: any) => s.id.toLowerCase().includes(query) || (s.type && s.type.includes(query))).slice(0, args.limit || 50);
        return { content: [{ type: "text", text: JSON.stringify(filtered, null, 2) }] };
      }

      case "draft_nation_concept": {
        const args = request.params.arguments as any;
        appendLore(args);
        const isFinished = args.planned_index >= args.total_planned;
        let guide = isFinished 
            ? "Planning complete! Now iterate nations to scan capitals and establish territories." 
            : `Nation ${args.planned_index}/${args.total_planned} recorded. Draft next.`;
        return { content: [{ type: "text", text: `[Lore Saved] '${args.concept_name}' saved.\n${guide}` }] };
      }

      case "scan_local_candidates": {
        const args = request.params.arguments as any;
        try {
            const res = await axios.post(`${MC_API_URL}/query_region`, args);
            // 结果可能很长，只取前几个或者简化
            return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
        } catch (err: any) {
             return { content: [{ type: "text", text: `Error: ${err.response?.data?.error || err.message}` }], isError: true };
        }
      }

      case "establish_territory": {
        const args = request.params.arguments as any;
        let colorInt = 0xFF0000;
        if (args.color) {
            if (typeof args.color === 'number') colorInt = args.color;
            else if (typeof args.color === 'string') colorInt = parseInt(args.color.replace(/^#|0x/, ''), 16);
        }
        await axios.post(`${MC_API_URL}/create_territory`, { ...args, color: colorInt });
        return { content: [{ type: "text", text: `Territory '${args.name}' established.` }] };
      }

      case "get_territory_status": {
        const res = await axios.get(`${MC_API_URL}/territory_status`);
        return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
      }

      case "establish_city": {
        const args = request.params.arguments as any;
        const layerCount = pickFirst(args, ["layer_count", "层级数量"]);
        const layerThresholds = pickFirst(args, ["layer_thresholds", "层级阈值"]);
        const layers = normalizeLayerConfigs(pickFirst(args, ["layers", "层配置"]));
        const payload: Record<string, any> = {
            territoryId: args.territory_id,
            continentId: args.continent_id,
            centerX: args.center_x,
            centerZ: args.center_z,
            targetChunkCount: args.target_chunk_count,
            bias: pickFirst(args, ["bias", "扩张倾向"]) || "balanced",
            ecology: pickFirst(args, ["ecology", "ecology_policy", "生态策略"]) || "adaptive",
            density: normalizeDensityValue(args.density || "medium"),
        };
        if (layerCount !== undefined) payload["layer_count"] = layerCount;
        if (layerThresholds !== undefined) payload["layer_thresholds"] = layerThresholds;
        if (layers !== undefined) payload["layers"] = layers;
        const res = await axios.post(`${MC_API_URL}/create_city`, payload);
        return { 
            content: [{ 
                type: "text", 
                text: `City established! ID: ${res.data.city_id}, Size: ${res.data.actual_size} chunks.` 
            }] 
        };
      }

      case "place_structure": {
        const args = request.params.arguments as any;
        const chunkX = Math.floor(args.x / 16);
        const chunkZ = Math.floor(args.z / 16);
        await axios.post(`${MC_API_URL}/place`, { x: chunkX, z: chunkZ, id: args.structure_id });
        return { content: [{ type: "text", text: `Structure planned at [${chunkX}, ${chunkZ}].` }] };
      }

      case "city_stage1_data": {
        const args = request.params.arguments as any;
        const res = await axios.post(`${MC_API_URL}/city_stage1_data`, { city_id: args.city_id });
        return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
      }
      case "city_stage2_data": {
        const args = request.params.arguments as any;
        const res = await axios.post(`${MC_API_URL}/city_stage2_data`, { city_id: args.city_id });
        return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
      }

      default:
        throw new Error(`Unknown tool: ${request.params.name}`);
    }
  } catch (error: any) {
    const detail = error?.response?.data?.error
      || (error?.response?.data ? JSON.stringify(error.response.data) : null);
    const message = detail || error?.message || "Unknown error";
    return { content: [{ type: "text", text: `Error: ${message}` }], isError: true };
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
