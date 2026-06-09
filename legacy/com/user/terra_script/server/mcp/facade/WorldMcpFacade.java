package com.user.terra_script.server.mcp.facade;

import com.google.gson.JsonObject;
import com.user.terra_script.application.world.WorldAutomationApplicationService;
import net.minecraft.server.MinecraftServer;

public final class WorldMcpFacade {
    private final WorldAutomationApplicationService applicationService;

    public WorldMcpFacade(MinecraftServer server) {
        this.applicationService = new WorldAutomationApplicationService(server);
    }

    public JsonObject startWorldScan(int chunkRadius, int targetResolution) throws Exception {
        return applicationService.startWorldScan(chunkRadius, targetResolution);
    }

    public JsonObject worldScanStatus() {
        return applicationService.worldScanStatus();
    }

    public JsonObject cancelWorldScan() {
        return applicationService.cancelWorldScan();
    }

    public JsonObject clusterWorld(int continentMinSize, int oceanMinMultiplier, int mergeDistance) throws Exception {
        return applicationService.clusterWorld(continentMinSize, oceanMinMultiplier, mergeDistance);
    }

    public JsonObject scanRegion(int regionId, int padding, int scanStep) throws Exception {
        return applicationService.scanRegion(regionId, padding, scanStep);
    }

    public JsonObject regionStatus(int regionId) {
        return applicationService.regionStatus(regionId);
    }

    public JsonObject exportW4(Integer regionId, boolean allCached) throws Exception {
        return applicationService.exportW4(regionId, allCached);
    }
}
