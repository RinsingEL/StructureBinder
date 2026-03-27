import axios from "axios";

export const MC_API_URL = "http://localhost:5000";

export const TIMEOUTS = {
  quick: 30_000,
  workflow: 20 * 60_000,
  worldScan: 60 * 60_000,
  regionScan: 30 * 60_000,
  export: 15 * 60_000,
};

export async function getJson(url: string, timeoutMs = TIMEOUTS.quick) {
  return axios.get(url, { timeout: timeoutMs });
}

export async function postJson(url: string, body: any, timeoutMs = TIMEOUTS.quick) {
  return axios.post(url, body, { timeout: timeoutMs });
}

export function formatAxiosError(error: any) {
  return error?.response?.data?.error
    || (error?.response?.data ? JSON.stringify(error.response.data) : null)
    || error?.message
    || "Unknown error";
}
