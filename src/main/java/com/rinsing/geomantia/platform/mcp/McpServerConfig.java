package com.rinsing.geomantia.platform.mcp;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

/** Instance-wide MCP listener settings, independent of Provider credentials and world saves. */
public record McpServerConfig(boolean enabled, int port) {
    public static McpServerConfig defaults() { return new McpServerConfig(true, 5001); }
    public McpServerConfig {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("MCP 端口必须在 1024–65535 之间");
        if (port == Integer.getInteger("geomantia.apiPort", 5000)) throw new IllegalArgumentException("MCP 端口不能与游戏内部服务端口相同");
    }
    public String url() { return "http://127.0.0.1:" + port + "/mcp"; }
    public static Path path(Path gameDirectory) { return gameDirectory.resolve("config/geomantia/mcp_server.json"); }
    public static McpServerConfig load(Path gameDirectory) throws IOException {
        Path path = path(gameDirectory);
        if (!Files.exists(path)) { var defaults = defaults(); defaults.save(gameDirectory); return defaults; }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!json.has("enabled") || !json.get("enabled").isJsonPrimitive()
                    || !json.getAsJsonPrimitive("enabled").isBoolean()
                    || !json.has("port") || !json.getAsJsonPrimitive("port").isNumber())
                throw new IllegalArgumentException("enabled 和 port 配置格式错误");
            int port = json.get("port").getAsBigDecimal().intValueExact();
            return new McpServerConfig(json.get("enabled").getAsBoolean(), port);
        } catch (RuntimeException ex) { throw new IOException("MCP 配置无效：" + ex.getMessage(), ex); }
    }
    public void save(Path gameDirectory) throws IOException {
        Path target = path(gameDirectory);
        Files.createDirectories(target.getParent());
        JsonObject json = new JsonObject(); json.addProperty("enabled", enabled); json.addProperty("port", port);
        Path staging = Files.createTempFile(target.getParent(), "mcp-settings-", ".json");
        try {
            Files.writeString(staging, new GsonBuilder().setPrettyPrinting().create().toJson(json));
            try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(staging); }
    }
}
