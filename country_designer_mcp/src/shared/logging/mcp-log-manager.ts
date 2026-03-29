import * as fs from "fs";
import * as path from "path";
import { fileURLToPath } from "url";
import { McpLogContext } from "./mcp-log-context.js";

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const MCP_LOG_DIR = path.join(SCRIPT_DIR, "..", "..", "..", "logs");

export function writeMcpEvent(
  context: McpLogContext,
  args: unknown,
  result: unknown,
  isError: boolean,
  errorMessage?: string,
) {
  try {
    fs.mkdirSync(MCP_LOG_DIR, { recursive: true });
    const now = new Date();
    const iso = now.toISOString();
    const safeToolName = (context.tool_name || "unknown").replace(/[^\w.-]/g, "_");
    const stamp = iso.replace(/[:.]/g, "-");
    const file = path.join(MCP_LOG_DIR, `${stamp}_${safeToolName}.json`);
    const payload = {
      ts_epoch_ms: now.getTime(),
      timestamp: iso,
      source: context.source,
      tool: context.tool_name,
      task_id: context.task_id ?? null,
      stage_id: context.stage_id ?? null,
      arguments: args ?? {},
      response: result ?? null,
      is_error: isError,
      error_message: errorMessage ?? null,
    };
    fs.writeFileSync(file, JSON.stringify(payload, null, 2), "utf-8");
  } catch (e) {
    console.error("[MCP] Failed to write log:", e);
  }
}
