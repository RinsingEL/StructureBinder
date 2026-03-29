export type WaitMode = "none" | "until_done" | "poll_until_done";

export type McpTaskPolicy = {
  waitMode: WaitMode;
  requestTimeoutMs: number;
  overallTimeoutMs?: number;
  pollIntervalMs?: number;
};
