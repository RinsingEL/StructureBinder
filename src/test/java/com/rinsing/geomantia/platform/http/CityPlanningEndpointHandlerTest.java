package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.PatchGroupPlan;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityPlanningEndpointHandlerTest {

    @Test
    void handlePlanD2_restoresSealedRunMetadataWhenCellStepOmitted() throws Exception {
        Path debugRoot = Path.of("run/realm_debug");
        Path runDir = debugRoot.resolve("realm_reterraforged_t4_crop_4096_20260614");
        if (!Files.exists(runDir.resolve("city_seed_registry.json"))) {
            return;
        }

        JsonObject response = CityPlanningEndpointHandler.handlePlanD2(
                debugRoot,
                "realm_reterraforged_t4_crop_4096_20260614",
                "city_realm_salt_kingdom_0_capital",
                null);
        JsonObject context = response.getAsJsonObject("citySiteContext");
        JsonObject grid = context.getAsJsonObject("grid");
        JsonObject anchor = context.getAsJsonObject("anchorBlock");

        assertEquals("minecraft:overworld", context.get("dimensionId").getAsString());
        assertEquals(32, grid.get("cellStepBlocks").getAsInt(),
                "City planning grid clamps the sealed W/T cell step into the city-scale range");
        assertEquals(4096, anchor.get("x").getAsInt());
        assertEquals(-3584, anchor.get("z").getAsInt());
        assertEquals("capital_realm_salt_kingdom_0", context.get("siteCandidateId").getAsString());
        assertNotEquals("unknown", context.get("territoryCheckResult").getAsString());
    }

    @Test
    void handlePlanD4_readsD3PackageAndWritesArtifacts() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-test");
        String runId = "run_d4";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "village",
                      "theoreticalScale": "village",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 64,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 64, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain", LandformType.PLAIN, -50, -50, -10, -10),
                patch("shore", LandformType.SHORE, 0, 0, 50, 50)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        LandformPatchSummary plain = review.landformPatches().stream()
                .filter(p -> p.landformType() == LandformType.PLAIN)
                .findFirst()
                .orElseThrow();
        PatchGroupPlan plan = new PatchGroupPlan(PatchGroupPlan.CURRENT_SCHEMA_VERSION, review.cityId(), List.of(
                new PatchGroupPlan.Group("g1", "", "中心区", "civic_core",
                        List.of(plain.mapLabel()), List.of(plain.landformPatchId()),
                        "village_hall", List.of(), "测试", "", false)));

        JsonObject response = CityPlanningEndpointHandler.handlePlanD4(
                debugRoot, runId, citySeedId,
                JsonParser.parseString(CityJson.GSON.toJson(plan.asJson())).getAsJsonObject());

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("functionZoneMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("functionZonePreview").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("qualityReport").getAsString())));
        assertFalse(response.getAsJsonArray("functionZonePatches").isEmpty());
    }

    private static LandformPatch patch(String id, LandformType type, int minX, int minZ, int maxX, int maxZ) {
        boolean water = type == LandformType.SHORE || type == LandformType.WATER;
        return new LandformPatch(id, "region_0", type,
                Math.max(1, (maxX - minX) * (maxZ - minZ) / 256),
                minX, minZ, maxX, maxZ,
                70.0, 65.0, 75.0, 1.5, water ? 8.0 : 50.0,
                water, false, 0.9, EnumSet.noneOf(PatchFlag.class));
    }
}
