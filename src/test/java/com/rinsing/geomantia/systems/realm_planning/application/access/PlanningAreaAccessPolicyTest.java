package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
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
    void opensTravelCorridorInsteadOfIsolatedCityCircle() throws Exception {
        writeRun("run_connected", 2_000L, -8192, 8192, -8192, 8192,
                List.of(new Seed("city_far", 4096, 0, 4, "waiting_for_generation")));

        PlanningAreaAccessPolicy policy = policy();
        assertEquals(Set.of("city_far"), policy.connectedCityIds());
        assertEquals("RELEASED_TRAVEL_CORRIDOR",
                policy.evaluate("minecraft:overworld", 3000, 0).reasonCode());
        assertEquals("RELEASED_CITY_AREA",
                policy.evaluate("minecraft:overworld", 4096, 0).reasonCode());
        assertFalse(policy.evaluate("minecraft:overworld", 3000, 900).allowed());
    }

    @Test
    void detoursAroundUnreleasedCityReservation() throws Exception {
        writeRun("run_detour", 3_000L, -8192, 8192, -4096, 4096, List.of(
                new Seed("city_near_unreleased", 3584, 0, 2, "pending"),
                new Seed("city_far_ready", 7168, 0, 4, "waiting_for_generation")));

        PlanningAreaAccessPolicy policy = policy();
        assertEquals(Set.of("city_far_ready"), policy.connectedCityIds());
        assertEquals("UNRELEASED_CITY_RESERVED",
                policy.evaluate("minecraft:overworld", 3584, 0).reasonCode());
        assertEquals("RELEASED_CITY_AREA",
                policy.evaluate("minecraft:overworld", 7168, 0).reasonCode());
    }

    @Test
    void keepsReadyFarCityLockedWhenReservationWallCannotBeBypassed() throws Exception {
        writeRun("run_blocked", 4_000L, -1024, 8192, -2048, 2048, List.of(
                new Seed("wall_a", 3584, -2048, 2, "pending"),
                new Seed("wall_b", 3584, -1024, 2, "pending"),
                new Seed("wall_c", 3584, 0, 2, "pending"),
                new Seed("wall_d", 3584, 1024, 2, "pending"),
                new Seed("wall_e", 3584, 2048, 2, "pending"),
                new Seed("city_far_ready", 7168, 0, 4, "waiting_for_generation")));

        PlanningAreaAccessPolicy policy = policy();
        assertEquals(Set.of("city_far_ready"), policy.disconnectedCityIds());
        PlanningAreaAccessPolicy.Decision decision = policy.evaluate("minecraft:overworld", 7168, 0);
        assertFalse(decision.allowed());
        assertEquals("RELEASED_CITY_NOT_CONNECTED", decision.reasonCode());
    }

    @Test
    void readsOnlyMostRecentlyActiveQueuedRun() throws Exception {
        writeRun("old_run", 1_000L, -8192, 8192, -8192, 8192,
                List.of(new Seed("old_city", 4096, 0, 4, "waiting_for_generation")));
        writeRun("current_run", 5_000L, -8192, 8192, -8192, 8192,
                List.of(new Seed("current_city", -4096, 0, 4, "waiting_for_generation")));

        PlanningAreaAccessPolicy policy = policy();
        assertEquals("current_run", policy.activeRunId());
        assertTrue(policy.evaluate("minecraft:overworld", -4096, 0).allowed());
        assertFalse(policy.evaluate("minecraft:overworld", 4096, 0).allowed());
    }

    @Test
    void installsConfigWithConfirmedDefaults() throws Exception {
        Path path = temporaryDirectory.resolve("config/geomantia/planning_area_access.json");
        PlanningAreaAccessConfig config = PlanningAreaAccessConfig.loadOrCreate(path);

        assertTrue(Files.isRegularFile(path));
        assertEquals(2048, config.initialActivityRadiusBlocks());
        assertEquals(3072, config.firstCityMinimumDistanceBlocks());
        assertEquals(256, PlanningAreaAccessConfig.DEFAULT_TRAVEL_CORRIDOR_RADIUS_BLOCKS);
        assertEquals(96, PlanningAreaAccessConfig.DEFAULT_BOUNDARY_WARNING_DISTANCE_BLOCKS);
    }

    private PlanningAreaAccessPolicy policy() {
        return new PlanningAreaAccessPolicy(temporaryDirectory.resolve("realm_debug"),
                new PlanningAreaAccessConfig(true, 2048, 3072, Set.of("minecraft:overworld")), 160);
    }

    private void writeRun(String runId, long queueTimestamp, int minX, int maxX, int minZ, int maxZ,
                          List<Seed> values) throws Exception {
        Path run = temporaryDirectory.resolve("realm_debug").resolve(runId);
        Files.createDirectories(run);

        JsonObject manifest = new JsonObject();
        JsonObject config = new JsonObject();
        config.addProperty("dimensionId", "minecraft:overworld");
        config.addProperty("cellStepBlocks", 128);
        manifest.add("config", config);
        JsonObject scanBounds = new JsonObject();
        scanBounds.addProperty("minBlockX", minX);
        scanBounds.addProperty("maxBlockX", maxX);
        scanBounds.addProperty("minBlockZ", minZ);
        scanBounds.addProperty("maxBlockZ", maxZ);
        manifest.add("scanBounds", scanBounds);
        write(run.resolve("world_survey_manifest.json"), manifest);

        JsonArray seeds = new JsonArray();
        JsonArray items = new JsonArray();
        for (Seed value : values) {
            JsonObject seed = new JsonObject();
            seed.addProperty("citySeedId", value.citySeedId());
            JsonObject anchor = new JsonObject();
            anchor.addProperty("x", value.x());
            anchor.addProperty("z", value.z());
            seed.add("anchorBlock", anchor);
            seed.addProperty("planningRadiusCells", value.planningRadiusCells());
            seeds.add(seed);

            JsonObject item = new JsonObject();
            item.addProperty("citySeedId", value.citySeedId());
            item.addProperty("status", value.status());
            items.add(item);
        }
        JsonObject registry = new JsonObject();
        registry.add("citySeeds", seeds);
        write(run.resolve("city_seed_registry.json"), registry);
        JsonObject queue = new JsonObject();
        queue.add("items", items);
        Path queuePath = run.resolve("automation/city_design_queue.json");
        write(queuePath, queue);
        Files.setLastModifiedTime(queuePath, FileTime.fromMillis(queueTimestamp));
    }

    private static void write(Path path, JsonObject value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(value));
    }

    private record Seed(String citySeedId, int x, int z, int planningRadiusCells, String status) {
    }
}
