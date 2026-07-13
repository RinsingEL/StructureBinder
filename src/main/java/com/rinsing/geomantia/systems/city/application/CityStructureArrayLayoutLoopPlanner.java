package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.PatchMemberCell;
import com.rinsing.geomantia.systems.city.domain.model.PlanningGrid;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

public final class CityStructureArrayLayoutLoopPlanner {
    public static final String PLAN_SCHEMA = "city_d4_array_layout_plan.v0.2";
    public static final String PLAN_SCHEMA_V03 = "city_d4_array_layout_plan.v0.3";
    public static final String PLAN_SCHEMA_V04 = "city_d4_array_layout_plan.v0.4";
    public static final String STATE_SCHEMA = "city_d4_array_layout_loop_state.v0.2";
    public static final String STATE_SCHEMA_V03 = "city_d4_array_layout_loop_state.v0.3";
    public static final String STATE_SCHEMA_V04 = "city_d4_array_layout_loop_state.v0.4";
    public static final String TRACE_SCHEMA = "city_d4_array_layout_execution_trace.v0.2";
    public static final String TRACE_SCHEMA_V03 = "city_d4_array_layout_execution_trace.v0.3";
    public static final String TRACE_SCHEMA_V04 = "city_d4_array_layout_execution_trace.v0.4";
    public static final String OCCUPIED_SCHEMA = "city_d4_array_occupied_field.v0.2";
    public static final String PATCH_AVAILABILITY_SCHEMA = "city_d4_array_patch_availability.v0.2";
    public static final String ZONES_SCHEMA = "city_d4_functional_array_zones.v0.2";
    public static final String EXPANSION_SPACE_SCHEMA_V04 = "city_d4_array_expansion_space.v0.4";
    public static final String EXPANSION_CANDIDATE_SCHEMA_V04 = "city_d4_array_expansion_candidate_set.v0.4";
    public static final String PLANNING_MODE_V02 = "array_layout_loop_v0_2";
    public static final String PLANNING_MODE_V03 = "array_layout_loop_v0_3";
    public static final String PLANNING_MODE_V04 = "array_candidate_selection_loop_v0_4";

    private static final Set<String> PLANNER_TYPES = Set.of(
            "plaza_ring", "compound_cluster", "guide_line_dual_side", "riverbank_dual_side", "contour_band",
            "composite_array");
    private static final List<String> SECTORS = List.of(
            "center", "north", "south", "east", "west", "northeast", "northwest", "southeast", "southwest");
    private static final List<String> EXPANSION_DIRECTIONS = List.of(
            "north", "south", "east", "west", "northeast", "northwest", "southeast", "southwest");

    public CreateResult create(Path baseDirectory,
                               CityLandformReviewPackage reviewPackage,
                               JsonObject terraSenseProfileSource,
                               JsonObject arrayLayoutPlan,
                               CityStructureEnvelopeFacts envelopeFacts,
                               JsonObject baseStructureAnchorPlan,
                               JsonObject baseStructureAnchorMap) throws IOException {
        long started = System.nanoTime();
        if (reviewPackage == null) {
            throw new IllegalArgumentException("CityLandformReviewPackage is required for D4 array layout loop.");
        }
        if (arrayLayoutPlan == null) {
            throw new IllegalArgumentException("arrayLayoutPlan object is required.");
        }
        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(baseDirectory, terraSenseProfileSource);
        JsonObject normalizedPlan = normalizePlan(arrayLayoutPlan, reviewPackage.cityId());
        JsonObject normalizedBasePlan = normalizeBaseAnchorPlan(baseStructureAnchorPlan, reviewPackage.cityId());
        JsonObject normalizedBaseMap = baseStructureAnchorMap == null ? new JsonObject() : baseStructureAnchorMap.deepCopy();
        JsonArray occupied = occupiedFromAnchorMap(normalizedBaseMap, catalog.byId(), envelopeFacts);
        String planningMode = planningMode(normalizedPlan);
        JsonObject state = new JsonObject();
        state.addProperty("schemaVersion", stateSchema(planningMode));
        state.addProperty("planningMode", planningMode);
        state.addProperty("loopId", reviewPackage.cityId() + "/d4_array_layout");
        state.addProperty("stateId", stateId(0));
        state.addProperty("iteration", 0);
        state.addProperty("cityId", reviewPackage.cityId());
        state.addProperty("cityScale", stringValue(normalizedPlan, "cityScale", "town"));
        state.addProperty("maxArrayPlans", maxArrayPlans(normalizedPlan));
        state.addProperty("status", "ready_for_next_item");
        state.addProperty("generatedAt", Instant.now().toString());
        state.add("grid", reviewPackage.grid().asJson());
        state.add("sourceArrayLayoutPlan", normalizedPlan.deepCopy());
        JsonObject accumulated = normalizedPlan.deepCopy();
        accumulated.add("layoutPlans", new JsonArray());
        state.add("accumulatedArrayLayoutPlan", accumulated);
        state.add("sourceTerraSenseProfileSource", terraSenseProfileSource == null ? new JsonObject()
                : terraSenseProfileSource.deepCopy());
        state.add("structureProfileCatalog", catalog.asJson());
        state.add("baseStructureAnchorPlan", normalizedBasePlan);
        state.add("baseStructureAnchorMap", normalizedBaseMap);
        state.add("arrayAnchors", new JsonArray());
        state.add("executedArrayIds", new JsonArray());
        state.add("occupiedEnvelopes", occupied);
        state.add("executionTrace", executionTrace(new JsonArray(), planningMode));
        state.add("functionalArrayZones", zones(new JsonArray(), planningMode));
        state.add("patchAvailability", patchAvailability(reviewPackage, occupied));
        state.add("quality", quality(List.of(), catalog.warnings(), catalog.needsReview(), 100));
        state.add("timingMs", timing(started));
        return new CreateResult(state, state.getAsJsonObject("accumulatedArrayLayoutPlan"),
                state.getAsJsonObject("executionTrace"), state.getAsJsonArray("occupiedEnvelopes"),
                state.getAsJsonObject("patchAvailability"), state.getAsJsonObject("functionalArrayZones"),
                state.getAsJsonObject("quality"));
    }

