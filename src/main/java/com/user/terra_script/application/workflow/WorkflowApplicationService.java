package com.user.terra_script.application.workflow;

import com.google.gson.JsonObject;
import com.user.terra_script.config.AiProviderConfig;
import com.user.terra_script.core.artifact.ArtifactStore;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.core.workflow.FileStageStatusStore;
import com.user.terra_script.core.workflow.StageRegistry;
import com.user.terra_script.core.workflow.TaskStatusHeartbeat;
import com.user.terra_script.core.workflow.WorkflowEngine;
import com.user.terra_script.domain.territory.stage.T1Stage;
import com.user.terra_script.domain.territory.stage.T2Stage;
import com.user.terra_script.domain.territory.stage.T3Stage;
import com.user.terra_script.domain.territory.stage.T4Stage;
import com.user.terra_script.domain.world.stage.W3Stage;
import com.user.terra_script.domain.world.stage.W4Stage;
import com.user.terra_script.runtime.context.RuntimeLogContext;
import com.user.terra_script.runtime.log.RuntimeLogEvent;
import com.user.terra_script.runtime.log.RuntimeLogger;
import com.user.terra_script.runtime.task.RuntimeTaskHandle;
import com.user.terra_script.runtime.task.RuntimeTaskRegistry;
import com.user.terra_script.runtime.task.RuntimeTaskResult;
import com.user.terra_script.server.mcp.protocol.TaskResultResponse;
import com.user.terra_script.server.mcp.protocol.TaskStatusResponse;
import com.user.terra_script.world.NationGenManager;
import com.user.terra_script.world.city.CityProjectSnapshot;
import net.minecraft.server.MinecraftServer;

import java.util.Locale;

public final class WorkflowApplicationService {
    private final MinecraftServer server;

    public WorkflowApplicationService(MinecraftServer server) {
        this.server = server;
    }

    public JsonObject freezeStatus() {
        JsonObject res = new JsonObject();
        res.addProperty("frozen", NationGenManager.SnapshotManager.hasSnapshot());
        res.add("ai_config", new com.google.gson.Gson().toJsonTree(AiProviderConfig.describe()));
        return res;
    }

    public JsonObject freezeProject() {
        CityProjectSnapshot.FreezeResult result = NationGenManager.SnapshotManager.freezeIfNotFrozen();
        JsonObject res = new JsonObject();
        res.addProperty("ok", result.ok);
        res.addProperty("message", result.message);
        return res;
    }

    public TaskResultResponse runWorkflowStage(String stageId, T4Stage.RuntimeOptions requestOptions) throws Exception {
        ArtifactStore artifacts = new ArtifactStore();
        FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
        StageContext ctx = StageContext.forServer(server, artifacts, statusStore);
        StageRegistry registry = buildRegistry();
        WorkflowEngine engine = new WorkflowEngine(registry);
        String normalizedStage = stageId.trim().toUpperCase(Locale.ROOT);
        String taskId = normalizedStage;
        RuntimeLogger logger = RuntimeLogger.forServer(
                server,
                RuntimeLogContext.builder()
                        .domain("workflow")
                        .scope("task")
                        .taskId(taskId)
                        .stageId(normalizedStage)
                        .build()
        );

        T4Stage.configure(requestOptions);
        logger.info(RuntimeLogEvent.TASK_STARTED, "Workflow stage accepted.");
        statusStore.markRunningDetailed(normalizedStage, ctx, "Workflow stage accepted. Processing may take several minutes.", taskId);
        try (TaskStatusHeartbeat ignored = TaskStatusHeartbeat.start(
                statusStore,
                normalizedStage,
                ctx,
                "Workflow stage still running. Please wait and poll workflow/status.",
                taskId
        )) {
            var result = engine.runStage(normalizedStage, ctx);
            if ("T3".equals(normalizedStage)) {
                T4Stage.configure(T4Stage.RuntimeOptions.autoTriggerDefaults());
                registry.get("T4").run(ctx);
            }
            statusStore.markDoneDetailed(normalizedStage, ctx, result.message);
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", true);
            payload.addProperty("stage", normalizedStage);
            payload.addProperty("status", result.status.name());
            payload.addProperty("message", result.message);
            payload.addProperty("status_query", "/workflow/status?stageId=" + normalizedStage);
            payload.addProperty("heartbeat_interval_seconds", FileStageStatusStore.HEARTBEAT_INTERVAL_SECONDS);
            logger.info(RuntimeLogEvent.TASK_COMPLETED, result.message, payload);
            RuntimeTaskHandle handle = new RuntimeTaskRegistry(server).get(normalizedStage, new RuntimeTaskResult(payload));
            return TaskResultResponse.from(TaskStatusResponse.from(handle));
        } catch (Exception e) {
            statusStore.markFailed(normalizedStage, ctx, e);
            JsonObject details = new JsonObject();
            details.addProperty("error", e.getMessage() == null ? "unknown" : e.getMessage());
            logger.error(RuntimeLogEvent.TASK_FAILED, "Workflow stage failed.", details);
            throw e;
        }
    }

    public TaskStatusResponse workflowStatus(String stageId) throws Exception {
        ArtifactStore artifacts = new ArtifactStore();
        FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
        StageContext ctx = StageContext.forServer(server, artifacts, statusStore);
        RuntimeTaskHandle handle = new RuntimeTaskRegistry(server).get(stageId, null);
        return TaskStatusResponse.from(handle);
    }

    public TaskStatusResponse taskStatus(String taskId) throws Exception {
        RuntimeTaskHandle handle = new RuntimeTaskRegistry(server).get(taskId, null);
        return TaskStatusResponse.from(handle);
    }

    private static StageRegistry buildRegistry() {
        StageRegistry registry = new StageRegistry();
        registry.register(new W3Stage());
        registry.register(new W4Stage());
        registry.register(new T1Stage());
        registry.register(new T2Stage());
        registry.register(new T3Stage());
        registry.register(new T4Stage());
        return registry;
    }
}
