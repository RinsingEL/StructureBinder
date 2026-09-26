package com.rinsing.geomantia.platform.mcp;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

/** Instance-wide MCP listener settings, independent of Provider credentials and world saves. */
public record McpServerConfig(boolean enabled, int port, int flashPort, boolean embeddedAdvanced, boolean embeddedFlash) {
    public McpServerConfig(boolean enabled, int port, int flashPort) { this(enabled,port,flashPort,false,false); }
    public McpServerConfig(boolean enabled, int port) { this(enabled, port, port == 5002 ? 5003 : 5002); }
    public static McpServerConfig defaults() { return new McpServerConfig(true, 5001); }
    public McpServerConfig {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("MCP 端口必须在 1024–65535 之间");
        if (port == Integer.getInteger("geomantia.apiPort", 5000)) throw new IllegalArgumentException("MCP 端口不能与游戏内部服务端口相同");
        if (flashPort < 1024 || flashPort > 65535 || flashPort == port || flashPort == Integer.getInteger("geomantia.apiPort", 5000))
            throw new IllegalArgumentException("Flash MCP 端口必须有效且与高级端口、内部服务端口不同");
    }
    public String url() { return "http://127.0.0.1:" + port + "/mcp"; }
    public static Path path(Path gameDirectory) { return gameDirectory.resolve("config/geomantia/mcp_server.json"); }
    public static McpServerConfig load(Path gameDirectory) throws IOException {
        return loadDirectory(path(gameDirectory).getParent());
    }
    public static synchronized McpServerConfig loadDirectory(Path directory) throws IOException {
        Path path = directory.resolve("mcp_server.json");
        if (!Files.exists(path)) return new McpServerConfig(true,5001,5002,false,legacyEnabled(directory));
        try {
            JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!json.has("enabled") || !json.get("enabled").isJsonPrimitive()
                    || !json.getAsJsonPrimitive("enabled").isBoolean()
                    || !json.has("port") || !json.getAsJsonPrimitive("port").isNumber())
                throw new IllegalArgumentException("enabled 和 port 配置格式错误");
            int port = json.get("port").getAsBigDecimal().intValueExact();
            return new McpServerConfig(json.get("enabled").getAsBoolean(), port,
                    json.has("flashPort") ? json.get("flashPort").getAsBigDecimal().intValueExact() : port==5002 ? 5003 : 5002,
                    flag(json,"embeddedAdvanced",false),flag(json,"embeddedFlash",json.has("embeddedFlash") ? false : legacyEnabled(directory)));
        } catch (RuntimeException ex) { throw new IOException("MCP 配置无效：" + ex.getMessage(), ex); }
    }
    public void save(Path gameDirectory) throws IOException {
        saveDirectory(path(gameDirectory).getParent());
    }
    public boolean handles(com.rinsing.geomantia.systems.provider.application.PlanningRole role) {
        return role == com.rinsing.geomantia.systems.provider.application.PlanningRole.ADVANCED ? embeddedAdvanced : embeddedFlash;
    }
    private static boolean flag(JsonObject json,String key,boolean fallback) {
        if(!json.has(key)) return fallback;
        if(!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isBoolean()) throw new IllegalArgumentException(key+" 必须为布尔值");
        return json.get(key).getAsBoolean();
    }
    private static boolean legacyEnabled(Path directory) throws IOException {
        Path path=directory.resolve("provider.json");
        if(!Files.isRegularFile(path)) return false;
        try { return flag(JsonParser.parseString(Files.readString(path)).getAsJsonObject(),"enabled",false); }
        catch(RuntimeException ex){ throw new IOException("旧自动规划配置无效",ex); }
    }
    public static synchronized void setTakeover(Path directory,com.rinsing.geomantia.systems.provider.application.PlanningRole role,boolean enabled) throws IOException {
        var old=loadDirectory(directory);
        new McpServerConfig(old.enabled,old.port,old.flashPort,
                role==com.rinsing.geomantia.systems.provider.application.PlanningRole.ADVANCED ? enabled : old.embeddedAdvanced,
                role==com.rinsing.geomantia.systems.provider.application.PlanningRole.FLASH ? enabled : old.embeddedFlash).saveDirectory(directory);
    }
    public void saveDirectory(Path directory) throws IOException {
        synchronized(McpServerConfig.class) { saveDirectoryLocked(directory); }
    }
    private void saveDirectoryLocked(Path directory) throws IOException {
        Path target = directory.resolve("mcp_server.json");
        Files.createDirectories(target.getParent());
        JsonObject json = new JsonObject(); json.addProperty("enabled", enabled); json.addProperty("port", port);
        json.addProperty("flashPort", flashPort);
        json.addProperty("embeddedAdvanced",embeddedAdvanced); json.addProperty("embeddedFlash",embeddedFlash);
        Path staging = Files.createTempFile(target.getParent(), "mcp-settings-", ".json");
        try {
            Files.writeString(staging, new GsonBuilder().setPrettyPrinting().create().toJson(json));
            try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(staging); }
    }
}
