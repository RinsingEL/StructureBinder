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
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestCase;
import com.rinsing.geomantia.systems.gis.testsupport.SyntheticAtlasSampler;
import com.rinsing.geomantia.systems.gis.testsupport.SyntheticTerrainProfile;
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
        assertEquals(0, t4Score.get("offTerritoryAnchorCount").getAsInt());
        assertTrue(t4Score.get("allAnchorsInOwnedTerritory").getAsBoolean());
        JsonObject registry = readJson(runDir.resolve("city_seed_registry.json"));
        JsonArray citySeeds = registry.getAsJsonArray("citySeeds");
        assertFalse(citySeeds.isEmpty());
        Set<String> citySeedIds = new HashSet<>();
        for (int i = 0; i < citySeeds.size(); i++) {
            citySeedIds.add(citySeeds.get(i).getAsJsonObject().get("citySeedId").getAsString());
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
        JsonObject worldSurveyManifest = readJson(second.manifestPath());
        JsonObject manifestConfig = worldSurveyManifest.getAsJsonObject("config");
        JsonObject manifestStats = worldSurveyManifest.getAsJsonObject("stats");
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
                .has("coastal"));

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
        for (int i = 0; i < cells.size(); i++) {
            JsonObject cell = cells.get(i).getAsJsonObject();
            if ("cliff".equals(cell.get("landform").getAsString())) {
                JsonObject slopeStats = cell.getAsJsonObject("slopeStats");
                double waterFrac = cell.get("waterFrac").getAsDouble();
                double cliffFractionThreshold = waterFrac > 0.05 && waterFrac < 0.95 ? 0.45 : 0.35;
                boolean microConfirmsCliff = slopeStats.get("p90").getAsDouble() >= 18.0
                        && slopeStats.get("steepFrac").getAsDouble() >= cliffFractionThreshold;
                JsonArray tags = cell.getAsJsonArray("landformTags");
                assertEquals(microConfirmsCliff, contains(tags, "cliff"), cell.toString());
            }
        }

        RealmPlanningService auditService = new RealmPlanningService(tempDir.resolve("realm_debug"));
        JsonObject audit = auditService.runTagAudit("realm_world_survey_test",
                new SyntheticAtlasSampler(testCase.profile()), 24, 32, 8, 4);
        assertTrue(audit.getAsJsonObject("tagAuditReport").get("sampleCount").getAsInt() > 0);
        assertTrue(Files.exists(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("tag_audit_samples.json")));
        JsonObject auditReport = readJson(tempDir.resolve("realm_debug")
                .resolve("realm_world_survey_test")
                .resolve("tag_audit_report.json"));
        assertTrue(auditReport.getAsJsonObject("tagMetrics").has("cliff"));
        assertTrue(auditReport.getAsJsonObject("tagMetrics")
                .getAsJsonObject("cliff")
                .has("precision"));
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

    private static boolean contains(JsonArray array, String value) {
        for (int i = 0; i < array.size(); i++) {
            if (value.equals(array.get(i).getAsString())) {
                return true;
            }
        }
        return false;
    }
}
