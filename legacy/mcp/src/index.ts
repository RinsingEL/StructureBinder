import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { writeMcpLog } from "./shared/logging.js";
import { formatAxiosError } from "./shared/http.js";
import { ToolDefinition, ToolHandler } from "./shared/types.js";
import { worldTools } from "./world/tools.js";
import { territoryTools } from "./territory/tools.js";
import { cityTools } from "./city/tools.js";
import { worldHandlers } from "./world/handlers.js";
import { territoryHandlers } from "./territory/handlers.js";
import { cityHandlers } from "./city/handlers.js";

const server = new Server(
  { name: "minecraft-world-architect", version: "3.9.0" },
  { capabilities: { tools: {} } }
);

const tools: ToolDefinition[] = [...worldTools, ...territoryTools, ...cityTools];
const handlers: Record<string, ToolHandler> = {
  ...worldHandlers,
  ...territoryHandlers,
  ...cityHandlers,
};

server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools }));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const toolName = request.params.name;
  const toolArgs = (request.params.arguments as any) || {};
  try {
    const handler = handlers[toolName];
    if (!handler) throw new Error(`Unknown tool: ${toolName}`);
    const result = await handler(toolArgs);
    writeMcpLog(toolName, toolArgs, result, false);
    return result;
  } catch (error: any) {
    const message = formatAxiosError(error);
    const errorResult = { content: [{ type: "text", text: `Error: ${message}` }], isError: true };
    writeMcpLog(toolName, toolArgs, errorResult, true, message);
    return errorResult;
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
