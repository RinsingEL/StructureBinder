package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.adapter.snapshot.AtlasRegionSnapshotIo;
import com.rinsing.geomantia.systems.gis.application.refresh.GisRefreshService;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshPriority;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestCase;
import com.rinsing.geomantia.systems.gis.testsupport.SyntheticAtlasSampler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(Files.exists(runDir.resolve("capital_city_seeds.json")));
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
        JsonObject registry = readJson(runDir.resolve("city_seed_registry.json"));
        JsonArray citySeeds = registry.getAsJsonArray("citySeeds");
        assertFalse(citySeeds.isEmpty());
        Set<String> citySeedIds = new HashSet<>();
        for (int i = 0; i < citySeeds.size(); i++) {
            citySeedIds.add(citySeeds.get(i).getAsJsonObject().get("citySeedId").getAsString());
        }
        assertEquals(citySeeds.size(), citySeedIds.size());
        JsonObject survey = readJson(runDir.resolve("world_survey_context.json"));
        JsonObject packageManifest = readJsonArray(runDir.resolve("candidate_map_packages.json"))
                .get(0)
                .getAsJsonObject();
        assertEquals(survey.get("surveyId").getAsString(), packageManifest.get("surveyId").getAsString());
        JsonObject legend = packageManifest.getAsJsonObject("gridLegend");
        assertEquals(64, legend.get("cellStepBlocks").getAsInt());
        assertTrue(legend.has("originBlock"));
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
        assertTrue(report.getAsJsonObject("territoryStatusSummary").has("wildRatio"));
        assertTrue(report.getAsJsonObject("expansionBudgets").size() > 0);
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

    private static JsonObject readJson(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static JsonArray readJsonArray(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    }
}
