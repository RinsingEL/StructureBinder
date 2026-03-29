import { getJson, postJson } from "../http/client.js";
import { taskResult } from "../result/task-result.js";
import { normalizeTaskStatus, pollTaskStatus } from "./task-poller.js";
import { McpTaskPolicy } from "./task-policy.js";

type InvokeTaskArgs = {
  method?: "GET" | "POST";
  url: string;
  payload?: unknown;
  policy: McpTaskPolicy;
  statusUrl?: (initialResponse: any) => string | undefined;
};

const NON_TERMINAL_STATES = new Set(["ACCEPTED", "RUNNING", "WAITING_FOR_AI"]);

export async function invokeMcTask(args: InvokeTaskArgs) {
  const method = args.method ?? "POST";
  const res = method === "GET"
    ? await getJson(args.url, args.policy.requestTimeoutMs)
    : await postJson(args.url, args.payload ?? {}, args.policy.requestTimeoutMs);
  const data = res.data;
  if (args.policy.waitMode !== "poll_until_done") {
    return taskResult(data);
  }

  const initial = normalizeTaskStatus(data);
  if (!initial) return taskResult(data);
  if (!NON_TERMINAL_STATES.has(initial.state)) return taskResult(initial);

  const statusUrl = args.statusUrl?.(data);
  if (!statusUrl) return taskResult(initial);
  const polled = await pollTaskStatus(
    statusUrl,
    args.policy.overallTimeoutMs ?? 60_000,
    args.policy.pollIntervalMs ?? 2_000,
  );
  if (polled.task_id === "unknown") {
    polled.task_id = initial.task_id;
  }
  return taskResult(polled);
}
