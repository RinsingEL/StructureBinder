package com.user.terra_script.world.city.stage.c8;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.runtime.context.RuntimeLogContext;
import com.user.terra_script.runtime.log.RuntimeLogEvent;
import com.user.terra_script.runtime.log.RuntimeLogger;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class CityJigsawSolverDebugTrace {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final RuntimeLogger logger;
    private final String debugRunId;
    private final String taskId;
    private final String cityId;
    private final String groupId;
    private final Path artifactDir;
    private final String relativeArtifactDir;
    private final List<DebugTraceStep> steps = new ArrayList<>();

    private CityJigsawSolverDebugTrace(
            RuntimeLogger logger,
            String debugRunId,
            String taskId,
            String cityId,
            String groupId,
            Path artifactDir,
            String relativeArtifactDir
    ) {
        this.logger = logger;
        this.debugRunId = debugRunId;
        this.taskId = taskId;
        this.cityId = cityId;
        this.groupId = groupId;
        this.artifactDir = artifactDir;
        this.relativeArtifactDir = relativeArtifactDir;
    }

    public static CityJigsawSolverDebugTrace create(
            MinecraftServer server,
            Path cityDir,
            String cityId,
            String groupId
    ) throws Exception {
        String actualGroupId = groupId != null && !groupId.isBlank() ? groupId : "group_unknown";
        String debugRunId = RUN_ID_FORMAT.format(LocalDateTime.now());
        String taskId = "city_jigsaw_solve_" + debugRunId;
        Path groupDir = CityGroupPathUtil.resolveGroupDir(cityDir, actualGroupId);
        Path artifactDir = groupDir.resolve("jigsaw_solver_debug").resolve(debugRunId);
        Files.createDirectories(artifactDir);
        String relativeArtifactDir = "cities/" + cityId + "/groups/" + CityGroupPathUtil.safeGroupId(actualGroupId)
                + "/jigsaw_solver_debug/" + debugRunId;
        RuntimeLogger logger = server != null
                ? RuntimeLogger.forServer(
                server,
                RuntimeLogContext.builder()
                        .domain("city")
                        .scope("task")
                        .taskId(taskId)
                        .stageId("JIGSAW_SOLVER")
                        .cityId(cityId)
                        .build()
        )
                : null;
        return new CityJigsawSolverDebugTrace(logger, debugRunId, taskId, cityId, actualGroupId, artifactDir, relativeArtifactDir);
    }

    public void addStep(
            String stepKey,
            String titleZh,
            String goalZh,
            String actionSummaryZh,
            String implementationZh,
            String resultZh,
            String status,
            JsonObject evidence
    ) {
        DebugTraceStep step = new DebugTraceStep();
        step.step_key = stepKey;
        step.title_zh = titleZh;
        step.goal_zh = goalZh;
        step.action_summary_zh = actionSummaryZh;
        step.implementation_zh = implementationZh;
        step.result_zh = resultZh;
        step.status = status == null || status.isBlank() ? "ok" : status;
        step.evidence = evidence == null ? new JsonObject() : evidence.deepCopy();
        steps.add(step);
        logStep(step);
    }

    public void attachPreview(String stepKey, String relativeImagePath) {
        if (stepKey == null || stepKey.isBlank() || relativeImagePath == null || relativeImagePath.isBlank()) return;
        for (DebugTraceStep step : steps) {
            if (step != null && stepKey.equals(step.step_key)) {
                step.preview_image = relativeImagePath;
                return;
            }
        }
    }

    public void logFinal(String messageZh, boolean ok, JsonObject details) {
        if (logger == null) return;
        JsonObject out = details == null ? new JsonObject() : details.deepCopy();
        out.addProperty("debug_run_id", debugRunId);
        out.addProperty("debug_artifact_dir", relativeArtifactDir);
        out.addProperty("step_count", steps.size());
        if (ok) logger.info(RuntimeLogEvent.TASK_COMPLETED, messageZh, out);
        else logger.error(RuntimeLogEvent.TASK_FAILED, messageZh, out);
    }

    public void writeTrace() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("debug_run_id", debugRunId);
        payload.addProperty("task_id", taskId);
        payload.addProperty("city_id", cityId);
        payload.addProperty("group_id", groupId);
        payload.addProperty("debug_artifact_dir", relativeArtifactDir);
        payload.add("debug_trace", toJson());
        payload.add("debug_preview_steps", previewStepsJson());
        Files.writeString(artifactDir.resolve("trace.json"), GSON.toJson(payload), StandardCharsets.UTF_8);
    }

    public JsonArray toJson() {
        JsonArray out = new JsonArray();
        for (DebugTraceStep step : steps) {
            out.add(step.toJson());
        }
        return out;
    }

    public JsonArray previewStepsJson() {
        JsonArray out = new JsonArray();
        for (DebugTraceStep step : steps) {
            if (step == null || step.preview_image == null || step.preview_image.isBlank()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("step_key", step.step_key);
            item.addProperty("title_zh", step.title_zh);
            item.addProperty("status", step.status);
            item.addProperty("preview_image", step.preview_image);
            out.add(item);
        }
        return out;
    }

    public String debugRunId() {
        return debugRunId;
    }

    public Path artifactDir() {
        return artifactDir;
    }

    public String relativeArtifactDir() {
        return relativeArtifactDir;
    }

    public String previewImagePath(String fileName) {
        return relativeArtifactDir + "/" + fileName;
    }

    private void logStep(DebugTraceStep step) {
        if (logger == null || step == null) return;
        JsonObject details = step.toJson();
        details.addProperty("debug_run_id", debugRunId);
        switch (normalizeStatus(step.status)) {
            case "invalid", "warning" -> logger.warn(RuntimeLogEvent.TASK_PROGRESS, step.title_zh, details);
            case "failed", "error" -> logger.error(RuntimeLogEvent.TASK_PROGRESS, step.title_zh, details);
            default -> logger.info(RuntimeLogEvent.TASK_PROGRESS, step.title_zh, details);
        }
    }

    private static String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase();
    }

    private static final class DebugTraceStep {
        String step_key;
        String title_zh;
        String goal_zh;
        String action_summary_zh;
        String implementation_zh;
        String result_zh;
        String status;
        JsonObject evidence = new JsonObject();
        String preview_image;

        JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("step_key", step_key);
            out.addProperty("title_zh", title_zh);
            out.addProperty("goal_zh", goal_zh);
            out.addProperty("action_summary_zh", action_summary_zh);
            out.addProperty("implementation_zh", implementation_zh);
            out.addProperty("result_zh", result_zh);
            out.addProperty("status", status);
            out.add("evidence", evidence == null ? new JsonObject() : evidence.deepCopy());
            if (preview_image != null && !preview_image.isBlank()) out.addProperty("preview_image", preview_image);
            return out;
        }
    }
}
