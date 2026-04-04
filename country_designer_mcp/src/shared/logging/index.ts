import { McpLogContext } from "./mcp-log-context.js";
import { writeMcpEvent } from "./mcp-log-manager.js";

export function writeMcpLog(toolName: string, args: unknown, result: unknown, isError: boolean, errorMessage?: string) {
  const anyResult = result as any;
  const taskId = anyResult?.task_id || anyResult?.status?.task_id || anyResult?.result?.task_id;
  const stageId = anyResult?.stage_id || anyResult?.result?.stage || anyResult?.result?.stage_id;
  const context: McpLogContext = {
    source: "node_mcp",
    tool_name: toolName,
    task_id: typeof taskId === "string" ? taskId : undefined,
    stage_id: typeof stageId === "string" ? stageId : undefined,
  };
  writeMcpEvent(context, args, result, isError, errorMessage);
}
