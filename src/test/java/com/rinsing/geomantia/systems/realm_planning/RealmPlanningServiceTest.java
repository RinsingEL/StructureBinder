package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.adapter.snapshot.AtlasRegionSnapshotIo;
import com.rinsing.geomantia.systems.gis.application.refresh.GisRefreshService;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshPriority;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestCase;
import com.rinsing.geomantia.systems.gis.testsupport.SyntheticAtlasSampler;
import com.rinsing.geomantia.systems.gis.testsupport.SyntheticTerrainProfile;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainSamplingProvenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmPlanningServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptanceRunWritesWtArtifacts() throws Exception {
        RefreshResult result = refreshSynthetic("mixed", 64);
        RealmPlanningService service = new RealmPlanningService(tempDir.resolve("realm_debug"));

        JsonObject response = service.runAcceptance(result, "realm_acceptance_test", 3, null, true);

        assertTrue(response.get("ok").getAsBoolean());
        assertTrue(response.get("passed").getAsBoolean(), response.toString());
        Path runDir = tempDir.resolve("realm_debug").resolve("realm_acceptance_test");
        assertTrue(Files.exists(runDir.resolve("world_survey_context.json")));
        assertTrue(Files.exists(runDir.resolve("world_patch_map.json")));
        assertTrue(Files.exists(runDir.resolve("grid_overlay_preview.png")));
        assertTrue(Files.exists(runDir.resolve("realm_profiles.json")));
        assertTrue(Files.exists(runDir.resolve("candidate_map_packages.json")));
        assertTrue(Files.exists(runDir.resolve("realm_coordinate_selections.json")));
        assertTrue(Files.exists(runDir.resolve("realm_seeds.json")));
        assertTrue(Files.exists(runDir.resolve("capital_city_intents.json")));
        JsonObject capitalIntent = readJsonArray(runDir.resolve("capital_city_intents.json"))
                .get(0).getAsJsonObject();
        assertFalse(capitalIntent.has("anchorGrid"));
        assertFalse(capitalIntent.has("anchorBlock"));
        assertTrue(Files.exists(runDir.resolve("realm_territory_map.json")));
        assertTrue(Files.exists(runDir.resolve("territory_preview.png")));
        assertTrue(Files.exists(runDir.resolve("city_seed_registry.json")));
        assertTrue(Files.exists(runDir.resolve("city_seed_preview.png")));
        assertTrue(Files.exists(runDir.resolve("territory_repair_log.json")));
        assertTrue(Files.exists(runDir.resolve("realm_city_candidate_packages.json")));
        assertTrue(Files.exists(runDir.resolve("score_manifest.json")));
        assertTrue(Files.exists(runDir.resolve("acceptance_report.json")));

        JsonObject report = readJson(runDir.resolve("acceptance_report.json"));
        assertTrue(report.get("passed").getAsBoolean());
        assertTrue(report.has("durationMs"));
        assertTrue(report.getAsJsonObject("stageResults").get("T4").getAsBoolean());
        JsonObject score = readJson(runDir.resolve("score_manifest.json"));
        assertTrue(score.get("passed").getAsBoolean(), score.toString());
        assertTrue(score.getAsJsonArray("hardBlocks").isEmpty());
        JsonObject wScore = score.getAsJsonObject("subScores").getAsJsonObject("W");
        assertFalse(wScore.get("microSamplingImplemented").getAsBoolean());
        JsonObject t3Score = score.getAsJsonObject("subScores").getAsJsonObject("T3");
        assertEquals("action_budget", t3Score.get("expansionModel").getAsString());
        assertTrue(t3Score.get("minLargestComponentRatio").getAsDouble() >= 0.90, t3Score.toString());
        assertTrue(t3Score.get("maxDetachedAreaRatio").getAsDouble() <= 0.05, t3Score.toString());
        assertTrue(t3Score.has("wildlandRatio"));
        assertTrue(t3Score.has("budgetCoherenceScore"));
        JsonObject t4Score = score.getAsJsonObject("subScores").getAsJsonObject("T4");
        assertEquals(0, t4Score.get("duplicateAnchorCount").getAsInt());
        assertEquals(0, t4Score.get("spacingViolationCount").getAsInt());
        assertEquals(0, t4Score.get("offTerritoryAnchorCount").getAsInt());
        assertTrue(t4Score.get("allAnchorsInOwnedTerritory").getAsBoolean());
        JsonObject registry = readJson(runDir.resolve("city_seed_registry.json"));
        JsonArray citySeeds = registry.getAsJsonArray("citySeeds");
        assertFalse(citySeeds.isEmpty());
        Set<String> citySeedIds = new HashSet<>();
        for (int i = 0; i < citySeeds.size(); i++) {
            JsonObject citySeed = citySeeds.get(i).getAsJsonObject();
            citySeedIds.add(citySeed.get("citySeedId").getAsString());
            assertEquals("rule_fixture", citySeed.getAsJsonObject("source")
                    .get("selectionMode").getAsString());
        }
        assertEquals(citySeeds.size(), citySeedIds.size());
        JsonObject t4Report = readJson(runDir.resolve("t4_report.json"));
        assertEquals(0, t4Report.get("offTerritoryAnchorCount").getAsInt());
        assertTrue(t4Report.get("allAnchorsInOwnedTerritory").getAsBoolean());
        JsonObject territory = readJson(runDir.resolve("realm_territory_map.json"));
        Set<String> ownedAnchors = new HashSet<>();
        JsonArray territoryCells = territory.getAsJsonArray("territoryCells");
        for (int i = 0; i < territoryCells.size(); i++) {
            JsonObject cell = territoryCells.get(i).getAsJsonObject();
            if ("owned".equals(cell.get("status").getAsString())) {
                ownedAnchors.add(cell.get("realmId").getAsString() + ":"
                        + cell.get("gridX").getAsInt() + "," + cell.get("gridZ").getAsInt());
            }
        }
        for (int i = 0; i < citySeeds.size(); i++) {
            JsonObject seed = citySeeds.get(i).getAsJsonObject();
            JsonObject anchor = seed.getAsJsonObject("anchorGrid");
            String anchorKey = seed.get("realmId").getAsString() + ":"
                    + anchor.get("x").getAsInt() + "," + anchor.get("z").getAsInt();
            assertTrue(ownedAnchors.contains(anchorKey), seed.toString());
        }
        JsonObject survey = readJson(runDir.resolve("world_survey_context.json"));
        JsonObject packageManifest = readJsonArray(runDir.resolve("candidate_map_packages.json"))
                .get(0)
                .getAsJsonObject();
        assertEquals(survey.get("surveyId").getAsString(), packageManifest.get("surveyId").getAsString());
        JsonObject legend = packageManifest.getAsJsonObject("gridLegend");
        assertEquals(64, legend.get("cellStepBlocks").getAsInt());
        assertTrue(legend.has("originBlock"));
        JsonArray cityCandidatePackages = readJsonArray(runDir.resolve("realm_city_candidate_packages.json"));
        assertFalse(cityCandidatePackages.isEmpty());
        for (int i = 0; i < cityCandidatePackages.size(); i++) {
            JsonObject cityPackage = cityCandidatePackages.get(i).getAsJsonObject();
            assertEquals("realm_owned_territory", cityPackage.get("mapScope").getAsString(), cityPackage.toString());
            assertTrue(cityPackage.has("mapBounds"), cityPackage.toString());
            JsonObject cityLegend = cityPackage.getAsJsonObject("gridLegend");
            assertEquals(64, cityLegend.get("cellStepBlocks").getAsInt());
            String image = cityPackage.get("candidateMapImage").getAsString();
            assertTrue(image.startsWith("city_candidates/"), cityPackage.toString());
            assertTrue(Files.exists(runDir.resolve(image)), cityPackage.toString());
        }
    }

    @Test
    void t2RejectsCoordinateOutsideMap() throws Exception {
        RefreshResult result = refreshSynthetic("plain", 64);
        RealmPlanningService service = new RealmPlanningService(tempDir.resolve("realm_debug"));
        service.runW(result, "realm_reject_test", null);
        service.prepareT1("realm_reject_test", null, 2, "", true);

        JsonObject response = service.selectT2("realm_reject_test", "realm_salt_kingdom_0",
                9999, 9999, null, "bad coordinate", "debug", false);

        assertEquals("failed", response.get("status").getAsString());
        assertFalse(response.getAsJsonArray("errors").isEmpty());
    }

    @Test
    void synchronizeT4RegistryRebuildsDerivedArtifactsAndInvalidatesAcceptance() throws Exception {
        RefreshResult result = refreshSynthetic("mixed", 64);
        Path debugRoot = tempDir.resolve("realm_debug");
        RealmPlanningService service = new RealmPlanningService(debugRoot);
        service.runAcceptance(result, "realm_t4_sync_test", 3, null, true);

        Path runDir = debugRoot.resolve("realm_t4_sync_test");
        JsonObject registry = readJson(runDir.resolve("city_seed_registry.json"));
        JsonArray seeds = registry.getAsJsonArray("citySeeds");
        int removedIndex = -1;
        for (int i = seeds.size() - 1; i >= 0; i--) {
            if (!"capital".equals(seeds.get(i).getAsJsonObject().get("role").getAsString())) {
                removedIndex = i;
                break;
            }
        }
        assertTrue(removedIndex >= 0, registry.toString());
        seeds.remove(removedIndex);
        JsonObject source = seeds.get(0).getAsJsonObject().getAsJsonObject("source");
        source.addProperty("patchSelectionRef", "patch_selection_test");
        source.addProperty("patchCandidateId", "PLAINS-01");
        JsonObject retainedExternalSeed = seeds.get(0).getAsJsonObject().deepCopy();
        retainedExternalSeed.addProperty("citySeedId", "city_retained_external_realm");
        retainedExternalSeed.addProperty("realmId", "realm_from_another_planning_run");
        retainedExternalSeed.addProperty("role", "large_town");
        retainedExternalSeed.getAsJsonObject("anchorGrid").addProperty("x", 10_000);
        retainedExternalSeed.getAsJsonObject("anchorGrid").addProperty("z", 10_000);
        seeds.add(retainedExternalSeed);

        JsonObject response = service.synchronizeT4RegistryArtifacts("realm_t4_sync_test", registry);
        int expectedCount = seeds.size();

        assertEquals("rerun_acceptance", response.getAsJsonArray("nextActions").get(0).getAsString());
        JsonObject persistedRegistry = readJson(runDir.resolve("city_seed_registry.json"));
        assertEquals("patch_selection_test", persistedRegistry.getAsJsonArray("citySeeds").get(0)
                .getAsJsonObject().getAsJsonObject("source").get("patchSelectionRef").getAsString());
        assertEquals(expectedCount, readJson(runDir.resolve("t4_report.json")).get("citySeedCount").getAsInt());
        JsonObject score = readJson(runDir.resolve("score_manifest.json"));
        assertEquals(expectedCount, score.getAsJsonObject("subScores").getAsJsonObject("T4")
                .get("citySeedCount").getAsInt());
        assertEquals(0, score.getAsJsonObject("subScores").getAsJsonObject("T4")
                .get("offTerritoryAnchorCount").getAsInt());
        assertTrue(score.getAsJsonArray("hardBlocks").isEmpty(), score.toString());
        assertTrue(score.get("passed").getAsBoolean(), score.toString());
        assertTrue(Files.isRegularFile(runDir.resolve("city_seed_preview.png")));
        assertTrue(Files.isRegularFile(runDir.resolve("realm_city_candidate_packages.json")));

        JsonObject acceptance = readJson(runDir.resolve("acceptance_report.json"));
        assertFalse(acceptance.get("passed").getAsBoolean());
        assertTrue(acceptance.get("stale").getAsBoolean());
        assertEquals("stale", acceptance.get("status").getAsString());
        assertEquals("t4_registry_replaced_by_patch_planning", acceptance.get("staleReason").getAsString());
        assertEquals(expectedCount, acceptance.get("currentCitySeedCount").getAsInt());
    }

    @Test
    void t1RoutesSemanticSiteSelectionThroughPatchExplorer() throws Exception {
        RefreshResult result = refreshSynthetic("mixed", 64);
        Path debugRoot = tempDir.resolve("realm_debug");
        RealmPlanningService service = new RealmPlanningService(debugRoot);
        service.runW(result, "realm_t1_patch_explorer_route_test", null);

        JsonObject response = service.prepareT1("realm_t1_patch_explorer_route_test", null, 2, "", true);

        assertEquals("patch_explorer_primary", response.get("selectionMode").getAsString());
        assertEquals("continent_scope_reference", response.get("candidateMapRole").getAsString());
        assertEquals(List.of("patch_explorer_open"),
                response.getAsJsonArray("nextActions").asList().stream().map(element -> element.getAsString()).toList());
        assertEquals(List.of("realm_t2_select_coordinate"),
                response.getAsJsonArray("compatibilityActions").asList().stream()
                        .map(element -> element.getAsString()).toList());

        JsonArray packages = readJsonArray(debugRoot.resolve("realm_t1_patch_explorer_route_test")
                .resolve("candidate_map_packages.json"));
        PatchExplorerService patchExplorer = new PatchExplorerService(debugRoot);
        for (JsonElement element : packages) {
            JsonObject candidatePackage = element.getAsJsonObject();
            assertEquals("continent_scope_reference", candidatePackage.get("mapRole").getAsString());
            assertEquals("target_continent_assignable_land", candidatePackage.get("scopeBasis").getAsString());
            assertFalse(candidatePackage.get("profileDifferentiated").getAsBoolean());
            assertEquals("patch_explorer_primary", candidatePackage.get("selectionMode").getAsString());
            JsonObject rules = candidatePackage.getAsJsonObject("selectionRules");
            assertTrue(rules.get("primaryFlow").getAsString().startsWith("patch_explorer_open"));
            assertEquals("compatibility_only", rules.get("directGridSubmission").getAsString());

            JsonObject openRequest = new JsonObject();
            openRequest.addProperty("runId", "realm_t1_patch_explorer_route_test");
            openRequest.addProperty("scopeType", "realm_t2");
            openRequest.addProperty("realmId", candidatePackage.get("realmId").getAsString());
            JsonObject opened = patchExplorer.open(openRequest);
            assertTrue(opened.get("ok").getAsBoolean());
            assertFalse(opened.getAsJsonArray("typeCatalog").isEmpty());
        }
    }

    @Test
    void runStateIsScopedToServiceInstance() throws Exception {
        RefreshResult result = refreshSynthetic("plain", 64);
        Path debugRoot = tempDir.resolve("realm_debug");
        RealmPlanningService first = new RealmPlanningService(debugRoot);
        RealmPlanningService second = new RealmPlanningService(debugRoot);

        first.runW(result, "realm_instance_scope_test", null);

        assertEquals(1, first.status().getAsJsonArray("knownRuns").size());
        assertTrue(second.status().getAsJsonArray("knownRuns").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> second.prepareT1("realm_instance_scope_test", null, 1, "", true));
    }

    @Test
    void persistedWtCheckpointsResumeAcrossServiceInstancesWithoutRewritingUpstreamArtifacts() throws Exception {
        String runId = "realm_persisted_checkpoint_resume_test";
        Path debugRoot = tempDir.resolve("realm_debug");
        GisTestCase testCase = GisTestCase.byId("mixed");
        WorldSurveyResult survey = new WorldSurveyRunner(debugRoot, GisClassifierConfig.defaults()).run(
                new WorldSurveyRunner.Config(
                        runId,
                        testCase.dimensionId(),
                        "synthetic",
                        0.0,
                        256,
                        256,
                        128,
                        64,
                        32,
                        4,
                        testCase.sampleMode(),
                        WorldSurveyRunner.ResumePolicy.USE_CACHE),
                new SyntheticAtlasSampler(testCase.profile()));
        Path runDir = debugRoot.resolve(runId);

        RealmPlanningService wService = new RealmPlanningService(debugRoot);
        wService.runW(survey, null);
        Path wManifest = runDir.resolve("w_manifest.json");
        String wManifestBefore = Files.readString(wManifest);
        FileTime wManifestTimeBefore = Files.getLastModifiedTime(wManifest);

        RealmPlanningService t1Service = new RealmPlanningService(debugRoot);
        assertTrue(t1Service.status().getAsJsonArray("knownRuns").isEmpty());
        JsonObject t1 = t1Service.prepareT1(runId, null, 1, "", true);
        assertEquals("completed", t1.get("status").getAsString());
        assertEquals(1, t1Service.status().getAsJsonArray("knownRuns").size());
        assertEquals(wManifestBefore, Files.readString(wManifest));
        assertEquals(wManifestTimeBefore, Files.getLastModifiedTime(wManifest));

        String realmId = t1.getAsJsonArray("realmProfiles").get(0).getAsJsonObject().get("realmId").getAsString();
        RealmPlanningService.GridPoint point = t1Service.suggestedPoint(runId, realmId);
        JsonObject t2 = t1Service.selectT2(runId, realmId, point.x(), point.z(), null,
                "persisted checkpoint resume test", "debug", true);
        assertEquals("completed", t2.get("status").getAsString());
        Path profiles = runDir.resolve("realm_profiles.json");
        Path seeds = runDir.resolve("realm_seeds.json");
        String profilesBefore = Files.readString(profiles);
        FileTime profilesTimeBefore = Files.getLastModifiedTime(profiles);
        String seedsBefore = Files.readString(seeds);
        FileTime seedsTimeBefore = Files.getLastModifiedTime(seeds);

        RealmPlanningService t3Service = new RealmPlanningService(debugRoot);
        JsonObject t3 = t3Service.expandT3(runId, "", false, "smoke", "quota_frontier");
        assertEquals("completed", t3.get("status").getAsString());
        assertEquals(profilesBefore, Files.readString(profiles));
        assertEquals(profilesTimeBefore, Files.getLastModifiedTime(profiles));
        assertEquals(seedsBefore, Files.readString(seeds));
        assertEquals(seedsTimeBefore, Files.getLastModifiedTime(seeds));
        Path territory = runDir.resolve("realm_territory_map.json");
        String territoryBefore = Files.readString(territory);
        FileTime territoryTimeBefore = Files.getLastModifiedTime(territory);

        RealmPlanningService t4Service = new RealmPlanningService(debugRoot);
        JsonObject t4 = t4Service.buildT4(runId);
        assertEquals("completed", t4.get("status").getAsString());
        assertFalse(t4.getAsJsonObject("citySeedRegistry").getAsJsonArray("citySeeds").isEmpty());
        assertEquals(territoryBefore, Files.readString(territory));
        assertEquals(territoryTimeBefore, Files.getLastModifiedTime(territory));
    }

    @Test
    void t3WritesNormalizedScaleRatios() throws Exception {
        RefreshResult result = refreshSynthetic("mixed", 64);
        RealmPlanningService service = new RealmPlanningService(tempDir.resolve("realm_debug"));
        service.runAcceptance(result, "realm_ratio_test", 4, null, true);

        JsonObject territory = readJson(tempDir.resolve("realm_debug")
                .resolve("realm_ratio_test")
                .resolve("realm_territory_map.json"));
        JsonObject scales = territory.getAsJsonObject("normalizedScales");
        double total = 0.0;
        for (String key : scales.keySet()) {
            total += scales.getAsJsonObject(key).get("normalizedTargetAreaRatio").getAsDouble();
        }
        assertEquals(1.0, total, 0.0001);
    }

    @Test
    void worldSurveyRunnerSealsMultipleTilesAndCanResumeFromCache() throws Exception {
        GisTestCase testCase = GisTestCase.byId("mixed");
        WorldSurveyRunner runner = new WorldSurveyRunner(tempDir.resolve("realm_debug"), GisClassifierConfig.defaults());
        WorldSurveyRunner.Config config = new WorldSurveyRunner.Config(
                "realm_world_survey_test",
                testCase.dimensionId(),
                "synthetic",
                0.0,
                0,
                0,
                1024,
                128,
                RealmPlanningService.DEFAULT_MICRO_SAMPLE_STRIDE_BLOCKS,
                WorldSurveyRunner.DEFAULT_LOCAL_SLOPE_RADIUS_BLOCKS,
                testCase.sampleMode(),
                WorldSurveyRunner.ResumePolicy.USE_CACHE
        );

        WorldSurveyResult first = runner.run(config, new SyntheticAtlasSampler(testCase.profile()));
        assertTrue(first.sealed());
        assertEquals(16, first.tileCount());
        assertEquals(16, first.scannedTileCount());
        assertTrue(Files.exists(first.manifestPath()));

        WorldSurveyResult second = runner.run(config, new SyntheticAtlasSampler(testCase.profile()));
        assertEquals(0, second.scannedTileCount());
        assertEquals(16, second.cachedTileCount());
        assertTrue(second.microSamplingImplemented());
        assertEquals(4096, second.microSampleCount());
        assertFalse(second.featureCells().isEmpty());
        JsonObject worldSurveyManifest = readJson(second.manifestPath());
        JsonObject manifestConfig = worldSurveyManifest.getAsJsonObject("config");
        JsonObject manifestStats = worldSurveyManifest.getAsJsonObject("stats");
        JsonObject liveProgress = readJson(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("world_survey_progress.json"));
        assertEquals("completed", liveProgress.get("status").getAsString());
        assertEquals("complete", liveProgress.get("phase").getAsString());
        assertEquals(16, liveProgress.getAsJsonObject("tiles").get("processed").getAsInt());
        assertEquals(256, liveProgress.getAsJsonObject("microSampling").get("completedCells").getAsLong());
        assertEquals(4096, liveProgress.getAsJsonObject("microSampling").get("completedSamples").getAsLong());
        assertEquals(16, manifestConfig.get("microSampleBudgetPerCell").getAsInt());
        assertFalse(manifestConfig.get("adaptiveSampling").getAsBoolean());
        assertEquals(4096, manifestStats.get("microSampleBudget").getAsLong());
        assertEquals(16, manifestStats.get("microSampleBudgetPerCell").getAsInt());
        assertFalse(manifestStats.get("adaptiveSampling").getAsBoolean());

        JsonObject restoredAudit = new RealmPlanningService(tempDir.resolve("realm_debug"))
                .runTagAudit("realm_world_survey_test", new SyntheticAtlasSampler(testCase.profile()), 12, 32, 8, 4);
        assertEquals("completed", restoredAudit.get("status").getAsString());
        assertTrue(restoredAudit.getAsJsonObject("tagAuditReport")
                .getAsJsonObject("tagMetrics")
                .has("water_edge"));

        WorldSurveyResult restored = runner.loadSealedResult("realm_world_survey_test");
        JsonObject replayedResponse = new RealmPlanningService(tempDir.resolve("realm_debug"))
                .runAcceptance(restored, 3, null, true, "smoke");
        assertTrue(replayedResponse.get("passed").getAsBoolean(), replayedResponse.toString());
        JsonObject replayedSurvey = readJson(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("world_survey_context.json"));
        assertEquals(4096, replayedSurvey.get("microSampleBudget").getAsLong());
        assertEquals(16, replayedSurvey.get("microSampleBudgetPerCell").getAsInt());
        assertFalse(replayedSurvey.get("adaptiveSampling").getAsBoolean());
        JsonObject replayedT3 = readJson(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("t3_report.json"));
        assertTrue(replayedT3.has("ownedAreaRatio"));
        JsonObject replayedT4 = readJson(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("t4_report.json"));
        assertTrue(replayedT4.get("allAnchorsInOwnedTerritory").getAsBoolean());

        JsonObject response = new RealmPlanningService(tempDir.resolve("realm_debug"))
                .runAcceptance(second, 3, null, true, "smoke");
        assertTrue(response.get("passed").getAsBoolean(), response.toString());
        JsonObject survey = readJson(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("world_survey_context.json"));
        assertEquals(128, survey.get("cellStepBlocks").getAsInt());
        assertTrue(survey.get("microSamplingImplemented").getAsBoolean());
        assertTrue(Files.exists(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("world_feature_grid.json")));
        assertTrue(survey.get("sealed").getAsBoolean());
        assertEquals(16, survey.getAsJsonObject("surveyStats").get("tileCount").getAsInt());
        assertEquals(4096, survey.get("microSampleBudget").getAsLong());
        assertEquals(16, survey.get("microSampleBudgetPerCell").getAsInt());
        assertFalse(survey.get("adaptiveSampling").getAsBoolean());
        JsonObject surveyStats = survey.getAsJsonObject("surveyStats");
        assertEquals(4096, surveyStats.get("microSampleBudget").getAsLong());
        assertEquals(second.microSampleCount(), surveyStats.get("microSampleCount").getAsLong());
        assertFalse(surveyStats.get("adaptiveSampling").getAsBoolean());
        JsonObject acceptance = readJson(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("acceptance_report.json"));
        assertEquals(4096, acceptance.getAsJsonObject("surveyStats").get("microSampleBudget").getAsLong());

        JsonObject patchMap = readJson(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("world_patch_map.json"));
        JsonArray cells = patchMap.getAsJsonArray("cells");
        boolean hasNonMountainLand = false;
        boolean hasTypedWaterEdge = false;
        boolean hasNonCoastalWaterEdge = false;
        boolean hasTerrainEvidence = false;
        for (int i = 0; i < cells.size(); i++) {
            JsonObject cell = cells.get(i).getAsJsonObject();
            if ("land".equals(cell.get("landWater").getAsString())
                    && ("lowland".equals(cell.get("baseLandform").getAsString())
                    || "plateau".equals(cell.get("baseLandform").getAsString()))) {
                hasNonMountainLand = true;
            }
            JsonArray tags = cell.getAsJsonArray("landformTags");
            if (contains(tags, "water_edge")) {
                assertTrue(cell.has("waterEdgeType"), cell.toString());
                String type = cell.get("waterEdgeType").getAsString();
                assertTrue(List.of("seacoast", "riverbank", "lakeshore", "boundary_truncated").contains(type),
                        cell.toString());
                if ("seacoast".equals(type)) {
                    assertTrue(contains(tags, "coastal"), cell.toString());
                } else {
                    assertFalse(contains(tags, "coastal"), cell.toString());
                    hasNonCoastalWaterEdge = true;
                }
                hasTypedWaterEdge = true;
            }
            if (cell.has("terrainMetrics")) {
                JsonObject terrainMetrics = cell.getAsJsonObject("terrainMetrics");
                assertTrue(terrainMetrics.has("localHeightRank"), cell.toString());
                assertTrue(terrainMetrics.has("regionalHeightRank"), cell.toString());
                assertTrue(terrainMetrics.has("devLocal"), cell.toString());
                assertTrue(terrainMetrics.has("roughnessLocal"), cell.toString());
                assertTrue(terrainMetrics.has("plateauProminence"), cell.toString());
                assertTrue(terrainMetrics.has("plateauCoreFlatSupport"), cell.toString());
                assertTrue(cell.has("landformEvidence"), cell.toString());
                assertTrue(cell.has("landformConfidence"), cell.toString());
                hasTerrainEvidence = true;
            }
            if ("cliff".equals(cell.get("landform").getAsString())) {
                JsonObject slopeStats = cell.getAsJsonObject("slopeStats");
                double waterFrac = cell.get("waterFrac").getAsDouble();
                double cliffFractionThreshold = waterFrac > 0.05 && waterFrac < 0.95 ? 0.65 : 0.55;
                boolean microConfirmsCliff = slopeStats.get("p90").getAsDouble() >= 18.0
                        && slopeStats.get("steepFrac").getAsDouble() >= cliffFractionThreshold;
                assertEquals(microConfirmsCliff, contains(tags, "cliff"), cell.toString());
            }
        }
        assertTrue(hasNonMountainLand);
        assertTrue(hasTypedWaterEdge);
        assertTrue(hasNonCoastalWaterEdge);
        assertTrue(hasTerrainEvidence);

        RealmPlanningService auditService = new RealmPlanningService(tempDir.resolve("realm_debug"));
        JsonObject audit = auditService.runTagAudit("realm_world_survey_test",
                new SyntheticAtlasSampler(testCase.profile()), 24, 32, 8, 4, "manual_round_1");
        assertTrue(audit.getAsJsonObject("tagAuditReport").get("sampleCount").getAsInt() > 0);
        assertTrue(Files.exists(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("tag_audit_samples.json")));
        JsonObject auditReport = readJson(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("tag_audit_report.json"));
        assertEquals("manual_round_1", auditReport.get("sampleSeed").getAsString());
        assertTrue(auditReport.getAsJsonObject("sampleLayerCounts").size() > 0);
        assertTrue(auditReport.getAsJsonObject("tagMetrics").has("cliff"));
        assertTrue(auditReport.getAsJsonObject("tagMetrics")
                .getAsJsonObject("cliff")
                .has("precision"));
        JsonArray auditSamples = readJsonArray(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("tag_audit_samples.json"));
        assertFalse(auditSamples.isEmpty());
        JsonObject firstSample = auditSamples.get(0).getAsJsonObject();
        assertTrue(firstSample.has("auditLayer"));
        assertTrue(firstSample.has("cellMinBlockX"));
        assertTrue(firstSample.has("cellMinBlockZ"));
        assertTrue(firstSample.has("tpCommand"));
        assertTrue(firstSample.has("representativePoints"));
        assertTrue(firstSample.has("cellReferenceTags"));
        assertTrue(firstSample.has("pointReferenceTags"));
        assertTrue(firstSample.has("cellReferenceMetrics"));
        JsonObject representativePoints = firstSample.getAsJsonObject("representativePoints");
        assertTrue(representativePoints.has("cellCenter"));
        assertTrue(representativePoints.has("highestMicroPoint"));
        assertTrue(representativePoints.has("lowestMicroPoint"));
        assertTrue(representativePoints.has("maxSlopeMicroPoint"));
        assertTrue(representativePoints.has("recommendedTpPoint"));
        JsonObject cellCenter = representativePoints.getAsJsonObject("cellCenter");
        assertEquals(firstSample.get("cellMinBlockX").getAsInt() + 64, cellCenter.get("blockX").getAsInt());
        assertEquals(firstSample.get("cellMinBlockZ").getAsInt() + 64, cellCenter.get("blockZ").getAsInt());
        JsonObject recommendedTp = representativePoints.getAsJsonObject("recommendedTpPoint");
        assertEquals(recommendedTp.get("blockX").getAsInt(), firstSample.get("blockX").getAsInt());
        assertEquals(recommendedTp.get("blockZ").getAsInt(), firstSample.get("blockZ").getAsInt());
        assertEquals("w_coarse_cell", auditReport.get("samplingUnit").getAsString());
        assertTrue(auditReport.has("cellTagMetrics"));
        assertTrue(auditReport.has("baseLandformMetrics"));
        assertTrue(auditReport.has("representativePointMismatchRate"));
    }

    @Test
    void worldSurveyRunnerRescansWhenSamplingConfigChanges() throws Exception {
        GisTestCase testCase = GisTestCase.byId("mixed");
        WorldSurveyRunner runner = new WorldSurveyRunner(tempDir.resolve("realm_debug"), GisClassifierConfig.defaults());
        WorldSurveyRunner.Config stride32 = new WorldSurveyRunner.Config(
                "realm_cache_config_test",
                testCase.dimensionId(),
                "synthetic",
                0.0,
                0,
                0,
                512,
                128,
                32,
                8,
                testCase.sampleMode(),
                WorldSurveyRunner.ResumePolicy.USE_CACHE
        );
        WorldSurveyRunner.Config stride16 = new WorldSurveyRunner.Config(
                "realm_cache_config_test",
                testCase.dimensionId(),
                "synthetic",
                0.0,
                0,
                0,
                512,
                128,
                16,
                8,
                testCase.sampleMode(),
                WorldSurveyRunner.ResumePolicy.USE_CACHE
        );

        WorldSurveyResult first = runner.run(stride32, new SyntheticAtlasSampler(testCase.profile()));
        WorldSurveyResult changed = runner.run(stride16, new SyntheticAtlasSampler(testCase.profile()));

        assertTrue(first.sealed());
        assertTrue(changed.sealed());
        assertEquals(first.tileCount(), changed.scannedTileCount());
        assertEquals(0, changed.cachedTileCount());
        assertFalse(first.configHash().equals(changed.configHash()));
        assertEquals(first.microSampleCount() * 4, changed.microSampleCount());
        JsonObject manifest = readJson(changed.manifestPath());
        assertEquals(16, manifest.getAsJsonObject("config").get("microSampleStrideBlocks").getAsInt());
        assertEquals(changed.configHash(), manifest.get("configHash").getAsString());
        JsonObject featureGrid = readJson(changed.runDirectory().resolve("world_feature_grid.json"));
        assertEquals(changed.configHash(), featureGrid.get("configHash").getAsString());
        assertEquals(16, featureGrid.get("microSampleStrideBlocks").getAsInt());
        assertEquals(changed.microSampleCount(), featureGrid.get("microSampleCount").getAsLong());
    }

    @Test
    void worldSurveyRunnerSeparatesCachesByTerrainProvider() throws Exception {
        GisTestCase testCase = GisTestCase.byId("mixed");
        WorldSurveyRunner runner = new WorldSurveyRunner(tempDir.resolve("realm_debug"), GisClassifierConfig.defaults());
        WorldSurveyRunner.Config minecraftConfig = new WorldSurveyRunner.Config(
                "realm_provider_cache_test", testCase.dimensionId(), "synthetic", 0.0,
                0, 0, 512, 128, 32, 8, testCase.sampleMode(), WorldSurveyRunner.ResumePolicy.USE_CACHE,
                new TerrainSamplingProvenance(false, "current_atlas_sampler", "gis_atlas_sampler", false,
                        "generator_native_disabled", "minecraft:synthetic:v1", "atlas_sampler"));
        WorldSurveyRunner.Config rtfConfig = new WorldSurveyRunner.Config(
                "realm_provider_cache_test", testCase.dimensionId(), "synthetic", 0.0,
                0, 0, 512, 128, 32, 8, testCase.sampleMode(), WorldSurveyRunner.ResumePolicy.USE_CACHE,
                new TerrainSamplingProvenance(true, "rtf_heightmap_preview_v0_0_5", "generator_native", true,
                        "", "rtf:synthetic:v1", "estimated_heightmap"));

        WorldSurveyResult minecraft = runner.run(minecraftConfig, new SyntheticAtlasSampler(testCase.profile()));
        WorldSurveyResult rtf = runner.run(rtfConfig, new SyntheticAtlasSampler(testCase.profile()));

        assertFalse(minecraft.configHash().equals(rtf.configHash()));
        assertEquals(minecraft.tileCount(), rtf.scannedTileCount());
        assertEquals(0, rtf.cachedTileCount());
        JsonObject manifest = readJson(rtf.manifestPath());
        JsonObject provider = manifest.getAsJsonObject("config").getAsJsonObject("terrainProvider");
        assertEquals("rtf_heightmap_preview_v0_0_5", provider.get("providerId").getAsString());
        assertTrue(provider.get("generatorNativeRequested").getAsBoolean());
        assertTrue(provider.get("fastPath").getAsBoolean());
        assertEquals(provider, manifest.getAsJsonObject("stats").getAsJsonObject("terrainProvider"));
        try (var tileEntries = Files.list(rtf.runDirectory().resolve("tiles"))) {
            assertFalse(tileEntries.anyMatch(path -> path.getFileName().toString().startsWith("debug_")));
        }
    }

    @Test
    void tagAuditCoversConfirmedCliffTruePositives() throws Exception {
        SyntheticTerrainProfile cliffStrip = new SyntheticTerrainProfile() {
            @Override
            public double seaLevel() {
                return 32.0;
            }

            @Override
            public double elevationAt(double blockX, double blockZ) {
                return 90.0 + Math.floor(blockX / 16.0) * 24.0 + Math.sin(blockZ / 24.0) * 2.0;
            }

            @Override
            public boolean hasWaterAt(double blockX, double blockZ) {
                return false;
            }

            @Override
            public SurfaceType surfaceTypeAt(double blockX, double blockZ, double elevation) {
                return SurfaceType.ROCK;
            }

            @Override
            public String biomeAt(double blockX, double blockZ, double elevation, boolean water) {
                return "minecraft:stony_peaks";
            }
        };
        WorldSurveyRunner runner = new WorldSurveyRunner(tempDir.resolve("realm_debug"), GisClassifierConfig.defaults());
        WorldSurveyRunner.Config config = new WorldSurveyRunner.Config(
                "realm_cliff_audit_test",
                "minecraft:overworld",
                "synthetic_cliff_strip",
                0.0,
                0,
                0,
                512,
                128,
                RealmPlanningService.DEFAULT_MICRO_SAMPLE_STRIDE_BLOCKS,
                WorldSurveyRunner.DEFAULT_LOCAL_SLOPE_RADIUS_BLOCKS,
                com.rinsing.geomantia.systems.gis.application.refresh.SampleMode.PRIOR,
                WorldSurveyRunner.ResumePolicy.RESCAN
        );
        SyntheticAtlasSampler sampler = new SyntheticAtlasSampler(cliffStrip);
        WorldSurveyResult survey = runner.run(config, sampler);

        RealmPlanningService service = new RealmPlanningService(tempDir.resolve("realm_debug"));
        service.runAcceptance(survey, 1, null, true, "smoke");
        JsonObject audit = service.runTagAudit("realm_cliff_audit_test", sampler, 36, 32, 8, 4);
        JsonObject cliff = audit.getAsJsonObject("tagAuditReport")
                .getAsJsonObject("tagMetrics")
                .getAsJsonObject("cliff");
        assertTrue(cliff.get("truePositive").getAsInt() > 0, cliff.toString());
        assertEquals(0, cliff.get("falsePositive").getAsInt(), cliff.toString());
        assertEquals(0, cliff.get("falseNegative").getAsInt(), cliff.toString());
    }

    @Test
    void plateauRequiresBroadProminentFlatTop() throws Exception {
        JsonObject smallMound = centerCellForSyntheticPlateauProfile("realm_small_mound_plateau_test", 96.0);
        assertFalse("plateau".equals(smallMound.get("baseLandform").getAsString()), smallMound.toString());
        JsonObject smallMetrics = smallMound.getAsJsonObject("terrainMetrics");
        assertTrue(smallMetrics.get("plateauCoreFlatSupport").getAsDouble() < 0.65, smallMetrics.toString());

        JsonObject broadPlateau = centerCellForSyntheticPlateauProfile("realm_broad_plateau_test", 640.0);
        assertEquals("plateau", broadPlateau.get("baseLandform").getAsString(), broadPlateau.toString());
        JsonObject broadMetrics = broadPlateau.getAsJsonObject("terrainMetrics");
        assertTrue(broadMetrics.get("plateauProminence").getAsDouble() >= 18.0, broadMetrics.toString());
        assertTrue(broadMetrics.get("plateauCoreFlatSupport").getAsDouble() >= 0.65, broadMetrics.toString());
    }

    @Test
    void t3SupportsQuotaAndActionBudgetModels() throws Exception {
        RefreshResult result = refreshSynthetic("mixed", 64);
        RealmPlanningService service = new RealmPlanningService(tempDir.resolve("realm_debug"));
        service.runW(result, "realm_model_test", null);
        service.prepareT1("realm_model_test", null, 3, "", true);
        for (String realmId : new String[] {"realm_salt_kingdom_0", "realm_stone_march_1", "realm_green_court_2"}) {
            RealmPlanningService.GridPoint point = service.suggestedPoint("realm_model_test", realmId);
            service.selectT2("realm_model_test", realmId, point.x(), point.z(), null, "model test", "debug", true);
        }

        JsonObject quota = service.expandT3("realm_model_test", "", false, "smoke", "quota_frontier");
        assertEquals("quota_frontier", quota.getAsJsonObject("territoryMap").get("expansionModel").getAsString());

        JsonObject action = service.expandT3("realm_model_test", "", true, "strict", "action_budget");
        JsonObject territory = action.getAsJsonObject("territoryMap");
        assertEquals("action_budget", territory.get("expansionModel").getAsString());
        JsonObject report = readJson(tempDir.resolve("realm_debug").resolve("realm_model_test").resolve("t3_report.json"));
        JsonObject statusSummary = report.getAsJsonObject("territoryStatusSummary");
        assertTrue(statusSummary.has("wildRatio"));
        assertEquals(statusSummary.get("ownedRatio").getAsDouble(), report.get("ownedAreaRatio").getAsDouble(), 0.0001);
        assertEquals(statusSummary.get("wildRatio").getAsDouble(), report.get("wildlandRatio").getAsDouble(), 0.0001);
        assertEquals(statusSummary.get("contestedRatio").getAsDouble(), report.get("contestedRatio").getAsDouble(), 0.0001);
        assertEquals(statusSummary.get("blockedRatio").getAsDouble(), report.get("blockedRatio").getAsDouble(), 0.0001);
        assertTrue(report.getAsJsonObject("expansionBudgets").size() > 0);

        JsonObject repeatedAction = service.expandT3("realm_model_test", "", true, "strict", "action_budget");
        JsonObject repeatedReport = readJson(tempDir.resolve("realm_debug").resolve("realm_model_test").resolve("t3_report.json"));
        assertEquals(territory.toString(), repeatedAction.getAsJsonObject("territoryMap").toString());
        assertEquals(report.toString(), repeatedReport.toString());
    }

    @Test
    void quotaFrontierScalesToTwelveRealmsAndRemainsDeterministic() throws Exception {
        GisTestCase testCase = GisTestCase.byId("mixed");
        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(8);
        AtlasRegionStore store = new AtlasRegionStore(sampleConfig);
        GisRefreshService gisService = new GisRefreshService(sampleConfig, GisClassifierConfig.defaults(), store,
                new SyntheticAtlasSampler(testCase.profile()));
        RefreshResult result = gisService.refresh(testCase.dimensionId(), 0, 0, 16, testCase.sampleMode(),
                RefreshPriority.DEBUG, tempDir.resolve("gis_debug"));
        RealmPlanningService service = new RealmPlanningService(tempDir.resolve("realm_debug"));
        service.runW(result, "realm_twelve_scale_test", null);
        JsonObject t1 = service.prepareT1("realm_twelve_scale_test", null, 12, "", true);
        for (JsonElement profile : t1.getAsJsonArray("realmProfiles")) {
            String realmId = profile.getAsJsonObject().get("realmId").getAsString();
            RealmPlanningService.GridPoint point = service.suggestedPoint("realm_twelve_scale_test", realmId);
            JsonObject selection = service.selectT2("realm_twelve_scale_test", realmId, point.x(), point.z(), null,
                    "twelve realm scale test", "debug", true);
            assertEquals("completed", selection.get("status").getAsString(), selection.toString());
        }

        JsonObject first = assertTimeout(Duration.ofSeconds(10),
                () -> service.expandT3("realm_twelve_scale_test", "", false, "smoke", "quota_frontier"));
        JsonObject firstTerritory = first.getAsJsonObject("territoryMap");
        assertEquals(12, firstTerritory.getAsJsonArray("realmStats").size());
        assertFalse(firstTerritory.getAsJsonArray("territoryCells").isEmpty());
        for (JsonElement stat : firstTerritory.getAsJsonArray("realmStats")) {
            JsonObject realm = stat.getAsJsonObject();
            assertTrue(realm.get("areaCells").getAsInt() > 0, realm.toString());
            assertEquals(1, realm.get("componentCount").getAsInt(), realm.toString());
        }
        int quotaMoves = 0;
        for (JsonElement repair : firstTerritory.getAsJsonArray("repairs")) {
            JsonObject entry = repair.getAsJsonObject();
            if ("quota_rebalanced".equals(entry.get("type").getAsString())) {
                quotaMoves += entry.get("affectedCells").getAsInt();
            }
        }
        assertTrue(quotaMoves <= firstTerritory.getAsJsonArray("territoryCells").size(),
                "Quota repair must converge monotonically instead of hitting the move cap: " + quotaMoves);

        JsonObject repeated = assertTimeout(Duration.ofSeconds(10),
                () -> service.expandT3("realm_twelve_scale_test", "", false, "smoke", "quota_frontier"));
        assertEquals(firstTerritory, repeated.getAsJsonObject("territoryMap"));
    }

    private RefreshResult refreshSynthetic(String caseId, int cellStepBlocks) throws Exception {
        GisTestCase testCase = GisTestCase.byId(caseId);
        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(cellStepBlocks);
        AtlasRegionStore store = new AtlasRegionStore(sampleConfig);
        GisRefreshService service = new GisRefreshService(sampleConfig, GisClassifierConfig.defaults(), store,
                new SyntheticAtlasSampler(testCase.profile()));
        RefreshResult result = service.refresh(testCase.dimensionId(), testCase.centerBlockX(), testCase.centerBlockZ(),
                testCase.radiusChunks(), testCase.sampleMode(), RefreshPriority.DEBUG, tempDir.resolve("gis_debug"));
        new AtlasRegionSnapshotIo().write(result.region(), result.runDirectory().resolve("region_snapshot.json"));
        return result;
    }

    private JsonObject centerCellForSyntheticPlateauProfile(String runId, double plateauHalfSizeBlocks) throws Exception {
        SyntheticTerrainProfile profile = new SyntheticTerrainProfile() {
            @Override
            public double seaLevel() {
                return 32.0;
            }

            @Override
            public double elevationAt(double blockX, double blockZ) {
                return Math.abs(blockX) <= plateauHalfSizeBlocks && Math.abs(blockZ) <= plateauHalfSizeBlocks
                        ? 96.0 : 70.0;
            }

            @Override
            public boolean hasWaterAt(double blockX, double blockZ) {
                return false;
            }

            @Override
            public SurfaceType surfaceTypeAt(double blockX, double blockZ, double elevation) {
                return SurfaceType.GRASS;
            }

            @Override
            public String biomeAt(double blockX, double blockZ, double elevation, boolean water) {
                return "minecraft:plains";
            }
        };
        WorldSurveyRunner runner = new WorldSurveyRunner(tempDir.resolve("realm_debug"), GisClassifierConfig.defaults());
        WorldSurveyRunner.Config config = new WorldSurveyRunner.Config(
                runId,
                "minecraft:overworld",
                "synthetic_plateau_" + Math.round(plateauHalfSizeBlocks),
                0.0,
                0,
                0,
                2048,
                128,
                RealmPlanningService.DEFAULT_MICRO_SAMPLE_STRIDE_BLOCKS,
                WorldSurveyRunner.DEFAULT_LOCAL_SLOPE_RADIUS_BLOCKS,
                com.rinsing.geomantia.systems.gis.application.refresh.SampleMode.PRIOR,
                WorldSurveyRunner.ResumePolicy.RESCAN
        );
        WorldSurveyResult survey = runner.run(config, new SyntheticAtlasSampler(profile));
        RealmPlanningService service = new RealmPlanningService(tempDir.resolve("realm_debug"));
        service.runW(survey, null);
        JsonArray cells = readJson(survey.runDirectory().resolve("world_patch_map.json")).getAsJsonArray("cells");
        JsonObject best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < cells.size(); i++) {
            JsonObject cell = cells.get(i).getAsJsonObject();
            int centerX = cell.get("blockX").getAsInt() + 64;
            int centerZ = cell.get("blockZ").getAsInt() + 64;
            int distance = Math.abs(centerX) + Math.abs(centerZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = cell;
            }
        }
        assertTrue(best != null);
        return best;
    }

    private static JsonObject readJson(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static JsonArray readJsonArray(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    }

    private static boolean contains(JsonArray array, String value) {
        for (int i = 0; i < array.size(); i++) {
            if (value.equals(array.get(i).getAsString())) {
                return true;
            }
        }
        return false;
    }
}
