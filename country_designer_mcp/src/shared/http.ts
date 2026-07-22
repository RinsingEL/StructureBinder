import axios from "axios";

export const MC_API_URL = (process.env.GEOMANTIA_MC_API_URL || process.env.MC_API_URL || "http://127.0.0.1:5000")
  .replace(/\/+$/, "");

export const TIMEOUTS = {
  quick: 10_000,
  test: 600_000,
  refresh: 600_000,
};

export async function getJson(url: string, timeoutMs = TIMEOUTS.quick) {
  return axios.get(url, { timeout: timeoutMs });
}

export async function postJson(url: string, body: unknown, timeoutMs = TIMEOUTS.quick) {
  return axios.post(url, body, { timeout: timeoutMs });
}

export function formatAxiosError(error: unknown) {
  const anyError = error as any;
  return anyError?.response?.data?.error
    || (anyError?.response?.data ? JSON.stringify(anyError.response.data) : null)
    || anyError?.message
    || "Unknown error";
}

export function isTimeoutError(error: unknown) {
  const anyError = error as any;
  return anyError?.code === "ECONNABORTED"
    || anyError?.code === "ETIMEDOUT"
    || anyError?.response?.status === 408
    || anyError?.response?.status === 504
    || /(?:timed?\s*out|timeout)/i.test(anyError?.message || "");
}
