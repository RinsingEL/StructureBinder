import { MC_API_URL, TIMEOUTS, getJson, postJson } from "../shared/http.js";
import { textResult, type ToolHandler } from "../shared/types.js";

export const gisHandlers: Record<string, ToolHandler> = {
  async gis_status() {
    const res = await getJson(`${MC_API_URL}/gis/status`, TIMEOUTS.quick);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async gis_refresh(args) {
    const payload: Record<string, unknown> = {};
    assignNumber(payload, args, "radiusChunks");
    assignString(payload, args, "sampleMode");
    assignNumber(payload, args, "centerBlockX");
    assignNumber(payload, args, "centerBlockZ");
    assignString(payload, args, "dimensionId");
    assignString(payload, args, "playerName");
    const res = await postJson(`${MC_API_URL}/gis/refresh`, payload, TIMEOUTS.refresh);
    return textResult(JSON.stringify(res.data, null, 2));
  },

  async gis_test_run(args) {
    const payload: Record<string, unknown> = {};
    assignString(payload, args, "caseId");
    const res = await postJson(`${MC_API_URL}/gis/test_run`, payload, TIMEOUTS.test);
    return textResult(JSON.stringify(res.data, null, 2));
  },
};

function assignNumber(payload: Record<string, unknown>, args: Record<string, unknown>, key: string) {
  if (args[key] === undefined || args[key] === null || args[key] === "") {
    return;
  }
  const value = Number(args[key]);
  if (!Number.isFinite(value)) {
    throw new Error(`${key} must be a finite number.`);
  }
  payload[key] = value;
}

function assignString(payload: Record<string, unknown>, args: Record<string, unknown>, key: string) {
  if (args[key] === undefined || args[key] === null) {
    return;
  }
  const value = String(args[key]).trim();
  if (value) {
    payload[key] = value;
  }
}
