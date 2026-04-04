import { MC_API_URL, TIMEOUTS, getJson, postJson } from "../shared/http.js";
import { invokeMcTask } from "../shared/task/task-runner.js";
import { ToolHandler, textResult } from "../shared/types.js";
import { worldTaskPolicies } from "./policy.js";

export const worldHandlers: Record<string, ToolHandler> = {
  async get_world_atlas() {
    const res = await getJson(`${MC_API_URL}/world_atlas`, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async world_scan_start(args) {
    const payload = {
      chunk_radius: Number(args.chunk_radius),
      target_resolution: Number(args.target_resolution),
    };
    return invokeMcTask({ url: `${MC_API_URL}/world/scan/start`, payload, policy: worldTaskPolicies.world_scan_start });
  },

  async world_scan_status() {
    const res = await getJson(`${MC_API_URL}/world/scan/status`, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async world_scan_cancel() {
    const res = await postJson(`${MC_API_URL}/world/scan/cancel`, {}, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async w3_cluster(args) {
    const payload: Record<string, any> = {};
    if (args.continent_min_size !== undefined) payload.continent_min_size = Number(args.continent_min_size);
    if (args.ocean_min_size_multiplier !== undefined) payload.ocean_min_size_multiplier = Number(args.ocean_min_size_multiplier);
    if (args.merge_distance !== undefined) payload.merge_distance = Number(args.merge_distance);
    const res = await postJson(`${MC_API_URL}/world/w3/cluster`, payload, TIMEOUTS.export);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async get_t1_preview_maps(args) {
    const regionId = Number(args.region_id);
    if (!Number.isFinite(regionId) || regionId <= 0) throw new Error("region_id is required and must be > 0");
    const res = await postJson(`${MC_API_URL}/t1_preview_maps`, { region_id: regionId }, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async w4_region_scan(args) {
    const regionId = Number(args.region_id);
    if (!Number.isFinite(regionId) || regionId <= 0) throw new Error("region_id is required and must be > 0");
    const payload: Record<string, any> = { region_id: regionId };
    if (args.padding_blocks !== undefined) payload.padding_blocks = Number(args.padding_blocks);
    if (args.scan_step !== undefined) payload.scan_step = Number(args.scan_step);
    return invokeMcTask({ url: `${MC_API_URL}/world/w4/region_scan`, payload, policy: worldTaskPolicies.w4_region_scan });
  },

  async w4_region_status(args) {
    const regionId = Number(args.region_id);
    if (!Number.isFinite(regionId) || regionId <= 0) throw new Error("region_id is required and must be > 0");
    const res = await getJson(`${MC_API_URL}/world/w4/region_status?region_id=${encodeURIComponent(String(regionId))}`, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async w4_export(args) {
    const payload: Record<string, any> = {};
    if (args.region_id !== undefined) payload.region_id = Number(args.region_id);
    if (args.all_cached_regions !== undefined) payload.all_cached_regions = Boolean(args.all_cached_regions);
    return invokeMcTask({ url: `${MC_API_URL}/world/w4/export`, payload, policy: worldTaskPolicies.w4_export });
  },

  async run_workflow_stage(args) {
    const stageId = String(args.stage_id || "").trim().toUpperCase();
    if (!["W3", "W4", "T1", "T2", "T3", "T4"].includes(stageId)) {
      throw new Error("stage_id must be one of: W3, W4, T1, T2, T3, T4");
    }
    const payload: Record<string, any> = { stageId };
    if (args.t4_sample_stride !== undefined) payload.t4_sample_stride = Number(args.t4_sample_stride);
    if (args.t4_max_chunks !== undefined) payload.t4_max_chunks = Number(args.t4_max_chunks);
    if (args.t4_loaded_only !== undefined) payload.t4_loaded_only = Boolean(args.t4_loaded_only);
    return invokeMcTask({
      url: `${MC_API_URL}/workflow/run`,
      payload,
      policy: worldTaskPolicies.run_workflow_stage,
      statusUrl: (initial) => {
        const taskId = initial?.task_id;
        return taskId ? `${MC_API_URL}/task_status?taskId=${encodeURIComponent(String(taskId))}` : undefined;
      },
    });
  },

  async workflow_status(args) {
    const stageId = args.stage_id ? String(args.stage_id).trim().toUpperCase() : "";
    const url = stageId
      ? `${MC_API_URL}/workflow/status?stageId=${encodeURIComponent(stageId)}`
      : `${MC_API_URL}/workflow/status`;
    const res = await getJson(url, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async task_status(args) {
    const taskId = String(args.task_id || "").trim();
    if (!taskId) throw new Error("task_id is required");
    const res = await getJson(`${MC_API_URL}/task_status?taskId=${encodeURIComponent(taskId)}`, TIMEOUTS.quick);
    return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
  },

  async runtime_task_timeout_test(args) {
    const payload: Record<string, any> = {};
    if (args.sleep_ms !== undefined) payload.sleep_ms = Number(args.sleep_ms);
    if (args.task_id !== undefined) payload.task_id = String(args.task_id);
    if (args.message !== undefined) payload.message = String(args.message);
    return invokeMcTask({
      url: `${MC_API_URL}/runtime_debug/task_timeout_test`,
      payload,
      policy: worldTaskPolicies.runtime_task_timeout_test,
      statusUrl: (initial) => {
        const taskId = initial?.task_id;
        return taskId ? `${MC_API_URL}/task_status?taskId=${encodeURIComponent(String(taskId))}` : undefined;
      },
    });
  },
};
