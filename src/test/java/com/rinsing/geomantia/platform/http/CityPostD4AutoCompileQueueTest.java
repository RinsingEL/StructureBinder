package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityPostD4AutoCompileQueueTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void advancesAcceptedD4ToWaitingForGeneration() throws Exception {
        try (CityPostD4AutoCompileQueue queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (runId, citySeedId) -> response("waiting_for_generation", true))) {
            JsonObject queued = queue.enqueue("run_1", "city_1");
            assertEquals("queued", queued.get("status").getAsString());

            JsonObject completed = awaitStatus(queue, "run_1", "city_1", "waiting_for_generation");
            assertEquals("WAITING_FOR_GENERATION", completed.get("reasonCode").getAsString());
            assertEquals("waiting_for_generation", completed.get("workflowStatus").getAsString());
            assertTrue(completed.get("ok").getAsBoolean());
        }
    }

    @Test
    void persistsNeedsAgentWhenWorkflowFails() throws Exception {
        try (CityPostD4AutoCompileQueue queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (runId, citySeedId) -> {
                    throw new IllegalStateException("broken stage");
                })) {
            queue.enqueue("run_2", "city_2");

            JsonObject failed = awaitStatus(queue, "run_2", "city_2", "needs_agent");
            assertEquals("POST_D4_WORKFLOW_FAILED", failed.get("reasonCode").getAsString());
            assertEquals("broken stage", failed.get("error").getAsString());
        }
    }

    @Test
    void rejectsRunPathOutsideDebugRoot() {
        try (CityPostD4AutoCompileQueue queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (runId, citySeedId) -> response("waiting_for_generation", true))) {
            assertThrows(IllegalArgumentException.class, () -> queue.enqueue("../outside", "city_3"));
        }
    }

    @Test
    void enrichesPersistedWorkflowFailureFromExistingCompileTraceWithoutRetry() throws Exception {
        Path tracePath = temporaryDirectory.resolve("run_4/city_test_runs/city_4/steps/d4")
                .resolve("city_generation_compile_trace.json");
        Files.createDirectories(tracePath.getParent());
        JsonObject trace = new JsonObject();
        JsonArray selections = new JsonArray();
        JsonObject selection = new JsonObject();
        selection.addProperty("status", "no_legal_candidate");
        selection.addProperty("groupId", "civic_core");
        selection.addProperty("structureRef", "geomantia:city/trek/landmark/plains_fountain_01");
        JsonArray attempts = new JsonArray();
        JsonObject placementAttempt = new JsonObject();
        JsonArray hardBlocks = new JsonArray();
        hardBlocks.add("D4_ARRAY_LAYOUT_FRONTAGE_ENTRANCE_AMBIGUOUS: frontageEntranceId required.");
        placementAttempt.add("hardBlocks", hardBlocks);
        attempts.add(placementAttempt);
        selection.add("attempts", attempts);
        selections.add(selection);
        trace.add("selections", selections);
        Files.writeString(tracePath, trace.toString());

        try (CityPostD4AutoCompileQueue queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (runId, citySeedId) -> failedWorkflowResponse())) {
            queue.enqueue("run_4", "city_4");
            JsonObject failed = awaitStatus(queue, "run_4", "city_4", "needs_agent");
            assertEquals("D4_BLUEPRINT_REVISION_REQUIRED", failed.get("reasonCode").getAsString());
            assertEquals("city_submit_d4_blueprint", failed.get("nextAction").getAsString());
            JsonObject step = failed.getAsJsonObject("workflowResponse").getAsJsonObject("workflowReport")
                    .getAsJsonArray("steps").get(0).getAsJsonObject();
            assertEquals("civic_core", step.getAsJsonObject("failureSummary").get("groupId").getAsString());
            assertEquals(1, failed.get("attempt").getAsInt());
        }
    }

    @Test
    void republishesTerminalStateToUpperQueueDuringRestart() throws Exception {
        Path statePath = temporaryDirectory.resolve("run_5/automation/post_d4/city_5.json");
        Files.createDirectories(statePath.getParent());
        JsonObject persisted = new JsonObject();
        persisted.addProperty("runId", "run_5");
        persisted.addProperty("citySeedId", "city_5");
        persisted.addProperty("status", "needs_agent");
        persisted.addProperty("nextAction", "city_submit_d4_blueprint");
        Files.writeString(statePath, persisted.toString());
        AtomicReference<JsonObject> reconciled = new AtomicReference<>();

        try (CityPostD4AutoCompileQueue ignored = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (runId, citySeedId) -> response("waiting_for_generation", true), reconciled::set)) {
            assertEquals("city_submit_d4_blueprint",
                    reconciled.get().get("nextAction").getAsString());
        }
    }

    private static JsonObject awaitStatus(CityPostD4AutoCompileQueue queue, String runId, String citySeedId,
                                          String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        JsonObject current;
        do {
            current = queue.status(runId, citySeedId);
            if (expected.equals(current.get("status").getAsString())) return current;
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected " + expected + " but was " + current);
    }

    private static JsonObject response(String status, boolean ok) {
        JsonObject response = new JsonObject();
        response.addProperty("status", status);
        response.addProperty("ok", ok);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("testRunManifest", "run_1/city_1/test_run_manifest.json");
        response.add("artifacts", artifacts);
        return response;
    }

    private static JsonObject failedWorkflowResponse() {
        JsonObject response = response("failed", false);
        JsonObject report = new JsonObject();
        JsonArray steps = new JsonArray();
        JsonObject step = new JsonObject();
        step.addProperty("name", "city_compile_d4_blueprint");
        step.addProperty("ok", false);
        step.addProperty("reasonCode", "CITY_BLUEPRINT_REQUIRED_STRUCTURE_NO_LEGAL_PLACEMENT");
        step.addProperty("message", "All finite required building candidate combinations were exhausted.");
        step.addProperty("nextAction", "city_submit_d4_blueprint");
        steps.add(step);
        report.add("steps", steps);
        response.add("workflowReport", report);
        return response;
    }
}
