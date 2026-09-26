import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { gisHandlers } from "./gis/handlers.js";
import { gisTools } from "./gis/tools.js";
import { realmHandlers } from "./realm/handlers.js";
import { realmTools } from "./realm/tools.js";
import { formatAxiosError, isTimeoutError } from "./shared/http.js";
import { beginMcpCall, completeMcpCall } from "./shared/logging.js";
import type { ToolDefinition, ToolHandler } from "./shared/types.js";
import axios from "axios";
import { lobbyInstructions, planningTools, createPlanningHandlers } from "./planning.js";


/** Each MCP client owns its own planning credential and tool session. */
export function createMcpServer(embeddedBridge = Boolean(process.env.GEOMANTIA_PROVIDER_TOOL_URL), role?: "ADVANCED" | "FLASH") {
  const planning = embeddedBridge ? undefined : createPlanningHandlers(undefined, role);

  const server = new Server(
    { name: "geomantia-gis-debug", version: "0.1.0" },
    { capabilities: { tools: {} }, ...(embeddedBridge ? {} : { instructions: lobbyInstructions }) }
  );

  const tools: ToolDefinition[] = role ? planningTools : [...(embeddedBridge ? [] : planningTools), ...gisTools, ...realmTools];
  const handlers: Record<string, ToolHandler> = {
    ...gisHandlers,
    ...realmHandlers,
    ...planning?.handlers,
  };

  server.setRequestHandler(ListToolsRequestSchema, async () => {
    const bridge = embeddedBridge ? process.env.GEOMANTIA_PROVIDER_TOOL_URL : undefined;
    if (!bridge) return { tools };
    // Provider decisions use the host's scoped schema, including combined selection actions.
    return (await axios.get(bridge, { timeout: 10_000,
      headers: { "X-Geomantia-Bridge-Key": process.env.GEOMANTIA_PROVIDER_TOOL_KEY || "" },
    })).data;
  });

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const toolName = request.params.name;
    const toolArgs = (request.params.arguments as Record<string, unknown>) || {};
    const call = beginMcpCall(toolName, toolArgs);
    try {
      if (role && !planningTools.some(tool => tool.name === toolName)) throw new Error("PLANNING_SCOPED_ENTRY_REQUIRED: use planning_action");
      const handler = handlers[toolName];
      const bridge = embeddedBridge ? process.env.GEOMANTIA_PROVIDER_TOOL_URL : undefined;
      if (!bridge && !handler) {
        throw new Error(`Unknown tool: ${toolName}`);
      }
      const result = bridge ? (await axios.post(bridge, { name: toolName, arguments: toolArgs }, {
        timeout: 600_000, headers: { "X-Geomantia-Bridge-Key": process.env.GEOMANTIA_PROVIDER_TOOL_KEY || "" },
      })).data : await handler(toolArgs);
      completeMcpCall(call, result, result.isError ? "error" : "success");
      return result;
    } catch (error: unknown) {
      const message = formatAxiosError(error);
      const errorResult = { content: [{ type: "text" as const, text: `Error: ${message}` }], isError: true };
      completeMcpCall(call, errorResult, isTimeoutError(error) ? "timeout" : "error", message);
      return errorResult;
    }
  });

  server.onclose = () => { void planning?.close(); };
  return { server, async close() { await server.close(); await planning?.close(); } };
}
