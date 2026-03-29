package com.user.terra_script.application.world;

import com.google.gson.JsonObject;
import com.user.terra_script.runtime.context.RuntimeLogContext;
import com.user.terra_script.runtime.log.RuntimeLogEvent;
import com.user.terra_script.runtime.log.RuntimeLogger;
import net.minecraft.server.MinecraftServer;

public final class WorldAutomationApplicationService {
    private final MinecraftServer server;

    public WorldAutomationApplicationService(MinecraftServer server) {
        this.server = server;
    }

    public JsonObject startWorldScan(int chunkRadius, int targetResolution) throws Exception {
        RuntimeLogger logger = logger("world_scan_start", null);
        JsonObject request = new JsonObject();
        request.addProperty("chunk_radius", chunkRadius);
        request.addProperty("target_resolution", targetResolution);
        logger.info(RuntimeLogEvent.HTTP_REQUEST_RECEIVED, "Starting world scan.", request);
        JsonObject response = com.user.terra_script.server.mcp.WorldAutomationService.startWorldScan(server, chunkRadius, targetResolution);
        logger.info(RuntimeLogEvent.HTTP_REQUEST_SUCCEEDED, "World scan finished.", response);
        return response;
    }

    public JsonObject worldScanStatus() {
        return com.user.terra_script.server.mcp.WorldAutomationService.scanStatus();
    }

    public JsonObject cancelWorldScan() {
        return com.user.terra_script.server.mcp.WorldAutomationService.cancelWorldScan();
    }

    public JsonObject clusterWorld(int continentMinSize, int oceanMinMultiplier, int mergeDistance) throws Exception {
        RuntimeLogger logger = logger("w3_cluster", "W3");
        logger.info(RuntimeLogEvent.STAGE_STARTED, "Starting W3 cluster.");
        JsonObject response = com.user.terra_script.server.mcp.WorldAutomationService.clusterWorld(server, continentMinSize, oceanMinMultiplier, mergeDistance);
        logger.info(RuntimeLogEvent.STAGE_COMPLETED, "W3 cluster completed.", response);
        return response;
    }

    public JsonObject scanRegion(int regionId, int padding, int scanStep) throws Exception {
        String taskId = "w4_region_" + regionId;
        RuntimeLogger logger = logger(taskId, "W4");
        JsonObject details = new JsonObject();
        details.addProperty("region_id", regionId);
        details.addProperty("padding_blocks", padding);
        details.addProperty("scan_step", scanStep);
        logger.info(RuntimeLogEvent.TASK_STARTED, "Starting W4 region scan.", details);
        JsonObject response = com.user.terra_script.server.mcp.WorldAutomationService.scanRegion(server, regionId, padding, scanStep);
        logger.info(RuntimeLogEvent.TASK_COMPLETED, "W4 region scan completed.", response);
        return response;
    }

    public JsonObject regionStatus(int regionId) {
        return com.user.terra_script.server.mcp.WorldAutomationService.getRegionStatus(regionId);
    }

    public JsonObject exportW4(Integer regionId, boolean allCached) throws Exception {
        RuntimeLogger logger = logger("w4_export", "W4");
        logger.info(RuntimeLogEvent.TASK_STARTED, "Starting W4 export.");
        JsonObject response = com.user.terra_script.server.mcp.WorldAutomationService.exportW4(server, regionId, allCached);
        logger.info(RuntimeLogEvent.TASK_COMPLETED, "W4 export completed.", response);
        return response;
    }

    private RuntimeLogger logger(String taskId, String stageId) {
        return RuntimeLogger.forServer(
                server,
                RuntimeLogContext.builder()
                        .domain("world")
                        .scope("task")
                        .taskId(taskId)
                        .stageId(stageId)
                        .build()
        );
    }
}
