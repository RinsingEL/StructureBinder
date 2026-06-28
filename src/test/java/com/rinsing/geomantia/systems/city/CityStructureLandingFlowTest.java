package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityReservationMaskPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeFacts;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeProfiler;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CityStructureLandingFlowTest {
    @Test
    void d4BuildsStructureAnchorMapWithFixedAndJigsawReservationEnvelope() throws Exception {
        Fixture fixture = fixture();
        CityStructureAnchorPlanner.Result result = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()));

        JsonObject anchorMap = result.structureAnchorMap();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals(2, anchorMap.getAsJsonArray("anchors").size());

        JsonObject fixed = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        JsonObject jigsaw = anchorMap.getAsJsonArray("anchors").get(1).getAsJsonObject();
        assertEquals("fixedFootprint+clearance", fixed.get("reservedEnvelopePolicy").getAsString());
        assertEquals("startFootprint+jigsawMaxExpansionRadius+clearance+roadAccessMargin",
                jigsaw.get("reservedEnvelopePolicy").getAsString());
        assertEquals(8, fixed.get("reservedEnvelopeRadiusBlocks").getAsInt());
        assertEquals(78, jigsaw.get("reservedEnvelopeRadiusBlocks").getAsInt());
        assertEquals("function.village", jigsaw.getAsJsonArray("functionTerms").get(0).getAsString());
    }

    @Test
    void envelopeProfilerBuildsPercentileFactsAndD4ConsumesThem() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeProfiler.Result factsResult = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:village_plains"), 20,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-10 - sampleIndex, -12, 20 + sampleIndex, 24),
                                4 + sampleIndex,
                                "config_hash",
                                "pack_hash"));
        Path factsPath = fixture.baseDir().resolve("structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));

        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()),
                        CityStructureEnvelopeFacts.load(factsPath))
                .structureAnchorMap();

        JsonObject jigsaw = anchorMap.getAsJsonArray("anchors").get(1).getAsJsonObject();
        assertEquals("structureEnvelopeFacts:P95+clearance/P99+vegetationMargin",
                jigsaw.get("reservedEnvelopePolicy").getAsString());
        assertTrue(jigsaw.has("maskEnvelope"));
        assertTrue(jigsaw.has("safetyEnvelope"));
        assertTrue(jigsaw.has("structureEnvelopeFact"));
    }

    @Test
    void d4RequiresEnvelopeFactsForTrekStructures() throws Exception {
        Fixture fixture = fixture();
        Path catalogPath = fixture.baseDir().resolve("trek_debug_catalog.json");
        Files.writeString(catalogPath, trekDebugCatalog());
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());

        JsonObject plan = singleAnchorPlan(fixture.review());
        plan.getAsJsonArray("anchors").get(0).getAsJsonObject()
                .addProperty("structureId", "trek:overworld/medium/farm");
        JsonObject result = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), source, plan)
                .asJson();

        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.getAsJsonObject("qualityReport").getAsJsonArray("hardBlocks")
                .toString()
                .contains("structure envelope facts are required"));
    }

    @Test
    void d4RejectsLegacyFunctionZonePayload() throws Exception {
        Fixture fixture = fixture();
        JsonObject legacy = anchorPlan(fixture.review());
        legacy.addProperty("functionType", "civic_core");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CityStructureAnchorPlanner()
                        .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), legacy));
        assertTrue(ex.getMessage().contains("LEGACY_CITY_FUNCTION_ZONE_FLOW_REMOVED"));
    }

    @Test
    void d4OfficialProfileRequiresApprovedReviewState() throws Exception {
        Fixture fixture = fixture();
        Path profilePath = fixture.baseDir().resolve("StructureProfile.jsonl");
        JsonObject profile = JsonParser.parseString("""
                {
                  "structureId": "minecraft:desert_pyramid",
                  "profileType": "single",
                  "footprintMode": "fixed_footprint",
                  "functionTerms": ["function.landmark"],
                  "qualityTerms": ["quality.usable"],
                  "fixedFootprint": {"widthBlocks": 20, "depthBlocks": 12, "heightBlocks": 10}
                }
                """).getAsJsonObject();
        Files.writeString(profilePath, profile + "\n");
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "structure_profile_jsonl");
        source.addProperty("catalogMode", "official");
        source.addProperty("profilePath", profilePath.toString());

        JsonObject result = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), source, singleAnchorPlan(fixture.review()))
                .asJson();

        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.getAsJsonObject("qualityReport").getAsJsonArray("hardBlocks")
                .toString()
                .contains("approved TerraSense catalog"));
    }

    @Test
    void d5ReservationMaskCoversStructureEnvelopeAndRoadAccess() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();

        CityReservationMaskPlanner.Result result = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap);

        JsonObject mask = result.reservationMaskPlan();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals(2, mask.getAsJsonArray("noVanillaStructureMask").size());
        assertTrue(mask.getAsJsonArray("noVegetationMask").size() >= 4);
        assertTrue(mask.getAsJsonObject("hookRequirements").get("required").getAsBoolean());
        String operations = result.buildOperationPlan().getAsJsonArray("operations").toString();
        assertTrue(operations.contains("clearVegetation"));
        assertTrue(operations.contains("surfaceFill"));
    }

    @Test
    void d5ActivateWritesPlannedStructureRegistryForWorldgenHook() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject mask = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap)
                .reservationMaskPlan();
        Path serverRoot = Files.createTempDirectory("city-mask-registry");

        JsonObject active = CityReservationMaskRegistry.activate(mask, anchorMap,
                "run_test", fixture.context().cityId(), serverRoot);

        assertEquals(1, active.getAsJsonArray("plannedStructures").size());
        assertTrue(Files.exists(CityReservationMaskRegistry.plannedRegistryPath(serverRoot)));
        JsonObject planned = active.getAsJsonArray("plannedStructures").get(0).getAsJsonObject();
        ChunkPos anchorChunk = new ChunkPos(
                planned.getAsJsonObject("anchorChunk").get("x").getAsInt(),
                planned.getAsJsonObject("anchorChunk").get("z").getAsInt());
        assertEquals(1, CityReservationMaskRegistry.plannedStructuresForChunk(anchorChunk).size());
    }

    @Test
    void d6WorldgenPlanDoesNotCallLatePlacementBackend() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();
        FakePlacementBackend backend = new FakePlacementBackend("sig", true);

        CityStructureMaterializationPlanner planner = new CityStructureMaterializationPlanner();
        CityStructureMaterializationPlanner.Result dryRun = planner.planWorldgen(anchorMap,
                CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(), backend, null);

        assertEquals("worldgen_time_planned_registry",
                dryRun.structureMaterializationPlan().get("dryRunMode").getAsString());
        assertEquals("registry_structure_start_no_world_mutation",
                dryRun.structureMaterializationPlan().get("preflightMode").getAsString());
        assertEquals(2, dryRun.structureMaterializationPlan().getAsJsonArray("plannedWorldgenStructures").size());
        assertEquals(0, dryRun.structureMaterializationPlan().getAsJsonArray("structures").size());
        assertEquals(0, dryRun.placedStructureLedger().getAsJsonArray("placedStructures").size());
        assertEquals(2, backend.planCalls);
        assertEquals(0, backend.placeCalls);
        JsonObject planned = dryRun.structureMaterializationPlan()
                .getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();
        assertTrue(planned.has("expectedStartSignature"));
        assertTrue(planned.has("pieceBoxes"));

        backend.planCalls = 0;
        backend.placeCalls = 0;
        CityStructureMaterializationPlanner.Result recheck = planner.executeWorldgen(
                dryRun.structureMaterializationPlan(), new JsonObject(),
                CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(), true);
        assertEquals(0, recheck.placedStructureLedger().getAsJsonArray("placedStructures").size());
        assertEquals(0, backend.planCalls);
        assertEquals(0, backend.placeCalls);
        assertTrue(recheck.structureMaterializationTrace()
                .getAsJsonObject("waitingSummary")
                .has("WAITING_FOR_WORLDGEN"));
    }

    @Test
    void debugLateMaterializeStillRequiresSameStartSignatureBeforeLedgerWrite() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();
        CityStructureMaterializationPlanner planner = new CityStructureMaterializationPlanner();
        CityStructureMaterializationPlanner.Result dryRun = planner.plan(anchorMap,
                new FakePlacementBackend("sig_a", true), null);

        CityStructureMaterializationPlanner.Result result = planner.execute(
                dryRun.structureMaterializationPlan(), new FakePlacementBackend("sig_b", true),
                null, true);

        assertEquals(0, result.placedStructureLedger().getAsJsonArray("placedStructures").size());
        assertTrue(result.structureMaterializationTrace()
                .getAsJsonObject("failureSummary")
                .has("START_SIGNATURE_MISMATCH"));
    }

    private static Fixture fixture() throws Exception {
        Path baseDir = Files.createTempDirectory("city-structure-landing");
        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder(config)
                .build("city_test", "realm_test", "minecraft:overworld",
                        "city_test", "candidate_test", 0, 0,
                        "village", "village", 160, 4, null);
        CityLandformReviewPackage review = new com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder(config)
                .build(context, List.of(
                        patch("plain", LandformType.PLAIN, -360, -360, -260, -260),
                        patch("slope", LandformType.PLAIN, 260, 260, 360, 360)));
        Path catalogPath = baseDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        return new Fixture(baseDir, context, review, source);
    }

    private static JsonObject anchorPlan(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        LandformPatchSummary second = review.landformPatches().get(1);
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
                      "roadAccessIntent": "primary_access"
                    },
                    {
                      "anchorId": "anchor_village",
                      "structureId": "minecraft:village_plains",
                      "sourcePatchIds": ["%s"],
                      "anchorBlock": {"x": %d, "z": %d},
                      "rotation": "NONE",
                      "intentTerms": ["function.village"],
                      "priority": 2,
                      "roadAccessIntent": "secondary_access"
                    }
                  ]
                }
                """.formatted(first.landformPatchId(), first.centerBlock().x(), first.centerBlock().z(),
                second.landformPatchId(), second.centerBlock().x(), second.centerBlock().z())).getAsJsonObject();
    }

    private static JsonObject singleAnchorPlan(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
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
                      "roadAccessIntent": "primary_access"
                    }
                  ]
                }
                """.formatted(first.landformPatchId(), first.centerBlock().x(), first.centerBlock().z()))
                .getAsJsonObject();
    }

    private static LandformPatch patch(String id, LandformType type, int minX, int minZ, int maxX, int maxZ) {
        return new LandformPatch(id, "region_0", type,
                Math.max(1, (maxX - minX) * (maxZ - minZ) / 256), minX, minZ, maxX, maxZ,
                70.0, 65.0, 75.0, 1.5, 50.0,
                false, false, 0.9, EnumSet.noneOf(PatchFlag.class));
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
                      "fixedFootprint": {"widthBlocks": 20, "depthBlocks": 12, "heightBlocks": 10},
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
                      "semanticTerms": ["function.village", "style.debug", "placement.inside_zone", "usage.filler", "quality.debug_usable"],
                      "functionTerms": ["function.village"],
                      "styleTerms": ["style.debug"],
                      "placementTerms": ["placement.inside_zone"],
                      "usageTerms": ["usage.filler"],
                      "qualityTerms": ["quality.debug_usable"],
                      "expectedAreaRange": {
                        "minAreaBlocks": 128,
                        "maxAreaBlocks": 4096,
                        "startFootprint": {"widthBlocks": 10, "depthBlocks": 10, "heightBlocks": 8}
                      },
                      "maxDistanceFromCenter": 64,
                      "allowedRotations": ["NONE", "CLOCKWISE_90"],
                      "clearanceBlocks": 0
                    }
                  ],
                  "quality": {
                    "passed": true,
                    "score": 100,
                    "hardBlocks": [],
                    "warnings": [],
                    "needsReview": [],
                    "metrics": {}
                  }
                }
                """;
    }

    private static String trekDebugCatalog() {
        return """
                {
                  "schemaVersion": "city_structure_profile_catalog.v0.1",
                  "catalogMode": "debug",
                  "source": {"basis": "synthetic trek unit-test fixture"},
                  "structures": [
                    {
                      "structureId": "trek:overworld/medium/farm",
                      "sourceProfileRef": "synthetic://unit-test/trek_farm",
                      "profileType": "jigsaw_system",
                      "sampleType": "structure_assembly",
                      "placementKind": "minecraft_place_structure",
                      "placementCommand": "place structure trek:overworld/medium/farm <x> <y> <z>",
                      "footprintMode": "variable_area",
                      "semanticTerms": ["function.farm", "quality.debug_usable"],
                      "functionTerms": ["function.farm"],
                      "styleTerms": ["style.trek"],
                      "placementTerms": ["placement.plains"],
                      "usageTerms": ["usage.test"],
                      "qualityTerms": ["quality.debug_usable"],
                      "expectedAreaRange": {
                        "minAreaBlocks": 128,
                        "maxAreaBlocks": 4096,
                        "startFootprint": {"widthBlocks": 17, "depthBlocks": 23, "heightBlocks": 11}
                      },
                      "maxDistanceFromCenter": 64,
                      "allowedRotations": ["NONE"],
                      "clearanceBlocks": 0
                    }
                  ],
                  "quality": {"passed": true, "score": 100, "hardBlocks": [], "warnings": [], "needsReview": [], "metrics": {}}
                }
                """;
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private record Fixture(Path baseDir, CitySiteContext context, CityLandformReviewPackage review,
                           JsonObject terraSenseSource) {
    }

    private static final class FakePlacementBackend implements CityStructureMaterializationPlanner.PlacementBackend {
        private final String signaturePrefix;
        private final boolean success;
        private int planCalls;
        private int placeCalls;

        private FakePlacementBackend(String signaturePrefix, boolean success) {
            this.signaturePrefix = signaturePrefix;
            this.success = success;
        }

        @Override
        public CityStructureMaterializationPlanner.PlacementResult plan(
                CityStructureMaterializationPlanner.StructureTask task) {
            planCalls++;
            return result(task, false);
        }

        @Override
        public CityStructureMaterializationPlanner.PlacementResult place(
                CityStructureMaterializationPlanner.StructureTask task) {
            placeCalls++;
            return result(task, true);
        }

        private CityStructureMaterializationPlanner.PlacementResult result(
                CityStructureMaterializationPlanner.StructureTask task, boolean applied) {
            if (!success) {
                return CityStructureMaterializationPlanner.PlacementResult.failed("TEST_FAILURE", "synthetic failure");
            }
            JsonArray pieces = new JsonArray();
            JsonObject piece = new JsonObject();
            piece.addProperty("pieceIndex", 0);
            piece.add("box", boundsJson(task.plannedFootprint()));
            piece.addProperty("type", "synthetic");
            pieces.add(piece);
            return CityStructureMaterializationPlanner.PlacementResult.success(applied, "ok",
                    task.plannedFootprint(), signaturePrefix + ":" + task.anchorId(), pieces);
        }
    }
}
