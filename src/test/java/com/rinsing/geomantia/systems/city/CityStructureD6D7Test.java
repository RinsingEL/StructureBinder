package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityStructureD6Planner;
import com.rinsing.geomantia.systems.city.application.CityStructureD7Executor;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityStructurePreviewRenderer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class CityStructureD6D7Test {
    @Test
    void d6RejectsTemplateAndJigsawIdsAsRealCandidates() throws Exception {
        Fixture fixture = fixture();
        JsonObject catalog = catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                fixed("minecraft:village/plains/houses/plains_weaponsmith_1", "single_template", "minecraft_template", 8, 8),
                variable("minecraft:village_plains", "jigsaw_assembly", "minecraft_jigsaw_pool")));
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .add("variableSelections", new JsonArray());
        JsonObject result = runD6(fixture, catalog, choice, selectionPlan("fixed_core_cand_01"));

        JsonObject zoneCatalog = result.getAsJsonObject("filteredStructureCatalog")
                .getAsJsonArray("zoneCatalogs")
                .get(0)
                .getAsJsonObject();
        assertEquals(1, zoneCatalog.getAsJsonArray("fixedCandidates").size());
        assertEquals("minecraft:desert_pyramid", zoneCatalog.getAsJsonArray("fixedCandidates")
                .get(0).getAsJsonObject().get("structureId").getAsString());
        assertTrue(zoneCatalog.getAsJsonArray("filteredOut").toString().contains("NOT_CONFIGURED_STRUCTURE_ENTRY"));
    }

    @Test
    void d6ForbidsFixedRatioAndAiCoordinates() throws Exception {
        Fixture fixture = fixture();
        JsonObject badChoice = choicePlan();
        badChoice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("fixedSelections")
                .get(0).getAsJsonObject()
                .addProperty("targetVisibleAreaRatio", 0.2);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> runD6(fixture, catalog(List.of(fixed("minecraft:desert_pyramid",
                                "structure_assembly", "minecraft_place_structure", 16, 16),
                        variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                        badChoice, selectionPlan("fixed_core_cand_01")));
        assertTrue(ex.getMessage().contains("targetVisibleAreaRatio"));
    }

    @Test
    void d6RejectsUnknownVariableMaterializationMode() {
        Fixture fixture = fixture();
        JsonObject badChoice = choicePlan();
        badChoice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "global_jigsaw_mixin");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> runD6(fixture, catalog(List.of(fixed("minecraft:desert_pyramid",
                                "structure_assembly", "minecraft_place_structure", 16, 16),
                        variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                        badChoice, selectionPlan("fixed_core_cand_01")));
        assertTrue(ex.getMessage().contains("materializationMode"));
    }

    @Test
    void d6ProducesFixedPlanAndVariablePoolWithoutPlacingWorld() throws Exception {
        Fixture fixture = fixture();
        JsonObject result = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choicePlan(), selectionPlan("fixed_core_cand_01"));

        assertFalse(result.getAsJsonObject("fixedPlacementCandidateSet").getAsJsonArray("candidates").isEmpty());
        JsonObject placement = result.getAsJsonObject("plannedFixedPlacementMap")
                .getAsJsonArray("placements")
                .get(0)
                .getAsJsonObject();
        assertEquals("fixed_core_cand_01", placement.get("landingCandidateId").getAsString());
        assertEquals("minecraft:desert_pyramid", placement.get("structureId").getAsString());
        assertFalse(placement.has("placed"));

        JsonObject variable = result.getAsJsonObject("structurePoolMap")
                .getAsJsonArray("zonePools")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject();
        assertEquals(0.25, variable.get("targetVisibleAreaRatio").getAsDouble(), 0.0001);
        JsonObject startFootprint = variable.getAsJsonObject("startFootprint");
        assertEquals(12, startFootprint.get("widthBlocks").getAsInt());
        assertEquals(12, startFootprint.get("depthBlocks").getAsInt());
    }

    @Test
    void d6ExposesBroadFixedLandingCandidateSetForRealStructureStartFiltering() throws Exception {
        Fixture fixture = fixture();
        JsonObject result = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choicePlan(), selectionPlan("fixed_core_cand_01"));

        JsonArray candidates = result.getAsJsonObject("fixedPlacementCandidateSet")
                .getAsJsonArray("candidates");
        assertTrue(candidates.size() >= 64);
        assertTrue(candidates.toString().contains("fixed_core_cand_64"));
    }

    @Test
    void d6NormalizesRotationsBeforeComputingFixedFootprints() throws Exception {
        Fixture fixture = fixture();
        JsonObject result = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 8),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choicePlan(), selectionPlan("fixed_core_cand_01"));

        JsonArray candidates = result.getAsJsonObject("fixedPlacementCandidateSet")
                .getAsJsonArray("candidates");
        assertTrue(candidates.toString().contains("CLOCKWISE_90"));
        JsonObject rotated = null;
        for (var elem : candidates) {
            JsonObject candidate = elem.getAsJsonObject();
            if ("CLOCKWISE_90".equals(candidate.get("rotation").getAsString())) {
                rotated = candidate;
                break;
            }
        }
        assertNotNull(rotated);
        JsonObject footprint = rotated.getAsJsonObject("footprint");
        assertEquals(8, footprint.get("maxX").getAsInt() - footprint.get("minX").getAsInt() + 1);
        assertEquals(16, footprint.get("maxZ").getAsInt() - footprint.get("minZ").getAsInt() + 1);
    }

    @Test
    void d6RejectsDebugFixedFootprintSentinelValues() {
        Fixture fixture = fixture();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> runD6(fixture, catalog(List.of(
                                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 0, 0),
                                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                        choicePlan(), selectionPlan("fixed_core_cand_01")));
        assertTrue(ex.getMessage().contains("debug_catalog contains fixed_footprint entries"));
    }

    @Test
    void d6RejectsLegacyCompatCatalogSource() throws Exception {
        Fixture fixture = fixture();
        Path dir = Files.createTempDirectory("city-d6-legacy-compat");
        Path compatPath = dir.resolve("C3_5_StructureCatalog.preprocessed.json");
        Files.writeString(compatPath, "[]");
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "c3_5_compat_catalog");
        source.addProperty("catalogMode", "compat");
        source.addProperty("compatCatalogPath", compatPath.toString());
        source.add("quality", qualityJson(true));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CityStructureD6Planner().plan(dir, fixture.zoneMap(), fixture.buildableAreaMap(),
                        source, choicePlan(), selectionPlan("fixed_core_cand_01")));
        assertTrue(ex.getMessage().contains("compat"));
        assertTrue(ex.getMessage().contains("StructureProfile.jsonl"));
    }

    @Test
    void d6RejectsLegacySemanticFieldsInCatalog() {
        Fixture fixture = fixture();
        JsonObject legacy = fixed("minecraft:desert_pyramid",
                "structure_assembly", "minecraft_place_structure", 16, 16);
        legacy.remove("semanticTerms");
        legacy.remove("functionTerms");
        legacy.add("functionTags", strings("civic_core"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> runD6(fixture, catalog(List.of(legacy,
                                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                        choicePlan(), selectionPlan("fixed_core_cand_01")));
        assertTrue(ex.getMessage().contains("legacy semantic field functionTags"));
    }

    @Test
    void d6ReadsOfficialStructureProfileJsonlWithTerraSenseTerms() throws Exception {
        Fixture fixture = fixture();
        Path dir = Files.createTempDirectory("city-d6-official-profile");
        Path profilePath = dir.resolve("StructureProfile.jsonl");
        Files.writeString(profilePath, fixed("minecraft:desert_pyramid",
                "structure_assembly", "minecraft_place_structure", 16, 16) + "\n"
                + variable("minecraft:village_plains",
                "structure_assembly", "minecraft_place_structure") + "\n");
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "structure_profile_jsonl");
        source.addProperty("catalogMode", "official");
        source.addProperty("profilePath", profilePath.toString());
        source.add("quality", qualityJson(true));

        JsonObject result = new CityStructureD6Planner().plan(dir, fixture.zoneMap(), fixture.buildableAreaMap(),
                source, choicePlan(), selectionPlan("fixed_core_cand_01")).asJson();

        JsonObject profile = result.getAsJsonObject("structureProfileCatalog")
                .getAsJsonArray("structures")
                .get(0).getAsJsonObject();
        assertEquals("function.landmark", profile.getAsJsonArray("functionTerms").get(0).getAsString());
        assertTrue(result.getAsJsonObject("filteredStructureCatalog").toString().contains("semanticTerms"));
    }

    @Test
    void d7GeneratesStartCandidatesAndIsSeedReproducible() throws Exception {
        Fixture fixture = fixture();
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choicePlan(), selectionPlan("fixed_core_cand_01"));

        CityStructureD7Executor executor = new CityStructureD7Executor();
        CityStructureD7Executor.Result first = executor.execute(fixture.zoneMap(), fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                CityStructureD7Executor.PlacementBackend.traceOnly());
        CityStructureD7Executor.Result second = executor.execute(fixture.zoneMap(), fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                CityStructureD7Executor.PlacementBackend.traceOnly());

        assertEquals(CityJson.GSON.toJson(first.startCandidateSets()), CityJson.GSON.toJson(second.startCandidateSets()));
        assertEquals(CityJson.GSON.toJson(first.placedStructureMap()), CityJson.GSON.toJson(second.placedStructureMap()));
        assertFalse(first.startCandidateSets().isEmpty());
        assertTrue(first.placedStructureMap().getAsJsonArray("placedStructures").size() >= 2);
    }

    @Test
    void d7StartCandidatesMustStayInsideFunctionZoneBounds() throws Exception {
        Fixture fixture = narrowZoneFixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .add("fixedSelections", new JsonArray());
        JsonObject d6 = runD6(fixture, catalog(List.of(
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, emptySelectionPlan());

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                CityStructureD7Executor.PlacementBackend.traceOnly());

        JsonArray candidates = result.startCandidateSets().get(0).getAsJsonObject().getAsJsonArray("candidates");
        boolean sawOutOfZoneCandidate = false;
        boolean sawHardPassed = false;
        for (int i = 0; i < candidates.size(); i++) {
            JsonObject candidate = candidates.get(i).getAsJsonObject();
            JsonObject footprint = candidate.getAsJsonObject("candidateFootprint");
            boolean inside = footprint.get("minX").getAsInt() >= 0
                    && footprint.get("minZ").getAsInt() >= 0
                    && footprint.get("maxX").getAsInt() <= 15
                    && footprint.get("maxZ").getAsInt() <= 15;
            if (candidate.get("hardPassed").getAsBoolean()) {
                sawHardPassed = true;
                assertTrue(inside, candidate.toString());
            }
            if (footprint.get("maxX").getAsInt() > 15 || footprint.get("maxZ").getAsInt() > 15) {
                sawOutOfZoneCandidate = true;
                assertFalse(candidate.get("hardPassed").getAsBoolean(), candidate.toString());
            }
        }
        assertTrue(sawOutOfZoneCandidate);
        assertTrue(sawHardPassed);
    }

    @Test
    void d7StartCandidateScorePrefersExpandableAreaAwayFromReservedCells() throws Exception {
        Fixture fixture = reservedCorridorFixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .add("fixedSelections", new JsonArray());
        JsonObject d6 = runD6(fixture, catalog(List.of(
                        variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, emptySelectionPlan());

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                CityStructureD7Executor.PlacementBackend.traceOnly());

        JsonArray candidates = result.startCandidateSets().get(0).getAsJsonObject().getAsJsonArray("candidates");
        JsonObject edge = hardPassedCandidateAt(candidates, 16, 32);
        JsonObject open = hardPassedCandidateAt(candidates, 48, 32);
        assertNotNull(edge);
        assertNotNull(open);
        assertTrue(open.get("score").getAsDouble() > edge.get("score").getAsDouble(),
                "open=" + open + " edge=" + edge);
        JsonObject breakdown = open.getAsJsonObject("scoreBreakdown");
        assertTrue(breakdown.has("expansionScore"));
        assertTrue(breakdown.has("reservedPenalty"));
        assertTrue(breakdown.has("corridorReachCells"));
        assertEquals(1.0, breakdown.get("buildableFit").getAsDouble(), 0.0001);
    }

    @Test
    void d7UsesFootprintOriginOffsetForFixedCommandAnchor() throws Exception {
        Fixture fixture = fixture();
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16, -3, 5),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choicePlan(), selectionPlan("fixed_core_cand_01"));
        JsonObject fixed = d6.getAsJsonObject("plannedFixedPlacementMap")
                .getAsJsonArray("placements")
                .get(0)
                .getAsJsonObject();
        JsonObject footprint = fixed.getAsJsonObject("footprint");
        int expectedX = footprint.get("minX").getAsInt() + 3;
        int expectedZ = footprint.get("minZ").getAsInt() - 5;
        CityStructureD7Executor.PlacementRequest[] fixedRequest = new CityStructureD7Executor.PlacementRequest[1];

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                request -> {
                    if ("fixed_footprint".equals(request.footprintMode())) {
                        fixedRequest[0] = request;
                    }
                    return CityStructureD7Executor.PlacementResult.placed("ok");
                });

        assertNotNull(fixedRequest[0]);
        assertEquals(expectedX, fixedRequest[0].anchorBlock().x());
        assertEquals(expectedZ, fixedRequest[0].anchorBlock().z());
        JsonObject fixedAttempt = result.structureGenerationTrace()
                .getAsJsonArray("fixedPlacements")
                .get(0)
                .getAsJsonObject();
        assertEquals(expectedX, fixedAttempt.getAsJsonObject("commandAnchorBlock").get("x").getAsInt());
        assertEquals(expectedZ, fixedAttempt.getAsJsonObject("commandAnchorBlock").get("z").getAsInt());
    }

    @Test
    void d7FixedFailureDoesNotPlacePartialStructureOrContinueVariableGeneration() throws Exception {
        Fixture fixture = fixture();
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choicePlan(), selectionPlan("fixed_core_cand_01"));

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                request -> "fixed_footprint".equals(request.footprintMode())
                        ? CityStructureD7Executor.PlacementResult.failed("CONFIGURED_STRUCTURE_START_INVALID", "fixed failed")
                        : CityStructureD7Executor.PlacementResult.placed("variable should not run"));

        assertEquals(0, result.placedStructureMap().getAsJsonArray("placedStructures").size());
        assertTrue(result.placedStructureMap().getAsJsonObject("quality")
                .getAsJsonArray("hardBlocks").toString().contains("FIXED_FOOTPRINT_INVALID"));
        assertEquals(0, result.startCandidateSets().size());
        assertEquals(0, result.structureGenerationTrace().getAsJsonArray("variableAttempts").size());
        assertTrue(result.structureGenerationTrace().getAsJsonObject("failureSummary")
                .has("CONFIGURED_STRUCTURE_START_INVALID"));
    }

    @Test
    void d7WaitsForChunksWithoutFailingOrStartingVariableGeneration() throws Exception {
        Fixture fixture = fixture();
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choicePlan(), selectionPlan("fixed_core_cand_01"));

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                request -> "fixed_footprint".equals(request.footprintMode())
                        ? CityStructureD7Executor.PlacementResult.waiting("STRUCTURE_CHUNK_NOT_LOADED", "wait")
                        : CityStructureD7Executor.PlacementResult.placed("variable should not run"));

        assertEquals("waiting", result.structureGenerationTrace().get("status").getAsString());
        assertTrue(result.structureGenerationTrace().getAsJsonObject("waitingSummary")
                .has("STRUCTURE_CHUNK_NOT_LOADED"));
        assertEquals(0, result.placedStructureMap().getAsJsonArray("placedStructures").size());
        assertEquals(0, result.startCandidateSets().size());
        assertEquals(0, result.structureGenerationTrace().getAsJsonArray("variableAttempts").size());
        assertFalse(result.placedStructureMap().getAsJsonObject("quality")
                .getAsJsonArray("hardBlocks").toString().contains("FIXED_FOOTPRINT_INVALID"));
    }

    @Test
    void d7ReusesOnlyRealPlacementLedgerEntriesAcrossCalls() throws Exception {
        Fixture fixture = fixture();
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choicePlan(), selectionPlan("fixed_core_cand_01"));

        CityStructureD7Executor executor = new CityStructureD7Executor();
        CityStructureD7Executor.Result dryRun = executor.execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                CityStructureD7Executor.PlacementBackend.traceOnly());
        assertFalse(dryRun.placedStructureMap().getAsJsonArray("placedStructures")
                .get(0).getAsJsonObject().get("worldMutationApplied").getAsBoolean());
        assertEquals(0, dryRun.placedStructureMap()
                .getAsJsonObject("chunkMaterializationLedger")
                .getAsJsonArray("appliedJobs").size());
        assertTrue(dryRun.structureGenerationTrace().has("materializationJobs"));
        assertTrue(dryRun.structureGenerationTrace().getAsJsonArray("materializationJobs")
                .toString().contains("\"status\":\"planned\""));

        final int[] calls = {0};
        CityStructureD7Executor.Result realRun = executor.execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                request -> {
                    calls[0]++;
                    return CityStructureD7Executor.PlacementResult.placed("real placed");
                },
                dryRun.placedStructureMap());
        assertTrue(calls[0] >= 2);
        assertTrue(realRun.placedStructureMap().getAsJsonArray("placedStructures")
                .get(0).getAsJsonObject().get("worldMutationApplied").getAsBoolean());
        assertTrue(realRun.placedStructureMap()
                .getAsJsonObject("chunkMaterializationLedger")
                .getAsJsonArray("appliedJobs").size() >= 2);
        assertEquals(realRun.placedStructureMap().getAsJsonObject("chunkMaterializationLedger").toString(),
                realRun.structureGenerationTrace().getAsJsonObject("chunkMaterializationLedger").toString());

        final int[] replayCalls = {0};
        CityStructureD7Executor.Result replay = executor.execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                request -> {
                    replayCalls[0]++;
                    return CityStructureD7Executor.PlacementResult.placed("should be reused");
                },
                realRun.placedStructureMap());
        assertEquals(0, replayCalls[0]);
        assertTrue(replay.structureGenerationTrace().getAsJsonArray("fixedPlacements")
                .toString().contains("already_placed"));
        assertTrue(replay.structureGenerationTrace().getAsJsonArray("materializationJobs")
                .toString().contains("\"status\":\"applied\""));
    }

    @Test
    void d7FailureReasonIsStructuredWhenRegistryRejectsVariableStructure() throws Exception {
        Fixture fixture = fixture();
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choicePlan(), selectionPlan("fixed_core_cand_01"));

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                request -> request.footprintMode().equals("variable_area")
                        ? CityStructureD7Executor.PlacementResult.failed("CONFIGURED_STRUCTURE_REGISTRY_MISSING", "missing")
                        : CityStructureD7Executor.PlacementResult.placed("fixed ok"));

        JsonObject summary = result.structureGenerationTrace().getAsJsonObject("failureSummary");
        assertTrue(summary.has("CONFIGURED_STRUCTURE_REGISTRY_MISSING"));
        assertTrue(result.structureGenerationTrace().getAsJsonArray("variableAttempts")
                .toString().contains("CONFIGURED_STRUCTURE_REGISTRY_MISSING"));
    }

    @Test
    void d6PassesBoundedJigsawMaterializationModeToD7Pool() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "bounded_jigsaw");

        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, selectionPlan("fixed_core_cand_01"));

        JsonObject variable = d6.getAsJsonObject("structurePoolMap")
                .getAsJsonArray("zonePools")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject();
        assertEquals("bounded_jigsaw", variable.get("materializationMode").getAsString());
    }

    @Test
    void d7BoundedJigsawUsesDedicatedBackendAndCarriesTrace() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "bounded_jigsaw");
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, selectionPlan("fixed_core_cand_01"));

        final int[] boundedCalls = {0};
        CityStructureD7Executor.PlacementBackend backend = new CityStructureD7Executor.PlacementBackend() {
            @Override
            public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.placed("fixed ok");
            }

            @Override
            public CityStructureD7Executor.PlacementResult placeBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
                boundedCalls[0]++;
                assertEquals("bounded_jigsaw", request.materializationMode());
                assertTrue(request.constraintField().has("buildableCells"));
                assertTrue(request.constraintField().has("reservedCells"));
                BlockBounds footprint = new BlockBounds(16, 16, 23, 23);
                JsonObject trace = boundedTraceWithAcceptedPiece(footprint);
                return CityStructureD7Executor.PlacementResult.placed("bounded ok", trace, footprint, footprint);
            }
        };

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                backend);

        assertTrue(boundedCalls[0] >= 1);
        JsonObject boundedTrace = result.structureGenerationTrace().getAsJsonArray("variableAttempts")
                .get(0).getAsJsonObject()
                .getAsJsonObject("boundedJigsawTrace");
        assertEquals("city_bounded_jigsaw_plan.v0.1", boundedTrace.getAsJsonObject("plan")
                .get("schemaVersion").getAsString());
        assertTrue(result.placedStructureMap().getAsJsonArray("placedStructures")
                .toString().contains("\"maxX\":23"));
    }

    @Test
    void d7DefaultsToSingleMainStartEvenWhenAreaIsPartial() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "bounded_jigsaw");
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, selectionPlan("fixed_core_cand_01"));

        final int[] planCalls = {0};
        final int[] materializeCalls = {0};
        CityStructureD7Executor.PlacementBackend backend = new CityStructureD7Executor.PlacementBackend() {
            @Override
            public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.placed("fixed placed");
            }

            @Override
            public CityStructureD7Executor.PlacementResult planBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
                planCalls[0]++;
                assertTrue(request.targetAreaBlocks() >= request.footprint().widthBlocks()
                        * request.footprint().heightBlocks());
                assertTrue(request.constraintField().toString().contains("occupiedFootprints"));
                JsonObject trace = boundedTraceWithAcceptedPiece(request.footprint());
                trace.addProperty("worldPasteMode", "accepted_piece_template_paste");
                return CityStructureD7Executor.PlacementResult.dryRunAccepted("bounded planned",
                        trace, request.footprint(), request.footprint());
            }

            @Override
            public CityStructureD7Executor.PlacementResult materializeBoundedJigsaw(
                    CityStructureD7Executor.PlacementRequest request, JsonObject selectedPlanTrace) {
                materializeCalls[0]++;
                return CityStructureD7Executor.PlacementResult.placed("bounded placed",
                        selectedPlanTrace, request.footprint(), request.footprint());
            }
        };

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                backend);

        assertTrue(planCalls[0] > 1);
        assertEquals(1, materializeCalls[0]);
        assertEquals(1, result.startCandidateSets().size());
        assertEquals(2, result.placedStructureMap().getAsJsonArray("placedStructures").size());
        JsonObject attempt = result.structureGenerationTrace().getAsJsonArray("variableAttempts")
                .get(0).getAsJsonObject();
        assertTrue(attempt.has("boundedJigsawSamples"));
        assertEquals("HAMLET", attempt.get("feasibility").getAsString());
        assertTrue(attempt.has("selectedSampleId"));
    }

    @Test
    void d7BoundedDryRunSamplesKeepCompactTraceSummaries() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "bounded_jigsaw");
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, selectionPlan("fixed_core_cand_01"));

        CityStructureD7Executor.PlacementBackend backend = new CityStructureD7Executor.PlacementBackend() {
            @Override
            public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.placed("fixed placed");
            }

            @Override
            public CityStructureD7Executor.PlacementResult planBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
                JsonObject trace = boundedTraceWithAcceptedPiece(request.footprint());
                JsonArray rejected = new JsonArray();
                for (int i = 0; i < 80; i++) {
                    JsonObject rejectedPiece = new JsonObject();
                    rejectedPiece.addProperty("pieceId", "rejected_" + i);
                    rejectedPiece.addProperty("reasonCode", "JIGSAW_RUNTIME_OCCUPIED");
                    rejected.add(rejectedPiece);
                }
                trace.add("rejectedPieces", rejected);
                JsonObject rejectionReport = new JsonObject();
                rejectionReport.addProperty("OCCUPIED_LIMITED", rejected.size());
                rejectionReport.addProperty("totalRejectedPieces", rejected.size());
                trace.add("rejectionReport", rejectionReport);
                trace.getAsJsonObject("metrics").addProperty("rejectedPieceCount", rejected.size());
                JsonObject terrainSummary = new JsonObject();
                terrainSummary.addProperty("schemaVersion", "city_terrain_probe_summary.v0.1");
                terrainSummary.addProperty("waiting", false);
                terrainSummary.addProperty("missingChunks", "");
                JsonArray probes = new JsonArray();
                for (int i = 0; i < 40; i++) {
                    JsonObject probe = new JsonObject();
                    probe.addProperty("probeId", "probe_" + i);
                    probe.addProperty("candidateId", "candidate_" + i);
                    probe.add("footprint", boundsJson(request.footprint()));
                    probes.add(probe);
                }
                terrainSummary.addProperty("probeCount", probes.size());
                terrainSummary.add("probes", probes);
                trace.add("terrainProbeSummary", terrainSummary);
                return CityStructureD7Executor.PlacementResult.dryRunAccepted("bounded planned",
                        trace, request.footprint(), request.footprint());
            }

            @Override
            public CityStructureD7Executor.PlacementResult materializeBoundedJigsaw(
                    CityStructureD7Executor.PlacementRequest request, JsonObject selectedPlanTrace) {
                return CityStructureD7Executor.PlacementResult.placed("bounded placed",
                        selectedPlanTrace, request.footprint(), request.footprint());
            }
        };

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                backend);

        JsonObject attempt = result.structureGenerationTrace().getAsJsonArray("variableAttempts")
                .get(0).getAsJsonObject();
        JsonObject firstSample = attempt.getAsJsonArray("boundedJigsawSamples")
                .get(0).getAsJsonObject();
        assertFalse(firstSample.has("boundedJigsawTrace"));
        assertTrue(firstSample.has("boundedJigsawTraceSummary"));
        assertEquals(80, firstSample.getAsJsonObject("boundedJigsawTraceSummary")
                .get("rejectedPieceCount").getAsInt());
        assertTrue(firstSample.getAsJsonObject("boundedJigsawTraceSummary")
                .getAsJsonArray("rejectedPieces").size() < 80);
        JsonObject terrainSummary = firstSample.getAsJsonObject("boundedJigsawTraceSummary")
                .getAsJsonObject("terrainProbeSummary");
        assertFalse(terrainSummary.has("probes"));
        assertEquals(40, terrainSummary.get("probeCount").getAsInt());
        assertTrue(terrainSummary.getAsJsonArray("probeSamples").size() < 40);

        JsonObject selectedTrace = attempt.getAsJsonObject("boundedJigsawTrace");
        assertEquals(80, selectedTrace.get("rejectedPieceCount").getAsInt());
        assertTrue(selectedTrace.getAsJsonArray("rejectedPieces").size() < 80);
        assertFalse(selectedTrace.getAsJsonObject("terrainProbeSummary").has("probes"));
        assertTrue(result.placedStructureMap().getAsJsonArray("placedStructures")
                .get(1).getAsJsonObject()
                .getAsJsonObject("boundedJigsawTrace")
                .getAsJsonArray("acceptedPieces")
                .size() >= 1);
    }

    @Test
    void d7SatelliteStartsRemainOptInAndOccupyRemainingZone() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        JsonObject variable = choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject();
        variable.addProperty("materializationMode", "bounded_jigsaw");
        enableSatelliteStarts(variable);
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, selectionPlan("fixed_core_cand_01"));

        List<BlockBounds> materializedFootprints = new java.util.ArrayList<>();
        CityStructureD7Executor.PlacementBackend backend = new CityStructureD7Executor.PlacementBackend() {
            @Override
            public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.placed("fixed placed");
            }

            @Override
            public CityStructureD7Executor.PlacementResult planBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
                JsonObject trace = boundedTraceWithAcceptedPiece(request.footprint());
                return CityStructureD7Executor.PlacementResult.dryRunAccepted("bounded planned",
                        trace, request.footprint(), request.footprint());
            }

            @Override
            public CityStructureD7Executor.PlacementResult materializeBoundedJigsaw(
                    CityStructureD7Executor.PlacementRequest request, JsonObject selectedPlanTrace) {
                for (BlockBounds existing : materializedFootprints) {
                    assertFalse(existing.overlaps(request.footprint()), request.footprint().toString());
                }
                materializedFootprints.add(request.footprint());
                return CityStructureD7Executor.PlacementResult.placed("bounded placed",
                        selectedPlanTrace, request.footprint(), request.footprint());
            }
        };

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                backend);

        assertTrue(materializedFootprints.size() > 1);
        assertEquals(materializedFootprints.size(), result.startCandidateSets().size());
        assertTrue(result.startCandidateSets().get(1).getAsJsonObject()
                .getAsJsonArray("candidates").toString().contains("hard_filter_failed"));
    }

    @Test
    void d7PreviousVariablePlacementsOccupyRemainingZoneForLaterStarts() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "bounded_jigsaw");
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .add("fixedSelections", new JsonArray());
        JsonObject d6 = runD6(fixture, catalog(List.of(
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, emptySelectionPlan());

        BlockBounds previousFootprint = new BlockBounds(0, 0, 11, 11);
        JsonObject previousMap = previousPlacedStructureMap("var_core", "task_core_var_core_start_001",
                previousFootprint, 144);

        final int[] replayBoundedCalls = {0};
        CityStructureD7Executor.PlacementBackend replayBackend = new CityStructureD7Executor.PlacementBackend() {
            @Override
            public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.placed("fixed placed");
            }

            @Override
            public CityStructureD7Executor.PlacementResult planBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
                replayBoundedCalls[0]++;
                assertFalse(previousFootprint.overlaps(request.footprint()), request.footprint().toString());
                JsonObject trace = boundedTraceWithAcceptedPiece(request.footprint());
                return CityStructureD7Executor.PlacementResult.dryRunAccepted("additional bounded planned",
                        trace, request.footprint(), request.footprint());
            }
        };

        CityStructureD7Executor.Result replay = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                replayBackend,
                previousMap);

        assertEquals(0, replayBoundedCalls[0]);
        assertEquals(0, replay.startCandidateSets().size());
        JsonObject attempt = replay.structureGenerationTrace().getAsJsonArray("variableAttempts")
                .get(0).getAsJsonObject();
        assertEquals("SINGLE_START_ALREADY_PLACED", attempt.get("reasonCode").getAsString());
        assertEquals(1, replay.placedStructureMap().getAsJsonArray("placedStructures").size());
    }

    @Test
    void d7DoesNotRetryFailedVariableStartsWhenOpeningRemainingStartSets() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "bounded_jigsaw");
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .add("fixedSelections", new JsonArray());
        JsonObject d6 = runD6(fixture, catalog(List.of(
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, emptySelectionPlan());

        CityStructureD7Executor.PlacementBackend backend = new CityStructureD7Executor.PlacementBackend() {
            @Override
            public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.placed("fixed placed");
            }

            @Override
            public CityStructureD7Executor.PlacementResult placeBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.failed(
                        "JIGSAW_NO_ACCEPTED_PIECE", "no legal bounded piece");
            }
        };

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                backend);

        assertEquals(1, result.startCandidateSets().size());
        JsonArray attempts = result.structureGenerationTrace().getAsJsonArray("variableAttempts");
        Set<String> firstAttemptedIds = new LinkedHashSet<>();
        JsonObject attempt = attempts.get(0).getAsJsonObject();
        assertEquals("failed", attempt.get("status").getAsString());
        assertTrue(attempt.has("boundedJigsawSamples"));
        for (JsonElement elem : attempt.getAsJsonArray("boundedJigsawSamples")) {
            firstAttemptedIds.add(elem.getAsJsonObject().get("anchorCandidateId").getAsString());
        }
        assertFalse(firstAttemptedIds.isEmpty());
        assertTrue(attempt.has("terminationReport"));
        assertEquals("FAILED", attempt.get("feasibility").getAsString());
    }

    @Test
    void d7BoundedJigsawLedgerRecordsOnlyRealAcceptedPieces() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "bounded_jigsaw");
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, selectionPlan("fixed_core_cand_01"));

        BlockBounds footprint = new BlockBounds(16, 16, 23, 23);
        CityStructureD7Executor.PlacementBackend dryBackend = new CityStructureD7Executor.PlacementBackend() {
            @Override
            public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.dryRunAccepted("fixed dry-run");
            }

            @Override
            public CityStructureD7Executor.PlacementResult planBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.dryRunAccepted("bounded dry-run",
                        boundedTraceWithAcceptedPiece(footprint), footprint, footprint);
            }

            @Override
            public CityStructureD7Executor.PlacementResult materializeBoundedJigsaw(
                    CityStructureD7Executor.PlacementRequest request, JsonObject selectedPlanTrace) {
                return CityStructureD7Executor.PlacementResult.dryRunAccepted("bounded dry-run",
                        selectedPlanTrace, footprint, footprint);
            }
        };

        CityStructureD7Executor.Result dryRun = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                dryBackend);

        JsonObject dryLedger = dryRun.placedStructureMap().getAsJsonObject("chunkMaterializationLedger");
        assertEquals(0, dryLedger.getAsJsonArray("appliedJobs").size());
        assertEquals(0, dryLedger.getAsJsonArray("appliedPieces").size());
        assertEquals(0, dryLedger.get("appliedPieceCount").getAsInt());

        CityStructureD7Executor.PlacementBackend realBackend = new CityStructureD7Executor.PlacementBackend() {
            @Override
            public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.placed("fixed placed");
            }

            @Override
            public CityStructureD7Executor.PlacementResult planBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.dryRunAccepted("bounded planned",
                        boundedTraceWithAcceptedPiece(footprint), footprint, footprint);
            }

            @Override
            public CityStructureD7Executor.PlacementResult materializeBoundedJigsaw(
                    CityStructureD7Executor.PlacementRequest request, JsonObject selectedPlanTrace) {
                return CityStructureD7Executor.PlacementResult.placed("bounded placed",
                        selectedPlanTrace, footprint, footprint);
            }
        };

        CityStructureD7Executor.Result realRun = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                realBackend,
                dryRun.placedStructureMap());

        JsonObject ledger = realRun.placedStructureMap().getAsJsonObject("chunkMaterializationLedger");
        assertEquals(variableAppliedJobCount(ledger),
                ledger.getAsJsonArray("appliedPieces").size());
        assertEquals(ledger.getAsJsonArray("appliedPieces").size(),
                ledger.get("appliedPieceCount").getAsInt());
        JsonObject piece = ledger.getAsJsonArray("appliedPieces").get(0).getAsJsonObject();
        assertEquals("start_piece_1", piece.get("pieceId").getAsString());
        assertEquals("minecraft:village/plains/houses/plains_small_house_1", piece.get("templateId").getAsString());
        assertEquals("minecraft:village/plains/houses", piece.get("poolId").getAsString());
        assertTrue(piece.get("idempotencyKey").getAsString().contains("start_piece_1"));
        assertTrue(piece.get("worldMutationApplied").getAsBoolean());
        assertEquals(ledger.toString(),
                realRun.structureGenerationTrace().getAsJsonObject("chunkMaterializationLedger").toString());
    }

    @Test
    void d7BoundedJigsawStartPieceAdapterLedgerDoesNotApplyUnpastedChildPieces() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "bounded_jigsaw");
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, selectionPlan("fixed_core_cand_01"));

        BlockBounds startFootprint = new BlockBounds(16, 16, 23, 23);
        BlockBounds childFootprint = new BlockBounds(24, 16, 31, 23);
        CityStructureD7Executor.PlacementBackend backend = new CityStructureD7Executor.PlacementBackend() {
            @Override
            public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.placed("fixed placed");
            }

            @Override
            public CityStructureD7Executor.PlacementResult planBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
                JsonObject trace = boundedTraceWithAcceptedPiece(startFootprint);
                trace.addProperty("worldPasteMode", "start_piece_adapter");
                JsonObject child = boundedPiece("child_piece_1",
                        "minecraft:village/plains/streets/straight_01",
                        "minecraft:village/plains/streets", childFootprint);
                trace.getAsJsonArray("acceptedPieces").add(child.deepCopy());
                trace.getAsJsonObject("plan").getAsJsonArray("pieces").add(child.deepCopy());
                return CityStructureD7Executor.PlacementResult.dryRunAccepted("bounded start piece planned",
                        trace, startFootprint, startFootprint);
            }

            @Override
            public CityStructureD7Executor.PlacementResult materializeBoundedJigsaw(
                    CityStructureD7Executor.PlacementRequest request, JsonObject selectedPlanTrace) {
                return CityStructureD7Executor.PlacementResult.placed("bounded start piece placed",
                        selectedPlanTrace, startFootprint, startFootprint);
            }
        };

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                backend);

        JsonObject ledger = result.placedStructureMap().getAsJsonObject("chunkMaterializationLedger");
        assertEquals(variableAppliedJobCount(ledger),
                ledger.getAsJsonArray("appliedPieces").size());
        JsonObject piece = ledger.getAsJsonArray("appliedPieces").get(0).getAsJsonObject();
        assertEquals("start_piece_1", piece.get("pieceId").getAsString());
        assertFalse(ledger.toString().contains("child_piece_1"));
    }

    @Test
    void d7BoundedJigsawAcceptedPiecePasteLedgerAppliesAllAcceptedPieces() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "bounded_jigsaw");
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, selectionPlan("fixed_core_cand_01"));

        BlockBounds startFootprint = new BlockBounds(16, 16, 23, 23);
        BlockBounds childFootprint = new BlockBounds(24, 16, 31, 23);
        CityStructureD7Executor.PlacementBackend backend = new CityStructureD7Executor.PlacementBackend() {
            @Override
            public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.placed("fixed placed");
            }

            @Override
            public CityStructureD7Executor.PlacementResult planBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
                JsonObject trace = boundedTraceWithAcceptedPiece(startFootprint);
                trace.addProperty("worldPasteMode", "accepted_piece_template_paste");
                JsonObject child = boundedPiece("child_piece_1",
                        "minecraft:village/plains/streets/straight_01",
                        "minecraft:village/plains/streets", childFootprint);
                child.addProperty("pasteStatus", "applied");
                child.addProperty("worldMutationApplied", true);
                trace.getAsJsonArray("acceptedPieces").add(child.deepCopy());
                trace.getAsJsonObject("plan").getAsJsonArray("pieces").add(child.deepCopy());
                trace.getAsJsonObject("plan").add("estimatedFootprint", boundsJson(new BlockBounds(16, 16, 31, 23)));
                trace.getAsJsonObject("plan").addProperty("visibleAreaCost", 128);
                trace.getAsJsonObject("metrics").addProperty("acceptedPieceCount", 2);
                trace.getAsJsonObject("metrics").addProperty("visibleAreaCost", 128);
                return CityStructureD7Executor.PlacementResult.dryRunAccepted("bounded accepted pieces planned",
                        trace, new BlockBounds(16, 16, 31, 23), new BlockBounds(16, 16, 31, 23));
            }

            @Override
            public CityStructureD7Executor.PlacementResult materializeBoundedJigsaw(
                    CityStructureD7Executor.PlacementRequest request, JsonObject selectedPlanTrace) {
                return CityStructureD7Executor.PlacementResult.placed("bounded accepted pieces placed",
                        selectedPlanTrace, new BlockBounds(16, 16, 31, 23), new BlockBounds(16, 16, 31, 23));
            }
        };

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                backend);

        JsonObject ledger = result.placedStructureMap().getAsJsonObject("chunkMaterializationLedger");
        assertEquals(variableAppliedJobCount(ledger) * 2,
                ledger.getAsJsonArray("appliedPieces").size());
        assertEquals(ledger.getAsJsonArray("appliedPieces").size(),
                ledger.get("appliedPieceCount").getAsInt());
        assertTrue(ledger.toString().contains("start_piece_1"));
        assertTrue(ledger.toString().contains("child_piece_1"));
    }

    @Test
    void d7PreviewRendersAcceptedBoundedJigsawPieces() throws Exception {
        Fixture fixture = fixture();
        JsonObject placedMap = new JsonObject();
        JsonArray placed = new JsonArray();
        BlockBounds startFootprint = new BlockBounds(16, 16, 23, 23);
        BlockBounds childFootprint = new BlockBounds(24, 16, 31, 23);
        JsonObject trace = boundedTraceWithAcceptedPiece(startFootprint);
        JsonObject child = boundedPiece("child_piece_1",
                "minecraft:village/plains/streets/straight_01",
                "minecraft:village/plains/streets", childFootprint);
        child.addProperty("pasteStatus", "applied");
        child.addProperty("worldMutationApplied", true);
        trace.getAsJsonArray("acceptedPieces").add(child.deepCopy());
        trace.getAsJsonObject("plan").getAsJsonArray("pieces").add(child.deepCopy());
        JsonObject variable = placed("placed_var_core", "var_core", "task_core_var_core_start_001",
                "minecraft:village_plains", "variable_area", new BlockBounds(16, 16, 31, 23));
        variable.add("boundedJigsawTrace", trace);
        placed.add(variable);
        placedMap.add("placedStructures", placed);

        Path output = Files.createTempDirectory("d7-piece-preview");
        CityStructurePreviewRenderer.D7PreviewPaths previews = new CityStructurePreviewRenderer()
                .renderD7(fixture.zoneMap(), fixture.buildableAreaMap(), new JsonArray(), placedMap, output);

        assertTrue(Files.exists(previews.boundedPiecePreview()));
        assertTrue(Files.size(previews.boundedPiecePreview()) > 0);
    }

    @Test
    void d7BoundedJigsawUnsupportedIsStructuredFailure() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "bounded_jigsaw");
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, selectionPlan("fixed_core_cand_01"));

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                request -> CityStructureD7Executor.PlacementResult.placed("fixed ok"));

        assertTrue(result.structureGenerationTrace().getAsJsonObject("failureSummary")
                .has("BOUNDED_JIGSAW_UNSUPPORTED"));
        assertTrue(result.structureGenerationTrace().getAsJsonArray("variableAttempts")
                .toString().contains("BOUNDED_JIGSAW_UNSUPPORTED"));
    }

    @Test
    void d7BoundedJigsawFailureCarriesTraceAndPlan() throws Exception {
        Fixture fixture = fixture();
        JsonObject choice = choicePlan();
        choice.getAsJsonArray("zoneChoices")
                .get(0).getAsJsonObject()
                .getAsJsonArray("variableSelections")
                .get(0).getAsJsonObject()
                .addProperty("materializationMode", "bounded_jigsaw");
        JsonObject d6 = runD6(fixture, catalog(List.of(
                fixed("minecraft:desert_pyramid", "structure_assembly", "minecraft_place_structure", 16, 16),
                variable("minecraft:village_plains", "structure_assembly", "minecraft_place_structure"))),
                choice, selectionPlan("fixed_core_cand_01"));

        CityStructureD7Executor.PlacementBackend backend = new CityStructureD7Executor.PlacementBackend() {
            @Override
            public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
                return CityStructureD7Executor.PlacementResult.placed("fixed ok");
            }

            @Override
            public CityStructureD7Executor.PlacementResult placeBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
                JsonObject trace = new JsonObject();
                trace.addProperty("schemaVersion", "city_bounded_jigsaw_trace.v0.1");
                trace.addProperty("capability", "bounded_jigsaw_supported");
                trace.add("acceptedPieces", new JsonArray());
                JsonArray stopped = new JsonArray();
                JsonObject stoppedBranch = new JsonObject();
                stoppedBranch.addProperty("reasonCode", "JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA");
                stopped.add(stoppedBranch);
                trace.add("stoppedBranches", stopped);
                JsonObject plan = new JsonObject();
                plan.addProperty("schemaVersion", "city_bounded_jigsaw_plan.v0.1");
                plan.add("pieces", new JsonArray());
                plan.add("stoppedBranches", stopped.deepCopy());
                trace.add("plan", plan);
                return CityStructureD7Executor.PlacementResult.failed("JIGSAW_NO_ACCEPTED_PIECE",
                        "no accepted piece", trace);
            }
        };

        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                fixture.zoneMap(),
                fixture.buildableAreaMap(),
                d6.getAsJsonObject("plannedFixedPlacementMap"),
                d6.getAsJsonObject("structurePoolMap"),
                12345L,
                backend);

        JsonObject attempt = result.structureGenerationTrace().getAsJsonArray("variableAttempts")
                .get(0).getAsJsonObject();
        assertEquals("failed", attempt.get("status").getAsString());
        assertEquals("JIGSAW_NO_ACCEPTED_PIECE", attempt.get("reasonCode").getAsString());
        assertEquals("city_bounded_jigsaw_plan.v0.1", attempt.getAsJsonObject("boundedJigsawTrace")
                .getAsJsonObject("plan")
                .get("schemaVersion").getAsString());
        assertTrue(attempt.getAsJsonObject("boundedJigsawTrace")
                .getAsJsonArray("stoppedBranches")
                .toString().contains("JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA"));
    }

    private JsonObject boundedTraceWithAcceptedPiece(BlockBounds footprint) {
        JsonObject piece = boundedPiece("start_piece_1",
                "minecraft:village/plains/houses/plains_small_house_1",
                "minecraft:village/plains/houses", footprint);

        JsonArray pieces = new JsonArray();
        pieces.add(piece);
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_bounded_jigsaw_plan.v0.1");
        plan.add("pieces", pieces.deepCopy());
        plan.add("stoppedBranches", new JsonArray());
        plan.add("estimatedFootprint", boundsJson(footprint));
        plan.addProperty("visibleAreaCost", footprint.widthBlocks() * footprint.heightBlocks());
        JsonObject quality = new JsonObject();
        quality.addProperty("acceptedPieceCount", 1);
        quality.addProperty("stoppedBranchCount", 0);
        quality.addProperty("startPieceOnly", true);
        quality.addProperty("areaFillRatio", 0.05d);
        quality.addProperty("compactness", 1.0d);
        plan.add("quality", quality);

        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", "city_bounded_jigsaw_trace.v0.1");
        trace.addProperty("capability", "bounded_jigsaw_supported");
        trace.addProperty("sourceStructureId", "minecraft:village_plains");
        trace.addProperty("targetAreaBlocks", 1024);
        trace.add("acceptedPieces", pieces);
        trace.add("rejectedPieces", new JsonArray());
        trace.add("stoppedBranches", new JsonArray());
        trace.add("failureSummary", new JsonObject());
        trace.add("rejectionReport", new JsonObject());
        JsonObject termination = new JsonObject();
        termination.addProperty("NO_FRONTIER", 1);
        trace.add("terminationReport", termination);
        JsonObject metrics = new JsonObject();
        metrics.addProperty("acceptedPieceCount", 1);
        metrics.addProperty("rejectedPieceCount", 0);
        metrics.addProperty("stoppedBranchCount", 0);
        metrics.addProperty("visibleAreaCost", footprint.widthBlocks() * footprint.heightBlocks());
        metrics.addProperty("targetAreaBlocks", 1024);
        metrics.addProperty("areaFillRatio", 0.05d);
        metrics.addProperty("maxDepth", 4);
        metrics.addProperty("maxAcceptedDepth", 0);
        metrics.addProperty("compactness", 1.0d);
        trace.add("metrics", metrics);
        trace.add("plan", plan);
        return trace;
    }

    private void enableSatelliteStarts(JsonObject variableSelection) {
        JsonObject config = new JsonObject();
        config.addProperty("enableSatelliteStarts", true);
        config.addProperty("sampleCandidateCount", 2);
        config.addProperty("sampleSeedsPerCandidate", 1);
        variableSelection.add("boundedJigsawConfig", config);
    }

    private JsonObject boundedPiece(String pieceId, String templateId, String poolId, BlockBounds footprint) {
        JsonObject piece = new JsonObject();
        piece.addProperty("pieceId", pieceId);
        piece.addProperty("templateId", templateId);
        piece.addProperty("poolId", poolId);
        JsonObject anchor = new JsonObject();
        anchor.addProperty("x", footprint.minX());
        anchor.addProperty("y", 64);
        anchor.addProperty("z", footprint.minZ());
        piece.add("anchorBlock", anchor);
        piece.addProperty("rotation", "NONE");
        piece.add("footprint", boundsJson(footprint));
        piece.addProperty("visibleAreaCost", footprint.widthBlocks() * footprint.heightBlocks());
        piece.addProperty("validatorResult", "passed");
        return piece;
    }

    private JsonObject placed(String placedId, String sourceSelectionId, String anchorCandidateId,
                              String structureId, String footprintMode, BlockBounds footprint) {
        JsonObject obj = new JsonObject();
        obj.addProperty("placedId", placedId);
        obj.addProperty("sourceSelectionId", sourceSelectionId);
        obj.addProperty("anchorCandidateId", anchorCandidateId);
        obj.addProperty("zonePatchId", "core");
        obj.addProperty("structureId", structureId);
        obj.addProperty("footprintMode", footprintMode);
        obj.addProperty("placementKind", "minecraft_place_structure");
        obj.addProperty("placementCommand", "place structure " + structureId + " <x> <y> <z>");
        obj.addProperty("materializationMode", "bounded_jigsaw");
        obj.add("anchorBlock", footprint.center().asJson());
        obj.add("commandAnchorBlock", footprint.center().asJson());
        obj.addProperty("rotation", "NONE");
        obj.add("footprint", boundsJson(footprint));
        obj.add("clearanceFootprint", boundsJson(footprint));
        obj.addProperty("visibleAreaCost", footprint.widthBlocks() * footprint.heightBlocks());
        obj.addProperty("worldMutationApplied", true);
        return obj;
    }

    private JsonObject boundsJson(BlockBounds footprint) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", footprint.minX());
        obj.addProperty("minZ", footprint.minZ());
        obj.addProperty("maxX", footprint.maxX());
        obj.addProperty("maxZ", footprint.maxZ());
        return obj;
    }

    private BlockBounds boundsJsonObject(JsonObject footprint) {
        return new BlockBounds(
                footprint.get("minX").getAsInt(),
                footprint.get("minZ").getAsInt(),
                footprint.get("maxX").getAsInt(),
                footprint.get("maxZ").getAsInt());
    }

    private int variableAppliedJobCount(JsonObject ledger) {
        int count = 0;
        for (JsonElement elem : ledger.getAsJsonArray("appliedJobs")) {
            JsonObject job = elem.getAsJsonObject();
            if ("variable_area".equals(job.get("footprintMode").getAsString())) {
                count++;
            }
        }
        return count;
    }

    private JsonObject previousPlacedStructureMap(String sourceSelectionId, String anchorCandidateId,
                                                  BlockBounds footprint, int visibleAreaCost) {
        JsonObject map = new JsonObject();
        map.addProperty("schemaVersion", "city_placed_structure_map.v0.1");
        map.addProperty("cityId", "city_test");
        JsonArray placed = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("placedId", "placed_task_core_var_core_" + anchorCandidateId);
        item.addProperty("sourceSelectionId", sourceSelectionId);
        item.addProperty("anchorCandidateId", anchorCandidateId);
        item.addProperty("zonePatchId", "core");
        item.addProperty("structureId", "minecraft:village_plains");
        item.addProperty("footprintMode", "variable_area");
        item.addProperty("placementKind", "minecraft_place_structure");
        item.addProperty("placementCommand", "place structure minecraft:village_plains <x> <y> <z>");
        item.addProperty("materializationMode", "bounded_jigsaw");
        item.add("anchorBlock", footprint.center().asJson());
        item.add("commandAnchorBlock", footprint.center().asJson());
        item.addProperty("rotation", "NONE");
        item.add("footprint", boundsJson(footprint));
        item.add("clearanceFootprint", boundsJson(footprint));
        item.addProperty("visibleAreaCost", visibleAreaCost);
        item.addProperty("worldMutationApplied", true);
        placed.add(item);
        map.add("placedStructures", placed);
        map.add("remainingVisibleAreaByZone", new JsonObject());
        map.add("chunkMaterializationLedger", new JsonObject());
        map.add("quality", qualityJson(true));
        return map;
    }

    private JsonObject runD6(Fixture fixture, JsonObject catalog, JsonObject choicePlan,
                             JsonObject selectionPlan) throws Exception {
        Path dir = Files.createTempDirectory("city-d6d7-test");
        Path catalogPath = dir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, CityJson.GSON.toJson(catalog));
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        source.add("quality", qualityJson(true));
        return new CityStructureD6Planner().plan(dir, fixture.zoneMap(), fixture.buildableAreaMap(),
                source, choicePlan, selectionPlan).asJson();
    }

    private Fixture fixture() {
        FunctionZoneMap zoneMap = new FunctionZoneMap(
                FunctionZoneMap.CURRENT_SCHEMA_VERSION,
                "city_test",
                new PlanningGrid(0, 0, 16, 8, 8),
                List.of(zone("core", CityFunctionType.CIVIC_CORE, 0, 0, 127, 127)),
                List.of(),
                new CityQualityReport(true, 100, List.of(), List.of(), List.of(), new JsonObject()));
        BuildableAreaMap buildableAreaMap = new BuildableAreaMap(
                BuildableAreaMap.CURRENT_SCHEMA_VERSION,
                "city_test",
                zoneMap.grid(),
                List.of(buildableZone("core", CityFunctionType.CIVIC_CORE, zoneMap.grid())),
                new CityQualityReport(true, 100, List.of(), List.of(), List.of(), new JsonObject()));
        return new Fixture(zoneMap, buildableAreaMap);
    }

    private Fixture narrowZoneFixture() {
        FunctionZoneMap zoneMap = new FunctionZoneMap(
                FunctionZoneMap.CURRENT_SCHEMA_VERSION,
                "city_test",
                new PlanningGrid(0, 0, 16, 2, 2),
                List.of(zone("core", CityFunctionType.CIVIC_CORE, 0, 0, 15, 15)),
                List.of(),
                new CityQualityReport(true, 100, List.of(), List.of(), List.of(), new JsonObject()));
        BuildableAreaMap buildableAreaMap = new BuildableAreaMap(
                BuildableAreaMap.CURRENT_SCHEMA_VERSION,
                "city_test",
                zoneMap.grid(),
                List.of(buildableZone("core", CityFunctionType.CIVIC_CORE, zoneMap.grid())),
                new CityQualityReport(true, 100, List.of(), List.of(), List.of(), new JsonObject()));
        return new Fixture(zoneMap, buildableAreaMap);
    }

    private Fixture reservedCorridorFixture() {
        PlanningGrid grid = new PlanningGrid(0, 0, 16, 7, 7);
        FunctionZoneMap zoneMap = new FunctionZoneMap(
                FunctionZoneMap.CURRENT_SCHEMA_VERSION,
                "city_test",
                grid,
                List.of(zone("core", CityFunctionType.CIVIC_CORE, 0, 0, 111, 111)),
                List.of(),
                new CityQualityReport(true, 100, List.of(), List.of(), List.of(), new JsonObject()));
        BuildableAreaMap buildableAreaMap = new BuildableAreaMap(
                BuildableAreaMap.CURRENT_SCHEMA_VERSION,
                "city_test",
                grid,
                List.of(buildableZoneWithReserved("core", CityFunctionType.CIVIC_CORE, grid,
                        Set.of(cellKey(0, 0), cellKey(0, 1), cellKey(0, 2), cellKey(0, 3),
                                cellKey(0, 4), cellKey(0, 5), cellKey(0, 6)))),
                new CityQualityReport(true, 100, List.of(), List.of(), List.of(), new JsonObject()));
        return new Fixture(zoneMap, buildableAreaMap);
    }

    private BuildableAreaMap.ZoneBuildability buildableZone(String zoneId, CityFunctionType type, PlanningGrid grid) {
        List<BuildableAreaMap.BuildableCell> cells = new java.util.ArrayList<>();
        for (int x = 0; x < grid.cellsX(); x++) {
            for (int z = 0; z < grid.cellsZ(); z++) {
                cells.add(new BuildableAreaMap.BuildableCell(x, z, grid.cellToBlockX(x), grid.cellToBlockZ(z)));
            }
        }
        int area = cells.size() * grid.cellStepBlocks() * grid.cellStepBlocks();
        return new BuildableAreaMap.ZoneBuildability(zoneId, type,
                cells.size(), 0, cells.size(), area, 0, area, List.of(), cells);
    }

    private BuildableAreaMap.ZoneBuildability buildableZoneWithReserved(String zoneId, CityFunctionType type,
                                                                        PlanningGrid grid, Set<Long> reservedKeys) {
        List<BuildableAreaMap.ReservedCell> reserved = new java.util.ArrayList<>();
        List<BuildableAreaMap.BuildableCell> buildable = new java.util.ArrayList<>();
        for (int x = 0; x < grid.cellsX(); x++) {
            for (int z = 0; z < grid.cellsZ(); z++) {
                if (reservedKeys.contains(cellKey(x, z))) {
                    reserved.add(new BuildableAreaMap.ReservedCell(x, z, grid.cellToBlockX(x), grid.cellToBlockZ(z),
                            List.of("test_reserved_corridor"), List.of("road")));
                } else {
                    buildable.add(new BuildableAreaMap.BuildableCell(x, z, grid.cellToBlockX(x), grid.cellToBlockZ(z)));
                }
            }
        }
        int cellArea = grid.cellStepBlocks() * grid.cellStepBlocks();
        return new BuildableAreaMap.ZoneBuildability(zoneId, type,
                grid.cellsX() * grid.cellsZ(), reserved.size(), buildable.size(),
                grid.cellsX() * grid.cellsZ() * cellArea, reserved.size() * cellArea,
                buildable.size() * cellArea, reserved, buildable);
    }

    private long cellKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private JsonObject hardPassedCandidateAt(JsonArray candidates, int minX, int minZ) {
        for (JsonElement elem : candidates) {
            JsonObject candidate = elem.getAsJsonObject();
            JsonObject footprint = candidate.getAsJsonObject("candidateFootprint");
            if (candidate.get("hardPassed").getAsBoolean()
                    && footprint.get("minX").getAsInt() == minX
                    && footprint.get("minZ").getAsInt() == minZ) {
                return candidate;
            }
        }
        return null;
    }

    private FunctionZonePatch zone(String id, CityFunctionType type, int minX, int minZ, int maxX, int maxZ) {
        return new FunctionZonePatch(
                id,
                "group_" + id,
                id,
                type,
                semanticTerms(type),
                List.of("patch_" + id),
                new BlockBounds(minX, minZ, maxX, maxZ),
                List.of(),
                (maxX - minX + 1) * (maxZ - minZ + 1),
                "",
                id + "_stats",
                "",
                "");
    }

    private List<String> semanticTerms(CityFunctionType type) {
        return switch (type) {
            case CIVIC_CORE -> List.of("function.landmark");
            case RESIDENTIAL -> List.of("function.村庄");
            case PRODUCTION -> List.of("function.utility");
            case MARKET -> List.of("function.trade");
            case FARM_OR_PASTURE -> List.of("function.农场");
            case DEFENSE -> List.of("function.瞭望塔");
            case HARBOR_OR_WATERFRONT -> List.of("function.灯塔", "function.贸易船");
            case SACRED_OR_CULTURAL -> List.of("function.教堂");
        };
    }

    private JsonObject choicePlan() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_structure_choice_plan.v0.1",
                  "cityId": "city_test",
                  "zoneChoices": [
                    {
                      "zonePatchId": "core",
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
                          "weight": 3,
                          "reason": "temporary variable configured structure fixture"
                        }
                      ]
                    }
                  ]
                }
                """).getAsJsonObject();
    }

    private JsonObject selectionPlan(String candidateId) {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_fixed_placement_selection_plan.v0.1",
                  "cityId": "city_test",
                  "selections": [
                    {
                      "selectionId": "fixed_core",
                      "landingCandidateId": "%s",
                      "reason": "choose first program candidate"
                    }
                  ]
                }
                """.formatted(candidateId)).getAsJsonObject();
    }

    private JsonObject emptySelectionPlan() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_fixed_placement_selection_plan.v0.1",
                  "cityId": "city_test",
                  "selections": []
                }
                """).getAsJsonObject();
    }

    private JsonObject catalog(List<JsonObject> structures) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_structure_profile_catalog.v0.1");
        obj.addProperty("catalogMode", "debug");
        JsonObject source = new JsonObject();
        source.addProperty("basis", "synthetic unit-test fixture");
        obj.add("source", source);
        JsonArray array = new JsonArray();
        structures.forEach(array::add);
        obj.add("structures", array);
        obj.add("quality", qualityJson(true));
        return obj;
    }

    private JsonObject fixed(String id, String sampleType, String placementKind, int width, int depth) {
        return fixed(id, sampleType, placementKind, width, depth, 0, 0);
    }

    private JsonObject fixed(String id, String sampleType, String placementKind,
                             int width, int depth, int originOffsetX, int originOffsetZ) {
        JsonObject obj = base(id, sampleType, placementKind, "fixed_footprint");
        obj.add("semanticTerms", strings("function.landmark", "style.debug", "placement.inside_zone",
                "usage.public_core", "quality.debug_usable"));
        obj.add("functionTerms", strings("function.landmark"));
        JsonObject footprint = new JsonObject();
        footprint.addProperty("widthBlocks", width);
        footprint.addProperty("depthBlocks", depth);
        footprint.addProperty("heightBlocks", 12);
        obj.add("fixedFootprint", footprint);
        JsonObject originOffset = new JsonObject();
        originOffset.addProperty("x", originOffsetX);
        originOffset.addProperty("z", originOffsetZ);
        obj.add("footprintOriginOffset", originOffset);
        obj.addProperty("visibleAreaCost", (width + 4) * (depth + 4));
        obj.addProperty("clearanceBlocks", 2);
        return obj;
    }

    private JsonObject variable(String id, String sampleType, String placementKind) {
        JsonObject obj = base(id, sampleType, placementKind, "variable_area");
        obj.add("semanticTerms", strings("function.村庄", "style.debug", "placement.inside_zone",
                "usage.filler", "quality.debug_usable"));
        obj.add("functionTerms", strings("function.村庄"));
        JsonObject range = new JsonObject();
        range.addProperty("minAreaBlocks", 128);
        range.addProperty("maxAreaBlocks", 25600);
        JsonObject start = new JsonObject();
        start.addProperty("widthBlocks", 12);
        start.addProperty("depthBlocks", 12);
        start.addProperty("heightBlocks", 10);
        range.add("startFootprint", start);
        obj.add("expectedAreaRange", range);
        return obj;
    }

    private JsonObject base(String id, String sampleType, String placementKind, String footprintMode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("structureId", id);
        obj.addProperty("sourceProfileRef", "synthetic://unit-test/" + id.replace(':', '_').replace('/', '_'));
        obj.addProperty("profileType", "single");
        obj.addProperty("sampleType", sampleType);
        obj.addProperty("placementKind", placementKind);
        obj.addProperty("placementCommand", "place structure " + id + " <x> <y> <z>");
        obj.addProperty("footprintMode", footprintMode);
        obj.add("styleTerms", strings("style.debug"));
        obj.add("placementTerms", strings("placement.inside_zone"));
        obj.add("usageTerms", strings("usage.public_core"));
        obj.add("qualityTerms", strings("quality.debug_usable"));
        obj.add("allowedRotations", strings("NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"));
        return obj;
    }

    private JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private JsonObject qualityJson(boolean passed) {
        JsonObject quality = new JsonObject();
        quality.addProperty("passed", passed);
        quality.addProperty("score", passed ? 100 : 0);
        quality.add("hardBlocks", new JsonArray());
        quality.add("warnings", new JsonArray());
        quality.add("needsReview", new JsonArray());
        quality.add("metrics", new JsonObject());
        return quality;
    }

    private record Fixture(FunctionZoneMap zoneMap, BuildableAreaMap buildableAreaMap) {
    }
}
