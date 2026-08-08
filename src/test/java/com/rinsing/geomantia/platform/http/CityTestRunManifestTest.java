package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityTestRunLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityTestRunManifestTest {
    @TempDir
    Path tempDir;

    @Test
    void workflowAttemptsAppendWithoutLosingRestoreContext() throws Exception {
        Path runDir = Files.createDirectories(tempDir.resolve("realm_run"));
        Files.writeString(runDir.resolve("world_survey_manifest.json"), """
                {"config":{"dimensionId":"minecraft:overworld","worldSeed":42}}
                """);
        CityTestRunLayout layout = CityTestRunLayout.open(runDir, "capital");
        JsonObject seed = JsonParser.parseString("""
                {"citySeedId":"capital","center":{"x":128,"z":-256}}
                """).getAsJsonObject();

        JsonObject firstRequest = JsonParser.parseString("""
                {"skipExisting":true,"confirmWorldMutation":false}
                """).getAsJsonObject();
        JsonObject firstManifest = CityPlanningEndpointHandler.loadOrCreateTestRunManifest(
                layout, runDir, "realm_run", "capital", seed, firstRequest, tempDir);
        JsonObject firstAttempt = attempt(0, "waiting_for_confirmation");
        firstManifest.getAsJsonArray("attempts").add(firstAttempt);
        CityPlanningEndpointHandler.writeTestRunState(layout.manifestPath(), firstManifest, firstAttempt);

        JsonObject secondRequest = JsonParser.parseString("""
                {"skipExisting":true,"confirmWorldMutation":true}
                """).getAsJsonObject();
        JsonObject secondManifest = CityPlanningEndpointHandler.loadOrCreateTestRunManifest(
                layout, runDir, "realm_run", "capital", seed, secondRequest, tempDir);
        JsonObject secondAttempt = attempt(1, "waiting_for_worldgen");
        secondManifest.getAsJsonArray("attempts").add(secondAttempt);
        CityPlanningEndpointHandler.writeTestRunState(layout.manifestPath(), secondManifest, secondAttempt);

        JsonObject stored = JsonParser.parseString(Files.readString(layout.manifestPath())).getAsJsonObject();
        assertEquals("city_test_run_manifest.v0.1", stored.get("schemaVersion").getAsString());
        assertEquals("realm_run::capital", stored.get("testRunId").getAsString());
        assertEquals("minecraft:overworld",
                stored.getAsJsonObject("worldIdentity").get("dimensionId").getAsString());
        assertEquals(-256, stored.getAsJsonObject("citySeedSnapshot")
                .getAsJsonObject("center").get("z").getAsInt());
        assertTrue(stored.getAsJsonObject("latestRequest").get("confirmWorldMutation").getAsBoolean());
        assertEquals(2, stored.getAsJsonArray("attempts").size());
        assertEquals("waiting_for_confirmation", stored.getAsJsonArray("attempts").get(0)
                .getAsJsonObject().get("status").getAsString());
        assertEquals("waiting_for_worldgen", stored.get("status").getAsString());
        assertEquals("city_run_workflow", stored.get("nextAction").getAsString());
        assertFalse(Files.exists(layout.manifestPath().resolveSibling("test_run_manifest.json.tmp")));
    }

    private static JsonObject attempt(int index, String status) {
        JsonObject attempt = new JsonObject();
        attempt.addProperty("schemaVersion", "city_workflow_attempt.v0.1");
        attempt.addProperty("attemptIndex", index);
        attempt.addProperty("status", status);
        attempt.add("steps", new JsonArray());
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("testRunManifest", "realm_run/city_test_runs/capital/test_run_manifest.json");
        attempt.add("artifacts", artifacts);
        return attempt;
    }
}
