import { getJson } from "../http/client.js";
import { TaskStatusPayload } from "../protocol/task-types.js";

const TERMINAL_STATES = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"]);

export async function pollTaskStatus(
  statusUrl: string,
  timeoutMs: number,
  intervalMs: number,
): Promise<TaskStatusPayload> {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    const res = await getJson(statusUrl, Math.min(intervalMs, timeoutMs));
    const data = normalizeTaskStatus(res.data);
    if (data && TERMINAL_STATES.has(data.state)) return data;
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  return {
    task_id: "unknown",
    state: "TIMED_OUT",
    message: `Task did not complete within ${timeoutMs}ms.`,
  };
}

export function normalizeTaskStatus(payload: any): TaskStatusPayload | null {
  if (!payload || typeof payload !== "object") return null;
  if (typeof payload.task_id === "string" && typeof payload.state === "string") return payload as TaskStatusPayload;
  if (payload.status && typeof payload.task_id === "string" && typeof payload.status.state === "string") {
    return { task_id: payload.task_id, ...payload.status } as TaskStatusPayload;
  }
  return null;
}
