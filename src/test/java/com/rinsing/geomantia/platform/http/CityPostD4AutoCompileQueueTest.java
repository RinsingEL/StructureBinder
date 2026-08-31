package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

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
}
