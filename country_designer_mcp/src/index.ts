import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { gisHandlers } from "./gis/handlers.js";
import { gisTools } from "./gis/tools.js";
import { realmHandlers } from "./realm/handlers.js";
import { realmTools } from "./realm/tools.js";
import { formatAxiosError, isTimeoutError } from "./shared/http.js";
import { beginMcpCall, completeMcpCall } from "./shared/logging.js";
import type { ToolDefinition, ToolHandler } from "./shared/types.js";

const server = new Server(
  { name: "geomantia-gis-debug", version: "0.1.0" },
  { capabilities: { tools: {} } }
);

const tools: ToolDefinition[] = [...gisTools, ...realmTools];
const handlers: Record<string, ToolHandler> = {
  ...gisHandlers,
  ...realmHandlers,
};

server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools }));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const toolName = request.params.name;
  const toolArgs = (request.params.arguments as Record<string, unknown>) || {};
  const call = beginMcpCall(toolName, toolArgs);
  try {
    const handler = handlers[toolName];
    if (!handler) {
      throw new Error(`Unknown tool: ${toolName}`);
    }
    const result = await handler(toolArgs);
    completeMcpCall(call, result, result.isError ? "error" : "success");
    return result;
  } catch (error: unknown) {
    const message = formatAxiosError(error);
    const errorResult = { content: [{ type: "text" as const, text: `Error: ${message}` }], isError: true };
    completeMcpCall(call, errorResult, isTimeoutError(error) ? "timeout" : "error", message);
    return errorResult;
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
