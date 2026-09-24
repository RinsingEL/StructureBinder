import axios from "axios";
import { randomUUID } from "node:crypto";
import { MC_API_URL } from "./shared/http.js";
import { planningResult, textResult, type ToolDefinition, type ToolHandler, type ToolResult } from "./shared/types.js";

export const lobbyInstructions = `你已连接 Geomantia，Minecraft 世界规划服务。
首次接入本服务时，先调用 geomantia_lobby，将返回的存档大厅主界面呈现给用户，然后等待用户选择。不要仅报告连接成功，不要因为连接成功就开始扫描或规划。用户已经明确要求开始/继续规划时，可以呈现简短状态后直接执行，不要重复询问。
大厅中的名称和进度必须来自工具实时结果，不可编造。用户选择继续后调用 planning_resume；通过 planning_action 使用任务包提供的工具与参数，实际查看返回的预览图，其他资料使用 planning_artifact 按需读取。running/waiting 时调用 planning_wait；准备完成后再次 resume。完成、明确阻塞或用户暂停时停止，暂停调用 planning_release。再次进入时从存档恢复，不能依赖旧聊天推测进度。`;

const str = { type: "string" };
function tool(name: string, description: string, properties: Record<string, unknown> = {}, required: string[] = []): ToolDefinition {
  return { name, description, inputSchema: { type: "object", properties, required, additionalProperties: false } };
}
export const planningTools = [
  tool("geomantia_lobby", "首次连接首先调用：只读当前存档大厅，向用户展示菜单并等待选择；不启动规划。"),
  tool("planning_resume", "用户要求开始或继续后调用。恢复存档、执行程序步骤并准备与内置 Harness 相同的任务/图片；busy 时不抢占。", { retry: { type: "boolean", description: "仅在阻塞已处理、用户要求重试后设为 true。" } }),
  tool("planning_action", "执行任务包 tools 中的一个工具。只用当前 taskId；重试同一请求时保持 actionId 不变。", { taskId: str, actionId: str, tool: str, arguments: { type: "object" } }, ["taskId", "actionId", "tool", "arguments"]),
  tool("planning_wait", "等待状态变化，最多20秒；不重复返回图片。准备好后调用 resume 获取任务。", { cursor: str, timeoutSeconds: { type: "integer", minimum: 0, maximum: 20 } }, ["cursor"]),
  tool("planning_release", "用户暂停或任务结束时释放占用，已提交进度保存在存档；运行中的程序操作不会被强制中断。"),
  tool("planning_artifact", "按需浏览/搜索/读取当前规划 run 的资料或实际图片；仅当前 run 内只读，path 为相对路径。", { operation: { type: "string", enum: ["list", "search", "text", "image"] }, path: str, query: str, offset: { type: "integer", minimum: 0 } }, ["operation"]),
];

export function renderLobby(data: Record<string, any>): string {
  const stage: Record<string, string> = { W: "世界扫描", T1: "国度设计", T2: "国度选址", T3: "领土扩张", T4: "城市选址", CITY: "城市设计", EXTENSION: "附属内容设计", QUEUE_REFRESH: "城市队列准备", WAITING: "等待程序推进", COMPLETE: "规划完成" };
  return ["```text", "+-------- Geomantia · 存档大厅 --------+",
    `  当前存档：${data.worldName ?? "未知"}`, `  当前阶段：${stage[data.stage] ?? data.stage ?? "未知"}`,
    ...(data.citySeedId ? [`  当前城市：${data.citySeedId}`] : []),
    ...(data.extensionTitle ? [`  当前任务：${data.extensionTitle}`] : []),
    `  状态：${data.error || ({ready:"可以继续",running:"程序处理中",waiting:"等待程序推进",complete:"规划完成",blocked:"遇到阻塞"} as Record<string,string>)[data.status] || data.status}`,
    ...(data.owner ? [`  当前执行端：${data.owner === "embedded" ? "游戏内置 Harness" : data.ownedByThisConnection ? "本次连接" : "其他外部 Agent"}`] : []),
    "+-------------------------------------+", "",
    `  [1] ${data.status === "complete" ? "查看最终成果" : data.hasSavedProgress ? "继续规划" : "开始规划"}`,
    "  [2] 查看当前成果", "  [3] 查看规划状态与问题", "```",
    "告诉我你想进行哪一项，也可以直接描述需求。",
    "执行指引：1 在尚未完成时调用 planning_resume，已完成则查看成果；2/3 使用 planning_artifact 查看当前 run 的资料，不启动规划。存在其他执行端占用时先说明情况，不抢占。",
    JSON.stringify(data),
  ].join("\n");
}

export function actionResult(data: Record<string, any>): ToolResult {
  const { output, ...status } = data;
  const result = textResult(JSON.stringify(status));
  if (Array.isArray(output)) {
    for (const part of output) {
      if (part.type === "input_text") result.content.push({ type: "text", text: part.text });
      else if (part.type === "input_image") {
        const match = /^data:(image\/(?:png|jpeg|webp));base64,(.+)$/s.exec(part.image_url ?? "");
        if (match) result.content.push({ type: "image", mimeType: match[1], data: match[2] });
      }
    }
  } else if (output !== undefined) result.content.push({ type: "text", text: typeof output === "string" ? output : JSON.stringify(output) });
  result.isError = data.ok === false;
  return result;
}

export function createPlanningHandlers(baseUrl = MC_API_URL) {
  const ownerId = randomUUID();
  let token = "";
  let activeRequests = 0, lastActivity = Date.now();
  async function request(operation: string, args: Record<string, unknown> = {}) {
    if (operation !== "heartbeat") { activeRequests++; lastActivity = Date.now(); }
    try {
      const response = await axios.post(`${baseUrl}/planning/${operation}`, args,
        { timeout: operation === "action" ? 660_000 : 30_000, headers: { "X-Geomantia-Planning-Token": token } });
      return response.data;
    } finally {
      if (operation !== "heartbeat") { activeRequests--; lastActivity = Date.now(); }
    }
  }
  const heartbeat = setInterval(() => {
    // An idle MCP process can outlive a cancelled chat. Do not keep that task occupied forever.
    if (token && (activeRequests > 0 || Date.now() - lastActivity < 120_000))
      request("heartbeat").catch(() => { /* A temporary disconnect must not erase the credential. */ });
  }, 30_000);
  heartbeat.unref();
  const handlers: Record<string, ToolHandler> = {
    async geomantia_lobby() {
      try { return textResult(renderLobby(await request("lobby"))); }
      catch { return textResult("```text\n+-- Geomantia · 存档大厅 --+\n  游戏状态：暂时无法读取存档\n  请确认已进入世界且游戏服务可用\n  [1] 重新连接 / 刷新大厅\n+-------------------------+\n```\n不要开始规划或编造进度。用户重试时调用 geomantia_lobby。"); }
    },
    async planning_resume(args) {
      const data = await request("resume", { ...args, ownerId });
      token = data.leaseToken; delete data.leaseToken;
      if (data.status === "complete") { await request("release"); token = ""; }
      return planningResult(data);
    },
    async planning_action(args) { return actionResult(await request("action", args)); },
    async planning_wait(args) { return planningResult(await request("wait", args)); },
    async planning_release() { const data = await request("release"); token = ""; return planningResult(data); },
    async planning_artifact(args) { return planningResult(await request("artifact", args)); },
  };
  return { handlers, async close() { clearInterval(heartbeat); if (token) await request("release").catch(() => {}); token = ""; } };
}
