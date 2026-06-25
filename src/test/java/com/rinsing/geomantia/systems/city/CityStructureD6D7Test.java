package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityStructureD6Planner;
import com.rinsing.geomantia.systems.city.application.CityStructureD7Executor;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertTrue(replay.structureGenerationTrace().getAsJsonArray("variableAttempts")
                .toString().contains("already_placed"));
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
                JsonObject trace = new JsonObject();
                trace.addProperty("schemaVersion", "city_bounded_jigsaw_trace.v0.1");
                trace.addProperty("capability", "bounded_jigsaw_supported");
                trace.add("acceptedPieces", new JsonArray());
                JsonObject plan = new JsonObject();
                plan.addProperty("schemaVersion", "city_bounded_jigsaw_plan.v0.1");
                plan.add("pieces", new JsonArray());
                plan.add("stoppedBranches", new JsonArray());
                trace.add("plan", plan);
                BlockBounds footprint = new BlockBounds(16, 16, 23, 23);
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

        assertEquals(1, boundedCalls[0]);
        JsonObject boundedTrace = result.structureGenerationTrace().getAsJsonArray("variableAttempts")
                .get(0).getAsJsonObject()
                .getAsJsonObject("boundedJigsawTrace");
        assertEquals("city_bounded_jigsaw_plan.v0.1", boundedTrace.getAsJsonObject("plan")
                .get("schemaVersion").getAsString());
        assertTrue(result.placedStructureMap().getAsJsonArray("placedStructures")
                .toString().contains("\"maxX\":23"));
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

    private FunctionZonePatch zone(String id, CityFunctionType type, int minX, int minZ, int maxX, int maxZ) {
        return new FunctionZonePatch(
                id,
                "group_" + id,
                id,
                type,
                List.of("patch_" + id),
                new BlockBounds(minX, minZ, maxX, maxZ),
                List.of(),
                (maxX - minX + 1) * (maxZ - minZ + 1),
                "",
                id + "_stats",
                "",
                "");
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
        obj.add("functionTags", strings("civic_core", "market", "harbor_or_waterfront"));
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
        obj.add("functionTags", strings("civic_core", "residential", "market"));
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
        obj.add("styleTags", strings("debug"));
        obj.add("placementTags", strings("inside_zone"));
        obj.add("usageTags", strings("public_core"));
        obj.add("qualityTags", strings("debug_usable"));
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
