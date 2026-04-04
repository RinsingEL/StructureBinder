import axios from "axios";
import { TIMEOUTS } from "./timeouts.js";

export const MC_API_URL = "http://localhost:5000";

export async function getJson(url: string, timeoutMs = TIMEOUTS.quick) {
  return axios.get(url, { timeout: timeoutMs });
}

export async function postJson(url: string, body: unknown, timeoutMs = TIMEOUTS.quick) {
  return axios.post(url, body, { timeout: timeoutMs });
}

export function formatAxiosError(error: any) {
  return error?.response?.data?.error
    || (error?.response?.data ? JSON.stringify(error.response.data) : null)
    || error?.message
    || "Unknown error";
}
