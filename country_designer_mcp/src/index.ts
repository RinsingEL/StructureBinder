import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { gisHandlers } from "./gis/handlers.js";
import { gisTools } from "./gis/tools.js";
import { formatAxiosError } from "./shared/http.js";
import { writeMcpLog } from "./shared/logging.js";
import type { ToolDefinition, ToolHandler } from "./shared/types.js";

const server = new Server(
  { name: "geomantia-gis-debug", version: "0.1.0" },
  { capabilities: { tools: {} } }
);

const tools: ToolDefinition[] = [...gisTools];
const handlers: Record<string, ToolHandler> = {
  ...gisHandlers,
};

server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools }));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const toolName = request.params.name;
  const toolArgs = (request.params.arguments as Record<string, unknown>) || {};
  try {
    const handler = handlers[toolName];
    if (!handler) {
      throw new Error(`Unknown tool: ${toolName}`);
    }
    const result = await handler(toolArgs);
    writeMcpLog(toolName, toolArgs, result, false);
    return result;
  } catch (error: unknown) {
    const message = formatAxiosError(error);
    const errorResult = { content: [{ type: "text" as const, text: `Error: ${message}` }], isError: true };
    writeMcpLog(toolName, toolArgs, errorResult, true, message);
    return errorResult;
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