    public ExecuteResult execute(Path baseDirectory,
                                 CityLandformReviewPackage reviewPackage,
                                 JsonObject terraSenseProfileSource,
                                 JsonObject currentState,
                                 JsonObject nextItem,
                                 CityStructureEnvelopeFacts envelopeFacts) throws IOException {
        long started = System.nanoTime();
        if (currentState == null || !validStateSchema(stringValue(currentState, "schemaVersion", ""))) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_LOOP_STATE_REQUIRED: current loop state is required.");
        }
        if (nextItem == null) {
            throw new IllegalArgumentException("nextArrayLayoutPlanItem object is required.");
        }
        if (PLANNING_MODE_V04.equals(planningMode(currentState))) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_V04_CANDIDATE_SELECTION_REQUIRED: "
                    + "generate candidates and select one complete candidate before committing state.");
        }
        if (nextItem.has("layoutPlans")) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_ONE_ITEM_PER_EXECUTE: execute accepts one item only.");
        }
        int iteration = intValue(currentState, "iteration", 0);
        int max = intValue(currentState, "maxArrayPlans", 7);
        if (iteration >= max) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_LOOP_MAX_ITERATIONS: maxArrayPlans reached.");
        }
        JsonObject state = currentState.deepCopy();
        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(baseDirectory, terraSenseProfileSource);
        Map<String, CityStructureProfileCatalog.StructureProfile> profiles = catalog.byId();
        CityStructureEnvelopeFacts facts = envelopeFacts == null ? CityStructureEnvelopeFacts.empty() : envelopeFacts;
        JsonArray hardBlocks = new JsonArray();
        JsonArray warnings = new JsonArray();
        catalog.warnings().forEach(warnings::add);
        List<LandformPatchSummary> sourcePatches = sourcePatches(nextItem, reviewPackage);
        String arrayId = requiredString(nextItem, "arrayId");
        if (containsString(array(state, "executedArrayIds"), arrayId)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_DUPLICATE_ARRAY_ID: " + arrayId);
        }
        String plannerType = stringValue(nextItem, "plannerType", "compound_cluster");
        if (!PLANNER_TYPES.contains(plannerType)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_PLANNER_TYPE_UNSUPPORTED: " + plannerType);
        }
        if (sourcePatches.isEmpty()) {
            hardBlocks.add("D4_ARRAY_LAYOUT_PATCH_UNAVAILABLE: " + arrayId);
        }
        List<DesiredItem> desiredItems = desiredItems(nextItem);
        if (desiredItems.isEmpty() && !"composite_array".equals(plannerType)) {
            hardBlocks.add(arrayId + ": requiredItems, featuredItems or fillPool must provide structures.");
        }
        for (DesiredItem item : desiredItems) {
            if (!profiles.containsKey(item.structureId())) {
                hardBlocks.add(arrayId + ": structureId is not in approved TerraSense catalog: " + item.structureId());
            }
        }

        JsonObject itemTrace;
        if (hardBlocks.isEmpty()) {
            BuildResult build = "composite_array".equals(plannerType)
                    ? buildCompositeArrayItem(nextItem, sourcePatches, reviewPackage, profiles, facts,
                    occupiedBounds(array(state, "occupiedEnvelopes")), null)
                    : buildArrayItem(nextItem, plannerType, sourcePatches, reviewPackage, profiles,
                    facts, occupiedBounds(array(state, "occupiedEnvelopes")), desiredItems, null);
            itemTrace = build.trace();
            appendAll(warnings, build.warnings());
            appendAll(hardBlocks, build.hardBlocks());
            if (hardBlocks.isEmpty()) {
                applyBuild(state, nextItem, build);
            }
        } else {
            itemTrace = traceForRejectedItem(nextItem, hardBlocks, warnings);
        }
        appendTrace(state, itemTrace);
        int nextIteration = iteration + (hardBlocks.isEmpty() ? 1 : 0);
        state.addProperty("iteration", nextIteration);
        state.addProperty("stateId", stateId(nextIteration));
        state.addProperty("status", hardBlocks.isEmpty() ? "ready_for_next_item" : "hard_blocked");
        state.add("patchAvailability", patchAvailability(reviewPackage, array(state, "occupiedEnvelopes")));
        state.add("quality", quality(toStrings(hardBlocks), toStrings(warnings), catalog.needsReview(),
                hardBlocks.isEmpty() ? 100 : 0));
        state.add("timingMs", timing(started));
        return new ExecuteResult(state, state.getAsJsonObject("accumulatedArrayLayoutPlan"),
                state.getAsJsonObject("executionTrace"), state.getAsJsonArray("occupiedEnvelopes"),
                state.getAsJsonObject("patchAvailability"), state.getAsJsonObject("functionalArrayZones"),
                state.getAsJsonObject("quality"));
    }

    /**
     * Reads the space around an already planned collision envelope. The result is deliberately
     * separate from loop state so inspecting possible growth never reserves a patch.
     */
    public ExpansionSpaceResult queryExpansionSpace(CityLandformReviewPackage reviewPackage,
                                                     JsonObject currentState,
                                                     JsonObject request) {
        requireV04State(currentState);
        ExpansionContext context = expansionContext(reviewPackage, currentState, request);
        return new ExpansionSpaceResult(expansionSpace(reviewPackage, currentState, context));
    }

    /**
     * Builds complete, mutually independent choices for one array theme. It must not mutate the
     * supplied state: only {@link #selectExpansionCandidate} can reserve occupied space.
     */
    public ExpansionCandidateSetResult planExpansionCandidates(Path baseDirectory,
                                                                CityLandformReviewPackage reviewPackage,
                                                                JsonObject terraSenseProfileSource,
                                                                JsonObject currentState,
                                                                JsonObject request,
                                                                CityStructureEnvelopeFacts envelopeFacts) throws IOException {
        long started = System.nanoTime();
        requireV04State(currentState);
        JsonObject submittedItem = object(request, "nextArrayLayoutPlanItem");
        if (submittedItem.size() == 0) {
            submittedItem = object(request, "arrayLayoutPlanItem");
        }
        if (submittedItem.size() == 0) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CANDIDATE_ITEM_REQUIRED: "
                    + "nextArrayLayoutPlanItem is required.");
        }
        if (submittedItem.has("layoutPlans")) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_ONE_ITEM_PER_EXECUTE: candidate planning accepts one item only.");
        }
        String submittedArrayId = requiredString(submittedItem, "arrayId");
        if (containsString(array(currentState, "executedArrayIds"), submittedArrayId)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_DUPLICATE_ARRAY_ID: " + submittedArrayId);
        }
        if (intValue(currentState, "iteration", 0) >= intValue(currentState, "maxArrayPlans", 7)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_LOOP_MAX_ITERATIONS: maxArrayPlans reached.");
        }
        ExpansionContext context = expansionContext(reviewPackage, currentState, request);
        if (context.continuousFrontier()) {
            return planContinuousExpansionCandidates(baseDirectory, reviewPackage, terraSenseProfileSource,
                    currentState, submittedItem, request, context, envelopeFacts, started);
        }
        if (context.targetPatch() == null) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_GLOBAL_PATCH_SELECTION_REQUIRED: "
                    + "query with newFunctionalArea=true, then provide selectedGlobalPatchRef when planning candidates.");
        }
        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(baseDirectory, terraSenseProfileSource);
        Map<String, CityStructureProfileCatalog.StructureProfile> profiles = catalog.byId();
        CityStructureEnvelopeFacts facts = envelopeFacts == null ? CityStructureEnvelopeFacts.empty() : envelopeFacts;
        int candidateCount = clamp(intValue(request, "candidateCount", 5), 3, 5);
        int minCandidateCount = clamp(intValue(request, "minCandidateCount", 3), 2, candidateCount);
        JsonArray candidates = new JsonArray();
        Set<String> signatures = new LinkedHashSet<>();
        List<String> sectors = expansionCandidateSectors(context.direction());
        int attemptLimit = Math.max(candidateCount * 4, 12);
        List<LandformPatchSummary> targetPatch = List.of(context.targetPatch());
        List<BlockBounds> occupied = occupiedBounds(array(currentState, "occupiedEnvelopes"));

        for (int attempt = 0; attempt < attemptLimit && candidates.size() < candidateCount; attempt++) {
            JsonObject item = submittedItem.deepCopy();
            String sector = sectors.get(attempt % sectors.size());
            BlockPoint variantOrigin = expansionVariantOrigin(context, sector);
            item.add("candidatePatchRefs", singleStringArray(context.targetPatch().landformPatchId()));
            item.addProperty("startSector", sector);
            item.addProperty("outwardDirection", context.direction());
            item.add("expansionOrigin", variantOrigin.asJson());
            item.add("expansionAvailableBounds", CityStructureCandidateEnvelope.boundsJson(context.availableBounds()));
            item.addProperty("candidateVariant", "v" + (attempt + 1));

            String plannerType = stringValue(item, "plannerType", "compound_cluster");
            if (!PLANNER_TYPES.contains(plannerType)) {
                throw new IllegalArgumentException("D4_ARRAY_LAYOUT_PLANNER_TYPE_UNSUPPORTED: " + plannerType);
            }
            BuildResult build;
            if ("composite_array".equals(plannerType)) {
                build = buildCompositeArrayItem(item, targetPatch, reviewPackage, profiles, facts, occupied,
                        context.availableBounds());
            } else {
                List<DesiredItem> desiredItems = desiredItems(item);
                if (desiredItems.isEmpty()) {
                    throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CANDIDATE_ITEM_REQUIRED: "
                            + "the array item must provide requiredItems, featuredItems or fillPool.");
                }
                for (DesiredItem desired : desiredItems) {
                    if (!profiles.containsKey(desired.structureId())) {
                        throw new IllegalArgumentException("D4_ARRAY_LAYOUT_STRUCTURE_PROFILE_UNAVAILABLE: "
                                + desired.structureId());
                    }
                }
                build = buildArrayItem(item, plannerType, targetPatch, reviewPackage, profiles, facts,
                        occupied, desiredItems, context.availableBounds());
            }
            if (!build.hardBlocks().isEmpty()) {
                continue;
            }
            String signature = candidateSignature(build.items());
            if (!signatures.add(signature)) {
                continue;
            }
            int ordinal = candidates.size() + 1;
            JsonObject candidate = new JsonObject();
            candidate.addProperty("candidateId", stringValue(item, "arrayId") + "_candidate_"
                    + String.format(Locale.ROOT, "%02d", ordinal));
            candidate.addProperty("candidateOrdinal", ordinal);
            candidate.addProperty("sourceStateId", stringValue(currentState, "stateId"));
            candidate.addProperty("plannerType", plannerType);
            candidate.addProperty("score", candidateScore(build));
            candidate.add("scoreBreakdown", candidateScoreBreakdown(build));
            candidate.add("focusRef", context.focusRef().deepCopy());
            candidate.addProperty("direction", context.direction());
            candidate.addProperty("targetPatchRef", context.targetPatch().landformPatchId());
            candidate.addProperty("newFunctionalArea", context.newFunctionalArea());
            if (context.newFunctionalArea()) {
                candidate.addProperty("selectedGlobalPatchRef", context.targetPatch().landformPatchId());
            }
            candidate.add("expansionEntryPoint", variantOrigin.asJson());
            candidate.add("expansionAvailableBounds", CityStructureCandidateEnvelope.boundsJson(context.availableBounds()));
            candidate.add("candidateArrayLayoutPlanItem", item.deepCopy());
            candidate.add("items", build.items().deepCopy());
            candidate.add("anchors", build.anchors().deepCopy());
            candidate.add("arrayZones", build.zones().deepCopy());
            candidate.add("executionTrace", build.trace().deepCopy());
            candidates.add(candidate);
        }
        if (candidates.size() < minCandidateCount) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CANDIDATES_UNSATISFIED: generated "
                    + candidates.size() + " complete candidates, requires at least " + minCandidateCount + ".");
        }
        JsonObject set = new JsonObject();
        set.addProperty("schemaVersion", EXPANSION_CANDIDATE_SCHEMA_V04);
        set.addProperty("planningMode", PLANNING_MODE_V04);
        set.addProperty("cityId", reviewPackage.cityId());
        set.addProperty("sourceStateId", stringValue(currentState, "stateId"));
        set.addProperty("generatedAt", Instant.now().toString());
        set.add("grid", reviewPackage.grid().asJson());
        set.add("expansionSpace", expansionSpace(reviewPackage, currentState, context));
        set.add("arrayCandidates", candidates);
        set.add("qualityReport", quality(List.of(), catalog.warnings(), catalog.needsReview(), 100));
        set.add("timingMs", timing(started));
        return new ExpansionCandidateSetResult(set, set.getAsJsonObject("qualityReport"));
    }

    /**
     * Continuous outward growth deliberately starts from the resolved parent body envelope. D3
     * patches are only used to admit / score member cells after the physical frontier is known;
     * they are not a discontinuous placement target.
     */
    private ExpansionCandidateSetResult planContinuousExpansionCandidates(Path baseDirectory,
                                                                            CityLandformReviewPackage reviewPackage,
                                                                            JsonObject terraSenseProfileSource,
                                                                            JsonObject currentState,
                                                                            JsonObject submittedItem,
                                                                            JsonObject request,
                                                                            ExpansionContext context,
                                                                            CityStructureEnvelopeFacts envelopeFacts,
                                                                            long started) throws IOException {
        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(baseDirectory, terraSenseProfileSource);
        Map<String, CityStructureProfileCatalog.StructureProfile> profiles = catalog.byId();
        CityStructureEnvelopeFacts facts = envelopeFacts == null ? CityStructureEnvelopeFacts.empty() : envelopeFacts;
        int candidateCount = clamp(intValue(request, "candidateCount", 5), 3, 5);
        int minCandidateCount = clamp(intValue(request, "minCandidateCount", 3), 2, candidateCount);
        String plannerType = stringValue(submittedItem, "plannerType", "compound_cluster");
        if (!PLANNER_TYPES.contains(plannerType)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_PLANNER_TYPE_UNSUPPORTED: " + plannerType);
        }
        List<DesiredItem> desired = "composite_array".equals(plannerType)
                ? List.of() : desiredItems(submittedItem);
        if (!"composite_array".equals(plannerType) && desired.isEmpty()) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CANDIDATE_ITEM_REQUIRED: "
                    + "the array item must provide requiredItems, featuredItems or fillPool.");
        }
        for (DesiredItem item : desired) {
            if (!profiles.containsKey(item.structureId())) {
                throw new IllegalArgumentException("D4_ARRAY_LAYOUT_STRUCTURE_PROFILE_UNAVAILABLE: " + item.structureId());
            }
        }

        FrontierReference reference = frontierReference(submittedItem, plannerType, desired, profiles, facts);
        int spacing = reference.spacingBlocks();
        TerrainPlacementPolicy terrainPolicy = terrainPlacementPolicy(submittedItem, plannerType, desired, profiles);
        JsonArray candidates = new JsonArray();
        JsonArray frontierTrace = new JsonArray();
        Set<String> signatures = new LinkedHashSet<>();
        List<BlockBounds> occupied = occupiedBounds(array(currentState, "occupiedEnvelopes"));
        ExpansionPolicy policy = context.expansionPolicy();

        for (int ring = 0; ring < policy.maxExpansionRounds() && candidates.isEmpty(); ring++) {
            int gapMin = policy.actualBodyGapMin() + ring * policy.frontierExpansionStepBlocks();
            int gapMax = policy.actualBodyGapMax() + ring * policy.frontierExpansionStepBlocks();
            BlockPoint firstAnchor = continuousFrontierAnchor(context.focusBodyBounds(), context.direction(),
                    reference.localFootprint(), gapMin, gapMax, spacing, reviewPackage.grid());
            BlockPoint firstGuideOffset = continuousFirstGuideOffset(submittedItem, plannerType,
                    Math.max(1, desired.size()), spacing);
            BlockPoint origin = clampToGrid(new BlockPoint(firstAnchor.x() - firstGuideOffset.x(),
                    firstAnchor.z() - firstGuideOffset.z()), reviewPackage.grid());
            BlockBounds frontierBounds = continuousFrontierBounds(context.focusBodyBounds(), context.direction(),
                    origin, spacing, Math.max(2, desired.size()), reviewPackage.grid());
            TerrainPatchSelection terrain = terrainPatchesForFrontier(reviewPackage, frontierBounds, terrainPolicy);
            List<LandformPatchSummary> sourcePatches = terrain.acceptedPatches();
            JsonObject ringTrace = new JsonObject();
            ringTrace.addProperty("frontierRing", frontierRingName(ring));
            ringTrace.addProperty("frontierRingIndex", ring);
            ringTrace.addProperty("actualBodyGapMin", gapMin);
            ringTrace.addProperty("actualBodyGapMax", gapMax);
            ringTrace.add("frontierBounds", CityStructureCandidateEnvelope.boundsJson(frontierBounds));
            ringTrace.add("terrainPatchRefs", patchRefs(sourcePatches));
            ringTrace.add("excludedTerrainPatches", terrain.excludedPatches());
            if (sourcePatches.isEmpty()) {
                ringTrace.addProperty("result", "skipped");
                ringTrace.addProperty("reasonCode", terrain.hasGroundedWaterRejection()
                        ? "D4_ARRAY_LAYOUT_FRONTIER_GROUNDED_TERRAIN_UNAVAILABLE"
                        : "D4_ARRAY_LAYOUT_FRONTIER_NO_TERRAIN_PATCH");
                frontierTrace.add(ringTrace);
                continue;
            }

            int produced = 0;
            int rejectedForGap = 0;
            int rejectedForBuild = 0;
            JsonArray observedBodyGaps = new JsonArray();
            int attemptLimit = Math.max(candidateCount * 8, 24);
            for (int attempt = 0; attempt < attemptLimit && candidates.size() < candidateCount; attempt++) {
                JsonObject item = submittedItem.deepCopy();
                String sector = expansionCandidateSectors(context.direction())
                        .get(attempt % expansionCandidateSectors(context.direction()).size());
                BlockPoint variantOrigin = continuousVariantOrigin(frontierBounds, origin, context.direction(),
                        attempt, spacing);
                item.add("candidatePatchRefs", patchRefs(sourcePatches));
                item.addProperty("startSector", sector);
                item.addProperty("outwardDirection", context.direction());
                item.addProperty("continuousFrontier", true);
                item.add("expansionOrigin", variantOrigin.asJson());
                item.add("expansionAvailableBounds", CityStructureCandidateEnvelope.boundsJson(frontierBounds));
                item.addProperty("candidateVariant", "frontier_" + frontierRingName(ring) + "_" + (attempt + 1));

                BuildResult build = "composite_array".equals(plannerType)
                        ? buildCompositeArrayItem(item, sourcePatches, reviewPackage, profiles, facts, occupied,
                        frontierBounds)
                        : buildArrayItem(item, plannerType, sourcePatches, reviewPackage, profiles, facts,
                        occupied, desired, frontierBounds);
                if (!build.hardBlocks().isEmpty()) {
                    rejectedForBuild++;
                    continue;
                }
                FrontierGapValidation gap = validateFrontierBodyGap(context.focusBodyBounds(), context.direction(),
                        build.items(), gapMin, gapMax);
                if (!gap.accepted()) {
                    rejectedForGap++;
                    if (observedBodyGaps.size() < 8) {
                        observedBodyGaps.add(gap.actualBodyGapBlocks());
                    }
                    continue;
                }
                String signature = candidateSignature(build.items());
                if (!signatures.add(signature)) {
                    continue;
                }
                int ordinal = candidates.size() + 1;
                JsonObject candidate = new JsonObject();
                candidate.addProperty("candidateId", stringValue(item, "arrayId") + "_candidate_"
                        + String.format(Locale.ROOT, "%02d", ordinal));
                candidate.addProperty("candidateOrdinal", ordinal);
                candidate.addProperty("sourceStateId", stringValue(currentState, "stateId"));
                candidate.addProperty("plannerType", plannerType);
                candidate.addProperty("score", candidateScore(build));
                candidate.add("scoreBreakdown", candidateScoreBreakdown(build));
                candidate.addProperty("expansionMode", "continuous_focus_frontier");
                candidate.add("focusRef", context.focusRef().deepCopy());
                candidate.addProperty("direction", context.direction());
                candidate.add("parentCollisionEnvelope",
                        CityStructureCandidateEnvelope.boundsJson(context.focusBounds()));
                candidate.add("parentBodyEnvelope", CityStructureCandidateEnvelope.boundsJson(context.focusBodyBounds()));
                candidate.add("expansionPolicy", policy.asJson());
                candidate.addProperty("frontierRing", frontierRingName(ring));
                candidate.addProperty("frontierLevel", frontierRingName(ring));
                candidate.addProperty("frontierRingIndex", ring);
                candidate.addProperty("actualBodyGapBlocks", gap.actualBodyGapBlocks());
                candidate.addProperty("bodyGapBlocks", gap.actualBodyGapBlocks());
                candidate.add("terrainPatchRefs", patchRefs(sourcePatches));
                candidate.add("memberTerrainPatchRefs", terrainPatchRefs(build.items()));
                candidate.add("expansionEntryPoint", variantOrigin.asJson());
                candidate.add("expansionAvailableBounds", CityStructureCandidateEnvelope.boundsJson(frontierBounds));
                candidate.add("candidateArrayLayoutPlanItem", item.deepCopy());
                candidate.add("items", build.items().deepCopy());
                candidate.add("anchors", build.anchors().deepCopy());
                candidate.add("arrayZones", build.zones().deepCopy());
                candidate.add("executionTrace", build.trace().deepCopy());
                JsonObject candidateTrace = ringTrace.deepCopy();
                candidateTrace.addProperty("result", "accepted");
                candidateTrace.addProperty("actualBodyGapBlocks", gap.actualBodyGapBlocks());
                candidate.add("frontierTrace", candidateTrace);
                candidates.add(candidate);
                produced++;
            }
            ringTrace.addProperty("candidateCount", produced);
            ringTrace.addProperty("rejectedForBodyGap", rejectedForGap);
            ringTrace.addProperty("rejectedForBuild", rejectedForBuild);
            ringTrace.add("observedBodyGaps", observedBodyGaps);
            if (produced == 0) {
                ringTrace.addProperty("result", "skipped");
                ringTrace.addProperty("reasonCode", rejectedForGap > 0
                        ? "D4_ARRAY_LAYOUT_FRONTIER_BODY_GAP_UNSATISFIED"
                        : "D4_ARRAY_LAYOUT_FRONTIER_COMPLETE_CLUSTER_UNAVAILABLE");
            } else {
                ringTrace.addProperty("result", "accepted");
            }
            frontierTrace.add(ringTrace);
        }
        if (candidates.size() < minCandidateCount) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CONTINUOUS_FRONTIER_UNSATISFIED: generated "
                    + candidates.size() + " complete candidates; frontier trace=" + frontierTrace + ".");
        }
        JsonObject set = new JsonObject();
        set.addProperty("schemaVersion", EXPANSION_CANDIDATE_SCHEMA_V04);
        set.addProperty("planningMode", PLANNING_MODE_V04);
        set.addProperty("cityId", reviewPackage.cityId());
        set.addProperty("sourceStateId", stringValue(currentState, "stateId"));
        set.addProperty("generatedAt", Instant.now().toString());
        set.addProperty("expansionMode", "continuous_focus_frontier");
        set.add("focusCollisionEnvelope", CityStructureCandidateEnvelope.boundsJson(context.focusBounds()));
        set.add("focusBodyEnvelope", CityStructureCandidateEnvelope.boundsJson(context.focusBodyBounds()));
        set.add("expansionPolicy", context.expansionPolicy().asJson());
        set.add("grid", reviewPackage.grid().asJson());
        set.add("expansionSpace", expansionSpace(reviewPackage, currentState, context));
        set.add("frontierSearchTrace", frontierTrace);
        set.add("arrayCandidates", candidates);
        set.add("qualityReport", quality(List.of(), catalog.warnings(), catalog.needsReview(), 100));
        set.add("timingMs", timing(started));
        return new ExpansionCandidateSetResult(set, set.getAsJsonObject("qualityReport"));
    }

    /**
     * Applies one previously generated complete candidate to a matching v0.4 state. The caller
     * writes the returned snapshot once, which keeps anchors, occupied envelopes and zones atomic.
     */
    public ExpansionSelectionResult selectExpansionCandidate(CityLandformReviewPackage reviewPackage,
                                                              JsonObject currentState,
                                                              JsonObject candidateSet,
                                                              String candidateId,
                                                              boolean autoSelectHighestScore,
                                                              String selectionReason) {
        long started = System.nanoTime();
        requireV04State(currentState);
        if (intValue(currentState, "iteration", 0) >= intValue(currentState, "maxArrayPlans", 7)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_LOOP_MAX_ITERATIONS: maxArrayPlans reached.");
        }
        if (!EXPANSION_CANDIDATE_SCHEMA_V04.equals(stringValue(candidateSet, "schemaVersion"))) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CANDIDATE_SET_REQUIRED: v0.4 candidate set is required.");
        }
        String stateId = stringValue(currentState, "stateId");
        if (!stateId.equals(stringValue(candidateSet, "sourceStateId"))) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CANDIDATE_SET_STALE: candidate set targets "
                    + stringValue(candidateSet, "sourceStateId") + " but current state is " + stateId + ".");
        }
        JsonObject selected = null;
        if (candidateId != null && !candidateId.isBlank()) {
            for (JsonElement elem : array(candidateSet, "arrayCandidates")) {
                if (elem.isJsonObject() && candidateId.equals(stringValue(elem.getAsJsonObject(), "candidateId"))) {
                    selected = elem.getAsJsonObject();
                    break;
                }
            }
            if (selected == null) {
                throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CANDIDATE_NOT_FOUND: " + candidateId);
            }
        } else if (autoSelectHighestScore) {
            for (JsonElement elem : array(candidateSet, "arrayCandidates")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject candidate = elem.getAsJsonObject();
                if (selected == null || doubleValue(candidate, "score", 0.0) > doubleValue(selected, "score", 0.0)) {
                    selected = candidate;
                }
            }
            if (selected == null) {
                throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CANDIDATE_NOT_FOUND: candidate set is empty.");
            }
        } else {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CANDIDATE_SELECTION_REQUIRED: candidateId is required; "
                    + "automatic highest-score selection is disabled by default.");
        }
        if (!stateId.equals(stringValue(selected, "sourceStateId"))) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CANDIDATE_SET_STALE: selected candidate was built for another state.");
        }
        JsonObject state = currentState.deepCopy();
        BuildResult build = new BuildResult(array(selected, "items").deepCopy(), array(selected, "anchors").deepCopy(),
                array(selected, "arrayZones").deepCopy(), object(selected, "executionTrace").deepCopy(),
                new JsonArray(), new JsonArray());
        JsonObject selectedItem = object(selected, "candidateArrayLayoutPlanItem");
        applyBuild(state, selectedItem, build);
        int nextIteration = intValue(state, "iteration", 0) + 1;
        state.addProperty("iteration", nextIteration);
        state.addProperty("stateId", stateId(nextIteration));
        state.addProperty("status", "ready_for_next_item");
        JsonObject trace = build.trace().deepCopy();
        trace.addProperty("candidateId", stringValue(selected, "candidateId"));
        trace.addProperty("decisionSource", autoSelectHighestScore && (candidateId == null || candidateId.isBlank())
                ? "auto_highest_score_explicit" : "ai_or_human_selected");
        if (selectionReason != null && !selectionReason.isBlank()) {
            trace.addProperty("selectionReason", selectionReason);
        }
        appendTrace(state, trace);
        ExpansionContext context = expansionContext(reviewPackage, state, selected);
        state.add("remainingExpansionSpace", expansionSpace(reviewPackage, state, context));
        state.add("patchAvailability", patchAvailability(reviewPackage, array(state, "occupiedEnvelopes")));
        state.add("quality", quality(List.of(), List.of(), List.of(), 100));
        state.add("timingMs", timing(started));
        return new ExpansionSelectionResult(state, selected.deepCopy(), state.getAsJsonObject("quality"));
    }

    public FinalizeResult finalizeLoop(JsonObject state) {
        if (state == null || !validStateSchema(stringValue(state, "schemaVersion", ""))) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_LOOP_STATE_REQUIRED: current loop state is required.");
        }
        JsonObject base = object(state, "baseStructureAnchorPlan");
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", CityStructureAnchorPlanner.PLAN_SCHEMA);
        plan.addProperty("cityId", stringValue(state, "cityId", stringValue(base, "cityId", "")));
        JsonArray anchors = new JsonArray();
        LinkedHashSet<String> anchorIds = new LinkedHashSet<>();
        appendAnchors(anchors, anchorIds, base, "anchors", "baseStructureAnchorPlan");
        appendAnchors(anchors, anchorIds, state, "arrayAnchors", "arrayLayoutLoopState");
        plan.add("anchors", anchors);
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", traceSchema(state));
        trace.addProperty("planningMode", planningMode(state));
        trace.addProperty("stateId", stringValue(state, "stateId"));
        trace.addProperty("iteration", intValue(state, "iteration", 0));
        trace.add("executionTrace", object(state, "executionTrace").deepCopy());
        trace.add("functionalArrayZones", object(state, "functionalArrayZones").deepCopy());
        plan.add("arrayLayoutLoopTrace", trace);
        JsonObject quality = quality(List.of(), List.of(), List.of(), 100);
        return new FinalizeResult(plan, quality);
    }

    private BuildResult buildArrayItem(JsonObject item,
                                       String plannerType,
                                       List<LandformPatchSummary> sourcePatches,
                                       CityLandformReviewPackage reviewPackage,
                                       Map<String, CityStructureProfileCatalog.StructureProfile> profiles,
                                       CityStructureEnvelopeFacts facts,
                                       List<BlockBounds> occupied,
                                       List<DesiredItem> desiredItems,
                                       BlockBounds placementBounds) {
        String arrayId = requiredString(item, "arrayId");
        JsonArray hardBlocks = new JsonArray();
        JsonArray warnings = new JsonArray();
        LandformPatchSummary pivot = sourcePatches.get(0);
        int spacing = spacing(item, desiredItems, profiles, facts);
        BlockBounds effectiveBounds = placementBounds == null ? pivot.blockBounds() : placementBounds;
        BlockPoint sectorStart = sectorPoint(effectiveBounds, stringValue(item, "startSector", "center"));
        BlockPoint requestedOrigin = point(item, "expansionOrigin", sectorStart);
        BlockPoint start = placementBounds == null ? sectorStart : new BlockPoint(
                clamp(requestedOrigin.x(), effectiveBounds.minX(), effectiveBounds.maxX()),
                clamp(requestedOrigin.z(), effectiveBounds.minZ(), effectiveBounds.maxZ()));
        List<BlockPoint> guidePoints = placementBounds == null
                ? rawPoints(plannerType, item, pivot, sourcePatches, reviewPackage.grid(),
                start, spacing, Math.max(1, desiredItems.size()))
                : rawPointsInBounds(plannerType, item, effectiveBounds, start, spacing,
                Math.max(1, desiredItems.size()));
        List<BlockPoint> rawPoints = booleanValue(item, "continuousFrontier", false)
                ? continuousMemberCellCandidatePoints(sourcePatches, reviewPackage.grid(), effectiveBounds,
                start, guidePoints)
                : memberCellCandidatePoints(sourcePatches, reviewPackage.grid(), effectiveBounds, start, guidePoints);
        if (rawPoints.isEmpty()) {
            hardBlocks.add("D4_ARRAY_LAYOUT_NO_CAPACITY: " + arrayId
                    + " has no member-cell candidate points in selected patches.");
        }
        if ("riverbank_dual_side".equals(plannerType) && sourcePatches.stream().noneMatch(this::isShoreLike)) {
            warnings.add("D4_ARRAY_LAYOUT_RIVERBANK_CONTEXT_MISSING: " + arrayId
                    + " fell back to patch long axis.");
        }
        if ("contour_band".equals(plannerType)) {
            double elevationRange = pivot.metricsSummary().maxElevation() - pivot.metricsSummary().minElevation();
            if (pivot.metricsSummary().meanSlope() > 0.18 || elevationRange > 18.0) {
                warnings.add("D4_ARRAY_LAYOUT_CONTOUR_BAND_SLOPE_RISK: " + arrayId
                        + " uses coarse patch metrics only.");
            }
        }
        JsonArray placedItems = new JsonArray();
        JsonArray anchors = new JsonArray();
        JsonArray rejected = new JsonArray();
        List<BlockBounds> localOccupied = new ArrayList<>(occupied);
        List<BlockBounds> groupCollision = new ArrayList<>();
        BlockBounds groupCollisionUnion = null;
        BlockBounds groupMaskUnion = null;
        int pointCursor = 0;

        for (int i = 0; i < desiredItems.size(); i++) {
            DesiredItem desired = desiredItems.get(i);
            CityStructureProfileCatalog.StructureProfile profile = profiles.get(desired.structureId());
            JsonObject options = item.deepCopy();
            options.addProperty("rotation", "NONE");
            options.addProperty("compactArraySubmission", true);
            Accepted accepted = null;
            while (pointCursor < rawPoints.size()) {
                BlockPoint point = rawPoints.get(pointCursor++);
                if (placementBounds != null && !placementBounds.contains(point.x(), point.z())) {
                    rejected.add(rejection(desired, point, "POINT_OUTSIDE_SUBZONE"));
                    continue;
                }
                LandformPatchSummary patch = pointPatch(sourcePatches, reviewPackage.grid(), point);
                if (patch == null || !reviewPackage.grid().containsBlock(point.x(), point.z())) {
                    rejected.add(rejection(desired, point, "POINT_OUTSIDE_PATCH"));
                    continue;
                }
                CityStructureCandidateEnvelope.Estimate estimate =
                        CityStructureCandidateEnvelope.estimate(point, profile, facts, options);
                if (estimate.requiredFactsMissing()) {
                    hardBlocks.add("D4_ARRAY_LAYOUT_STRUCTURE_ENVELOPE_FACTS_REQUIRED: " + desired.structureId());
                    break;
                }
                if (!estimate.hardBlockReason().isBlank()) {
                    rejected.add(rejection(desired, point, estimate.hardBlockReason()));
                    continue;
                }
                if (!gridContains(reviewPackage.grid(), estimate.collisionEnvelope())) {
                    rejected.add(rejection(desired, point, "COLLISION_ENVELOPE_OUTSIDE_CITY_GRID"));
                    continue;
                }
                BlockBounds occupancyEnvelope = estimate.collisionEnvelope();
                if (overlapsAny(localOccupied, occupancyEnvelope) || overlapsAny(groupCollision, occupancyEnvelope)) {
                    rejected.add(rejection(desired, point, "D4_ARRAY_LAYOUT_OCCUPIED_CONFLICT"));
                    continue;
                }
                accepted = new Accepted(desired, point, patch, estimate);
                break;
            }
            if (accepted == null) {
                if ("required".equals(desired.kind())) {
                    hardBlocks.add("D4_ARRAY_LAYOUT_REQUIRED_ITEM_UNPLACED: " + desired.itemId());
                } else if ("featured".equals(desired.kind())) {
                    warnings.add("D4_ARRAY_LAYOUT_FEATURED_ITEM_SKIPPED: " + desired.itemId());
                }
                continue;
            }
            CityStructureCandidateEnvelope.Estimate estimate = accepted.estimate();
            JsonObject itemJson = itemJson(item, plannerType, spacing, placedItems.size() + 1, accepted);
            JsonObject anchorJson = anchorJson(item, plannerType, placedItems.size() + 1, accepted);
            placedItems.add(itemJson);
            anchors.add(anchorJson);
            localOccupied.add(estimate.collisionEnvelope());
            groupCollision.add(estimate.collisionEnvelope());
            groupCollisionUnion = union(groupCollisionUnion, estimate.collisionEnvelope());
            groupMaskUnion = union(groupMaskUnion, estimate.maskEnvelope());
        }
        int minCount = minCount(item, desiredItems.size());
        int targetShortfallCount = Math.max(0, desiredItems.size() - placedItems.size());
        if (placedItems.size() < minCount) {
            hardBlocks.add("D4_ARRAY_LAYOUT_MIN_COUNT_UNSATISFIED: " + arrayId
                    + " placed " + placedItems.size() + " of minCount " + minCount + ".");
        }
        JsonObject zone = zone(item, plannerType, spacing, placedItems, groupCollisionUnion, groupMaskUnion,
                roadAccessPoints(item, placedItems));
        JsonObject trace = new JsonObject();
        trace.addProperty("arrayId", arrayId);
        trace.addProperty("plannerType", plannerType);
        trace.addProperty("arrayShape", arrayShape(item, plannerType));
        trace.addProperty("spacingBlocks", spacing);
        trace.addProperty("status", hardBlocks.isEmpty() ? "accepted" : "hard_blocked");
        trace.addProperty("placedItemCount", placedItems.size());
        trace.addProperty("requestedItemCount", desiredItems.size());
        trace.addProperty("minCount", minCount);
        trace.addProperty("targetShortfallCount", targetShortfallCount);
        trace.add("hardBlocks", hardBlocks.deepCopy());
        trace.add("warnings", warnings.deepCopy());
        trace.add("rejectedPoints", rejected);
        JsonArray zones = new JsonArray();
        zones.add(zone);
        return new BuildResult(placedItems, anchors, zones, trace, hardBlocks, warnings);
    }

    private BuildResult buildCompositeArrayItem(JsonObject item,
                                                List<LandformPatchSummary> sourcePatches,
                                                CityLandformReviewPackage reviewPackage,
                                                Map<String, CityStructureProfileCatalog.StructureProfile> profiles,
                                                CityStructureEnvelopeFacts facts,
                                                List<BlockBounds> occupied,
                                                BlockBounds placementBounds) {
        String arrayId = requiredString(item, "arrayId");
        JsonArray hardBlocks = new JsonArray();
        JsonArray warnings = new JsonArray();
        JsonArray childPlans = array(item, "childLayoutPlans");
        if (childPlans.isEmpty()) {
            hardBlocks.add("D4_ARRAY_LAYOUT_COMPOSITE_CHILD_PLANS_REQUIRED: " + arrayId);
        }
        if (sourcePatches.isEmpty()) {
            hardBlocks.add("D4_ARRAY_LAYOUT_PATCH_UNAVAILABLE: " + arrayId);
        }
        if (!hardBlocks.isEmpty()) {
            JsonObject trace = traceForRejectedItem(item, hardBlocks, warnings);
            trace.addProperty("zoneKind", "parent_composite");
            return new BuildResult(new JsonArray(), new JsonArray(), new JsonArray(), trace, hardBlocks, warnings);
        }

        LandformPatchSummary pivot = sourcePatches.get(0);
        BlockBounds parentBounds = placementBounds == null ? pivot.blockBounds() : placementBounds;
        List<SubZone> subZones = subZones(item, parentBounds,
                Math.max(intValue(item, "subZoneCount", 0), childPlans.size()));
        Map<String, SubZone> subZonesById = new LinkedHashMap<>();
        for (SubZone subZone : subZones) {
            subZonesById.put(subZone.subZoneId(), subZone);
        }

        JsonArray placedItems = new JsonArray();
        JsonArray anchors = new JsonArray();
        JsonArray zones = new JsonArray();
        JsonArray childTraces = new JsonArray();
        JsonArray childZoneIds = new JsonArray();
        List<BlockBounds> localOccupied = new ArrayList<>(occupied);
        BlockBounds groupCollisionUnion = null;
        BlockBounds groupMaskUnion = null;

        int childIndex = 0;
        for (JsonElement elem : childPlans) {
            if (!elem.isJsonObject()) {
                continue;
            }
            childIndex++;
            JsonObject child = elem.getAsJsonObject().deepCopy();
            if (child.has("layoutPlans")) {
                hardBlocks.add("D4_ARRAY_LAYOUT_ONE_ITEM_PER_EXECUTE: composite child cannot carry layoutPlans.");
                break;
            }
            String childPlannerType = stringValue(child, "plannerType", "compound_cluster");
            if ("composite_array".equals(childPlannerType) || !PLANNER_TYPES.contains(childPlannerType)) {
                hardBlocks.add("D4_ARRAY_LAYOUT_COMPOSITE_CHILD_PLANNER_UNSUPPORTED: " + childPlannerType);
                break;
            }
            if (stringValue(child, "arrayId").isBlank()) {
                child.addProperty("arrayId", arrayId + "_child_" + String.format(Locale.ROOT, "%02d", childIndex));
            }
            if (!child.has("candidatePatchRefs")) {
                child.add("candidatePatchRefs", array(item, "candidatePatchRefs").deepCopy());
            }
            if (stringValue(child, "startSector").isBlank()) {
                child.addProperty("startSector", stringValue(item, "startSector", "center"));
            }
            if (stringValue(child, "outwardDirection").isBlank() && item.has("outwardDirection")) {
                child.addProperty("outwardDirection", stringValue(item, "outwardDirection"));
            }
            if (!child.has("expansionOrigin") && item.has("expansionOrigin")
                    && item.get("expansionOrigin").isJsonObject()) {
                child.add("expansionOrigin", item.getAsJsonObject("expansionOrigin").deepCopy());
            }

            SubZone subZone = childSubZone(child, subZones, subZonesById, childIndex);
            child.addProperty("parentArrayId", arrayId);
            child.addProperty("targetSubZoneId", subZone.subZoneId());

            List<DesiredItem> desiredItems = desiredItems(child);
            if (desiredItems.isEmpty()) {
                hardBlocks.add(stringValue(child, "arrayId") + ": requiredItems, featuredItems or fillPool must provide structures.");
                break;
            }
            for (DesiredItem desired : desiredItems) {
                if (!profiles.containsKey(desired.structureId())) {
                    hardBlocks.add(stringValue(child, "arrayId") + ": structureId is not in approved TerraSense catalog: "
                            + desired.structureId());
                }
            }
            if (!hardBlocks.isEmpty()) {
                break;
            }

            List<LandformPatchSummary> childPatches = sourcePatches(child, reviewPackage);
            if (childPatches.isEmpty()) {
                childPatches = sourcePatches;
            }
            BuildResult childBuild = buildArrayItem(child, childPlannerType, childPatches, reviewPackage,
                    profiles, facts, localOccupied, desiredItems, subZone.bounds());
            childTraces.add(childBuild.trace());
            appendAll(warnings, childBuild.warnings());
            appendAll(hardBlocks, childBuild.hardBlocks());
            if (!childBuild.hardBlocks().isEmpty()) {
                break;
            }
            for (JsonElement placedElem : childBuild.items()) {
                placedItems.add(placedElem.deepCopy());
                JsonObject placed = placedElem.getAsJsonObject();
                BlockBounds collision = CityStructureCandidateEnvelope.bounds(
                        object(placed, "estimatedCollisionEnvelope"));
                localOccupied.add(collision);
                groupCollisionUnion = union(groupCollisionUnion, collision);
                groupMaskUnion = union(groupMaskUnion, CityStructureCandidateEnvelope.bounds(
                        object(placed, "estimatedMaskEnvelope")));
            }
            for (JsonElement anchorElem : childBuild.anchors()) {
                anchors.add(anchorElem.deepCopy());
            }
            for (JsonElement zoneElem : childBuild.zones()) {
                JsonObject childZone = zoneElem.getAsJsonObject().deepCopy();
                childZone.addProperty("zoneKind", "child_array");
                childZone.addProperty("parentArrayId", arrayId);
                childZone.addProperty("subZoneId", subZone.subZoneId());
                childZone.add("subZoneBounds", CityStructureCandidateEnvelope.boundsJson(subZone.bounds()));
                zones.add(childZone);
                childZoneIds.add(stringValue(childZone, "arrayZoneId", stringValue(childZone, "arrayId")));
            }
        }

        JsonObject parentZone = zone(item, "composite_array", 0, new JsonArray(), groupCollisionUnion,
                groupMaskUnion, new JsonArray());
        parentZone.addProperty("zoneKind", "parent_composite");
        parentZone.addProperty("parentPlannerType", stringValue(item, "parentPlannerType",
                stringValue(item, "subZonePolicy", "grid")));
        parentZone.add("subZones", subZonesJson(subZones));
        parentZone.add("childArrayZoneIds", childZoneIds);
        JsonArray allZones = new JsonArray();
        allZones.add(parentZone);
        for (JsonElement zoneElem : zones) {
            allZones.add(zoneElem.deepCopy());
        }

        JsonObject trace = new JsonObject();
        trace.addProperty("arrayId", arrayId);
        trace.addProperty("plannerType", "composite_array");
        trace.addProperty("zoneKind", "parent_composite");
        trace.addProperty("status", hardBlocks.isEmpty() ? "accepted" : "hard_blocked");
        trace.addProperty("placedItemCount", placedItems.size());
        trace.addProperty("requestedChildCount", childPlans.size());
        trace.add("subZones", subZonesJson(subZones));
        trace.add("childTraces", childTraces);
        trace.add("hardBlocks", hardBlocks.deepCopy());
        trace.add("warnings", warnings.deepCopy());
        return new BuildResult(placedItems, anchors, allZones, trace, hardBlocks, warnings);
    }

    private void applyBuild(JsonObject state, JsonObject sourceItem, BuildResult build) {
        JsonArray anchors = array(state, "arrayAnchors");
        for (JsonElement elem : build.anchors()) {
            anchors.add(elem.deepCopy());
        }
        JsonArray occupied = array(state, "occupiedEnvelopes");
        for (JsonElement elem : build.items()) {
            JsonObject item = elem.getAsJsonObject();
            JsonObject occ = new JsonObject();
            occ.addProperty("source", "array_layout_loop");
            occ.addProperty("arrayId", stringValue(item, "arrayId", stringValue(sourceItem, "arrayId")));
            occ.addProperty("itemId", stringValue(item, "itemId"));
            occ.addProperty("anchorId", stringValue(item, "anchorId"));
            occ.addProperty("envelopeType", "estimatedCollisionEnvelope");
            occ.add("blockBounds", object(item, "estimatedCollisionEnvelope").deepCopy());
            occ.add("bodyBounds", object(item, "plannedFootprint").deepCopy());
            occupied.add(occ);
        }
        JsonArray executed = array(state, "executedArrayIds");
        executed.add(stringValue(sourceItem, "arrayId"));
        for (JsonElement elem : build.zones()) {
            if (!elem.isJsonObject()) {
                continue;
            }
            String zoneArrayId = stringValue(elem.getAsJsonObject(), "arrayId");
            if (!zoneArrayId.isBlank() && !containsString(executed, zoneArrayId)) {
                executed.add(zoneArrayId);
            }
        }
        JsonObject accumulated = object(state, "accumulatedArrayLayoutPlan");
        JsonArray plans = array(accumulated, "layoutPlans");
        plans.add(sourceItem.deepCopy());
        JsonObject zones = object(state, "functionalArrayZones");
        for (JsonElement zoneElem : build.zones()) {
            array(zones, "arrayZones").add(zoneElem.deepCopy());
        }
    }

    private JsonArray roadAccessPoints(JsonObject item, JsonArray placedItems) {
        JsonArray points = new JsonArray();
        if (placedItems.isEmpty()) {
            return points;
        }
        int max = placedItems.size() >= 12 ? 3 : placedItems.size() >= 7 ? 2 : 1;
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        indexes.add(0);
        if (max >= 2) {
            indexes.add(placedItems.size() - 1);
        }
        if (max >= 3) {
            indexes.add(placedItems.size() / 2);
        }
        int ordinal = 0;
        for (int index : indexes) {
            JsonObject placed = placedItems.get(index).getAsJsonObject();
            JsonObject point = new JsonObject();
            point.addProperty("roadAccessPointId", stringValue(item, "arrayId") + "_gateway_" + (++ordinal));
            point.addProperty("kind", ordinal == 1 ? "gateway" : "secondary_gateway");
            point.add("anchorBlock", object(placed, "anchorBlock").deepCopy());
            points.add(point);
        }
        return points;
    }

    private JsonObject zone(JsonObject item, String plannerType, int spacing, JsonArray placedItems,
                            BlockBounds groupCollisionUnion, BlockBounds groupMaskUnion,
                            JsonArray roadAccessPoints) {
        JsonObject zone = new JsonObject();
        zone.addProperty("arrayZoneId", stringValue(item, "arrayId"));
        zone.addProperty("arrayId", stringValue(item, "arrayId"));
        zone.addProperty("zoneKind", "array_zone");
        zone.addProperty("role", stringValue(item, "role", stringValue(item, "displayRole", "")));
        zone.addProperty("plannerType", plannerType);
        zone.addProperty("arrayShape", arrayShape(item, plannerType));
        zone.addProperty("spacingBlocks", spacing);
        zone.addProperty("itemCount", placedItems.size());
        zone.add("items", placedItems.deepCopy());
        zone.add("groupCollisionEnvelope", CityStructureCandidateEnvelope.boundsJson(nonNullBounds(groupCollisionUnion)));
        zone.add("groupMaskEnvelope", CityStructureCandidateEnvelope.boundsJson(nonNullBounds(groupMaskUnion)));
        zone.add("roadAccessPoints", roadAccessPoints);
        return zone;
    }

    private JsonObject itemJson(JsonObject sourceItem, String plannerType, int spacing, int index, Accepted accepted) {
        JsonObject obj = new JsonObject();
        obj.addProperty("itemId", accepted.desired().itemId());
        obj.addProperty("itemIndex", index);
        obj.addProperty("itemKind", accepted.desired().kind());
        obj.addProperty("structureId", accepted.desired().structureId());
        obj.addProperty("arrayId", stringValue(sourceItem, "arrayId"));
        obj.addProperty("anchorId", stringValue(sourceItem, "arrayId") + "_"
                + safeId(accepted.desired().itemId(), index));
        obj.addProperty("plannerType", plannerType);
        obj.addProperty("arrayShape", arrayShape(sourceItem, plannerType));
        obj.addProperty("spacingBlocks", spacing);
        obj.add("anchorBlock", accepted.point().asJson());
        obj.add("roadPoint", accepted.point().asJson());
        obj.addProperty("rotation", "NONE");
        obj.add("sourcePatchRefs", patchRefs(accepted.patch()));
        obj.add("plannedFootprint", CityStructureCandidateEnvelope.boundsJson(accepted.estimate().plannedFootprint()));
        obj.add("estimatedCollisionEnvelope",
                CityStructureCandidateEnvelope.boundsJson(accepted.estimate().collisionEnvelope()));
        obj.add("estimatedMaskEnvelope", CityStructureCandidateEnvelope.boundsJson(accepted.estimate().maskEnvelope()));
        obj.add("diagnosticMaxObservedEnvelope",
                CityStructureCandidateEnvelope.boundsJson(accepted.estimate().diagnosticMaxObservedEnvelope()));
        obj.addProperty("envelopeMode", accepted.estimate().envelopeMode());
        obj.addProperty("selectedEnvelopeGroupKey", accepted.estimate().selectedEnvelopeGroupKey());
        obj.addProperty("roadAccessIntent", "array_zone_gateway_deferred_to_roadweaver");
        return obj;
    }

    private JsonObject anchorJson(JsonObject sourceItem, String plannerType, int index, Accepted accepted) {
        JsonObject anchor = new JsonObject();
        String arrayId = stringValue(sourceItem, "arrayId");
        anchor.addProperty("anchorId", arrayId + "_" + safeId(accepted.desired().itemId(), index));
        anchor.addProperty("arrayId", arrayId);
        anchor.addProperty("arrayPattern", plannerType);
        anchor.addProperty("arrayPlannerType", plannerType);
        anchor.addProperty("structureId", accepted.desired().structureId());
        anchor.add("sourcePatchIds", patchRefs(accepted.patch()));
        anchor.add("anchorBlock", accepted.point().asJson());
        anchor.addProperty("rotation", "NONE");
        anchor.add("intentTerms", intentTerms(arrayId, stringValue(sourceItem, "role",
                stringValue(sourceItem, "displayRole", arrayId)), plannerType));
        anchor.addProperty("priority", intValue(sourceItem, "priority", 100) + index);
        anchor.addProperty("roadAccessIntent", "array_zone_gateway_deferred_to_roadweaver");
        if (!accepted.estimate().selectedEnvelopeGroupKey().isBlank()) {
            anchor.addProperty("envelopeGroupKey", accepted.estimate().selectedEnvelopeGroupKey());
        }
        anchor.addProperty("smallClearanceBlocks", CityStructureCandidateEnvelope.DEFAULT_SMALL_CLEARANCE_BLOCKS);
        anchor.addProperty("selectionReason", "Selected from D4 array layout loop " + arrayId
                + " planner " + plannerType);
        return anchor;
    }

    private List<BlockPoint> rawPoints(String plannerType, JsonObject item, LandformPatchSummary pivot,
                                       List<LandformPatchSummary> patches, PlanningGrid grid,
                                       BlockPoint start, int spacing, int requested) {
        LinkedHashSet<BlockPoint> points = new LinkedHashSet<>();
        switch (plannerType) {
            case "plaza_ring" -> {
                String direction = stringValue(item, "outwardDirection", "");
                if (EXPANSION_DIRECTIONS.contains(direction)) {
                    outwardPlazaRing(points, point(item, "expansionOrigin", start), direction, spacing, requested);
                } else {
                    plazaRing(points, start, spacing, requested);
                }
            }
            case "guide_line_dual_side", "riverbank_dual_side" ->
                    dualSideBand(points, pivot, patches, start, spacing, requested);
            case "contour_band" -> contourBand(points, pivot, patches, start, spacing, requested);
            default -> compoundCluster(points, item, start, pivot, spacing, requested);
        }
        return new ArrayList<>(points);
    }

    private List<BlockPoint> rawPointsInBounds(String plannerType, JsonObject item, BlockBounds bounds,
                                               BlockPoint start, int spacing, int requested) {
        LinkedHashSet<BlockPoint> points = new LinkedHashSet<>();
        switch (plannerType) {
            case "plaza_ring" -> {
                String direction = stringValue(item, "outwardDirection", "");
                if (EXPANSION_DIRECTIONS.contains(direction)) {
                    outwardPlazaRing(points, point(item, "expansionOrigin", start), direction, spacing, requested);
                } else {
                    plazaRing(points, start, spacing, requested);
                }
            }
            case "guide_line_dual_side", "riverbank_dual_side" ->
                    dualSideBounds(points, bounds, start, spacing, requested);
            case "contour_band" -> contourBounds(points, bounds, start, spacing, requested);
            default -> compoundClusterBounds(points, item, bounds, start, spacing, requested);
        }
        return new ArrayList<>(points);
    }

    private List<BlockPoint> memberCellCandidatePoints(List<LandformPatchSummary> patches,
                                                       PlanningGrid grid,
                                                       BlockBounds bounds,
                                                       BlockPoint start,
                                                       List<BlockPoint> guidePoints) {
        List<BlockPoint> memberPoints = memberCellCenters(patches, grid, bounds);
        if (memberPoints.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<BlockPoint> ordered = new LinkedHashSet<>();
        for (BlockPoint guide : guidePoints) {
            BlockPoint nearest = nearestUnused(memberPoints, ordered, guide);
            if (nearest != null) {
                ordered.add(nearest);
            }
        }
        memberPoints.stream()
                .sorted(Comparator
                        .comparingLong((BlockPoint point) -> distanceSquared(point, start))
                        .thenComparingInt(BlockPoint::x)
                        .thenComparingInt(BlockPoint::z))
                .forEach(ordered::add);
        return new ArrayList<>(ordered);
    }

    /** Keeps D3 member-cell eligibility but does not snap continuous frontier anchors to a coarse cell center. */
    private List<BlockPoint> continuousMemberCellCandidatePoints(List<LandformPatchSummary> patches,
                                                                 PlanningGrid grid,
                                                                 BlockBounds bounds,
                                                                 BlockPoint start,
                                                                 List<BlockPoint> guidePoints) {
        LinkedHashSet<BlockPoint> ordered = new LinkedHashSet<>();
        for (BlockPoint guide : guidePoints) {
            if (!bounds.contains(guide.x(), guide.z()) || !grid.containsBlock(guide.x(), guide.z())) {
                continue;
            }
            if (pointPatch(patches, grid, guide) != null) {
                ordered.add(guide);
            }
        }
        for (BlockPoint point : memberCellCenters(patches, grid, bounds).stream()
                .sorted(Comparator.comparingLong((BlockPoint point) -> distanceSquared(point, start))
                        .thenComparingInt(BlockPoint::x).thenComparingInt(BlockPoint::z)).toList()) {
            ordered.add(point);
        }
        return new ArrayList<>(ordered);
    }

    private List<BlockPoint> memberCellCenters(List<LandformPatchSummary> patches,
                                               PlanningGrid grid,
                                               BlockBounds bounds) {
        LinkedHashSet<BlockPoint> centers = new LinkedHashSet<>();
        int step = Math.max(1, grid.cellStepBlocks());
        for (LandformPatchSummary patch : patches) {
            BlockBounds patchBounds = patch.blockBounds();
            if (!patchBounds.overlaps(bounds)) {
                continue;
            }
            if (!patch.memberCells().isEmpty()) {
                for (PatchMemberCell cell : patch.memberCells()) {
                    BlockBounds cellBounds = new BlockBounds(cell.blockMinX(), cell.blockMinZ(),
                            cell.blockMinX() + step - 1, cell.blockMinZ() + step - 1);
                    if (!cellBounds.overlaps(bounds)) {
                        continue;
                    }
                    BlockPoint point = new BlockPoint(
                            clamp(cell.blockMinX() + step / 2, bounds.minX(), bounds.maxX()),
                            clamp(cell.blockMinZ() + step / 2, bounds.minZ(), bounds.maxZ()));
                    if (grid.containsBlock(point.x(), point.z()) && patchContains(patch, grid, point)) {
                        centers.add(point);
                    }
                }
                continue;
            }
            int minX = Math.max(patchBounds.minX(), bounds.minX());
            int minZ = Math.max(patchBounds.minZ(), bounds.minZ());
            int maxX = Math.min(patchBounds.maxX(), bounds.maxX());
            int maxZ = Math.min(patchBounds.maxZ(), bounds.maxZ());
            for (int z = minZ; z <= maxZ; z += step) {
                for (int x = minX; x <= maxX; x += step) {
                    BlockPoint point = new BlockPoint(clamp(x + step / 2, minX, maxX),
                            clamp(z + step / 2, minZ, maxZ));
                    if (grid.containsBlock(point.x(), point.z()) && patchContains(patch, grid, point)) {
                        centers.add(point);
                    }
                }
            }
        }
        return new ArrayList<>(centers);
    }

    private BlockPoint nearestUnused(List<BlockPoint> points, Set<BlockPoint> used, BlockPoint guide) {
        BlockPoint best = null;
        long bestDistance = Long.MAX_VALUE;
        for (BlockPoint point : points) {
            if (used.contains(point)) {
                continue;
            }
            long distance = distanceSquared(point, guide);
            if (distance < bestDistance
                    || (distance == bestDistance && best != null
                    && (point.x() < best.x() || point.x() == best.x() && point.z() < best.z()))) {
                best = point;
                bestDistance = distance;
            }
        }
        return best;
    }

    private long distanceSquared(BlockPoint a, BlockPoint b) {
        long dx = (long) a.x() - b.x();
        long dz = (long) a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private void plazaRing(LinkedHashSet<BlockPoint> points, BlockPoint start, int spacing, int requested) {
        points.add(start);
        int rings = Math.max(2, (int) Math.ceil(requested / 8.0) + 1);
        for (int ring = 1; ring <= rings; ring++) {
            double radius = Math.max(spacing, spacing * 0.85 * ring);
            int steps = Math.max(8, requested * 3);
            for (int step = 0; step < steps; step++) {
                double angle = (Math.PI * 2.0 * step) / steps;
                points.add(new BlockPoint(start.x() + (int) Math.round(Math.cos(angle) * radius),
                        start.z() + (int) Math.round(Math.sin(angle) * radius)));
            }
        }
    }

    private void outwardPlazaRing(LinkedHashSet<BlockPoint> points, BlockPoint origin, String direction,
                                  int spacing, int requested) {
        double baseAngle = switch (direction) {
            case "north" -> -Math.PI / 2.0;
            case "south" -> Math.PI / 2.0;
            case "east" -> 0.0;
            case "west" -> Math.PI;
            case "northeast" -> -Math.PI / 4.0;
            case "northwest" -> -Math.PI * 3.0 / 4.0;
            case "southeast" -> Math.PI / 4.0;
            case "southwest" -> Math.PI * 3.0 / 4.0;
            default -> 0.0;
        };
        int rings = Math.max(1, (int) Math.ceil(requested / 6.0));
        for (int ring = 1; ring <= rings; ring++) {
            double radius = Math.max(spacing, spacing * 0.9 * ring);
            int steps = Math.max(7, requested * 3);
            for (int step = 0; step < steps; step++) {
                double angle = baseAngle - Math.PI / 2.0 + Math.PI * step / (steps - 1.0);
                points.add(new BlockPoint(origin.x() + (int) Math.round(Math.cos(angle) * radius),
                        origin.z() + (int) Math.round(Math.sin(angle) * radius)));
            }
        }
    }

    private void compoundCluster(LinkedHashSet<BlockPoint> points, JsonObject item, BlockPoint start,
                                 LandformPatchSummary pivot, int spacing, int requested) {
        String shape = compoundShape(item);
        if (!"organic_compact".equals(shape)) {
            ShapeGrid grid = shapeGrid(item, requested);
            structuredGrid(points, start, grid.rows(), grid.columns(), spacing, shape, null);
        }
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < requested * 24; i++) {
            double radius = i == 0 ? 0.0 : Math.max(spacing, spacing * 0.58 * Math.sqrt(i));
            double angle = i * golden;
            points.add(new BlockPoint(start.x() + (int) Math.round(Math.cos(angle) * radius),
                    start.z() + (int) Math.round(Math.sin(angle) * radius)));
        }
        points.add(pivot.centerBlock());
    }

    private void dualSideBand(LinkedHashSet<BlockPoint> points, LandformPatchSummary pivot,
                              List<LandformPatchSummary> patches, BlockPoint start, int spacing, int requested) {
        for (LandformPatchSummary patch : orderedPatches(pivot, patches)) {
            BlockBounds b = patch.blockBounds();
            boolean xAxis = b.widthBlocks() >= b.heightBlocks();
            int length = xAxis ? b.widthBlocks() : b.heightBlocks();
            int steps = Math.max(requested * 4, Math.max(1, length / Math.max(8, spacing / 2)));
            for (int step = 0; step <= steps; step++) {
                int along = spacing / 2 + step * Math.max(8, spacing / 2);
                int side = step % 2 == 0 ? 1 : -1;
                int cross = side * Math.max(10, spacing / 2);
                int x = xAxis ? b.minX() + Math.min(length - 1, along) : start.x() + cross;
                int z = xAxis ? start.z() + cross : b.minZ() + Math.min(length - 1, along);
                points.add(new BlockPoint(clamp(x, b.minX(), b.maxX()), clamp(z, b.minZ(), b.maxZ())));
            }
        }
    }

    private void dualSideBounds(LinkedHashSet<BlockPoint> points, BlockBounds b, BlockPoint start,
                                int spacing, int requested) {
        boolean xAxis = b.widthBlocks() >= b.heightBlocks();
        int length = xAxis ? b.widthBlocks() : b.heightBlocks();
        int steps = Math.max(requested * 6, Math.max(1, length / Math.max(8, spacing / 2)));
        int stride = Math.max(8, spacing / 2);
        for (int step = 0; step <= steps; step++) {
            int along = (step - steps / 2) * stride;
            int side = step % 2 == 0 ? 1 : -1;
            int cross = side * Math.max(8, spacing / 3);
            int x = xAxis ? start.x() + along : start.x() + cross;
            int z = xAxis ? start.z() + cross : start.z() + along;
            points.add(new BlockPoint(clamp(x, b.minX(), b.maxX()), clamp(z, b.minZ(), b.maxZ())));
        }
    }

    private void contourBand(LinkedHashSet<BlockPoint> points, LandformPatchSummary pivot,
                             List<LandformPatchSummary> patches, BlockPoint start, int spacing, int requested) {
        for (LandformPatchSummary patch : orderedPatches(pivot, patches)) {
            BlockBounds b = patch.blockBounds();
            boolean xAxis = b.widthBlocks() >= b.heightBlocks();
            int lanes = Math.max(1, Math.min(3, (xAxis ? b.heightBlocks() : b.widthBlocks())
                    / Math.max(1, spacing)));
            int length = xAxis ? b.widthBlocks() : b.heightBlocks();
            for (int lane = 0; lane < lanes; lane++) {
                int cross = (lane - lanes / 2) * Math.max(8, spacing / 2);
                for (int step = 0; step < requested * 8; step++) {
                    int along = spacing / 2 + step * Math.max(8, spacing);
                    int x = xAxis ? b.minX() + Math.min(length - 1, along) : start.x() + cross;
                    int z = xAxis ? start.z() + cross : b.minZ() + Math.min(length - 1, along);
                    points.add(new BlockPoint(clamp(x, b.minX(), b.maxX()), clamp(z, b.minZ(), b.maxZ())));
                }
            }
        }
    }

    private void contourBounds(LinkedHashSet<BlockPoint> points, BlockBounds b, BlockPoint start,
                               int spacing, int requested) {
        boolean xAxis = b.widthBlocks() >= b.heightBlocks();
        int lanes = Math.max(1, Math.min(3, (xAxis ? b.heightBlocks() : b.widthBlocks())
                / Math.max(1, spacing)));
        int length = xAxis ? b.widthBlocks() : b.heightBlocks();
        for (int lane = 0; lane < lanes; lane++) {
            int cross = (lane - lanes / 2) * Math.max(8, spacing / 2);
            for (int step = 0; step < requested * 10; step++) {
                int along = spacing / 2 + step * Math.max(8, spacing);
                int x = xAxis ? b.minX() + Math.min(length - 1, along) : start.x() + cross;
                int z = xAxis ? start.z() + cross : b.minZ() + Math.min(length - 1, along);
                points.add(new BlockPoint(clamp(x, b.minX(), b.maxX()), clamp(z, b.minZ(), b.maxZ())));
            }
        }
    }

    private void compoundClusterBounds(LinkedHashSet<BlockPoint> points, JsonObject item, BlockBounds bounds,
                                       BlockPoint start, int spacing, int requested) {
        String shape = compoundShape(item);
        if (!"organic_compact".equals(shape)) {
            ShapeGrid grid = shapeGrid(item, requested);
            structuredGrid(points, start, grid.rows(), grid.columns(), spacing, shape, bounds);
        }
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < requested * 24; i++) {
            double radius = i == 0 ? 0.0 : Math.max(spacing, spacing * 0.55 * Math.sqrt(i));
            double angle = i * golden;
            int x = start.x() + (int) Math.round(Math.cos(angle) * radius);
            int z = start.z() + (int) Math.round(Math.sin(angle) * radius);
            points.add(new BlockPoint(clamp(x, bounds.minX(), bounds.maxX()),
                    clamp(z, bounds.minZ(), bounds.maxZ())));
        }
        points.add(bounds.center());
    }

    private List<DesiredItem> desiredItems(JsonObject item) {
        List<DesiredItem> desired = new ArrayList<>();
        addExplicitItems(desired, array(item, "requiredItems"), "required");
        addExplicitItems(desired, array(item, "featuredItems"), "featured");
        JsonArray fillPool = array(item, "fillPool");
        int target = countValue(item, "targetCount", Math.max(desired.size(), fillPool.isEmpty() ? desired.size() : 1));
        int max = countValue(item, "maxCount", Math.max(target, desired.size()));
        target = Math.min(Math.max(target, desired.size()), max);
        int fillIndex = 0;
        while (desired.size() < target && !fillPool.isEmpty()) {
            fillIndex++;
            String structureId = fillStructureId(item, fillPool, fillIndex);
            desired.add(new DesiredItem("fill_" + String.format(Locale.ROOT, "%02d", fillIndex),
                    structureId, "fill", "skip_with_warning"));
        }
        return desired;
    }

    private void addExplicitItems(List<DesiredItem> desired, JsonArray items, String kind) {
        int index = 0;
        for (JsonElement elem : items) {
            index++;
            if (elem.isJsonPrimitive()) {
                String structureId = elem.getAsString();
                desired.add(new DesiredItem(kind + "_" + String.format(Locale.ROOT, "%02d", index),
                        structureId, kind, "required".equals(kind) ? "hard_block" : "skip_with_warning"));
            } else if (elem.isJsonObject()) {
                JsonObject obj = elem.getAsJsonObject();
                String structureId = requiredString(obj, "structureId");
                String itemId = stringValue(obj, "itemId", kind + "_" + String.format(Locale.ROOT, "%02d", index));
                desired.add(new DesiredItem(itemId, structureId, kind,
                        stringValue(obj, "failurePolicy", "required".equals(kind) ? "hard_block" : "skip_with_warning")));
            }
        }
    }

    private String fillStructureId(JsonObject item, JsonArray fillPool, int itemIndex) {
        String mode = stringValue(item, "variantSelectionMode", "weighted_random");
        if (!"seeded_random".equals(mode) && !"weighted_random".equals(mode) && !"random".equals(mode)) {
            return fillPool.get((itemIndex - 1) % fillPool.size()).isJsonObject()
                    ? requiredString(fillPool.get((itemIndex - 1) % fillPool.size()).getAsJsonObject(), "structureId")
                    : fillPool.get((itemIndex - 1) % fillPool.size()).getAsString();
        }
        double total = 0.0;
        List<Double> weights = new ArrayList<>();
        for (JsonElement elem : fillPool) {
            double weight = elem.isJsonObject() ? doubleValue(elem.getAsJsonObject(), "weight", 1.0) : 1.0;
            weight = Math.max(0.0, weight);
            weights.add(weight);
            total += weight;
        }
        if (total <= 0.0) {
            return fillPool.get((itemIndex - 1) % fillPool.size()).isJsonObject()
                    ? requiredString(fillPool.get((itemIndex - 1) % fillPool.size()).getAsJsonObject(), "structureId")
                    : fillPool.get((itemIndex - 1) % fillPool.size()).getAsString();
        }
        long seed = stableSeed(stringValue(item, "variantSeed", "") + ":" + stringValue(item, "arrayId")
                + ":" + itemIndex);
        double pick = new SplittableRandom(seed).nextDouble(total);
        double cursor = 0.0;
        for (int i = 0; i < fillPool.size(); i++) {
            cursor += weights.get(i);
            if (pick < cursor) {
                JsonElement elem = fillPool.get(i);
                return elem.isJsonObject() ? requiredString(elem.getAsJsonObject(), "structureId") : elem.getAsString();
            }
        }
        JsonElement last = fillPool.get(fillPool.size() - 1);
        return last.isJsonObject() ? requiredString(last.getAsJsonObject(), "structureId") : last.getAsString();
    }

    private void structuredGrid(LinkedHashSet<BlockPoint> points,
                                BlockPoint center,
                                int rows,
                                int columns,
                                int spacing,
                                String shape,
                                BlockBounds clampBounds) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                if (!includesCell(shape, row, col, rows, columns)) {
                    continue;
                }
                int x = center.x() + (int) Math.round((col - (columns - 1) / 2.0) * spacing);
                int z = center.z() + (int) Math.round((row - (rows - 1) / 2.0) * spacing);
                if (clampBounds != null) {
                    x = clamp(x, clampBounds.minX(), clampBounds.maxX());
                    z = clamp(z, clampBounds.minZ(), clampBounds.maxZ());
                }
                points.add(new BlockPoint(x, z));
            }
        }
    }

    private boolean includesCell(String shape, int row, int col, int rows, int columns) {
        if ("courtyard".equals(shape) && rows > 2 && columns > 2) {
            return row == 0 || row == rows - 1 || col == 0 || col == columns - 1;
        }
        if ("l_shape".equals(shape) && rows > 1 && columns > 1) {
            return row == rows - 1 || col == 0;
        }
        if ("u_shape".equals(shape) && rows > 1 && columns > 2) {
            return row == rows - 1 || col == 0 || col == columns - 1;
        }
        return true;
    }

    private int spacing(JsonObject item,
                        List<DesiredItem> items,
                        Map<String, CityStructureProfileCatalog.StructureProfile> profiles,
                        CityStructureEnvelopeFacts facts) {
        int configured = compoundInt(item, "spacingBlocks", 0);
        if (configured > 0) {
            return configured;
        }
        int max = 16;
        for (DesiredItem desired : items) {
            CityStructureProfileCatalog.StructureProfile profile = profiles.get(desired.structureId());
            if (profile == null) {
                continue;
            }
            max = Math.max(max, CityStructureCandidateEnvelope.automaticSpacing(profile, facts, item));
        }
        return max;
    }

    private ShapeGrid shapeGrid(JsonObject item, int requestedCount) {
        int requested = Math.max(1, requestedCount);
        int rows = compoundInt(item, "rows", 0);
        int columns = compoundInt(item, "columns", 0);
        if (rows <= 0 && columns <= 0) {
            columns = Math.max(1, (int) Math.ceil(Math.sqrt(requested)));
            rows = Math.max(1, (int) Math.ceil(requested / (double) columns));
        } else if (rows <= 0) {
            rows = Math.max(1, (int) Math.ceil(requested / (double) Math.max(1, columns)));
        } else if (columns <= 0) {
            columns = Math.max(1, (int) Math.ceil(requested / (double) rows));
        }
        return new ShapeGrid(Math.max(1, rows), Math.max(1, columns));
    }

    private String compoundShape(JsonObject item) {
        String shape = compoundString(item, "shape",
                compoundString(item, "clusterShape", "organic_compact"));
        return switch (shape) {
            case "grid", "courtyard", "l_shape", "u_shape", "organic_compact" -> shape;
            default -> "organic_compact";
        };
    }

    private String arrayShape(JsonObject item, String plannerType) {
        return "compound_cluster".equals(plannerType) ? compoundShape(item) : plannerType;
    }

    private int compoundInt(JsonObject item, String key, int defaultValue) {
        JsonObject compound = object(item, "compoundCluster");
        return intValue(compound, key, intValue(item, key, defaultValue));
    }

    private String compoundString(JsonObject item, String key, String defaultValue) {
        JsonObject compound = object(item, "compoundCluster");
        return stringValue(compound, key, stringValue(item, key, defaultValue));
    }

    private void requireV04State(JsonObject state) {
        if (state == null || !STATE_SCHEMA_V04.equals(stringValue(state, "schemaVersion"))
                || !PLANNING_MODE_V04.equals(planningMode(state))) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_V04_STATE_REQUIRED: create an explicit v0.4 array candidate loop first.");
        }
    }

    private String stateSchema(String planningMode) {
        return switch (planningMode) {
            case PLANNING_MODE_V04 -> STATE_SCHEMA_V04;
            case PLANNING_MODE_V03 -> STATE_SCHEMA_V03;
            default -> STATE_SCHEMA;
        };
    }

    private ExpansionContext expansionContext(CityLandformReviewPackage reviewPackage,
                                              JsonObject state,
                                              JsonObject request) {
        boolean newFunctionalArea = booleanValue(request, "newFunctionalArea", false);
        if (newFunctionalArea) {
            String selectedGlobalPatchRef = stringValue(request, "selectedGlobalPatchRef", "");
            if (selectedGlobalPatchRef.isBlank()) {
                return new ExpansionContext(new JsonObject(), null, null, "", null, null, null,
                        true, false, ExpansionPolicy.defaults());
            }
            LandformPatchSummary targetPatch = patchByRef(reviewPackage, selectedGlobalPatchRef);
            if (targetPatch == null) {
                throw new IllegalArgumentException("D4_ARRAY_LAYOUT_GLOBAL_PATCH_UNAVAILABLE: " + selectedGlobalPatchRef);
            }
            List<BlockBounds> occupied = occupiedBounds(array(state, "occupiedEnvelopes"));
            BlockBounds available = targetPatch.blockBounds();
            BlockPoint entry = nearestAvailablePoint(targetPatch, reviewPackage.grid(), available, occupied,
                    targetPatch.centerBlock());
            if (entry == null) {
                throw new IllegalArgumentException("D4_ARRAY_LAYOUT_GLOBAL_PATCH_NO_CAPACITY: " + selectedGlobalPatchRef);
            }
            return new ExpansionContext(new JsonObject(), null, null, "", targetPatch, available, entry,
                    true, false, ExpansionPolicy.defaults());
        }
        JsonObject focusRef = object(request, "focusRef");
        if (focusRef.size() == 0) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_FOCUS_REQUIRED: focusRef must identify planned collision occupied structure(s).");
        }
        BlockBounds focusBounds = focusBounds(state, focusRef);
        BlockBounds focusBodyBounds = focusBodyBounds(state, focusRef, focusBounds);
        String direction = normalizedDirection(requiredString(request, "direction"));
        ExpansionPolicy policy = expansionPolicy(request);
        String targetPatchRef = stringValue(request, "targetPatchRef", stringValue(request, "targetPatch", ""));
        if (targetPatchRef.isBlank()) {
            return new ExpansionContext(focusRef.deepCopy(), focusBounds, focusBodyBounds, direction,
                    null, null, focusBodyBounds.center(), false, true, policy);
        }
        LandformPatchSummary targetPatch = patchByRef(reviewPackage, targetPatchRef);
        if (targetPatch == null) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_TARGET_PATCH_UNAVAILABLE: " + targetPatchRef);
        }
        BlockBounds available = directionalIntersection(focusBounds, direction, targetPatch.blockBounds());
        if (available == null) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_EXPANSION_DIRECTION_UNAVAILABLE: " + direction
                    + " does not reach target patch " + targetPatchRef + " from the focus collision envelope.");
        }
        BlockPoint entry = nearestAvailablePoint(targetPatch, reviewPackage.grid(), available,
                occupiedBounds(array(state, "occupiedEnvelopes")), focusBounds.center());
        return new ExpansionContext(focusRef.deepCopy(), focusBounds, focusBodyBounds, direction, targetPatch,
                available, entry == null ? available.center() : entry, false, false, policy);
    }

    private JsonObject expansionSpace(CityLandformReviewPackage reviewPackage,
                                      JsonObject state,
                                      ExpansionContext selected) {
        List<BlockBounds> occupied = occupiedBounds(array(state, "occupiedEnvelopes"));
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", EXPANSION_SPACE_SCHEMA_V04);
        result.addProperty("planningMode", PLANNING_MODE_V04);
        result.addProperty("cityId", reviewPackage.cityId());
        result.addProperty("sourceStateId", stringValue(state, "stateId"));
        result.addProperty("searchScope", selected.newFunctionalArea()
                ? "explicit_global_new_functional_area"
                : selected.continuousFrontier() ? "continuous_focus_frontier" : "focus_nearby_expansion");
        result.add("focusRef", selected.focusRef().deepCopy());
        if (selected.newFunctionalArea()) {
            result.add("globalPatchCandidates", globalPatchCandidates(reviewPackage, occupied));
            result.addProperty("selectedGlobalPatchRequired", selected.targetPatch() == null);
            if (selected.targetPatch() == null) {
                result.addProperty("selectionReasonCode", "D4_ARRAY_LAYOUT_GLOBAL_PATCH_SELECTION_REQUIRED");
                return result;
            }
        }
        if (selected.focusBounds() != null) {
            result.add("focusCollisionEnvelope", CityStructureCandidateEnvelope.boundsJson(selected.focusBounds()));
        }
        if (selected.focusBodyBounds() != null) {
            result.add("focusBodyEnvelope", CityStructureCandidateEnvelope.boundsJson(selected.focusBodyBounds()));
        }
        if (selected.continuousFrontier()) {
            result.addProperty("expansionMode", "continuous_focus_frontier");
            result.add("expansionPolicy", selected.expansionPolicy().asJson());
            result.add("frontierRings", frontierRingPreview(reviewPackage, selected));
        }
        result.addProperty("selectedDirection", selected.direction());
        if (selected.targetPatch() != null) {
            result.addProperty("selectedTargetPatchRef", selected.targetPatch().landformPatchId());
            result.add("selectedExpansionAvailableBounds", CityStructureCandidateEnvelope.boundsJson(selected.availableBounds()));
            result.add("selectedExpansionEntryPoint", selected.entryPoint().asJson());
            result.addProperty("selectedRemainingCapacity", capacity(selected.targetPatch(), reviewPackage.grid(),
                    selected.availableBounds(), occupied));
        }

        JsonArray nearby = new JsonArray();
        List<LandformPatchSummary> ordered = new ArrayList<>(reviewPackage.landformPatches());
        ordered.sort(Comparator.<LandformPatchSummary>comparingDouble(patch -> selected.focusBounds() == null ? 0.0
                : boundsDistance(selected.focusBounds(), patch.blockBounds()))
                .thenComparing(LandformPatchSummary::landformPatchId));
        for (LandformPatchSummary patch : ordered) {
            JsonObject patchJson = new JsonObject();
            patchJson.addProperty("patchRef", patch.landformPatchId());
            patchJson.addProperty("mapLabel", patch.mapLabel());
            patchJson.add("blockBounds", CityStructureCandidateEnvelope.boundsJson(patch.blockBounds()));
            patchJson.addProperty("distanceBlocks", selected.focusBounds() == null ? 0.0
                    : boundsDistance(selected.focusBounds(), patch.blockBounds()));
            patchJson.addProperty("remainingCapacity", capacity(patch, reviewPackage.grid(), patch.blockBounds(), occupied));
            JsonArray directions = new JsonArray();
            if (selected.focusBounds() != null) {
                for (String direction : EXPANSION_DIRECTIONS) {
                    BlockBounds available = directionalIntersection(selected.focusBounds(), direction, patch.blockBounds());
                    JsonObject option = new JsonObject();
                    option.addProperty("direction", direction);
                    option.addProperty("available", available != null);
                    if (available != null) {
                        option.add("availableBounds", CityStructureCandidateEnvelope.boundsJson(available));
                        option.addProperty("remainingCapacity", capacity(patch, reviewPackage.grid(), available, occupied));
                        BlockPoint entry = nearestAvailablePoint(patch, reviewPackage.grid(), available, occupied,
                                selected.focusBounds().center());
                        if (entry != null) {
                            option.add("expansionEntryPoint", entry.asJson());
                        }
                    }
                    directions.add(option);
                }
            }
            patchJson.add("availableDirections", directions);
            nearby.add(patchJson);
        }
        result.add("nearbyPatches", nearby);
        return result;
    }

    private JsonArray globalPatchCandidates(CityLandformReviewPackage reviewPackage,
                                            List<BlockBounds> occupied) {
        List<GlobalPatchCandidate> candidates = new ArrayList<>();
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            int remainingCapacity = capacity(patch, reviewPackage.grid(), patch.blockBounds(), occupied);
            BlockPoint entry = nearestAvailablePoint(patch, reviewPackage.grid(), patch.blockBounds(), occupied,
                    patch.centerBlock());
            candidates.add(new GlobalPatchCandidate(patch, remainingCapacity, entry));
        }
        candidates.sort(Comparator.comparing(GlobalPatchCandidate::available).reversed()
                .thenComparing(GlobalPatchCandidate::remainingCapacity, Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.patch().landformPatchId()));

        JsonArray result = new JsonArray();
        int rank = 1;
        for (GlobalPatchCandidate candidate : candidates) {
            JsonObject patch = new JsonObject();
            patch.addProperty("globalRank", rank++);
            patch.addProperty("patchRef", candidate.patch().landformPatchId());
            patch.addProperty("mapLabel", candidate.patch().mapLabel());
            patch.add("blockBounds", CityStructureCandidateEnvelope.boundsJson(candidate.patch().blockBounds()));
            patch.addProperty("remainingCapacity", candidate.remainingCapacity());
            patch.addProperty("available", candidate.available());
            patch.addProperty("availabilityReason", candidate.available()
                    ? "member_cells_available" : "collision_occupied_or_no_member_cells");
            if (candidate.entryPoint() != null) {
                patch.add("expansionEntryPoint", candidate.entryPoint().asJson());
            }
            result.add(patch);
        }
        return result;
    }

    private BlockBounds focusBounds(JsonObject state, JsonObject focusRef) {
        String anchorId = stringValue(focusRef, "anchorId", stringValue(focusRef, "focusId", ""));
        String arrayId = stringValue(focusRef, "arrayId", "");
        if (anchorId.isBlank() && arrayId.isBlank()) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_FOCUS_REQUIRED: focusRef requires anchorId or arrayId.");
        }
        BlockBounds union = null;
        for (JsonElement elem : array(state, "occupiedEnvelopes")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject occupied = elem.getAsJsonObject();
            boolean matches = !anchorId.isBlank() && anchorId.equals(stringValue(occupied, "anchorId"));
            matches |= !arrayId.isBlank() && arrayId.equals(stringValue(occupied, "arrayId"));
            if (matches) {
                union = union(union, CityStructureCandidateEnvelope.bounds(object(occupied, "blockBounds")));
            }
        }
        if (union == null) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_FOCUS_NOT_OCCUPIED: focusRef must resolve to planned collision occupied data.");
        }
        return union;
    }

    private BlockBounds focusBodyBounds(JsonObject state, JsonObject focusRef, BlockBounds fallback) {
        String anchorId = stringValue(focusRef, "anchorId", stringValue(focusRef, "focusId", ""));
        String arrayId = stringValue(focusRef, "arrayId", "");
        BlockBounds union = null;
        for (JsonElement elem : array(state, "occupiedEnvelopes")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject occupied = elem.getAsJsonObject();
            boolean matches = !anchorId.isBlank() && anchorId.equals(stringValue(occupied, "anchorId"));
            matches |= !arrayId.isBlank() && arrayId.equals(stringValue(occupied, "arrayId"));
            if (!matches) {
                continue;
            }
            JsonObject body = object(occupied, "bodyBounds");
            union = union(union, body.size() == 0
                    ? CityStructureCandidateEnvelope.bounds(object(occupied, "blockBounds"))
                    : CityStructureCandidateEnvelope.bounds(body));
        }
        return union == null ? fallback : union;
    }

    private ExpansionPolicy expansionPolicy(JsonObject request) {
        JsonObject policy = object(request, "expansionPolicy");
        int min = Math.max(0, intValue(policy, "actualBodyGapMin",
                intValue(request, "actualBodyGapMin", 16)));
        int max = Math.max(min, intValue(policy, "actualBodyGapMax",
                intValue(request, "actualBodyGapMax", 30)));
        int step = Math.max(4, intValue(policy, "frontierExpansionStepBlocks",
                intValue(request, "frontierExpansionStepBlocks", Math.max(16, max - min + 1))));
        int rounds = clamp(intValue(policy, "frontierMaxExpansionRounds",
                intValue(request, "frontierMaxExpansionRounds", 3)), 1, 3);
        return new ExpansionPolicy(min, max, step, rounds);
    }

    private FrontierReference frontierReference(JsonObject submittedItem,
                                                String plannerType,
                                                List<DesiredItem> desired,
                                                Map<String, CityStructureProfileCatalog.StructureProfile> profiles,
                                                CityStructureEnvelopeFacts facts) {
        String structureId = desired.isEmpty() ? firstStructureId(submittedItem, plannerType) : desired.get(0).structureId();
        CityStructureProfileCatalog.StructureProfile profile = profiles.get(structureId);
        if (profile == null) {
            return new FrontierReference(new BlockBounds(-8, -8, 8, 8), 32);
        }
        CityStructureCandidateEnvelope.Estimate estimate = CityStructureCandidateEnvelope.estimate(
                new BlockPoint(0, 0), profile, facts, submittedItem);
        int spacing = desired.isEmpty()
                ? Math.max(16, CityStructureCandidateEnvelope.automaticSpacing(profile, facts, submittedItem))
                : spacing(submittedItem, desired, profiles, facts);
        return new FrontierReference(estimate.plannedFootprint(), spacing);
    }

    private String firstStructureId(JsonObject item, String plannerType) {
        if (!"composite_array".equals(plannerType)) {
            List<DesiredItem> items = desiredItems(item);
            return items.isEmpty() ? "" : items.get(0).structureId();
        }
        for (JsonElement child : array(item, "childLayoutPlans")) {
            if (!child.isJsonObject()) {
                continue;
            }
            JsonObject childItem = child.getAsJsonObject();
            String id = firstStructureId(childItem, stringValue(childItem, "plannerType", "compound_cluster"));
            if (!id.isBlank()) {
                return id;
            }
        }
        return "";
    }

    private BlockPoint continuousFrontierAnchor(BlockBounds parentBody,
                                                String direction,
                                                BlockBounds localFootprint,
                                                int gapMin,
                                                int gapMax,
                                                int spacing,
                                                PlanningGrid grid) {
        int x = parentBody.center().x();
        int z = parentBody.center().z();
        if (direction.contains("east")) {
            x = alignedFrontierAnchor(parentBody.maxX() + gapMin + 1 - localFootprint.minX(),
                    parentBody.maxX() + gapMax + 1 - localFootprint.minX());
        } else if (direction.contains("west")) {
            x = alignedFrontierAnchor(parentBody.minX() - gapMax - 1 - localFootprint.maxX(),
                    parentBody.minX() - gapMin - 1 - localFootprint.maxX());
        }
        if (direction.contains("south")) {
            z = alignedFrontierAnchor(parentBody.maxZ() + gapMin + 1 - localFootprint.minZ(),
                    parentBody.maxZ() + gapMax + 1 - localFootprint.minZ());
        } else if (direction.contains("north")) {
            z = alignedFrontierAnchor(parentBody.minZ() - gapMax - 1 - localFootprint.maxZ(),
                    parentBody.minZ() - gapMin - 1 - localFootprint.maxZ());
        }
        return clampToGrid(new BlockPoint(x, z), grid);
    }

    private int alignedFrontierAnchor(int minimum, int maximum) {
        int aligned = ceilToMultiple(minimum, 16);
        if (aligned <= maximum) {
            return aligned;
        }
        int midpoint = (minimum + maximum) / 2;
        int lower = Math.floorDiv(midpoint, 16) * 16;
        int upper = lower + 16;
        return Math.abs(lower - midpoint) <= Math.abs(upper - midpoint) ? lower : upper;
    }

    private int ceilToMultiple(int value, int multiple) {
        return -Math.floorDiv(-value, multiple) * multiple;
    }

    private BlockPoint continuousFirstGuideOffset(JsonObject item,
                                                  String plannerType,
                                                  int requested,
                                                  int spacing) {
        if (!"compound_cluster".equals(plannerType) || "organic_compact".equals(compoundShape(item))) {
            return new BlockPoint(0, 0);
        }
        ShapeGrid shape = shapeGrid(item, requested);
        return new BlockPoint((int) Math.round(-(shape.columns() - 1) * spacing / 2.0),
                (int) Math.round(-(shape.rows() - 1) * spacing / 2.0));
    }

    private BlockBounds continuousFrontierBounds(BlockBounds parentBody,
                                                  String direction,
                                                  BlockPoint origin,
                                                  int spacing,
                                                  int requested,
                                                  PlanningGrid grid) {
        int reach = Math.max(spacing * 2, spacing * (requested + 1));
        int minX = origin.x() - reach;
        int maxX = origin.x() + reach;
        int minZ = origin.z() - reach;
        int maxZ = origin.z() + reach;
        if (direction.contains("east")) {
            minX = Math.max(minX, parentBody.maxX() + 1);
        }
        if (direction.contains("west")) {
            maxX = Math.min(maxX, parentBody.minX() - 1);
        }
        if (direction.contains("south")) {
            minZ = Math.max(minZ, parentBody.maxZ() + 1);
        }
        if (direction.contains("north")) {
            maxZ = Math.min(maxZ, parentBody.minZ() - 1);
        }
        return clampBounds(new BlockBounds(minX, minZ, maxX, maxZ), gridBounds(grid));
    }

    private BlockPoint continuousVariantOrigin(BlockBounds bounds,
                                                BlockPoint base,
                                                String direction,
                                                int attempt,
                                                int spacing) {
        int[] offsets = {0, 1, -1, 2, -2, 3, -3, 4};
        int offset = offsets[attempt % offsets.length] * Math.max(4, spacing);
        int x = base.x();
        int z = base.z();
        if ("east".equals(direction) || "west".equals(direction)) {
            z += offset;
        } else if ("north".equals(direction) || "south".equals(direction)) {
            x += offset;
        } else if (direction.contains("east")) {
            x -= offset;
            z += offset;
        } else {
            x += offset;
            z += offset;
        }
        return new BlockPoint(clamp(x, bounds.minX(), bounds.maxX()), clamp(z, bounds.minZ(), bounds.maxZ()));
    }

    private FrontierGapValidation validateFrontierBodyGap(BlockBounds parentBody,
                                                           String direction,
                                                           JsonArray items,
                                                           int gapMin,
                                                           int gapMax) {
        if (items.isEmpty()) {
            return new FrontierGapValidation(false, -1);
        }
        BlockBounds body = CityStructureCandidateEnvelope.bounds(
                object(items.get(0).getAsJsonObject(), "plannedFootprint"));
        int gap = directionalBodyGap(parentBody, body, direction);
        return new FrontierGapValidation(gap >= gapMin && gap <= gapMax, gap);
    }

    private int directionalBodyGap(BlockBounds parent, BlockBounds child, String direction) {
        int xGap = direction.contains("east") ? child.minX() - parent.maxX() - 1
                : direction.contains("west") ? parent.minX() - child.maxX() - 1 : Integer.MAX_VALUE;
        int zGap = direction.contains("south") ? child.minZ() - parent.maxZ() - 1
                : direction.contains("north") ? parent.minZ() - child.maxZ() - 1 : Integer.MAX_VALUE;
        if (xGap != Integer.MAX_VALUE && zGap != Integer.MAX_VALUE) {
            return Math.min(xGap, zGap);
        }
        return xGap != Integer.MAX_VALUE ? xGap : zGap;
    }

    private TerrainPlacementPolicy terrainPlacementPolicy(JsonObject item,
                                                          String plannerType,
                                                          List<DesiredItem> desired,
                                                          Map<String, CityStructureProfileCatalog.StructureProfile> profiles) {
        List<String> structureIds = new ArrayList<>();
        for (DesiredItem value : desired) {
            structureIds.add(value.structureId());
        }
        if (structureIds.isEmpty()) {
            collectStructureIds(item, plannerType, structureIds);
        }
        boolean waterAllowed = !structureIds.isEmpty();
        for (String structureId : structureIds) {
            CityStructureProfileCatalog.StructureProfile profile = profiles.get(structureId);
            waterAllowed &= profile != null && explicitlyWaterPlaced(profile);
        }
        return new TerrainPlacementPolicy(waterAllowed);
    }

    private void collectStructureIds(JsonObject item, String plannerType, List<String> target) {
        if ("composite_array".equals(plannerType)) {
            for (JsonElement child : array(item, "childLayoutPlans")) {
                if (!child.isJsonObject()) {
                    continue;
                }
                JsonObject childItem = child.getAsJsonObject();
                collectStructureIds(childItem, stringValue(childItem, "plannerType", "compound_cluster"), target);
            }
            return;
        }
        for (DesiredItem desired : desiredItems(item)) {
            target.add(desired.structureId());
        }
    }

    private boolean explicitlyWaterPlaced(CityStructureProfileCatalog.StructureProfile profile) {
        for (String term : profile.placementTerms()) {
            String normalized = term.toLowerCase(Locale.ROOT);
            if (normalized.contains("water") || normalized.contains("aquatic") || term.contains("水上")) {
                return true;
            }
        }
        return false;
    }

    private TerrainPatchSelection terrainPatchesForFrontier(CityLandformReviewPackage reviewPackage,
                                                             BlockBounds bounds,
                                                             TerrainPlacementPolicy policy) {
        List<LandformPatchSummary> patches = new ArrayList<>();
        JsonArray excluded = new JsonArray();
        boolean groundedWaterRejection = false;
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            if (!patch.blockBounds().overlaps(bounds)) {
                continue;
            }
            String rejection = terrainPatchRejection(patch, policy);
            if (rejection.isBlank()) {
                patches.add(patch);
            } else {
                JsonObject skipped = new JsonObject();
                skipped.addProperty("patchRef", patch.landformPatchId());
                skipped.addProperty("reasonCode", rejection);
                skipped.add("blockBounds", CityStructureCandidateEnvelope.boundsJson(patch.blockBounds()));
                excluded.add(skipped);
                groundedWaterRejection |= "D4_ARRAY_LAYOUT_FRONTIER_WATER_REJECTED_FOR_GROUNDED_STRUCTURE"
                        .equals(rejection);
            }
        }
        patches.sort(Comparator.comparing(LandformPatchSummary::landformPatchId));
        return new TerrainPatchSelection(patches, excluded, groundedWaterRejection);
    }

    private String terrainPatchRejection(LandformPatchSummary patch, TerrainPlacementPolicy policy) {
        if (explicitlyUnusableTerrain(patch)) {
            return "D4_ARRAY_LAYOUT_FRONTIER_STEEP_OR_UNAVAILABLE_PATCH";
        }
        if (!policy.waterAllowed() && waterPatch(patch)) {
            return "D4_ARRAY_LAYOUT_FRONTIER_WATER_REJECTED_FOR_GROUNDED_STRUCTURE";
        }
        return "";
    }

    private boolean waterPatch(LandformPatchSummary patch) {
        if (patch.landformType() == LandformType.WATER) {
            return true;
        }
        return terrainTerms(patch).stream().anyMatch(this::explicitWaterBodyTag);
    }

    private boolean explicitWaterBodyTag(String raw) {
        String term = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        if ("near_water".equals(term) || "nearwater".equals(term) || "waterfront".equals(term)) {
            return false;
        }
        return "water".equals(term) || "water_body".equals(term) || "waterbody".equals(term)
                || "open_water".equals(term) || "surface_water".equals(term)
                || "水体".equals(term) || "水域".equals(term) || "水面".equals(term);
    }

    private boolean explicitlyUnusableTerrain(LandformPatchSummary patch) {
        if (patch.landformType() == LandformType.CLIFF) {
            return true;
        }
        return terrainTerms(patch).stream().anyMatch(term -> term.contains("steep") || term.contains("cliff")
                || term.contains("unavailable") || term.contains("no_build") || term.contains("blocked")
                || term.contains("陡") || term.contains("不可用"));
    }

    private List<String> terrainTerms(LandformPatchSummary patch) {
        List<String> values = new ArrayList<>();
        patch.landformTags().forEach(value -> values.add(value.toLowerCase(Locale.ROOT)));
        patch.overlayTags().forEach(value -> values.add(value.toLowerCase(Locale.ROOT)));
        patch.summaryFacts().forEach(value -> values.add(value.toLowerCase(Locale.ROOT)));
        return values;
    }

    private JsonArray terrainPatchRefs(JsonArray items) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (JsonElement item : items) {
            for (JsonElement ref : array(item.getAsJsonObject(), "sourcePatchRefs")) {
                refs.add(ref.getAsString());
            }
        }
        return stringArray(new ArrayList<>(refs));
    }

    private JsonArray frontierRingPreview(CityLandformReviewPackage reviewPackage, ExpansionContext context) {
        JsonArray rings = new JsonArray();
        for (int ring = 0; ring < context.expansionPolicy().maxExpansionRounds(); ring++) {
            JsonObject value = new JsonObject();
            value.addProperty("frontierRing", frontierRingName(ring));
            value.addProperty("frontierRingIndex", ring);
            value.addProperty("actualBodyGapMin", context.expansionPolicy().actualBodyGapMin()
                    + ring * context.expansionPolicy().frontierExpansionStepBlocks());
            value.addProperty("actualBodyGapMax", context.expansionPolicy().actualBodyGapMax()
                    + ring * context.expansionPolicy().frontierExpansionStepBlocks());
            rings.add(value);
        }
        return rings;
    }

    private String frontierRingName(int ring) {
        return switch (ring) {
            case 0 -> "near";
            case 1 -> "mid";
            default -> "far";
        };
    }

    private BlockBounds gridBounds(PlanningGrid grid) {
        return new BlockBounds(grid.blockMinX(), grid.blockMinZ(), grid.blockMaxX() - 1, grid.blockMaxZ() - 1);
    }

    private BlockPoint clampToGrid(BlockPoint point, PlanningGrid grid) {
        BlockBounds bounds = gridBounds(grid);
        return new BlockPoint(clamp(point.x(), bounds.minX(), bounds.maxX()),
                clamp(point.z(), bounds.minZ(), bounds.maxZ()));
    }

    private LandformPatchSummary patchByRef(CityLandformReviewPackage reviewPackage, String ref) {
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            if (ref.equals(patch.landformPatchId()) || ref.equals(patch.mapLabel())) {
                return patch;
            }
        }
        return null;
    }

    private String normalizedDirection(String raw) {
        String direction = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!EXPANSION_DIRECTIONS.contains(direction)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_EXPANSION_DIRECTION_UNSUPPORTED: " + raw);
        }
        return direction;
    }

    private BlockBounds directionalIntersection(BlockBounds focus, String direction, BlockBounds target) {
        int minX = target.minX();
        int maxX = target.maxX();
        int minZ = target.minZ();
        int maxZ = target.maxZ();
        if (direction.contains("east")) {
            minX = Math.max(minX, focus.maxX() + 1);
        }
        if (direction.contains("west")) {
            maxX = Math.min(maxX, focus.minX() - 1);
        }
        if (direction.contains("north")) {
            maxZ = Math.min(maxZ, focus.minZ() - 1);
        }
        if (direction.contains("south")) {
            minZ = Math.max(minZ, focus.maxZ() + 1);
        }
        return minX > maxX || minZ > maxZ ? null : new BlockBounds(minX, minZ, maxX, maxZ);
    }

    private int capacity(LandformPatchSummary patch, PlanningGrid grid, BlockBounds bounds,
                         List<BlockBounds> occupied) {
        int count = 0;
        for (BlockPoint point : memberCellCenters(List.of(patch), grid, bounds)) {
            boolean blocked = false;
            for (BlockBounds existing : occupied) {
                if (existing.contains(point.x(), point.z())) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                count++;
            }
        }
        return count;
    }

    private BlockPoint nearestAvailablePoint(LandformPatchSummary patch, PlanningGrid grid, BlockBounds bounds,
                                             List<BlockBounds> occupied, BlockPoint reference) {
        BlockPoint best = null;
        long bestDistance = Long.MAX_VALUE;
        for (BlockPoint point : memberCellCenters(List.of(patch), grid, bounds)) {
            boolean blocked = false;
            for (BlockBounds existing : occupied) {
                if (existing.contains(point.x(), point.z())) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) {
                continue;
            }
            long distance = distanceSquared(point, reference);
            if (distance < bestDistance || distance == bestDistance && best != null
                    && (point.x() < best.x() || point.x() == best.x() && point.z() < best.z())) {
                best = point;
                bestDistance = distance;
            }
        }
        return best;
    }

    private double boundsDistance(BlockBounds a, BlockBounds b) {
        int dx = a.maxX() < b.minX() ? b.minX() - a.maxX() : b.maxX() < a.minX() ? a.minX() - b.maxX() : 0;
        int dz = a.maxZ() < b.minZ() ? b.minZ() - a.maxZ() : b.maxZ() < a.minZ() ? a.minZ() - b.maxZ() : 0;
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    private List<String> expansionCandidateSectors(String direction) {
        return switch (direction) {
            case "north" -> List.of("north", "northeast", "northwest", "center", "east");
            case "south" -> List.of("south", "southeast", "southwest", "center", "west");
            case "east" -> List.of("east", "northeast", "southeast", "center", "north");
            case "west" -> List.of("west", "northwest", "southwest", "center", "south");
            case "northeast" -> List.of("northeast", "north", "east", "center", "southeast");
            case "northwest" -> List.of("northwest", "north", "west", "center", "southwest");
            case "southeast" -> List.of("southeast", "south", "east", "center", "northeast");
            case "southwest" -> List.of("southwest", "south", "west", "center", "northwest");
            default -> List.of("center", "north", "south", "east", "west");
        };
    }

    private BlockPoint expansionVariantOrigin(ExpansionContext context, String sector) {
        if (context.focusBounds() == null || context.direction().isBlank()) {
            return sectorPoint(context.availableBounds(), sector);
        }
        BlockBounds available = context.availableBounds();
        BlockPoint variation = sectorPoint(available, sector);
        if ((context.direction().contains("east") || context.direction().contains("west"))
                && (context.direction().contains("north") || context.direction().contains("south"))) {
            return variation;
        }
        // The available bounds already exclude the focus side. Keeping the sector origin inside
        // that bounded area gives line-based layouts distinct complete variants without backfilling.
        return variation;
    }

    private String candidateSignature(JsonArray items) {
        StringBuilder signature = new StringBuilder();
        for (JsonElement elem : items) {
            JsonObject item = elem.getAsJsonObject();
            JsonObject point = object(item, "anchorBlock");
            signature.append(stringValue(item, "structureId")).append('@')
                    .append(intValue(point, "x", 0)).append(',').append(intValue(point, "z", 0)).append(';');
        }
        return signature.toString();
    }

    private double candidateScore(BuildResult build) {
        return build.items().size() * 100.0 - build.warnings().size() * 5.0;
    }

    private JsonObject candidateScoreBreakdown(BuildResult build) {
        JsonObject score = new JsonObject();
        score.addProperty("completeItemCount", build.items().size());
        score.addProperty("warningCount", build.warnings().size());
        score.addProperty("score", candidateScore(build));
        return score;
    }

    private JsonArray singleStringArray(String value) {
        JsonArray values = new JsonArray();
        values.add(value);
        return values;
    }

    private JsonObject normalizePlan(JsonObject source, String cityId) {
        JsonObject plan = source.deepCopy();
        String rawSchema = stringValue(plan, "schemaVersion", PLAN_SCHEMA);
        String rawMode = stringValue(plan, "planningMode", "");
        boolean v04 = PLAN_SCHEMA_V04.equals(rawSchema) || PLANNING_MODE_V04.equals(rawMode);
        boolean v03 = PLAN_SCHEMA_V03.equals(rawSchema) || PLANNING_MODE_V03.equals(rawMode);
        plan.addProperty("schemaVersion", v04 ? PLAN_SCHEMA_V04 : v03 ? PLAN_SCHEMA_V03 : PLAN_SCHEMA);
        plan.addProperty("planningMode", v04 ? PLANNING_MODE_V04 : v03 ? PLANNING_MODE_V03 : PLANNING_MODE_V02);
        if (stringValue(plan, "cityId").isBlank()) {
            plan.addProperty("cityId", cityId);
        }
        if (!cityId.equals(stringValue(plan, "cityId"))) {
            throw new IllegalArgumentException("ArrayLayoutPlan cityId mismatch.");
        }
        if (!plan.has("cityScale")) {
            plan.addProperty("cityScale", "town");
        }
        if (!plan.has("layoutPlans")) {
            plan.add("layoutPlans", new JsonArray());
        }
        if (v04 && !array(plan, "layoutPlans").isEmpty()) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_V04_ONE_THEME_PER_ROUND: "
                    + "create the v0.4 loop with an empty layoutPlans array and submit one candidate item per round.");
        }
        return plan;
    }

    private JsonObject normalizeBaseAnchorPlan(JsonObject basePlan, String cityId) {
        JsonObject plan = basePlan == null ? new JsonObject() : basePlan.deepCopy();
        plan.addProperty("schemaVersion", CityStructureAnchorPlanner.PLAN_SCHEMA);
        if (stringValue(plan, "cityId").isBlank()) {
            plan.addProperty("cityId", cityId);
        }
        if (!plan.has("anchors") || !plan.get("anchors").isJsonArray()) {
            plan.add("anchors", new JsonArray());
        }
        return plan;
    }

    private JsonArray occupiedFromAnchorMap(JsonObject anchorMap,
                                            Map<String, CityStructureProfileCatalog.StructureProfile> profiles,
                                            CityStructureEnvelopeFacts envelopeFacts) {
        JsonArray occupied = new JsonArray();
        for (JsonElement elem : array(anchorMap, "anchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject anchor = elem.getAsJsonObject();
            JsonObject bounds = object(anchor, "collisionEnvelope");
            if (bounds.size() == 0) {
                bounds = object(anchor, "reservedEnvelope");
            }
            if (bounds.size() == 0) {
                bounds = object(anchor, "plannedFootprint");
            }
            if (bounds.size() == 0) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("source", "base_structure_anchor_map");
            obj.addProperty("anchorId", stringValue(anchor, "anchorId"));
            obj.addProperty("envelopeType", "collisionEnvelope");
            obj.add("blockBounds", bounds.deepCopy());
            BodyBoundsResolution body = baseAnchorBodyBounds(anchor, profiles, envelopeFacts, bounds);
            obj.add("bodyBounds", CityStructureCandidateEnvelope.boundsJson(body.bounds()));
            obj.addProperty("bodyEnvelopeSource", body.source());
            occupied.add(obj);
        }
        return occupied;
    }

    private BodyBoundsResolution baseAnchorBodyBounds(JsonObject anchor,
                                                       Map<String, CityStructureProfileCatalog.StructureProfile> profiles,
                                                       CityStructureEnvelopeFacts envelopeFacts,
                                                       JsonObject fallbackCollision) {
        BlockPoint anchorBlock = point(anchor, "anchorBlock", null);
        JsonObject embeddedFact = object(anchor, "structureEnvelopeFact");
        if (anchorBlock != null && embeddedFact.size() > 0) {
            BlockBounds local = d2RecommendedLocalEnvelope(embeddedFact);
            if (local != null) {
                return new BodyBoundsResolution(fromLocal(anchorBlock, local),
                        "d2_structure_envelope_fact");
            }
        }
        CityStructureProfileCatalog.StructureProfile profile = profiles.get(stringValue(anchor, "structureId"));
        if (anchorBlock != null && profile != null) {
            CityStructureCandidateEnvelope.Estimate estimate = CityStructureCandidateEnvelope.estimate(anchorBlock,
                    profile, envelopeFacts == null ? CityStructureEnvelopeFacts.empty() : envelopeFacts,
                    anchor);
            if (!estimate.requiredFactsMissing()) {
                return new BodyBoundsResolution(estimate.plannedFootprint(), "d2_envelope_facts_or_profile_fallback");
            }
        }
        JsonObject actual = object(anchor, "actualFootprint");
        if (actual.size() > 0) {
            return new BodyBoundsResolution(CityStructureCandidateEnvelope.bounds(actual), "actual_footprint");
        }
        JsonObject planned = object(anchor, "plannedFootprint");
        if (planned.size() > 0) {
            return new BodyBoundsResolution(CityStructureCandidateEnvelope.bounds(planned), "static_planned_footprint_fallback");
        }
        return new BodyBoundsResolution(CityStructureCandidateEnvelope.bounds(fallbackCollision),
                "collision_envelope_fallback");
    }

    private BlockBounds d2RecommendedLocalEnvelope(JsonObject fact) {
        String source = stringValue(fact, "collisionEnvelopeSource", "localEnvelopeP95");
        if ("dominantBBoxGroup".equals(source)) {
            JsonObject dominant = object(fact, "dominantBBoxGroup");
            JsonObject local = object(dominant, "localEnvelope");
            if (local.size() > 0) {
                return CityStructureCandidateEnvelope.bounds(local);
            }
        }
        JsonObject local = "stableMaxEnvelope".equals(source)
                ? object(fact, "stableMaxEnvelope") : object(fact, "localEnvelopeP95");
        return local.size() == 0 ? null : CityStructureCandidateEnvelope.bounds(local);
    }

    private BlockBounds fromLocal(BlockPoint anchorBlock, BlockBounds local) {
        int originX = Math.floorDiv(anchorBlock.x(), 16) * 16;
        int originZ = Math.floorDiv(anchorBlock.z(), 16) * 16;
        return new BlockBounds(originX + local.minX(), originZ + local.minZ(),
                originX + local.maxX(), originZ + local.maxZ());
    }

    private JsonObject patchAvailability(CityLandformReviewPackage reviewPackage, JsonArray occupiedEnvelopes) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", PATCH_AVAILABILITY_SCHEMA);
        obj.addProperty("cityId", reviewPackage.cityId());
        JsonArray patches = new JsonArray();
        List<BlockBounds> occupied = occupiedBounds(occupiedEnvelopes);
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            int overlaps = 0;
            for (BlockBounds bounds : occupied) {
                if (patch.blockBounds().overlaps(bounds)) {
                    overlaps++;
                }
            }
            JsonObject patchJson = new JsonObject();
            patchJson.addProperty("patchRef", patch.landformPatchId());
            patchJson.addProperty("mapLabel", patch.mapLabel());
            patchJson.add("blockBounds", CityStructureCandidateEnvelope.boundsJson(patch.blockBounds()));
            patchJson.addProperty("occupiedOverlapCount", overlaps);
            patchJson.addProperty("available", overlaps < 12);
            JsonArray sectors = new JsonArray();
            SECTORS.forEach(sectors::add);
            patchJson.add("availableSectors", sectors);
            patches.add(patchJson);
        }
        obj.add("patches", patches);
        return obj;
    }

    private JsonObject executionTrace(JsonArray items, String planningMode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", traceSchemaForMode(planningMode));
        obj.addProperty("planningMode", planningMode);
        obj.add("items", items);
        return obj;
    }

    private JsonObject zones(JsonArray arrayZones, String planningMode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", ZONES_SCHEMA);
        obj.addProperty("planningMode", planningMode);
        obj.add("arrayZones", arrayZones);
        return obj;
    }

    private void appendTrace(JsonObject state, JsonObject itemTrace) {
        JsonObject trace = object(state, "executionTrace");
        array(trace, "items").add(itemTrace);
    }

    private JsonObject traceForRejectedItem(JsonObject item, JsonArray hardBlocks, JsonArray warnings) {
        JsonObject trace = new JsonObject();
        trace.addProperty("arrayId", stringValue(item, "arrayId", "array"));
        trace.addProperty("plannerType", stringValue(item, "plannerType", "compound_cluster"));
        trace.addProperty("status", "hard_blocked");
        trace.addProperty("placedItemCount", 0);
        trace.add("hardBlocks", hardBlocks.deepCopy());
        trace.add("warnings", warnings.deepCopy());
        return trace;
    }

    private JsonObject quality(List<String> hardBlocks, List<String> warnings, List<String> needsReview, int score) {
        JsonObject quality = new JsonObject();
        quality.addProperty("passed", hardBlocks.isEmpty());
        quality.addProperty("score", hardBlocks.isEmpty() ? score : 0);
        quality.add("hardBlocks", stringArray(hardBlocks));
        quality.add("warnings", stringArray(warnings));
        quality.add("needsReview", stringArray(needsReview));
        JsonObject metrics = new JsonObject();
        metrics.addProperty("hardBlockCount", hardBlocks.size());
        metrics.addProperty("warningCount", warnings.size());
        quality.add("metrics", metrics);
        return quality;
    }

    private List<LandformPatchSummary> sourcePatches(JsonObject item, CityLandformReviewPackage reviewPackage) {
        Map<String, LandformPatchSummary> patches = new LinkedHashMap<>();
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            patches.put(patch.landformPatchId(), patch);
            patches.put(patch.mapLabel(), patch);
        }
        List<LandformPatchSummary> result = new ArrayList<>();
        for (JsonElement elem : array(item, "candidatePatchRefs")) {
            String ref = elem.getAsString();
            LandformPatchSummary patch = patches.get(ref);
            if (patch != null && !result.contains(patch)) {
                result.add(patch);
            }
        }
        return result;
    }

    private List<SubZone> subZones(JsonObject item, BlockBounds parentBounds, int requestedCount) {
        JsonArray explicit = array(item, "subZones");
        if (!explicit.isEmpty()) {
            List<SubZone> result = new ArrayList<>();
            int index = 0;
            for (JsonElement elem : explicit) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                index++;
                JsonObject obj = elem.getAsJsonObject();
                BlockBounds bounds = CityStructureCandidateEnvelope.bounds(object(obj, "blockBounds"));
                result.add(new SubZone(stringValue(obj, "subZoneId",
                        "subzone_" + String.format(Locale.ROOT, "%02d", index)), clampBounds(bounds, parentBounds)));
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        int count = Math.max(1, requestedCount);
        String policy = stringValue(item, "subZonePolicy", stringValue(item, "subZoneLayout", "grid"));
        if ("linear_blocks".equals(policy)) {
            return linearSubZones(parentBounds, count);
        }
        if ("radial_quadrants".equals(policy)) {
            return gridSubZones(parentBounds, Math.max(4, count));
        }
        return gridSubZones(parentBounds, count);
    }

    private List<SubZone> gridSubZones(BlockBounds bounds, int count) {
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
        int rows = Math.max(1, (int) Math.ceil(count / (double) columns));
        List<SubZone> result = new ArrayList<>();
        int index = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                if (index >= count) {
                    break;
                }
                int minX = bounds.minX() + col * bounds.widthBlocks() / columns;
                int maxX = bounds.minX() + (col + 1) * bounds.widthBlocks() / columns - 1;
                int minZ = bounds.minZ() + row * bounds.heightBlocks() / rows;
                int maxZ = bounds.minZ() + (row + 1) * bounds.heightBlocks() / rows - 1;
                result.add(new SubZone("subzone_" + String.format(Locale.ROOT, "%02d", ++index),
                        new BlockBounds(minX, minZ, Math.max(minX, maxX), Math.max(minZ, maxZ))));
            }
        }
        return result;
    }

    private List<SubZone> linearSubZones(BlockBounds bounds, int count) {
        List<SubZone> result = new ArrayList<>();
        boolean xAxis = bounds.widthBlocks() >= bounds.heightBlocks();
        for (int i = 0; i < count; i++) {
            int minX = xAxis ? bounds.minX() + i * bounds.widthBlocks() / count : bounds.minX();
            int maxX = xAxis ? bounds.minX() + (i + 1) * bounds.widthBlocks() / count - 1 : bounds.maxX();
            int minZ = xAxis ? bounds.minZ() : bounds.minZ() + i * bounds.heightBlocks() / count;
            int maxZ = xAxis ? bounds.maxZ() : bounds.minZ() + (i + 1) * bounds.heightBlocks() / count - 1;
            result.add(new SubZone("subzone_" + String.format(Locale.ROOT, "%02d", i + 1),
                    new BlockBounds(minX, minZ, Math.max(minX, maxX), Math.max(minZ, maxZ))));
        }
        return result;
    }

    private SubZone childSubZone(JsonObject child, List<SubZone> subZones, Map<String, SubZone> byId, int childIndex) {
        String explicit = stringValue(child, "targetSubZoneId", stringValue(child, "subZoneId", ""));
        if (!explicit.isBlank()) {
            SubZone subZone = byId.get(explicit);
            if (subZone == null) {
                throw new IllegalArgumentException("D4_ARRAY_LAYOUT_SUBZONE_NOT_FOUND: " + explicit);
            }
            return subZone;
        }
        return subZones.get(Math.floorMod(childIndex - 1, subZones.size()));
    }

    private JsonArray subZonesJson(List<SubZone> subZones) {
        JsonArray array = new JsonArray();
        for (SubZone subZone : subZones) {
            JsonObject obj = new JsonObject();
            obj.addProperty("subZoneId", subZone.subZoneId());
            obj.add("blockBounds", CityStructureCandidateEnvelope.boundsJson(subZone.bounds()));
            obj.add("centerBlock", subZone.bounds().center().asJson());
            array.add(obj);
        }
        return array;
    }

    private BlockBounds clampBounds(BlockBounds value, BlockBounds outer) {
        int minX = clamp(value.minX(), outer.minX(), outer.maxX());
        int minZ = clamp(value.minZ(), outer.minZ(), outer.maxZ());
        int maxX = clamp(value.maxX(), minX, outer.maxX());
        int maxZ = clamp(value.maxZ(), minZ, outer.maxZ());
        return new BlockBounds(minX, minZ, maxX, maxZ);
    }

    private LandformPatchSummary pointPatch(List<LandformPatchSummary> patches, PlanningGrid grid, BlockPoint point) {
        return patches.stream()
                .filter(patch -> patchContains(patch, grid, point))
                .findFirst()
                .orElse(null);
    }

    private boolean patchContains(LandformPatchSummary patch, PlanningGrid grid, BlockPoint point) {
        if (patch.memberCells().isEmpty()) {
            return patch.blockBounds().contains(point.x(), point.z());
        }
        int step = grid.cellStepBlocks();
        for (PatchMemberCell cell : patch.memberCells()) {
            if (point.x() >= cell.blockMinX() && point.x() < cell.blockMinX() + step
                    && point.z() >= cell.blockMinZ() && point.z() < cell.blockMinZ() + step) {
                return true;
            }
        }
        return false;
    }

    private List<LandformPatchSummary> orderedPatches(LandformPatchSummary pivot, List<LandformPatchSummary> patches) {
        List<LandformPatchSummary> ordered = new ArrayList<>();
        ordered.add(pivot);
        patches.stream()
                .filter(patch -> !patch.landformPatchId().equals(pivot.landformPatchId()))
                .sorted(Comparator.comparing(LandformPatchSummary::landformPatchId))
                .forEach(ordered::add);
        return ordered;
    }

    private boolean isShoreLike(LandformPatchSummary patch) {
        return patch.landformType() == LandformType.SHORE
                || patch.landformTags().stream().anyMatch(tag -> tag.contains("shore") || tag.contains("water"))
                || patch.overlayTags().stream().anyMatch(tag -> tag.contains("shore") || tag.contains("water"));
    }

    private BlockPoint sectorPoint(BlockBounds bounds, String rawSector) {
        String sector = SECTORS.contains(rawSector) ? rawSector : "center";
        double fx = switch (sector) {
            case "west", "northwest", "southwest" -> 0.25;
            case "east", "northeast", "southeast" -> 0.75;
            default -> 0.50;
        };
        double fz = switch (sector) {
            case "north", "northeast", "northwest" -> 0.25;
            case "south", "southeast", "southwest" -> 0.75;
            default -> 0.50;
        };
        return new BlockPoint(bounds.minX() + (int) Math.round((bounds.widthBlocks() - 1) * fx),
                bounds.minZ() + (int) Math.round((bounds.heightBlocks() - 1) * fz));
    }

    private BlockPoint point(JsonObject source, String key, BlockPoint fallback) {
        JsonObject point = object(source, key);
        return point.has("x") && point.has("z")
                ? new BlockPoint(point.get("x").getAsInt(), point.get("z").getAsInt()) : fallback;
    }

    private JsonArray patchRefs(LandformPatchSummary patch) {
        JsonArray refs = new JsonArray();
        refs.add(patch.landformPatchId());
        if (!patch.mapLabel().isBlank() && !patch.mapLabel().equals(patch.landformPatchId())) {
            refs.add(patch.mapLabel());
        }
        return refs;
    }

    private JsonArray patchRefs(List<LandformPatchSummary> patches) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (LandformPatchSummary patch : patches) {
            refs.add(patch.landformPatchId());
        }
        return stringArray(new ArrayList<>(refs));
    }

    private JsonArray intentTerms(String arrayId, String role, String plannerType) {
        JsonArray terms = new JsonArray();
        terms.add("city.array_layout_loop");
        terms.add("array." + arrayId);
        if (!role.isBlank()) {
            terms.add("role." + role);
        }
        terms.add("planner." + plannerType);
        return terms;
    }

    private JsonArray occupiedBoundsJson(List<BlockBounds> bounds) {
        JsonArray array = new JsonArray();
        for (BlockBounds bound : bounds) {
            JsonObject obj = new JsonObject();
            obj.add("blockBounds", CityStructureCandidateEnvelope.boundsJson(bound));
            array.add(obj);
        }
        return array;
    }

    private List<BlockBounds> occupiedBounds(JsonArray array) {
        List<BlockBounds> bounds = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject obj = elem.getAsJsonObject();
            JsonObject source = obj.has("blockBounds") && obj.get("blockBounds").isJsonObject()
                    ? obj.getAsJsonObject("blockBounds") : obj;
            bounds.add(CityStructureCandidateEnvelope.bounds(source));
        }
        return bounds;
    }

    private boolean overlapsAny(List<BlockBounds> existing, BlockBounds candidate) {
        for (BlockBounds bounds : existing) {
            if (bounds.overlaps(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean gridContains(PlanningGrid grid, BlockBounds bounds) {
        return grid.containsBlock(bounds.minX(), bounds.minZ())
                && grid.containsBlock(bounds.maxX(), bounds.maxZ());
    }

    private BlockBounds union(BlockBounds a, BlockBounds b) {
        return a == null ? b : CityStructureCandidateEnvelope.union(a, b);
    }

    private BlockBounds nonNullBounds(BlockBounds bounds) {
        return bounds == null ? new BlockBounds(0, 0, 0, 0) : bounds;
    }

    private int maxArrayPlans(JsonObject plan) {
        int configured = intValue(plan, "maxArrayPlans", 0);
        if (configured > 0) {
            return configured;
        }
        return switch (stringValue(plan, "cityScale", "town")) {
            case "starter_village", "hamlet" -> 2;
            case "village" -> 4;
            case "city" -> 12;
            default -> 7;
        };
    }

    private int countValue(JsonObject item, String key, int defaultValue) {
        JsonObject policy = object(item, "countPolicy");
        return intValue(policy, key, intValue(item, key, defaultValue));
    }

    private boolean hasCountValue(JsonObject item, String key) {
        JsonObject policy = object(item, "countPolicy");
        return policy.has(key) && !policy.get(key).isJsonNull()
                || item.has(key) && !item.get(key).isJsonNull();
    }

    private int minCount(JsonObject item, int targetCount) {
        return hasCountValue(item, "minCount") ? countValue(item, "minCount", targetCount) : targetCount;
    }

    private int requiredCount(List<DesiredItem> desiredItems) {
        int count = 0;
        for (DesiredItem item : desiredItems) {
            if ("required".equals(item.kind())) {
                count++;
            }
        }
        return count;
    }

    private JsonObject rejection(DesiredItem desired, BlockPoint point, String reason) {
        JsonObject obj = new JsonObject();
        obj.addProperty("itemId", desired.itemId());
        obj.addProperty("structureId", desired.structureId());
        obj.add("anchorBlock", point.asJson());
        obj.addProperty("reason", reason);
        return obj;
    }

    private void appendAnchors(JsonArray target, Set<String> anchorIds, JsonObject plan,
                               String arrayKey, String source) {
        for (JsonElement elem : array(plan, arrayKey)) {
            JsonObject anchor = elem.getAsJsonObject();
            String anchorId = requiredString(anchor, "anchorId");
            if (!anchorIds.add(anchorId)) {
                throw new IllegalArgumentException("D4_ARRAY_LAYOUT_DUPLICATE_ANCHOR_ID: "
                        + anchorId + " from " + source + ".");
            }
            target.add(anchor.deepCopy());
        }
    }

    private void appendAll(JsonArray target, JsonArray source) {
        for (JsonElement elem : source) {
            target.add(elem.deepCopy());
        }
    }

    private boolean validStateSchema(String schema) {
        return STATE_SCHEMA.equals(schema) || STATE_SCHEMA_V03.equals(schema) || STATE_SCHEMA_V04.equals(schema);
    }

    private String planningMode(JsonObject obj) {
        String mode = stringValue(obj, "planningMode", "");
        if (PLANNING_MODE_V04.equals(mode)) {
            return PLANNING_MODE_V04;
        }
        return PLANNING_MODE_V03.equals(mode) ? PLANNING_MODE_V03 : PLANNING_MODE_V02;
    }

    private String traceSchema(JsonObject obj) {
        return traceSchemaForMode(planningMode(obj));
    }

    private String traceSchemaForMode(String planningMode) {
        return switch (planningMode) {
            case PLANNING_MODE_V04 -> TRACE_SCHEMA_V04;
            case PLANNING_MODE_V03 -> TRACE_SCHEMA_V03;
            default -> TRACE_SCHEMA;
        };
    }

    private List<String> toStrings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement elem : array) {
            values.add(elem.getAsString());
        }
        return values;
    }

    private JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private String stateId(int iteration) {
        return "loop_state_" + String.format(Locale.ROOT, "%04d", iteration);
    }

    private String safeId(String value, int index) {
        String normalized = value == null ? "" : value.replaceAll("[^a-zA-Z0-9_.-]", "_");
        if (normalized.isBlank()) {
            normalized = String.format(Locale.ROOT, "%02d", index);
        }
        return normalized;
    }

    private long stableSeed(String value) {
        long h = 1125899906842597L;
        for (int i = 0; i < value.length(); i++) {
            h = 31 * h + value.charAt(i);
        }
        return h;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private JsonObject timing(long started) {
        JsonObject obj = new JsonObject();
        obj.addProperty("totalMs", (System.nanoTime() - started) / 1_000_000L);
        return obj;
    }

    private JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private JsonObject object(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : new JsonObject();
    }

    private boolean containsString(JsonArray array, String value) {
        for (JsonElement elem : array) {
            if (value.equals(elem.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private String stringValue(JsonObject obj, String key) {
        return stringValue(obj, key, "");
    }

    private String stringValue(JsonObject obj, String key, String defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : defaultValue;
    }

    private int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsInt() : defaultValue;
    }

    private double doubleValue(JsonObject obj, String key, double defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsDouble() : defaultValue;
    }

    private boolean booleanValue(JsonObject obj, String key, boolean defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsBoolean() : defaultValue;
    }

    private record ShapeGrid(int rows, int columns) {
    }

    public record CreateResult(JsonObject loopState,
                               JsonObject arrayLayoutPlan,
                               JsonObject executionTrace,
                               JsonArray occupiedEnvelopes,
                               JsonObject patchAvailability,
                               JsonObject functionalArrayZones,
                               JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.addProperty("planningMode", loopState != null && loopState.has("planningMode")
                    ? loopState.get("planningMode").getAsString() : PLANNING_MODE_V02);
            obj.add("arrayLayoutLoopState", loopState.deepCopy());
            obj.add("arrayLayoutPlan", arrayLayoutPlan.deepCopy());
            obj.add("executionTrace", executionTrace.deepCopy());
            obj.add("occupiedEnvelopes", occupiedEnvelopes.deepCopy());
            obj.add("patchAvailability", patchAvailability.deepCopy());
            obj.add("functionalArrayZones", functionalArrayZones.deepCopy());
            obj.add("qualityReport", qualityReport.deepCopy());
            return obj;
        }
    }

    public record ExecuteResult(JsonObject loopState,
                                JsonObject arrayLayoutPlan,
                                JsonObject executionTrace,
                                JsonArray occupiedEnvelopes,
                                JsonObject patchAvailability,
                                JsonObject functionalArrayZones,
                                JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.addProperty("planningMode", loopState != null && loopState.has("planningMode")
                    ? loopState.get("planningMode").getAsString() : PLANNING_MODE_V02);
            obj.add("arrayLayoutLoopState", loopState.deepCopy());
            obj.add("arrayLayoutPlan", arrayLayoutPlan.deepCopy());
            obj.add("executionTrace", executionTrace.deepCopy());
            obj.add("occupiedEnvelopes", occupiedEnvelopes.deepCopy());
            obj.add("patchAvailability", patchAvailability.deepCopy());
            obj.add("functionalArrayZones", functionalArrayZones.deepCopy());
            obj.add("qualityReport", qualityReport.deepCopy());
            return obj;
        }
    }

    public record FinalizeResult(JsonObject structureAnchorPlan, JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.addProperty("planningMode", PLANNING_MODE_V02);
            obj.add("structureAnchorPlan", structureAnchorPlan.deepCopy());
            obj.add("qualityReport", qualityReport.deepCopy());
            return obj;
        }
    }

    public record ExpansionSpaceResult(JsonObject expansionSpace) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", true);
            obj.add("expansionSpace", expansionSpace.deepCopy());
            return obj;
        }
    }

    public record ExpansionCandidateSetResult(JsonObject candidateSet, JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.addProperty("planningMode", PLANNING_MODE_V04);
            obj.add("arrayExpansionCandidateSet", candidateSet.deepCopy());
            obj.add("qualityReport", qualityReport.deepCopy());
            return obj;
        }
    }

    public record ExpansionSelectionResult(JsonObject loopState, JsonObject selectedCandidate,
                                           JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.addProperty("planningMode", PLANNING_MODE_V04);
            obj.add("arrayLayoutLoopState", loopState.deepCopy());
            obj.add("selectedArrayCandidate", selectedCandidate.deepCopy());
            obj.add("executionTrace", objectForResult(loopState, "executionTrace"));
            obj.add("occupiedEnvelopes", arrayForResult(loopState, "occupiedEnvelopes"));
            obj.add("functionalArrayZones", objectForResult(loopState, "functionalArrayZones"));
            obj.add("remainingExpansionSpace", objectForResult(loopState, "remainingExpansionSpace"));
            obj.add("qualityReport", qualityReport.deepCopy());
            return obj;
        }

        private static JsonObject objectForResult(JsonObject source, String key) {
            return source != null && source.has(key) && source.get(key).isJsonObject()
                    ? source.getAsJsonObject(key).deepCopy() : new JsonObject();
        }

        private static JsonArray arrayForResult(JsonObject source, String key) {
            return source != null && source.has(key) && source.get(key).isJsonArray()
                    ? source.getAsJsonArray(key).deepCopy() : new JsonArray();
        }
    }

    private record DesiredItem(String itemId, String structureId, String kind, String failurePolicy) {
    }

    private record Accepted(DesiredItem desired,
                            BlockPoint point,
                            LandformPatchSummary patch,
                            CityStructureCandidateEnvelope.Estimate estimate) {
    }

    private record BuildResult(JsonArray items,
                               JsonArray anchors,
                               JsonArray zones,
                               JsonObject trace,
                               JsonArray hardBlocks,
                               JsonArray warnings) {
    }

    private record SubZone(String subZoneId, BlockBounds bounds) {
    }

    private record GlobalPatchCandidate(LandformPatchSummary patch,
                                        int remainingCapacity,
                                        BlockPoint entryPoint) {
        private boolean available() {
            return remainingCapacity > 0 && entryPoint != null;
        }
    }

    private record ExpansionContext(JsonObject focusRef,
                                    BlockBounds focusBounds,
                                    BlockBounds focusBodyBounds,
                                    String direction,
                                    LandformPatchSummary targetPatch,
                                    BlockBounds availableBounds,
                                    BlockPoint entryPoint,
                                    boolean newFunctionalArea,
                                    boolean continuousFrontier,
                                    ExpansionPolicy expansionPolicy) {
    }

    private record ExpansionPolicy(int actualBodyGapMin,
                                   int actualBodyGapMax,
                                   int frontierExpansionStepBlocks,
                                   int maxExpansionRounds) {
        private static ExpansionPolicy defaults() {
            return new ExpansionPolicy(16, 30, 16, 3);
        }

        private JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("actualBodyGapMin", actualBodyGapMin);
            obj.addProperty("actualBodyGapMax", actualBodyGapMax);
            obj.addProperty("frontierExpansionStepBlocks", frontierExpansionStepBlocks);
            obj.addProperty("frontierMaxExpansionRounds", maxExpansionRounds);
            return obj;
        }
    }

    private record FrontierReference(BlockBounds localFootprint, int spacingBlocks) {
    }

    private record FrontierGapValidation(boolean accepted, int actualBodyGapBlocks) {
    }

    private record TerrainPlacementPolicy(boolean waterAllowed) {
    }

    private record TerrainPatchSelection(List<LandformPatchSummary> acceptedPatches,
                                         JsonArray excludedPatches,
                                         boolean hasGroundedWaterRejection) {
    }

    private record BodyBoundsResolution(BlockBounds bounds, String source) {
    }
}
