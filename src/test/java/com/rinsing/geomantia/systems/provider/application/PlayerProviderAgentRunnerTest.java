package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProviderAgentRunnerTest {
    @TempDir
    Path temporaryDirectory;

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
