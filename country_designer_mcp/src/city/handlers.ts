import { MC_API_URL, TIMEOUTS, getJson, postJson } from "../shared/http.js";
import { invokeMcTask } from "../shared/task/task-runner.js";
import { ToolHandler, textResult } from "../shared/types.js";
import { buildCreateCityPayload } from "../shared/utils.js";
import { generateC1IntentImage, smokeTestC1IntentImage } from "./image-intent.js";
import { generateC1GeometryIntent } from "./geometry-intent.js";
import { cityTaskPolicies } from "./policy.js";

export const cityHandlers: Record<string, ToolHandler> = {
  async list_available_structures(args) {
    const res = await getJson(`${MC_API_URL}/structures`, TIMEOUTS.quick);
    const all = res.data as any[];
    const query = String(args.search_query || "").toLowerCase();
    const filtered = all
      .filter((s: any) => s.id.toLowerCase().includes(query) || (s.type && s.type.includes(query)))
      .slice(0, args.limit || 50);
    return textResult(JSON.stringify(filtered, null, 2));
  },

  async establish_city(args) {
    const payload = buildCreateCityPayload(args);
    const res = await postJson(`${MC_API_URL}/create_city`, payload, cityTaskPolicies.city_c1_generate.requestTimeoutMs);
    return textResult(`City established! ID: ${res.data.city_id}, Size: ${res.data.actual_size} chunks.`);
  },

  async city_c1_generate(args) {
    return invokeMcTask({ url: `${MC_API_URL}/city_c1_generate`, payload: buildCreateCityPayload(args), policy: cityTaskPolicies.city_c1_generate });
  },

  async city_c1_image_intent_prepare(args) {
    return postCityJson("/city_c1_image_intent_prepare", normalizeImageIntentPayload(args), TIMEOUTS.workflow);
  },

  async city_c1_image_intent_generate(args) {
    const payload = normalizeImageIntentPayload(args);
    const preparedRes = await postJson(`${MC_API_URL}/city_c1_image_intent_prepare`, payload, TIMEOUTS.workflow);
    const prepared = preparedRes.data;
    const generated = await generateC1IntentImage(args, prepared);
    if (!generated.generated) {
      return textResult(JSON.stringify({
        status: "prepared_offline_import_required",
        reason: generated.reason,
        error_code: generated.error_code,
        error: generated.error,
        city_id: prepared.city_id,
        prepared,
        next_action: "Set OPENAI_API_KEY or call city_c1_image_intent_import with image_path/image_base64.",
      }, null, 2));
    }
    return textResult(JSON.stringify({
      status: "generated_and_imported",
      city_id: prepared.city_id,
      prepared,
      model: generated.model,
      import_result: generated.import_result,
    }, null, 2));
  },

  async city_c1_image_intent_api_smoke_test(args) {
    const result = await smokeTestC1IntentImage(args);
    if (!result.generated) {
      return textResult(JSON.stringify({
        status: "image_api_unavailable",
        reason: result.reason,
        error_code: result.error_code,
        error: result.error,
        next_action: result.reason === "missing_openai_api_key"
          ? "Set OPENAI_API_KEY before running image API generation."
          : "Check local network/proxy, API key billing/quota, or OPENAI_IMAGE_MODEL, then rerun this smoke test.",
      }, null, 2));
    }
    return textResult(JSON.stringify({
      status: "image_api_generated",
      model: result.model,
      endpoint: result.endpoint,
      image_path: result.image_path,
      bytes: result.bytes,
    }, null, 2));
  },

  async city_c1_image_intent_import(args) {
    return postCityJson("/city_c1_image_intent_import", args, TIMEOUTS.workflow);
  },

  async city_c1_image_intent_data(args) {
    return postCityJson("/city_c1_image_intent_data", { city_id: args.city_id });
  },

  async city_c1_geometry_prepare(args) {
    return postCityJson("/city_c1_geometry_prepare", normalizeImageIntentPayload(args), TIMEOUTS.workflow);
  },

  async city_c1_geometry_generate(args) {
    const payload = normalizeImageIntentPayload(args);
    const preparedRes = await postJson(`${MC_API_URL}/city_c1_geometry_prepare`, payload, TIMEOUTS.workflow);
    const prepared = preparedRes.data;
    const generated = await generateC1GeometryIntent(args, prepared);
    return textResult(JSON.stringify(generated, null, 2));
  },

  async city_c1_geometry_import(args) {
    return postCityJson("/city_c1_geometry_import", args, TIMEOUTS.workflow);
  },

  async city_c1_geometry_patch(args) {
    return postCityJson("/city_c1_geometry_patch", args, TIMEOUTS.workflow);
  },

  async city_c1_geometry_data(args) {
    return postCityJson("/city_c1_geometry_data", { city_id: args.city_id }, TIMEOUTS.workflow);
  },

  async city_c2_generate(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.scan_step !== undefined) payload.scan_step = Number(args.scan_step);
    if (args.scan_padding_blocks !== undefined) payload.scan_padding_blocks = Number(args.scan_padding_blocks);
    return invokeMcTask({ url: `${MC_API_URL}/city_c2_generate`, payload, policy: cityTaskPolicies.city_c2_generate });
  },

  async city_c2_data(args) { return postCityJson("/city_c2_data", { city_id: args.city_id }); },
  async city_c3_generate(args) { return invokeMcTask({ url: `${MC_API_URL}/city_c3_generate`, payload: { city_id: args.city_id }, policy: cityTaskPolicies.city_c3_generate }); },
  async city_c3_data(args) { return postCityJson("/city_c3_data", { city_id: args.city_id }); },

  async place_structure(args) {
    const chunkX = Math.floor(args.x / 16);
    const chunkZ = Math.floor(args.z / 16);
    await postJson(`${MC_API_URL}/place`, { x: chunkX, z: chunkZ, id: args.structure_id }, TIMEOUTS.quick);
    return textResult(`Structure planned at [${chunkX}, ${chunkZ}].`);
  },

  async city_stage1_data(args) { return postCityJson("/city_stage1_data", { city_id: args.city_id }); },
  async city_stage2_data(args) { return postCityJson("/city_stage2_data", { city_id: args.city_id }); },
  async city_c4_whitelist_generate(args) {
    const payload: Record<string, any> = {
      city_id: args.city_id,
      primary_functions: Array.isArray(args.primary_functions) ? args.primary_functions : [],
      secondary_functions: Array.isArray(args.secondary_functions) ? args.secondary_functions : [],
    };
    if (args.version !== undefined) payload.version = String(args.version);
    if (args.source !== undefined) payload.source = String(args.source);
    if (args.rationale !== undefined) payload.rationale = String(args.rationale);
    return postCityJson("/city_c4_whitelist_generate", payload);
  },
  async city_c4_whitelist_data(args) { return postCityJson("/city_c4_whitelist_data", { city_id: args.city_id }); },
  async city_c4_generate(args) { return invokeMcTask({ url: `${MC_API_URL}/city_c4_generate`, payload: { city_id: args.city_id }, policy: cityTaskPolicies.city_c4_generate }); },
  async city_c4_data(args) { return postCityJson("/city_c4_data", { city_id: args.city_id }); },
  async city_c5_generate(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.cross_layer_merge !== undefined) payload.cross_layer_merge = Boolean(args.cross_layer_merge);
    return invokeMcTask({ url: `${MC_API_URL}/city_c5_generate`, payload, policy: cityTaskPolicies.city_c5_generate });
  },
  async city_c5_data(args) { return postCityJson("/city_c5_data", { city_id: args.city_id }); },
  async city_c6_generate(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    if (args.fill_style !== undefined) payload.fill_style = String(args.fill_style);
    return invokeMcTask({ url: `${MC_API_URL}/city_c6_generate`, payload, policy: cityTaskPolicies.city_c6_generate });
  },
  async city_c6_data(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    return postCityJson("/city_c6_data", payload);
  },
  async city_c7_generate(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    return invokeMcTask({ url: `${MC_API_URL}/city_c7_generate`, payload, policy: cityTaskPolicies.city_c7_generate });
  },
  async city_c7_data(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    return postCityJson("/city_c7_data", payload);
  },
  async city_c8_generate(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    return invokeMcTask({ url: `${MC_API_URL}/city_c8_generate`, payload, policy: cityTaskPolicies.city_c8_generate });
  },
  async city_c8_data(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    return postCityJson("/city_c8_data", payload);
  },
  async city_c8_submit(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    if (args.build_area_id !== undefined) payload.build_area_id = String(args.build_area_id);
    if (args.node_id !== undefined) payload.node_id = String(args.node_id);
    if (args.selected_template_id !== undefined) payload.selected_template_id = String(args.selected_template_id);
    if (args.selected_connector_dir !== undefined) payload.selected_connector_dir = String(args.selected_connector_dir);
    if (args.selected_rotation !== undefined) payload.selected_rotation = Number(args.selected_rotation);
    if (args.x !== undefined) payload.x = Number(args.x);
    if (args.z !== undefined) payload.z = Number(args.z);
    if (args.terrain_relax_profile !== undefined) payload.terrain_relax_profile = args.terrain_relax_profile;
    return postCityJson("/city_c8_submit", payload);
  },
  async city_c8_retry(args) {
    const payload: Record<string, any> = { city_id: args.city_id, node_id: args.node_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    if (args.build_area_id !== undefined) payload.build_area_id = String(args.build_area_id);
    return postCityJson("/city_c8_retry", payload);
  },
  async city_jigsaw_solve(args) {
    const payload: Record<string, any> = { city_id: args.city_id, parent_node_id: args.parent_node_id, selected_template_id: args.selected_template_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    if (args.build_area_id !== undefined) payload.build_area_id = String(args.build_area_id);
    if (args.parent_connector_id !== undefined) payload.parent_connector_id = String(args.parent_connector_id);
    if (args.selected_connector_dir !== undefined) payload.selected_connector_dir = String(args.selected_connector_dir);
    if (args.selected_rotation !== undefined) payload.selected_rotation = Number(args.selected_rotation);
    if (args.apply_now !== undefined) payload.apply_now = Boolean(args.apply_now);
    return postCityJson("/city_jigsaw_solve", payload, args.apply_now ? TIMEOUTS.workflow : TIMEOUTS.quick);
  },
  async city_c9_generate(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    if (args.mode !== undefined) payload.mode = String(args.mode);
    if (args.apply_blocks !== undefined) payload.apply_blocks = Boolean(args.apply_blocks);
    if (args.max_blocks !== undefined) payload.max_blocks = Number(args.max_blocks);
    return invokeMcTask({ url: `${MC_API_URL}/city_c9_generate`, payload, policy: cityTaskPolicies.city_c9_generate });
  },
  async city_c9_data(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    return postCityJson("/city_c9_data", payload);
  },
  async city_c6_pave_stone(args) {
    const payload: Record<string, any> = { city_id: args.city_id };
    if (args.group_id !== undefined) payload.group_id = String(args.group_id);
    if (args.build_area_id !== undefined) payload.build_area_id = String(args.build_area_id);
    if (args.square_only !== undefined) payload.square_only = Boolean(args.square_only);
    if (args.square_size !== undefined) payload.square_size = Number(args.square_size);
    if (args.square_count !== undefined) payload.square_count = Number(args.square_count);
    return invokeMcTask({ url: `${MC_API_URL}/city_c6_pave_stone`, payload, policy: cityTaskPolicies.city_c6_pave_stone });
  },
};

async function postCityJson(path: string, payload: any, timeout = TIMEOUTS.quick) {
  const res = await postJson(`${MC_API_URL}${path}`, payload, timeout);
  return textResult(JSON.stringify(res.data, null, 2));
}

function normalizeImageIntentPayload(args: any) {
  const payload: Record<string, any> = {};
  for (const key of ["city_id", "territory_id", "center_x", "center_z", "city_scale_bucket", "city_role", "density", "ecology", "water_policy", "radius_blocks"]) {
    if (args[key] !== undefined) payload[key] = args[key];
  }
  return payload;
}
