package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.core.artifact.ArtifactStore;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.core.workflow.FileStageStatusStore;
import com.user.terra_script.core.workflow.StageRegistry;
import com.user.terra_script.core.workflow.StageStatus;
import com.user.terra_script.core.workflow.WorkflowEngine;
import com.user.terra_script.domain.territory.stage.T2Stage;
import com.user.terra_script.domain.territory.stage.T3Stage;
import com.user.terra_script.domain.territory.stage.T4Stage;
import com.user.terra_script.domain.world.stage.W3Stage;
import com.user.terra_script.domain.world.stage.W4Stage;
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.world.NationGenManager;
import com.user.terra_script.world.city.CityProjectSnapshot;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.Locale;

public class WorkflowController {
    private static final Gson GSON = new Gson();
    private final MinecraftServer server;

    public WorkflowController(MinecraftServer server) {
        this.server = server;
    }

    public void handleFreezeStatus(HttpExchange exchange) throws IOException {
        try {
            JsonObject res = new JsonObject();
            res.addProperty("frozen", NationGenManager.SnapshotManager.hasSnapshot());
            HttpUtil.sendResponse(exchange, 200, res.toString());
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleFreezeProject(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            CityProjectSnapshot.FreezeResult result = NationGenManager.SnapshotManager.freezeIfNotFrozen();
            JsonObject res = new JsonObject();
            res.addProperty("ok", result.ok);
            res.addProperty("message", result.message);
            int code = result.ok ? 200 : ("already frozen".equals(result.message) ? 409 : 400);
            HttpUtil.sendResponse(exchange, code, res.toString());
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleWorkflowRun(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject req = body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
            String stageId = req.has("stageId")
                    ? req.get("stageId").getAsString().trim().toUpperCase(Locale.ROOT)
                    : "T2";
            T4Stage.RuntimeOptions requestOptions = parseT4Options(req, T4Stage.RuntimeOptions.defaults());
            T4Stage.configure(requestOptions);

            ArtifactStore artifacts = new ArtifactStore();
            FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
            StageContext ctx = StageContext.forServer(server, artifacts, statusStore);
            StageRegistry registry = buildRegistry();
            WorkflowEngine engine = new WorkflowEngine(registry);
            var result = engine.runStage(stageId, ctx);
            String triggeredStage = null;
            String triggeredStatus = null;
            if ("T3".equals(stageId)) {
                T4Stage.configure(parseT4Options(req, T4Stage.RuntimeOptions.autoTriggerDefaults()));
                var t4 = registry.get("T4").run(ctx);
                triggeredStage = "T4";
                triggeredStatus = t4.status.name();
            }

            JsonObject res = new JsonObject();
            res.addProperty("ok", true);
            res.addProperty("stage", stageId);
            res.addProperty("status", result.status.name());
            res.addProperty("message", result.message);
            if (triggeredStage != null) {
                res.addProperty("triggered_stage", triggeredStage);
                res.addProperty("triggered_status", triggeredStatus);
            }
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleWorkflowStatus(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "GET")) return;
        try {
            String stageId = getQueryParam(exchange, "stageId");
            ArtifactStore artifacts = new ArtifactStore();
            FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
            StageContext ctx = StageContext.forServer(server, artifacts, statusStore);

            JsonObject res = new JsonObject();
            if (stageId != null && !stageId.isBlank()) {
                String normalized = stageId.trim().toUpperCase(Locale.ROOT);
                StageStatus status = statusStore.getStatus(normalized, ctx);
                res.add(normalized, toJson(status));
            } else {
                for (String id : new String[]{"W3", "W4", "T2", "T3", "T4"}) {
                    StageStatus status = statusStore.getStatus(id, ctx);
                    res.add(id, toJson(status));
                }
            }
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    private static StageRegistry buildRegistry() {
        StageRegistry registry = new StageRegistry();
        registry.register(new W3Stage());
        registry.register(new W4Stage());
        registry.register(new T2Stage());
        registry.register(new T3Stage());
        registry.register(new T4Stage());
        return registry;
    }

    private static JsonObject toJson(StageStatus status) {
        JsonObject obj = new JsonObject();
        obj.addProperty("state", status.state.name());
        obj.addProperty("message", status.message);
        obj.addProperty("lastUpdatedMillis", status.lastUpdatedMillis);
        return obj;
    }

    private static String getQueryParam(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI() != null ? exchange.getRequestURI().getQuery() : null;
        if (raw == null || raw.isBlank()) return null;
        String[] pairs = raw.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 0) continue;
            if (key.equals(kv[0])) return kv.length > 1 ? kv[1] : "";
        }
        return null;
    }

    private static T4Stage.RuntimeOptions parseT4Options(JsonObject req, T4Stage.RuntimeOptions fallback) {
        if (req == null) return fallback;
        int stride = req.has("t4_sample_stride")
                ? req.get("t4_sample_stride").getAsInt()
                : fallback.sampleStride;
        int maxChunks = req.has("t4_max_chunks")
                ? req.get("t4_max_chunks").getAsInt()
                : fallback.maxChunksPerTerritory;
        boolean loadedOnly = req.has("t4_loaded_only")
                ? req.get("t4_loaded_only").getAsBoolean()
                : fallback.loadedOnly;
        return new T4Stage.RuntimeOptions(stride, maxChunks, loadedOnly);
    }
}
