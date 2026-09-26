import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createMcpServer } from "./server.js";
import { startMcpHttpServer } from "./http-server.js";

if (process.argv.includes("--http")) {
  const port = Number(process.env.GEOMANTIA_MCP_PORT ?? "5001");
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid MCP port");
  const flashPort = Number(process.env.GEOMANTIA_FLASH_MCP_PORT ?? "5002");
  if (!Number.isInteger(flashPort) || flashPort < 1 || flashPort > 65535 || flashPort === port) throw new Error("Invalid Flash MCP port");
  const service = await startMcpHttpServer(port, "ADVANCED");
  let flash;
  try { flash = await startMcpHttpServer(flashPort, "FLASH"); }
  catch (error) { await service.close(); throw error; }
  console.log(JSON.stringify({ type: "mcp_ready", port: service.port, url: service.url, flashPort: flash.port, flashUrl: flash.url }));
  const close = () => { void Promise.allSettled([service.close(), flash.close()]).finally(() => process.exit(0)); };
  process.once("SIGTERM", close);
  process.once("SIGINT", close);
  if (process.env.GEOMANTIA_PARENT_PID) {
    process.stdin.resume();
    process.stdin.once("end", close);
    const parent = Number(process.env.GEOMANTIA_PARENT_PID);
    const timer = setInterval(() => { try { process.kill(parent, 0); } catch { close(); } }, 2000);
    timer.unref();
  }
} else {
  const service = createMcpServer();
  await service.server.connect(new StdioServerTransport());
  process.once("SIGTERM", () => { void service.close().finally(() => process.exit(0)); });
}
