package com.user.terra_script.core.workflow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.user.terra_script.core.artifact.ArtifactStore;
import com.user.terra_script.core.stage.StageContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class FileStageStatusStore implements StageStatusStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int HEARTBEAT_INTERVAL_SECONDS = 60;
    private final ArtifactStore store;

    public FileStageStatusStore(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public void markRunning(String stageId, StageContext ctx) throws Exception {
        markRunningDetailed(stageId, ctx, "running", stageId);
    }

    public void markRunningDetailed(String stageId, StageContext ctx, String message, String activeOperation) throws Exception {
        long now = System.currentTimeMillis();
        StageStatus previous = getStatus(stageId, ctx);
        writeStatus(statusPath(ctx.server, ctx.worldId, stageId), stageId, StageStatus.State.RUNNING, message, previous.startedAtMillis > 0 ? previous.startedAtMillis : now, now, -1, -1L, -1L, false, "", activeOperation);
    }

    @Override
    public void markDone(String stageId, StageContext ctx) throws Exception {
        markDoneDetailed(stageId, ctx, "done");
    }

    public void markDoneDetailed(String stageId, StageContext ctx, String message) throws Exception {
        long now = System.currentTimeMillis();
        StageStatus previous = getStatus(stageId, ctx);
        writeStatus(statusPath(ctx.server, ctx.worldId, stageId), stageId, StageStatus.State.DONE, message, previous.startedAtMillis > 0 ? previous.startedAtMillis : now, now, 100, previous.progressTotal > 0 ? previous.progressTotal : previous.progressCurrent, previous.progressTotal, previous.waitingForAi, previous.nextAction, previous.activeOperation);
    }

    @Override
    public void markFailed(String stageId, StageContext ctx, Exception e) throws Exception {
        String msg = e != null ? e.getMessage() : "failed";
        long now = System.currentTimeMillis();
        StageStatus previous = getStatus(stageId, ctx);
        writeStatus(statusPath(ctx.server, ctx.worldId, stageId), stageId, StageStatus.State.FAILED, msg, previous.startedAtMillis > 0 ? previous.startedAtMillis : now, now, previous.progressPercent, previous.progressCurrent, previous.progressTotal, false, "", previous.activeOperation);
    }

    public void updateProgress(String stageId, StageContext ctx, String message, long current, long total, String activeOperation) throws Exception {
        long now = System.currentTimeMillis();
        int percent = total > 0L ? (int) Math.max(0L, Math.min(100L, (current * 100L) / total)) : -1;
        StageStatus previous = getStatus(stageId, ctx);
        writeStatus(statusPath(ctx.server, ctx.worldId, stageId), stageId, StageStatus.State.RUNNING, message, previous.startedAtMillis > 0 ? previous.startedAtMillis : now, now, percent, current, total, false, "", activeOperation);
    }

    public void updateHeartbeat(String stageId, StageContext ctx, String message, String activeOperation) throws Exception {
        long now = System.currentTimeMillis();
        StageStatus previous = getStatus(stageId, ctx);
        writeStatus(statusPath(ctx.server, ctx.worldId, stageId), stageId, previous.state == StageStatus.State.NOT_STARTED ? StageStatus.State.RUNNING : previous.state, message == null || message.isBlank() ? previous.message : message, previous.startedAtMillis > 0 ? previous.startedAtMillis : now, now, previous.progressPercent, previous.progressCurrent, previous.progressTotal, previous.waitingForAi, previous.nextAction, activeOperation == null || activeOperation.isBlank() ? previous.activeOperation : activeOperation);
    }

    public void markWaiting(String taskId, StageContext ctx, String message, String nextAction, String activeOperation) throws Exception {
        long now = System.currentTimeMillis();
        StageStatus previous = getStatus(taskId, ctx);
        writeStatus(statusPath(ctx.server, ctx.worldId, taskId), taskId, previous.state == StageStatus.State.NOT_STARTED ? StageStatus.State.DONE : previous.state, message, previous.startedAtMillis > 0 ? previous.startedAtMillis : now, now, previous.progressPercent, previous.progressCurrent, previous.progressTotal, true, nextAction, activeOperation);
    }

    @Override
    public StageStatus getStatus(String stageId, StageContext ctx) throws Exception {
        return readStatus(statusPath(ctx.server, ctx.worldId, stageId));
    }

    public StageStatus getTaskStatus(String taskId, StageContext ctx) throws Exception {
        return readStatus(statusPath(ctx.server, ctx.worldId, taskId));
    }

    public static String cityTaskId(String stageId, String cityId) {
        return "CITY_" + sanitize(stageId) + "_" + sanitize(cityId);
    }

    private StageStatus readStatus(Path p) throws Exception {
        if (!Files.exists(p)) return new StageStatus(StageStatus.State.NOT_STARTED, "", System.currentTimeMillis());
        String json = Files.readString(p, StandardCharsets.UTF_8);
        JsonObject obj = GSON.fromJson(json, JsonObject.class);
        StageStatus.State state = StageStatus.State.valueOf(obj.get("state").getAsString());
        String msg = obj.has("message") ? obj.get("message").getAsString() : "";
        long ts = obj.has("ts") ? obj.get("ts").getAsLong() : System.currentTimeMillis();
        long startedAt = obj.has("started_at") ? obj.get("started_at").getAsLong() : 0L;
        long heartbeatAt = obj.has("heartbeat_at") ? obj.get("heartbeat_at").getAsLong() : ts;
        int progressPercent = obj.has("progress_percent") ? obj.get("progress_percent").getAsInt() : -1;
        long progressCurrent = obj.has("progress_current") ? obj.get("progress_current").getAsLong() : -1L;
        long progressTotal = obj.has("progress_total") ? obj.get("progress_total").getAsLong() : -1L;
        boolean waitingForAi = obj.has("waiting_for_ai") && obj.get("waiting_for_ai").getAsBoolean();
        String nextAction = obj.has("next_action") ? obj.get("next_action").getAsString() : "";
        String activeOperation = obj.has("active_operation") ? obj.get("active_operation").getAsString() : "";
        return new StageStatus(state, msg, ts, startedAt, heartbeatAt, progressPercent, progressCurrent, progressTotal, waitingForAi, nextAction, activeOperation);
    }

    private void writeStatus(
            Path p,
            String stageId,
            StageStatus.State state,
            String message,
            long startedAt,
            long heartbeatAt,
            int progressPercent,
            long progressCurrent,
            long progressTotal,
            boolean waitingForAi,
            String nextAction,
            String activeOperation
    ) throws Exception {
        Files.createDirectories(p.getParent());
        JsonObject obj = new JsonObject();
        long now = System.currentTimeMillis();
        obj.addProperty("stage", stageId);
        obj.addProperty("state", state.name());
        obj.addProperty("message", message == null ? "" : message);
        obj.addProperty("ts", now);
        obj.addProperty("started_at", startedAt);
        obj.addProperty("heartbeat_at", heartbeatAt);
        obj.addProperty("progress_percent", progressPercent);
        obj.addProperty("progress_current", progressCurrent);
        obj.addProperty("progress_total", progressTotal);
        obj.addProperty("waiting_for_ai", waitingForAi);
        obj.addProperty("next_action", nextAction == null ? "" : nextAction);
        obj.addProperty("active_operation", activeOperation == null ? "" : activeOperation);
        Files.writeString(p, GSON.toJson(obj), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Path statusPath(MinecraftServer server, String worldId, String stageId) {
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("terra_script").resolve(worldId);
        return root.resolve("workflow").resolve(sanitize(stageId) + ".status.json");
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
