package com.user.terra_script.core.stage;

import com.user.terra_script.core.artifact.ArtifactStore;
import com.user.terra_script.core.workflow.StageStatusStore;
import net.minecraft.server.MinecraftServer;

public final class StageContext {
    public final MinecraftServer server;
    public final String worldId;
    public final ArtifactStore artifacts;
    public final StageStatusStore statusStore;

    public StageContext(MinecraftServer server, String worldId, ArtifactStore artifacts, StageStatusStore statusStore) {
        this.server = server;
        this.worldId = worldId;
        this.artifacts = artifacts;
        this.statusStore = statusStore;
    }

    public static StageContext forServer(MinecraftServer server, ArtifactStore artifacts, StageStatusStore statusStore) {
        String worldId = String.valueOf(server.overworld().getSeed());
        return new StageContext(server, worldId, artifacts, statusStore);
    }
}
