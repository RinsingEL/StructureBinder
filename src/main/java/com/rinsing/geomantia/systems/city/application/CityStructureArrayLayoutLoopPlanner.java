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
    public static final String STATE_SCHEMA = "city_d4_array_layout_loop_state.v0.2";
    public static final String STATE_SCHEMA_V03 = "city_d4_array_layout_loop_state.v0.3";
    public static final String TRACE_SCHEMA = "city_d4_array_layout_execution_trace.v0.2";
    public static final String TRACE_SCHEMA_V03 = "city_d4_array_layout_execution_trace.v0.3";
    public static final String OCCUPIED_SCHEMA = "city_d4_array_occupied_field.v0.2";
    public static final String PATCH_AVAILABILITY_SCHEMA = "city_d4_array_patch_availability.v0.2";
    public static final String ZONES_SCHEMA = "city_d4_functional_array_zones.v0.2";
    public static final String PLANNING_MODE_V02 = "array_layout_loop_v0_2";
    public static final String PLANNING_MODE_V03 = "array_layout_loop_v0_3";

    private static final Set<String> PLANNER_TYPES = Set.of(
            "plaza_ring", "compound_cluster", "guide_line_dual_side", "riverbank_dual_side", "contour_band",
            "composite_array");
    private static final List<String> SECTORS = List.of(
            "center", "north", "south", "east", "west", "northeast", "northwest", "southeast", "southwest");

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
        JsonArray occupied = occupiedFromAnchorMap(normalizedBaseMap);
        String planningMode = planningMode(normalizedPlan);
        JsonObject state = new JsonObject();
        state.addProperty("schemaVersion", PLANNING_MODE_V03.equals(planningMode) ? STATE_SCHEMA_V03 : STATE_SCHEMA);
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
                    occupiedBounds(array(state, "occupiedEnvelopes")))
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
        int spacing = spacing(item, desiredItems, profiles);
        BlockBounds effectiveBounds = placementBounds == null ? pivot.blockBounds() : placementBounds;
        BlockPoint start = sectorPoint(effectiveBounds, stringValue(item, "startSector", "center"));
        List<BlockPoint> guidePoints = placementBounds == null
                ? rawPoints(plannerType, item, pivot, sourcePatches, reviewPackage.grid(),
                start, spacing, Math.max(1, desiredItems.size()))
                : rawPointsInBounds(plannerType, item, effectiveBounds, start, spacing,
                Math.max(1, desiredItems.size()));
        List<BlockPoint> rawPoints = memberCellCandidatePoints(sourcePatches, reviewPackage.grid(),
                effectiveBounds, start, guidePoints);
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
                                                List<BlockBounds> occupied) {
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
        List<SubZone> subZones = subZones(item, pivot.blockBounds(),
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
            occ.addProperty("envelopeType", "estimatedCollisionEnvelope");
            occ.add("blockBounds", object(item, "estimatedCollisionEnvelope").deepCopy());
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
            case "plaza_ring" -> plazaRing(points, start, spacing, requested);
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
            case "plaza_ring" -> plazaRing(points, start, spacing, requested);
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
        for (int step = 0; step <= steps; step++) {
            int along = spacing / 2 + step * Math.max(8, spacing / 2);
            int side = step % 2 == 0 ? 1 : -1;
            int cross = side * Math.max(8, spacing / 3);
            int x = xAxis ? b.minX() + Math.min(length - 1, along) : start.x() + cross;
            int z = xAxis ? start.z() + cross : b.minZ() + Math.min(length - 1, along);
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
                        Map<String, CityStructureProfileCatalog.StructureProfile> profiles) {
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
            CityStructureProfileCatalog.Footprint footprint = profile.planningFootprint();
            if (footprint.valid()) {
                max = Math.max(max, Math.max(footprint.widthBlocks(), footprint.depthBlocks())
                        + CityStructureCandidateEnvelope.DEFAULT_SMALL_CLEARANCE_BLOCKS * 2);
            } else if (profile.jigsawLike()) {
                int radius = profile.jigsawExpansionRadius(CityStructureCandidateEnvelope.DEFAULT_JIGSAW_RADIUS_BLOCKS)
                        + CityStructureCandidateEnvelope.DEFAULT_CLEARANCE_BLOCKS;
                max = Math.max(max, radius * 2);
            }
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

    private JsonObject normalizePlan(JsonObject source, String cityId) {
        JsonObject plan = source.deepCopy();
        String rawSchema = stringValue(plan, "schemaVersion", PLAN_SCHEMA);
        String rawMode = stringValue(plan, "planningMode", "");
        boolean v03 = PLAN_SCHEMA_V03.equals(rawSchema) || PLANNING_MODE_V03.equals(rawMode);
        plan.addProperty("schemaVersion", v03 ? PLAN_SCHEMA_V03 : PLAN_SCHEMA);
        plan.addProperty("planningMode", v03 ? PLANNING_MODE_V03 : PLANNING_MODE_V02);
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

    private JsonArray occupiedFromAnchorMap(JsonObject anchorMap) {
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
            occupied.add(obj);
        }
        return occupied;
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
        obj.addProperty("schemaVersion", PLANNING_MODE_V03.equals(planningMode) ? TRACE_SCHEMA_V03 : TRACE_SCHEMA);
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

    private JsonArray patchRefs(LandformPatchSummary patch) {
        JsonArray refs = new JsonArray();
        refs.add(patch.landformPatchId());
        if (!patch.mapLabel().isBlank() && !patch.mapLabel().equals(patch.landformPatchId())) {
            refs.add(patch.mapLabel());
        }
        return refs;
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
        return STATE_SCHEMA.equals(schema) || STATE_SCHEMA_V03.equals(schema);
    }

    private String planningMode(JsonObject obj) {
        String mode = stringValue(obj, "planningMode", "");
        return PLANNING_MODE_V03.equals(mode) ? PLANNING_MODE_V03 : PLANNING_MODE_V02;
    }

    private String traceSchema(JsonObject obj) {
        return PLANNING_MODE_V03.equals(planningMode(obj)) ? TRACE_SCHEMA_V03 : TRACE_SCHEMA;
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
}
