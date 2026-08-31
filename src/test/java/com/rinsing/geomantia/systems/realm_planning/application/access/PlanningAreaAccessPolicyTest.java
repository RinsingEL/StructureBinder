package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningAreaAccessPolicyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void allowsInitialAreaAndRejectsUnplannedOuterArea() {
        PlanningAreaAccessPolicy policy = policy();

        assertEquals("INITIAL_ACTIVITY_AREA",
                policy.evaluate("minecraft:overworld", 2048, 0).reasonCode());
        assertFalse(policy.evaluate("minecraft:overworld", 2049, 0).allowed());
        assertTrue(policy.evaluate("minecraft:the_nether", 50000, 50000).allowed());
    }

    @Test
    void allowsOnlyReleasedCityRadiusOutsideInitialArea() throws Exception {
        Path run = temporaryDirectory.resolve("realm_debug").resolve("run_1");
        Files.createDirectories(run);

        JsonObject manifest = new JsonObject();
        JsonObject config = new JsonObject();
        config.addProperty("dimensionId", "minecraft:overworld");
        config.addProperty("cellStepBlocks", 128);
        manifest.add("config", config);
        write(run.resolve("world_survey_manifest.json"), manifest);

        JsonObject seed = new JsonObject();
        seed.addProperty("citySeedId", "city_1");
        JsonObject anchor = new JsonObject();
        anchor.addProperty("x", 4096);
        anchor.addProperty("z", 0);
        seed.add("anchorBlock", anchor);
        seed.addProperty("planningRadiusCells", 4);
        JsonArray seeds = new JsonArray();
        seeds.add(seed);
        JsonObject registry = new JsonObject();
        registry.add("citySeeds", seeds);
        write(run.resolve("city_seed_registry.json"), registry);

        PlanningAreaAccessPolicy policy = policy();
        assertFalse(policy.evaluate("minecraft:overworld", 4096, 0).allowed());

        Path autoState = run.resolve("automation/post_d4/city_1.json");
        JsonObject state = new JsonObject();
        state.addProperty("status", "waiting_for_generation");
        write(autoState, state);

        PlanningAreaAccessPolicy.Decision allowed = policy.evaluate("minecraft:overworld", 4400, 0);
        assertTrue(allowed.allowed());
        assertEquals("RELEASED_CITY_AREA", allowed.reasonCode());
        assertEquals("city_1", allowed.citySeedId());
        assertFalse(policy.evaluate("minecraft:overworld", 4700, 0).allowed());
    }

    @Test
    void installsConfigWithConfirmedDefaults() throws Exception {
        Path path = temporaryDirectory.resolve("config/geomantia/planning_area_access.json");
        PlanningAreaAccessConfig config = PlanningAreaAccessConfig.loadOrCreate(path);

        assertTrue(Files.isRegularFile(path));
        assertEquals(2048, config.initialActivityRadiusBlocks());
        assertEquals(3072, config.firstCityMinimumDistanceBlocks());
    }

    private PlanningAreaAccessPolicy policy() {
        return new PlanningAreaAccessPolicy(temporaryDirectory.resolve("realm_debug"),
                new PlanningAreaAccessConfig(true, 2048, 3072, Set.of("minecraft:overworld")));
    }

    private static void write(Path path, JsonObject value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(value));
    }
}
