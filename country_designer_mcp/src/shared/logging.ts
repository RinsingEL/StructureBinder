import { randomUUID } from "node:crypto";
import { appendFileSync } from "node:fs";
import { join } from "node:path";
import { performance } from "node:perf_hooks";
import type { ToolResult } from "./types.js";

const MCP_LOG_FILE = "country_designer_mcp_log.jsonl";

export type McpCallStatus = "success" | "error" | "timeout";

export interface McpCallContext {
  callId: string;
  toolName: string;
  startedAt: string;
  startedAtEpochMs: number;
  monotonicStartedAtMs: number;
}

export function beginMcpCall(toolName: string, args: Record<string, unknown>): McpCallContext {
  const startedAtEpochMs = Date.now();
  const context = {
    callId: randomUUID(),
    toolName,
    startedAt: new Date(startedAtEpochMs).toISOString(),
    startedAtEpochMs,
    monotonicStartedAtMs: performance.now(),
  };
  append({
    schemaVersion: "geomantia_mcp_call_log.v0.2",
    eventType: "mcp_call_started",
    callId: context.callId,
    recordedAt: context.startedAt,
    recordedAtEpochMs: context.startedAtEpochMs,
    startedAt: context.startedAt,
    startedAtEpochMs: context.startedAtEpochMs,
    toolName,
    args,
  });
  return context;
}

export function completeMcpCall(
  context: McpCallContext,
  result: ToolResult,
  status: McpCallStatus,
  errorMessage = ""
) {
  const endedAtEpochMs = Date.now();
  append({
    schemaVersion: "geomantia_mcp_call_log.v0.2",
    eventType: "mcp_call_completed",
    callId: context.callId,
    recordedAt: new Date(endedAtEpochMs).toISOString(),
    recordedAtEpochMs: endedAtEpochMs,
    startedAt: context.startedAt,
    startedAtEpochMs: context.startedAtEpochMs,
    endedAt: new Date(endedAtEpochMs).toISOString(),
    endedAtEpochMs,
    durationMs: Math.max(0, Number((performance.now() - context.monotonicStartedAtMs).toFixed(3))),
    status,
    toolName: context.toolName,
    isError: status !== "success",
    errorMessage,
    resultSummary: summarizeResult(result),
  });
}

function summarizeResult(result: ToolResult) {
  const textContent = result.content.filter((item) => item.type === "text").map((item) => item.text);
  const textLength = textContent.reduce((total, value) => total + value.length, 0);
  return {
    contentItemCount: result.content.length,
    textLength,
    textPreview: textContent.join("\n").slice(0, 512),
  };
}

function append(entry: Record<string, unknown>) {
  try {
    appendFileSync(join(process.cwd(), MCP_LOG_FILE), `${JSON.stringify(entry)}\n`, "utf8");
  } catch {
    // MCP logging is diagnostic only; stdio responses must remain unaffected.
  }
}
