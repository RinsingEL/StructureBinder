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
    private final ArtifactStore store;

    public FileStageStatusStore(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public void markRunning(String stageId, StageContext ctx) throws Exception {
        writeStatus(ctx.server, ctx.worldId, stageId, StageStatus.State.RUNNING, "running");
    }

    @Override
    public void markDone(String stageId, StageContext ctx) throws Exception {
        writeStatus(ctx.server, ctx.worldId, stageId, StageStatus.State.DONE, "done");
    }

    @Override
    public void markFailed(String stageId, StageContext ctx, Exception e) throws Exception {
        String msg = e != null ? e.getMessage() : "failed";
        writeStatus(ctx.server, ctx.worldId, stageId, StageStatus.State.FAILED, msg);
    }

    @Override
    public StageStatus getStatus(String stageId, StageContext ctx) throws Exception {
        Path p = statusPath(ctx.server, ctx.worldId, stageId);
        if (!Files.exists(p)) return new StageStatus(StageStatus.State.NOT_STARTED, "", System.currentTimeMillis());
        String json = Files.readString(p, StandardCharsets.UTF_8);
        JsonObject obj = GSON.fromJson(json, JsonObject.class);
        StageStatus.State state = StageStatus.State.valueOf(obj.get("state").getAsString());
        String msg = obj.has("message") ? obj.get("message").getAsString() : "";
        long ts = obj.has("ts") ? obj.get("ts").getAsLong() : System.currentTimeMillis();
        return new StageStatus(state, msg, ts);
    }

    private void writeStatus(MinecraftServer server, String worldId, String stageId, StageStatus.State state, String message) throws Exception {
        Path p = statusPath(server, worldId, stageId);
        Files.createDirectories(p.getParent());
        JsonObject obj = new JsonObject();
        obj.addProperty("stage", stageId);
        obj.addProperty("state", state.name());
        obj.addProperty("message", message == null ? "" : message);
        obj.addProperty("ts", System.currentTimeMillis());
        Files.writeString(p, GSON.toJson(obj), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Path statusPath(MinecraftServer server, String worldId, String stageId) {
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("terra_script").resolve(worldId);
        return root.resolve("workflow").resolve(stageId + ".status.json");
    }
}
