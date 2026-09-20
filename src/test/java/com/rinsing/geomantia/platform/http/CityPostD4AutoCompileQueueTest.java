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
    @Test void localFailuresFinishWithVisiblePartialOutcomeWithoutRetrying() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (r,c) -> { calls.incrementAndGet(); return response("completed_with_errors", true); }, ignored -> {}, 10L)) {
            queue.enqueue("run_partial", "city_partial");
            var state = awaitStatus(queue, "run_partial", "city_partial", "completed_with_errors");
            assertEquals("CITY_GENERATION_PARTIAL_FAILURE", state.get("reasonCode").getAsString());
            assertTrue(state.has("workflowResponse"));
            Thread.sleep(60);
            assertEquals(1, calls.get());
        }
    }

    @Test void waitingGenerationContinuesAutomaticallyAndStopsAfterCompletion() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var queue = new CityPostD4AutoCompileQueue(temporaryDirectory, (r, c) -> {
            int call = calls.incrementAndGet();
            if (call == 1) return response("waiting_for_worldgen", true);
            entered.countDown();
            assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return response("completed", true);
        }, state -> {}, 10L)) {
            queue.enqueue("run_auto", "city_auto");
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("waiting_for_generation", queue.status("run_auto", "city_auto").get("status").getAsString());
            queue.enqueue("run_auto", "city_auto"); // Must not duplicate an active continuation.
            release.countDown();
            var state = awaitStatus(queue, "run_auto", "city_auto", "completed");
            assertEquals("CITY_GENERATION_COMPLETED", state.get("reasonCode").getAsString());
            assertEquals(1, state.get("attempt").getAsInt());
            Thread.sleep(60);
            assertEquals(2, calls.get());
        } finally { release.countDown(); }
    }

    @Test void resumesLegacyWaitingJobAfterRestartButNeverRepeatsCompletedWalls() throws Exception {
        Path file = temporaryDirectory.resolve("run_resume/automation/post_d4/city_resume.json");
        Files.createDirectories(file.getParent());
        JsonObject state = new JsonObject();
        state.addProperty("runId", "run_resume"); state.addProperty("citySeedId", "city_resume");
        state.addProperty("status", "waiting_for_generation"); state.addProperty("attempt", 4);
        Files.writeString(file, state.toString());
        try (var queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (r, c) -> response("completed", true), ignored -> {}, 10L)) {
            assertEquals(5, awaitStatus(queue, "run_resume", "city_resume", "completed").get("attempt").getAsInt());
        }
        try (var queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (r, c) -> { throw new AssertionError("completed walls must not run again"); })) {
            assertEquals("completed", queue.status("run_resume", "city_resume").get("status").getAsString());
        }
    }

    @Test void wallFailureAfterWaitingRemainsBlockedWithoutAutomaticRetries() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        JsonObject failure = failedWorkflowResponse();
        var step = failure.getAsJsonObject("workflowReport").getAsJsonArray("steps").get(0).getAsJsonObject();
        step.addProperty("name", "city_execute_city_walls");
        step.addProperty("reasonCode", "WALL_TERRAIN_REQUIRES_REDESIGN");
        step.addProperty("nextAction", "city_post_d4_auto_compile_retry");
        try (var queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (r, c) -> calls.incrementAndGet() == 1 ? response("waiting_for_worldgen", true) : failure,
                ignored -> {}, 10L)) {
            queue.enqueue("run_wall_failure", "city_wall_failure");
            var state = awaitStatus(queue, "run_wall_failure", "city_wall_failure", "blocked_by_program");
            assertEquals("city_execute_city_walls", state.get("failedStep").getAsString());
            assertEquals("WALL_TERRAIN_REQUIRES_REDESIGN", state.get("reasonCode").getAsString());
            Thread.sleep(60);
            assertEquals(2, calls.get());
        }
    }

    @Test void publishesConcreteProgramFailureToStatusListenerAndRestart() throws Exception {
        JsonObject failure = failedWorkflowResponse();
        JsonObject step = failure.getAsJsonObject("workflowReport").getAsJsonArray("steps").get(0).getAsJsonObject();
        step.addProperty("name", "city_execute_d5");
        step.addProperty("reasonCode", "CITY_CHUNKS_ALREADY_GENERATED");
        step.addProperty("message", "172 of 247 chunks reached FEATURES; activation refused.");
        step.addProperty("nextAction", "city_post_d4_auto_compile_retry");
        AtomicReference<JsonObject> notified = new AtomicReference<>();
        try (var queue = new CityPostD4AutoCompileQueue(temporaryDirectory, (r, c) -> failure, notified::set)) {
            queue.enqueue("run_detail", "city_detail");
            var state = awaitStatus(queue, "run_detail", "city_detail", "blocked_by_program");
            assertEquals("CITY_CHUNKS_ALREADY_GENERATED", state.get("reasonCode").getAsString());
            assertEquals("POST_D4_WORKFLOW_UNEXPECTED_STATUS", state.get("queueReasonCode").getAsString());
            assertEquals("city_execute_d5", state.get("failedStep").getAsString());
            assertEquals(step.get("message"), state.get("message"));
            assertEquals("city_post_d4_auto_compile_retry", state.get("nextAction").getAsString());
        }
        try (var queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (r, c) -> { throw new AssertionError("must not retry"); }, notified::set)) {
            assertEquals("CITY_CHUNKS_ALREADY_GENERATED", notified.get().get("reasonCode").getAsString());
            assertEquals(step.get("message"), queue.status("run_detail", "city_detail").get("message"));
        }
    }

    @Test void legacyPersistedBlockGetsDetailsWithoutChangingItsRecoveryAction() throws Exception {
        Path file = temporaryDirectory.resolve("run_legacy/automation/post_d4/city_legacy.json");
        Files.createDirectories(file.getParent());
        JsonObject state = new JsonObject();
        state.addProperty("runId", "run_legacy"); state.addProperty("citySeedId", "city_legacy");
        state.addProperty("status", "blocked_by_program"); state.addProperty("reasonCode", "POST_D4_WORKFLOW_UNEXPECTED_STATUS");
        state.addProperty("nextAction", "city_post_d4_auto_compile_retry");
        JsonObject response = response("failed", false);
        response.addProperty("reasonCode", "STORAGE_UNAVAILABLE"); response.addProperty("error", "Disk unavailable");
        state.add("workflowResponse", response); Files.writeString(file, state.toString());
        try (var queue = new CityPostD4AutoCompileQueue(temporaryDirectory, (r, c) -> { throw new AssertionError(); })) {
            var restored = queue.status("run_legacy", "city_legacy");
            assertEquals("STORAGE_UNAVAILABLE", restored.get("reasonCode").getAsString());
            assertEquals("Disk unavailable", restored.get("error").getAsString());
            assertEquals("city_post_d4_auto_compile_retry", restored.get("nextAction").getAsString());
        }
    }
    @Test
    void refreshedContextSupersedesOldBlockAcrossRestartWithoutRunningCompiler() throws Exception {
        Path path = temporaryDirectory.resolve("run_refresh/automation/post_d4/city_refresh.json");
        Files.createDirectories(path.getParent());
        JsonObject old = new JsonObject();
        old.addProperty("runId", "run_refresh"); old.addProperty("citySeedId", "city_refresh");
        old.addProperty("status", "blocked_by_program"); old.addProperty("attempt", 2);
        old.addProperty("nextAction", "city_post_d4_auto_compile_retry");
        Files.writeString(path, old.toString());
        try (var queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (r, c) -> { throw new AssertionError("must wait for a new accepted design"); })) {
            assertThrows(java.io.IOException.class, () -> queue.prepareContext("run_refresh", "city_refresh",
                    recovery -> { assertTrue(recovery); throw new java.io.IOException("preflight rejected"); }));
            assertEquals("blocked_by_program", queue.status("run_refresh", "city_refresh").get("status").getAsString());
            queue.prepareContext("run_refresh", "city_refresh", recovery -> {
                assertTrue(recovery);
                JsonObject response = new JsonObject(); response.addProperty("ok", true);
                response.addProperty("contextId", "new-context"); return response;
            });
            var state = queue.status("run_refresh", "city_refresh");
            assertEquals("needs_agent", state.get("status").getAsString());
            assertEquals("city_submit_d4_blueprint", state.get("nextAction").getAsString());
            assertEquals(old, state.getAsJsonObject("previousProgramFailure"));
            assertEquals(2, state.get("attempt").getAsInt());
            queue.prepareContext("run_refresh", "city_refresh", recovery -> {
                assertTrue(!recovery); return new JsonObject();
            });
        }
        try (var queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (r, c) -> { throw new AssertionError("must not compile on restart"); })) {
            assertEquals("needs_agent", queue.status("run_refresh", "city_refresh").get("status").getAsString());
        }
    }

    @Test
    void contradictorySuccessStatusCannotCompleteTheCity() throws Exception {
        try (CityPostD4AutoCompileQueue queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (run, city) -> response("waiting_for_generation", false))) {
            queue.enqueue("run_conflict", "city_conflict");
            assertEquals("blocked_by_program", awaitStatus(queue, "run_conflict", "city_conflict", "blocked_by_program")
                    .get("status").getAsString());
        }
    }
    @Test
    void restartReclassifiesOldProgramFailureWithoutCompilingOrResettingBudget() throws Exception {
        Path path = temporaryDirectory.resolve("run_old/automation/post_d4/city_old.json");
        Files.createDirectories(path.getParent());
        JsonObject state = new JsonObject();
        state.addProperty("runId", "run_old"); state.addProperty("citySeedId", "city_old");
        state.addProperty("status", "needs_agent"); state.addProperty("failureCount", 2);
        JsonObject response = failedWorkflowResponse();
        response.getAsJsonObject("workflowReport").getAsJsonArray("steps").get(0).getAsJsonObject()
                .addProperty("reasonCode", "CITY_BLUEPRINT_COMPILED_ANCHOR_FINALIZATION_FAILED");
        state.add("workflowResponse", response);
        Files.writeString(path, state.toString());
        try (CityPostD4AutoCompileQueue queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (run, city) -> { throw new AssertionError("must not compile on restart"); })) {
            JsonObject recovered = queue.status("run_old", "city_old");
            assertEquals("blocked_by_program", recovered.get("status").getAsString());
            assertEquals(2, recovered.get("failureCount").getAsInt());
            assertEquals("city_post_d4_auto_compile_retry", recovered.get("nextAction").getAsString());
        }
    }
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
    void programExceptionDoesNotAskDesignerToRewrite() throws Exception {
        try (CityPostD4AutoCompileQueue queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (runId, citySeedId) -> {
                    throw new IllegalStateException("broken stage");
                })) {
            queue.enqueue("run_2", "city_2");

            JsonObject failed = awaitStatus(queue, "run_2", "city_2", "blocked_by_program");
            assertEquals("program", failed.get("failureOwner").getAsString());
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

    @Test
    void shutdownCancellationWaitsForRestartAndThenResumes() throws Exception {
        try (CityPostD4AutoCompileQueue queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (runId, citySeedId) -> {
                    throw new java.util.concurrent.CancellationException("CITY_SERVER_STOPPING");
                })) {
            queue.enqueue("shutdown", "city");
            awaitRestart(queue, "shutdown", "city");
        }
        try (CityPostD4AutoCompileQueue queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (runId, citySeedId) -> response("completed", true))) {
            assertEquals(2, awaitStatus(queue, "shutdown", "city", "completed").get("attempt").getAsInt());
        }
    }

    @Test
    void shutdownReportedByWorkflowAlsoWaitsForRestart() throws Exception {
        JsonObject failure = failedWorkflowResponse();
        failure.getAsJsonObject("workflowReport").getAsJsonArray("steps").get(0).getAsJsonObject()
                .addProperty("reasonCode", "CITY_SERVER_STOPPING");
        try (CityPostD4AutoCompileQueue queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (runId, citySeedId) -> failure)) {
            queue.enqueue("shutdown", "city");
            awaitRestart(queue, "shutdown", "city");
        }
    }

    @Test
    void restartRecoversLegacyShutdownBlock() throws Exception {
        Path path = temporaryDirectory.resolve("shutdown/automation/post_d4/city.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                {"runId":"shutdown","citySeedId":"city","status":"blocked_by_program",
                 "reasonCode":"POST_D4_WORKFLOW_FAILED","error":"CITY_SERVER_STOPPING","attempt":2}
                """);
        try (CityPostD4AutoCompileQueue queue = new CityPostD4AutoCompileQueue(temporaryDirectory,
                (runId, citySeedId) -> response("completed", true))) {
            assertEquals(3, awaitStatus(queue, "shutdown", "city", "completed").get("attempt").getAsInt());
        }
    }

    private static void awaitRestart(CityPostD4AutoCompileQueue queue, String run, String city) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            JsonObject state = queue.status(run, city);
            if ("WAITING_FOR_SERVER_RESTART".equals(state.get("reasonCode").getAsString())) {
                assertEquals("queued", state.get("status").getAsString());
                assertEquals(1, state.get("attempt").getAsInt());
                return;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Shutdown interruption was not queued for restart");
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
