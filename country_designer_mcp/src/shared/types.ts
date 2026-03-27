export type ToolContent = { type: "text"; text: string };

export type ToolResult = {
  content: ToolContent[];
  isError?: boolean;
};

export type ToolHandler = (args: any) => Promise<ToolResult>;

export type ToolDefinition = {
  name: string;
  description: string;
  inputSchema: Record<string, any>;
};

export function textResult(text: string, isError = false): ToolResult {
  return {
    content: [{ type: "text", text }],
    ...(isError ? { isError: true } : {}),
  };
}
