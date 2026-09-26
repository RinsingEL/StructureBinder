package com.rinsing.geomantia.systems.realm_planning.application.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdventurerMapStatusReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void overviewUsesSurveyExtentAndSurvivesPlayerFiltering() throws Exception {
        Path root = temporaryDirectory.resolve("realm_debug");
        Path run = Files.createDirectories(root.resolve("survey_bounds"));
        Files.writeString(run.resolve("world_survey_context.json"), """
                {"scanBounds":{"minBlockX":-12288,"minBlockZ":-12288,"maxBlockX":12287,"maxBlockZ":12287}}
                """);
        var bounds = AdventurerMapStatusReader.read(root,"survey_bounds").forViewer(false).surveyBounds();
        assertTrue(bounds.available());
        assertEquals(24576,bounds.maxX()-bounds.minX());
        assertEquals(0,bounds.centerX());
        assertTrue(4096/bounds.minimumZoom() >= 12288);
        assertTrue(4096/bounds.minimumZoom() < 15000,"Overview must not zoom out to the old 524288 radius");
        assertFalse(AdventurerMapSnapshot.empty().surveyBounds().available());
    }

    @Test
    void cachedSourcesFollowEditsDeletionAndWorldChanges() throws Exception {
        Path root = temporaryDirectory.resolve("first/realm_debug");
        Path run = Files.createDirectories(root.resolve("same_run"));
        Path grid = run.resolve("world_feature_grid.json");
        String land = "{\"cellStepBlocks\":128,\"cells\":[{\"gridX\":0,\"gridZ\":0,\"waterFrac\":0}]}";
        Files.writeString(run.resolve("world_survey_context.json"), "{}");
        Files.writeString(grid, land);
        var config = new PlanningAreaAccessConfig(true, 2048, 8192, Set.of("minecraft:overworld"));
        var firstView = new AdventurerMapStatusReader.MapViewport(0, 0, 1024);
        var nextView = new AdventurerMapStatusReader.MapViewport(128, 0, 1024);
        var landMap = AdventurerMapStatusReader.read(root, "same_run", config, firstView).coarseMap();
        assertEquals(3, landMap.terrainCodes()[0]);
        assertTrue(landMap.revealedAt(64, 64));

        // A changed starter mask must invalidate fog even if world samples are unchanged.
        Files.writeString(root.getParent().resolve("geomantia_starter_realm.json"),
                "{\"centerBlockX\":10000,\"centerBlockZ\":10000}");
        var moved = AdventurerMapStatusReader.read(root, "same_run", config, nextView).coarseMap();
        assertFalse(moved.revealedAt(64, 64));

        Files.writeString(grid, land.replace("\"waterFrac\":0", "\"waterFrac\":1.00"));
        assertEquals(1, AdventurerMapStatusReader.read(root, "same_run", config, firstView)
                .coarseMap().terrainCodes()[0]);

        Path otherRoot = temporaryDirectory.resolve("second/realm_debug");
        Path otherRun = Files.createDirectories(otherRoot.resolve("same_run"));
        Files.writeString(otherRun.resolve("world_survey_context.json"), "{}");
        Files.writeString(otherRun.resolve("world_feature_grid.json"), land);
        var otherMap = AdventurerMapStatusReader.read(otherRoot, "same_run", config, nextView).coarseMap();
        assertEquals(3, otherMap.terrainCodes()[0]);
        assertTrue(otherMap.revealedAt(64, 64));

        Files.delete(grid);
        assertFalse(AdventurerMapStatusReader.read(root, "same_run", config, firstView).coarseMap().available());
    }

    @Test
    void rasterComparisonDetectsFogAndOwnershipChangesAcrossDecodedArrays() {
        var first = new AdventurerMapSnapshot.CoarseMap("minecraft:overworld", 0, 0, 128, 1, 1,
                new byte[]{3}, new byte[]{1}, new byte[]{1}, java.util.List.of("realm"));
        var decoded = new AdventurerMapSnapshot.CoarseMap("minecraft:overworld", 0, 0, 128, 1, 1,
                new byte[]{3}, new byte[]{1}, new byte[]{1}, java.util.List.of("realm"));
        var hidden = new AdventurerMapSnapshot.CoarseMap("minecraft:overworld", 0, 0, 128, 1, 1,
                new byte[]{3}, new byte[]{1}, new byte[]{0}, java.util.List.of("realm"));
        var newOwner = new AdventurerMapSnapshot.CoarseMap("minecraft:overworld", 0, 0, 128, 1, 1,
                new byte[]{3}, new byte[]{1}, new byte[]{1}, java.util.List.of("other"));
        assertTrue(first.sameRaster(decoded));
        assertFalse(first.sameRaster(hidden));
        assertFalse(first.sameRaster(newOwner));
    }

    @Test
    void returnsEmptySnapshotWhenNoPlanningRunExists() throws Exception {
        var snapshot = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"), "");

        assertEquals("", snapshot.runId());
        assertEquals("not_started", snapshot.wStatus());
        assertTrue(snapshot.cityNodes().isEmpty());
    }

    @Test
    void reusesLargeSurveyAcrossViewportChanges() throws Exception {
        Path root = temporaryDirectory.resolve("benchmark/realm_debug");
        Path run = Files.createDirectories(root.resolve("survey"));
        Files.writeString(run.resolve("world_survey_context.json"), "{}");
        Files.writeString(run.resolve("world_survey_manifest.json"), "{\"status\":\"sealed\"}");
        JsonObject grid = new JsonObject();
        grid.addProperty("cellStepBlocks", 128);
        JsonArray cells = new JsonArray();
        for (int z = -64; z < 64; z++) for (int x = -64; x < 64; x++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("gridX", x); cell.addProperty("gridZ", z);
            cell.addProperty("waterFrac", x < 0 ? 1 : 0);
            cells.add(cell);
        }
        grid.add("cells", cells);
        Files.writeString(run.resolve("world_feature_grid.json"), grid.toString());
        var config = new PlanningAreaAccessConfig(true, 2048, 8192, Set.of("minecraft:overworld"));
        long start = System.nanoTime();
        AdventurerMapStatusReader.read(root, "survey", config,
                new AdventurerMapStatusReader.MapViewport(0, 0, 1024));
        double coldMs = (System.nanoTime() - start) / 1_000_000.0;
        double[] warmMs = new double[8];
        for (int i = 0; i < warmMs.length; i++) {
            int center = i % 2 == 0 ? -4096 : 4096;
            start = System.nanoTime();
            var map = AdventurerMapStatusReader.read(root, "survey", config,
                    new AdventurerMapStatusReader.MapViewport(center, i * 128, 1024)).coarseMap();
            warmMs[i] = (System.nanoTime() - start) / 1_000_000.0;
            assertEquals(center < 0 ? 1 : 3, map.terrainCodes()[0]);
            assertEquals(16, map.width());
        }
        java.util.Arrays.sort(warmMs);
        System.out.printf(java.util.Locale.ROOT,
                "Map survey 16384 cells: cold=%.2f ms, changed-view median=%.2f ms%n", coldMs, warmMs[4]);
    }

    @Test
    void exposesLiveWProgressWithoutInventingTProgress() throws Exception {
        Path run = Files.createDirectories(temporaryDirectory.resolve("realm_debug/run_w"));
        Files.writeString(run.resolve("world_survey_progress.json"), """
                {"status":"running","phase":"micro_sampling","phaseProgressPercent":42.5}
                """);

        var snapshot = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"), "run_w");

        assertEquals("run_w", snapshot.runId());
        assertEquals("running", snapshot.wStatus());
        assertEquals("micro_sampling", snapshot.wPhase());
        assertEquals(42.5D, snapshot.wProgressPercent());
        assertEquals("", snapshot.tStage());
        assertEquals("not_started", snapshot.tStatus());
    }

    @Test
    void readsCurrentCityAndNodesFromPersistedArtifactsWithoutMutatingThem() throws Exception {
        Path run = Files.createDirectories(temporaryDirectory.resolve("realm_debug/run_complete"));
        Files.writeString(run.resolve("world_survey_context.json"), "{}");
        Files.writeString(run.resolve("t4_report.json"), "{}");
        Files.writeString(run.resolve("realm_profiles.json"), """
                [{"realmId":"realm_a","name":"晨曦王国"}]
                """);
        Files.writeString(run.resolve("city_seed_registry.json"), """
                {"citySeeds":[
                  {"citySeedId":"capital_a","realmId":"realm_a","role":"capital","anchorBlock":{"x":3200,"z":0}},
                  {"citySeedId":"city_a_2","realmId":"realm_a","role":"city","anchorBlock":{"x":4600,"z":800}}
                ]}
                """);
        Path automation = Files.createDirectories(run.resolve("automation"));
        Path queuePath = automation.resolve("city_design_queue.json");
        Files.writeString(queuePath, """
                {"status":"waiting_for_agent","currentCitySeedId":"capital_a","completedCount":0,"remainingCount":2,
                 "items":[
                   {"citySeedId":"capital_a","realmId":"realm_a","status":"waiting_for_agent"},
                   {"citySeedId":"city_a_2","realmId":"realm_a","status":"pending"}
                 ]}
                """);
        String before = Files.readString(queuePath);

        var snapshot = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"), "run_complete", 1536);

        assertEquals("completed", snapshot.wStatus());
        assertEquals("T4", snapshot.tStage());
        assertEquals("completed", snapshot.tStatus());
        assertEquals("realm_a", snapshot.currentRealmId());
        assertEquals("晨曦王国", snapshot.currentRealmName());
        assertEquals("capital_a", snapshot.currentCityId());
        assertEquals("waiting_for_agent", snapshot.cityStatus());
        assertEquals(2, snapshot.remainingCityCount());
        assertEquals(1536, snapshot.initialActivityRadiusBlocks());
        assertEquals(2, snapshot.cityNodes().size());
        assertTrue(snapshot.cityNodes().get(0).current());
        assertFalse(snapshot.cityNodes().get(1).current());
        assertEquals(before, Files.readString(queuePath));
    }

    @Test
    void buildsBoundedCoarseMapFromFeatureGridAndTerritoryWithoutReadingPatchMap() throws Exception {
        Path run = Files.createDirectories(temporaryDirectory.resolve("realm_debug/run_map"));
        Files.writeString(run.resolve("world_survey_context.json"), """
                {"dimensionId":"minecraft:overworld","cellStepBlocks":128}
                """);
        Files.writeString(run.resolve("world_feature_grid.json"), """
                {"cellStepBlocks":128,"cells":[
                  {"gridX":-1,"gridZ":-1,"heightP50":52,"robustRelief":8,"waterFrac":1.0,
                   "biomeHist":{"minecraft:frozen_ocean":16}},
                  {"gridX":0,"gridZ":-1,"heightP50":65,"robustRelief":5,"waterFrac":0.0,
                   "biomeHist":{"minecraft:plains":16}},
                  {"gridX":-1,"gridZ":0,"heightP50":112,"robustRelief":50,"waterFrac":0.0,
                   "biomeHist":{"minecraft:windswept_hills":16}},
                  {"gridX":0,"gridZ":0,"heightP50":70,"robustRelief":10,"waterFrac":0.0,
                   "biomeHist":{"minecraft:forest":16}}
                ]}
                """);
        Files.writeString(run.resolve("realm_territory_map.json"), """
                {"territoryCells":[
                  {"gridX":0,"gridZ":-1,"realmId":"realm_a"},
                  {"gridX":0,"gridZ":0,"realmId":"realm_a"}
                ]}
                """);
        Files.writeString(run.resolve("world_patch_map.json"), "this file must not be parsed");

        var snapshot = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"), "run_map");
        var map = snapshot.coarseMap();

        assertTrue(map.available());
        assertEquals("minecraft:overworld", map.dimensionId());
        assertEquals(-128, map.minBlockX());
        assertEquals(-128, map.minBlockZ());
        assertEquals(128, map.cellSizeBlocks());
        assertEquals(2, map.width());
        assertEquals(2, map.height());
        assertEquals(2, Byte.toUnsignedInt(map.terrainCodes()[0]));
        assertEquals(3, Byte.toUnsignedInt(map.terrainCodes()[1]));
        assertEquals(7, Byte.toUnsignedInt(map.terrainCodes()[2]));
        assertEquals(4, Byte.toUnsignedInt(map.terrainCodes()[3]));
        assertEquals(2, map.realmIds().size());
        assertEquals("新手村国度", map.realmNames().get(1));
        assertEquals(2, Byte.toUnsignedInt(map.realmCodes()[1]));
        assertEquals(1, Byte.toUnsignedInt(map.revealedCodes()[0]));
    }

    @Test
    void masksCoarseCellsOutsideTheConfiguredOpenArea() throws Exception {
        Path run = Files.createDirectories(temporaryDirectory.resolve("realm_debug/run_mask"));
        Files.writeString(run.resolve("world_survey_context.json"),
                "{\"dimensionId\":\"minecraft:overworld\",\"cellStepBlocks\":128}");
        Files.writeString(run.resolve("world_feature_grid.json"), """
                {"cellStepBlocks":128,"cells":[
                  {"gridX":0,"gridZ":0,"heightP50":65,"waterFrac":0,"biomeHist":{"minecraft:plains":16}},
                  {"gridX":20,"gridZ":0,"heightP50":65,"waterFrac":0,"biomeHist":{"minecraft:plains":16}}
                ]}
                """);

        var map = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"), "run_mask", 128)
                .coarseMap();

        assertEquals(1, Byte.toUnsignedInt(map.revealedCodes()[0]));
        assertEquals(0, Byte.toUnsignedInt(map.revealedCodes()[20]));
        assertTrue(map.revealedAt(64, 64));
        assertFalse(map.revealedAt(20 * 128 + 64, 64));
    }

    @Test
    void cropsAndRefinesTheWGridForThePlayerViewportAndOmitsOffscreenNodes() throws Exception {
        Path run = Files.createDirectories(temporaryDirectory.resolve("realm_debug/run_viewport"));
        Files.writeString(run.resolve("world_survey_context.json"),
                "{\"dimensionId\":\"minecraft:overworld\",\"cellStepBlocks\":128}");
        JsonObject grid = new JsonObject();
        grid.addProperty("cellStepBlocks", 128);
        JsonArray cells = new JsonArray();
        for (int gridX = -80; gridX < 80; gridX++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("gridX", gridX);
            cell.addProperty("gridZ", 0);
            cell.addProperty("heightP50", 65);
            cell.addProperty("waterFrac", 0);
            JsonObject biomes = new JsonObject();
            biomes.addProperty("minecraft:plains", 16);
            cell.add("biomeHist", biomes);
            cells.add(cell);
        }
        grid.add("cells", cells);
        Files.writeString(run.resolve("world_feature_grid.json"), grid.toString());
        Files.writeString(run.resolve("city_seed_registry.json"), """
                {"citySeeds":[
                  {"citySeedId":"visible","realmId":"realm_a","role":"city","anchorBlock":{"x":448,"z":64}},
                  {"citySeedId":"offscreen","realmId":"realm_a","role":"city","anchorBlock":{"x":4096,"z":64}}
                ]}
                """);
        PlanningAreaAccessConfig accessConfig = new PlanningAreaAccessConfig(true, 8192,
                8192, Set.of("minecraft:overworld"));

        var overview = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"),
                "run_viewport", accessConfig).coarseMap();
        var snapshot = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"),
                "run_viewport", accessConfig, new AdventurerMapStatusReader.MapViewport(512, 64, 1024));
        var viewport = snapshot.coarseMap();

        assertEquals(256, overview.cellSizeBlocks());
        assertEquals(128, viewport.cellSizeBlocks());
        assertEquals(-512, viewport.minBlockX());
        assertEquals(16, viewport.width());
        assertEquals(1, snapshot.cityNodes().size());
        assertEquals("visible", snapshot.cityNodes().get(0).citySeedId());

        var panned = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"),
                "run_viewport", accessConfig, new AdventurerMapStatusReader.MapViewport(4096, 64, 1024));
        assertEquals(3072, panned.coarseMap().minBlockX());
        assertEquals(16, panned.coarseMap().width());
        assertEquals(1, panned.cityNodes().size());
        assertEquals("offscreen", panned.cityNodes().get(0).citySeedId());

        var negative = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"),
                "run_viewport", accessConfig, new AdventurerMapStatusReader.MapViewport(-4096, 64, 1024));
        assertEquals(-5120, negative.coarseMap().minBlockX());
        assertTrue(negative.cityNodes().isEmpty());

        var locked = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"),
                "run_viewport", accessConfig, new AdventurerMapStatusReader.MapViewport(9216, 64, 1024));
        assertTrue(locked.coarseMap().available());
        assertFalse(locked.coarseMap().revealedAt(9216, 64));

        var outside = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"),
                "run_viewport", accessConfig, new AdventurerMapStatusReader.MapViewport(20000, 64, 1024));
        assertFalse(outside.coarseMap().available());
        assertTrue(outside.cityNodes().isEmpty());
    }
}
