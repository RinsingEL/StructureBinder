package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.application.CityWallPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallReservationPlanner;
import com.rinsing.geomantia.systems.city.application.dressing.CityDecorationTerrainProbe;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationWorldgenRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
        Files.createDirectories(runDir.resolve("city_d4_city_test"));
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

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());

        JsonObject response = CityPlanningEndpointHandler.handlePlanD4(
                debugRoot, runId, citySeedId,
                terraSenseSource(catalogPath),
                structureAnchorPlan(review, 1));

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureAnchorMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureAnchorPreview").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("qualityReport").getAsString())));
        assertFalse(response.getAsJsonObject("structureAnchorMap").getAsJsonArray("anchors").isEmpty());
    }

    @Test
    void handlePlanD4CandidatesAndSelect_writeCandidateAndStandardD4Artifacts() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-candidates-test");
        String runId = "run_d4_candidates";
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

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject candidates = CityPlanningEndpointHandler.handlePlanD4Candidates(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                designSlotPlan(review), null);

        assertTrue(candidates.get("ok").getAsBoolean());
        assertEquals("all_slots_tentative_order_debug",
                candidates.getAsJsonObject("anchorCandidateSet").get("planningMode").getAsString());
        JsonObject artifacts = candidates.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("anchorCandidateSet").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("anchorCandidatePreview").getAsString())));
        JsonObject firstCandidate = candidates.getAsJsonObject("anchorCandidateSet")
                .getAsJsonArray("slotCandidates").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject();
        JsonObject selectionPlan = JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_anchor_selection_plan.v0.1",
                  "cityId": "city_test",
                  "selectedCandidates": [
                    {
                      "slotId": "admin_core",
                      "candidateId": "%s",
                      "anchorId": "admin_core_01",
                      "selectionReason": "test selection"
                    }
                  ]
                }
                """.formatted(firstCandidate.get("candidateId").getAsString())).getAsJsonObject();

        JsonObject selected = CityPlanningEndpointHandler.handleSelectD4Candidates(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                selectionPlan, null, null);

        assertTrue(selected.get("ok").getAsBoolean());
        JsonObject selectedArtifacts = selected.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(selectedArtifacts.get("structureAnchorMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(selectedArtifacts.get("sourceAnchorCandidateSet").getAsString())));
        assertEquals("admin_core_01", selected.getAsJsonObject("structureAnchorMap")
                .getAsJsonArray("anchors").get(0).getAsJsonObject()
                .get("anchorId").getAsString());
    }

    @Test
    void handlePlanD4ArrayCandidates_writesArtifactsAndFeedsStandardD4() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-array-candidates-test");
        String runId = "run_d4_array_candidates";
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
                      "planningRadiusCells": 128,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 128, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain_array", LandformType.PLAIN, -220, -220, 220, 220)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject arrayCandidates = CityPlanningEndpointHandler.handlePlanD4ArrayCandidates(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                arrayCandidatePlan(review), null, null, new JsonArray());

        assertTrue(arrayCandidates.get("ok").getAsBoolean());
        JsonObject artifacts = arrayCandidates.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("arrayCandidateSet").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("arrayCandidatePreview").getAsString())));
        JsonObject firstGroup = arrayCandidates.getAsJsonObject("arrayCandidateSet")
                .getAsJsonArray("arrayCandidates").get(0).getAsJsonObject();
        JsonObject expandedPlan = firstGroup.getAsJsonObject("expandedStructureAnchorPlan");
        assertEquals(4, expandedPlan.getAsJsonArray("anchors").size());

        JsonObject d4 = CityPlanningEndpointHandler.handlePlanD4(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                expandedPlan, null);

        assertTrue(d4.get("ok").getAsBoolean());
        assertEquals(4, d4.getAsJsonObject("structureAnchorMap")
                .getAsJsonArray("anchors").size());
    }

    @Test
    void handleD4ArrayLayoutLoop_writesArtifactsRejectsStaleStateAndFinalizesD4() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-array-layout-loop-test");
        String runId = "run_d4_array_layout_loop";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "town",
                      "theoreticalScale": "town",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 128,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld",
                "city_test", "candidate_test", 0, 0,
                "town", "town", 128, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain_array", LandformType.PLAIN, -260, -260, 260, 260)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));
        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());

        JsonObject created = CityPlanningEndpointHandler.handleCreateD4ArrayLayoutLoop(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                arrayLayoutPlan(review), null, null, null);
        assertTrue(created.get("ok").getAsBoolean());
        JsonObject createArtifacts = created.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(createArtifacts.get("arrayLayoutLoopState").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(createArtifacts.get("arrayLayoutPreview").getAsString())));

        JsonObject executed = CityPlanningEndpointHandler.handleExecuteD4ArrayLayoutItem(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                created.getAsJsonObject("arrayLayoutLoopState").get("stateId").getAsString(),
                arrayLayoutItem(review, "compound_cluster", "residential_cluster", 3),
                null, null);
        assertTrue(executed.get("ok").getAsBoolean());
        assertEquals("loop_state_0001", executed.getAsJsonObject("arrayLayoutLoopState")
                .get("stateId").getAsString());
        assertEquals(1, executed.getAsJsonObject("functionalArrayZones")
                .getAsJsonArray("arrayZones").size());

        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD4ArrayLayoutItem(
                        debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                        "loop_state_0000",
                        arrayLayoutItem(review, "plaza_ring", "stale_cluster", 2),
                        null, null));
        assertTrue(stale.getMessage().contains("D4_ARRAY_LAYOUT_LOOP_STATE_STALE"));

        JsonObject finalized = CityPlanningEndpointHandler.handleFinalizeD4ArrayLayoutLoop(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                executed.getAsJsonObject("arrayLayoutLoopState").get("stateId").getAsString(),
                null, null);
        assertTrue(finalized.get("ok").getAsBoolean());
        assertEquals(3, finalized.getAsJsonObject("structureAnchorMap")
                .getAsJsonArray("anchors").size());
        JsonObject finalArtifacts = finalized.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(finalArtifacts.get("structureAnchorMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(finalArtifacts.get("functionalArrayZones").getAsString())));
    }

    @Test
    void handleD4ArrayExpansionCandidatesStayTentativeUntilSelected() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-array-expansion-test");
        String runId = "run_d4_array_expansion";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {"citySeeds":[{"citySeedId":"city_test","realmId":"realm_test","role":"town",
                "theoreticalScale":"town","anchorBlock":{"x":0,"z":0},"planningRadiusCells":128,
                "candidateId":"candidate_test"}]}
                """);
        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld", "city_test", "candidate_test", 0, 0,
                "town", "town", 128, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain_expansion", LandformType.PLAIN, -260, -260, 260, 260)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"), CityJson.GSON.toJson(review.asJson()));
        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        Path occupiedMapPath = runDir.resolve("manor_occupied_anchor_map.json");
        Files.writeString(occupiedMapPath, """
                {"anchors":[{"anchorId":"manor_core","collisionEnvelope":{"minX":-48,"minZ":-48,"maxX":48,"maxZ":48}}]}
                """);
        JsonObject occupiedSource = new JsonObject();
        occupiedSource.addProperty("anchorMapPath", occupiedMapPath.toString());

        JsonObject created = CityPlanningEndpointHandler.handleCreateD4ArrayLayoutLoop(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath), arrayLayoutPlanV04(),
                null, null, occupiedSource);
        String stateId = created.getAsJsonObject("arrayLayoutLoopState").get("stateId").getAsString();
        JsonObject request = arrayExpansionRequest(review);

        JsonObject space = CityPlanningEndpointHandler.handleQueryD4ArrayExpansionSpace(
                debugRoot, runId, citySeedId, stateId, request, null);
        assertTrue(space.get("ok").getAsBoolean());
        assertTrue(Files.exists(debugRoot.resolve(space.getAsJsonObject("artifacts")
                .get("arrayExpansionSpace").getAsString())));

        JsonObject globalRequest = JsonParser.parseString("""
                {
                  "newFunctionalArea": true,
                  "candidateCount": 3,
                  "minCandidateCount": 3,
                  "nextArrayLayoutPlanItem": {
                    "arrayId": "global_residential_theme",
                    "plannerType": "compound_cluster",
                    "role": "residential",
                    "fillPool": [{"structureId": "minecraft:desert_pyramid", "weight": 1}],
                    "countPolicy": {"minCount": 2, "targetCount": 2, "maxCount": 2},
                    "variantSelectionMode": "round_robin"
                  }
                }
                """).getAsJsonObject();
        JsonObject globalSpace = CityPlanningEndpointHandler.handleQueryD4ArrayExpansionSpace(
                debugRoot, runId, citySeedId, stateId, globalRequest, null);
        JsonObject globalSpaceResult = JsonParser.parseString(Files.readString(debugRoot.resolve(globalSpace
                .getAsJsonObject("artifacts").get("arrayExpansionSpace").getAsString()))).getAsJsonObject();
        assertEquals("explicit_global_new_functional_area", globalSpaceResult.get("searchScope").getAsString());
        assertTrue(globalSpaceResult.get("selectedGlobalPatchRequired").getAsBoolean());
        String globalPatchRef = globalSpaceResult.getAsJsonArray("globalPatchCandidates").get(0).getAsJsonObject()
                .get("patchRef").getAsString();
        IllegalArgumentException globalSelectionRequired = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD4ArrayExpansionCandidates(
                        debugRoot, runId, citySeedId, terraSenseSource(catalogPath), stateId,
                        globalRequest, null, null));
        assertTrue(globalSelectionRequired.getMessage().contains("D4_ARRAY_LAYOUT_GLOBAL_PATCH_SELECTION_REQUIRED"));
        globalRequest.addProperty("selectedGlobalPatchRef", globalPatchRef);
        JsonObject globalPlanned = CityPlanningEndpointHandler.handlePlanD4ArrayExpansionCandidates(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath), stateId, globalRequest, null, null);
        assertTrue(globalPlanned.get("ok").getAsBoolean());
        assertEquals(globalPatchRef, globalPlanned.getAsJsonObject("arrayExpansionCandidateSet")
                .getAsJsonArray("arrayCandidates").get(0).getAsJsonObject()
                .get("selectedGlobalPatchRef").getAsString());

        JsonObject planned = CityPlanningEndpointHandler.handlePlanD4ArrayExpansionCandidates(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath), stateId, request, null, null);
        assertTrue(planned.get("ok").getAsBoolean());
        assertEquals(3, planned.getAsJsonObject("arrayExpansionCandidateSet")
                .getAsJsonArray("arrayCandidates").size());
        assertEquals(stateId, planned.getAsJsonObject("arrayExpansionCandidateSet")
                .get("sourceStateId").getAsString());
        JsonObject candidateArtifacts = planned.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(candidateArtifacts.get("arrayExpansionCandidateSet").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(candidateArtifacts.get("arrayExpansionCandidatePreview").getAsString())));

        String candidateId = planned.getAsJsonObject("arrayExpansionCandidateSet").getAsJsonArray("arrayCandidates")
                .get(0).getAsJsonObject().get("candidateId").getAsString();
        JsonObject selected = CityPlanningEndpointHandler.handleSelectD4ArrayExpansionCandidate(
                debugRoot, runId, citySeedId, stateId, candidateId, false, "AI 选择东侧阵列", null, null, null);
        assertTrue(selected.get("ok").getAsBoolean());
        assertEquals(1, selected.getAsJsonObject("arrayLayoutLoopState").get("iteration").getAsInt());
        assertEquals("ai_or_human_selected", selected.getAsJsonObject("executionTrace").getAsJsonArray("items")
                .get(0).getAsJsonObject().get("decisionSource").getAsString());
    }

    @Test
    void handleD4DesignLoopState_writesReadsAppendsTwoRoundsAndWriteBack() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-design-loop-state-test");
        String runId = "run_d4_design_loop_state";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "town",
                      "theoreticalScale": "town",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 128,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);
        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld",
                "city_test", "candidate_test", 0, 0,
                "town", "town", 128, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain_design", LandformType.PLAIN, -160, -160, 160, 160)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        JsonObject created = CityPlanningEndpointHandler.handleCreateD4DesignLoopState(
                debugRoot, runId, citySeedId, new JsonObject(), null);
        assertTrue(created.get("ok").getAsBoolean());
        assertEquals("d4_design_loop_state_0000", created.getAsJsonObject("designLoopState")
                .get("stateId").getAsString());
        JsonObject createArtifacts = created.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(createArtifacts.get("designLoopState").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(createArtifacts.get("designLoopOccupiedField").getAsString())));

        JsonObject firstRound = JsonParser.parseString("""
                {
                  "roundId": "round_01",
                  "anchors": [
                    {
                      "anchorId": "admin_core",
                      "structureId": "minecraft:desert_pyramid",
                      "collisionEnvelope": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8},
                      "safetyEnvelope": {"minX": -80, "minZ": -80, "maxX": 80, "maxZ": 80}
                    }
                  ],
                  "functionZones": [
                    {"zoneId": "civic_core", "blockBounds": {"minX": -16, "minZ": -16, "maxX": 16, "maxZ": 16}}
                  ],
                  "arrayZones": {
                    "arrayZones": [
                      {"arrayId": "core_ring", "blockBounds": {"minX": -24, "minZ": -24, "maxX": 24, "maxZ": 24}}
                    ]
                  },
                  "nextAiContextSummary": {"summary": "admin core placed"}
                }
                """).getAsJsonObject();
        JsonObject first = CityPlanningEndpointHandler.handleAppendD4DesignLoopRound(
                debugRoot, runId, citySeedId,
                created.getAsJsonObject("designLoopState").get("stateId").getAsString(),
                firstRound, null);
        JsonObject firstOccupied = first.getAsJsonObject("occupiedField")
                .getAsJsonArray("occupied").get(0).getAsJsonObject();
        assertEquals("collisionEnvelope", firstOccupied.get("envelopeSource").getAsString());
        assertEquals(8, firstOccupied.getAsJsonObject("blockBounds").get("maxX").getAsInt());
        assertFalse(first.getAsJsonObject("designLoopState")
                .getAsJsonArray("anchors").get(0).getAsJsonObject().has("safetyEnvelope"));

        JsonObject read = CityPlanningEndpointHandler.handleReadD4DesignLoopState(
                debugRoot, runId, citySeedId, null);
        assertEquals("d4_design_loop_state_0001", read.getAsJsonObject("designLoopState")
                .get("stateId").getAsString());

        JsonObject secondRound = JsonParser.parseString("""
                {
                  "roundId": "round_02",
                  "anchors": [
                    {
                      "anchorId": "market_01",
                      "structureId": "minecraft:jungle_pyramid",
                      "collisionEnvelope": {"minX": 40, "minZ": 40, "maxX": 58, "maxZ": 58}
                    }
                  ],
                  "nextAiContextSummary": {"summary": "market can avoid admin occupied field"}
                }
                """).getAsJsonObject();
        JsonObject second = CityPlanningEndpointHandler.handleAppendD4DesignLoopRound(
                debugRoot, runId, citySeedId,
                read.getAsJsonObject("designLoopState").get("stateId").getAsString(),
                secondRound, null);
        JsonArray occupied = second.getAsJsonObject("occupiedField").getAsJsonArray("occupied");
        assertEquals(2, occupied.size());
        assertEquals(8, occupied.get(0).getAsJsonObject()
                .getAsJsonObject("blockBounds").get("maxX").getAsInt());
        assertEquals(40, occupied.get(1).getAsJsonObject()
                .getAsJsonObject("blockBounds").get("minX").getAsInt());
        assertEquals(2, second.getAsJsonObject("nextAiContextSummary")
                .get("occupiedEnvelopeCount").getAsInt());

        JsonObject written = CityPlanningEndpointHandler.handleWriteD4DesignLoopState(
                debugRoot, runId, citySeedId,
                second.getAsJsonObject("designLoopState").get("stateId").getAsString(),
                second.getAsJsonObject("designLoopState"));
        assertTrue(written.get("ok").getAsBoolean());
        JsonObject writeArtifacts = written.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(writeArtifacts
                .get("designLoopNextAiContextSummary").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(writeArtifacts
                .get("designLoopExecutionTrace").getAsString())));

        IllegalArgumentException d5 = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId));
        assertTrue(d5.getMessage().contains("D4 artifacts not found"));

        IllegalArgumentException d6 = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD6(debugRoot, runId, citySeedId, null, null));
        assertTrue(d6.getMessage().contains("D4 structure_anchor_map.json not found"));

        IllegalArgumentException dressing = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanCityDressing(
                        debugRoot, runId, citySeedId, dressingBrushPlan()));
        assertTrue(dressing.getMessage().contains("CITY_DRESSING_LEGACY_SCHEMA_REMOVED"));

        IllegalArgumentException executeD5 = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, Files.createTempDirectory("city-d4-loop-submit-server-root"),
                        runId, citySeedId, true, null, "auto"));
        assertTrue(executeD5.getMessage().contains("D4 structure_anchor_map.json not found"));

        IllegalArgumentException executeD7 = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD7(
                        debugRoot, runId, citySeedId, 12345L, false, false, null, null));
        assertTrue(executeD7.getMessage().contains("D6 artifacts not found"));
    }

    @Test
    void handlePlanD4StructureClusterGroups_writesGroupArtifactsAndSelectsWholeGroup() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-cluster-groups-test");
        String runId = "run_d4_cluster_groups";
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
                      "planningRadiusCells": 128,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 128, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain_cluster", LandformType.PLAIN, -220, -220, 220, 220)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject planned = CityPlanningEndpointHandler.handlePlanD4StructureClusterGroups(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                designSlotPlan(review), null, null, null, null);

        assertTrue(planned.get("ok").getAsBoolean());
        JsonObject artifacts = planned.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureClusterGroupCandidateSet").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureClusterGroupCandidatesPreview").getAsString())));
        JsonObject firstGroup = planned.getAsJsonObject("structureClusterGroupCandidateSet")
                .getAsJsonArray("groupCandidates").get(0).getAsJsonObject();
        assertEquals(2, firstGroup.getAsJsonArray("items").size());

        JsonObject selected = CityPlanningEndpointHandler.handleSelectD4StructureClusterGroup(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                firstGroup.get("groupCandidateId").getAsString(), null, null);

        assertTrue(selected.get("ok").getAsBoolean());
        assertTrue(selected.getAsJsonObject("artifacts").has("sourceStructureClusterGroupCandidateSet"));
        assertEquals(2, selected.getAsJsonObject("structureAnchorMap")
                .getAsJsonArray("anchors").size());
    }

    @Test
    void handleD4CandidateSession_writesSequentialArtifactsAndFinalizesD4() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-session-test");
        String runId = "run_d4_session";
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
                patch("shore", LandformType.SHORE, 0, 0, 50, 50),
                patch("offscreen_plain", LandformType.PLAIN, 1000, 1000, 1040, 1040)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject created = CityPlanningEndpointHandler.handleCreateD4CandidateSession(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                designSlotPlan(review), null, "session_test");
        assertTrue(created.get("ok").getAsBoolean());
        assertEquals("admin_core", created.get("currentSlotId").getAsString());
        assertTrue(Files.exists(debugRoot.resolve(created.getAsJsonObject("artifacts")
                .get("d4CandidateSession").getAsString())));

        JsonObject next = CityPlanningEndpointHandler.handlePlanD4NextCandidates(
                debugRoot, runId, citySeedId, null);
        assertTrue(next.get("ok").getAsBoolean());
        assertFalse(next.getAsJsonObject("artifacts").has("structureClusterCandidateOverview"));
        JsonObject firstCandidate = next.getAsJsonObject("slotCandidateSet")
                .getAsJsonArray("slotCandidates").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject();
        JsonObject selected = CityPlanningEndpointHandler.handleSelectD4Candidate(
                debugRoot, runId, citySeedId, "session_test", "admin_core",
                firstCandidate.get("candidateId").getAsString(), "admin_core_01",
                "test first pick", true);
        assertTrue(selected.get("ok").getAsBoolean());
        assertEquals("deferred_to_d6",
                selected.getAsJsonObject("quickPreflightReport").get("status").getAsString());
        assertEquals("residential_01", selected.get("nextSlotId").getAsString());

        JsonObject nextSecond = CityPlanningEndpointHandler.handlePlanD4NextCandidates(
                debugRoot, runId, citySeedId, null);
        JsonObject secondCandidate = nextSecond.getAsJsonObject("slotCandidateSet")
                .getAsJsonArray("slotCandidates").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject();
        CityPlanningEndpointHandler.handleSelectD4Candidate(
                debugRoot, runId, citySeedId, "session_test", "residential_01",
                secondCandidate.get("candidateId").getAsString(), "residential_01",
                "test second pick", false);

        JsonObject finalized = CityPlanningEndpointHandler.handleFinalizeD4CandidateSession(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath), null, "session_test");
        assertTrue(finalized.get("ok").getAsBoolean());
        assertEquals("finalized", finalized.get("status").getAsString());
        JsonObject artifacts = finalized.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureAnchorMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("d4DesignTimeReport").getAsString())));
        assertEquals(2, finalized.getAsJsonObject("structureAnchorMap")
                .getAsJsonArray("anchors").size());
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

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        CityPlanningEndpointHandler.handlePlanD4(debugRoot, runId, citySeedId,
                terraSenseSource(catalogPath), structureAnchorPlan(review, 2));

        JsonObject response = CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId);

        assertTrue(response.get("ok").getAsBoolean());
        assertTrue(response.getAsJsonObject("buildOperationPlan").getAsJsonArray("operations").isEmpty());
        assertEquals("d7_after_worldgen_ledger",
                response.getAsJsonObject("reservationMaskPlan").get("roadPlanningStage").getAsString());
        assertFalse(response.getAsJsonObject("reservationMaskPlan").getAsJsonArray("noVegetationMask").isEmpty());
        assertTrue(response.getAsJsonObject("reservationMaskPlan").has("wallReservationPlan"));
        assertEquals("city_wall_reservation_plan.v0.2",
                response.getAsJsonObject("wallReservationPlan").get("schemaVersion").getAsString());
        assertFalse(response.getAsJsonObject("wallReservationPlan").getAsJsonArray("wallCorridorMask").isEmpty());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("reservationMaskPlan").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("wallReservationPlan").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("roadAccessPlan").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("reservationMaskPreview").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("wallReservationPreview").getAsString())));
    }

    @Test
    void handlePlanD6AndExecuteD7_writeStructureArtifactsAndPreviews() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d6d7-http-test");
        String runId = "run_d6d7";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        JsonObject d6 = CityPlanningEndpointHandler.handlePlanD6(
                debugRoot,
                runId,
                citySeedId,
                null,
                null);

        assertTrue(d6.get("ok").getAsBoolean());
        assertEquals("worldgen_time_planned_registry",
                d6.getAsJsonObject("structureMaterializationPlan").get("dryRunMode").getAsString());
        assertFalse(d6.getAsJsonObject("structureMaterializationPlan")
                .getAsJsonArray("plannedWorldgenStructures")
                .isEmpty());
        JsonObject d6Artifacts = d6.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("structureMaterializationPlan").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("placedStructureLedger").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("structureMaterializationTrace").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("structureMaterializationPreview").getAsString())));

        CityPlanningEndpointHandler.handleExecuteD5(
                debugRoot, Files.createTempDirectory("city-d6d7-server-root"),
                runId, citySeedId, true, null, "auto");

        JsonObject d7 = CityPlanningEndpointHandler.handleExecuteD7(
                debugRoot,
                runId,
                citySeedId,
                12345L,
                false,
                false,
                null,
                null);

        assertTrue(d7.get("ok").getAsBoolean());
        assertTrue(d7.get("worldgenPlacementMode").getAsBoolean());
        JsonObject d7Artifacts = d7.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("placedStructureLedger").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("structureMaterializationTrace").getAsString())));
        Path placedPreview = debugRoot.resolve(d7Artifacts.get("placedStructurePreview").getAsString());
        assertTrue(Files.exists(placedPreview));
        assertTrue(d7Artifacts.has("sourceD3Package"));
        assertPreviewHasPatchBackdrop(placedPreview);
        assertEquals(0, d7.getAsJsonObject("placedStructureLedger")
                .getAsJsonArray("placedStructures")
                .size());
        assertTrue(d7.getAsJsonObject("structureMaterializationTrace")
                .getAsJsonObject("waitingSummary")
                .has("WAITING_FOR_WORLDGEN"));
    }

    @Test
    void handlePlanCityDressingWritesV02ArtifactsAndAvoidsHardObstacles() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-dressing-http-test");
        Path catalogRoot = createDecorationCatalog();
        String runId = "run_city_dressing";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        addPatchMemberCells(debugRoot, runId, citySeedId, "plain");
        CityPlanningEndpointHandler.handlePlanD6(debugRoot, runId, citySeedId, null, null);
        ensureTwoPlannedRoadEndpoints(debugRoot, runId, citySeedId);
        JsonObject catalog = CityPlanningEndpointHandler.handleQueryDecorationCatalog(catalogRoot);
        String styleProfileHash = catalog.getAsJsonArray("styleProfiles").get(0).getAsJsonObject()
                .get("styleProfileHash").getAsString();

        JsonObject dressing = CityPlanningEndpointHandler.handlePlanCityDressing(
                debugRoot, runId, citySeedId,
                decorationProgramPlan(catalog.get("catalogHash").getAsString(), styleProfileHash,
                        "plain", "market_stall"),
                catalogRoot);

        assertTrue(dressing.get("ok").getAsBoolean());
        JsonObject artifacts = dressing.getAsJsonObject("artifacts");
        for (String key : List.of("decorationProgramPlan", "compiledDecorationProgramPlan",
                "decorationSlotProjection", "qualityReport", "planningTrace", "decorationPreviewIndex",
                "styleResolution", "planningComplete")) {
            assertTrue(Files.exists(debugRoot.resolve(artifacts.get(key).getAsString())), key);
        }
        JsonObject previewIndex = dressing.getAsJsonObject("decorationPreviewIndex");
        assertEquals("city_decoration_preview_index.v0.2",
                previewIndex.get("schemaVersion").getAsString());
        assertTrue(Files.exists(Path.of(previewIndex.getAsJsonArray("previews").get(0).getAsJsonObject()
                .get("path").getAsString())));
        assertFalse(artifacts.has("dressingSurfaceOperationPlan"));
        assertFalse(artifacts.has("dressingDecorationPlacementPlan"));
        assertEquals("market_stall", dressing.getAsJsonObject("decorationProgramPlan")
                .getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonObject("contentPalette").getAsJsonArray("slots").get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject().get("contentRef").getAsString());
        assertEquals("geomantia:test_bench", dressing.getAsJsonObject("compiledDecorationProgramPlan")
                .getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonObject("contentPalette").getAsJsonArray("slots").get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject().get("contentRef").getAsString());
        assertEquals("city_decoration_style_resolution.v0.1", dressing.getAsJsonObject("styleResolution")
                .get("schemaVersion").getAsString());

        JsonArray obstacles = dressing.getAsJsonObject("compiledDecorationProgramPlan")
                .getAsJsonArray("hardObstacles");
        assertTrue(obstacles.asList().stream().anyMatch(element -> element.getAsJsonObject()
                .get("obstacleType").getAsString().startsWith("structure_")));
        assertTrue(obstacles.asList().stream().anyMatch(element -> element.getAsJsonObject()
                .get("obstacleType").getAsString().equals("wall_corridor")));
        assertTrue(obstacles.asList().stream().anyMatch(element -> element.getAsJsonObject()
                .get("obstacleType").getAsString().equals("planned_road_corridor")));
        for (JsonElement slotElement : dressing.getAsJsonObject("slotProjection").getAsJsonArray("slots")) {
            JsonObject anchor = slotElement.getAsJsonObject().getAsJsonObject("worldAnchor");
            int x = anchor.get("x").getAsInt();
            int z = anchor.get("z").getAsInt();
            for (JsonElement obstacleElement : obstacles) {
                assertFalse(bounds(obstacleElement.getAsJsonObject().getAsJsonObject("blockBounds")).contains(x, z),
                        "projected slot must not enter a compiled hard obstacle");
            }
        }

        Path serverRoot = Files.createTempDirectory("city-decoration-activation-root");
        Path compiledPath = debugRoot.resolve(artifacts.get("compiledDecorationProgramPlan").getAsString());
        JsonObject compiledJson = JsonParser.parseString(Files.readString(compiledPath)).getAsJsonObject();
        JsonObject wrongCity = compiledJson.deepCopy();
        wrongCity.addProperty("cityId", "wrong_city");
        Files.writeString(compiledPath, CityJson.GSON.toJson(wrongCity));
        IllegalArgumentException wrongCityFailure = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, serverRoot, runId, citySeedId, true, null, "auto", catalogRoot));
        assertTrue(wrongCityFailure.getMessage().contains("CITY_DECORATION_CITY_ID_MISMATCH"));
        assertFalse(Files.exists(CityReservationMaskRegistry.plannedRegistryPath(serverRoot)),
                "decoration preflight must fail before the structure registry is activated");
        Files.writeString(compiledPath, CityJson.GSON.toJson(compiledJson));

        Path completePath = debugRoot.resolve(artifacts.get("planningComplete").getAsString());
        JsonObject completionJson = JsonParser.parseString(Files.readString(completePath)).getAsJsonObject();
        JsonObject wrongCompletion = completionJson.deepCopy();
        wrongCompletion.addProperty("catalogHash", "stale_catalog");
        Files.writeString(completePath, CityJson.GSON.toJson(wrongCompletion));
        IllegalArgumentException completionFailure = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, serverRoot, runId, citySeedId, true, null, "auto", catalogRoot));
        assertTrue(completionFailure.getMessage().contains("CITY_DECORATION_PLAN_INCOMPLETE"));
        assertFalse(Files.exists(CityReservationMaskRegistry.plannedRegistryPath(serverRoot)),
                "completion preflight must fail before the structure registry is activated");
        Files.writeString(completePath, CityJson.GSON.toJson(completionJson));

        JsonObject activation = CityPlanningEndpointHandler.handleExecuteD5(
                debugRoot, serverRoot, runId, citySeedId, true, null, "auto", catalogRoot);
        assertTrue(activation.get("decorationWorldgenMode").getAsBoolean());
        assertEquals(1, activation.getAsJsonObject("activeDecorationSummary")
                .get("activePlanCount").getAsInt());
        assertTrue(Files.exists(CityDecorationWorldgenRegistry.activePlansPath(serverRoot)));
        assertTrue(Files.exists(CityDecorationWorldgenRegistry.worldgenLedgerPath(serverRoot)));
        assertEquals(CityDecorationWorldgenRegistry.ACTIVE_SCHEMA,
                JsonParser.parseString(Files.readString(CityDecorationWorldgenRegistry.activePlansPath(serverRoot)))
                        .getAsJsonObject().get("schemaVersion").getAsString());
        assertEquals(CityDecorationWorldgenRegistry.LEDGER_SCHEMA,
                JsonParser.parseString(Files.readString(CityDecorationWorldgenRegistry.worldgenLedgerPath(serverRoot)))
                        .getAsJsonObject().get("schemaVersion").getAsString());

        Files.delete(completePath);
        IllegalArgumentException incomplete = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, serverRoot, runId, citySeedId, true, null, "auto", catalogRoot));
        assertTrue(incomplete.getMessage().contains("CITY_DECORATION_PLAN_INCOMPLETE"));
        Files.delete(compiledPath);
        JsonObject deactivation = CityPlanningEndpointHandler.handleExecuteD5(
                debugRoot, serverRoot, runId, citySeedId, true, null, "auto", catalogRoot);
        assertFalse(deactivation.get("decorationWorldgenMode").getAsBoolean());
        assertEquals(0, deactivation.getAsJsonObject("activeDecorationSummary")
                .get("activePlanCount").getAsInt());

        Path legacyDirectory = debugRoot.resolve(runId).resolve("city_dressing_" + citySeedId);
        Files.createDirectories(legacyDirectory);
        Files.writeString(legacyDirectory.resolve("preview.png"), "legacy");
        IllegalArgumentException legacyArtifact = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, serverRoot, runId, citySeedId, true, null, "auto", catalogRoot));
        assertTrue(legacyArtifact.getMessage().contains("CITY_DRESSING_LEGACY_SCHEMA_REMOVED"));
    }

    @Test
    void decorationTerrainProbeReadsExistingArtifactsWithoutWritingOrGeneratingUnavailableTerrain() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-decoration-terrain-probe-test");
        Path catalogRoot = createDecorationCatalog();
        String runId = "run_city_decoration_terrain";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        addPatchMemberCells(debugRoot, runId, citySeedId, "plain");
        CityPlanningEndpointHandler.handlePlanD6(debugRoot, runId, citySeedId, null, null);
        JsonObject catalog = CityPlanningEndpointHandler.handleQueryDecorationCatalog(catalogRoot);
        String styleProfileHash = catalog.getAsJsonArray("styleProfiles").get(0).getAsJsonObject()
                .get("styleProfileHash").getAsString();
        CityPlanningEndpointHandler.handlePlanCityDressing(debugRoot, runId, citySeedId,
                decorationProgramPlan(catalog.get("catalogHash").getAsString(), styleProfileHash,
                        "plain", "market_stall"), catalogRoot);

        Path decorationDirectory = debugRoot.resolve(runId).resolve("city_decoration_" + citySeedId);
        List<String> before = relativeFiles(decorationDirectory);
        JsonObject response = CityPlanningEndpointHandler.handleProbeDecorationTerrain(debugRoot, runId, citySeedId,
                (worldX, worldZ) -> CityDecorationTerrainProbe.Sample.unavailable());

        assertTrue(response.get("ok").getAsBoolean());
        assertTrue(response.get("loadedChunksOnly").getAsBoolean());
        assertFalse(response.get("mutatesWorld").getAsBoolean());
        assertEquals("await_chunk_load", response.getAsJsonObject("overall")
                .get("activationRecommendation").getAsString());
        assertTrue(response.getAsJsonObject("overall").get("unavailableSlotCount").getAsInt() > 0);
        assertEquals(before, relativeFiles(decorationDirectory),
                "terrain probing must not publish an artifact or modify the compiled plan");
        assertFalse(Files.exists(decorationDirectory.resolve("city_decoration_terrain_probe.json")));
    }

    @Test
    void decorationCatalogQueryAndPlanningFailuresAreExplicit() throws Exception {
        Path catalogRoot = createDecorationCatalog();
        JsonObject query = CityPlanningEndpointHandler.handleQueryDecorationCatalog(catalogRoot);
        assertEquals("city_decoration_catalog_query.v0.2", query.get("schemaVersion").getAsString());
        assertEquals(1, query.getAsJsonArray("contents").size());
        JsonObject summary = query.getAsJsonArray("contents").get(0).getAsJsonObject();
        assertEquals("geomantia:test_bench", summary.get("contentRef").getAsString());
        assertEquals("above_surface", summary.get("placementMode").getAsString());
        assertFalse(summary.has("template"));
        assertFalse(summary.has("nbtFile"));
        JsonObject styleProfile = query.getAsJsonArray("styleProfiles").get(0).getAsJsonObject();
        assertEquals("forest_village", styleProfile.get("styleProfileId").getAsString());
        assertTrue(styleProfile.get("styleProfileHash").getAsString().startsWith("sha256:"));
        assertEquals("market_stall", styleProfile.getAsJsonArray("semanticRefs").get(0).getAsString());

        Path debugRoot = Files.createTempDirectory("city-decoration-errors-test");
        String runId = "run_city_decoration_errors";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        addPatchMemberCells(debugRoot, runId, citySeedId, "plain");
        CityPlanningEndpointHandler.handlePlanD6(debugRoot, runId, citySeedId, null, null);
        String hash = query.get("catalogHash").getAsString();
        String styleProfileHash = styleProfile.get("styleProfileHash").getAsString();

        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanCityDressing(debugRoot, runId, citySeedId,
                        decorationProgramPlan("stale_hash", styleProfileHash, "plain", "market_stall"), catalogRoot));
        assertTrue(mismatch.getMessage().contains("CITY_DECORATION_CATALOG_HASH_MISMATCH"));

        IllegalArgumentException unknownPatch = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanCityDressing(debugRoot, runId, citySeedId,
                        decorationProgramPlan(hash, styleProfileHash, "missing_patch", "market_stall"), catalogRoot));
        assertTrue(unknownPatch.getMessage().contains("CITY_DECORATION_TARGET_PATCH_UNKNOWN"));

        IllegalArgumentException unknownContent = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanCityDressing(debugRoot, runId, citySeedId,
                        decorationProgramPlan(hash, styleProfileHash, "plain", "missing_semantic"), catalogRoot));
        assertTrue(unknownContent.getMessage().contains("CITY_DECORATION_STYLE_SEMANTIC_REF_UNKNOWN"));

        IllegalArgumentException legacy = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanCityDressing(debugRoot, runId, citySeedId,
                        dressingBrushPlan(), catalogRoot));
        assertTrue(legacy.getMessage().contains("CITY_DRESSING_LEGACY_SCHEMA_REMOVED"));
    }

    @Test
    void structureCatalogQueryResolvesVocabularyTagsFiltersAndStaysReadOnly() throws Exception {
        Path baseDirectory = Files.createTempDirectory("city-structure-catalog-query-test");
        Path catalogPath = baseDirectory.resolve("debug_structure_profile_catalog.json");
        Path vocabularyPath = baseDirectory.resolve("StructureVocabulary.snapshot.json");
        Files.writeString(catalogPath, """
                {
                  "catalogMode": "debug",
                  "structures": [
                    {
                      "structureId": "test:windmill",
                      "sourceProfileRef": "terrasense://windmill",
                      "profileType": "single",
                      "footprintMode": "fixed_footprint",
                      "functionTerms": ["function.agriculture"],
                      "styleTerms": ["style.medieval"],
                      "usageTerms": ["usage.production"],
                      "qualityTerms": ["quality.usable"],
                      "fixedFootprint": {"widthBlocks": 11, "depthBlocks": 9, "heightBlocks": 17},
                      "allowedRotations": ["NONE", "CLOCKWISE_90"],
                      "clearanceBlocks": 2
                    },
                    {
                      "structureId": "test:fishing_boat",
                      "sourceProfileRef": "terrasense://fishing_boat",
                      "profileType": "single",
                      "footprintMode": "fixed_footprint",
                      "functionTerms": ["function.harbor"],
                      "styleTerms": ["style.medieval"],
                      "usageTerms": ["usage.commercial"],
                      "qualityTerms": ["quality.usable"],
                      "fixedFootprint": {"widthBlocks": 7, "depthBlocks": 19, "heightBlocks": 8},
                      "allowedRotations": ["NONE"],
                      "clearanceBlocks": 1
                    }
                  ]
                }
                """);
        Files.writeString(vocabularyPath, """
                {
                  "terms": [
                    {"term_id": "function.agriculture", "label": "农业", "aliases": ["farm"], "status": "approved"},
                    {"term_id": "function.harbor", "label": "港口", "aliases": ["harbor"], "status": "approved"},
                    {"term_id": "style.medieval", "label": "中世纪", "aliases": ["medieval"], "status": "approved"}
                  ]
                }
                """);
        JsonObject source = new JsonObject();
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        source.addProperty("vocabularySnapshotPath", vocabularyPath.toString());

        JsonObject query = JsonParser.parseString("""
                {
                  "allOfTerms": ["中世纪"],
                  "anyOfTerms": ["farm", "港口"],
                  "excludeTerms": ["港口"],
                  "limit": 10
                }
                """).getAsJsonObject();
        JsonObject response = CityPlanningEndpointHandler.handleQueryStructureCatalog(baseDirectory, source, query);
        assertTrue(response.get("ok").getAsBoolean());
        assertTrue(response.get("readOnly").getAsBoolean());
        assertTrue(response.getAsJsonObject("vocabulary").get("available").getAsBoolean());
        assertEquals(1, response.get("matchedCount").getAsInt());
        JsonObject windmill = response.getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals("test:windmill", windmill.get("structureId").getAsString());
        assertEquals("style.medieval", windmill.getAsJsonArray("matchedCanonicalTerms").get(0).getAsString());
        assertEquals("function.agriculture", windmill.getAsJsonArray("matchedCanonicalTerms").get(1).getAsString());
        assertEquals(11, windmill.getAsJsonObject("hardFacts").getAsJsonObject("fixedFootprint")
                .get("widthBlocks").getAsInt());
        assertEquals("debug", windmill.getAsJsonObject("profileSource").get("catalogMode").getAsString());

        JsonObject canonicalQuery = JsonParser.parseString("""
                {"allOfTerms": ["function.harbor"]}
                """).getAsJsonObject();
        JsonObject canonicalResponse = CityPlanningEndpointHandler.handleQueryStructureCatalog(
                baseDirectory, source, canonicalQuery);
        assertEquals("test:fishing_boat", canonicalResponse.getAsJsonArray("candidates").get(0)
                .getAsJsonObject().get("structureId").getAsString());

        JsonObject chineseLabelQuery = JsonParser.parseString("""
                {"allOfTerms": ["农业"]}
                """).getAsJsonObject();
        JsonObject chineseLabelResponse = CityPlanningEndpointHandler.handleQueryStructureCatalog(
                baseDirectory, source, chineseLabelQuery);
        assertEquals("test:windmill", chineseLabelResponse.getAsJsonArray("candidates").get(0)
                .getAsJsonObject().get("structureId").getAsString());

        IllegalArgumentException unresolved = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleQueryStructureCatalog(baseDirectory, source,
                        JsonParser.parseString("{\"allOfTerms\":[\"未定义标签\"]}").getAsJsonObject()));
        assertTrue(unresolved.getMessage().contains("CITY_STRUCTURE_QUERY_TERM_UNRESOLVED"));
    }

    @Test
    void handleExecuteD7BuildsRoadPlanFromWorldgenLedgerActualFootprints() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d7-ledger-road-test");
        Path serverRoot = Files.createTempDirectory("city-d7-ledger-road-server-root");
        String runId = "run_d7_ledger_road";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        JsonObject d6 = CityPlanningEndpointHandler.handlePlanD6(
                debugRoot, runId, citySeedId, null, null);
        CityPlanningEndpointHandler.handleExecuteD5(debugRoot, serverRoot, runId, citySeedId, true, null,
                "worldedit_debug");
        recordPlannedWorldgenPlacementsFromRegistry();

        JsonObject d7 = CityPlanningEndpointHandler.handleExecuteD7(
                debugRoot, runId, citySeedId, 12345L,
                true, false, null, null);

        JsonObject roadReport = d7.getAsJsonObject("deferredRoadPostprocessReport");
        assertEquals("worldgen_ledger_actual_footprint",
                roadReport.get("roadPostprocessSource").getAsString());
        assertEquals(3, roadReport.get("roadAvoidanceMarginBlocks").getAsInt());
        assertEquals("actual_footprint_union", roadReport.get("boundarySource").getAsString());
        JsonArray operations = roadReport.getAsJsonObject("generatedBuildOperationPlan")
                .getAsJsonArray("operations");
        assertFalse(operations.isEmpty());
        assertEquals(d6.getAsJsonObject("structureMaterializationPlan")
                        .getAsJsonArray("plannedWorldgenStructures").size(),
                d7.getAsJsonObject("placedStructureLedger").getAsJsonArray("placedStructures").size());
    }

    @Test
    void handleExecuteD7SkipsLegacyRoadWhenAutoRoadWeaverMissing() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d7-auto-road-skip-test");
        Path serverRoot = Files.createTempDirectory("city-d7-auto-road-skip-server-root");
        String runId = "run_d7_auto_road_skip";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        CityPlanningEndpointHandler.handlePlanD6(debugRoot, runId, citySeedId, null, null);
        JsonObject d5 = CityPlanningEndpointHandler.handleExecuteD5(debugRoot, serverRoot, runId, citySeedId,
                true, null, "auto");
        JsonObject providerState = d5.getAsJsonObject("roadProviderState");
        assertFalse(providerState.get("useWorldEditDebugFallback").getAsBoolean());
        assertEquals("skipped",
                providerState.getAsJsonObject("roadWeaverRegistrationReport").get("status").getAsString());
        assertEquals("ROADWEAVER_UNAVAILABLE",
                providerState.getAsJsonObject("roadWeaverRegistrationReport").get("reasonCode").getAsString());
        providerState.addProperty("useWorldEditDebugFallback", true);
        Files.writeString(debugRoot.resolve(d5.getAsJsonObject("artifacts").get("roadProviderState").getAsString()),
                CityJson.GSON.toJson(providerState));
        recordPlannedWorldgenPlacementsFromRegistry();

        JsonObject d7 = CityPlanningEndpointHandler.handleExecuteD7(
                debugRoot, runId, citySeedId, 12345L,
                true, false, null, null);

        JsonObject roadReport = d7.getAsJsonObject("deferredRoadPostprocessReport");
        assertEquals("skipped", roadReport.get("status").getAsString());
        assertEquals("ROADWEAVER_UNAVAILABLE", roadReport.get("reasonCode").getAsString());
        assertEquals("none", roadReport.get("roadPostprocessSource").getAsString());
        assertFalse(roadReport.has("generatedBuildOperationPlan"));
        assertTrue(d7.getAsJsonObject("roadProviderState").get("useWorldEditDebugFallback").getAsBoolean());
    }

    @Test
    void handleExecuteD5RequiresLockedD6Plan() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-requires-d6");
        String runId = "run_d5_requires_d6";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, Files.createTempDirectory("city-d5-requires-d6-server-root"),
                        runId, citySeedId, true, null, "auto"));

        assertTrue(ex.getMessage().contains("Run city_plan_d6 before city_execute_d5"));
    }

    @Test
    void handlePlanD6_allowsPreflightBeforeActiveWorldgenRegistry() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d6-registry-missing");
        String runId = "run_d6_missing_registry";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        JsonObject d6 = CityPlanningEndpointHandler.handlePlanD6(
                debugRoot,
                runId,
                citySeedId,
                null,
                null);

        assertTrue(d6.get("ok").getAsBoolean());
        assertEquals("worldgen_time_planned_registry",
                d6.getAsJsonObject("structureMaterializationPlan").get("dryRunMode").getAsString());
        JsonObject planned = d6.getAsJsonObject("structureMaterializationPlan")
                .getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();
        assertEquals("planned_worldgen", planned.get("status").getAsString());
        assertEquals("WAITING_FOR_WORLDGEN", planned.get("reasonCode").getAsString());
    }

    @Test
    void handleExecuteD5_requiresExplicitConfirmation() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        Path.of("run/realm_debug"), Path.of("run"),
                        "run", "city", false, null, "auto"));
        assertTrue(ex.getMessage().contains("confirmWorldMutation"));
    }

    @Test
    void handleExecuteD5_requiresPlannedOperationFile() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-execute-missing-plan");
        String runId = "run_d5_execute";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir.resolve("city_d4_city_test"));
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
        Files.writeString(runDir.resolve("city_d4_city_test").resolve("structure_anchor_map.json"), """
                {
                  "schemaVersion": "city_structure_anchor_map.v0.1",
                  "cityId": "city_test",
                  "anchors": []
                }
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, Files.createTempDirectory("city-d5-server-root"),
                        runId, citySeedId, true, null, "auto"));
        assertTrue(ex.getMessage().contains("reservation_mask_plan.json"));
    }

    @Test
    void handleExecuteD5_activatesRegistryWithoutLoadedWorld() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-execute-no-level");
        Path serverRoot = Files.createTempDirectory("city-d5-server-root");
        String runId = "run_d5_execute";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir.resolve("city_d4_city_test"));
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
        Files.writeString(runDir.resolve("city_d4_city_test").resolve("structure_anchor_map.json"), """
                {
                  "schemaVersion": "city_structure_anchor_map.v0.1",
                  "cityId": "city_test",
                  "anchors": [
                    {
                      "anchorId": "anchor_test",
                      "structureId": "minecraft:village_plains",
                      "anchorBlock": {"x": 0, "z": 0},
                      "commandAnchorBlock": {"x": 0, "z": 0},
                      "rotation": "NONE",
                      "plannedFootprint": {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4},
                      "reservedEnvelope": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32},
                      "sourcePatches": [],
                      "semanticTerms": [],
                      "functionTerms": []
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
        Files.writeString(runDir.resolve("city_d5_city_test").resolve("reservation_mask_plan.json"), """
                {
                  "schemaVersion": "city_reservation_mask_plan.v0.1",
                  "cityId": "city_test",
                  "noVegetationMask": [],
                  "vegetationLimitedMask": [],
                  "noVanillaStructureMask": [],
                  "reservationReason": []
                }
                """);

        Files.createDirectories(runDir.resolve("city_d6_city_test"));
        Files.writeString(runDir.resolve("city_d6_city_test").resolve("structure_materialization_plan.json"), """
                {
                  "schemaVersion": "city_structure_materialization_plan.v0.1",
                  "cityId": "city_test",
                  "worldgenPlacementMode": true,
                  "locked": true,
                  "plannedWorldgenStructures": [
                    {
                      "anchorId": "anchor_test",
                      "structureId": "minecraft:village_plains",
                      "anchorBlock": {"x": 0, "z": 0},
                      "commandAnchorBlock": {"x": 0, "z": 0},
                      "anchorChunk": {"x": 0, "z": 0},
                      "rotation": "NONE",
                      "plannedFootprint": {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4},
                      "reservedEnvelope": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8},
                      "collisionEnvelope": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8},
                      "actualFootprint": {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4},
                      "lockedActualFootprint": {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4},
                      "lockedCollisionEnvelope": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8},
                      "lockedBBoxGroupKey": "anchor_test@0,0",
                      "pieceBoxes": [
                        {"pieceId": "anchor_test_start", "blockBounds": {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4}}
                      ],
                      "maskEnvelope": {"minX": -12, "minZ": -12, "maxX": 12, "maxZ": 12},
                      "locked": true,
                      "expectedStartSignature": "sig_anchor_test",
                      "status": "planned_worldgen",
                      "reasonCode": "WAITING_FOR_WORLDGEN",
                      "sourcePatches": [],
                      "semanticTerms": [],
                      "functionTerms": []
                    }
                  ]
                }
                """);

        JsonObject response = CityPlanningEndpointHandler.handleExecuteD5(
                debugRoot, serverRoot, runId, citySeedId, true, null, "auto");
        assertTrue(response.get("ok").getAsBoolean());
        assertTrue(response.get("worldgenPlacementMode").getAsBoolean());
        assertEquals(1, response.get("activePlannedStructureCount").getAsInt());
        assertFalse(response.getAsJsonObject("worldMutationReport").get("executed").getAsBoolean());

        JsonObject activeMask = JsonParser.parseString(Files.readString(serverRoot
                .resolve("geomantia_city_masks")
                .resolve("active_reservation_mask_plan.json"))).getAsJsonObject();
        JsonObject activeNoVegetation = activeMask.getAsJsonArray("noVegetationMask")
                .get(0).getAsJsonObject().getAsJsonObject("blockBounds");
        assertEquals(-12, activeNoVegetation.get("minX").getAsInt());
        assertEquals(12, activeNoVegetation.get("maxX").getAsInt());
        assertFalse(activeMask.toString().contains("safetyEnvelope"));

        JsonObject activeRegistry = JsonParser.parseString(Files.readString(
                CityReservationMaskRegistry.plannedRegistryPath(serverRoot))).getAsJsonObject();
        JsonObject planned = activeRegistry.getAsJsonArray("plannedStructures").get(0).getAsJsonObject();
        assertEquals("sig_anchor_test", planned.get("expectedStartSignature").getAsString());
        assertEquals(4, planned.getAsJsonObject("lockedActualFootprint").get("maxX").getAsInt());
        assertEquals(8, planned.getAsJsonObject("lockedCollisionEnvelope").get("maxX").getAsInt());
        assertFalse(planned.has("safetyEnvelope"));
    }

    @Test
    void handleExecuteD5_roadweaverProviderHardFailsWhenModMissing() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-roadweaver-missing");
        String runId = "run_d5_roadweaver_missing";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD6(debugRoot, runId, citySeedId, null, null);

        Path serverRoot = Files.createTempDirectory("city-d5-roadweaver-server-root");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, serverRoot,
                        runId, citySeedId, true, null, "roadweaver"));

        assertTrue(ex.getMessage().contains("ROADWEAVER_UNAVAILABLE"));
        assertFalse(Files.exists(CityReservationMaskRegistry.plannedRegistryPath(serverRoot)),
                "RoadWeaver preflight must fail before the structure registry is activated");
    }

    @Test
    void handlePlanCityWallsWritesPlanPreviewAndReadableNbtTemplates() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-plan-test");
        String runId = "run_wall_plan";
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
        Path d7Dir = runDir.resolve("city_d7_" + citySeedId);
        Files.createDirectories(d7Dir);
        Files.writeString(d7Dir.resolve("placed_structure_ledger.json"), """
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {
                      "anchorId": "admin_core",
                      "structureId": "minecraft:desert_pyramid",
                      "actualFootprint": {"minX": -10, "minZ": -12, "maxX": 18, "maxZ": 20}
                    },
                    {
                      "anchorId": "farm",
                      "structureId": "minecraft:village_plains",
                      "actualFootprint": {"minX": 60, "minZ": 24, "maxX": 82, "maxZ": 44}
                    }
                  ]
                }
                """);

        JsonObject response = CityPlanningEndpointHandler.handlePlanCityWalls(
                debugRoot, runId, citySeedId, 24, 15, 7,
                "v1_debug", null, 8, 2, 8, 7);

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        Path planPath = debugRoot.resolve(artifacts.get("cityWallPlan").getAsString());
        Path previewPath = debugRoot.resolve(artifacts.get("cityWallPreview").getAsString());
        Path templateDir = debugRoot.resolve(artifacts.get("cityWallTemplateDirectory").getAsString());
        assertTrue(Files.exists(planPath));
        assertTrue(Files.exists(previewPath));
        assertTrue(Files.exists(templateDir.resolve("wall_template_library.json")));
        assertTrue(Files.exists(templateDir.resolve("wall_straight_15.nbt")));
        assertTrue(Files.exists(templateDir.resolve("wall_tower_small.nbt")));
        assertTrue(Files.exists(templateDir.resolve("wall_gap_gate_7.nbt")));
        JsonArray segments = response.getAsJsonObject("cityWallPlan").getAsJsonArray("wallSegments");
        assertTrue(segments.toString().contains("gate_gap"));
        assertTrue(segments.toString().contains("tower"));

        CompoundTag straight = NbtIo.readCompressed(templateDir.resolve("wall_straight_15.nbt").toFile());
        assertEquals(15, straight.getList("size", 3).getInt(0));
        assertFalse(straight.getList("blocks", 10).isEmpty());
    }

    @Test
    void handlePlanCityWallsAcceptsV32DesignPolicyAndWritesDesignTemplates() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v32-plan-test");
        String runId = "run_wall_v32_plan";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v3", 24, 15, 4, new CityWallReservationPlanner.V3Options(24, 2, 64, 0.6));

        Path d7Dir = debugRoot.resolve(runId).resolve("city_d7_" + citySeedId);
        Files.createDirectories(d7Dir);
        Files.writeString(d7Dir.resolve("placed_structure_ledger.json"), """
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {
                      "anchorId": "admin_core",
                      "structureId": "minecraft:desert_pyramid",
                      "actualFootprint": {"minX": -10, "minZ": -12, "maxX": 18, "maxZ": 20}
                    }
                  ]
                }
                """);

        JsonObject response = CityPlanningEndpointHandler.handlePlanCityWalls(
                debugRoot, runId, citySeedId, 24, 15, 9,
                "v3", null, 8, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true,
                        "v3.2", 48, 24, 4096));

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject wallPlan = response.getAsJsonObject("cityWallPlan");
        assertEquals("v3.2", wallPlan.get("wallDesignPolicy").getAsString());
        assertEquals("domain_hull_then_natural_boundary_and_gatehouse_nodes",
                wallPlan.get("wallPlanningMode").getAsString());
        Path templateDir = debugRoot.resolve(response.getAsJsonObject("artifacts")
                .get("cityWallTemplateDirectory").getAsString());
        assertTrue(Files.exists(templateDir.resolve("gatehouse_9.nbt")));
        assertTrue(Files.exists(templateDir.resolve("gatehouse_13.nbt")));
        assertTrue(Files.exists(templateDir.resolve("watchtower_5x5.nbt")));
        assertTrue(Files.exists(templateDir.resolve("beacon_5x5.nbt")));
    }

    @Test
    void handlePlanCityWallsAcceptsV4AndWritesWallGraphPreview() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v4-plan-test");
        String runId = "run_wall_v4_plan";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v4", 24, 15, 4, new CityWallReservationPlanner.V3Options(24, 2, 64, 0.6));

        Path d7Dir = debugRoot.resolve(runId).resolve("city_d7_" + citySeedId);
        Files.createDirectories(d7Dir);
        Files.writeString(d7Dir.resolve("placed_structure_ledger.json"), """
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {
                      "anchorId": "admin_core",
                      "structureId": "minecraft:desert_pyramid",
                      "actualFootprint": {"minX": -10, "minZ": -12, "maxX": 18, "maxZ": 20}
                    },
                    {
                      "anchorId": "outside_patch_structure",
                      "structureId": "minecraft:village_plains",
                      "actualFootprint": {"minX": 108, "minZ": 8, "maxX": 136, "maxZ": 32}
                    }
                  ]
                }
                """);

        JsonObject response = CityPlanningEndpointHandler.handlePlanCityWalls(
                debugRoot, runId, citySeedId, 24, 15, 9,
                "v4", null, 8, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true),
                CityWallPlanner.V4Options.defaults());

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject wallPlan = response.getAsJsonObject("cityWallPlan");
        assertEquals("city_wall_plan.v0.4", wallPlan.get("schemaVersion").getAsString());
        assertEquals("actual_footprint_land_ring", wallPlan.get("wallBoundaryMode").getAsString());
        assertFalse(wallPlan.getAsJsonArray("wallNodes").isEmpty());
        assertFalse(wallPlan.getAsJsonArray("wallUnits").isEmpty());
        assertFalse(wallPlan.getAsJsonArray("nodeConnectorUnits").isEmpty());
        assertTrue(wallPlan.has("cityWallDatumY"));
        BlockBounds wallBounds = bounds(wallPlan.getAsJsonObject("wallBounds"));
        assertTrue(wallBounds.contains(136, 32), wallPlan.toString());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("cityWallPlan").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("cityWallPreview").getAsString())));
    }

    @Test
    void handlePlanD5CanWriteV5FinalWallReservationArtifacts() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-d5-test");
        String runId = "run_wall_v5_d5";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        JsonObject response = CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v5", 24, 15, 4, CityWallReservationPlanner.V3Options.defaults());

        JsonObject reservation = response.getAsJsonObject("wallReservationPlan");
        assertEquals("city_wall_reservation_plan.v0.5", reservation.get("schemaVersion").getAsString());
        assertEquals("v5", reservation.get("wallVersion").getAsString());
        assertEquals("d4_planned_footprint_envelope_rectilinear_hull",
                reservation.get("boundarySource").getAsString());
        assertEquals("semantic_and_coverage_check_only", reservation.get("patchUsage").getAsString());
        assertFalse(reservation.get("finalBoundaryDeferredToD7").getAsBoolean());
        assertFalse(reservation.getAsJsonArray("wallLine").isEmpty());
        assertFalse(reservation.getAsJsonArray("wallCorridorMask").isEmpty());
        assertFalse(reservation.getAsJsonArray("gateSlots").isEmpty());
        assertFalse(reservation.getAsJsonArray("wallNodeSlots").isEmpty());
        assertFalse(reservation.getAsJsonObject("coverageCheck")
                .get("patchBoundaryIsFinalWallLine").getAsBoolean());
        JsonObject mask = response.getAsJsonObject("reservationMaskPlan");
        assertTrue(mask.has("gateCorridorMask"));
        assertTrue(mask.has("worldgenMaskChannels"));
        assertTrue(mask.getAsJsonObject("worldgenMaskChannels").has("noRoadsideStructure"));
    }

    @Test
    void handlePlanD5V5RequiresPatchRescanWhenReservationTouchesD3Boundary() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-rescan-test");
        String runId = "run_wall_v5_rescan";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path d3Path = debugRoot.resolve(runId).resolve("city_d3_" + citySeedId)
                .resolve("city_landform_review_package.json");
        JsonObject d3 = JsonParser.parseString(Files.readString(d3Path)).getAsJsonObject();
        JsonObject grid = d3.getAsJsonObject("grid");
        grid.addProperty("originBlockX", -16);
        grid.addProperty("originBlockZ", -16);
        grid.addProperty("cellStepBlocks", 4);
        grid.addProperty("cellsX", 8);
        grid.addProperty("cellsZ", 8);
        Files.writeString(d3Path, CityJson.GSON.toJson(d3));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                        "v5", 24, 15, 4, CityWallReservationPlanner.V3Options.defaults()));

        assertTrue(ex.getMessage().contains("D5_V5_REQUIRES_PATCH_RESCAN"));
    }

    @Test
    void handlePlanCityWallsV5KeepsD5WallLineAndBackfillsSurfaceCacheReport() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-plan-test");
        String runId = "run_wall_v5_plan";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v5", 24, 15, 4, CityWallReservationPlanner.V3Options.defaults());
        Path reservationPath = debugRoot.resolve(runId).resolve("city_d5_" + citySeedId)
                .resolve("wall_reservation_plan.json");
        JsonObject reservation = JsonParser.parseString(Files.readString(reservationPath)).getAsJsonObject();

        Path d7Dir = debugRoot.resolve(runId).resolve("city_d7_" + citySeedId);
        Files.createDirectories(d7Dir);
        Files.writeString(d7Dir.resolve("placed_structure_ledger.json"), """
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {
                      "anchorId": "admin_core",
                      "structureId": "minecraft:desert_pyramid",
                      "actualFootprint": {"minX": -10, "minZ": -12, "maxX": 18, "maxZ": 20}
                    }
                  ]
                }
                """);

        JsonObject response = CityPlanningEndpointHandler.handlePlanCityWalls(
                debugRoot, runId, citySeedId, 24, 15, 9,
                "v5", null, 8, 2, 8, 7);

        JsonObject wallPlan = response.getAsJsonObject("cityWallPlan");
        assertEquals("city_wall_plan.v0.5", wallPlan.get("schemaVersion").getAsString());
        assertEquals("d5_final_wall_line", wallPlan.get("wallBoundaryMode").getAsString());
        assertEquals(reservation.getAsJsonArray("wallLine").toString(),
                wallPlan.getAsJsonArray("wallLine").toString());
        assertEquals(8, wallPlan.get("wallUnitLengthBlocks").getAsInt());
        assertEquals(9, wallPlan.get("nominalWallHeightBlocks").getAsInt());
        assertTrue(wallPlan.getAsJsonObject("wallGraphValidation").get("noRelineAfterD5").getAsBoolean());
        assertTrue(wallPlan.getAsJsonArray("wallUnits").toString().contains("surface_cache_1_block_median_at_execute"));
        assertEquals("skipped", wallPlan.getAsJsonObject("surfaceCacheBackfill").get("status").getAsString());
        assertTrue(response.getAsJsonObject("artifacts").has("surfaceCacheBackfill"));
    }

    @Test
    void handlePlanD6HardStopsWhenV5LockedFootprintExceedsD5Coverage() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-d6-stop-test");
        String runId = "run_wall_v5_d6_stop";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v5", 24, 15, 4, CityWallReservationPlanner.V3Options.defaults());
        Path reservationPath = debugRoot.resolve(runId).resolve("city_d5_" + citySeedId)
                .resolve("wall_reservation_plan.json");
        JsonObject reservation = JsonParser.parseString(Files.readString(reservationPath)).getAsJsonObject();
        reservation.add("wallCoverageBounds", JsonParser.parseString("""
                {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4}
                """).getAsJsonObject());
        Files.writeString(reservationPath, CityJson.GSON.toJson(reservation));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD6(debugRoot, runId, citySeedId, null, null));

        assertTrue(ex.getMessage().contains("D5_V5_LOCKED_FOOTPRINT_OUTSIDE_RESERVATION"));
    }

    @Test
    void handlePlanD5CanWriteV3WallReservationArtifacts() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v3-d5-test");
        String runId = "run_wall_v3_d5";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        JsonObject response = CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v3", 24, 15, 4, new CityWallReservationPlanner.V3Options(24, 2, 64, 0.6));

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        Path reservationPath = debugRoot.resolve(artifacts.get("wallReservationPlan").getAsString());
        Path previewPath = debugRoot.resolve(artifacts.get("wallReservationPreview").getAsString());
        assertTrue(Files.exists(reservationPath));
        assertTrue(Files.exists(previewPath));
        JsonObject reservation = JsonParser.parseString(Files.readString(reservationPath)).getAsJsonObject();
        assertEquals("city_wall_reservation_plan.v0.3", reservation.get("schemaVersion").getAsString());
        assertEquals("structure_seeded_patch_region_hull", reservation.get("boundarySource").getAsString());
        assertFalse(reservation.getAsJsonArray("cityDomainMask").isEmpty());
    }

    @Test
    void handleRunWorkflowSkipsExistingArtifactsAndWritesTimingReport() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-workflow-test");
        String runId = "run_workflow";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Files.createDirectories(runDir.resolve("city_structure_envelopes_" + citySeedId));
        Files.writeString(runDir.resolve("city_structure_envelopes_" + citySeedId)
                .resolve("structure_envelope_facts.json"), "{}");
        Files.createDirectories(runDir.resolve("city_d6_" + citySeedId));
        Files.writeString(runDir.resolve("city_d6_" + citySeedId)
                .resolve("structure_materialization_plan.json"), "{}");

        JsonObject request = JsonParser.parseString("""
                {
                  "runId": "run_workflow",
                  "citySeedId": "city_test",
                  "skipExisting": true
                }
                """).getAsJsonObject();

        JsonObject response = CityPlanningEndpointHandler.handleRunWorkflow(
                debugRoot,
                Files.createTempDirectory("city-workflow-server-root"),
                runId,
                citySeedId,
                request,
                null,
                null);

        assertTrue(response.get("ok").getAsBoolean());
        assertEquals("waiting_for_confirmation", response.get("status").getAsString());
        JsonObject report = response.getAsJsonObject("workflowReport");
        assertEquals("city_workflow_report.v0.1", report.get("schemaVersion").getAsString());
        assertTrue(report.has("durationMs"));
        assertTrue(report.getAsJsonArray("steps").toString().contains("WORKFLOW_EXISTING_ARTIFACT"));
        assertTrue(report.getAsJsonArray("steps").toString().contains("WORKFLOW_CONFIRM_WORLD_MUTATION_REQUIRED"));
        Path reportPath = debugRoot.resolve(response.getAsJsonObject("artifacts")
                .get("workflowReport").getAsString());
        assertTrue(Files.exists(reportPath));
    }

    @Test
    void handleRunWorkflowRescanForcesProfileRefreshWhenFactsExist() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-workflow-profile-rescan-test");
        String runId = "run_workflow_profile_rescan";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path factsPath = runDir.resolve("city_structure_envelopes_" + citySeedId)
                .resolve("structure_envelope_facts.json");
        Files.createDirectories(factsPath.getParent());
        Files.writeString(factsPath, "{}");
        Files.createDirectories(runDir.resolve("city_d6_" + citySeedId));
        Files.writeString(runDir.resolve("city_d6_" + citySeedId)
                .resolve("structure_materialization_plan.json"), "{}");
        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");

        JsonObject request = new JsonObject();
        request.addProperty("runId", runId);
        request.addProperty("citySeedId", citySeedId);
        request.addProperty("skipExisting", true);
        request.addProperty("cacheMode", "rescan");
        request.addProperty("sampleCount", 1);
        request.add("terrasenseProfileSource", terraSenseSource(catalogPath));
        request.add("structureIds", JsonParser.parseString("""
                ["minecraft:desert_pyramid"]
                """).getAsJsonArray());

        JsonObject response = CityPlanningEndpointHandler.handleRunWorkflow(
                debugRoot,
                Files.createTempDirectory("city-workflow-profile-rescan-server-root"),
                runId,
                citySeedId,
                request,
                null,
                null);

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject profileStep = response.getAsJsonObject("workflowReport")
                .getAsJsonArray("steps").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(step -> "city_profile_structure_envelopes".equals(step.get("name").getAsString()))
                .findFirst()
                .orElseThrow();
        assertEquals("success", profileStep.get("status").getAsString());
        assertFalse(profileStep.has("reasonCode")
                && "WORKFLOW_EXISTING_ARTIFACT".equals(profileStep.get("reasonCode").getAsString()));
        JsonObject facts = JsonParser.parseString(Files.readString(factsPath)).getAsJsonObject();
        assertEquals("city_structure_envelope_facts.v0.1", facts.get("schemaVersion").getAsString());
        assertTrue(facts.getAsJsonObject("profileCache").get("forceRefresh").getAsBoolean());
    }

    @Test
    void handleRunWorkflowDefaultD4PlacesKeyStructuresBeforeArrayStages() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-workflow-staged-d4-test");
        String runId = "run_workflow_staged_d4";
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
                      "planningRadiusCells": 128,
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
                "village", "village", 128, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain_array", LandformType.PLAIN, -220, -220, 220, 220)));
        Path d3Dir = runDir.resolve("city_d3_" + citySeedId);
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));
        Files.createDirectories(runDir.resolve("city_structure_envelopes_" + citySeedId));
        Files.writeString(runDir.resolve("city_structure_envelopes_" + citySeedId)
                .resolve("structure_envelope_facts.json"), "{}");
        Files.createDirectories(runDir.resolve("city_d6_" + citySeedId));
        Files.writeString(runDir.resolve("city_d6_" + citySeedId)
                .resolve("structure_materialization_plan.json"), "{}");
        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());

        JsonObject request = new JsonObject();
        request.addProperty("runId", runId);
        request.addProperty("citySeedId", citySeedId);
        request.addProperty("skipExisting", true);
        request.add("terrasenseProfileSource", terraSenseSource(catalogPath));
        request.add("designSlotPlan", stagedDesignSlotPlan(review));

        JsonObject response = CityPlanningEndpointHandler.handleRunWorkflow(
                debugRoot,
                Files.createTempDirectory("city-workflow-staged-d4-server-root"),
                runId,
                citySeedId,
                request,
                null,
                null);

        assertTrue(response.get("ok").getAsBoolean());
        assertEquals("waiting_for_confirmation", response.get("status").getAsString());
        JsonObject report = response.getAsJsonObject("workflowReport");
        assertEquals("key_then_array", report.get("d4CandidateMode").getAsString());
        String steps = report.getAsJsonArray("steps").toString();
        assertTrue(steps.indexOf("city_d4_key_structure_finalize_session")
                < steps.indexOf("city_plan_d4_array_stage_residential_array"));
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("d4StagedPlan").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("d4StagedTrace").getAsString())));

        JsonObject anchorMap = JsonParser.parseString(Files.readString(runDir
                .resolve("city_d4_" + citySeedId)
                .resolve("structure_anchor_map.json"))).getAsJsonObject();
        assertEquals(5, anchorMap.getAsJsonArray("anchors").size());
    }

    @Test
    void handleRunWorkflowArrayLayoutLoopModeReplaysItemsAfterKeyStage() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-workflow-array-layout-loop-test");
        String runId = "run_workflow_array_layout_loop";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "town",
                      "theoreticalScale": "town",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 128,
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
                "town", "town", 128, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain_array", LandformType.PLAIN, -260, -260, 260, 260)));
        Path d3Dir = runDir.resolve("city_d3_" + citySeedId);
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));
        Files.createDirectories(runDir.resolve("city_structure_envelopes_" + citySeedId));
        Files.writeString(runDir.resolve("city_structure_envelopes_" + citySeedId)
                .resolve("structure_envelope_facts.json"), "{}");
        Files.createDirectories(runDir.resolve("city_d6_" + citySeedId));
        Files.writeString(runDir.resolve("city_d6_" + citySeedId)
                .resolve("structure_materialization_plan.json"), "{}");
        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());

        JsonObject request = new JsonObject();
        request.addProperty("runId", runId);
        request.addProperty("citySeedId", citySeedId);
        request.addProperty("skipExisting", true);
        request.addProperty("d4CandidateMode", "array_layout_loop_v0_2");
        request.add("terrasenseProfileSource", terraSenseSource(catalogPath));
        request.add("designSlotPlan", stagedDesignSlotPlan(review));
        request.add("arrayLayoutPlan", arrayLayoutPlanWithItem(review));

        JsonObject response = CityPlanningEndpointHandler.handleRunWorkflow(
                debugRoot,
                Files.createTempDirectory("city-workflow-array-layout-loop-server-root"),
                runId,
                citySeedId,
                request,
                null,
                null);

        assertTrue(response.get("ok").getAsBoolean());
        assertEquals("waiting_for_confirmation", response.get("status").getAsString());
        JsonObject report = response.getAsJsonObject("workflowReport");
        assertEquals("array_layout_loop_v0_2", report.get("d4CandidateMode").getAsString());
        String steps = report.getAsJsonArray("steps").toString();
        assertTrue(steps.indexOf("city_d4_key_structure_finalize_session")
                < steps.indexOf("city_create_d4_array_layout_loop"));
        assertTrue(steps.indexOf("city_create_d4_array_layout_loop")
                < steps.indexOf("city_execute_d4_array_layout_item_residential_cluster"));
        assertTrue(steps.contains("city_finalize_d4_array_layout_loop"));
        JsonObject anchorMap = JsonParser.parseString(Files.readString(runDir
                .resolve("city_d4_" + citySeedId)
                .resolve("structure_anchor_map.json"))).getAsJsonObject();
        assertEquals(4, anchorMap.getAsJsonArray("anchors").size());
        assertTrue(Files.exists(runDir.resolve("city_d4_array_layout_" + citySeedId)
                .resolve("d4_array_layout_loop_state.json")));
    }

    @Test
    void handleRunWorkflowRejectsArrayStageBeforeKeyStructureStage() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-workflow-staged-d4-order-test");
        String runId = "run_workflow_staged_d4_order";
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
                      "planningRadiusCells": 128,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);
        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "minecraft:overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 128, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain_array", LandformType.PLAIN, -220, -220, 220, 220)));
        Path d3Dir = runDir.resolve("city_d3_" + citySeedId);
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));
        Files.createDirectories(runDir.resolve("city_structure_envelopes_" + citySeedId));
        Files.writeString(runDir.resolve("city_structure_envelopes_" + citySeedId)
                .resolve("structure_envelope_facts.json"), "{}");
        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());

        JsonObject plan = stagedDesignSlotPlan(review);
        plan.add("placementOrder", JsonParser.parseString("""
                ["residential_array", "admin_core"]
                """).getAsJsonArray());
        JsonObject request = new JsonObject();
        request.addProperty("runId", runId);
        request.addProperty("citySeedId", citySeedId);
        request.addProperty("skipExisting", true);
        request.add("terrasenseProfileSource", terraSenseSource(catalogPath));
        request.add("designSlotPlan", plan);

        JsonObject response = CityPlanningEndpointHandler.handleRunWorkflow(
                debugRoot,
                Files.createTempDirectory("city-workflow-staged-d4-order-server-root"),
                runId,
                citySeedId,
                request,
                null,
                null);

        assertFalse(response.get("ok").getAsBoolean());
        assertEquals("failed", response.get("status").getAsString());
        assertTrue(response.getAsJsonObject("workflowReport").getAsJsonArray("steps")
                .toString()
                .contains("D4_KEY_STRUCTURES_MUST_PRECEDE_ARRAYS"));
    }

    private static void assertPreviewHasPatchBackdrop(Path previewPath) throws Exception {
        BufferedImage image = ImageIO.read(previewPath.toFile());
        int patchPixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xff;
                int red = (argb >>> 16) & 0xff;
                int green = (argb >>> 8) & 0xff;
                int blue = argb & 0xff;
                if (alpha > 0 && green > red + 6 && green > blue + 12 && red > 150 && blue > 140) {
                    patchPixels++;
                }
            }
        }
        assertTrue(patchPixels > 500, "D7 placed preview should include D3 patch backdrop colors");
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

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        CityPlanningEndpointHandler.handlePlanD4(debugRoot, runId, citySeedId,
                terraSenseSource(catalogPath), structureAnchorPlan(review, 2));
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId);
    }

    private static JsonObject terraSenseSource(Path catalogPath) {
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        return source;
    }

    private static JsonObject structureAnchorPlan(CityLandformReviewPackage review, int anchorCount) {
        List<LandformPatchSummary> patches = review.landformPatches();
        LandformPatchSummary first = patches.get(0);
        LandformPatchSummary second = patches.size() > 1 ? patches.get(1) : first;
        String secondAnchor = anchorCount > 1 ? """
                    {
                      "anchorId": "anchor_village",
                      "structureId": "minecraft:village_plains",
                      "sourcePatchIds": ["%s"],
                      "anchorBlock": {"x": %d, "z": %d},
                      "rotation": "NONE",
                      "intentTerms": ["function.village"],
                      "priority": 2,
                      "roadAccessIntent": "secondary_access",
                      "clearanceBlocks": 8,
                      "roadAccessMarginBlocks": 6
                    }
                """.formatted(second.landformPatchId(), second.centerBlock().x(), second.centerBlock().z()) : "";
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_structure_anchor_plan.v0.1",
                  "cityId": "city_test",
                  "anchors": [
                    {
                      "anchorId": "anchor_pyramid",
                      "structureId": "minecraft:desert_pyramid",
                      "sourcePatchIds": ["%s"],
                      "anchorBlock": {"x": %d, "z": %d},
                      "rotation": "NONE",
                      "intentTerms": ["function.landmark"],
                      "priority": 1,
                      "roadAccessIntent": "primary_access",
                      "clearanceBlocks": 8,
                      "roadAccessMarginBlocks": 6
                    }
                    %s
                  ]
                }
                """.formatted(first.landformPatchId(), first.centerBlock().x(), first.centerBlock().z(),
                secondAnchor.isBlank() ? "" : "," + secondAnchor)).getAsJsonObject();
    }

    private static JsonObject designSlotPlan(CityLandformReviewPackage review) {
        List<LandformPatchSummary> patches = review.landformPatches();
        LandformPatchSummary first = patches.get(0);
        LandformPatchSummary second = patches.size() > 1 ? patches.get(1) : first;
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_design_slot_plan.v0.1",
                  "cityId": "city_test",
                  "placementOrder": ["admin_core", "residential_01"],
                  "slots": [
                    {
                      "slotId": "admin_core",
                      "displayRole": "行政核心",
                      "candidatePatchRefs": ["%s"],
                      "structureIds": ["minecraft:desert_pyramid"],
                      "relationHints": []
                    },
                    {
                      "slotId": "residential_01",
                      "displayRole": "住宅",
                      "candidatePatchRefs": ["%s"],
                      "structureIds": ["minecraft:desert_pyramid"],
                      "relationHints": [
                        {"targetSlotId": "admin_core", "distanceBand": "near"}
                      ]
                    }
                  ]
                }
                """.formatted(first.landformPatchId(), second.landformPatchId())).getAsJsonObject();
    }

    private static JsonObject stagedDesignSlotPlan(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_design_slot_plan.v0.1",
                  "cityId": "city_test",
                  "placementOrder": ["admin_core", "residential_array"],
                  "slots": [
                    {
                      "slotId": "admin_core",
                      "placementStrategy": "key_structure",
                      "displayRole": "行政核心",
                      "candidatePatchRefs": ["%s"],
                      "structureIds": ["minecraft:desert_pyramid"],
                      "relationHints": []
                    },
                    {
                      "slotId": "residential_array",
                      "placementStrategy": "array_fill",
                      "displayRole": "住宅阵列",
                      "candidatePatchRefs": ["%s"],
                      "structureIds": ["minecraft:desert_pyramid"],
                      "arrayCount": 4,
                      "variantSelectionMode": "seeded_random",
                      "patterns": ["loose_cluster", "patch_axis_band", "scattered"]
                    }
                  ]
                }
                """.formatted(first.landformPatchId(), first.landformPatchId())).getAsJsonObject();
    }

    private static JsonObject arrayCandidatePlan(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_array_candidate_plan.v0.1",
                  "cityId": "city_test",
                  "arrayId": "residential_cluster",
                  "displayRole": "住宅阵列",
                  "candidatePatchRefs": ["%s"],
                  "structureIds": ["minecraft:desert_pyramid"],
                  "arrayCount": 4,
                  "patterns": ["loose_cluster", "patch_axis_band", "scattered"]
                }
                """.formatted(first.landformPatchId())).getAsJsonObject();
    }

    private static JsonObject arrayLayoutPlan(CityLandformReviewPackage review) {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_array_layout_plan.v0.2",
                  "cityId": "city_test",
                  "cityScale": "town",
                  "maxArrayPlans": 4,
                  "layoutPlans": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject arrayLayoutPlanV04() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_array_layout_plan.v0.4",
                  "planningMode": "array_candidate_selection_loop_v0_4",
                  "cityId": "city_test",
                  "cityScale": "town",
                  "maxArrayPlans": 4,
                  "layoutPlans": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject arrayExpansionRequest(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "focusRef": {"anchorId": "manor_core"},
                  "direction": "east",
                  "targetPatchRef": "%s",
                  "candidateCount": 3,
                  "minCandidateCount": 3,
                  "nextArrayLayoutPlanItem": {
                    "arrayId": "east_residential_theme",
                    "plannerType": "compound_cluster",
                    "role": "residential",
                    "fillPool": [{"structureId": "minecraft:desert_pyramid", "weight": 1}],
                    "countPolicy": {"minCount": 2, "targetCount": 2, "maxCount": 2},
                    "variantSelectionMode": "round_robin"
                  }
                }
                """.formatted(first.landformPatchId())).getAsJsonObject();
    }

    private static JsonObject arrayLayoutPlanWithItem(CityLandformReviewPackage review) {
        JsonObject plan = arrayLayoutPlan(review);
        JsonArray items = new JsonArray();
        items.add(arrayLayoutItem(review, "compound_cluster", "residential_cluster", 3));
        plan.add("layoutPlans", items);
        return plan;
    }

    private static JsonObject arrayLayoutItem(CityLandformReviewPackage review, String plannerType,
                                              String arrayId, int targetCount) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "arrayId": "%s",
                  "plannerType": "%s",
                  "role": "residential",
                  "candidatePatchRefs": ["%s"],
                  "startSector": "southeast",
                  "fillPool": [
                    {"structureId": "minecraft:desert_pyramid", "weight": 1}
                  ],
                  "countPolicy": {"minCount": %d, "targetCount": %d, "maxCount": %d},
                  "variantSelectionMode": "round_robin"
                }
                """.formatted(arrayId, plannerType, first.landformPatchId(),
                targetCount, targetCount, targetCount)).getAsJsonObject();
    }

    private static JsonObject dressingBrushPlan() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_dressing_brush_plan.v0.1",
                  "cityId": "city_test",
                  "dressingLayoutItems": [
                    {
                      "itemType": "parallel_rows_dressing_item",
                      "itemId": "vineyard_rows",
                      "targetBounds": {"minX": -58, "minZ": -58, "maxX": 10, "maxZ": -8},
                      "rowSpacingBlocks": 8,
                      "countPolicy": {"minDecorations": 0, "targetDecorations": 8}
                    },
                    {
                      "itemType": "corner_clutter_dressing_item",
                      "itemId": "workshop_clutter",
                      "targetBounds": {"minX": 16, "minZ": 16, "maxX": 58, "maxZ": 58},
                      "cornerPolicy": "all",
                      "countPolicy": {"minDecorations": 0, "targetDecorations": 4}
                    }
                  ]
                }
                """).getAsJsonObject();
    }

    private static List<String> relativeFiles(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static Path createDecorationCatalog() throws Exception {
        Path root = Files.createTempDirectory("city-decoration-catalog");
        Path templates = root.resolve("templates");
        Files.createDirectories(templates);
        writeDecorationTemplate(templates.resolve("bench.nbt"));
        Files.writeString(root.resolve("content_index.json"), """
                {
                  "schemaVersion": "city_decoration_content_index.v0.2",
                  "contents": [
                    {
                      "contentId": "geomantia:test_bench",
                      "contentKind": "prefab",
                      "nbtFile": "templates/bench.nbt",
                      "allowedRotations": [0, 90, 180, 270],
                      "tags": ["test", "seating"]
                    }
                  ]
                }
                """);
        Path styles = root.resolve("styles");
        Files.createDirectories(styles);
        Files.writeString(styles.resolve("forest_village.json"), """
                {
                  "schemaVersion": "city_decoration_style_profile.v0.1",
                  "styleProfileId": "forest_village",
                  "mappings": [
                    {
                      "semanticRef": "market_stall",
                      "variants": [
                        {"contentRef": "geomantia:test_bench", "weight": 1.0}
                      ]
                    }
                  ]
                }
                """);
        return root;
    }

    private static void writeDecorationTemplate(Path path) throws Exception {
        CompoundTag root = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(1));
        size.add(IntTag.valueOf(1));
        size.add(IntTag.valueOf(1));
        root.put("size", size);

        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:oak_planks");
        ListTag palette = new ListTag();
        palette.add(state);
        root.put("palette", palette);

        CompoundTag block = new CompoundTag();
        ListTag position = new ListTag();
        position.add(IntTag.valueOf(0));
        position.add(IntTag.valueOf(0));
        position.add(IntTag.valueOf(0));
        block.put("pos", position);
        block.putInt("state", 0);
        ListTag blocks = new ListTag();
        blocks.add(block);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        NbtIo.writeCompressed(root, path.toFile());
    }

    private static JsonObject decorationProgramPlan(String catalogHash,
                                                    String styleProfileHash,
                                                    String patchRef,
                                                    String contentRef) {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_decoration_program_plan.v0.2",
                  "cityId": "city_test",
                  "catalogHash": "%s",
                  "styleProfileId": "forest_village",
                  "styleProfileHash": "%s",
                  "programs": [
                    {
                      "programId": "patch_fill",
                      "targetArea": {"sourceType": "patch", "ref": "%s", "insetBlocks": 0},
                      "coordinateFrame": {
                        "originMode": "target_centroid",
                        "orientationMode": "patch_long_axis",
                        "quarterTurns": 0,
                        "offsetUBlocks": 0,
                        "offsetVBlocks": 0
                      },
                      "shape": {"type": "target_mask", "params": {}},
                      "pattern": {"type": "uniform_fill", "params": {"paletteSlotId": "surface"}},
                      "contentPalette": {
                        "slots": [
                          {
                            "slotId": "surface",
                            "phase": "surface",
                            "entries": [{"contentRef": "%s", "weight": 1.0}],
                            "required": true
                          }
                        ]
                      },
                      "terrainPolicy": {
                        "maxSlopeDelta": 2,
                        "allowWater": false,
                        "invalidTerrainAction": "clip"
                      },
                      "conflictPolicy": {"onConflict": "skip", "clearanceBlocks": 1},
                      "priority": 10,
                      "seed": 42
                    }
                  ]
                }
                """.formatted(catalogHash, styleProfileHash, patchRef, contentRef)).getAsJsonObject();
    }

    private static void addPatchMemberCells(Path debugRoot, String runId, String citySeedId,
                                            String patchRef) throws Exception {
        Path path = debugRoot.resolve(runId).resolve("city_d3_" + citySeedId)
                .resolve("city_landform_review_package.json");
        JsonObject d3 = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        JsonObject grid = d3.getAsJsonObject("grid");
        int step = grid.get("cellStepBlocks").getAsInt();
        int originX = grid.get("originBlockX").getAsInt();
        int originZ = grid.get("originBlockZ").getAsInt();
        for (JsonElement element : d3.getAsJsonArray("landformPatches")) {
            JsonObject patch = element.getAsJsonObject();
            if (!patchRef.equals(patch.get("landformPatchId").getAsString())) {
                continue;
            }
            JsonObject patchBounds = patch.getAsJsonObject("blockBounds");
            JsonArray cells = new JsonArray();
            for (int z = patchBounds.get("minZ").getAsInt();
                 z + step - 1 <= patchBounds.get("maxZ").getAsInt(); z += step) {
                for (int x = patchBounds.get("minX").getAsInt();
                     x + step - 1 <= patchBounds.get("maxX").getAsInt(); x += step) {
                    JsonObject cell = new JsonObject();
                    cell.addProperty("cellX", Math.floorDiv(x - originX, step));
                    cell.addProperty("cellZ", Math.floorDiv(z - originZ, step));
                    cell.addProperty("blockMinX", x);
                    cell.addProperty("blockMinZ", z);
                    cells.add(cell);
                }
            }
            patch.addProperty("geometryMode", "patch_member_cells");
            patch.add("memberCells", cells);
            Files.writeString(path, CityJson.GSON.toJson(d3));
            return;
        }
        throw new IllegalArgumentException("test patch not found: " + patchRef);
    }

    private static void ensureTwoPlannedRoadEndpoints(Path debugRoot, String runId, String citySeedId)
            throws Exception {
        Path path = debugRoot.resolve(runId).resolve("city_d6_" + citySeedId)
                .resolve("structure_materialization_plan.json");
        JsonObject plan = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        JsonArray structures = plan.getAsJsonArray("plannedWorldgenStructures");
        assertFalse(structures.isEmpty());
        for (JsonElement element : structures) {
            element.getAsJsonObject().addProperty("status", "planned_worldgen");
        }
        if (structures.size() == 1) {
            JsonObject copy = structures.get(0).getAsJsonObject().deepCopy();
            copy.addProperty("anchorId", "test_road_endpoint_2");
            copy.addProperty("priority", 999);
            JsonObject actual = new JsonObject();
            actual.addProperty("minX", 36);
            actual.addProperty("minZ", 36);
            actual.addProperty("maxX", 42);
            actual.addProperty("maxZ", 42);
            JsonObject collision = new JsonObject();
            collision.addProperty("minX", 32);
            collision.addProperty("minZ", 32);
            collision.addProperty("maxX", 46);
            collision.addProperty("maxZ", 46);
            copy.add("actualFootprint", actual.deepCopy());
            copy.add("lockedActualFootprint", actual);
            copy.add("collisionEnvelope", collision.deepCopy());
            copy.add("lockedCollisionEnvelope", collision);
            structures.add(copy);
        }
        Files.writeString(path, CityJson.GSON.toJson(plan));
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(
                obj.get("minX").getAsInt(),
                obj.get("minZ").getAsInt(),
                obj.get("maxX").getAsInt(),
                obj.get("maxZ").getAsInt());
    }

    private static void recordPlannedWorldgenPlacementsFromRegistry() {
        JsonObject plannedRegistry = CityReservationMaskRegistry.plannedRegistrySummary();
        for (JsonElement elem : plannedRegistry.getAsJsonArray("plannedStructures")) {
            JsonObject plannedJson = elem.getAsJsonObject();
            JsonObject chunkJson = plannedJson.getAsJsonObject("anchorChunk");
            ChunkPos chunk = new ChunkPos(chunkJson.get("x").getAsInt(), chunkJson.get("z").getAsInt());
            CityReservationMaskRegistry.PlannedStructure planned = CityReservationMaskRegistry
                    .plannedStructuresForChunk(chunk)
                    .get(0);
            JsonObject actualJson = plannedJson.getAsJsonObject("lockedActualFootprint");
            BlockBounds actual = new BlockBounds(
                    actualJson.get("minX").getAsInt(),
                    actualJson.get("minZ").getAsInt(),
                    actualJson.get("maxX").getAsInt(),
                    actualJson.get("maxZ").getAsInt());
            CityReservationMaskRegistry.recordWorldgenPlacement(planned, actual,
                    planned.expectedStartSignature(), new JsonArray(), chunk,
                    "none", "WORLDGEN_PLACEMENT_RECORDED", "test placement");
        }
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
                      "semanticTerms": ["function.landmark", "style.debug", "placement.inside_zone", "usage.public_core", "quality.debug_usable"],
                      "functionTerms": ["function.landmark"],
                      "styleTerms": ["style.debug"],
                      "placementTerms": ["placement.inside_zone"],
                      "usageTerms": ["usage.public_core"],
                      "qualityTerms": ["quality.debug_usable"],
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
                      "semanticTerms": ["function.村庄", "style.debug", "placement.inside_zone", "usage.filler", "quality.debug_usable"],
                      "functionTerms": ["function.村庄"],
                      "styleTerms": ["style.debug"],
                      "placementTerms": ["placement.inside_zone"],
                      "usageTerms": ["usage.filler"],
                      "qualityTerms": ["quality.debug_usable"],
                      "expectedAreaRange": {
                        "minAreaBlocks": 128,
                        "maxAreaBlocks": 25600,
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

}
