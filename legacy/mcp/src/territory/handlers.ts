import * as fs from "fs";
import * as path from "path";
import { MC_API_URL, TIMEOUTS, getJson, postJson } from "../shared/http.js";
import { invokeMcTask } from "../shared/task/task-runner.js";
import { ToolHandler, textResult } from "../shared/types.js";
import { clampNumber, toFiniteNumber } from "../shared/utils.js";
import { territoryTaskPolicies } from "./policy.js";

const LORE_FILE = path.join(process.cwd(), "world_lore.json");

function appendLore(data: any) {
  let existing = [];
  if (fs.existsSync(LORE_FILE)) {
    const content = fs.readFileSync(LORE_FILE, "utf-8");
    if (content.trim()) existing = JSON.parse(content);
  }
  existing.push({ timestamp: new Date().toISOString(), ...data });
  fs.writeFileSync(LORE_FILE, JSON.stringify(existing, null, 2));
}

export const territoryHandlers: Record<string, ToolHandler> = {
  async read_history_lore() {
    if (fs.existsSync(LORE_FILE)) {
      const content = fs.readFileSync(LORE_FILE, "utf-8");
      const data = JSON.parse(content);
      return textResult(`Found ${data.length} existing records in '${LORE_FILE}'.\n${JSON.stringify(data, null, 2)}`);
    }
    return textResult("No history found.");
  },

  async draft_nation_concept(args) {
    appendLore(args);
    const isFinished = args.planned_index >= args.total_planned;
    const guide = isFinished
      ? "Planning complete! Now iterate nations to scan capitals and establish territories."
      : `Nation ${args.planned_index}/${args.total_planned} recorded. Draft next.`;
    return textResult(`[Lore Saved] '${args.concept_name}' saved.\n${guide}`);
  },

  async t1_blueprint_submit(args) {
    const res = await postJson(`${MC_API_URL}/t1_blueprint`, args, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async t1_blueprint_list() {
    const res = await getJson(`${MC_API_URL}/t1_blueprint`, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async t1_generate_blueprint(args) {
    const territoryId = String(args.territory_id || "").trim();
    const name = String(args.name || "").trim();
    if (!territoryId) throw new Error("territory_id is required");
    if (!name) throw new Error("name is required");

    const atlasRes = await getJson(`${MC_API_URL}/world_atlas`, TIMEOUTS.quick);
    const summaryRes = await getJson(`${MC_API_URL}/world_summary`, TIMEOUTS.quick);
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
    const totalLandArea = landRegions.reduce((sum: number, r: any) => sum + toFiniteNumber(r?.metrics?.area_pixels, 0), 0);
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
      clamped: shouldClampPower,
    };

    const blueprint = {
      territory_id: territoryId,
      name,
      target_continent_id: targetContinentId,
      narrative: {
        theme: args.theme || "GENERIC_REALM",
        description: args.description || `Auto-generated from world atlas/summary for region ${targetContinentId}.`,
        ruler: args.ruler || "Unknown Ruler",
        color: args.color || "0xA52A2A",
      },
      expansion_policy: {
        base_power: finalPower,
        costs: {
          base_move: Number(args.base_move ?? 1.0),
          slope_penalty: Number(args.slope_penalty ?? 0.6),
          water_penalty: Number(args.water_penalty ?? 6.0),
          forest_penalty: Number(args.forest_penalty ?? 1.5),
          preferred_biomes: Array.isArray(args.preferred_biomes) ? args.preferred_biomes : [],
          avoid_biomes: Array.isArray(args.avoid_biomes) ? args.avoid_biomes : [],
        },
      },
    };

    if (args.submit === false) {
      return textResult(JSON.stringify({ step: "T1", ok: true, mode: "draft_only", blueprint, power_range: powerRange, sources: { world_atlas: true, world_summary: true } }, null, 2));
    }

    const submitRes = await postJson(`${MC_API_URL}/t1_blueprint`, blueprint, TIMEOUTS.quick);
    return textResult(JSON.stringify({ step: "T1", ok: true, mode: "generated_and_submitted", blueprint, power_range: powerRange, sources: { world_atlas: true, world_summary: true }, submit_result: submitRes.data }, null, 2));
  },

  async scan_local_candidates(args) {
    return invokeMcTask({ url: `${MC_API_URL}/query_region`, payload: args, policy: territoryTaskPolicies.scan_local_candidates });
  },

  async query_region_pick(args) {
    const res = await postJson(`${MC_API_URL}/query_region_pick`, args || {}, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async t1_candidates_for_continent(args) {
    const continentId = Number(args.continent_id || 0);
    if (!Number.isFinite(continentId) || continentId <= 0) throw new Error("continent_id is required");
    return invokeMcTask({ url: `${MC_API_URL}/t1_candidates_for_continent`, payload: { continent_id: continentId }, policy: territoryTaskPolicies.t1_candidates_for_continent });
  },

  async t1_select_cluster(args) {
    const territoryId = String(args.territory_id || "").trim();
    const continentId = Number(args.continent_id || 0);
    if (!territoryId) throw new Error("territory_id is required");
    if (!Number.isFinite(continentId) || continentId <= 0) throw new Error("continent_id is required");
    if (args.cluster_id === undefined && !String(args.label || "").trim()) {
      throw new Error("cluster_id or label is required");
    }
    const res = await postJson(`${MC_API_URL}/t1_select_cluster`, args, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async t2_direction_candidates(args) {
    const territoryId = String(args.territory_id || "").trim();
    const continentId = Number(args.continent_id || 0);
    if (!territoryId) throw new Error("territory_id is required");
    if (!Number.isFinite(continentId) || continentId <= 0) throw new Error("continent_id is required");
    const res = await postJson(`${MC_API_URL}/t2_direction_candidates`, { territory_id: territoryId, continent_id: continentId }, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async t2_select_direction(args) {
    const territoryId = String(args.territory_id || "").trim();
    const continentId = Number(args.continent_id || 0);
    const direction = String(args.direction || "").trim();
    if (!territoryId) throw new Error("territory_id is required");
    if (!Number.isFinite(continentId) || continentId <= 0) throw new Error("continent_id is required");
    if (!direction) throw new Error("direction is required");
    const res = await postJson(`${MC_API_URL}/t2_select_direction`, { territory_id: territoryId, continent_id: continentId, direction }, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async t3_run_continent(args) {
    const continentId = Number(args.continent_id || 0);
    if (!Number.isFinite(continentId) || continentId <= 0) throw new Error("continent_id is required");
    return invokeMcTask({ url: `${MC_API_URL}/t3_run_continent`, payload: { continent_id: continentId }, policy: territoryTaskPolicies.t3_run_continent });
  },

  async establish_territory(args) {
    let colorInt = 0xff0000;
    if (args.color) {
      if (typeof args.color === "number") colorInt = args.color;
      else if (typeof args.color === "string") colorInt = parseInt(args.color.replace(/^#|0x/, ""), 16);
    }
    const res = await postJson(`${MC_API_URL}/create_territory`, { ...args, color: colorInt }, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async get_territory_status() {
    const res = await getJson(`${MC_API_URL}/territory_status`, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async territory_summary(args) {
    const territoryId = String(args.territory_id || "").trim();
    if (!territoryId) throw new Error("territory_id is required");
    const continentId = args.continent_id !== undefined ? Number(args.continent_id) : undefined;
    const query = Number.isFinite(continentId) && (continentId as number) > 0
      ? `territoryId=${encodeURIComponent(territoryId)}&continentId=${encodeURIComponent(String(continentId))}`
      : `territoryId=${encodeURIComponent(territoryId)}`;
    const res = await getJson(`${MC_API_URL}/territory/summary?${query}`, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async territory_t4_window(args) {
    const territoryId = String(args.territory_id || "").trim();
    if (!territoryId) throw new Error("territory_id is required");
    const payload = {
      territory_id: territoryId,
      center_x: args.center_x,
      center_z: args.center_z,
      radius_blocks: args.radius_blocks,
      max_points: args.max_points,
      max_biome_samples: args.max_biome_samples,
    };
    const res = await postJson(`${MC_API_URL}/territory/t4_window`, payload, TIMEOUTS.quick);
    return { content: [{ type: "text", text: JSON.stringify(res.data, null, 2) }] };
  },

  async territory_capital_terrain_map(args) {
    const territoryId = String(args.territory_id || "").trim();
    if (!territoryId) throw new Error("territory_id is required");
    const payload: Record<string, any> = { territory_id: territoryId };
    if (args.center_x !== undefined) payload.center_x = Number(args.center_x);
    if (args.center_z !== undefined) payload.center_z = Number(args.center_z);
    if (args.radius_blocks !== undefined) payload.radius_blocks = Number(args.radius_blocks);
    if (args.image_size !== undefined) payload.image_size = Number(args.image_size);
    const res = await postJson(`${MC_API_URL}/territory/capital_terrain_map`, payload, TIMEOUTS.workflow);
    return textResult(JSON.stringify(res.data, null, 2));
  },
};
