export type ToolContent = { type: "text"; text: string };

export type ToolResult = {
  content: ToolContent[];
  isError?: boolean;
};

export type ToolHandler = (args: Record<string, unknown>) => Promise<ToolResult>;

export type ToolDefinition = {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
};

export function textResult(text: string): ToolResult {
  return { content: [{ type: "text", text }] };
}
