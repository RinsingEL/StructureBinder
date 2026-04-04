package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.core.artifact.ArtifactStore;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.core.workflow.FileStageStatusStore;
import com.user.terra_script.runtime.context.RuntimeLogContext;
import com.user.terra_script.runtime.log.RuntimeLogEvent;
import com.user.terra_script.runtime.log.RuntimeLogger;
import com.user.terra_script.runtime.task.RuntimeTaskHandle;
import com.user.terra_script.runtime.task.RuntimeTaskRegistry;
import com.user.terra_script.server.mcp.protocol.TaskAcceptedResponse;
import com.user.terra_script.server.mcp.protocol.TaskStatusResponse;
import com.user.terra_script.server.http.HttpUtil;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RuntimeDebugController {
    private static final Gson GSON = new Gson();
    // [TEST] Dedicated async executor for runtime timeout test tasks.
    private static final ExecutorService TEST_TASK_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("TerraScript-TestTask");
        return t;
    });
    private final MinecraftServer server;

    public RuntimeDebugController(MinecraftServer server) {
        this.server = server;
    }

    public void handleRuntimeDebugLogs(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
            String source = json.has("source") ? json.get("source").getAsString() : "gradle";
            int lines = json.has("contains")
                    ? (json.has("context_lines") ? json.get("context_lines").getAsInt() : (json.has("lines") ? json.get("lines").getAsInt() : 10))
                    : (json.has("lines") ? json.get("lines").getAsInt() : 80);
            String contains = json.has("contains") ? json.get("contains").getAsString() : null;
            int maxSegments = json.has("max_segments") ? json.get("max_segments").getAsInt() : 20;
            int maxTotalLines = json.has("max_total_lines") ? json.get("max_total_lines").getAsInt() : 500;

            JsonObject result = RuntimeConsoleDebugService.readLogs(source, lines, contains, maxSegments, maxTotalLines);
            result.addProperty("status", "ok");
            result.addProperty("step", "runtime_debug_logs");
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(result));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleRuntimeTaskTimeoutTest(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
            long sleepMs = json.has("sleep_ms") ? Math.max(0L, json.get("sleep_ms").getAsLong()) : 70_000L;
            String taskId = json.has("task_id") && !json.get("task_id").getAsString().isBlank()
                    ? json.get("task_id").getAsString().trim()
                    : "TEST_TIMEOUT_" + System.currentTimeMillis();
            String message = json.has("message") && !json.get("message").getAsString().isBlank()
                    ? json.get("message").getAsString()
                    : "[TEST] Runtime timeout task is sleeping.";

            ArtifactStore artifacts = new ArtifactStore();
            FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
            StageContext ctx = StageContext.forServer(server, artifacts, statusStore);

            JsonObject startDetails = new JsonObject();
            startDetails.addProperty("task_id", taskId);
            startDetails.addProperty("sleep_ms", sleepMs);
            RuntimeLogger.forServer(
                    server,
                    RuntimeLogContext.builder()
                            .domain("runtime")
                            .scope("task")
                            .taskId(taskId)
                            .build()
            ).info(RuntimeLogEvent.TASK_STARTED, "[TEST] Runtime timeout task accepted.", startDetails);

            statusStore.markRunningDetailed(taskId, ctx, message, "[TEST] runtime_timeout_sleep");
            TEST_TASK_EXECUTOR.submit(() -> runTimeoutTestTask(taskId, sleepMs));

            RuntimeTaskHandle handle = new RuntimeTaskRegistry(server).get(taskId, null);
            TaskAcceptedResponse response = TaskAcceptedResponse.from(TaskStatusResponse.from(handle));
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(response));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    // [TEST] Sleeps for the requested duration and updates shared task status for timeout-policy verification.
    private void runTimeoutTestTask(String taskId, long sleepMs) {
        try {
            ArtifactStore artifacts = new ArtifactStore();
            FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
            StageContext ctx = StageContext.forServer(server, artifacts, statusStore);
            Thread.sleep(sleepMs);
            statusStore.markDoneDetailed(taskId, ctx, "[TEST] Runtime timeout task completed.");

            JsonObject doneDetails = new JsonObject();
            doneDetails.addProperty("task_id", taskId);
            doneDetails.addProperty("sleep_ms", sleepMs);
            RuntimeLogger.forServer(
                    server,
                    RuntimeLogContext.builder()
                            .domain("runtime")
                            .scope("task")
                            .taskId(taskId)
                            .build()
            ).info(RuntimeLogEvent.TASK_COMPLETED, "[TEST] Runtime timeout task completed.", doneDetails);
        } catch (Exception e) {
            try {
                ArtifactStore artifacts = new ArtifactStore();
                FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
                StageContext ctx = StageContext.forServer(server, artifacts, statusStore);
                statusStore.markFailed(taskId, ctx, e instanceof Exception ? (Exception) e : new RuntimeException(e));
            } catch (Exception ignored) {
            }
            JsonObject errorDetails = new JsonObject();
            errorDetails.addProperty("task_id", taskId);
            errorDetails.addProperty("error", e.getMessage() == null ? "unknown" : e.getMessage());
            RuntimeLogger.forServer(
                    server,
                    RuntimeLogContext.builder()
                            .domain("runtime")
                            .scope("task")
                            .taskId(taskId)
                            .build()
            ).error(RuntimeLogEvent.TASK_FAILED, "[TEST] Runtime timeout task failed.", errorDetails);
        }
    }
}
