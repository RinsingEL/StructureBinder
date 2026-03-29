package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.core.artifact.ArtifactStore;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.core.workflow.FileStageStatusStore;
import com.user.terra_script.core.workflow.StageStatus;
import com.user.terra_script.domain.territory.stage.T4Stage;
import com.user.terra_script.server.mcp.facade.WorkflowMcpFacade;
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.server.mcp.protocol.ErrorResponse;
import com.user.terra_script.server.mcp.protocol.TaskResultResponse;
import com.user.terra_script.server.mcp.protocol.TaskStatusResponse;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.Locale;

public class WorkflowController {
    private static final Gson GSON = new Gson();
    private final MinecraftServer server;
    private final WorkflowMcpFacade facade;

    public WorkflowController(MinecraftServer server) {
        this.server = server;
        this.facade = new WorkflowMcpFacade(server);
    }

    public void handleFreezeStatus(HttpExchange exchange) throws IOException {
        try {
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(facade.freezeStatus()));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleFreezeProject(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            JsonObject result = facade.freezeProject();
            int code = result.has("ok") && result.get("ok").getAsBoolean()
                    ? 200
                    : ("already frozen".equals(result.get("message").getAsString()) ? 409 : 400);
            HttpUtil.sendResponse(exchange, code, GSON.toJson(result));
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
                    : "T1";
            T4Stage.RuntimeOptions requestOptions = parseT4Options(req, T4Stage.RuntimeOptions.defaults());
            TaskResultResponse response = facade.runWorkflowStage(stageId, requestOptions);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(response));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendResponse(exchange, 400, GSON.toJson(new ErrorResponse(e.getMessage())));
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
                TaskStatusResponse status = facade.workflowStatus(normalized);
                res.add(normalized, GSON.toJsonTree(status));
            } else {
                for (String id : new String[]{"W3", "W4", "T1", "T2", "T3", "T4"}) {
                    StageStatus status = statusStore.getStatus(id, ctx);
                    res.add(id, toJson(status));
                }
            }
            res.addProperty("heartbeat_interval_seconds", FileStageStatusStore.HEARTBEAT_INTERVAL_SECONDS);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleTaskStatus(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "GET")) return;
        try {
            String taskId = getQueryParam(exchange, "taskId");
            if (taskId == null || taskId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"taskId is required\"}");
                return;
            }
            ArtifactStore artifacts = new ArtifactStore();
            FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
            StageContext ctx = StageContext.forServer(server, artifacts, statusStore);
            JsonObject res = new JsonObject();
            res.addProperty("task_id", taskId);
            res.add("status", GSON.toJsonTree(facade.taskStatus(taskId)));
            res.addProperty("heartbeat_interval_seconds", FileStageStatusStore.HEARTBEAT_INTERVAL_SECONDS);
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    private static JsonObject toJson(StageStatus status) {
        JsonObject obj = new JsonObject();
        obj.addProperty("state", status.state.name());
        obj.addProperty("message", status.message);
        obj.addProperty("lastUpdatedMillis", status.lastUpdatedMillis);
        obj.addProperty("started_at", status.startedAtMillis);
        obj.addProperty("heartbeat_at", status.heartbeatAtMillis);
        obj.addProperty("progress_percent", status.progressPercent);
        obj.addProperty("progress_current", status.progressCurrent);
        obj.addProperty("progress_total", status.progressTotal);
        obj.addProperty("waiting_for_ai", status.waitingForAi);
        obj.addProperty("next_action", status.nextAction);
        obj.addProperty("active_operation", status.activeOperation);
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
        for (String deprecated : new String[]{
                "t4_bootstrap_city",
                "t4_city_target_chunks",
                "t4_city_bias",
                "t4_city_density",
                "t4_city_ecology",
                "t4_city_allow_water",
                "t4_legacy_scan"
        }) {
            if (req.has(deprecated)) {
                throw new IllegalArgumentException("Deprecated T4 option is no longer supported: " + deprecated);
            }
        }
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
