package com.rinsing.geomantia.systems.city.application.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDesignQueueTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void agentWorkflowStateIsNoOpForUnmanagedRun() throws Exception {
        CityDesignQueue queue = new CityDesignQueue(
                temporaryDirectory.resolve("debug"),
                temporaryDirectory.resolve("config.json")
        );

        queue.onAgentWorkflowState(
                "standalone-run",
                "standalone-city",
                "waiting_for_patch_review",
                "D4_REQUIRES_TOP_PATCH_REVIEW",
                "session-1"
        );
    }

    @Test
    void sortsAllCitiesByDistanceFromOriginAndAdvancesAfterPostD4() throws Exception {
        CityDesignQueue queue = queue();
        writeRegistry("run_global",
                seed("city_far", "realm_a", "town", 6000, 0),
                seed("city_near", "realm_b", "capital", 3500, 0),
                seed("city_mid", "realm_a", "capital", 5000, 0));

        JsonObject created = queue.refresh("run_global", "global_radial");
        assertEquals("city_near", created.get("currentCitySeedId").getAsString());
        assertEquals("waiting_for_agent", created.get("status").getAsString());
        assertEquals("city_mid", created.getAsJsonArray("items").get(1).getAsJsonObject()
                .get("citySeedId").getAsString());
        assertThrows(IllegalArgumentException.class,
                () -> queue.requireCurrentIfManaged("run_global", "city_far"));

        queue.onPostD4State(postState("run_global", "city_near", "running"));
        assertEquals("post_d4_running", queue.status("run_global").get("status").getAsString());
        queue.onPostD4State(postState("run_global", "city_near", "waiting_for_generation"));
        JsonObject advanced = queue.status("run_global");
        assertEquals("city_mid", advanced.get("currentCitySeedId").getAsString());
        assertEquals(1, advanced.get("completedCount").getAsInt());
    }

    @Test
    void groupsRealmsByCapitalDistanceThenCitiesByCapitalDistance() throws Exception {
        CityDesignQueue queue = queue();
        writeRegistry("run_grouped",
                seed("a_town", "realm_a", "town", 5100, 0),
                seed("b_far", "realm_b", "town", 4200, 0),
                seed("a_capital", "realm_a", "capital", 5000, 0),
                seed("b_capital", "realm_b", "capital", 3500, 0));

        JsonArray items = queue.refresh("run_grouped", "realm_grouped").getAsJsonArray("items");
        assertEquals("b_capital", id(items, 0));
        assertEquals("b_far", id(items, 1));
        assertEquals("a_capital", id(items, 2));
        assertEquals("a_town", id(items, 3));
    }

    @Test
    void needsAgentBlocksLaterCities() throws Exception {
        CityDesignQueue queue = queue();
        writeRegistry("run_failure",
                seed("city_1", "realm_a", "capital", 4000, 0),
                seed("city_2", "realm_a", "town", 5000, 0));
        queue.refresh("run_failure", "global_radial");

        queue.onPostD4State(postState("run_failure", "city_1", "needs_agent"));
        JsonObject state = queue.status("run_failure");
        assertEquals("needs_agent", state.get("status").getAsString());
        assertEquals("city_post_d4_auto_compile_retry", state.get("nextAction").getAsString());
        assertEquals("city_1", state.get("currentCitySeedId").getAsString());
        assertEquals("pending", state.getAsJsonArray("items").get(1).getAsJsonObject()
                .get("status").getAsString());
    }

    @Test
    void exposesMandatoryPatchReviewBetweenD3AndBlueprintPreparation() throws Exception {
        CityDesignQueue queue = queue();
        writeRegistry("run_review", seed("city_1", "realm_a", "capital", 4000, 0));
        queue.refresh("run_review", "global_radial");

        queue.onAgentWorkflowState("run_review", "city_1", "waiting_for_patch_review",
                "D4_REQUIRES_TOP_PATCH_REVIEW", "pex_review");
        JsonObject pending = queue.status("run_review");
        assertEquals("waiting_for_patch_review", pending.get("status").getAsString());
        assertEquals("patch_explorer_show_candidates", pending.get("nextAction").getAsString());
        assertEquals("pex_review", pending.getAsJsonObject("currentCity")
                .get("patchExplorerSessionId").getAsString());

        queue.onAgentWorkflowState("run_review", "city_1", "waiting_for_agent",
                "PATCH_REVIEW_COMPLETED", "");
        assertEquals("city_prepare_d4_blueprint_context",
                queue.status("run_review").get("nextAction").getAsString());
        queue.onAgentWorkflowState("run_review", "city_1", "waiting_for_agent",
                "D4_CONTEXT_PREPARED", "");
        assertEquals("city_submit_d4_blueprint",
                queue.status("run_review").get("nextAction").getAsString());
    }

    @Test
    void waitsUntilEveryRealmHasCapitalBeforeAutomaticStart() throws Exception {
        CityDesignQueue queue = queue();
        writeRegistry("run_realms", seed("a_capital", "realm_a", "capital", 4000, 0));
        JsonArray profiles = new JsonArray();
        profiles.add(profile("realm_a"));
        profiles.add(profile("realm_b"));
        Files.writeString(temporaryDirectory.resolve("realm_debug/run_realms/realm_profiles.json"),
                profiles.toString());

        assertFalse(queue.registryCoversAllRealms("run_realms"));
        writeRegistry("run_realms",
                seed("a_capital", "realm_a", "capital", 4000, 0),
                seed("b_capital", "realm_b", "capital", 5000, 0));
        assertTrue(queue.registryCoversAllRealms("run_realms"));
    }

    private CityDesignQueue queue() {
        return new CityDesignQueue(temporaryDirectory.resolve("realm_debug"),
                temporaryDirectory.resolve("config/geomantia/city_design_queue.json"));
    }

    private void writeRegistry(String runId, JsonObject... seeds) throws Exception {
        Path run = temporaryDirectory.resolve("realm_debug").resolve(runId);
        Files.createDirectories(run);
        JsonArray values = new JsonArray();
        for (JsonObject seed : seeds) values.add(seed);
        JsonObject registry = new JsonObject();
        registry.add("citySeeds", values);
        Files.writeString(run.resolve("city_seed_registry.json"), registry.toString());
    }

    private static JsonObject seed(String id, String realm, String role, int x, int z) {
        JsonObject seed = new JsonObject();
        seed.addProperty("citySeedId", id);
        seed.addProperty("realmId", realm);
        seed.addProperty("role", role);
        JsonObject anchor = new JsonObject();
        anchor.addProperty("x", x);
        anchor.addProperty("z", z);
        seed.add("anchorBlock", anchor);
        return seed;
    }

    private static JsonObject profile(String realmId) {
        JsonObject profile = new JsonObject();
        profile.addProperty("realmId", realmId);
        return profile;
    }

    private static JsonObject postState(String runId, String citySeedId, String status) {
        JsonObject state = new JsonObject();
        state.addProperty("runId", runId);
        state.addProperty("citySeedId", citySeedId);
        state.addProperty("status", status);
        return state;
    }

    private static String id(JsonArray items, int index) {
        return items.get(index).getAsJsonObject().get("citySeedId").getAsString();
    }
}
