import { TIMEOUTS } from "../shared/http/timeouts.js";
import { McpTaskPolicy } from "../shared/task/task-policy.js";

export const territoryTaskPolicies: Record<string, McpTaskPolicy> = {
  scan_local_candidates: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.export },
  t1_candidates_for_continent: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.export },
  t3_run_continent: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.export },
};
