package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;

public final class CityWorkflowStepRunner {
    private final JsonObject report;
    private final JsonArray steps;
    private final boolean skipExisting;
    private final Function<Path, String> artifactReference;
    private final ReportWriter reportWriter;

    public CityWorkflowStepRunner(JsonObject report,
                                  JsonArray steps,
                                  boolean skipExisting,
                                  Function<Path, String> artifactReference,
                                  ReportWriter reportWriter) {
        this.report = Objects.requireNonNull(report, "report");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.skipExisting = skipExisting;
        this.artifactReference = Objects.requireNonNull(artifactReference, "artifactReference");
        this.reportWriter = Objects.requireNonNull(reportWriter, "reportWriter");
    }

    public boolean runStep(String name, Path skipArtifact, StepAction action) throws IOException {
        if (skipArtifact != null && skipExisting && Files.exists(skipArtifact)) {
            addSkippedStep(name, skipArtifact, "Existing artifact found.");
            return true;
        }
        JsonObject step = new JsonObject();
        step.addProperty("name", name);
        step.addProperty("startedAt", Instant.now().toString());
        steps.add(step);
        long started = System.nanoTime();
        try {
            JsonObject response = action.run();
            boolean ok = response == null || !response.has("ok") || response.get("ok").getAsBoolean();
            step.addProperty("status", ok ? "success" : "failed");
            step.addProperty("ok", ok);
            step.addProperty("responseStatus", stringValue(response, "status", ""));
            step.addProperty("reasonCode", stringValue(response, "reasonCode", ""));
            copyResponseSummary(response, step);
            finishTimedStep(step, started);
            writeReport();
            return ok;
        } catch (Exception ex) {
            step.addProperty("status", "failed");
            step.addProperty("ok", false);
            step.addProperty("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            step.addProperty("reasonCode", reasonFromError(ex));
            finishTimedStep(step, started);
            writeReport();
            return false;
        }
    }

    public void addSkippedStep(String name, Path artifact, String message) throws IOException {
        JsonObject step = baseImmediateStep(name, "skipped", "WORKFLOW_EXISTING_ARTIFACT", message);
        step.addProperty("artifact", artifactReference.apply(artifact));
        steps.add(step);
        writeReport();
    }

    public void addStop(String name, String status, String reasonCode, String message) throws IOException {
        steps.add(baseImmediateStep(name, status, reasonCode, message));
        writeReport();
    }

    public JsonObject finish(long workflowStarted, String status) throws IOException {
        report.addProperty("status", status);
        report.addProperty("ok", "completed".equals(status)
                || "waiting_for_worldgen".equals(status)
                || "waiting_for_confirmation".equals(status)
                || "awaiting_city_blueprint".equals(status));
        report.addProperty("endedAt", Instant.now().toString());
        report.addProperty("durationMs", (System.nanoTime() - workflowStarted) / 1_000_000L);
        writeReport();

        JsonObject response = new JsonObject();
        response.addProperty("ok", report.get("ok").getAsBoolean());
        response.addProperty("status", status);
        response.add("workflowReport", report.deepCopy());
        response.add("artifacts", report.getAsJsonObject("artifacts").deepCopy());
        return response;
    }

    public void writeReport() throws IOException {
        reportWriter.write(report);
    }

    private static void copyResponseSummary(JsonObject response, JsonObject step) {
        if (response != null && response.has("artifacts") && response.get("artifacts").isJsonObject()) {
            step.add("artifacts", response.getAsJsonObject("artifacts").deepCopy());
        }
        if (response != null && response.has("cityWallPlan") && response.get("cityWallPlan").isJsonObject()) {
            JsonObject wallPlan = response.getAsJsonObject("cityWallPlan");
            step.addProperty("wallSegmentCount", array(wallPlan, "wallSegments").size());
            step.addProperty("wallNodeCount", array(wallPlan, "wallNodes").size());
            step.addProperty("wallUnitCount", array(wallPlan, "wallUnits").size());
        }
        if (response != null && response.has("d4CandidateSession")
                && response.get("d4CandidateSession").isJsonObject()) {
            JsonObject session = response.getAsJsonObject("d4CandidateSession");
            step.addProperty("selectedAnchorCount", intValue(session, "selectedAnchorCount", 0));
            step.addProperty("remainingSlotCount", intValue(session, "remainingSlotCount", 0));
        }
    }

    private static JsonObject baseImmediateStep(String name, String status, String reasonCode, String message) {
        JsonObject step = new JsonObject();
        step.addProperty("name", name);
        step.addProperty("status", status);
        step.addProperty("ok", true);
        step.addProperty("reasonCode", reasonCode);
        step.addProperty("message", message);
        step.addProperty("startedAt", Instant.now().toString());
        step.addProperty("endedAt", Instant.now().toString());
        step.addProperty("durationMs", 0);
        return step;
    }

    private static void finishTimedStep(JsonObject step, long started) {
        step.addProperty("endedAt", Instant.now().toString());
        step.addProperty("durationMs", (System.nanoTime() - started) / 1_000_000L);
    }

    private static String reasonFromError(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        int colon = message.indexOf(':');
        String prefix = colon > 0 ? message.substring(0, colon) : message;
        return prefix.length() <= 80 ? prefix : prefix.substring(0, 80);
    }

    private static JsonArray array(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return obj.getAsJsonArray(key);
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsInt();
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }

    @FunctionalInterface
    public interface StepAction {
        JsonObject run() throws Exception;
    }

    @FunctionalInterface
    public interface ReportWriter {
        void write(JsonObject report) throws IOException;
    }
}
