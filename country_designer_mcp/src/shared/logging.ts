import { appendFileSync } from "node:fs";
import { join } from "node:path";
import type { ToolResult } from "./types.js";

const MCP_LOG_FILE = "country_designer_mcp_log.jsonl";

export function writeMcpLog(
  toolName: string,
  args: Record<string, unknown>,
  result: ToolResult,
  isError: boolean,
  errorMessage = ""
) {
  try {
    const entry = {
      timestamp: Date.now(),
      toolName,
      args,
      isError,
      errorMessage,
      result,
    };
    appendFileSync(join(process.cwd(), MCP_LOG_FILE), `${JSON.stringify(entry)}\n`, "utf8");
  } catch {
    // MCP logging is diagnostic only; stdio responses must remain unaffected.
  }
}
