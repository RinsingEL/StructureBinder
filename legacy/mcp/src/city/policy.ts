import { TIMEOUTS } from "../shared/http/timeouts.js";
import { McpTaskPolicy } from "../shared/task/task-policy.js";

export const cityTaskPolicies: Record<string, McpTaskPolicy> = {
  city_c1_generate: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.workflow },
  city_c2_generate: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.workflow },
  city_c3_generate: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.workflow },
  city_c4_generate: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.workflow },
  city_c5_generate: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.workflow },
  city_c6_generate: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.workflow },
  city_c7_generate: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.workflow },
  city_c8_generate: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.workflow },
  city_c9_generate: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.workflow },
  city_c6_pave_stone: { waitMode: "until_done", requestTimeoutMs: TIMEOUTS.workflow },
};
