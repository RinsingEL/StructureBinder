package com.user.terra_script.domain.territory.stage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.runtime.context.RuntimeLogContext;
import com.user.terra_script.runtime.log.RuntimeLogEvent;
import com.user.terra_script.runtime.log.RuntimeLogger;

import java.nio.file.Path;

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

    private static void append(StageContext ctx, java.nio.file.Path path, String event, JsonObject details) {
        if (ctx == null || ctx.server == null || path == null) return;
        JsonObject payload = details == null ? new JsonObject() : details.deepCopy();
        payload.addProperty("legacy_stage_trace_path", path.toString());
        payload.addProperty("legacy_event", event == null ? "unknown" : event);
        RuntimeLogger.forServer(
                ctx.server,
                RuntimeLogContext.builder()
                        .domain("territory")
                        .scope("stage")
                        .stageId(stageIdFromPath(path))
                        .build()
        ).info(RuntimeLogEvent.STAGE_COMPLETED, "Territory stage trace event recorded.", payload);
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
