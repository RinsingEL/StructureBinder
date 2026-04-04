package com.user.terra_script.server.mcp.facade;

import com.google.gson.JsonObject;
import com.user.terra_script.application.workflow.WorkflowApplicationService;
import com.user.terra_script.domain.territory.stage.T4Stage;
import com.user.terra_script.server.mcp.protocol.TaskResultResponse;
import com.user.terra_script.server.mcp.protocol.TaskStatusResponse;
import net.minecraft.server.MinecraftServer;

public final class WorkflowMcpFacade {
    private final WorkflowApplicationService applicationService;

    public WorkflowMcpFacade(MinecraftServer server) {
        this.applicationService = new WorkflowApplicationService(server);
    }

    public JsonObject freezeStatus() {
        return applicationService.freezeStatus();
    }

    public JsonObject freezeProject() {
        return applicationService.freezeProject();
    }

    public TaskResultResponse runWorkflowStage(String stageId, T4Stage.RuntimeOptions options) throws Exception {
        return applicationService.runWorkflowStage(stageId, options);
    }

    public TaskStatusResponse workflowStatus(String stageId) throws Exception {
        return applicationService.workflowStatus(stageId);
    }

    public TaskStatusResponse taskStatus(String taskId) throws Exception {
        return applicationService.taskStatus(taskId);
    }
}
