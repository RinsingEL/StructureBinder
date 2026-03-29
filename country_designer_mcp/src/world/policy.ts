import { TIMEOUTS } from "../shared/http/timeouts.js";
import { McpTaskPolicy } from "../shared/task/task-policy.js";

export const worldTaskPolicies: Record<string, McpTaskPolicy> = {
  world_scan_start: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.worldScan },
  w4_region_scan: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.regionScan },
  w4_export: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.export },
  run_workflow_stage: { waitMode: "poll_until_done", requestTimeoutMs: TIMEOUTS.workflow, overallTimeoutMs: 60_000, pollIntervalMs: 2_000 },
  runtime_task_timeout_test: { waitMode: "poll_until_done", requestTimeoutMs: TIMEOUTS.quick, overallTimeoutMs: 60_000, pollIntervalMs: 2_000 },
};
