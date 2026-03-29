package com.user.terra_script.runtime.task;

import com.user.terra_script.core.artifact.ArtifactStore;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.core.workflow.FileStageStatusStore;
import com.user.terra_script.core.workflow.StageStatus;
import net.minecraft.server.MinecraftServer;

public final class RuntimeTaskRegistry {
    private final MinecraftServer server;

    public RuntimeTaskRegistry(MinecraftServer server) {
        this.server = server;
    }

    public RuntimeTaskHandle get(String taskId, RuntimeTaskResult result) throws Exception {
        ArtifactStore artifacts = new ArtifactStore();
        FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
        StageContext ctx = StageContext.forServer(server, artifacts, statusStore);
        StageStatus status = statusStore.getTaskStatus(taskId, ctx);
        return StageStatusTaskAdapter.toHandle(taskId, status, result);
    }
}
