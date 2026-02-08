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
import { fileURLToPath } from "url";

const MC_API_URL = "http://localhost:5000";
// 明确文件保存在当前运行目录下
const LORE_FILE = path.join(process.cwd(), "world_lore.json");
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const MCP_LOG_DIR = path.join(SCRIPT_DIR, "logs");

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
      weight: pickFirst(entry, ["weight", "权重"]),
      ecology: pickFirst(entry, ["ecology", "生态策略", "ecology_policy"]),
      is_wall: pickFirst(entry, ["is_wall", "isWall", "是否城墙"]),
      wall_layer: pickFirst(entry, ["wall_layer", "是否墙层"]),
      wall: normalizeWallConfig(wallRaw),
    });
    if (Object.keys(layer).length > 0) layers.push(layer);
  }
  return layers.length > 0 ? layers : undefined;
}

function toFiniteNumber(value: any, fallback: number) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

function clampNumber(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function writeMcpLog(toolName: string, args: any, result: any, isError: boolean, errorMessage?: string) {
  try {
    fs.mkdirSync(MCP_LOG_DIR, { recursive: true });
    const now = new Date();
    const iso = now.toISOString();
    const safeToolName = (toolName || "unknown").replace(/[^\w.-]/g, "_");
    const stamp = iso.replace(/[:.]/g, "-");
    const file = path.join(MCP_LOG_DIR, `${stamp}_${safeToolName}.json`);
    const payload = {
      timestamp: iso,
      tool: toolName,
      arguments: args ?? {},
      response: result ?? null,
      is_error: isError,
      error_message: errorMessage ?? null,
    };
    fs.writeFileSync(file, JSON.stringify(payload, null, 2), "utf-8");
  } catch (e) {
    console.error("[MCP] Failed to write log:", e);
  }
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
        description: "【Step 1】获取世界图集（W4 汇总：atlas + summary）。",
        inputSchema: { type: "object", properties: {} },
      },
      {
        name: "run_workflow_stage",
        description: "运行工作流阶段（W3/W4/T2/T3/T4）。用于触发扫描、汇总与领土导出。",
        inputSchema: {
          type: "object",
          properties: {
            stage_id: { type: "string", enum: ["W3", "W4", "T2", "T3", "T4"] },
            t4_sample_stride: { type: "number", description: "T4 扫描步长。1=最精细，越大越快。默认自动触发时=4，手动=2" },
            t4_max_chunks: { type: "number", description: "每个国家最多扫描 chunk 数，<=0 表示不限制" },
            t4_loaded_only: { type: "boolean", description: "仅扫描已加载 chunk；true 更不易卡主线程" }
          },
          required: ["stage_id"]
        }
      },
      {
        name: "workflow_status",
        description: "查询工作流阶段状态。可选传 stage_id 查询单阶段。",
        inputSchema: {
          type: "object",
          properties: {
            stage_id: { type: "string", enum: ["W3", "W4", "T2", "T3", "T4"] }
          }
        }
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
      {
        name: "t1_generate_blueprint",
        description:
          "基于 W4 世界数据生成一条 T1 蓝图（同时读取 world_atlas + world_summary）。" +
          "优先使用 target_continent_id；未提供时自动选择首个 CONTINENT。会按世界摘要约束扩张力范围。",
        inputSchema: {
          type: "object",
          properties: {
            territory_id: { type: "string" },
            name: { type: "string" },
            target_continent_id: { type: "number" },
            auto_pick_region: { type: "boolean" },
            submit: { type: "boolean", description: "默认 true；false 时仅返回草案不提交" },
            theme: { type: "string" },
            description: { type: "string" },
            ruler: { type: "string" },
            color: { type: "string" },
            base_power: { type: "number" },
            power_clamp: { type: "boolean", description: "默认 true。是否按 W4_world_summary 约束 base_power" },
            power_min_override: { type: "number" },
            power_max_override: { type: "number" },
            slope_penalty: { type: "number" },
            water_penalty: { type: "number" },
            forest_penalty: { type: "number" },
            base_move: { type: "number" },
            preferred_biomes: { type: "array", items: { type: "string" } },
            avoid_biomes: { type: "array", items: { type: "string" } }
          },
          required: ["territory_id", "name"]
        }
      },

      // --- 4. 微观选址 ---
      {
        name: "scan_local_candidates",
        description: 
          "【Step 3 / 支线任务】寻找符合地理条件的坐标点。\n" +
          "模式 A (找首都)：提供 `region_id`，在整个大陆范围内寻找。\n" +
          "模式 B (找分城)：提供 `territory_id`，仅在已建立的领土范围内寻找。\n" +
          "支持 interest_groups 多兴趣组扫描：返回 candidates + candidates_metadata + group_ascii_maps + visual_map（综合 ASCII）。",
        inputSchema: {
          type: "object",
          properties: {
            region_id: { type: "number", description: "模式 A: 目标大陆 ID" },
            territory_id: { type: "string", description: "模式 B: 目标领土 ID" },
            min_slope: { type: "number" },
            max_slope: { type: "number" },
            min_tpi: { type: "number" },
            max_tpi: { type: "number" },
            limit: { type: "number", description: "非 interest_groups 模式：最多返回多少个候选簇" },
            limit_per_group: { type: "number", description: "interest_groups 模式：每组最多返回多少簇" },
            interest_groups: {
              type: "array",
              description: "多兴趣组条件；示例: [{id:'A',criteria:{min_tpi:1.0,min_slope:0.8}},{id:'B',criteria:{max_slope:0.35}}]",
              items: {
                type: "object",
                properties: {
                  id: { type: "string", description: "组标识，用于生成 A1/B1 标签与 visual_map 符号" },
                  limit: { type: "number", description: "该组自定义返回上限（覆盖 limit_per_group）" },
                  criteria: {
                    type: "object",
                    properties: {
                      min_slope: { type: "number" },
                      max_slope: { type: "number" },
                      min_tpi: { type: "number" },
                      max_tpi: { type: "number" }
                    }
                  }
                }
              }
            }
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
      {
        name: "territory_summary",
        description: "读取指定领土的 T2 摘要产物（TerritorySummary.json）。",
        inputSchema: {
          type: "object",
          properties: {
            territory_id: { type: "string" }
          },
          required: ["territory_id"]
        }
      },
      {
        name: "territory_t4_window",
        description: "读取指定领土 T4 dat 的局部窗口（按中心点+半径返回高程/坡度/温度与窗口群系构成）。",
        inputSchema: {
          type: "object",
          properties: {
            territory_id: { type: "string" },
            center_x: { type: "number", description: "窗口中心 Block X；不传时默认首都 X" },
            center_z: { type: "number", description: "窗口中心 Block Z；不传时默认首都 Z" },
            radius_blocks: { type: "number", description: "窗口半径（方块），默认 256" },
            max_points: { type: "number", description: "返回样本点上限，默认 160" },
            max_biome_samples: { type: "number", description: "窗口群系采样上限，默认 3000" }
          },
          required: ["territory_id"]
        }
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
            target_chunk_count: { type: "number", description: "城市规模(区块数，可选)。不传时由程序自动估算" },
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
            allow_water_city: {
                type: "boolean",
                description: "是否允许中心位于显著水域（默认 false）"
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
                        weight: { type: "number", description: "层权重。程序按 weight/sum(weight) 计算层占比" },
                        ecology: { type: "string", enum: ["preserve", "adaptive", "clear"], description: "生态策略" },
                        is_wall: { type: "boolean", description: "是否城墙层（尤其用于 RING）" },
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
          required: ["territory_id", "center_x", "center_z"]
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
  const toolName = request.params.name;
  const toolArgs = (request.params.arguments as any) || {};
  try {
    const result = await (async () => {
    switch (toolName) {
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
        const res = await axios.get(`${MC_API_URL}/world_atlas`);
        return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
      }

      case "run_workflow_stage": {
        const args = request.params.arguments as any;
        const stageId = String(args.stage_id || "").trim().toUpperCase();
        if (!["W3", "W4", "T2", "T3", "T4"].includes(stageId)) {
          throw new Error("stage_id must be one of: W3, W4, T2, T3, T4");
        }
        const payload: any = { stageId };
        if (args.t4_sample_stride !== undefined) payload.t4_sample_stride = Number(args.t4_sample_stride);
        if (args.t4_max_chunks !== undefined) payload.t4_max_chunks = Number(args.t4_max_chunks);
        if (args.t4_loaded_only !== undefined) payload.t4_loaded_only = Boolean(args.t4_loaded_only);
        const res = await axios.post(`${MC_API_URL}/workflow/run`, payload);
        return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
      }

      case "workflow_status": {
        const args = (request.params.arguments as any) || {};
        const stageId = args.stage_id ? String(args.stage_id).trim().toUpperCase() : "";
        const url = stageId
          ? `${MC_API_URL}/workflow/status?stageId=${encodeURIComponent(stageId)}`
          : `${MC_API_URL}/workflow/status`;
        const res = await axios.get(url);
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

      case "t1_generate_blueprint": {
        const args = (request.params.arguments as any) || {};
        const territoryId = String(args.territory_id || "").trim();
        const name = String(args.name || "").trim();
        if (!territoryId) throw new Error("territory_id is required");
        if (!name) throw new Error("name is required");

        const atlasRes = await axios.get(`${MC_API_URL}/world_atlas`);
        const summaryRes = await axios.get(`${MC_API_URL}/world_summary`);
        const atlas = Array.isArray(atlasRes.data?.atlas) ? atlasRes.data.atlas : [];
        const worldSummary = summaryRes.data?.summary || atlasRes.data?.summary || {};
        if (atlas.length === 0) throw new Error("world_atlas is empty. Run W4 first.");

        let targetContinentId = Number(args.target_continent_id || 0);
        if (!Number.isFinite(targetContinentId) || targetContinentId <= 0) {
          const autoPick = args.auto_pick_region !== false;
          if (!autoPick) throw new Error("target_continent_id is required when auto_pick_region=false");
          const firstContinent = atlas.find((r: any) => r?.type === "CONTINENT") || atlas[0];
          targetContinentId = Number(firstContinent?.id || 0);
          if (!targetContinentId) throw new Error("Failed to pick a valid target_continent_id from atlas");
        }

        const targetRegion = atlas.find((r: any) => Number(r?.id) === targetContinentId) || null;
        const landRegions = atlas.filter((r: any) => r?.type === "CONTINENT" || r?.type === "ISLAND");
        const totalLandArea = landRegions.reduce((sum: number, r: any) => {
          return sum + toFiniteNumber(r?.metrics?.area_pixels, 0);
        }, 0);
        const regionArea = toFiniteNumber(targetRegion?.metrics?.area_pixels, 0);
        const roughnessAvg = toFiniteNumber(worldSummary?.roughness?.avg, 0);
        const slopeAvg = toFiniteNumber(worldSummary?.slope?.avg, 0);

        const areaRatioRaw = totalLandArea > 0 ? regionArea / totalLandArea : 0.05;
        const areaRatio = clampNumber(areaRatioRaw, 0.02, 0.45);
        let recommendedMin = Math.round(300 + 3500 * areaRatio);
        let recommendedMax = Math.round(900 + 9000 * areaRatio);

        if (roughnessAvg > 8 || slopeAvg > 6) {
          recommendedMin = Math.round(recommendedMin * 0.9);
          recommendedMax = Math.round(recommendedMax * 0.9);
        } else if (roughnessAvg < 4 && slopeAvg < 3) {
          recommendedMin = Math.round(recommendedMin * 1.1);
          recommendedMax = Math.round(recommendedMax * 1.1);
        }
        if (recommendedMax <= recommendedMin) recommendedMax = recommendedMin + 400;

        const userMin = args.power_min_override !== undefined ? Number(args.power_min_override) : undefined;
        const userMax = args.power_max_override !== undefined ? Number(args.power_max_override) : undefined;
        const finalMin = Number.isFinite(userMin) ? Math.max(1, Math.round(userMin as number)) : recommendedMin;
        const finalMaxBase = Number.isFinite(userMax) ? Math.round(userMax as number) : recommendedMax;
        const finalMax = Math.max(finalMin + 1, finalMaxBase);
        const recommendedPower = Math.round((finalMin + finalMax) / 2);

        const requestedPower = toFiniteNumber(args.base_power, recommendedPower);
        const shouldClampPower = args.power_clamp !== false;
        const finalPower = shouldClampPower
          ? Math.round(clampNumber(requestedPower, finalMin, finalMax))
          : Math.round(requestedPower);

        const powerRange = {
          min: finalMin,
          max: finalMax,
          recommended: recommendedPower,
          requested: requestedPower,
          applied: finalPower,
          clamped: shouldClampPower
        };

        const blueprint = {
          territory_id: territoryId,
          name,
          target_continent_id: targetContinentId,
          narrative: {
            theme: args.theme || "GENERIC_REALM",
            description: args.description || `Auto-generated from world atlas/summary for region ${targetContinentId}.`,
            ruler: args.ruler || "Unknown Ruler",
            color: args.color || "0xA52A2A"
          },
          expansion_policy: {
            base_power: finalPower,
            costs: {
              base_move: Number(args.base_move ?? 1.0),
              slope_penalty: Number(args.slope_penalty ?? 0.6),
              water_penalty: Number(args.water_penalty ?? 6.0),
              forest_penalty: Number(args.forest_penalty ?? 1.5),
              preferred_biomes: Array.isArray(args.preferred_biomes) ? args.preferred_biomes : [],
              avoid_biomes: Array.isArray(args.avoid_biomes) ? args.avoid_biomes : []
            }
          }
        };

        const shouldSubmit = args.submit !== false;
        if (!shouldSubmit) {
          return {
            content: [
              {
                type: "text",
                text: JSON.stringify(
                  {
                    step: "T1",
                    ok: true,
                    mode: "draft_only",
                    blueprint,
                    power_range: powerRange,
                    sources: { world_atlas: true, world_summary: true }
                  },
                  null,
                  2
                )
              }
            ]
          };
        }

        const submitRes = await axios.post(`${MC_API_URL}/t1_blueprint`, blueprint);
        return {
          content: [
            {
              type: "text",
              text: JSON.stringify(
                {
                  step: "T1",
                  ok: true,
                  mode: "generated_and_submitted",
                  blueprint,
                  power_range: powerRange,
                  sources: { world_atlas: true, world_summary: true },
                  submit_result: submitRes.data
                },
                null,
                2
              )
            }
          ]
        };
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
        const res = await axios.post(`${MC_API_URL}/create_territory`, { ...args, color: colorInt });
        return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
      }

      case "get_territory_status": {
        const res = await axios.get(`${MC_API_URL}/territory_status`);
        return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
      }

      case "territory_summary": {
        const args = request.params.arguments as any;
        const territoryId = String(args.territory_id || "").trim();
        if (!territoryId) throw new Error("territory_id is required");
        const res = await axios.get(`${MC_API_URL}/territory/summary?territoryId=${encodeURIComponent(territoryId)}`);
        return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
      }
      case "territory_t4_window": {
        const args = (request.params.arguments as any) || {};
        const territoryId = String(args.territory_id || "").trim();
        if (!territoryId) throw new Error("territory_id is required");
        const payload: Record<string, any> = {
          territory_id: territoryId,
          center_x: args.center_x,
          center_z: args.center_z,
          radius_blocks: args.radius_blocks,
          max_points: args.max_points,
          max_biome_samples: args.max_biome_samples
        };
        const res = await axios.post(`${MC_API_URL}/territory/t4_window`, payload);
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
            allow_water_city: pickFirst(args, ["allow_water_city", "allowWaterCity"]),
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
        throw new Error(`Unknown tool: ${toolName}`);
    }
    })();
    writeMcpLog(toolName, toolArgs, result, false);
    return result;
  } catch (error: any) {
    const detail = error?.response?.data?.error
      || (error?.response?.data ? JSON.stringify(error.response.data) : null);
    const message = detail || error?.message || "Unknown error";
    const errorResult = { content: [{ type: "text", text: `Error: ${message}` }], isError: true };
    writeMcpLog(toolName, toolArgs, errorResult, true, message);
    return errorResult;
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
