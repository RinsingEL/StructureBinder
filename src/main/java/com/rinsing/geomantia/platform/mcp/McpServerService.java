package com.rinsing.geomantia.platform.mcp;

import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.*;

/** Owns the MCP process for the game lifetime, including the title screen and world switches. */
public final class McpServerService implements AutoCloseable {
    private static final McpServerService INSTANCE = new McpServerService();
    public static McpServerService instance() { return INSTANCE; }
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Geomantia-MCP-Lifecycle"); t.setDaemon(true); return t;
    });
    private volatile Path gameDirectory;
    private volatile Process process;
    private volatile Snapshot snapshot = new Snapshot("starting", "", "MCP 等待初始化", true, 5001);
    private volatile boolean needsSetup;
    private volatile int flashPort = 5002;
    public int flashPort() { return flashPort; }
    public String flashUrl() { return snapshot.state().equals("ready") ? "http://127.0.0.1:" + flashPort + "/mcp" : ""; }
    public boolean needsSetup() { return needsSetup; }
    private boolean hookInstalled;
    private volatile boolean closed;
    public record Snapshot(String state, String url, String message, boolean enabled, int port) { }
    public Snapshot snapshot() { return snapshot; }

    public synchronized void start(Path directory) {
        if (gameDirectory != null) return;
        gameDirectory = directory.toAbsolutePath().normalize();
        if (!hookInstalled) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "Geomantia-MCP-Shutdown"));
            hookInstalled = true;
        }
        if (!Files.isRegularFile(McpServerConfig.path(gameDirectory))
                && net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
            needsSetup=true;
            snapshot=new Snapshot("setup", "", "选择端口并保存以启动外部 MCP 连接", true, 5001);
            return;
        }
        lifecycle.execute(() -> {
            try { restart(McpServerConfig.load(gameDirectory)); }
            catch (Exception ex) { failed(ex, null); }
        });
    }
    public CompletableFuture<Snapshot> save(boolean enabled, int port) {
        return save(enabled, port, flashPort);
    }
    public CompletableFuture<Snapshot> save(boolean enabled, int port, int flashPort) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (gameDirectory == null) throw new IOException("MCP 尚未初始化");
                McpServerConfig config = new McpServerConfig(enabled, port, flashPort);
                config.save(gameDirectory);
                needsSetup=false;
                restart(config);
            } catch (Exception ex) { failed(ex, null); }
            return snapshot;
        }, lifecycle);
    }
    private void restart(McpServerConfig config) throws Exception {
        if (closed) throw new IOException("MCP 服务已关闭");
        flashPort = config.flashPort();
        stopChild();
        if (!config.enabled()) { snapshot = new Snapshot("disabled", "", "MCP 自动服务已关闭", false, config.port()); return; }
        snapshot = new Snapshot("starting", "", "正在启动 MCP 服务…", true, config.port());
        Path runtime = McpNodeRuntime.ensureInstalled(gameDirectory,
                ignored -> snapshot = new Snapshot("starting", "", "首次准备 MCP 内置运行环境…", true, config.port()));
        Path bundle = installBundle(gameDirectory);
        Path log = gameDirectory.resolve("logs/geomantia-mcp-server.log");
        Files.createDirectories(log.getParent());
        ProcessBuilder builder = new ProcessBuilder(runtime.resolve("node.exe").toString(), bundle.toString(), "--http")
                .directory(gameDirectory.toFile()).redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        // A game-launched server must never inherit a development Provider capability bridge.
        builder.environment().remove("GEOMANTIA_PROVIDER_TOOL_URL");
        builder.environment().remove("GEOMANTIA_PROVIDER_TOOL_KEY");
        builder.environment().put("GEOMANTIA_MCP_PORT", Integer.toString(config.port()));
        builder.environment().put("GEOMANTIA_FLASH_MCP_PORT", Integer.toString(config.flashPort()));
        builder.environment().put("GEOMANTIA_MC_API_URL", "http://127.0.0.1:" + Integer.getInteger("geomantia.apiPort", 5000));
        builder.environment().put("GEOMANTIA_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("MCP 启动已取消");
        Process child = builder.start(); process = child;
        if (closed) { stopChild(); return; }
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try (var lines = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = lines.readLine()) != null;) {
                    var event = JsonParser.parseString(line).getAsJsonObject();
                    if (event.has("type") && "mcp_ready".equals(event.get("type").getAsString())
                            && event.get("port").getAsInt() == config.port()) ready.complete(null);
                }
                ready.completeExceptionally(new IOException("MCP 服务未就绪即退出；请检查端口是否被占用。日志：" + log));
            } catch (Exception ex) { ready.completeExceptionally(ex); }
        }, "Geomantia-MCP-Output");
        reader.setDaemon(true); reader.start();
        child.onExit().thenRun(() -> {
            if (process == child) snapshot = new Snapshot("error", "", "MCP 服务已退出，请查看日志或保存设置重启", true, config.port());
        });
        try {
            ready.get(30, TimeUnit.SECONDS);
            if (!child.isAlive()) throw new IOException("MCP 启动后异常退出");
            snapshot = new Snapshot("ready", config.url(), "高级 MCP：" + config.url() + "；Flash MCP：http://127.0.0.1:" + config.flashPort() + "/mcp", true, config.port());
        } catch (Exception ex) { stopChild(); failed(ex, config); }
    }
    static Path installBundle(Path directory) throws Exception {
        byte[] bytes;
        try (var input = McpServerService.class.getResourceAsStream("/geomantia/sidecar/geomantia-mcp-bundle.mjs")) {
            if (input == null) throw new IOException("Mod 中缺少 MCP 服务资源");
            bytes = input.readAllBytes();
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path target = directory.resolve("config/geomantia/runtime/mcp/" + hash + "/server.mjs");
        if (!Files.isRegularFile(target) || !MessageDigest.isEqual(Files.readAllBytes(target), bytes)) {
            Files.createDirectories(target.getParent()); Files.write(target, bytes);
        }
        return target;
    }
    private void failed(Exception ex, McpServerConfig config) {
        Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
        snapshot = new Snapshot("error", "", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(),
                config == null ? snapshot.enabled() : config.enabled(), config == null ? snapshot.port() : config.port());
    }
    private void stopChild() {
        Process child = process; process = null;
        if (child == null) return;
        try { child.getOutputStream().close(); } catch (IOException ignored) { }
        try { if (!child.waitFor(2, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); } }
        catch (InterruptedException interrupted) { child.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
    @Override public void close() { closed = true; lifecycle.shutdownNow(); stopChild(); }
}
