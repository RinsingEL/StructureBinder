package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityReservationMaskPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureArrayCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureClusterGroupCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeFacts;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeProfiler;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallReservationPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallTemplateLibrary;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.algorithm.landform.PatchMerger;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        assertEquals("startFootprint+jigsawMaxExpansionRadius+clearance",
                jigsaw.get("reservedEnvelopePolicy").getAsString());
        assertEquals(8, fixed.get("reservedEnvelopeRadiusBlocks").getAsInt());
        assertEquals(72, jigsaw.get("reservedEnvelopeRadiusBlocks").getAsInt());
        assertFalse(jigsaw.has("safetyEnvelope"));
        assertTrue(bounds(jigsaw.getAsJsonObject("maskEnvelope")).widthBlocks()
                > bounds(jigsaw.getAsJsonObject("collisionEnvelope")).widthBlocks());
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
        JsonObject fact = factsResult.structureEnvelopeFacts().getAsJsonArray("structures")
                .get(0).getAsJsonObject();
        assertTrue(fact.has("generationConfigHash"));
        assertEquals(20, fact.getAsJsonArray("validSamples").size());
        assertEquals(20, fact.getAsJsonArray("bboxGroups").size());
        assertTrue(fact.getAsJsonArray("validSamples").get(0).getAsJsonObject().has("bboxGroupKey"));
        Path factsPath = fixture.baseDir().resolve("structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));

        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()),
                        CityStructureEnvelopeFacts.load(factsPath))
                .structureAnchorMap();

        JsonObject jigsaw = anchorMap.getAsJsonArray("anchors").get(1).getAsJsonObject();
        assertEquals("structureEnvelopeFacts:fixedDepthP95+clearance/collision+maskMargin",
                jigsaw.get("reservedEnvelopePolicy").getAsString());
        assertEquals("fixed_depth_statistics", jigsaw.get("envelopeMode").getAsString());
        assertTrue(jigsaw.has("maskEnvelope"));
        assertFalse(jigsaw.has("safetyEnvelope"));
        assertTrue(jigsaw.has("structureEnvelopeFact"));
        assertTrue(jigsaw.getAsJsonObject("structureEnvelopeFact").has("maxObservedEnvelope"));
    }

    @Test
    void envelopeProfilerUsesCacheAndInvalidatesStaleKeys() throws Exception {
        Fixture fixture = fixture();
        Path cacheDir = fixture.baseDir().resolve("profile-cache");
        CityStructureEnvelopeProfiler.CacheOptions cacheOptions =
                CityStructureEnvelopeProfiler.CacheOptions.enabled(cacheDir, "city_context_hash", false);
        int[] firstCalls = {0};
        CityStructureEnvelopeProfiler.StructureEnvelopeSampler firstSampler =
                new CityStructureEnvelopeProfiler.StructureEnvelopeSampler() {
                    @Override
                    public CityStructureEnvelopeProfiler.EnvelopeSample sample(
                            com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog.StructureProfile profile,
                            int sampleIndex) {
                        firstCalls[0]++;
                        return CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-4, -5, 15, 6), 1,
                                "config_hash_a", "pack_hash_a");
                    }

                    @Override
                    public CityStructureEnvelopeProfiler.CacheIdentity cacheIdentity(
                            com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog.StructureProfile profile) {
                        return new CityStructureEnvelopeProfiler.CacheIdentity(
                                "config_hash_a", "pack_hash_a", "generation_context_a");
                    }
                };

        CityStructureEnvelopeProfiler.Result miss = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:desert_pyramid"), 3,
                        firstSampler, cacheOptions);
        JsonObject missFact = miss.structureEnvelopeFacts().getAsJsonArray("structures").get(0).getAsJsonObject();
        assertEquals(3, firstCalls[0]);
        assertEquals("miss_recomputed", missFact.get("cacheStatus").getAsString());
        assertTrue(missFact.has("cacheKey"));
        assertTrue(missFact.has("cacheIdentity"));
        assertEquals(1, miss.structureEnvelopeFacts().getAsJsonObject("profileCache")
                .getAsJsonObject("metrics").get("cacheMissRecomputedCount").getAsInt());

        CityStructureEnvelopeProfiler.Result hit = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:desert_pyramid"), 3,
                        firstSampler, cacheOptions);
        JsonObject hitFact = hit.structureEnvelopeFacts().getAsJsonArray("structures").get(0).getAsJsonObject();
        assertEquals(3, firstCalls[0]);
        assertEquals("hit", hitFact.get("cacheStatus").getAsString());
        assertEquals(missFact.get("cacheKey").getAsString(), hitFact.get("cacheKey").getAsString());

        int[] staleCalls = {0};
        CityStructureEnvelopeProfiler.StructureEnvelopeSampler staleSampler =
                new CityStructureEnvelopeProfiler.StructureEnvelopeSampler() {
                    @Override
                    public CityStructureEnvelopeProfiler.EnvelopeSample sample(
                            com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog.StructureProfile profile,
                            int sampleIndex) {
                        staleCalls[0]++;
                        return CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-6, -5, 17, 6), 1,
                                "config_hash_a", "pack_hash_b");
                    }

                    @Override
                    public CityStructureEnvelopeProfiler.CacheIdentity cacheIdentity(
                            com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog.StructureProfile profile) {
                        return new CityStructureEnvelopeProfiler.CacheIdentity(
                                "config_hash_a", "pack_hash_b", "generation_context_a");
                    }
                };
        CityStructureEnvelopeProfiler.Result stale = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:desert_pyramid"), 3,
                        staleSampler, cacheOptions);
        JsonObject staleFact = stale.structureEnvelopeFacts().getAsJsonArray("structures").get(0).getAsJsonObject();
        assertEquals(3, staleCalls[0]);
        assertEquals("stale_recomputed", staleFact.get("cacheStatus").getAsString());
        assertEquals("CACHE_KEY_STALE", staleFact.get("cacheReason").getAsString());
        assertFalse(missFact.get("cacheKey").getAsString().equals(staleFact.get("cacheKey").getAsString()));
    }

    @Test
    void d4UsesDominantFixedBBoxGroupWithSmallClearance() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeProfiler.Result factsResult = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:desert_pyramid"), 6,
                        (profile, sampleIndex) -> {
                            BlockBounds dominant = new BlockBounds(-4, -5, 15, 6);
                            BlockBounds rotated = new BlockBounds(-7, -2, 4, 17);
                            return CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                    sampleIndex < 4 ? dominant : rotated, 1,
                                    "fixed_config_hash", "pack_hash");
                        });
        Path factsPath = fixture.baseDir().resolve("fixed_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));

        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()),
                        CityStructureEnvelopeFacts.load(factsPath))
                .structureAnchorMap();

        JsonObject fixed = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        JsonObject group = fixed.getAsJsonObject("selectedEnvelopeGroup");
        JsonObject collision = fixed.getAsJsonObject("collisionEnvelope");
        JsonObject anchorBlock = fixed.getAsJsonObject("anchorBlock");
        int originX = Math.floorDiv(anchorBlock.get("x").getAsInt(), 16) * 16;
        int originZ = Math.floorDiv(anchorBlock.get("z").getAsInt(), 16) * 16;

        assertEquals("fixed_bbox_group", fixed.get("envelopeMode").getAsString());
        assertEquals("structureEnvelopeFacts:fixedBBoxGroup+smallClearance",
                fixed.get("reservedEnvelopePolicy").getAsString());
        assertEquals(4, fixed.get("clearanceBlocks").getAsInt());
        assertEquals(4, fixed.get("smallClearanceBlocks").getAsInt());
        assertEquals(4, group.get("sampleCount").getAsInt());
        assertEquals(originX - 8, collision.get("minX").getAsInt());
        assertEquals(originZ - 9, collision.get("minZ").getAsInt());
        assertEquals(originX + 19, collision.get("maxX").getAsInt());
        assertEquals(originZ + 10, collision.get("maxZ").getAsInt());
    }

    @Test
    void d4RejectsUnknownFixedBBoxGroupKey() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeProfiler.Result factsResult = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:desert_pyramid"), 2,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-4, -5, 15, 6), 1,
                                "fixed_config_hash", "pack_hash"));
        Path factsPath = fixture.baseDir().resolve("fixed_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));
        JsonObject plan = singleAnchorPlan(fixture.review());
        plan.getAsJsonArray("anchors").get(0).getAsJsonObject()
                .addProperty("envelopeGroupKey", "missing_group");

        JsonObject result = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan,
                        CityStructureEnvelopeFacts.load(factsPath))
                .asJson();

        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.getAsJsonObject("qualityReport").getAsJsonArray("hardBlocks")
                .toString()
                .contains("requested envelopeGroupKey is not in structure envelope facts"));
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
    void d4CandidatePlannerBuildsSafeCandidatesAndSelectionAnchorPlan() throws Exception {
        Fixture fixture = fixture();
        CityStructureAnchorCandidatePlanner planner = new CityStructureAnchorCandidatePlanner();

        CityStructureAnchorCandidatePlanner.Result result = planner.plan(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                designSlotPlan(fixture.review()), CityStructureEnvelopeFacts.empty());

        JsonObject candidateSet = result.anchorCandidateSet();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals("city_d4_anchor_candidate_set.v0.1", candidateSet.get("schemaVersion").getAsString());
        assertEquals(2, candidateSet.getAsJsonArray("slotCandidates").size());
        JsonObject firstSlot = candidateSet.getAsJsonArray("slotCandidates").get(0).getAsJsonObject();
        assertFalse(firstSlot.getAsJsonArray("candidates").isEmpty());
        JsonObject firstCandidate = firstSlot.getAsJsonArray("candidates").get(0).getAsJsonObject();
        Set<String> candidateIds = new HashSet<>();
        for (int i = 0; i < firstSlot.getAsJsonArray("candidates").size(); i++) {
            String candidateId = firstSlot.getAsJsonArray("candidates").get(i).getAsJsonObject()
                    .get("candidateId").getAsString();
            assertTrue(candidateIds.add(candidateId), "duplicate candidateId: " + candidateId);
        }
        assertTrue(firstCandidate.has("estimatedCollisionEnvelope"));
        assertTrue(firstCandidate.has("scoreBreakdown"));
        assertEquals("fallback_fixed_footprint", firstCandidate.get("envelopeMode").getAsString());

        JsonObject selectionPlan = JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_anchor_selection_plan.v0.1",
                  "cityId": "city_test",
                  "selectedCandidates": [
                    {
                      "slotId": "admin_core",
                      "candidateId": "%s",
                      "anchorId": "admin_core_01",
                      "selectionReason": "test"
                    }
                  ]
                }
                """.formatted(firstCandidate.get("candidateId").getAsString())).getAsJsonObject();
        JsonObject anchorPlan = planner.select(candidateSet, selectionPlan);

        assertEquals(CityStructureAnchorPlanner.PLAN_SCHEMA, anchorPlan.get("schemaVersion").getAsString());
        JsonObject anchor = anchorPlan.getAsJsonArray("anchors").get(0).getAsJsonObject();
        assertEquals("admin_core_01", anchor.get("anchorId").getAsString());
        assertEquals(firstCandidate.get("structureId").getAsString(), anchor.get("structureId").getAsString());
        assertTrue(anchorPlan.has("candidateSelectionTrace"));
    }

    @Test
    void d4CandidatePlannerAcceptsSingleStructureIdSlot() throws Exception {
        Fixture fixture = fixture();
        JsonObject plan = designSlotPlan(fixture.review());
        JsonObject slot = plan.getAsJsonArray("slots").get(0).getAsJsonObject();
        slot.addProperty("structureId", slot.getAsJsonArray("structureIds").get(0).getAsString());
        slot.remove("structureIds");

        CityStructureAnchorCandidatePlanner.Result result = new CityStructureAnchorCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan,
                        CityStructureEnvelopeFacts.empty());

        JsonArray candidates = result.anchorCandidateSet().getAsJsonArray("slotCandidates")
                .get(0).getAsJsonObject().getAsJsonArray("candidates");
        assertFalse(candidates.isEmpty());
        assertEquals("minecraft:desert_pyramid",
                candidates.get(0).getAsJsonObject().get("structureId").getAsString());
    }

    @Test
    void d4CandidatePlannerRejectsLegacyPayload() throws Exception {
        Fixture fixture = fixture();
        JsonObject plan = designSlotPlan(fixture.review());
        plan.addProperty("functionType", "civic_core");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CityStructureAnchorCandidatePlanner()
                        .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                                plan, CityStructureEnvelopeFacts.empty()));

        assertTrue(ex.getMessage().contains("LEGACY_CITY_FUNCTION_ZONE_FLOW_REMOVED"));
    }

    @Test
    void d4CandidateSessionPlansOneSlotThenFreezesSelectionForNextSlot() throws Exception {
        Fixture fixture = fixture();
        CityStructureAnchorCandidatePlanner planner = new CityStructureAnchorCandidatePlanner();
        JsonObject design = designSlotPlan(fixture.review());
        CityStructureAnchorCandidatePlanner.SessionResult sessionResult = planner.createSession(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), design, "session_test");

        assertEquals("admin_core", sessionResult.session().get("currentSlotId").getAsString());
        assertEquals(0, sessionResult.session().getAsJsonArray("selectedAnchors").size());

        CityStructureAnchorCandidatePlanner.NextCandidateResult first = planner.planNext(
                fixture.baseDir(), fixture.review(), sessionResult.session(), CityStructureEnvelopeFacts.empty());
        JsonObject firstSlot = first.slotCandidateSet().getAsJsonArray("slotCandidates").get(0).getAsJsonObject();
        assertEquals("admin_core", firstSlot.get("slotId").getAsString());
        JsonObject firstCandidate = firstSlot.getAsJsonArray("candidates").get(0).getAsJsonObject();

        CityStructureAnchorCandidatePlanner.SelectionResult selected = planner.selectSession(
                first.session(), first.slotCandidateSet(), "admin_core",
                firstCandidate.get("candidateId").getAsString(), "admin_core_01", "test", false);

        assertEquals(1, selected.session().getAsJsonArray("selectedAnchors").size());
        assertEquals(1, selected.session().getAsJsonArray("occupiedEnvelopes").size());
        JsonObject occupied = selected.session().getAsJsonArray("occupiedEnvelopes")
                .get(0).getAsJsonObject();
        assertEquals("estimated_collision", occupied.get("envelopeType").getAsString());
        assertTrue(occupied.has("estimatedCollisionEnvelope"));
        assertFalse(occupied.has("estimatedSafetyEnvelope"));
        assertTrue(firstCandidate.has("diagnosticMaxObservedEnvelope"));
        assertEquals("residential_01", selected.session().get("currentSlotId").getAsString());
        assertEquals("deferred_to_d6",
                selected.quickPreflightReport().get("status").getAsString());

        CityStructureAnchorCandidatePlanner.NextCandidateResult second = planner.planNext(
                fixture.baseDir(), fixture.review(), selected.session(), CityStructureEnvelopeFacts.empty());
        JsonObject secondSlot = second.slotCandidateSet().getAsJsonArray("slotCandidates").get(0).getAsJsonObject();
        assertEquals("residential_01", secondSlot.get("slotId").getAsString());
        assertEquals(1, second.slotCandidateSet().getAsJsonArray("occupiedEnvelopes").size());
        assertTrue(second.slotCandidateSet().has("selectedAnchors"));
        assertTrue(second.session().getAsJsonObject("timing").has("stepTimings"));
    }

    @Test
    void d4CandidateSessionRejectsOutOfOrderSelectionAndIncompleteFinalize() throws Exception {
        Fixture fixture = fixture();
        CityStructureAnchorCandidatePlanner planner = new CityStructureAnchorCandidatePlanner();
        CityStructureAnchorCandidatePlanner.SessionResult sessionResult = planner.createSession(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                designSlotPlan(fixture.review()), "session_test");
        CityStructureAnchorCandidatePlanner.NextCandidateResult first = planner.planNext(
                fixture.baseDir(), fixture.review(), sessionResult.session(), CityStructureEnvelopeFacts.empty());
        JsonObject firstCandidate = first.slotCandidateSet().getAsJsonArray("slotCandidates")
                .get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject();

        IllegalArgumentException order = assertThrows(IllegalArgumentException.class,
                () -> planner.selectSession(first.session(), first.slotCandidateSet(), "residential_01",
                        firstCandidate.get("candidateId").getAsString(), "bad", "bad", false));
        assertTrue(order.getMessage().contains("D4_SLOT_ORDER_VIOLATION"));

        IllegalArgumentException finalize = assertThrows(IllegalArgumentException.class,
                () -> planner.finalizeSession(first.session()));
        assertTrue(finalize.getMessage().contains("D4_SESSION_NOT_FINALIZABLE"));
    }

    @Test
    void d4CandidateSessionFinalizesStandardAnchorPlan() throws Exception {
        Fixture fixture = fixture();
        CityStructureAnchorCandidatePlanner planner = new CityStructureAnchorCandidatePlanner();
        JsonObject session = planner.createSession(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                designSlotPlan(fixture.review()), "session_test").session();

        for (String slotId : List.of("admin_core", "residential_01")) {
            CityStructureAnchorCandidatePlanner.NextCandidateResult next = planner.planNext(
                    fixture.baseDir(), fixture.review(), session, CityStructureEnvelopeFacts.empty());
            JsonObject candidate = next.slotCandidateSet().getAsJsonArray("slotCandidates")
                    .get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject();
            session = planner.selectSession(next.session(), next.slotCandidateSet(), slotId,
                    candidate.get("candidateId").getAsString(), slotId + "_anchor", "test", false).session();
        }

        CityStructureAnchorCandidatePlanner.FinalizeResult finalized = planner.finalizeSession(session);
        JsonObject plan = finalized.structureAnchorPlan();
        assertEquals(CityStructureAnchorPlanner.PLAN_SCHEMA, plan.get("schemaVersion").getAsString());
        assertEquals(2, plan.getAsJsonArray("anchors").size());
        assertTrue(plan.has("candidateSelectionTrace"));
        assertEquals("city_d4_design_time_report.v0.2",
                finalized.designTimeReport().get("schemaVersion").getAsString());
    }

    @Test
    void d4ArrayCandidatePlannerBuildsCompleteNonOverlappingGroups() throws Exception {
        Fixture fixture = arrayFixture();
        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        arrayCandidatePlan(fixture.review(), 10), CityStructureEnvelopeFacts.empty(),
                        new JsonObject(), new JsonArray());

        JsonObject candidateSet = result.arrayCandidateSet();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals("city_d4_array_candidate_set.v0.1", candidateSet.get("schemaVersion").getAsString());
        JsonArray groups = candidateSet.getAsJsonArray("arrayCandidates");
        assertFalse(groups.isEmpty());
        Set<String> patterns = new HashSet<>();
        for (JsonElement elem : groups) {
            patterns.add(elem.getAsJsonObject().get("arrayPattern").getAsString());
        }
        assertTrue(patterns.contains("loose_cluster"));
        assertTrue(patterns.contains("patch_axis_band"));
        assertTrue(patterns.contains("scattered"));

        JsonObject firstGroup = groups.get(0).getAsJsonObject();
        assertEquals(10, firstGroup.getAsJsonArray("items").size());
        assertEquals(10, firstGroup.getAsJsonObject("expandedStructureAnchorPlan")
                .getAsJsonArray("anchors").size());
        assertGroupItemsDoNotOverlap(firstGroup);
    }

    @Test
    void d4ArrayCandidatePlannerSupportsCompoundShapePatterns() throws Exception {
        Fixture fixture = arrayFixture();
        assertArrayCandidateShape(fixture, "grid", "grid", 2, 2, 4);
        assertArrayCandidateShape(fixture, "courtyard", "courtyard", 3, 3, 8);
        assertArrayCandidateShape(fixture, "l_shape", "l_shape", 3, 3, 5);
        assertArrayCandidateShape(fixture, "u_shape", "u_shape", 3, 3, 7);
        assertArrayCandidateShape(fixture, "organic_compact", "organic_compact", 3, 4, 6);
        assertArrayCandidateShape(fixture, "compound_cluster", "grid", 2, 2, 4);
    }

    @Test
    void d4ArrayCandidatePlannerSpacingIgnoresMaskEnvelopeOverlap() throws Exception {
        Fixture fixture = arrayFixture();
        JsonObject plan = arrayCandidatePlan(fixture.review(), 2);
        plan.add("patterns", JsonParser.parseString("""
                ["compound_cluster"]
                """).getAsJsonArray());
        plan.add("structureIds", JsonParser.parseString("""
                ["minecraft:desert_pyramid"]
                """).getAsJsonArray());
        plan.add("compoundCluster", JsonParser.parseString("""
                {"shape": "grid", "rows": 1, "columns": 2, "spacingBlocks": 40}
                """).getAsJsonObject());

        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        plan, CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonArray());

        assertTrue(result.asJson().get("ok").getAsBoolean());
        JsonObject group = result.arrayCandidateSet().getAsJsonArray("arrayCandidates").get(0).getAsJsonObject();
        assertEquals(2, group.getAsJsonArray("items").size());
        assertGroupItemsDoNotOverlap(group);
        assertAnyArrayItemMaskOverlap(group);
    }

    @Test
    void d4StructureClusterGroupPlannerBuildsFiveCompleteNonOverlappingGroups() throws Exception {
        Fixture fixture = arrayFixture();
        CityStructureClusterGroupCandidatePlanner.Result result =
                new CityStructureClusterGroupCandidatePlanner()
                        .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                                designSlotPlan(fixture.review()), CityStructureEnvelopeFacts.empty(),
                                CityStructureClusterGroupCandidatePlanner.Options.defaults());

        JsonObject candidateSet = result.structureClusterGroupCandidateSet();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals(CityStructureClusterGroupCandidatePlanner.CANDIDATE_SET_SCHEMA,
                candidateSet.get("schemaVersion").getAsString());
        assertEquals("structure_cluster_group_candidates",
                candidateSet.get("planningMode").getAsString());
        JsonArray groups = candidateSet.getAsJsonArray("groupCandidates");
        assertEquals(5, groups.size());
        for (JsonElement groupElem : groups) {
            JsonObject group = groupElem.getAsJsonObject();
            assertEquals(2, group.getAsJsonArray("items").size());
            assertEquals(2, group.getAsJsonObject("expandedStructureAnchorPlan")
                    .getAsJsonArray("anchors").size());
            assertClusterGroupItemsDoNotOverlap(group);
        }
    }

    @Test
    void d4ArrayCandidatePlannerAvoidsOccupiedEnvelopes() throws Exception {
        Fixture fixture = arrayFixture();
        JsonArray occupied = JsonParser.parseString("""
                [
                  {"blockBounds": {"minX": -72, "minZ": -72, "maxX": 72, "maxZ": 72}}
                ]
                """).getAsJsonArray();
        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        arrayCandidatePlan(fixture.review(), 10), CityStructureEnvelopeFacts.empty(),
                        new JsonObject(), occupied);

        assertTrue(result.asJson().get("ok").getAsBoolean());
        BlockBounds occupiedBounds = bounds(occupied.get(0).getAsJsonObject().getAsJsonObject("blockBounds"));
        for (JsonElement groupElem : result.arrayCandidateSet().getAsJsonArray("arrayCandidates")) {
            JsonObject group = groupElem.getAsJsonObject();
            for (JsonElement itemElem : group.getAsJsonArray("items")) {
                BlockBounds collision = bounds(itemElem.getAsJsonObject()
                        .getAsJsonObject("estimatedCollisionEnvelope"));
                assertFalse(collision.overlaps(occupiedBounds));
            }
        }
    }

    @Test
    void d4ArrayCandidatePlannerHardFailsWhenArrayCountCannotBeSatisfied() throws Exception {
        Fixture fixture = fixture();
        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        arrayCandidatePlan(fixture.review(), 10), CityStructureEnvelopeFacts.empty(),
                        new JsonObject(), new JsonArray());

        assertFalse(result.asJson().get("ok").getAsBoolean());
        assertTrue(result.qualityReport().getAsJsonArray("hardBlocks").toString()
                .contains("D4_ARRAY_COUNT_UNSATISFIED"));
        assertTrue(result.arrayCandidateSet().getAsJsonArray("arrayCandidates").isEmpty());
    }

    @Test
    void d4ArrayCandidatePlannerRejectsReviewRequiredVillageForCompactSubmission() throws Exception {
        Fixture fixture = arrayFixture();
        CityStructureEnvelopeProfiler.Result factsResult = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:village_plains"), 8,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-32 - sampleIndex, -28, 36 + sampleIndex, 34),
                                12 + sampleIndex,
                                "village_config_hash",
                                "pack_hash"));
        Path factsPath = fixture.baseDir().resolve("village_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));
        JsonObject plan = arrayCandidatePlan(fixture.review(), 1);
        plan.add("structureIds", JsonParser.parseString("""
                ["minecraft:village_plains"]
                """).getAsJsonArray());

        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        plan, CityStructureEnvelopeFacts.load(factsPath),
                        new JsonObject(), new JsonArray());

        assertFalse(result.asJson().get("ok").getAsBoolean());
        assertTrue(result.arrayCandidateSet().getAsJsonArray("arrayCandidates").isEmpty());
        assertTrue(result.arrayCandidateSet().getAsJsonArray("generationReports").toString()
                .contains("D4_COMPACT_ARRAY_STRUCTURE_REQUIRES_REVIEW"));
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
    void d5ReservationMaskCoversStructureEnvelopeWithoutFixedRoadAccess() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();

        CityReservationMaskPlanner.Result result = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap);

        JsonObject mask = result.reservationMaskPlan();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals(2, mask.getAsJsonArray("noVanillaStructureMask").size());
        assertEquals(2, mask.getAsJsonArray("noVegetationMask").size());
        assertTrue(mask.getAsJsonObject("hookRequirements").get("required").getAsBoolean());
        assertTrue(mask.get("requiresLockedMaterializationPlan").getAsBoolean());
        assertEquals("d7_after_worldgen_ledger", mask.get("roadPlanningStage").getAsString());
        String operations = result.buildOperationPlan().getAsJsonArray("operations").toString();
        assertFalse(operations.contains("clearVegetation"));
        assertFalse(operations.contains("surfaceFill"));
    }

    @Test
    void d5AndD6DeriveMaskFromCollisionAndAnchorMaskMargin() throws Exception {
        assertD5AndD6MaskMargin(5);
        assertD5AndD6MaskMargin(10);
    }

    private static void assertD5AndD6MaskMargin(int maskMarginBlocks) throws Exception {
        Fixture fixture = fixture();
        JsonObject plan = singleAnchorPlan(fixture.review());
        plan.getAsJsonArray("anchors").get(0).getAsJsonObject()
                .addProperty("maskMarginBlocks", maskMarginBlocks);
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan)
                .structureAnchorMap();
        JsonObject anchor = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        anchor.add("maskEnvelope", boundsJson(new BlockBounds(-999, -999, 999, 999)));
        anchor.add("safetyEnvelope", boundsJson(new BlockBounds(-999, -999, 999, 999)));
        anchor.add("estimatedSafetyEnvelope", boundsJson(new BlockBounds(-888, -888, 888, 888)));
        anchor.add("groupSafetyEnvelope", boundsJson(new BlockBounds(-777, -777, 777, 777)));

        JsonObject mask = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap)
                .reservationMaskPlan();

        BlockBounds collision = bounds(anchor.getAsJsonObject("collisionEnvelope"));
        JsonObject noVegetation = mask.getAsJsonArray("noVegetationMask").get(0).getAsJsonObject();
        JsonObject noVanilla = mask.getAsJsonArray("noVanillaStructureMask").get(0).getAsJsonObject();
        assertEquals(expand(collision, maskMarginBlocks), bounds(noVegetation.getAsJsonObject("blockBounds")));
        assertEquals(expand(collision, maskMarginBlocks), bounds(noVanilla.getAsJsonObject("blockBounds")));
        assertFalse(mask.toString().contains("safetyEnvelope"));

        CityStructureMaterializationPlanner.Result d6 = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        new FakePlacementBackend("sig", true), null);
        JsonObject planned = d6.structureMaterializationPlan()
                .getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();
        assertEquals(maskMarginBlocks, planned.get("maskMarginBlocks").getAsInt());
        assertTrue(planned.has("actualFootprint"));
        assertTrue(planned.has("lockedActualFootprint"));
        assertTrue(planned.has("pieceBoxes"));
        assertTrue(planned.has("lockedCollisionEnvelope"));
        assertEquals(expand(bounds(planned.getAsJsonObject("lockedCollisionEnvelope")), maskMarginBlocks),
                bounds(planned.getAsJsonObject("maskEnvelope")));
    }

    @Test
    void wallReservationAddsNonRectangularCorridorAndRoadMaskCutsGate() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject reservation = new CityWallReservationPlanner().plan(
                fixture.review(), anchorMap, "v2", 24, 15, 4);

        assertEquals("city_wall_reservation_plan.v0.2", reservation.get("schemaVersion").getAsString());
        assertEquals("d3_patch_member_cell_outer_boundary", reservation.get("boundarySource").getAsString());
        assertFalse(reservation.getAsJsonArray("wallCenterline").isEmpty());
        assertFalse(reservation.getAsJsonArray("wallCorridorMask").isEmpty());

        JsonObject mask = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap, reservation)
                .reservationMaskPlan();
        assertTrue(mask.getAsJsonArray("noVegetationMask").toString().contains("wall_reservation_corridor"));
        assertTrue(mask.getAsJsonArray("noVanillaStructureMask").toString().contains("wall_reservation_corridor"));

        JsonObject firstLine = reservation.getAsJsonArray("wallCenterline").get(0).getAsJsonObject();
        JsonObject lineBounds = firstLine.getAsJsonObject("blockBounds");
        int roadX = (lineBounds.get("minX").getAsInt() + lineBounds.get("maxX").getAsInt()) / 2;
        int roadZ = (lineBounds.get("minZ").getAsInt() + lineBounds.get("maxZ").getAsInt()) / 2;
        JsonObject actualRoadMask = JsonParser.parseString("""
                {
                  "schemaVersion": "city_actual_road_mask.v0.2",
                  "cityId": "city_test",
                  "status": "observed",
                  "roadMask": [
                    {
                      "maskId": "road_0",
                      "maskType": "actual_road",
                      "blockBounds": {"minX": %d, "minZ": %d, "maxX": %d, "maxZ": %d}
                    }
                  ]
                }
                """.formatted(roadX, roadZ, roadX, roadZ)).getAsJsonObject();
        JsonObject ledger = JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}}
                  ]
                }
                """).getAsJsonObject();

        JsonObject wallPlan = new CityWallPlanner().planV2(ledger, reservation, actualRoadMask, 9, 2, 8, 7);
        assertEquals("city_wall_plan.v0.2", wallPlan.get("schemaVersion").getAsString());
        assertFalse(wallPlan.getAsJsonArray("generatedGates").isEmpty());
        assertTrue(wallPlan.getAsJsonArray("wallSegments").toString().contains("WALL_GATE_FROM_ROAD"));
    }

    @Test
    void wallReservationV3BuildsStructureSeededDomainHullAndMaskContribution() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();

        JsonObject reservation = new CityWallReservationPlanner().plan(
                fixture.review(), anchorMap, "v3", 24, 15, 4,
                new CityWallReservationPlanner.V3Options(24, 2, 64, 0.6));

        assertEquals("city_wall_reservation_plan.v0.3", reservation.get("schemaVersion").getAsString());
        assertEquals("structure_seeded_patch_region_hull", reservation.get("boundarySource").getAsString());
        assertFalse(reservation.getAsJsonArray("seedPatches").isEmpty());
        assertFalse(reservation.getAsJsonArray("cityDomainMask").isEmpty());
        assertFalse(reservation.getAsJsonArray("wallCenterline").isEmpty());
        assertTrue(reservation.getAsJsonObject("domainCleanupReport").has("filledCellCount"));

        JsonObject mask = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap, reservation)
                .reservationMaskPlan();
        assertTrue(mask.getAsJsonArray("noVegetationMask").toString().contains("wall_reservation_corridor"));
        assertTrue(mask.getAsJsonArray("noVanillaStructureMask").toString().contains("wall_reservation_corridor"));
    }

    @Test
    void wallReservationV4KeepsD5MaskButDefersFinalBoundaryToD7Graph() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();

        JsonObject reservation = new CityWallReservationPlanner().plan(
                fixture.review(), anchorMap, "v4", 24, 15, 4,
                new CityWallReservationPlanner.V3Options(24, 2, 64, 0.6));

        assertEquals("city_wall_reservation_plan.v0.3", reservation.get("schemaVersion").getAsString());
        assertEquals("v4", reservation.get("wallVersion").getAsString());
        assertEquals("actual_footprint_land_ring_deferred_to_d7",
                reservation.get("boundarySource").getAsString());
        assertTrue(reservation.get("finalBoundaryDeferredToD7").getAsBoolean());
        assertFalse(reservation.getAsJsonArray("wallCorridorMask").isEmpty());
    }

    @Test
    void wallPlannerV5KeepsD5WallLineAndDeclaresNoRelineDowngradePolicies() {
        JsonObject reservation = syntheticV5Reservation();
        JsonObject wallPlan = new CityWallPlanner().planV5(syntheticWallLedger(), reservation,
                roadMaskFromBlocks("city_test", new int[][]{}), CityWallPlanner.V5Options.defaults());

        assertEquals("city_wall_plan.v0.5", wallPlan.get("schemaVersion").getAsString());
        assertEquals("d5_final_wall_line", wallPlan.get("wallBoundaryMode").getAsString());
        assertEquals(reservation.getAsJsonArray("wallLine").toString(),
                wallPlan.getAsJsonArray("wallLine").toString());
        assertEquals("disabled_v5_no_reline_after_d5", wallPlan.get("wallContourMode").getAsString());
        assertEquals("keep_gate_opening_or_downgrade_without_reline",
                wallPlan.get("gateFailurePolicy").getAsString());
        assertEquals("downgrade_to_wall_or_skip_without_reline",
                wallPlan.get("beaconFailurePolicy").getAsString());
        assertEquals(8, wallPlan.get("wallUnitLengthBlocks").getAsInt());
        assertEquals(9, wallPlan.get("nominalWallHeightBlocks").getAsInt());
        assertEquals(32, wallPlan.get("waterRunMinBlocks").getAsInt());
        assertEquals(7, wallPlan.get("heightSegmentMaxDeltaBlocks").getAsInt());
        assertEquals(16, wallPlan.get("heightSteppedTransitionMaxDeltaBlocks").getAsInt());
        assertEquals(17, wallPlan.get("naturalBoundaryMinDeltaBlocks").getAsInt());
        JsonObject terrain = wallPlan.getAsJsonObject("terrainFitPolicy");
        assertEquals("segmented_surface_datum", terrain.get("heightStrategy").getAsString());
        assertEquals("natural_cliff_boundary_no_wall", terrain.get("cliffPolicy").getAsString());
        assertEquals("surfaceY/topBlock/fluid/biome/temperature/flags",
                wallPlan.getAsJsonObject("surfaceCachePolicy").get("requiredFields").getAsString());
        assertTrue(wallPlan.getAsJsonArray("wallUnits").toString()
                .contains("surface_cache_1_block_median_at_execute"));
        assertTrue(wallPlan.getAsJsonArray("wallUnits").toString().contains("D5_V5_GATE_SLOT_OPENING"));
        assertTrue(wallPlan.getAsJsonArray("wallNodes").toString().contains("beacon_5x5"));
        assertEquals("X", wallNodeAxis(wallPlan, "node_0"));
        assertEquals("Z", wallNodeAxis(wallPlan, "node_2"));
        assertTrue(wallPlan.getAsJsonObject("wallGraphValidation").get("noRelineAfterD5").getAsBoolean());
    }

    @Test
    void wallPlannerV5HardStopsWhenD7ActualFootprintExceedsD5Coverage() {
        JsonObject reservation = syntheticV5Reservation();
        JsonObject ledger = JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "outside", "actualFootprint": {"minX": 120, "minZ": 0, "maxX": 140, "maxZ": 20}}
                  ]
                }
                """).getAsJsonObject();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CityWallPlanner().planV5(ledger, reservation,
                        roadMaskFromBlocks("city_test", new int[][]{}), CityWallPlanner.V5Options.defaults()));

        assertTrue(ex.getMessage().contains("D5_V5_LOCKED_FOOTPRINT_OUTSIDE_RESERVATION"));
    }

    @Test
    void wallReservationV4CarriesPatchMemberCellsIntoSeedPatches() {
        CityLandformReviewPackage review = preciseMemberCellReview();
        JsonObject anchorMap = JsonParser.parseString("""
                {
                  "schemaVersion": "city_structure_anchor_map.v0.2",
                  "cityId": "city_test",
                  "anchors": [
                    {
                      "anchorId": "a",
                      "sourcePatchIds": ["water_cells"],
                      "anchorBlock": {"x": 0, "z": 0}
                    }
                  ]
                }
                """).getAsJsonObject();

        JsonObject reservation = new CityWallReservationPlanner().plan(
                review, anchorMap, "v4", 24, 15, 4,
                new CityWallReservationPlanner.V3Options(24, 1, 64, 0.6));

        assertTrue(reservation.getAsJsonArray("seedPatches").toString().contains("\"memberCells\""),
                reservation.getAsJsonArray("seedPatches").toString());
        assertTrue(reservation.getAsJsonArray("seedPatches").toString().contains("\"cellStepBlocks\":16"),
                reservation.getAsJsonArray("seedPatches").toString());
    }

    @Test
    void wallPlannerV3ClustersExternalRoadGatesAndIgnoresInsideRoads() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject reservation = new CityWallReservationPlanner().plan(
                fixture.review(), anchorMap, "v3", 24, 15, 4,
                new CityWallReservationPlanner.V3Options(24, 2, 64, 0.6));
        JsonObject line = reservation.getAsJsonArray("wallCenterline").get(0).getAsJsonObject();
        BlockBounds lineBounds = bounds(line.getAsJsonObject("blockBounds"));
        BlockBounds domain = bounds(reservation.getAsJsonArray("cityDomainMask").get(0)
                .getAsJsonObject().getAsJsonObject("blockBounds"));
        int roadX = lineBounds.center().x();
        int roadZ = lineBounds.center().z();
        BlockBounds externalRoad = lineBounds.widthBlocks() >= lineBounds.heightBlocks()
                ? new BlockBounds(roadX - 1, lineBounds.minZ() - 2, roadX + 1, lineBounds.maxZ() + 2)
                : new BlockBounds(lineBounds.minX() - 2, roadZ - 1, lineBounds.maxX() + 2, roadZ + 1);
        int insideX = domain.center().x();
        int insideZ = domain.center().z();
        JsonObject actualRoadMask = JsonParser.parseString("""
                {
                  "schemaVersion": "city_actual_road_mask.v0.2",
                  "cityId": "city_test",
                  "status": "observed",
                  "roadMask": [
                    {"maskId": "road_external_a", "maskType": "actual_road", "blockBounds": {"minX": %d, "minZ": %d, "maxX": %d, "maxZ": %d}},
                    {"maskId": "road_external_b", "maskType": "actual_road", "blockBounds": {"minX": %d, "minZ": %d, "maxX": %d, "maxZ": %d}},
                    {"maskId": "road_inside_a", "maskType": "actual_road", "blockBounds": {"minX": %d, "minZ": %d, "maxX": %d, "maxZ": %d}},
                    {"maskId": "road_inside_b", "maskType": "actual_road", "blockBounds": {"minX": %d, "minZ": %d, "maxX": %d, "maxZ": %d}}
                  ]
                }
                """.formatted(
                externalRoad.minX(), externalRoad.minZ(), externalRoad.maxX(), externalRoad.maxZ(),
                externalRoad.minX() + 1, externalRoad.minZ(), externalRoad.maxX() + 1, externalRoad.maxZ(),
                insideX, insideZ, insideX, insideZ,
                insideX + 16, insideZ, insideX + 16, insideZ)).getAsJsonObject();
        JsonObject ledger = JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}}
                  ]
                }
                """).getAsJsonObject();

        JsonObject wallPlan = new CityWallPlanner().planV3(ledger, reservation, actualRoadMask, 9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5));

        assertEquals("city_wall_plan.v0.3", wallPlan.get("schemaVersion").getAsString());
        assertEquals("v3", wallPlan.get("wallVersion").getAsString());
        assertEquals("structure_seeded_patch_region_hull", wallPlan.get("wallBoundaryMode").getAsString());
        assertFalse(wallPlan.getAsJsonArray("gateClusters").isEmpty());
        assertTrue(wallPlan.getAsJsonArray("classifiedRoadComponents").toString().contains("insideRoad"));
        assertTrue(wallPlan.getAsJsonArray("insideRoadIgnoredIntersections").toString().contains("insideRoad"));
        assertTrue(wallPlan.getAsJsonArray("wallSegments").toString().contains("DOMAIN_HULL_WALL_SEGMENT"));
        assertEquals("v3", wallPlan.getAsJsonObject("terrainFitPolicy").get("policyVersion").getAsString());
    }

    @Test
    void wallPlannerV3CanEmitTerrainPolicyV31() {
        JsonObject reservation = JsonParser.parseString("""
                {
                  "schemaVersion": "city_wall_reservation_plan.v0.3",
                  "cityId": "city_test",
                  "wallBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32},
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -16, "minZ": -16, "maxX": 16, "maxZ": 16}}
                  ],
                  "wallCenterline": [
                    {"segmentId": "north", "blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": -28}}
                  ],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
        JsonObject actualRoadMask = JsonParser.parseString("""
                {"schemaVersion":"city_actual_road_mask.v0.1","cityId":"city_test","roadMask":[]}
                """).getAsJsonObject();
        JsonObject ledger = JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}}
                  ]
                }
                """).getAsJsonObject();

        JsonObject wallPlan = new CityWallPlanner().planV3(ledger, reservation, actualRoadMask, 9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true));

        JsonObject policy = wallPlan.getAsJsonObject("terrainFitPolicy");
        assertEquals("v3.1", wallPlan.get("wallTerrainPolicy").getAsString());
        assertEquals("v3.1", policy.get("policyVersion").getAsString());
        assertEquals(7, policy.get("flatMaxDeltaBlocks").getAsInt());
        assertEquals(16, policy.get("steppedMaxDeltaBlocks").getAsInt());
        assertEquals(6, policy.get("mountainProbeDistanceBlocks").getAsInt());
        assertEquals(17, policy.get("naturalBoundaryMinDeltaBlocks").getAsInt());
        assertTrue(policy.get("embeddedSlopeTower").getAsBoolean());
        assertEquals("low_flat_mid_stepped_high_embedded_or_cliff", policy.get("slopeMode").getAsString());
    }

    @Test
    void wallPlannerV3MarksWallAxisForExecution() {
        JsonObject reservation = JsonParser.parseString("""
                {
                  "schemaVersion": "city_wall_reservation_plan.v0.3",
                  "cityId": "city_test",
                  "wallBounds": {"minX": -40, "minZ": -40, "maxX": 40, "maxZ": 40},
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -16, "minZ": -16, "maxX": 16, "maxZ": 16}}
                  ],
                  "wallCenterline": [
                    {"segmentId": "north", "blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": -24}},
                    {"segmentId": "west", "blockBounds": {"minX": -32, "minZ": -32, "maxX": -24, "maxZ": 32}}
                  ],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
        JsonObject actualRoadMask = JsonParser.parseString("""
                {"schemaVersion":"city_actual_road_mask.v0.1","cityId":"city_test","roadMask":[]}
                """).getAsJsonObject();
        JsonObject ledger = JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}}
                  ]
                }
                """).getAsJsonObject();

        JsonObject wallPlan = new CityWallPlanner().planV3(ledger, reservation, actualRoadMask, 9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true));

        String segments = wallPlan.getAsJsonArray("wallSegments").toString();
        assertTrue(segments.contains("\"wallAxis\":\"X\""));
        assertTrue(segments.contains("\"wallAxis\":\"Z\""));
    }

    @Test
    void wallPlannerV32EmitsNaturalBoundariesGatehousesAndTrendSkips() {
        JsonObject reservation = JsonParser.parseString("""
                {
                  "schemaVersion": "city_wall_reservation_plan.v0.3",
                  "cityId": "city_test",
                  "wallBounds": {"minX": -64, "minZ": -64, "maxX": 64, "maxZ": 64},
                  "seedPatches": [
                    {"landformPatchId": "water_big", "mapLabel": "水域01", "landformType": "water",
                     "blockBounds": {"minX": -96, "minZ": -96, "maxX": 96, "maxZ": -48}},
                    {"landformPatchId": "shore_01", "mapLabel": "海岸01", "landformType": "shore",
                     "blockBounds": {"minX": -64, "minZ": -48, "maxX": 64, "maxZ": -32}}
                  ],
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32}}
                  ],
                  "wallCenterline": [
                    {"segmentId": "north", "blockBounds": {"minX": -64, "minZ": -64, "maxX": 64, "maxZ": -60}},
                    {"segmentId": "east", "blockBounds": {"minX": 60, "minZ": -64, "maxX": 64, "maxZ": 64}}
                  ],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
        JsonObject actualRoadMask = JsonParser.parseString("""
                {
                  "schemaVersion": "city_actual_road_mask.v0.2",
                  "cityId": "city_test",
                  "status": "observed",
                  "roadMask": [
                    {"maskId": "road_long_0", "maskType": "actual_road", "blockBounds": {"minX": 16, "minZ": -48, "maxX": 64, "maxZ": 16}},
                    {"maskId": "road_touch_0", "maskType": "actual_road", "blockBounds": {"minX": -4, "minZ": -64, "maxX": -3, "maxZ": -63}}
                  ]
                }
                """).getAsJsonObject();
        JsonObject ledger = JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}}
                  ]
                }
                """).getAsJsonObject();

        JsonObject wallPlan = new CityWallPlanner().planV3(ledger, reservation, actualRoadMask, 9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true,
                        "v3.2", 48, 24, 4096));

        assertEquals("v3.2", wallPlan.get("wallDesignPolicy").getAsString());
        assertTrue(wallPlan.getAsJsonArray("wallSegments").toString().contains("\"segmentType\":\"gatehouse\""),
                wallPlan.toString());
        assertTrue(wallPlan.getAsJsonArray("wallSegments").toString().contains("\"segmentType\":\"natural_boundary\""));
        assertTrue(wallPlan.getAsJsonArray("roadTrendSkippedIntersections").toString()
                .contains("WALL_ROAD_TOUCH_ONLY_SKIP"));
        assertTrue(wallPlan.getAsJsonArray("naturalBoundaries").toString().contains("NATURAL_WATER_BOUNDARY"));
    }

    @Test
    void wallPlannerV33ProjectsNearbyExternalRoadIntoGatehouse() {
        JsonObject wallPlan = new CityWallPlanner().planV3(syntheticWallLedger(), syntheticEastWallReservation(),
                roadMaskFromBlocks("city_test",
                        new int[][]{
                                {70, 0}, {71, 0}, {72, 0}, {73, 0},
                                {74, 0}, {75, 0}, {76, 0}, {77, 0}
                        }),
                9, 2, 8, 7, v33Options());

        assertEquals("v3.3", wallPlan.get("wallDesignPolicy").getAsString());
        assertTrue(wallPlan.getAsJsonArray("generatedGates").toString()
                        .contains("WALL_GATE_FROM_ROAD_PROJECTION"),
                wallPlan.toString());
        assertFalse(wallPlan.getAsJsonArray("projectedRoadGateCandidates").isEmpty());
        assertTrue(wallPlan.getAsJsonArray("wallSegments").toString().contains("\"segmentType\":\"gatehouse\""));
    }

    @Test
    void wallPlannerV33DoesNotProjectInsideRoads() {
        JsonObject wallPlan = new CityWallPlanner().planV3(syntheticWallLedger(), syntheticEastWallReservation(),
                roadMaskFromBlocks("city_test",
                        new int[][]{
                                {0, 0}, {1, 0}, {2, 0}, {3, 0},
                                {4, 0}, {5, 0}, {6, 0}, {7, 0}
                        }),
                9, 2, 8, 7, v33Options());

        assertTrue(wallPlan.getAsJsonArray("projectedRoadGateCandidates").isEmpty());
        assertTrue(wallPlan.getAsJsonArray("insideRoadIgnoredIntersections").toString().contains("insideRoad"));
        assertEquals("NO_VALID_GATE_CANDIDATE_AFTER_FILTER",
                wallPlan.get("gateFallbackReasonCode").getAsString());
    }

    @Test
    void wallPlannerV33KeepsTouchOnlyRoadsSkipped() {
        JsonObject wallPlan = new CityWallPlanner().planV3(syntheticWallLedger(), syntheticEastWallReservation(),
                roadMaskFromBlocks("city_test", new int[][]{{70, 0}}),
                9, 2, 8, 7, v33Options());

        assertTrue(wallPlan.getAsJsonArray("projectedRoadGateCandidates").isEmpty());
        assertTrue(wallPlan.getAsJsonArray("roadTrendSkippedIntersections").toString()
                .contains("WALL_ROAD_TOUCH_ONLY_SKIP"));
        assertTrue(wallPlan.getAsJsonArray("roadProjectionSkippedIntersections").toString()
                .contains("WALL_ROAD_TOUCH_ONLY_SKIP"));
    }

    @Test
    void wallPlannerV33DoesNotProjectParallelRoads() {
        JsonObject wallPlan = new CityWallPlanner().planV3(syntheticWallLedger(), syntheticEastWallReservation(),
                roadMaskFromBlocks("city_test",
                        new int[][]{
                                {70, 0}, {70, 1}, {70, 2}, {70, 3},
                                {70, 4}, {70, 5}, {70, 6}, {70, 7}
                        }),
                9, 2, 8, 7, v33Options());

        assertTrue(wallPlan.getAsJsonArray("projectedRoadGateCandidates").isEmpty());
        assertTrue(wallPlan.getAsJsonArray("roadProjectionSkippedIntersections").toString()
                .contains("WALL_ROAD_PROJECTION_NOT_ALIGNED"));
    }

    @Test
    void wallPlannerV33MergesNearbyProjectedGatesBySpacing() {
        JsonObject wallPlan = new CityWallPlanner().planV3(syntheticWallLedger(), syntheticEastWallReservation(),
                roadMaskFromBlocks("city_test",
                        new int[][]{
                                {70, 0}, {71, 0}, {72, 0}, {73, 0},
                                {74, 0}, {75, 0}, {76, 0}, {77, 0},
                                {70, 20}, {71, 20}, {72, 20}, {73, 20},
                                {74, 20}, {75, 20}, {76, 20}, {77, 20}
                        }),
                9, 2, 8, 7, v33Options());

        assertEquals(2, wallPlan.getAsJsonArray("projectedRoadGateCandidates").size());
        assertEquals(1, wallPlan.getAsJsonArray("gateClusters").size());
        assertEquals(1, wallPlan.getAsJsonArray("generatedGates").size());
    }

    @Test
    void wallPlannerV33FallbackReasonDistinguishesFilteredRoadsFromEmptyRoadMask() {
        JsonObject wallPlan = new CityWallPlanner().planV3(syntheticWallLedger(), syntheticEastWallReservation(),
                roadMaskFromBlocks("city_test", new int[][]{}),
                9, 2, 8, 7, v33Options());

        assertEquals("NO_VALID_GATE_CANDIDATE_AFTER_FILTER",
                wallPlan.get("gateFallbackReasonCode").getAsString());
        assertTrue(wallPlan.getAsJsonArray("generatedGates").toString()
                .contains("NO_VALID_GATE_CANDIDATE_AFTER_FILTER"));
    }

    @Test
    void wallPlannerV4EmitsGraphDatumAndIncludesActualFootprintOutsidePatch() {
        JsonObject wallPlan = new CityWallPlanner().planV4(syntheticWideWallLedger(), syntheticV4Reservation(),
                roadMaskFromBlocks("city_test", new int[][]{}), 9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true),
                CityWallPlanner.V4Options.defaults());

        assertEquals("city_wall_plan.v0.4", wallPlan.get("schemaVersion").getAsString());
        assertEquals("v4", wallPlan.get("wallVersion").getAsString());
        assertEquals("actual_footprint_land_ring", wallPlan.get("wallBoundaryMode").getAsString());
        assertTrue(wallPlan.has("cityWallDatumY"));
        assertFalse(wallPlan.getAsJsonArray("wallNodes").isEmpty());
        assertFalse(wallPlan.getAsJsonArray("wallUnits").isEmpty());
        assertFalse(wallPlan.getAsJsonArray("nodeConnectorUnits").isEmpty());
        BlockBounds wallBounds = bounds(wallPlan.getAsJsonObject("wallBounds"));
        assertTrue(wallBounds.contains(132, 12), wallPlan.toString());
        assertTrue(wallPlan.getAsJsonArray("wallUnits").toString().contains("\"targetY\""));
        assertTrue(wallPlan.getAsJsonArray("wallNodes").toString().contains("\"surfaceMedianY\""));
        assertTrue(wallPlan.getAsJsonObject("wallGraphValidation").has("breaks"));
        assertTrue(wallPlan.getAsJsonObject("wallGraphValidation").has("outsideKnownPatchUnitCount"));
    }

    @Test
    void wallPlannerV4UsesDomainCellsToBreakRectangularLandRing() {
        JsonObject wallPlan = new CityWallPlanner().planV4(syntheticWallLedger(),
                syntheticV4TerrainContourReservation(), roadMaskFromBlocks("city_test", new int[][]{}),
                9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true),
                CityWallPlanner.V4Options.defaults());

        assertEquals("terrain_adaptive_domain_guided_land_ring",
                wallPlan.get("wallContourMode").getAsString());
        assertFalse(wallPlan.getAsJsonArray("terrainContourEvents").isEmpty(), wallPlan.toString());
        assertTrue(wallPlan.getAsJsonObject("wallGraphValidation")
                .get("terrainContourAdjustedUnitCount").getAsInt() > 0, wallPlan.toString());
        assertTrue(wallPlan.getAsJsonObject("wallGraphValidation")
                .get("terrainContourLinkUnitCount").getAsInt() > 0, wallPlan.toString());

        Set<Integer> northWallZ = new HashSet<>();
        boolean sawContourLink = false;
        for (com.google.gson.JsonElement elem : wallPlan.getAsJsonArray("wallUnits")) {
            JsonObject unit = elem.getAsJsonObject();
            if (!unit.get("terrainContourAdjusted").getAsBoolean()) {
                continue;
            }
            if (unit.get("terrainContourLink").getAsBoolean()) {
                sawContourLink = true;
            }
            if ("north".equals(unit.get("side").getAsString())
                    && "X".equals(unit.get("wallAxis").getAsString())) {
                northWallZ.add(bounds(unit.getAsJsonObject("blockBounds")).center().z());
            }
        }
        assertTrue(sawContourLink, wallPlan.getAsJsonArray("wallUnits").toString());
        assertTrue(northWallZ.size() > 1, wallPlan.getAsJsonArray("wallUnits").toString());
    }

    @Test
    void landformReviewBuilderIncludesPaddingCellsAcrossGisRegions() {
        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(16);
        AtlasRegion west = new AtlasRegion("minecraft:overworld", 0, 0, sampleConfig);
        AtlasRegion east = new AtlasRegion("minecraft:overworld", 1, 0, sampleConfig);
        west.cell(31, 8).setLandformType(LandformType.PLAIN);
        east.cell(0, 8).setLandformType(LandformType.SLOPE);
        PatchMerger merger = new PatchMerger(GisClassifierConfig.defaults());
        merger.merge(west);
        merger.merge(east);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder(config)
                .build("city_region_edge", "realm_test", "minecraft:overworld",
                        "city_region_edge", "candidate_region_edge", 500, 128,
                        "village", "village", 8, 16, null);
        BlockBounds padded = new BlockBounds(context.bounds().minX() - 128, context.bounds().minZ() - 128,
                context.bounds().maxX() + 128, context.bounds().maxZ() + 128);

        CityLandformReviewPackage review = new com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder(config)
                .buildFromRegions(context, List.of(west, east), padded);

        assertTrue(review.landformPatches().stream()
                        .flatMap(patch -> patch.memberCells().stream())
                        .anyMatch(cell -> cell.blockMinX() == 512),
                review.asJson().toString());
    }

    @Test
    void wallPlannerV4AlignsNodesWithWallUnitsInsteadOfCreatingInnerColumnRing() {
        JsonObject wallPlan = new CityWallPlanner().planV4(syntheticWideWallLedger(), syntheticV4Reservation(),
                roadMaskFromBlocks("city_test", new int[][]{}), 9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true),
                CityWallPlanner.V4Options.defaults());

        java.util.Map<String, BlockBounds> nodeBoundsById = new java.util.HashMap<>();
        int junctionCount = 0;
        for (com.google.gson.JsonElement elem : wallPlan.getAsJsonArray("wallNodes")) {
            JsonObject node = elem.getAsJsonObject();
            nodeBoundsById.put(node.get("nodeId").getAsString(), bounds(node.getAsJsonObject("blockBounds")));
            if ("junction".equals(node.get("nodeType").getAsString())) {
                junctionCount++;
                assertEquals("graph_only", node.get("placementRole").getAsString());
            }
        }
        assertTrue(junctionCount > 0, wallPlan.getAsJsonArray("wallNodes").toString());

        for (com.google.gson.JsonElement elem : wallPlan.getAsJsonArray("wallUnits")) {
            JsonObject unit = elem.getAsJsonObject();
            if ("natural_boundary_gap".equals(unit.get("unitType").getAsString())) {
                continue;
            }
            BlockBounds unitBounds = bounds(unit.getAsJsonObject("blockBounds"));
            BlockBounds fromNodeBounds = nodeBoundsById.get(unit.get("fromNodeId").getAsString());
            assertTrue(fromNodeBounds != null, unit.toString());
            assertTrue(fromNodeBounds.overlaps(unitBounds), unit.toString() + " node=" + fromNodeBounds);
        }
    }

    @Test
    void wallPlannerV4PlacesGatehouseNodeOnSkippedRoadUnit() {
        JsonObject wallPlan = new CityWallPlanner().planV4(syntheticWideWallLedger(), syntheticV4Reservation(),
                roadMaskFromBlocks("city_test", new int[][]{{175, 0}, {175, 1}, {175, 2}}),
                9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true),
                CityWallPlanner.V4Options.defaults());

        java.util.Map<String, BlockBounds> nodeBoundsById = new java.util.HashMap<>();
        java.util.Map<String, String> nodeTypesById = new java.util.HashMap<>();
        for (com.google.gson.JsonElement elem : wallPlan.getAsJsonArray("wallNodes")) {
            JsonObject node = elem.getAsJsonObject();
            nodeBoundsById.put(node.get("nodeId").getAsString(), bounds(node.getAsJsonObject("blockBounds")));
            nodeTypesById.put(node.get("nodeId").getAsString(), node.get("nodeType").getAsString());
        }

        boolean sawGatehouseOpening = false;
        for (com.google.gson.JsonElement elem : wallPlan.getAsJsonArray("wallUnits")) {
            JsonObject unit = elem.getAsJsonObject();
            if (!"skipped_wall_unit".equals(unit.get("unitType").getAsString())) {
                continue;
            }
            if (!"ROAD_MASK_GATEHOUSE_OPENING".equals(unit.get("reasonCode").getAsString())) {
                continue;
            }
            sawGatehouseOpening = true;
            String fromNodeId = unit.get("fromNodeId").getAsString();
            assertEquals("gatehouse", nodeTypesById.get(fromNodeId), unit.toString());
            assertTrue(nodeBoundsById.get(fromNodeId).overlaps(bounds(unit.getAsJsonObject("blockBounds"))),
                    unit.toString());
        }
        assertTrue(sawGatehouseOpening, wallPlan.toString());
    }

    @Test
    void wallPlannerV4IgnoresMasonryRoadMaskFalsePositives() {
        JsonObject wallPlan = new CityWallPlanner().planV4(syntheticWideWallLedger(), syntheticV4Reservation(),
                roadMaskFromBlocks("city_test", new int[][]{{175, 0}, {175, 1}, {175, 2}},
                        "minecraft:stone_bricks"),
                9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true),
                CityWallPlanner.V4Options.defaults());

        assertFalse(wallPlan.getAsJsonArray("wallUnits").toString().contains("ROAD_MASK_GATEHOUSE_OPENING"),
                wallPlan.toString());
    }

    @Test
    void wallPlannerV4RetreatsContinuousWaterRunAndLeavesNoOrdinaryWaterUnits() {
        JsonObject wallPlan = new CityWallPlanner().planV4(syntheticWallLedger(), syntheticV4WaterReservation(),
                roadMaskFromBlocks("city_test", new int[][]{}), 9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true),
                CityWallPlanner.V4Options.defaults());

        assertFalse(wallPlan.getAsJsonArray("waterRetreatEvents").isEmpty(), wallPlan.toString());
        assertEquals(0, wallPlan.getAsJsonObject("wallGraphValidation")
                .get("ordinaryWallUnitsInWater").getAsInt(), wallPlan.toString());
        for (com.google.gson.JsonElement elem : wallPlan.getAsJsonArray("wallUnits")) {
            JsonObject unit = elem.getAsJsonObject();
            if (unit.get("placementAllowed").getAsBoolean()) {
                assertFalse(unit.get("waterOverlapAfterRetreat").getAsBoolean(), unit.toString());
            }
        }
    }

    @Test
    void wallPlannerV4UsesWaterMemberCellsInsteadOfPatchEnvelope() {
        JsonObject wallPlan = new CityWallPlanner().planV4(syntheticWallLedger(),
                syntheticV4PreciseWaterReservation(), roadMaskFromBlocks("city_test", new int[][]{}),
                9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true),
                CityWallPlanner.V4Options.defaults());

        int wallUnitCount = wallPlan.getAsJsonArray("wallUnits").size();
        int naturalGapCount = wallPlan.getAsJsonObject("wallGraphValidation")
                .get("naturalBoundaryGapCount").getAsInt();
        assertTrue(naturalGapCount < wallUnitCount, wallPlan.toString());
        assertTrue(wallPlan.getAsJsonArray("wallUnits").toString().contains("\"unitType\":\"wall_unit\""),
                wallPlan.toString());
        assertEquals(0, wallPlan.getAsJsonObject("wallGraphValidation")
                .get("ordinaryWallUnitsInWater").getAsInt(), wallPlan.toString());
    }

    @Test
    void wallPlannerV4CreatesSteppedUnitsTerraceNodesAndConnectorsForHeightBands() {
        JsonObject wallPlan = new CityWallPlanner().planV4(syntheticWallLedger(), syntheticV4Reservation(),
                roadMaskFromBlocks("city_test", new int[][]{}), 9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true),
                CityWallPlanner.V4Options.defaults());

        assertTrue(wallPlan.getAsJsonArray("wallUnits").toString().contains("\"unitType\":\"stepped_wall_unit\"")
                        || wallPlan.getAsJsonArray("wallUnits").toString().contains("\"unitType\":\"terraced_wall_unit\""),
                wallPlan.toString());
        assertTrue(wallPlan.getAsJsonArray("wallNodes").toString().contains("\"nodeType\":\"terrace_node\""),
                wallPlan.toString());
        assertTrue(wallPlan.getAsJsonArray("nodeConnectorUnits").toString()
                .contains("\"connectorStatus\":\"stepped\""));
        assertTrue(wallPlan.getAsJsonObject("wallGraphValidation")
                .get("heightBreakCount").getAsInt() > 0);
    }

    @Test
    void wallTemplateLibraryIncludesGatehousesAndUsableTowers() {
        String library = CityWallTemplateLibrary.libraryJson().toString();

        assertTrue(library.contains("gatehouse_9"));
        assertTrue(library.contains("gatehouse_13"));
        assertTrue(library.contains("watchtower_5x5"));
        assertTrue(library.contains("beacon_5x5"));
    }

    @Test
    void beaconTemplateKeepsWalkableCenterAndStraightClimbAccess() throws Exception {
        Path dir = Files.createTempDirectory("city-wall-beacon-template-test");
        CityWallTemplateLibrary.writeTemplates(dir);

        CompoundTag beacon = NbtIo.readCompressed(dir.resolve("beacon_5x5.nbt").toFile());
        assertEquals(5, beacon.getList("size", 3).getInt(0));
        assertEquals(16, beacon.getList("size", 3).getInt(1));
        assertEquals(5, beacon.getList("size", 3).getInt(2));

        ListTag palette = beacon.getList("palette", 10);
        int ladderState = -1;
        int stoneBricks = paletteState(beacon, "minecraft:stone_bricks");
        for (int i = 0; i < palette.size(); i++) {
            if ("minecraft:ladder".equals(palette.getCompound(i).getString("Name"))) {
                ladderState = i;
                break;
            }
        }
        assertTrue(ladderState >= 0, beacon.toString());

        ListTag blocks = beacon.getList("blocks", 10);
        for (int y = 1; y <= 6; y++) {
            assertFalse(hasTemplateBlockAt(blocks, 2, y, 2),
                    "center walkway must stay open at y=" + y);
        }
        assertEquals(stoneBricks, templateStateAt(blocks, 2, 7, 2),
                "wall-top passage floor must be walkable");
        for (int y = 8; y <= 12; y++) {
            assertFalse(hasTemplateBlockAt(blocks, 2, y, 2),
                    "wall-top passage headroom must stay open at y=" + y);
        }
        for (int y = 8; y <= 11; y++) {
            assertFalse(hasTemplateBlockAt(blocks, 0, y, 2),
                    "left wall-top connection must stay open at y=" + y);
            assertFalse(hasTemplateBlockAt(blocks, 4, y, 2),
                    "right wall-top connection must stay open at y=" + y);
        }
        for (int y = 1; y <= 3; y++) {
            assertFalse(hasTemplateBlockAt(blocks, 2, y, 0),
                    "front doorway must stay open at y=" + y);
            assertFalse(hasTemplateBlockAt(blocks, 2, y, 4),
                    "back doorway must stay open at y=" + y);
            assertFalse(hasTemplateBlockAt(blocks, 0, y, 2),
                    "left wall connection doorway must stay open at y=" + y);
            assertFalse(hasTemplateBlockAt(blocks, 4, y, 2),
                    "right wall connection doorway must stay open at y=" + y);
        }
        for (int y = 1; y <= 13; y++) {
            assertEquals(ladderState, templateStateAt(blocks, 2, y, 3),
                    "straight climb access must be continuous at y=" + y);
        }
    }

    @Test
    void gatehouseTemplateUsesCompactStoneCappedOpening() throws Exception {
        Path dir = Files.createTempDirectory("city-wall-gatehouse-template-test");
        CityWallTemplateLibrary.writeTemplates(dir);

        CompoundTag gatehouse = NbtIo.readCompressed(dir.resolve("gatehouse_9.nbt").toFile());
        int stoneBricks = paletteState(gatehouse, "minecraft:stone_bricks");
        int oakFence = paletteState(gatehouse, "minecraft:oak_fence");
        ListTag blocks = gatehouse.getList("blocks", 10);

        assertEquals(oakFence, templateStateAt(blocks, 3, 1, 3));
        assertFalse(hasTemplateBlockAt(blocks, 4, 1, 3));
        assertEquals(oakFence, templateStateAt(blocks, 5, 1, 3));
        assertEquals(stoneBricks, templateStateAt(blocks, 4, 5, 3));
        assertEquals(stoneBricks, templateStateAt(blocks, 4, 6, 3));
    }

    @Test
    void wallBackendFoundationDepthUsesOriginalV5SurfaceHeight() throws Exception {
        Method depth = Class.forName("com.rinsing.geomantia.systems.city.infrastructure.world.CityWallPlacementBackend")
                .getDeclaredMethod("foundationDepth", int.class, int.class, int.class);
        depth.setAccessible(true);

        assertEquals(3, ((Number) depth.invoke(null, 70, 67, 64)).intValue());
        assertEquals(0, ((Number) depth.invoke(null, 70, 71, 64)).intValue());
        assertEquals(2, ((Number) depth.invoke(null, 70, 60, 2)).intValue());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void wallBackendSplitsTerrainUnitsAlongWallAxis() throws Exception {
        Class<?> axisClass = Class.forName("com.rinsing.geomantia.systems.city.infrastructure.world.CityWallPlacementBackend$Axis");
        Object zAxis = Enum.valueOf((Class<Enum>) axisClass, "Z");
        Method split = Class.forName("com.rinsing.geomantia.systems.city.infrastructure.world.CityWallPlacementBackend")
                .getDeclaredMethod("splitUnits", BlockBounds.class, int.class, axisClass);
        split.setAccessible(true);

        List<BlockBounds> units = (List<BlockBounds>) split.invoke(null, new BlockBounds(-4, 0, 4, 31), 8, zAxis);

        assertEquals(4, units.size());
        for (BlockBounds unit : units) {
            assertEquals(-4, unit.minX());
            assertEquals(4, unit.maxX());
        }
        assertEquals(0, units.get(0).minZ());
        assertEquals(7, units.get(0).maxZ());
        assertEquals(24, units.get(3).minZ());
        assertEquals(31, units.get(3).maxZ());
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
        assertTrue(planned.has("collisionEnvelope"));
        assertTrue(planned.has("maskEnvelope"));
        assertFalse(planned.has("safetyEnvelope"));
        assertTrue(planned.has("envelopeMode"));
        ChunkPos anchorChunk = new ChunkPos(
                planned.getAsJsonObject("anchorChunk").get("x").getAsInt(),
                planned.getAsJsonObject("anchorChunk").get("z").getAsInt());
        assertEquals(1, CityReservationMaskRegistry.plannedStructuresForChunk(anchorChunk).size());
        CityReservationMaskRegistry.PlannedStructure plannedStructure =
                CityReservationMaskRegistry.plannedStructuresForChunk(anchorChunk).get(0);
        CityReservationMaskRegistry.recordWorldgenPlacement(plannedStructure, plannedStructure.plannedFootprint(),
                "sig_registry", new JsonArray(), anchorChunk,
                "none", "WORLDGEN_PLACEMENT_RECORDED", "test placement");
        JsonObject ledgerItem = CityReservationMaskRegistry.ledgerForCity(fixture.context().cityId())
                .getAsJsonArray("placedStructures").get(0).getAsJsonObject();
        assertFalse(ledgerItem.has("safetyEnvelope"));
    }

    @Test
    void d5ActiveRegistryLedgerIsScopedToWorldRoot() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject mask = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap)
                .reservationMaskPlan();
        Path worldRootA = Files.createTempDirectory("city-mask-world-a");
        Path worldRootB = Files.createTempDirectory("city-mask-world-b");

        JsonObject activeA = CityReservationMaskRegistry.activate(mask, anchorMap,
                "run_a", fixture.context().cityId(), worldRootA);
        JsonObject plannedJson = activeA.getAsJsonArray("plannedStructures").get(0).getAsJsonObject();
        JsonObject anchorChunkJson = plannedJson.getAsJsonObject("anchorChunk");
        ChunkPos anchorChunk = new ChunkPos(
                anchorChunkJson.get("x").getAsInt(),
                anchorChunkJson.get("z").getAsInt());
        CityReservationMaskRegistry.PlannedStructure plannedA = CityReservationMaskRegistry
                .plannedStructuresForChunk(anchorChunk)
                .get(0);
        CityReservationMaskRegistry.recordWorldgenPlacement(plannedA, plannedA.plannedFootprint(),
                "sig_a", new JsonArray(), anchorChunk,
                "none", "WORLDGEN_PLACEMENT_RECORDED", "test placement");
        assertEquals(1, CityReservationMaskRegistry.ledgerForCity(fixture.context().cityId())
                .getAsJsonArray("placedStructures").size());

        CityReservationMaskRegistry.activate(mask, anchorMap,
                "run_b", fixture.context().cityId(), worldRootB);

        assertTrue(Files.exists(CityReservationMaskRegistry.worldgenLedgerPath(worldRootA)));
        assertTrue(Files.exists(CityReservationMaskRegistry.worldgenLedgerPath(worldRootB)));
        assertEquals(0, CityReservationMaskRegistry.ledgerForCity(fixture.context().cityId())
                .getAsJsonArray("placedStructures").size());
    }

    @Test
    void worldgenLedgerDeduplicationIsScopedToCityIdentity() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject mask = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap)
                .reservationMaskPlan();
        Path worldRoot = Files.createTempDirectory("city-mask-shared-world");

        JsonObject activeA = CityReservationMaskRegistry.activate(mask, anchorMap,
                "run_a", fixture.context().cityId(), worldRoot);
        JsonObject plannedJson = activeA.getAsJsonArray("plannedStructures").get(0).getAsJsonObject();
        JsonObject anchorChunkJson = plannedJson.getAsJsonObject("anchorChunk");
        ChunkPos anchorChunk = new ChunkPos(
                anchorChunkJson.get("x").getAsInt(),
                anchorChunkJson.get("z").getAsInt());
        CityReservationMaskRegistry.PlannedStructure plannedA = CityReservationMaskRegistry
                .plannedStructuresForChunk(anchorChunk)
                .get(0);
        CityReservationMaskRegistry.recordWorldgenPlacement(plannedA, plannedA.plannedFootprint(),
                "sig_a", new JsonArray(), anchorChunk,
                "none", "WORLDGEN_PLACEMENT_RECORDED", "test placement");

        JsonObject secondCityAnchorMap = anchorMap.deepCopy();
        secondCityAnchorMap.addProperty("cityId", "city_other_with_same_anchor_ids");
        CityReservationMaskRegistry.activate(mask, secondCityAnchorMap,
                "run_b", "city_other_with_same_anchor_ids", worldRoot);

        assertEquals(1, CityReservationMaskRegistry.plannedStructuresForChunk(anchorChunk).size());
        assertEquals(0, CityReservationMaskRegistry.ledgerForCity("city_other_with_same_anchor_ids")
                .getAsJsonArray("placedStructures").size());
    }

    @Test
    void worldgenLedgerDeduplicationIsScopedToRunIdentity() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject mask = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap)
                .reservationMaskPlan();
        Path worldRoot = Files.createTempDirectory("city-mask-shared-run");

        JsonObject activeA = CityReservationMaskRegistry.activate(mask, anchorMap,
                "run_a", fixture.context().cityId(), worldRoot);
        JsonObject plannedJson = activeA.getAsJsonArray("plannedStructures").get(0).getAsJsonObject();
        JsonObject anchorChunkJson = plannedJson.getAsJsonObject("anchorChunk");
        ChunkPos anchorChunk = new ChunkPos(
                anchorChunkJson.get("x").getAsInt(),
                anchorChunkJson.get("z").getAsInt());
        CityReservationMaskRegistry.PlannedStructure plannedA = CityReservationMaskRegistry
                .plannedStructuresForChunk(anchorChunk)
                .get(0);
        CityReservationMaskRegistry.recordWorldgenPlacement(plannedA, plannedA.plannedFootprint(),
                "sig_a", new JsonArray(), anchorChunk,
                "none", "WORLDGEN_PLACEMENT_RECORDED", "test placement");

        CityReservationMaskRegistry.activate(mask, anchorMap,
                "run_b", fixture.context().cityId(), worldRoot);

        assertEquals(1, CityReservationMaskRegistry.plannedStructuresForChunk(anchorChunk).size());
        assertEquals(0, CityReservationMaskRegistry.ledgerForCity(
                        "run_b", fixture.context().cityId(), fixture.context().cityId())
                .getAsJsonArray("placedStructures").size());
        assertEquals(1, CityReservationMaskRegistry.ledgerForCity(
                        "run_a", fixture.context().cityId(), fixture.context().cityId())
                .getAsJsonArray("placedStructures").size());
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
        assertTrue(dryRun.structureMaterializationPlan().get("locked").getAsBoolean());
        assertTrue(planned.get("locked").getAsBoolean());
        assertTrue(planned.has("lockedActualFootprint"));
        assertTrue(planned.has("lockedCollisionEnvelope"));
        assertTrue(planned.has("lockedBBoxGroupKey"));
        assertTrue(planned.has("expectedStartSignature"));
        assertTrue(planned.has("pieceBoxes"));
        assertFalse(planned.has("safetyEnvelope"));
        assertEquals(expand(bounds(planned.getAsJsonObject("lockedCollisionEnvelope")), 8),
                bounds(planned.getAsJsonObject("maskEnvelope")));

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
    void d6PreflightLocksActualBBoxEvenWhenItDiffersFromD4Envelope() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeProfiler.Result factsResult = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:desert_pyramid"), 2,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-4, -5, 15, 6), 1,
                                "fixed_config_hash", "pack_hash"));
        Path factsPath = fixture.baseDir().resolve("fixed_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()),
                        CityStructureEnvelopeFacts.load(factsPath))
                .structureAnchorMap();
        JsonObject anchor = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        JsonObject anchorBlock = anchor.getAsJsonObject("anchorBlock");
        int originX = Math.floorDiv(anchorBlock.get("x").getAsInt(), 16) * 16;
        int originZ = Math.floorDiv(anchorBlock.get("z").getAsInt(), 16) * 16;
        BlockBounds actual = new BlockBounds(originX - 4, originZ - 5, originX + 15, originZ + 6);

        CityStructureMaterializationPlanner.Result result = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        new FakePlacementBackend("sig", true, actual), null);

        JsonObject attempt = result.structureMaterializationTrace()
                .getAsJsonArray("attempts").get(0).getAsJsonObject();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertTrue(attempt.get("locked").getAsBoolean());
        assertTrue(attempt.has("actualLocalBounds"));
        assertTrue(attempt.has("actualBBoxGroupKey"));
        assertTrue(attempt.has("lockedCollisionEnvelope"));
    }

    @Test
    void d6PreflightRejectsFixedBBoxGroupMissingFromFacts() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeProfiler.Result factsResult = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:desert_pyramid"), 2,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-4, -5, 15, 6), 1,
                                "fixed_config_hash", "pack_hash"));
        Path factsPath = fixture.baseDir().resolve("fixed_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()),
                        CityStructureEnvelopeFacts.load(factsPath))
                .structureAnchorMap();
        JsonObject anchor = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        JsonObject anchorBlock = anchor.getAsJsonObject("anchorBlock");
        int originX = Math.floorDiv(anchorBlock.get("x").getAsInt(), 16) * 16;
        int originZ = Math.floorDiv(anchorBlock.get("z").getAsInt(), 16) * 16;
        BlockBounds unprofiledShape = new BlockBounds(originX - 9, originZ - 5, originX + 15, originZ + 6);

        CityStructureMaterializationPlanner.Result result = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        new FakePlacementBackend("sig", true, unprofiledShape), null);

        assertFalse(result.asJson().get("ok").getAsBoolean());
        JsonObject attempt = result.structureMaterializationTrace()
                .getAsJsonArray("attempts").get(0).getAsJsonObject();
        assertEquals("BBOX_GROUP_NOT_IN_FACTS", attempt.get("reasonCode").getAsString());
        assertTrue(attempt.has("availableEnvelopeGroupKeys"));
    }

    @Test
    void d6PreflightRejectsOverlappingLockedCollisionEnvelope() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject anchor = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        BlockBounds first = bounds(anchor.getAsJsonObject("reservedEnvelope"));

        CityStructureMaterializationPlanner.Result result = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        new FakePlacementBackend("sig", true, first), null);

        assertFalse(result.asJson().get("ok").getAsBoolean());
        assertTrue(result.structureMaterializationTrace()
                .getAsJsonObject("failureSummary")
                .has("LEDGER_OCCUPIED_OVERLAP"));
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

    private static Fixture arrayFixture() throws Exception {
        Path baseDir = Files.createTempDirectory("city-structure-array");
        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder(config)
                .build("city_test", "realm_test", "minecraft:overworld",
                        "city_test", "candidate_test", 0, 0,
                        "village", "village", 220, 4, null);
        CityLandformReviewPackage review = new com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder(config)
                .build(context, List.of(
                        patch("plain_big", LandformType.PLAIN, -320, -320, 320, 320)));
        Path catalogPath = baseDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        return new Fixture(baseDir, context, review, source);
    }

    private static CityLandformReviewPackage preciseMemberCellReview() {
        return CityLandformReviewPackage.fromJson(JsonParser.parseString("""
                {
                  "schemaVersion": "city_landform_review.v0.1",
                  "cityId": "city_test",
                  "grid": {
                    "originBlockX": -32,
                    "originBlockZ": -32,
                    "cellStepBlocks": 16,
                    "cellsX": 4,
                    "cellsZ": 4
                  },
                  "targetScale": {
                    "scale": "town",
                    "radiusBlocks": 64,
                    "cellStepBlocks": 16
                  },
                  "reviewMapImage": "review.png",
                  "legend": [
                    {"color": "#2196F3", "label": "水域", "landformType": "water"}
                  ],
                  "landformPatches": [
                    {
                      "landformPatchId": "water_cells",
                      "mapLabel": "水域01",
                      "displayLandformName": "水域",
                      "landformType": "water",
                      "areaBlocks": 512,
                      "cellCount": 2,
                      "areaClass": "small",
                      "centerBlock": {"x": 8, "z": 8},
                      "blockBounds": {"minX": 0, "minZ": 0, "maxX": 31, "maxZ": 15},
                      "geometryMode": "patch_member_cells",
                      "memberCells": [
                        {"cellX": 0, "cellZ": 0, "blockMinX": 0, "blockMinZ": 0},
                        {"cellX": 1, "cellZ": 0, "blockMinX": 16, "blockMinZ": 0}
                      ],
                      "metricsSummary": {
                        "meanElevation": 63,
                        "minElevation": 62,
                        "maxElevation": 64,
                        "meanSlope": 0.1,
                        "meanWaterDistance": 0
                      },
                      "landformTags": [],
                      "overlayTags": [],
                      "summaryFacts": ["精细水体格子"],
                      "neighborLandformPatchIds": []
                    }
                  ],
                  "planningContext": [],
                  "aiPromptContext": "city test",
                  "debugRefs": []
                }
                """).getAsJsonObject());
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

    private static JsonObject arrayCandidatePlan(CityLandformReviewPackage review, int arrayCount) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_array_candidate_plan.v0.1",
                  "cityId": "city_test",
                  "arrayId": "residential_cluster",
                  "displayRole": "住宅阵列",
                  "candidatePatchRefs": ["%s"],
                  "structureIds": [
                    "minecraft:desert_pyramid",
                    "minecraft:jungle_pyramid",
                    "minecraft:village_plains"
                  ],
                  "arrayCount": %d,
                  "patterns": ["loose_cluster", "patch_axis_band", "scattered"]
                }
                """.formatted(first.landformPatchId(), arrayCount)).getAsJsonObject();
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
                      "structureId": "minecraft:jungle_pyramid",
                      "sourceProfileRef": "synthetic://unit-test/jungle_pyramid",
                      "profileType": "single",
                      "sampleType": "structure_assembly",
                      "placementKind": "minecraft_place_structure",
                      "placementCommand": "place structure minecraft:jungle_pyramid <x> <y> <z>",
                      "footprintMode": "fixed_footprint",
                      "semanticTerms": ["function.house", "style.debug", "placement.inside_zone", "usage.residential", "quality.debug_usable"],
                      "functionTerms": ["function.house"],
                      "styleTerms": ["style.debug"],
                      "placementTerms": ["placement.inside_zone"],
                      "usageTerms": ["usage.residential"],
                      "qualityTerms": ["quality.debug_usable"],
                      "fixedFootprint": {"widthBlocks": 16, "depthBlocks": 16, "heightBlocks": 10},
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

    private static JsonObject syntheticWallLedger() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}}
                  ]
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticWideWallLedger() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}},
                    {"anchorId": "b", "actualFootprint": {"minX": 108, "minZ": -4, "maxX": 132, "maxZ": 12}}
                  ]
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticV5Reservation() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_wall_reservation_plan.v0.5",
                  "cityId": "city_test",
                  "wallVersion": "v5",
                  "boundarySource": "d4_planned_footprint_envelope_rectilinear_hull",
                  "wallBounds": {"minX": -64, "minZ": -64, "maxX": 64, "maxZ": 64},
                  "wallCoverageBounds": {"minX": -96, "minZ": -96, "maxX": 96, "maxZ": 96},
                  "wallLine": [
                    {"lineId": "north", "segmentId": "north", "sideHint": "north",
                     "blockBounds": {"minX": -64, "minZ": -68, "maxX": 64, "maxZ": -60}},
                    {"lineId": "east", "segmentId": "east", "sideHint": "east",
                     "blockBounds": {"minX": 60, "minZ": -64, "maxX": 68, "maxZ": 64}}
                  ],
                  "wallCorridorMask": [
                    {"maskId": "wall_corridor_0", "maskType": "wall_reservation_corridor",
                     "blockBounds": {"minX": -64, "minZ": -68, "maxX": 64, "maxZ": -60}},
                    {"maskId": "wall_corridor_1", "maskType": "wall_reservation_corridor",
                     "blockBounds": {"minX": 60, "minZ": -64, "maxX": 68, "maxZ": 64}}
                  ],
                  "gateSlots": [
                    {"gateSlotId": "gate_0", "sideHint": "north",
                     "blockBounds": {"minX": -4, "minZ": -68, "maxX": 4, "maxZ": -60}}
                  ],
                  "wallNodeSlots": [
                    {"nodeSlotId": "node_0", "nodeType": "beacon_tower", "templateId": "beacon_5x5",
                     "blockBounds": {"minX": -34, "minZ": -66, "maxX": -30, "maxZ": -62}},
                    {"nodeSlotId": "node_1", "nodeType": "corner_tower", "templateId": "watchtower_5x5",
                     "blockBounds": {"minX": 62, "minZ": -66, "maxX": 66, "maxZ": -62}},
                    {"nodeSlotId": "node_2", "nodeType": "beacon_tower", "templateId": "beacon_5x5",
                     "blockBounds": {"minX": 62, "minZ": 30, "maxX": 66, "maxZ": 34}}
                  ]
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticV4Reservation() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_wall_reservation_plan.v0.3",
                  "cityId": "city_test",
                  "wallVersion": "v4",
                  "boundarySource": "actual_footprint_land_ring_deferred_to_d7",
                  "wallBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32},
                  "seedPatches": [
                    {"landformPatchId": "plain_0", "landformType": "plain",
                     "blockBounds": {"minX": -64, "minZ": -64, "maxX": 64, "maxZ": 64}}
                  ],
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32}}
                  ],
                  "wallCenterline": [],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticV4WaterReservation() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_wall_reservation_plan.v0.3",
                  "cityId": "city_test",
                  "wallVersion": "v4",
                  "boundarySource": "actual_footprint_land_ring_deferred_to_d7",
                  "wallBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32},
                  "seedPatches": [
                    {"landformPatchId": "lake_big", "landformType": "water",
                     "blockBounds": {"minX": -80, "minZ": -72, "maxX": 80, "maxZ": -40}},
                    {"landformPatchId": "plain_0", "landformType": "plain",
                     "blockBounds": {"minX": -80, "minZ": -32, "maxX": 80, "maxZ": 80}}
                  ],
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32}}
                  ],
                  "wallCenterline": [],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticV4PreciseWaterReservation() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_wall_reservation_plan.v0.3",
                  "cityId": "city_test",
                  "wallVersion": "v4",
                  "boundarySource": "actual_footprint_land_ring_deferred_to_d7",
                  "wallBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32},
                  "seedPatches": [
                    {
                      "landformPatchId": "lake_big",
                      "landformType": "water",
                      "geometryMode": "patch_member_cells",
                      "cellStepBlocks": 16,
                      "blockBounds": {"minX": -80, "minZ": -80, "maxX": 80, "maxZ": 80},
                      "memberCells": [
                        {"cellX": -2, "cellZ": -3, "blockMinX": -32, "blockMinZ": -48},
                        {"cellX": -1, "cellZ": -3, "blockMinX": -16, "blockMinZ": -48},
                        {"cellX": 0, "cellZ": -3, "blockMinX": 0, "blockMinZ": -48},
                        {"cellX": 1, "cellZ": -3, "blockMinX": 16, "blockMinZ": -48},
                        {"cellX": 2, "cellZ": -3, "blockMinX": 32, "blockMinZ": -48}
                      ]
                    },
                    {"landformPatchId": "plain_0", "landformType": "plain",
                     "blockBounds": {"minX": -80, "minZ": -32, "maxX": 80, "maxZ": 80}}
                  ],
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32}}
                  ],
                  "wallCenterline": [],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticV4TerrainContourReservation() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_wall_reservation_plan.v0.3",
                  "cityId": "city_test",
                  "wallVersion": "v4",
                  "boundarySource": "actual_footprint_land_ring_deferred_to_d7",
                  "wallBounds": {"minX": -64, "minZ": -64, "maxX": 64, "maxZ": 64},
                  "seedPatches": [
                    {"landformPatchId": "plain_0", "landformType": "plain",
                     "blockBounds": {"minX": -96, "minZ": -96, "maxX": 96, "maxZ": 96}}
                  ],
                  "cityDomainMask": [
                    {"maskId": "city_domain_cell_0", "maskType": "city_domain_cell", "blockBounds": {"minX": -32, "minZ": -32, "maxX": -17, "maxZ": -17}},
                    {"maskId": "city_domain_cell_1", "maskType": "city_domain_cell", "blockBounds": {"minX": -16, "minZ": -32, "maxX": -1, "maxZ": -17}},
                    {"maskId": "city_domain_cell_2", "maskType": "city_domain_cell", "blockBounds": {"minX": 0, "minZ": -32, "maxX": 15, "maxZ": -17}},
                    {"maskId": "city_domain_cell_3", "maskType": "city_domain_cell", "blockBounds": {"minX": 16, "minZ": -16, "maxX": 31, "maxZ": -1}},
                    {"maskId": "city_domain_cell_4", "maskType": "city_domain_cell", "blockBounds": {"minX": -32, "minZ": -16, "maxX": -17, "maxZ": -1}},
                    {"maskId": "city_domain_cell_5", "maskType": "city_domain_cell", "blockBounds": {"minX": -16, "minZ": -16, "maxX": -1, "maxZ": -1}},
                    {"maskId": "city_domain_cell_6", "maskType": "city_domain_cell", "blockBounds": {"minX": 0, "minZ": -16, "maxX": 15, "maxZ": -1}},
                    {"maskId": "city_domain_cell_7", "maskType": "city_domain_cell", "blockBounds": {"minX": 16, "minZ": 0, "maxX": 31, "maxZ": 15}},
                    {"maskId": "city_domain_cell_8", "maskType": "city_domain_cell", "blockBounds": {"minX": -32, "minZ": 0, "maxX": -17, "maxZ": 15}},
                    {"maskId": "city_domain_cell_9", "maskType": "city_domain_cell", "blockBounds": {"minX": -16, "minZ": 0, "maxX": -1, "maxZ": 15}},
                    {"maskId": "city_domain_cell_10", "maskType": "city_domain_cell", "blockBounds": {"minX": 0, "minZ": 0, "maxX": 15, "maxZ": 15}},
                    {"maskId": "city_domain_cell_11", "maskType": "city_domain_cell", "blockBounds": {"minX": 16, "minZ": 16, "maxX": 31, "maxZ": 31}}
                  ],
                  "wallCenterline": [],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticEastWallReservation() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_wall_reservation_plan.v0.3",
                  "cityId": "city_test",
                  "wallBounds": {"minX": -64, "minZ": -64, "maxX": 64, "maxZ": 64},
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32}}
                  ],
                  "wallCenterline": [
                    {"segmentId": "east", "blockBounds": {"minX": 60, "minZ": -64, "maxX": 64, "maxZ": 64}}
                  ],
                  "gateCandidateZones": [
                    {"blockBounds": {"minX": 60, "minZ": -4, "maxX": 64, "maxZ": 4}}
                  ]
                }
                """).getAsJsonObject();
    }

    private static JsonObject roadMaskFromBlocks(String cityId, int[][] blocks) {
        return roadMaskFromBlocks(cityId, blocks, "");
    }

    private static JsonObject roadMaskFromBlocks(String cityId, int[][] blocks, String blockId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_actual_road_mask.v0.2");
        obj.addProperty("cityId", cityId);
        obj.addProperty("status", blocks.length == 0 ? "empty" : "observed");
        JsonArray roadMask = new JsonArray();
        for (int i = 0; i < blocks.length; i++) {
            JsonObject mask = new JsonObject();
            mask.addProperty("maskId", "road_" + i);
            mask.addProperty("maskType", "actual_road");
            if (!blockId.isBlank()) {
                mask.addProperty("blockId", blockId);
            }
            mask.add("blockBounds", boundsJson(new BlockBounds(blocks[i][0], blocks[i][1], blocks[i][0], blocks[i][1])));
            roadMask.add(mask);
        }
        obj.add("roadMask", roadMask);
        return obj;
    }

    private static CityWallPlanner.V3Options v33Options() {
        return new CityWallPlanner.V3Options(
                24, 5, "v3.1", 7, 16, 6, 17, true,
                "v3.3", 48, 24, 4096, 32);
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(
                obj.get("minX").getAsInt(),
                obj.get("minZ").getAsInt(),
                obj.get("maxX").getAsInt(),
                obj.get("maxZ").getAsInt());
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static void assertGroupItemsDoNotOverlap(JsonObject group) {
        JsonArray items = group.getAsJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            BlockBounds a = bounds(items.get(i).getAsJsonObject()
                    .getAsJsonObject("estimatedCollisionEnvelope"));
            for (int j = i + 1; j < items.size(); j++) {
                BlockBounds b = bounds(items.get(j).getAsJsonObject()
                        .getAsJsonObject("estimatedCollisionEnvelope"));
                assertFalse(a.overlaps(b), "array items overlap: " + i + " / " + j);
            }
        }
    }

    private static void assertAnyArrayItemMaskOverlap(JsonObject group) {
        JsonArray items = group.getAsJsonArray("items");
        boolean found = false;
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            assertFalse(item.has("estimatedSafetyEnvelope"));
            BlockBounds a = bounds(item.getAsJsonObject("estimatedMaskEnvelope"));
            for (int j = i + 1; j < items.size(); j++) {
                JsonObject other = items.get(j).getAsJsonObject();
                assertFalse(other.has("estimatedSafetyEnvelope"));
                BlockBounds b = bounds(other.getAsJsonObject("estimatedMaskEnvelope"));
                found |= a.overlaps(b);
            }
        }
        assertTrue(found, "mask envelopes should be allowed to overlap when collision envelopes are clear");
    }

    private static void assertArrayCandidateShape(Fixture fixture,
                                                  String pattern,
                                                  String shape,
                                                  int rows,
                                                  int columns,
                                                  int targetCount) throws Exception {
        JsonObject plan = arrayCandidatePlan(fixture.review(), targetCount);
        plan.add("patterns", JsonParser.parseString("""
                ["%s"]
                """.formatted(pattern)).getAsJsonArray());
        plan.add("structureIds", JsonParser.parseString("""
                ["minecraft:desert_pyramid"]
                """).getAsJsonArray());
        plan.add("compoundCluster", JsonParser.parseString("""
                {"shape": "%s", "rows": %d, "columns": %d, "spacingBlocks": 64}
                """.formatted(shape, rows, columns)).getAsJsonObject());

        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        plan, CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonArray());

        assertTrue(result.asJson().get("ok").getAsBoolean(), pattern + "/" + shape);
        JsonArray groups = result.arrayCandidateSet().getAsJsonArray("arrayCandidates");
        assertFalse(groups.isEmpty());
        JsonObject group = groups.get(0).getAsJsonObject();
        assertEquals(pattern, group.get("arrayPattern").getAsString());
        assertEquals(shape, group.get("arrayShape").getAsString());
        assertEquals(64, group.get("spacingBlocks").getAsInt());
        assertEquals(targetCount, group.getAsJsonArray("items").size(), pattern + "/" + shape);
        assertGroupItemsDoNotOverlap(group);
        assertShapePoints(shape, group.getAsJsonArray("items"));
    }

    private static void assertShapePoints(String shape, JsonArray items) {
        List<Integer> xs = sortedUniqueCoordinates(items, "x");
        List<Integer> zs = sortedUniqueCoordinates(items, "z");
        switch (shape) {
            case "grid" -> {
                assertEquals(2, xs.size(), "grid should form two columns");
                assertEquals(2, zs.size(), "grid should form two rows");
                assertTrue(hasPoint(items, xs.get(0), zs.get(0)), "grid should contain northwest corner");
                assertTrue(hasPoint(items, xs.get(1), zs.get(0)), "grid should contain northeast corner");
                assertTrue(hasPoint(items, xs.get(0), zs.get(1)), "grid should contain southwest corner");
                assertTrue(hasPoint(items, xs.get(1), zs.get(1)), "grid should contain southeast corner");
            }
            case "courtyard" -> {
                assertEquals(3, xs.size(), "courtyard should keep three columns around the court");
                assertEquals(3, zs.size(), "courtyard should keep three rows around the court");
                int minX = xs.get(0);
                int maxX = xs.get(xs.size() - 1);
                int minZ = zs.get(0);
                int maxZ = zs.get(zs.size() - 1);
                assertEveryPoint(items, point ->
                        point.x() == minX || point.x() == maxX || point.z() == minZ || point.z() == maxZ);
                assertFalse(hasPoint(items, xs.get(1), zs.get(1)), "courtyard center must stay open");
            }
            case "l_shape" -> {
                assertEquals(3, xs.size(), "l_shape should expose three columns");
                assertEquals(3, zs.size(), "l_shape should expose three rows");
                int minX = xs.get(0);
                int maxZ = zs.get(zs.size() - 1);
                assertEveryPoint(items, point -> point.x() == minX || point.z() == maxZ);
            }
            case "u_shape" -> {
                assertEquals(3, xs.size(), "u_shape should expose three columns");
                assertEquals(3, zs.size(), "u_shape should expose three rows");
                int minX = xs.get(0);
                int maxX = xs.get(xs.size() - 1);
                int maxZ = zs.get(zs.size() - 1);
                assertEveryPoint(items, point ->
                        point.x() == minX || point.x() == maxX || point.z() == maxZ);
                assertFalse(hasPoint(items, xs.get(1), zs.get(0)), "u_shape should keep its open side empty");
                assertFalse(hasPoint(items, xs.get(1), zs.get(1)), "u_shape center must stay open");
            }
            case "organic_compact" -> {
                assertTrue(xs.size() >= 2, "organic_compact should not collapse to one column");
                assertTrue(zs.size() >= 2, "organic_compact should not collapse to one row");
                assertTrue(xs.get(xs.size() - 1) - xs.get(0) <= 256,
                        "organic_compact should remain locally compact");
                assertTrue(zs.get(zs.size() - 1) - zs.get(0) <= 256,
                        "organic_compact should remain locally compact");
            }
            default -> throw new AssertionError("unexpected shape " + shape);
        }
    }

    private static List<Integer> sortedUniqueCoordinates(JsonArray items, String axis) {
        List<Integer> values = new ArrayList<>();
        for (JsonElement elem : items) {
            int value = elem.getAsJsonObject().getAsJsonObject("anchorBlock").get(axis).getAsInt();
            if (!values.contains(value)) {
                values.add(value);
            }
        }
        Collections.sort(values);
        return values;
    }

    private static boolean hasPoint(JsonArray items, int x, int z) {
        for (JsonElement elem : items) {
            JsonObject point = elem.getAsJsonObject().getAsJsonObject("anchorBlock");
            if (point.get("x").getAsInt() == x && point.get("z").getAsInt() == z) {
                return true;
            }
        }
        return false;
    }

    private static void assertEveryPoint(JsonArray items, PointRule rule) {
        for (JsonElement elem : items) {
            JsonObject point = elem.getAsJsonObject().getAsJsonObject("anchorBlock");
            assertTrue(rule.accepts(new TestPoint(point.get("x").getAsInt(), point.get("z").getAsInt())),
                    "point should match expected shape outline: " + point);
        }
    }

    private static void assertClusterGroupItemsDoNotOverlap(JsonObject group) {
        JsonArray items = group.getAsJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            BlockBounds a = bounds(items.get(i).getAsJsonObject()
                    .getAsJsonObject("estimatedCollisionEnvelope"));
            for (int j = i + 1; j < items.size(); j++) {
                BlockBounds b = bounds(items.get(j).getAsJsonObject()
                        .getAsJsonObject("estimatedCollisionEnvelope"));
                assertFalse(a.overlaps(b), "cluster group items overlap: " + i + " / " + j);
            }
        }
    }

    private static boolean hasTemplateBlockAt(ListTag blocks, int x, int y, int z) {
        return templateStateAt(blocks, x, y, z) >= 0;
    }

    private static String wallNodeAxis(JsonObject wallPlan, String sourceNodeSlotId) {
        for (com.google.gson.JsonElement elem : wallPlan.getAsJsonArray("wallNodes")) {
            JsonObject node = elem.getAsJsonObject();
            if (sourceNodeSlotId.equals(node.get("sourceNodeSlotId").getAsString())) {
                return node.get("wallAxis").getAsString();
            }
        }
        return "";
    }

    private static int paletteState(CompoundTag template, String blockName) {
        ListTag palette = template.getList("palette", 10);
        for (int i = 0; i < palette.size(); i++) {
            if (blockName.equals(palette.getCompound(i).getString("Name"))) {
                return i;
            }
        }
        return -1;
    }

    private static int templateStateAt(ListTag blocks, int x, int y, int z) {
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag pos = block.getList("pos", 3);
            if (pos.getInt(0) == x && pos.getInt(1) == y && pos.getInt(2) == z) {
                return block.getInt("state");
            }
        }
        return -1;
    }

    private interface PointRule {
        boolean accepts(TestPoint point);
    }

    private record TestPoint(int x, int z) {
    }

    private record Fixture(Path baseDir, CitySiteContext context, CityLandformReviewPackage review,
                           JsonObject terraSenseSource) {
    }

    private static final class FakePlacementBackend implements CityStructureMaterializationPlanner.PlacementBackend {
        private final String signaturePrefix;
        private final boolean success;
        private final BlockBounds actualFootprintOverride;
        private int planCalls;
        private int placeCalls;

        private FakePlacementBackend(String signaturePrefix, boolean success) {
            this(signaturePrefix, success, null);
        }

        private FakePlacementBackend(String signaturePrefix, boolean success, BlockBounds actualFootprintOverride) {
            this.signaturePrefix = signaturePrefix;
            this.success = success;
            this.actualFootprintOverride = actualFootprintOverride;
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
                    actualFootprintOverride == null ? task.plannedFootprint() : actualFootprintOverride,
                    signaturePrefix + ":" + task.anchorId(), pieces);
        }
    }
}
