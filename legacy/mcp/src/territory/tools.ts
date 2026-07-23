import { ToolDefinition } from "../shared/types.js";

export const territoryTools: ToolDefinition[] = [
  {
    name: "read_history_lore",
    description: "读取本地已保存的历史设定。",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "draft_nation_concept",
    description: "设计并记录国家设定到本地硬盘。",
    inputSchema: {
      type: "object",
      properties: {
        concept_name: { type: "string" },
        preferred_region_id: { type: "number" },
        theme: { type: "string" },
        capital_requirements: { type: "string" },
        planned_index: { type: "number" },
        total_planned: { type: "number" },
      },
      required: ["concept_name", "preferred_region_id", "theme", "planned_index", "total_planned"],
    },
  },
  {
    name: "t1_blueprint_submit",
    description: "直接提交一条原始 T1 blueprint。",
    inputSchema: {
      type: "object",
      properties: {
        territory_id: { type: "string" },
        name: { type: "string" },
        target_continent_id: { type: "number" },
        continents: { type: "array", items: { type: "object" } },
        narrative: { type: "object" },
        expansion_policy: { type: "object" },
      },
      required: ["territory_id", "name", "expansion_policy"],
    },
  },
  {
    name: "t1_blueprint_list",
    description: "列出当前所有 T1 blueprint。",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "t1_generate_blueprint",
    description: "基于 W4 世界数据生成一条 T1 蓝图。",
    inputSchema: {
      type: "object",
      properties: {
        territory_id: { type: "string" },
        name: { type: "string" },
        target_continent_id: { type: "number" },
        auto_pick_region: { type: "boolean" },
        submit: { type: "boolean" },
        theme: { type: "string" },
        description: { type: "string" },
        ruler: { type: "string" },
        color: { type: "string" },
        base_power: { type: "number" },
        power_clamp: { type: "boolean" },
        power_min_override: { type: "number" },
        power_max_override: { type: "number" },
        slope_penalty: { type: "number" },
        water_penalty: { type: "number" },
        forest_penalty: { type: "number" },
        base_move: { type: "number" },
        preferred_biomes: { type: "array", items: { type: "string" } },
        avoid_biomes: { type: "array", items: { type: "string" } },
      },
      required: ["territory_id", "name"],
    },
  },
  {
    name: "scan_local_candidates",
    description: "寻找符合地理条件的候选簇并生成预览。",
    inputSchema: {
      type: "object",
      properties: {
        region_id: { type: "number" },
        territory_id: { type: "string" },
        min_slope: { type: "number" },
        max_slope: { type: "number" },
        min_tpi: { type: "number" },
        max_tpi: { type: "number" },
        limit: { type: "number" },
        limit_per_group: { type: "number" },
        interest_groups: { type: "array" },
      },
    },
  },
  {
    name: "query_region_pick",
    description: "在 scan_local_candidates 结果中选择簇并提取最终坐标点。",
    inputSchema: {
      type: "object",
      properties: {
        query_id: { type: "string" },
        target_type: { type: "string" },
        target_id: { type: "string" },
        cluster_id: { type: "number" },
        label: { type: "string" },
        preview_label: { type: "string" },
        point_mode: { type: "string", enum: ["center", "north", "south", "east", "west", "random_cardinal"] },
      },
    },
  },
  {
    name: "t1_candidates_for_continent",
    description: "按大陆生成并读取当前所有国家的 T1 候选与 blocked 状态。",
    inputSchema: {
      type: "object",
      properties: {
        continent_id: { type: "number" },
      },
      required: ["continent_id"],
    },
  },
  {
    name: "t1_select_cluster",
    description: "为指定国家在指定大陆确认最终 T1 簇。",
    inputSchema: {
      type: "object",
      properties: {
        territory_id: { type: "string" },
        continent_id: { type: "number" },
        cluster_id: { type: "number" },
        label: { type: "string" },
      },
      required: ["territory_id", "continent_id"],
    },
  },
  {
    name: "t2_direction_candidates",
    description: "读取指定国家在指定大陆的 T2 方位候选与冲突过滤结果。",
    inputSchema: {
      type: "object",
      properties: {
        territory_id: { type: "string" },
        continent_id: { type: "number" },
      },
      required: ["territory_id", "continent_id"],
    },
  },
  {
    name: "t2_select_direction",
    description: "为指定国家在指定大陆确认 T2 方位并写入领土配置。",
    inputSchema: {
      type: "object",
      properties: {
        territory_id: { type: "string" },
        continent_id: { type: "number" },
        direction: { type: "string", enum: ["center", "north", "south", "east", "west"] },
      },
      required: ["territory_id", "continent_id", "direction"],
    },
  },
  {
    name: "t3_run_continent",
    description: "对指定大陆执行竞争式 T3 扩张。",
    inputSchema: {
      type: "object",
      properties: {
        continent_id: { type: "number" },
      },
      required: ["continent_id"],
    },
  },
  {
    name: "establish_territory",
    description: "建立领土边界。",
    inputSchema: {
      type: "object",
      properties: {
        id: { type: "string" },
        name: { type: "string" },
        region_id: { type: "number" },
        capital_x: { type: "number" },
        capital_z: { type: "number" },
        power: { type: "number" },
        mountain_cost: { type: "number" },
        water_cost: { type: "number" },
        color: { type: "string" },
      },
      required: ["id", "name", "region_id", "capital_x", "capital_z", "power"],
    },
  },
  {
    name: "get_territory_status",
    description: "获取详细国情报告。",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "territory_summary",
    description: "读取指定领土摘要产物。",
    inputSchema: {
      type: "object",
      properties: {
        territory_id: { type: "string" },
        continent_id: { type: "number" },
      },
      required: ["territory_id"],
    },
  },
  {
    name: "territory_t4_window",
    description: "读取指定领土 T4 dat 的局部窗口。",
    inputSchema: {
      type: "object",
      properties: {
        territory_id: { type: "string" },
        center_x: { type: "number" },
        center_z: { type: "number" },
        radius_blocks: { type: "number" },
        max_points: { type: "number" },
        max_biome_samples: { type: "number" },
      },
      required: ["territory_id"],
    },
  },
  {
    name: "territory_capital_terrain_map",
    description: "从 T4 dat 导出首都或指定中心附近的 512 地形预览图，包含首都中心、网格、海陆/高度/坡度和国度范围暗化。",
    inputSchema: {
      type: "object",
      properties: {
        territory_id: { type: "string" },
        center_x: { type: "number" },
        center_z: { type: "number" },
        radius_blocks: { type: "number" },
        image_size: { type: "number" },
      },
      required: ["territory_id"],
    },
  },
];
