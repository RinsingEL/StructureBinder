import { MC_API_URL, TIMEOUTS, getJson, postJson } from "../shared/http.js";
import { planningResult, type ToolHandler } from "../shared/types.js";

export const realmHandlers: Record<string, ToolHandler> = {
  async realm_status() {
    const res = await getJson(`${MC_API_URL}/realm/status`, TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async realm_w_refresh(args) {
    const res = await postJson(`${MC_API_URL}/realm/w/refresh`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async realm_t1_prepare(args) {
    const res = await postJson(`${MC_API_URL}/realm/t1/prepare`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async realm_t2_select_coordinate(args) {
    const res = await postJson(`${MC_API_URL}/realm/t2/select_coordinate`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async realm_t3_expand(args) {
    const res = await postJson(`${MC_API_URL}/realm/t3/expand`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async realm_t4_build_registry(args) {
    const res = await postJson(`${MC_API_URL}/realm/t4/build_registry`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async realm_t4_patch_planning_create(args) {
    const res = await postJson(`${MC_API_URL}/realm/t4/patch_planning/create`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async realm_t4_patch_planning_select_capital(args) {
    const res = await postJson(`${MC_API_URL}/realm/t4/patch_planning/select_capital`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async realm_t4_patch_planning_add_city(args) {
    const res = await postJson(`${MC_API_URL}/realm/t4/patch_planning/add_city`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async realm_t4_patch_planning_finalize(args) {
    const res = await postJson(`${MC_API_URL}/realm/t4/patch_planning/finalize`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_design_queue_refresh(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/design_queue/refresh`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_design_queue_status(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/design_queue/status`, payload(args), TIMEOUTS.quick);
    return { ...planningResult(res.data), isError: res.data?.status === "needs_agent" };
  },

  async patch_explorer_open(args) {
    const res = await postJson(`${MC_API_URL}/realm/patch_explorer/open`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async patch_explorer_show_candidates(args) {
    const res = await postJson(`${MC_API_URL}/realm/patch_explorer/show_candidates`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async patch_explorer_select_candidate(args) {
    const res = await postJson(`${MC_API_URL}/realm/patch_explorer/select_candidate`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async realm_run_acceptance(args) {
    const res = await postJson(`${MC_API_URL}/realm/acceptance/run`, payload(args), TIMEOUTS.test);
    return planningResult(res.data);
  },

  async realm_tag_audit(args) {
    const res = await postJson(`${MC_API_URL}/realm/tag_audit`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async realm_debug_command(args) {
    const res = await postJson(`${MC_API_URL}/realm/debug/command`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_plan_d2(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/plan_d2`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_plan_d3(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/plan_d3`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async city_review_d3_site(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/review_d3_site`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_prepare_d4_blueprint_context(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/prepare_d4_blueprint_context`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_submit_d4_blueprint(args) {
    // Submission now solves and freezes design geometry, just like the compiler.
    // A quick-request timeout can report failure while the server accepts the design.
    const res = await postJson(`${MC_API_URL}/realm/city/submit_d4_blueprint`, payload(args), TIMEOUTS.refresh);
    return { ...planningResult(res.data), isError: res.data?.ok === false };
  },

  async city_post_d4_auto_compile_status(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/post_d4_auto_compile_status`, payload(args), TIMEOUTS.quick);
    return { ...planningResult(res.data), isError: res.data?.status === "needs_agent" };
  },

  async city_post_d4_auto_compile_retry(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/post_d4_auto_compile_retry`, payload(args), TIMEOUTS.quick);
    return { ...planningResult(res.data), isError: res.data?.ok === false };
  },

  async city_compile_d4_blueprint(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/compile_d4_blueprint`, payload(args), TIMEOUTS.refresh);
    return { ...planningResult(res.data), isError: res.data?.ok === false };
  },

  async city_plan_d5(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/plan_d5`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_query_decoration_catalog() {
    const res = await getJson(`${MC_API_URL}/realm/city/query_decoration_catalog`, TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_upgrade_default_decoration_catalog(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/upgrade_default_decoration_catalog`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_probe_decoration_terrain(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/probe_decoration_terrain`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_query_structure_catalog(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/query_structure_catalog`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_query_template_metadata(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/query_template_metadata`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_plan_city_dressing(args) {
    assertDecorationIntentRequest(args);
    const res = await postJson(`${MC_API_URL}/realm/city/plan_city_dressing`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_plan_decoration_anchor_candidates(args) {
    assertDecorationIntentRequest(args);
    const res = await postJson(`${MC_API_URL}/realm/city/plan_decoration_anchor_candidates`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async city_plan_land_use(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/plan_land_use`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async city_execute_d5(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/execute_d5`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async city_plan_d6(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/plan_d6`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_execute_d7(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/execute_d7`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async city_query_worldgen_observations(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/query_worldgen_observations`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_plan_city_walls(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/plan_city_walls`, payload(args), TIMEOUTS.quick);
    return planningResult(res.data);
  },

  async city_execute_city_walls(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/execute_city_walls`, payload(args), TIMEOUTS.refresh);
    return planningResult(res.data);
  },

  async city_run_workflow(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/run_workflow`, payload(args), TIMEOUTS.test);
    return planningResult(res.data);
  },
};

function payload(args: Record<string, unknown>) {
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(args)) {
    if (value !== undefined && value !== null && value !== "") {
      result[key] = value;
    }
  }
  return result;
}

const FORBIDDEN_DECORATION_INTENT_FIELDS = new Set([
  "targetBounds",
  "targetMask",
  "memberBounds",
  "origin",
  "axisU",
  "axisV",
  "anchorBlock",
  "x",
  "y",
  "z",
  "blockOperation",
  "blockOperations",
  "blockState",
  "blocks",
  "nbtFile",
  "templateRef",
]);

const LEGACY_DECORATION_FIELDS = new Set([
  "dressingBrushPlan",
  "dressingLayoutItems",
  "itemType",
  "fillAlgorithm",
]);

function assertDecorationIntentRequest(args: Record<string, unknown>) {
  rejectForbiddenDecorationFields(args, "request");
  if (!isObject(args.decorationProgramPlan)) {
    throw new Error("CITY_DECORATION_PROGRAM_PLAN_REQUIRED: decorationProgramPlan object is required.");
  }
  const plan = args.decorationProgramPlan;
  if (typeof plan.styleProfileId !== "string" || plan.styleProfileId.trim() === "") {
    throw new Error("CITY_DECORATION_STYLE_PROFILE_ID_REQUIRED: decorationProgramPlan.styleProfileId is required.");
  }
  if (typeof plan.styleProfileHash !== "string" || plan.styleProfileHash.trim() === "") {
    throw new Error("CITY_DECORATION_STYLE_PROFILE_HASH_REQUIRED: decorationProgramPlan.styleProfileHash is required.");
  }
  const programs = plan.programs;
  if (!Array.isArray(programs) || programs.length === 0) {
    throw new Error("CITY_DECORATION_PROGRAMS_REQUIRED: decorationProgramPlan.programs[] is required.");
  }
  for (let index = 0; index < programs.length; index++) {
    const program = programs[index];
    if (!isObject(program) || !isObject(program.targetArea)
      || !["patch", "land_use_area"].includes(String(program.targetArea.sourceType))) {
      throw new Error(`CITY_DECORATION_TARGET_SOURCE_TYPE_UNSUPPORTED: programs[${index}] accepts sourceType=patch|land_use_area.`);
    }
  }
}

function rejectForbiddenDecorationFields(value: unknown, path: string) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => rejectForbiddenDecorationFields(item, `${path}[${index}]`));
    return;
  }
  if (!isObject(value)) {
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    if (LEGACY_DECORATION_FIELDS.has(key)) {
      throw new Error(`CITY_DRESSING_LEGACY_SCHEMA_REMOVED: ${path}.${key} is not accepted.`);
    }
    if (FORBIDDEN_DECORATION_INTENT_FIELDS.has(key)) {
      throw new Error(`CITY_DECORATION_INTENT_FIELD_FORBIDDEN: ${path}.${key} is not accepted.`);
    }
    rejectForbiddenDecorationFields(child, `${path}.${key}`);
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
