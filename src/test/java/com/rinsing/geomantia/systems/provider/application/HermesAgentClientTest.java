package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesAgentClientTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void profileExposesOnlyCurrentStageMcpToolsAndUsesMinecraftApi() {
        HermesAgentClient client = new HermesAgentClient();
        client.start(temporaryDirectory.resolve("server"), temporaryDirectory.resolve("world/realm_debug"), 5123);
        PlayerProviderConfig config = new PlayerProviderConfig(PlayerProviderConfig.CUSTOM, true,
                "http://127.0.0.1:9000/v1", "glm-test", PlayerProviderConfig.CHAT_COMPLETIONS, 30,
                PlayerProviderConfig.HERMES);

        String yaml = client.profileConfig(config, temporaryDirectory.resolve("geomantia.mjs"),
                List.of("city_prepare_d4_blueprint_context", "city_submit_d4_blueprint"));

        assertTrue(yaml.contains("toolsets:\n  - geomantia\n"));
        assertTrue(yaml.contains("platform_toolsets:\n  api_server:\n    - geomantia\n"));
        assertFalse(yaml.contains("- mcp-geomantia"));
        assertTrue(yaml.contains("api_key: ${GEOMANTIA_PROVIDER_API_KEY}"));
        assertTrue(yaml.contains("http://127.0.0.1:5123"));
        assertTrue(yaml.contains("city_submit_d4_blueprint"));
        assertFalse(yaml.contains("city_post_d4_auto_compile_status"));
        assertTrue(yaml.contains("max_turns: 24"));
        assertTrue(yaml.contains("- terminal"));
    }

    @Test
    void sessionIdentityIsStableWithinWorldAndSeparatedAcrossWorlds() {
        ProviderPlanningDiscovery.PlanningStep step = new ProviderPlanningDiscovery.PlanningStep(
                ProviderPlanningDiscovery.Stage.CITY, "run_1", "realm_1", "city_1", "next",
                new JsonObject(), temporaryDirectory, List.of(), "identity");

        String first = PlayerProviderAgentRunner.sessionId(temporaryDirectory.resolve("a"), step);
        String repeated = PlayerProviderAgentRunner.sessionId(temporaryDirectory.resolve("a"), step);
        String otherWorld = PlayerProviderAgentRunner.sessionId(temporaryDirectory.resolve("b"), step);

        assertEquals(first, repeated);
        assertNotEquals(first, otherWorld);
        assertTrue(first.endsWith("-run_1-city_1"));
    }

    @Test
    void cityToolsDoNotExposeBackgroundCompilePolling() {
        List<String> tools = PlayerProviderAgentRunner.toolsFor(ProviderPlanningDiscovery.Stage.CITY);

        assertTrue(tools.contains("city_submit_d4_blueprint"));
        assertFalse(tools.contains("city_post_d4_auto_compile_status"));
    }
}
