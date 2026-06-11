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
        assertTrue(response.get("passed").getAsBoolean());
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
        assertTrue(Files.exists(runDir.resolve("acceptance_report.json")));

        JsonObject report = readJson(runDir.resolve("acceptance_report.json"));
        assertTrue(report.get("passed").getAsBoolean());
        assertTrue(report.has("durationMs"));
        assertTrue(report.getAsJsonObject("stageResults").get("T4").getAsBoolean());
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
