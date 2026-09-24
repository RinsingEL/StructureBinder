package com.rinsing.geomantia.harness.systems.provider.application;
import com.rinsing.geomantia.systems.provider.application.*;

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
    @TempDir Path temporaryDirectory;

    @Test void blockedStatusShowsTheCurrentCityStepAndActualError() {
        JsonObject queue = com.google.gson.JsonParser.parseString("""
                {"currentCitySeedId":"b","items":[
                  {"citySeedId":"a","reasonCode":"unrelated"},
                  {"citySeedId":"b","failedStep":"city_execute_d5","reasonCode":"CITY_CHUNKS_ALREADY_GENERATED",
                   "message":"172 chunks reached FEATURES"}]}
                """).getAsJsonObject();
        assertEquals("PLANNING_HOST_BLOCKED: city_execute_d5 · CITY_CHUNKS_ALREADY_GENERATED · 172 chunks reached FEATURES",
                PlayerProviderAgentRunner.programBlockMessage(queue));
        assertEquals("PLANNING_HOST_BLOCKED: POST_D4_PROGRAM_FAILURE", PlayerProviderAgentRunner.programBlockMessage(new JsonObject()));
    }
    @Test
    void mechanicalStepsNeedNoModelButSiteReviewRemainsADecision() {
        for (String action : List.of("city_plan_d3", "patch_explorer_show_candidates", "city_review_d3_site", "city_submit_d4_blueprint")) {
            var step = new ProviderPlanningDiscovery.PlanningStep(ProviderPlanningDiscovery.Stage.CITY,
                    "run", "realm", "city", action, new JsonObject(), temporaryDirectory, List.of(), action);
            assertEquals(List.of("city_plan_d3", "patch_explorer_show_candidates").contains(action), PlayerProviderAgentRunner.hostOnly(step));
        }
        assertFalse(PlayerProviderAgentRunner.toolsFor(ProviderPlanningDiscovery.Stage.T2).contains("patch_explorer_open"));
        assertFalse(PlayerProviderAgentRunner.toolsFor(ProviderPlanningDiscovery.Stage.T4).contains("realm_t4_patch_planning_create"));
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
