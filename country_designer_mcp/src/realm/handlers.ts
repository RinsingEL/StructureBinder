import { MC_API_URL, TIMEOUTS, getJson, postJson } from "../shared/http.js";
import { textResult, type ToolHandler } from "../shared/types.js";

export const realmHandlers: Record<string, ToolHandler> = {
  async realm_status() {
    const res = await getJson(`${MC_API_URL}/realm/status`, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async realm_w_refresh(args) {
    const res = await postJson(`${MC_API_URL}/realm/w/refresh`, payload(args), TIMEOUTS.refresh);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async realm_t1_prepare(args) {
    const res = await postJson(`${MC_API_URL}/realm/t1/prepare`, payload(args), TIMEOUTS.refresh);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async realm_t2_select_coordinate(args) {
    const res = await postJson(`${MC_API_URL}/realm/t2/select_coordinate`, payload(args), TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async realm_t3_expand(args) {
    const res = await postJson(`${MC_API_URL}/realm/t3/expand`, payload(args), TIMEOUTS.refresh);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async realm_t4_build_registry(args) {
    const res = await postJson(`${MC_API_URL}/realm/t4/build_registry`, payload(args), TIMEOUTS.refresh);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async realm_run_acceptance(args) {
    const res = await postJson(`${MC_API_URL}/realm/acceptance/run`, payload(args), TIMEOUTS.test);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async realm_tag_audit(args) {
    const res = await postJson(`${MC_API_URL}/realm/tag_audit`, payload(args), TIMEOUTS.refresh);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async city_plan_d2(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/plan_d2`, payload(args), TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async city_plan_d3(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/plan_d3`, payload(args), TIMEOUTS.refresh);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async city_plan_d4(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/plan_d4`, payload(args), TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async city_plan_d5(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/plan_d5`, payload(args), TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async city_execute_d5(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/execute_d5`, payload(args), TIMEOUTS.refresh);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async city_plan_d6(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/plan_d6`, payload(args), TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async city_execute_d7(args) {
    const res = await postJson(`${MC_API_URL}/realm/city/execute_d7`, payload(args), TIMEOUTS.refresh);
    return textResult(JSON.stringify(res.data, null, 2));
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
