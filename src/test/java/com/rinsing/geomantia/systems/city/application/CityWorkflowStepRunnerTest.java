package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityWorkflowStepRunnerTest {
    @Test
    void recordsResponseSummaryAndFinalWorkflowStatus() throws Exception {
        JsonObject report = report();
        JsonArray steps = report.getAsJsonArray("steps");
        AtomicReference<JsonObject> persisted = new AtomicReference<>();
        CityWorkflowStepRunner runner = new CityWorkflowStepRunner(report, steps, true,
                path -> "debug/" + path.getFileName(), value -> persisted.set(value.deepCopy()));

        boolean ok = runner.runStep("city_plan", null, () -> {
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("status", "planned");
            response.addProperty("reasonCode", "CITY_PLAN_READY");
            JsonObject artifacts = new JsonObject();
            artifacts.addProperty("plan", "debug/plan.json");
            response.add("artifacts", artifacts);
            JsonObject wallPlan = new JsonObject();
            wallPlan.add("wallSegments", values(2));
            wallPlan.add("wallNodes", values(1));
            wallPlan.add("wallUnits", values(3));
            response.add("cityWallPlan", wallPlan);
            JsonObject session = new JsonObject();
            session.addProperty("selectedAnchorCount", 4);
            session.addProperty("remainingSlotCount", 2);
            response.add("d4CandidateSession", session);
            return response;
        });

        JsonObject step = steps.get(0).getAsJsonObject();
        assertTrue(ok);
        assertEquals("success", step.get("status").getAsString());
        assertEquals("planned", step.get("responseStatus").getAsString());
        assertEquals(2, step.get("wallSegmentCount").getAsInt());
        assertEquals(4, step.get("selectedAnchorCount").getAsInt());

        JsonObject response = runner.finish(System.nanoTime(), "waiting_for_confirmation");
        assertTrue(response.get("ok").getAsBoolean());
        assertEquals("waiting_for_confirmation", persisted.get().get("status").getAsString());
    }

    @Test
    void skipsExistingArtifactWithoutRunningAction() throws Exception {
        JsonObject report = report();
        JsonArray steps = report.getAsJsonArray("steps");
        Path artifact = Files.createTempFile("city-workflow", ".json");
        AtomicBoolean invoked = new AtomicBoolean(false);
        CityWorkflowStepRunner runner = new CityWorkflowStepRunner(report, steps, true,
                path -> "debug/" + path.getFileName(), value -> { });

        boolean ok = runner.runStep("city_plan_d3", artifact, () -> {
            invoked.set(true);
            return new JsonObject();
        });

        JsonObject step = steps.get(0).getAsJsonObject();
        assertTrue(ok);
        assertFalse(invoked.get());
        assertEquals("skipped", step.get("status").getAsString());
        assertEquals("WORKFLOW_EXISTING_ARTIFACT", step.get("reasonCode").getAsString());
        assertEquals("debug/" + artifact.getFileName(), step.get("artifact").getAsString());
    }

    @Test
    void convertsStepExceptionPrefixToReasonCode() throws Exception {
        JsonObject report = report();
        JsonArray steps = report.getAsJsonArray("steps");
        CityWorkflowStepRunner runner = new CityWorkflowStepRunner(report, steps, false,
                Path::toString, value -> { });

        boolean ok = runner.runStep("city_plan_d4", null, () -> {
            throw new IllegalArgumentException("D4_STAGE_FAILED: invalid plan");
        });

        JsonObject step = steps.get(0).getAsJsonObject();
        assertFalse(ok);
        assertEquals("failed", step.get("status").getAsString());
        assertEquals("D4_STAGE_FAILED", step.get("reasonCode").getAsString());
        assertEquals("D4_STAGE_FAILED: invalid plan", step.get("error").getAsString());
    }

    @Test
    void preservesBlueprintFailureBudgetAndBlackBoxRetryGuidanceInWorkflowReport() throws Exception {
        JsonObject report = report();
        JsonArray steps = report.getAsJsonArray("steps");
        CityWorkflowStepRunner runner = new CityWorkflowStepRunner(report, steps, false,
                Path::toString, value -> { });

        boolean ok = runner.runStep("city_compile_d4_blueprint", null, () -> {
            JsonObject response = new JsonObject();
            response.addProperty("ok", false);
            response.addProperty("status", "failed");
            response.addProperty("reasonCode", "D4_LAYOUT_FAILED");
            response.addProperty("message", "Choose another Patch.");
            response.addProperty("failureCount", 2);
            response.addProperty("maximumFailureCount", 5);
            response.addProperty("remainingFailureCount", 3);
            response.addProperty("retryAllowed", true);
            response.addProperty("nextAction", "city_submit_d4_blueprint");
            JsonObject policy = new JsonObject();
            policy.addProperty("sourceCodeInspectionAllowed", false);
            response.add("agentRecoveryPolicy", policy);
            return response;
        });

        JsonObject step = steps.get(0).getAsJsonObject();
        assertFalse(ok);
        assertEquals(2, step.get("failureCount").getAsInt());
        assertEquals(3, step.get("remainingFailureCount").getAsInt());
        assertTrue(step.get("retryAllowed").getAsBoolean());
        assertEquals("city_submit_d4_blueprint", step.get("nextAction").getAsString());
        assertFalse(step.getAsJsonObject("agentRecoveryPolicy")
                .get("sourceCodeInspectionAllowed").getAsBoolean());
    }

    private static JsonObject report() {
        JsonObject report = new JsonObject();
        report.add("steps", new JsonArray());
        report.add("artifacts", new JsonObject());
        return report;
    }

    private static JsonArray values(int count) {
        JsonArray values = new JsonArray();
        for (int i = 0; i < count; i++) {
            values.add(i);
        }
        return values;
    }
}
