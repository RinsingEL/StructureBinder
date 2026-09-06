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

    @Test
    void completedNeighborCanBeReachedInsideRoutingMarginButNotProtectedReservation() throws Exception {
        writeRun("run_live_pinewood", 9_000L, -8192, 8192, -8192, 8192, List.of(
                new Seed("capital_pending", -6864, 3568, 3, "pending"),
                new Seed("pinewood", -6288, 3600, 1, "waiting_for_generation")));
        var policy = policy();
        assertTrue(policy.connectedCityIds().contains("pinewood"));
        assertTrue(policy.evaluate("minecraft:overworld", -6288, 3600).allowed());
        assertFalse(policy.evaluate("minecraft:overworld", -6864, 3568).allowed());
        assertFalse(policy.evaluate("minecraft:overworld", -6400, 3568).allowed(),
                "The narrowing arrival corridor must not open the pending city's safety buffer");
        var wideView = new PlanningAreaAccessPolicy(temporaryDirectory.resolve("realm_debug"),
                PlanningAreaAccessConfig.defaults(), 224);
        assertTrue(wideView.connectedCityIds().contains("pinewood"));
        assertTrue(wideView.evaluate("minecraft:overworld", -6192, 3600).allowed());
        assertFalse(wideView.evaluate("minecraft:overworld", -6288, 3600).allowed(),
                "Use a safe city arrival point, not a bypass of the view-distance protection");
    }

    @Test
    void realQadirActivatedStructuresAreAllExplorable() throws Exception {
        String configured = System.getenv("GEOMANTIA_QADIR_RUN");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null && !configured.isBlank());
        Path run = Path.of(configured);
        PlanningAreaAccessPolicy actual = new PlanningAreaAccessPolicy(run.getParent(),
                new PlanningAreaAccessConfig(true, 2048, 3072, Set.of("minecraft:overworld")), 288);
        JsonObject active = com.google.gson.JsonParser.parseString(Files.readString(run.getParent().getParent()
                .resolve("geomantia_city_masks/active_planned_structure_registry.json"))).getAsJsonObject();
        int checked = 0;
        for (var registry : active.getAsJsonArray("registries")) {
            if (!run.getFileName().toString().equals(registry.getAsJsonObject().get("runId").getAsString())) continue;
            for (var structure : registry.getAsJsonObject().getAsJsonArray("plannedStructures")) {
                JsonObject anchor = structure.getAsJsonObject().getAsJsonObject("anchorBlock");
                assertTrue(actual.evaluate("minecraft:overworld", anchor.get("x").getAsDouble(),
                        anchor.get("z").getAsDouble()).allowed(), structure.getAsJsonObject().get("anchorId").toString());
                checked++;
            }
        }
        assertEquals(83, checked);
        assertTrue(actual.evaluate("minecraft:overworld", -2980, 5430).allowed());
        assertFalse(actual.evaluate("minecraft:overworld", -7000, 7000).allowed());
    }

    @Test
    void releasedCityIncludesActivatedFootprintButNotOtherRunsOrUnreleasedCities() throws Exception {
        writeRun("current", 5000, -8192, 8192, -8192, 8192, List.of(
                new Seed("ready", 4096, 0, 1, "waiting_for_generation"),
                new Seed("pending", 6000, 0, 1, "pending")));
        write(temporaryDirectory.resolve("geomantia_city_masks/active_planned_structure_registry.json"),
                com.google.gson.JsonParser.parseString("""
                {"registries":[
                  {"runId":"current","cityId":"ready","plannedStructures":[
                    {"maskEnvelope":{"minX":4300,"maxX":6500,"minZ":-20,"maxZ":20}}]},
                  {"runId":"other","cityId":"ready","plannedStructures":[
                    {"maskEnvelope":{"minX":-7000,"maxX":-6000,"minZ":-20,"maxZ":20}}]}
                ]}
                """).getAsJsonObject());
        assertTrue(policy().evaluate("minecraft:overworld", 4600, 0).allowed());
        assertEquals("UNRELEASED_CITY_RESERVED", policy().evaluate("minecraft:overworld", 6000, 0).reasonCode());
        assertFalse(policy().evaluate("minecraft:overworld", -6500, 0).allowed());
        assertFalse(policy().evaluate("minecraft:overworld", 4700, 500).allowed());
    }

    @Test
    void activatedOutdoorSpansAndFeatureCellsExtendCityAndRefreshSourceStamp() throws Exception {
        writeRun("outdoor", 5000, -8192, 8192, -8192, 8192,
                List.of(new Seed("ready", 4096, 0, 1, "waiting_for_generation")));
        write(temporaryDirectory.resolve("geomantia_city_masks/active_planned_structure_registry.json"),
                com.google.gson.JsonParser.parseString("""
                {"registries":[{"runId":"outdoor","cityId":"ready","plannedStructures":[]}]}
                """).getAsJsonObject());
        long before = PlanningAreaAccessPolicy.sourceStamp(temporaryDirectory.resolve("realm_debug"));
        Path active = temporaryDirectory.resolve("geomantia_city_masks/active_city_land_use_area_plans.json");
        write(active, com.google.gson.JsonParser.parseString("""
                {"plans":[{"cityId":"ready","dimensionId":"minecraft:overworld",
                  "areaPlan":{"areas":[{"memberSpans":[{"minX":4600,"maxX":4800,"z":600}]}]},
                  "surfacePrintPlan":{"featureCells":[{"x":4900,"z":700}]}}]}
                """).getAsJsonObject());
        Files.setLastModifiedTime(active, FileTime.fromMillis(before + 10000));
        assertTrue(PlanningAreaAccessPolicy.sourceStamp(temporaryDirectory.resolve("realm_debug")) > before);
        assertTrue(policy().evaluate("minecraft:overworld", 4800, 600).allowed());
        assertTrue(policy().evaluate("minecraft:overworld", 4900, 700).allowed());
        assertFalse(policy().evaluate("minecraft:overworld", 5500, 700).allowed());
    }

    @Test
    void livePinewoodRoadRemainsOpenAcrossNeighborViewBuffer() throws Exception {
        writeRun("pinewood_walk", 9000, -8192, 8192, -8192, 8192, List.of(
                new Seed("capital", -6864, 3568, 3, "pending"),
                new Seed("pinewood", -6288, 3600, 1, "waiting_for_generation")));
        write(temporaryDirectory.resolve("geomantia_city_masks/active_planned_structure_registry.json"),
                com.google.gson.JsonParser.parseString("""
                {"registries":[{"runId":"pinewood_walk","cityId":"pinewood","plannedStructures":[
                  {"maskEnvelope":{"minX":-6500,"maxX":-6100,"minZ":3400,"maxZ":3800}}]}]}
                """).getAsJsonObject());
        var policy = new PlanningAreaAccessPolicy(temporaryDirectory.resolve("realm_debug"),
                PlanningAreaAccessConfig.defaults(), 224);
        for (int x = -6192; x >= -6440; x--)
            assertTrue(policy.evaluate("minecraft:overworld", x, 3649).allowed(), "Road x=" + x);
        assertFalse(policy.evaluate("minecraft:overworld", -6500, 3568).allowed(),
                "An overlapping activated envelope cannot release actual pending construction");
        assertFalse(policy.evaluate("minecraft:overworld", -6340, 3900).allowed(),
                "The buffer exemption is limited to the activated city footprint");
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
