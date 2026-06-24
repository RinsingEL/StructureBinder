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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void handlePlanD5_readsD4ArtifactsAndWritesPreview() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-test");
        String runId = "run_d5";
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
        Files.writeString(runDir.resolve("world_survey_manifest.json"), """
                {"config":{"cellStepBlocks":4,"dimensionId":"minecraft:overworld"}}
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "minecraft:overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 64, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain", LandformType.PLAIN, -50, -50, -10, -10),
                patch("shore", LandformType.SHORE, 0, 0, 50, 50)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        List<LandformPatchSummary> patches = review.landformPatches();
        PatchGroupPlan plan = new PatchGroupPlan(PatchGroupPlan.CURRENT_SCHEMA_VERSION, review.cityId(), List.of(
                new PatchGroupPlan.Group("g1", "", "中心区", "civic_core",
                        List.of(patches.get(0).mapLabel()), List.of(patches.get(0).landformPatchId()),
                        "village_hall", List.of(), "测试", "", false),
                new PatchGroupPlan.Group("g2", "", "水岸", "harbor_or_waterfront",
                        List.of(patches.get(1).mapLabel()), List.of(patches.get(1).landformPatchId()),
                        "dock_core", List.of(), "测试", "", false)));
        CityPlanningEndpointHandler.handlePlanD4(debugRoot, runId, citySeedId,
                JsonParser.parseString(CityJson.GSON.toJson(plan.asJson())).getAsJsonObject());

        JsonObject response = CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId);

        assertTrue(response.get("ok").getAsBoolean());
        assertFalse(response.getAsJsonObject("buildOperationPlan").getAsJsonArray("operations").isEmpty());
        assertFalse(response.getAsJsonObject("buildableAreaMap").getAsJsonArray("zones").isEmpty());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("roadIntent").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("cityPlanningPreview").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("buildableAreaMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("cityBuildabilityPreview").getAsString())));
    }

    @Test
    void handlePlanD6AndExecuteD7_writeStructureArtifactsAndPreviews() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d6d7-http-test");
        String runId = "run_d6d7";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        Path catalogPath = debugRoot.resolve(runId).resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        source.add("quality", qualityJson());
        String civicZoneId = civicZoneId(debugRoot, runId, citySeedId);

        JsonObject d6 = CityPlanningEndpointHandler.handlePlanD6(
                debugRoot,
                runId,
                citySeedId,
                source,
                structureChoicePlan(civicZoneId),
                fixedPlacementSelectionPlan());

        assertTrue(d6.get("ok").getAsBoolean());
        JsonObject d6Artifacts = d6.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("structureProfileCatalog").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("filteredStructureCatalog").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("plannedFixedPlacementMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("structurePoolMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("structureChoicePreview").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("fixedPlacementPreview").getAsString())));

        JsonObject d7 = CityPlanningEndpointHandler.handleExecuteD7(
                debugRoot,
                runId,
                citySeedId,
                12345L,
                false,
                null,
                null);

        assertTrue(d7.get("ok").getAsBoolean());
        JsonObject d7Artifacts = d7.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("startCandidateSet").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("placedStructureMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("structureGenerationTrace").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("startCandidatePreview").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("placedStructurePreview").getAsString())));
        assertTrue(d7.getAsJsonObject("placedStructureMap")
                .getAsJsonArray("placedStructures")
                .size() >= 2);
    }

    @Test
    void handleExecuteD5_requiresExplicitConfirmation() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        Path.of("run/realm_debug"), Path.of("run"),
                        "run", "city", false, null));
        assertTrue(ex.getMessage().contains("confirmWorldMutation"));
    }

    @Test
    void handleExecuteD5_requiresPlannedOperationFile() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-execute-missing-plan");
        String runId = "run_d5_execute";
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
                      "anchorBlock": {"x": 0, "z": 0}
                    }
                  ]
                }
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, Files.createTempDirectory("city-d5-server-root"),
                        runId, citySeedId, true, null));
        assertTrue(ex.getMessage().contains("build_operation_plan.json"));
    }

    @Test
    void handleExecuteD5_requiresLoadedWorld() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-execute-no-level");
        String runId = "run_d5_execute";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir.resolve("city_d5_city_test"));
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "village",
                      "theoreticalScale": "village",
                      "anchorBlock": {"x": 0, "z": 0}
                    }
                  ]
                }
                """);
        Files.writeString(runDir.resolve("city_d5_city_test").resolve("build_operation_plan.json"), """
                {
                  "schemaVersion": "build_operation_plan.v0.1",
                  "cityId": "city_test",
                  "templateDirectory": "geomantia_templates/d5",
                  "operations": []
                }
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, Files.createTempDirectory("city-d5-server-root"),
                        runId, citySeedId, true, null));
        assertTrue(ex.getMessage().contains("ServerLevel"));
    }

    private static LandformPatch patch(String id, LandformType type, int minX, int minZ, int maxX, int maxZ) {
        boolean water = type == LandformType.SHORE || type == LandformType.WATER;
        return new LandformPatch(id, "region_0", type,
                Math.max(1, (maxX - minX) * (maxZ - minZ) / 256),
                minX, minZ, maxX, maxZ,
                70.0, 65.0, 75.0, 1.5, water ? 8.0 : 50.0,
                water, false, 0.9, EnumSet.noneOf(PatchFlag.class));
    }

    private static void prepareD5Artifacts(Path debugRoot, String runId, String citySeedId) throws Exception {
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
        Files.writeString(runDir.resolve("world_survey_manifest.json"), """
                {"config":{"cellStepBlocks":4,"dimensionId":"minecraft:overworld"}}
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "minecraft:overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 64, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain", LandformType.PLAIN, -64, -64, 64, 64),
                patch("shore", LandformType.SHORE, 80, 0, 140, 60)));
        Path d3Dir = runDir.resolve("city_d3_" + citySeedId);
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        List<LandformPatchSummary> patches = review.landformPatches();
        PatchGroupPlan plan = new PatchGroupPlan(PatchGroupPlan.CURRENT_SCHEMA_VERSION, review.cityId(), List.of(
                new PatchGroupPlan.Group("g1", "", "中心区", "civic_core",
                        List.of(patches.get(0).mapLabel()), List.of(patches.get(0).landformPatchId()),
                        "village_hall", List.of(), "测试", "", false),
                new PatchGroupPlan.Group("g2", "", "水岸", "harbor_or_waterfront",
                        List.of(patches.get(1).mapLabel()), List.of(patches.get(1).landformPatchId()),
                        "dock_core", List.of(), "测试", "", false)));
        CityPlanningEndpointHandler.handlePlanD4(debugRoot, runId, citySeedId,
                JsonParser.parseString(CityJson.GSON.toJson(plan.asJson())).getAsJsonObject());
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId);
    }

    private static String civicZoneId(Path debugRoot, String runId, String citySeedId) throws Exception {
        Path zoneMapPath = debugRoot.resolve(runId)
                .resolve("city_d4_" + citySeedId)
                .resolve("function_zone_map.json");
        JsonObject zoneMap = JsonParser.parseString(Files.readString(zoneMapPath)).getAsJsonObject();
        for (com.google.gson.JsonElement elem : zoneMap.getAsJsonArray("zones")) {
            JsonObject zone = elem.getAsJsonObject();
            if ("civic_core".equals(zone.get("functionType").getAsString())) {
                return zone.get("zonePatchId").getAsString();
            }
        }
        throw new IllegalStateException("No civic_core zone in test fixture.");
    }

    private static JsonObject structureChoicePlan(String civicZoneId) {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_structure_choice_plan.v0.1",
                  "cityId": "city_test",
                  "zoneChoices": [
                    {
                      "zonePatchId": "%s",
                      "functionType": "civic_core",
                      "fixedSelections": [
                        {
                          "selectionId": "fixed_core",
                          "structureId": "minecraft:desert_pyramid",
                          "count": 1,
                          "priority": 1,
                          "failurePolicy": "block_city",
                          "reason": "temporary configured structure fixture"
                        }
                      ],
                      "variableSelections": [
                        {
                          "selectionId": "var_core",
                          "structureId": "minecraft:village_plains",
                          "targetVisibleAreaRatio": 0.25,
                          "weight": 2,
                          "reason": "temporary variable configured structure fixture"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(civicZoneId)).getAsJsonObject();
    }

    private static JsonObject fixedPlacementSelectionPlan() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_fixed_placement_selection_plan.v0.1",
                  "cityId": "city_test",
                  "selections": [
                    {
                      "selectionId": "fixed_core",
                      "landingCandidateId": "fixed_core_cand_01",
                      "reason": "choose first program candidate"
                    }
                  ]
                }
                """).getAsJsonObject();
    }

    private static String debugStructureCatalog() {
        return """
                {
                  "schemaVersion": "city_structure_profile_catalog.v0.1",
                  "catalogMode": "debug",
                  "source": {"basis": "synthetic unit-test fixture"},
                  "structures": [
                    {
                      "structureId": "minecraft:desert_pyramid",
                      "sourceProfileRef": "synthetic://unit-test/desert_pyramid",
                      "profileType": "single",
                      "sampleType": "structure_assembly",
                      "placementKind": "minecraft_place_structure",
                      "placementCommand": "place structure minecraft:desert_pyramid <x> <y> <z>",
                      "footprintMode": "fixed_footprint",
                      "functionTags": ["civic_core"],
                      "styleTags": ["debug"],
                      "placementTags": ["inside_zone"],
                      "usageTags": ["public_core"],
                      "qualityTags": ["debug_usable"],
                      "fixedFootprint": {"widthBlocks": 12, "depthBlocks": 12, "heightBlocks": 10},
                      "visibleAreaCost": 256,
                      "allowedRotations": ["NONE", "CLOCKWISE_90"],
                      "clearanceBlocks": 2
                    },
                    {
                      "structureId": "minecraft:village_plains",
                      "sourceProfileRef": "synthetic://unit-test/village_plains",
                      "profileType": "jigsaw_system",
                      "sampleType": "structure_assembly",
                      "placementKind": "minecraft_place_structure",
                      "placementCommand": "place structure minecraft:village_plains <x> <y> <z>",
                      "footprintMode": "variable_area",
                      "functionTags": ["civic_core", "residential", "market"],
                      "styleTags": ["debug"],
                      "placementTags": ["inside_zone"],
                      "usageTags": ["filler"],
                      "qualityTags": ["debug_usable"],
                      "expectedAreaRange": {
                        "minAreaBlocks": 128,
                        "maxAreaBlocks": 2048,
                        "startFootprint": {"widthBlocks": 10, "depthBlocks": 10, "heightBlocks": 8}
                      },
                      "allowedRotations": ["NONE", "CLOCKWISE_90"],
                      "clearanceBlocks": 0
                    }
                  ],
                  "quality": {
                    "passed": true,
                    "score": 100,
                    "hardBlocks": [],
                    "warnings": ["debug_catalog_semantic_mismatch_allowed"],
                    "needsReview": [],
                    "metrics": {}
                  }
                }
                """;
    }

    private static JsonObject qualityJson() {
        JsonObject quality = new JsonObject();
        quality.addProperty("passed", true);
        quality.addProperty("score", 100);
        quality.add("hardBlocks", new com.google.gson.JsonArray());
        quality.add("warnings", new com.google.gson.JsonArray());
        quality.add("needsReview", new com.google.gson.JsonArray());
        quality.add("metrics", new JsonObject());
        return quality;
    }
}
