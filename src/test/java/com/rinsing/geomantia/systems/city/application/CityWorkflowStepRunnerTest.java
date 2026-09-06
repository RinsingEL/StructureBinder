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
    @Test void finalHardBlocksTakePrecedenceOverOptionalFillSearchFailures() {
        JsonObject response = com.google.gson.JsonParser.parseString("""
            {"ok":false,"failureOwner":"program","reasonCode":"FINAL_FAILED",
            "cityGenerationCompileTrace":{"compilationAcceptance":{"hardBlocks":["actual final failure"]},
            "selections":[{"status":"no_legal_candidate","phase":"fill","structureRef":"unrelated"}]}}
            """).getAsJsonObject();
        JsonObject summary = CityWorkflowStepRunner.compactFailureSummary(response);
        assertEquals("final_acceptance", summary.get("phase").getAsString());
        assertEquals("actual final failure", summary.getAsJsonArray("hardBlocks").get(0).getAsString());
        assertFalse(summary.has("structureRef"));
    }
    @Test
    void waitingForGenerationIsSuccessfulProgramCompletion() throws Exception {
        JsonObject report = report();
        CityWorkflowStepRunner runner = new CityWorkflowStepRunner(report, report.getAsJsonArray("steps"), true,
                Path::toString, ignored -> { });
        assertTrue(runner.finish(System.nanoTime(), "waiting_for_generation").get("ok").getAsBoolean());
    }
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

    @Test
    void exposesCompactActionableD4FailureEvidenceWithoutCopyingTheFullTrace() throws Exception {
        JsonObject report = report();
        JsonArray steps = report.getAsJsonArray("steps");
        CityWorkflowStepRunner runner = new CityWorkflowStepRunner(report, steps, false,
                Path::toString, value -> { });

        boolean ok = runner.runStep("city_compile_d4_blueprint", null, () -> {
            JsonObject response = new JsonObject();
            response.addProperty("ok", false);
            response.addProperty("reasonCode", "CITY_BLUEPRINT_REQUIRED_STRUCTURE_NO_LEGAL_PLACEMENT");
            response.addProperty("message", "All finite required building candidate combinations were exhausted.");
            JsonObject trace = new JsonObject();
            JsonArray selections = new JsonArray();
            JsonObject selection = new JsonObject();
            selection.addProperty("sequence", 34);
            selection.addProperty("phase", "required");
            selection.addProperty("groupId", "civic_core");
            selection.addProperty("structureRef", "geomantia:city/trek/landmark/plains_fountain_01");
            selection.addProperty("candidateCount", 0);
            selection.addProperty("status", "no_legal_candidate");
            JsonArray attempts = new JsonArray();
            JsonObject attempt = new JsonObject();
            JsonObject filters = new JsonObject();
            filters.addProperty("INTERNAL_FRONTAGE_UNAVAILABLE", 1);
            attempt.add("filterReasonCounts", filters);
            JsonArray hardBlocks = new JsonArray();
            hardBlocks.add("D4_ARRAY_LAYOUT_FRONTAGE_ENTRANCE_AMBIGUOUS: fountain requires frontageEntranceId.");
            attempt.add("hardBlocks", hardBlocks);
            attempts.add(attempt);
            selection.add("attempts", attempts);
            selections.add(selection);
            trace.add("selections", selections);
            response.add("cityGenerationCompileTrace", trace);
            return response;
        });

        assertFalse(ok);
        JsonObject step = steps.get(0).getAsJsonObject();
        JsonObject summary = step.getAsJsonObject("failureSummary");
        assertEquals("civic_core", summary.get("groupId").getAsString());
        assertEquals("geomantia:city/trek/landmark/plains_fountain_01",
                summary.get("structureRef").getAsString());
        assertEquals(1, summary.getAsJsonObject("filterReasonCounts")
                .get("INTERNAL_FRONTAGE_UNAVAILABLE").getAsInt());
        assertEquals("AUTHOR_FRONTAGE_REQUIRED",
                summary.get("recommendedActionCode").getAsString());
        assertFalse(step.has("cityGenerationCompileTrace"));
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
