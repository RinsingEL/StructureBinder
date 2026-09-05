export type ToolContent = { type: "text"; text: string } | { type: "image"; data: string; mimeType: string };

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

export function planningResult(data: Record<string, unknown>): ToolResult {
  const { imageEvidence, ...text } = data;
  const result = textResult(JSON.stringify(text));
  if (Array.isArray(imageEvidence)) {
    for (const image of imageEvidence) {
      if (image?.type === "image" && image.mimeType === "image/png" && typeof image.data === "string") {
        result.content.push({ type: "image", data: image.data, mimeType: image.mimeType });
      }
    }
  }
  result.isError = data.ok === false || data.status === "needs_agent" || data.status === "failed";
  return result;
}
