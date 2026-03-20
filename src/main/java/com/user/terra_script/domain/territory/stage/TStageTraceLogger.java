package com.user.terra_script.domain.territory.stage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.user.terra_script.core.stage.StageContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class TStageTraceLogger {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TStageTraceLogger() {}

    public static void stage(StageContext ctx, String stageId, String event, JsonObject details) {
        append(ctx, stagePath(ctx, stageId), event, details);
    }

    public static void territory(StageContext ctx, String stageId, String territoryId, String event, JsonObject details) {
        append(ctx, territoryPath(ctx, stageId, territoryId), event, details);
    }

    public static void continent(StageContext ctx, String stageId, int continentId, String event, JsonObject details) {
        append(ctx, continentPath(ctx, stageId, continentId), event, details);
    }

    private static void append(StageContext ctx, Path path, String event, JsonObject details) {
        if (ctx == null || ctx.server == null || path == null) return;
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("ts_epoch_ms", System.currentTimeMillis());
            root.addProperty("stage", stageIdFromPath(path));
            root.addProperty("event", event == null ? "unknown" : event);
            if (details != null) {
                for (var entry : details.entrySet()) {
                    JsonElement value = entry.getValue();
                    root.add(entry.getKey(), value == null ? null : value.deepCopy());
                }
            }
            Files.writeString(
                    path,
                    GSON.toJson(root) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
        }
    }

    private static Path stagePath(StageContext ctx, String stageId) {
        return baseDir(ctx).resolve(stageId).resolve("stage_trace.jsonl");
    }

    private static Path territoryPath(StageContext ctx, String stageId, String territoryId) {
        return baseDir(ctx)
                .resolve(stageId)
                .resolve("territories")
                .resolve(safe(territoryId) + ".jsonl");
    }

    private static Path continentPath(StageContext ctx, String stageId, int continentId) {
        return baseDir(ctx)
                .resolve(stageId)
                .resolve("continents")
                .resolve("region_" + continentId + ".jsonl");
    }

    private static Path baseDir(StageContext ctx) {
        return TerritoryStageArtifacts.baseDir(ctx.server, ctx.worldId)
                .resolve("workflow")
                .resolve("t_stage_logs");
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String cleaned = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private static String stageIdFromPath(Path path) {
        if (path == null || path.getNameCount() < 2) return "unknown";
        return path.getName(path.getNameCount() - 2).toString();
    }
}
