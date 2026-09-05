package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProviderAgentRunnerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hostPreparesCanonicalContextAndActualImagesBeforeWakingDesigner() throws Exception {
        Path image = temporaryDirectory.resolve("map.png");
        java.nio.file.Files.write(image, new byte[]{1, 2, 3});
        JsonObject context = new JsonObject(); context.addProperty("contextId", "frozen");
        JsonObject d3 = new JsonObject(); d3.addProperty("reviewMapImage", "map.png");
        context.add("d3ReviewPackage", d3);
        JsonObject output = new JsonObject(); output.addProperty("ok", true); output.add("cityBlueprintContext", context);
        output.addProperty("remainingFailureCount", 2);
        JsonObject queue = new JsonObject(); queue.addProperty("remainingFailureCount", 3);
        List<String> calls = new ArrayList<>();
        var input = PreparedCityDesignTurn.prepare(queue, (tool, args) -> { calls.add(tool); return output; }, temporaryDirectory);
        assertEquals(List.of("city_prepare_d4_blueprint_context"), calls);
        assertEquals("frozen", input.state().getAsJsonObject("preparedBlueprintContext").get("contextId").getAsString());
        assertEquals(2, input.state().get("remainingFailureCount").getAsInt());
        assertEquals(List.of(image), input.images());
        assertFalse(queue.has("preparedBlueprintContext"));
        assertFalse(PreparedCityDesignTurn.TOOLS.contains("city_design_queue_status"));
        assertFalse(PreparedCityDesignTurn.TOOLS.contains("city_prepare_d4_blueprint_context"));
        assertTrue(PreparedCityDesignTurn.TOOLS.contains("city_submit_d4_blueprint"));
    }

    @Test
    void failedPreparationDoesNotWakeDesignerWithMissingAuthorData() {
        JsonObject output = new JsonObject(); output.addProperty("ok", false);
        output.addProperty("error", "PLANNING_PRESENTATION_TOO_LARGE");
        var error = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> PreparedCityDesignTurn.prepare(new JsonObject(), (tool, args) -> output, temporaryDirectory));
        assertEquals("PLANNING_PRESENTATION_TOO_LARGE", error.getMessage());
    }

    @Test
    void authorConfigurationFailureStopsImmediatelyWithoutClaimingThreeAttempts() {
        List<AgentActivityEvent> activity = new ArrayList<>();
        PlayerProviderAgentRunner runner = new PlayerProviderAgentRunner(
                new ProviderConfigStore(temporaryDirectory), new DeepSeekToolLoopClient(), ignored -> { }, activity::add);
        var step = new ProviderPlanningDiscovery.PlanningStep(ProviderPlanningDiscovery.Stage.CITY,
                "run_1", "realm_1", "city_1", "city_prepare_d4_blueprint_context", new JsonObject(),
                temporaryDirectory, List.of(), "city:run_1:city_1");
        runner.recordFailedTurn(step, "PLANNING_HOST_BLOCKED: PLANNING_AUTHOR_ANNOTATION_REQUIRED");
        assertEquals("error", runner.status().state());
        assertTrue(activity.get(0).message().contains("已停止"));
        assertFalse(activity.get(0).message().contains("连续失败"));
    }

    @Test
    void countsProviderRequestFailuresAndStopsAfterThirdAttempt() {
        List<AgentActivityEvent> activity = new ArrayList<>();
        PlayerProviderAgentRunner runner = new PlayerProviderAgentRunner(
                new ProviderConfigStore(temporaryDirectory), new DeepSeekToolLoopClient(), ignored -> { }, activity::add);
        ProviderPlanningDiscovery.PlanningStep step = new ProviderPlanningDiscovery.PlanningStep(
                ProviderPlanningDiscovery.Stage.CITY, "run_1", "realm_1", "city_1",
                "city_submit_d4_blueprint", new JsonObject(), temporaryDirectory, List.of(), "city:run_1:city_1");

        runner.recordFailedTurn(step, "PROVIDER_AGENT_REQUEST_FAILED");
        assertEquals("waiting", runner.status().state());
        assertTrue(activity.get(0).message().contains("1/3"));

        runner.recordFailedTurn(step, "PROVIDER_AGENT_REQUEST_FAILED");
        assertEquals("waiting", runner.status().state());
        assertTrue(activity.get(1).message().contains("2/3"));

        runner.recordFailedTurn(step, "PROVIDER_AGENT_REQUEST_FAILED");
        assertEquals("error", runner.status().state());
        assertTrue(activity.get(2).message().contains("3/3"));
        assertTrue(activity.get(2).message().contains("已停止"));
    }
}
