import { ToolDefinition } from "../shared/types.js";

export const worldTools: ToolDefinition[] = [
  {
    name: "get_world_atlas",
    description: "【Step 1】获取世界图集（W4 汇总：atlas + summary）。",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "world_scan_start",
    description: "启动全图世界扫描并阻塞等待结果。",
    inputSchema: {
      type: "object",
      properties: {
        chunk_radius: { type: "number", description: "扫描半径（区块）" },
        target_resolution: { type: "number", description: "目标分辨率" },
      },
      required: ["chunk_radius", "target_resolution"],
    },
  },
  {
    name: "world_scan_status",
    description: "读取当前全图扫描进度。",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "world_scan_cancel",
    description: "请求取消当前全图扫描。",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "w3_cluster",
    description: "基于当前全图扫描结果执行 W3 大陆/海洋聚类并导出。",
    inputSchema: {
      type: "object",
      properties: {
        continent_min_size: { type: "number" },
        ocean_min_size_multiplier: { type: "number" },
        merge_distance: { type: "number" },
      },
    },
  },
  {
    name: "get_t1_preview_maps",
    description: "【T1 预览】按 region_id 生成并返回 T1 预览图。",
    inputSchema: {
      type: "object",
      properties: {
        region_id: { type: "number", description: "目标大陆/区域 ID" },
      },
      required: ["region_id"],
    },
  },
  {
    name: "w4_region_scan",
    description: "按 region_id 执行一次 W4 局部 detail scan，并阻塞等待结果。",
    inputSchema: {
      type: "object",
      properties: {
        region_id: { type: "number" },
        padding_blocks: { type: "number" },
        scan_step: { type: "number" },
      },
      required: ["region_id"],
    },
  },
  {
    name: "w4_region_status",
    description: "读取某个 region 的 W4 detail cache 状态。",
    inputSchema: {
      type: "object",
      properties: {
        region_id: { type: "number" },
      },
      required: ["region_id"],
    },
  },
  {
    name: "w4_export",
    description: "基于当前缓存导出 W4 产物。",
    inputSchema: {
      type: "object",
      properties: {
        region_id: { type: "number" },
        all_cached_regions: { type: "boolean" },
      },
    },
  },
  {
    name: "run_workflow_stage",
    description: "运行工作流阶段（W3/W4/T1/T2/T3/T4）。",
    inputSchema: {
      type: "object",
      properties: {
        stage_id: { type: "string", enum: ["W3", "W4", "T1", "T2", "T3", "T4"] },
        t4_sample_stride: { type: "number" },
        t4_max_chunks: { type: "number" },
        t4_loaded_only: { type: "boolean" },
      },
      required: ["stage_id"],
    },
  },
  {
    name: "workflow_status",
    description: "查询工作流阶段状态。可选传 stage_id 查询单阶段。",
    inputSchema: {
      type: "object",
      properties: {
        stage_id: { type: "string", enum: ["W3", "W4", "T1", "T2", "T3", "T4"] },
      },
    },
  },
  {
    name: "task_status",
    description: "查询长任务状态。",
    inputSchema: {
      type: "object",
      properties: {
        task_id: { type: "string", description: "服务端返回的 task_id" },
      },
      required: ["task_id"],
    },
  },
];
