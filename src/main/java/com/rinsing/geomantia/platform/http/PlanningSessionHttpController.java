package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.provider.application.PlayerProviderService;
import com.rinsing.geomantia.systems.provider.application.PlanningSessionService;
import com.sun.net.httpserver.*;
import net.minecraft.server.MinecraftServer;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

final class PlanningSessionHttpController {
    private final Supplier<PlanningSessionService> sessions;
    private final Supplier<String> worldName;
    private final Semaphore waits = new Semaphore(2);
    PlanningSessionHttpController(MinecraftServer server) {
        this(() -> PlayerProviderService.instance().planning(), () -> server.getWorldData().getLevelName());
    }
    PlanningSessionHttpController(Supplier<PlanningSessionService> sessions, Supplier<String> worldName) {
        this.sessions = sessions; this.worldName = worldName;
    }
    private PlanningSessionService service() {
        var result = sessions.get();
        if (result == null) throw new IllegalStateException("PLANNING_WORLD_UNAVAILABLE");
        return result;
    }
    void handle(HttpExchange exchange) throws IOException {
        if (!GisHttpUtil.requireMethod(exchange, "POST")) return;
        String operation = exchange.getRequestURI().getPath().substring("/planning/".length());
        boolean waiting = false;
        try {
            JsonObject args = GisHttpUtil.readJsonObject(exchange);
            String token = exchange.getRequestHeaders().getFirst("X-Geomantia-Planning-Token");
            JsonObject result;
            switch (operation) {
                case "lobby" -> {
                    result = service().snapshot();
                    result.addProperty("worldName", worldName.get());
                    result.addProperty("ownedByThisConnection", service().owns(token));
                }
                case "resume" -> result = service().resume(string(args, "ownerId"), token,
                        args.has("retry") && args.get("retry").getAsBoolean());
                case "action" -> result = service().action(token, string(args, "taskId"), string(args, "actionId"),
                        string(args, "tool"), args.has("arguments") ? args.getAsJsonObject("arguments") : new JsonObject());
                case "artifact" -> result = service().artifact(string(args, "operation"), string(args, "path"),
                        string(args, "query"), args.has("offset") ? args.get("offset").getAsInt() : 0);
                case "wait" -> {
                    if (!waits.tryAcquire()) throw new IllegalStateException("PLANNING_TOO_MANY_WAITERS");
                    waiting = true;
                    result = service().await(token, string(args, "cursor"), args.has("timeoutSeconds") ? args.get("timeoutSeconds").getAsInt() : 20);
                }
                case "heartbeat" -> { service().heartbeat(token); result = new JsonObject(); result.addProperty("ok", true); }
                case "release" -> { service().release(token); result = new JsonObject(); result.addProperty("ok", true); }
                default -> throw new IllegalArgumentException("Unknown planning operation");
            }
            GisHttpUtil.sendJson(exchange, 200, result);
        } catch (Exception ex) {
            GisHttpUtil.sendError(exchange, ex instanceof IllegalStateException ? 409 : 400,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        } finally { if (waiting) waits.release(); }
    }
    Filter ownershipFilter() {
        return new Filter() {
            @Override public String description() { return "Serialize planning mutations with agent ownership"; }
            @Override public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                String path = exchange.getRequestURI().getPath();
                if (!"POST".equals(exchange.getRequestMethod()) || path.startsWith("/planning/")
                        || Set.of("/realm/city/design_queue/status", "/realm/city/post_d4_auto_compile_status",
                            "/gis/chunk_generation_benchmark/status").contains(path)) {
                    chain.doFilter(exchange); return;
                }
                AutoCloseable pin;
                try { pin = service().enter(exchange.getRequestHeaders().getFirst("X-Geomantia-Planning-Token")); }
                catch (IllegalStateException occupied) { GisHttpUtil.sendError(exchange, 409, occupied.getMessage()); return; }
                try (pin) { chain.doFilter(exchange); }
                catch (IOException ex) { throw ex; }
                catch (Exception ex) { throw new IOException(ex); }
            }
        };
    }
    private static String string(JsonObject args, String key) { return args.has(key) ? args.get(key).getAsString() : ""; }
}
