import { createServer, type IncomingMessage } from "node:http";
import { randomUUID } from "node:crypto";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { isInitializeRequest } from "@modelcontextprotocol/sdk/types.js";
import { createMcpServer } from "./server.js";

export async function startMcpHttpServer(port: number, role?: "ADVANCED" | "FLASH") {
  const sessions = new Map<string, { transport: StreamableHTTPServerTransport; close: () => Promise<void>; usedAt: number; active: number }>();
  let boundPort = port, closing = false;
  async function body(req: IncomingMessage) {
    let size = 0; const chunks: Buffer[] = [];
    for await (const chunk of req) {
      size += chunk.length;
      if (size > 8 * 1024 * 1024) throw new Error("MCP request exceeds 8 MiB");
      chunks.push(chunk);
    }
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  }
  const http = createServer(async (req, res) => {
    const path = req.url?.split("?")[0];
    // Local-only service: reject browser cross-origin and DNS rebinding before processing any RPC.
    const hosts = [`127.0.0.1:${boundPort}`, `localhost:${boundPort}`];
    if (!hosts.includes(req.headers.host ?? "") || req.headers.origin && !hosts.map(host => `http://${host}`).includes(req.headers.origin)) {
      res.writeHead(403).end("Local MCP origin required"); return;
    }
    if (path === "/health" && req.method === "GET") {
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ service: "geomantia-mcp", port: boundPort })); return;
    }
    if (path !== "/mcp") { res.writeHead(404).end(); return; }
    if (closing) { res.writeHead(503).end(); return; }
    let entry: typeof sessions extends Map<string, infer V> ? V : never;
    try {
      const id = req.headers["mcp-session-id"];
      const payload = req.method === "POST" ? await body(req) : undefined;
      if (typeof id === "string" && sessions.has(id)) entry = sessions.get(id)!;
      else if (id) { res.writeHead(404).end("MCP session expired; initialize again"); return; }
      else if (req.method === "POST" && isInitializeRequest(payload)) {
        if (sessions.size >= 32) { res.writeHead(503).end("Too many MCP sessions"); return; }
        const service = createMcpServer(false, role);
        const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: randomUUID, enableJsonResponse: true,
          onsessioninitialized: sessionId => { sessions.set(sessionId, entry); } });
        entry = { transport, close: service.close, usedAt: Date.now(), active: 0 };
        const onclose = service.server.onclose;
        service.server.onclose = () => { if (transport.sessionId) sessions.delete(transport.sessionId); onclose?.(); };
        await service.server.connect(transport);
      } else { res.writeHead(400).end("Initialize an MCP session first"); return; }
      entry.usedAt = Date.now(); entry.active++;
      try { await entry.transport.handleRequest(req, res, payload); }
      finally { entry.active--; entry.usedAt = Date.now(); }
    } catch (error) {
      if (!res.headersSent) res.writeHead(400, { "Content-Type": "application/json" }).end(JSON.stringify({ error: error instanceof Error ? error.message : "MCP request failed" }));
      else res.end();
    }
  });
  // Long design submissions use the existing compiler budget; idle network sessions are bounded.
  http.requestTimeout = 700_000;
  await new Promise<void>((resolve, reject) => { http.once("error", reject); http.listen(port, "127.0.0.1", resolve); });
  boundPort = (http.address() as { port: number }).port;
  const cleanup = setInterval(() => {
    for (const [id, session] of sessions) if (session.active === 0 && Date.now() - session.usedAt > 60 * 60_000) {
      sessions.delete(id); void session.close();
    }
  }, 60_000);
  cleanup.unref();
  return { port: boundPort, url: `http://127.0.0.1:${boundPort}/mcp`, async close() {
    if (closing) return;
    closing = true; clearInterval(cleanup);
    await Promise.allSettled([...sessions.values()].map(session => session.close()));
    sessions.clear();
    const done = new Promise<void>(resolve => http.close(() => resolve()));
    http.closeAllConnections(); await done;
  } };
}
