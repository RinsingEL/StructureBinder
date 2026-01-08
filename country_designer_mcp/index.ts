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
const LORE_FILE = "world_lore.json"; // 设定保存文件

const server = new Server(
  {
    name: "minecraft-world-architect",
    version: "3.4.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

// 辅助函数：保存 Lore 到本地文件
function appendLore(data: any) {
    try {
        let existing = [];
        if (fs.existsSync(LORE_FILE)) {
            existing = JSON.parse(fs.readFileSync(LORE_FILE, 'utf-8'));
        }
        existing.push({ timestamp: new Date().toISOString(), ...data });
        fs.writeFileSync(LORE_FILE, JSON.stringify(existing, null, 2));
    } catch (e) {
        console.error("Failed to save lore:", e);
    }
}

server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      {
        name: "get_world_atlas",
        description: 
          "【Step 1】获取世界地图数据。请优先寻找 'has_detail': true 的区域。",
        inputSchema: { type: "object", properties: {} },
      },
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
      {
        name: "draft_nation_concept",
        description: 
          "【Step 2】设计并记录国家设定。\n" +
          "**关键控制**：请在心中规划好你要创建的國家总数（例如 4 个）。\n" +
          "每次调用此工具设计一个国家。当设计完最后一个国家并建立领土后，请停止设计，转而使用 'place_structure' 建设首都。\n" +
          "此工具会将你的精彩设定保存到本地 'world_lore.json' 文件中。",
        inputSchema: {
          type: "object",
          properties: {
            concept_name: { type: "string", description: "国家名称" },
            preferred_region_id: { type: "number", description: "目标大陆 ID" },
            theme: { type: "string", description: "详细的文明背景故事、种族特性、建筑风格" },
            capital_requirements: { type: "string", description: "对首都地形的具体要求" },
            planned_index: { type: "number", description: "这是你计划创建的第几个国家 (e.g. 1)" },
            total_planned: { type: "number", description: "你总共计划创建几个国家 (e.g. 4)" }
          },
          required: ["concept_name", "preferred_region_id", "theme", "planned_index", "total_planned"]
        },
      },
      {
        name: "scan_local_candidates",
        description: "【Step 3】根据设定寻找首都坐标。",
        inputSchema: {
          type: "object",
          properties: {
            region_id: { type: "number" },
            min_slope: { type: "number" },
            max_slope: { type: "number" },
            min_tpi: { type: "number" },
            max_tpi: { type: "number" },
            limit: { type: "number" }
          },
          required: ["region_id"],
        },
      },
      {
        name: "establish_territory",
        description: 
          "【Step 4】建立领土边界。\n" +
          "**重要**：必须提供 'region_id'，且该 ID 必须与你刚才在 scan_local_candidates 中使用的 ID 一致，否则领土将无法生成。",
        inputSchema: {
          type: "object",
          properties: {
            id: { type: "string", description: "领土唯一ID (e.g. 'frost_kingdom')" },
            name: { type: "string", description: "领土显示名称" },
            
            region_id: { type: "number", description: "【必填】所在的大陆 ID (必须与 scan_local_candidates 一致)" },
            
            capital_x: { type: "number", description: "首都 X" },
            capital_z: { type: "number", description: "首都 Z" },
            power: { type: "number", description: "扩张能量" },
            mountain_cost: { type: "number" },
            water_cost: { type: "number" },
            color: { type: "string" }
          },
          required: ["id", "name", "region_id", "capital_x", "capital_z", "power"],
        },
      },
      {
        name: "place_structure",
        description: 
          "【Step 5】在首都建设结构。只有在所有国家领土都规划完毕后，再统一执行此步骤。",
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
    ],
  };
});

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  try {
    switch (request.params.name) {
      case "get_world_atlas": {
        const res = await axios.get(`${MC_API_URL}/continents`);
        const data = res.data;
        if (Array.isArray(data) && data.length === 0) return { content: [{ type: "text", text: "Atlas empty. Scan in-game first." }] };
        return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
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
        
        // 1. 保存到本地 JSON
        appendLore(args);

        // 2. 生成引导性提示
        const isFinished = args.planned_index >= args.total_planned;
        let guide = "";
        if (isFinished) {
            guide = "All planned nations recorded! Now please proceed to 'place_structure' for each established capital.";
        } else {
            guide = `Nation ${args.planned_index}/${args.total_planned} recorded. Proceed to scan & establish territory, then loop back to draft the next nation.`;
        }

        return { 
            content: [{ 
                type: "text", 
                text: `[Lore Saved] Concept for '${args.concept_name}' saved to disk.\n${guide}` 
            }] 
        };
      }

      case "scan_local_candidates": {
        const args = request.params.arguments as any;
        try {
            const res = await axios.post(`${MC_API_URL}/query_region`, args);
            return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
        } catch (err: any) {
             return { content: [{ type: "text", text: `Minecraft Error: ${err.response?.data?.error || err.message}` }], isError: true };
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

      case "place_structure": {
        const args = request.params.arguments as any;
        const chunkX = Math.floor(args.x / 16);
        const chunkZ = Math.floor(args.z / 16);
        await axios.post(`${MC_API_URL}/place`, { x: chunkX, z: chunkZ, id: args.structure_id });
        return { content: [{ type: "text", text: `Structure planned at [${chunkX}, ${chunkZ}].` }] };
      }

      default:
        throw new Error(`Unknown tool: ${request.params.name}`);
    }
  } catch (error: any) {
    return { content: [{ type: "text", text: `Error: ${error.message}` }], isError: true };
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);