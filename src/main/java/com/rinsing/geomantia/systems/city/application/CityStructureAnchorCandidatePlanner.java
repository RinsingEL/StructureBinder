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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class CityStructureAnchorCandidatePlanner {
    public static final String DESIGN_SLOT_PLAN_SCHEMA = "city_d4_design_slot_plan.v0.1";
    public static final String CANDIDATE_SET_SCHEMA = "city_d4_anchor_candidate_set.v0.1";
    public static final String SELECTION_PLAN_SCHEMA = "city_d4_anchor_selection_plan.v0.1";

    private static final int MAX_CANDIDATES_PER_SLOT = 5;
    private static final int DEFAULT_SMALL_CLEARANCE_BLOCKS = 4;
    private static final int DEFAULT_CLEARANCE_BLOCKS = 8;
    private static final int DEFAULT_VEGETATION_MARGIN_BLOCKS = 8;
    private static final int DEFAULT_ROAD_ACCESS_MARGIN_BLOCKS = 6;
    private static final int DEFAULT_JIGSAW_RADIUS_BLOCKS = 96;

    public Result plan(Path baseDirectory,
                       CityLandformReviewPackage reviewPackage,
                       JsonObject terraSenseProfileSource,
                       JsonObject designSlotPlan,
                       CityStructureEnvelopeFacts envelopeFacts) throws IOException {
        long started = System.nanoTime();
        if (reviewPackage == null) {
            throw new IllegalArgumentException("CityLandformReviewPackage is required for D4 candidates.");
        }
        rejectLegacyPayload(designSlotPlan);
        if (designSlotPlan == null || !designSlotPlan.has("slots")) {
            throw new IllegalArgumentException("designSlotPlan.slots array is required.");
        }
        String cityId = stringValue(designSlotPlan, "cityId", reviewPackage.cityId());
        if (!reviewPackage.cityId().equals(cityId)) {
            throw new IllegalArgumentException("DesignSlotPlan cityId mismatch.");
        }

        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(baseDirectory, terraSenseProfileSource);
        Map<String, CityStructureProfileCatalog.StructureProfile> profiles = catalog.byId();
        CityStructureEnvelopeFacts facts = envelopeFacts == null ? CityStructureEnvelopeFacts.empty() : envelopeFacts;
        Map<String, LandformPatchSummary> patches = patchesByRef(reviewPackage);
        Map<String, JsonObject> slots = slotsById(designSlotPlan);
        List<String> order = placementOrder(designSlotPlan, slots.keySet());

        List<String> hardBlocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>(catalog.warnings());
        List<String> needsReview = new ArrayList<>(catalog.needsReview());
        JsonArray slotCandidates = new JsonArray();
        Map<String, BlockPoint> plannedSlotCenters = new LinkedHashMap<>();
        Map<String, BlockPoint> plannedAnchorCenters = new LinkedHashMap<>();
        List<BlockBounds> occupied = new ArrayList<>();

        int slotIndex = 0;
        for (String slotId : order) {
            slotIndex++;
            JsonObject slot = slots.get(slotId);
            if (slot == null) {
                hardBlocks.add(slotId + ": placementOrder references missing slot.");
                continue;
            }
            SlotPlanResult slotResult = candidatesForSlot(slotIndex, slot, reviewPackage.grid(), patches,
                    profiles, facts, occupied, plannedSlotCenters, plannedAnchorCenters);
            hardBlocks.addAll(slotResult.hardBlocks());
            warnings.addAll(slotResult.warnings());
            slotCandidates.add(slotResult.asJson());
            slotResult.bestCandidate().ifPresent(candidate -> {
                occupied.add(bounds(candidate, "estimatedCollisionEnvelope"));
                BlockPoint center = point(candidate, "anchorBlock");
                plannedSlotCenters.put(slotId, center);
                plannedAnchorCenters.put(slotId + "_tentative", center);
            });
        }

        JsonObject candidateSet = new JsonObject();
        candidateSet.addProperty("schemaVersion", CANDIDATE_SET_SCHEMA);
        candidateSet.addProperty("cityId", reviewPackage.cityId());
        candidateSet.addProperty("planningMode", "all_slots_tentative_order");
        candidateSet.add("grid", reviewPackage.grid().asJson());
        candidateSet.add("sourceDesignSlotPlan", designSlotPlan.deepCopy());
        candidateSet.add("sourceTerraSenseProfileSource", terraSenseProfileSource.deepCopy());
        candidateSet.add("structureProfileCatalog", catalog.asJson());
        candidateSet.add("slotCandidates", slotCandidates);
        JsonObject quality = quality(hardBlocks, warnings, needsReview, slotCandidates);
        candidateSet.add("quality", quality);
        candidateSet.add("timingMs", timing(started));
        return new Result(designSlotPlan.deepCopy(), candidateSet, quality);
    }

    public JsonObject select(JsonObject candidateSet, JsonObject anchorSelectionPlan) {
        rejectLegacyPayload(anchorSelectionPlan);
        if (candidateSet == null || !candidateSet.has("slotCandidates")) {
            throw new IllegalArgumentException("anchorCandidateSet.slotCandidates array is required.");
        }
        if (anchorSelectionPlan == null || !anchorSelectionPlan.has("selectedCandidates")) {
            throw new IllegalArgumentException("anchorSelectionPlan.selectedCandidates array is required.");
        }
        String cityId = requiredString(candidateSet, "cityId");
        if (!cityId.equals(stringValue(anchorSelectionPlan, "cityId", cityId))) {
            throw new IllegalArgumentException("AnchorSelectionPlan cityId mismatch.");
        }
        Map<String, JsonObject> candidatesByKey = new LinkedHashMap<>();
        for (JsonElement slotElem : requiredArray(candidateSet, "slotCandidates")) {
            JsonObject slot = slotElem.getAsJsonObject();
            String slotId = requiredString(slot, "slotId");
            for (JsonElement candElem : optionalArray(slot, "candidates")) {
                JsonObject candidate = candElem.getAsJsonObject();
                candidatesByKey.put(slotId + "\n" + requiredString(candidate, "candidateId"), candidate);
            }
        }

        JsonArray anchors = new JsonArray();
        int index = 0;
        for (JsonElement elem : requiredArray(anchorSelectionPlan, "selectedCandidates")) {
            index++;
            JsonObject selection = elem.getAsJsonObject();
            String slotId = requiredString(selection, "slotId");
            String candidateId = requiredString(selection, "candidateId");
            JsonObject candidate = candidatesByKey.get(slotId + "\n" + candidateId);
            if (candidate == null) {
                throw new IllegalArgumentException("Selected candidate not found: " + slotId + "/" + candidateId);
            }
            String anchorId = stringValue(selection, "anchorId", slotId + "_01");
            JsonObject anchor = new JsonObject();
            anchor.addProperty("anchorId", anchorId);
            anchor.addProperty("slotId", slotId);
            anchor.addProperty("candidateId", candidateId);
            anchor.addProperty("displayRole", stringValue(candidate, "displayRole", slotId));
            anchor.addProperty("structureId", requiredString(candidate, "structureId"));
            anchor.add("sourcePatchIds", candidate.getAsJsonArray("sourcePatchRefs").deepCopy());
            anchor.add("anchorBlock", candidate.getAsJsonObject("anchorBlock").deepCopy());
            anchor.addProperty("rotation", stringValue(candidate, "rotation", "NONE"));
            anchor.add("intentTerms", candidate.has("intentTerms") && candidate.get("intentTerms").isJsonArray()
                    ? candidate.getAsJsonArray("intentTerms").deepCopy()
                    : defaultIntentTerms(slotId, stringValue(candidate, "displayRole", "")));
            anchor.addProperty("priority", intValue(candidate, "priority", index));
            anchor.addProperty("roadAccessIntent", stringValue(candidate, "roadAccessIntent", "connect_to_city_entry"));
            if (!stringValue(candidate, "selectedEnvelopeGroupKey", "").isBlank()) {
                anchor.addProperty("envelopeGroupKey", stringValue(candidate, "selectedEnvelopeGroupKey", ""));
            }
            anchor.addProperty("smallClearanceBlocks",
                    intValue(candidate, "smallClearanceBlocks", DEFAULT_SMALL_CLEARANCE_BLOCKS));
            anchor.addProperty("selectionReason", stringValue(selection, "selectionReason", ""));
            anchors.add(anchor);
        }

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", CityStructureAnchorPlanner.PLAN_SCHEMA);
        plan.addProperty("cityId", cityId);
        plan.add("anchors", anchors);
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", SELECTION_PLAN_SCHEMA);
        trace.add("sourceAnchorSelectionPlan", anchorSelectionPlan.deepCopy());
        trace.addProperty("selectedAnchorCount", anchors.size());
        plan.add("candidateSelectionTrace", trace);
        return plan;
    }

    private SlotPlanResult candidatesForSlot(int slotIndex,
                                             JsonObject slot,
                                             PlanningGrid grid,
                                             Map<String, LandformPatchSummary> patches,
                                             Map<String, CityStructureProfileCatalog.StructureProfile> profiles,
                                             CityStructureEnvelopeFacts facts,
                                             List<BlockBounds> occupied,
                                             Map<String, BlockPoint> plannedSlotCenters,
                                             Map<String, BlockPoint> plannedAnchorCenters) {
        String slotId = requiredString(slot, "slotId");
        String displayRole = stringValue(slot, "displayRole", slotId);
        List<String> structureIds = structureIds(slot);
        List<LandformPatchSummary> sourcePatches = sourcePatches(slot, patches);
        List<String> hardBlocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonArray candidates = new JsonArray();
        if (structureIds.isEmpty()) {
            hardBlocks.add(slotId + ": structureIds must not be empty.");
        }
        if (sourcePatches.isEmpty()) {
            hardBlocks.add(slotId + ": candidatePatchRefs must reference D3 landform patches or map labels.");
        }
        if (!hardBlocks.isEmpty()) {
            return new SlotPlanResult(slotId, displayRole, candidates, hardBlocks, warnings);
        }

        List<CandidateDraft> drafts = new ArrayList<>();
        for (String structureId : structureIds) {
            CityStructureProfileCatalog.StructureProfile profile = profiles.get(structureId);
            if (profile == null) {
                hardBlocks.add(slotId + ": structureId is not in approved TerraSense catalog: " + structureId);
                continue;
            }
            List<BlockPoint> representativePoints = representativePoints(grid, sourcePatches, plannedSlotCenters);
            int pointIndex = 0;
            for (BlockPoint point : representativePoints) {
                pointIndex++;
                if (!grid.containsBlock(point.x(), point.z())) {
                    continue;
                }
                LandformPatchSummary sourcePatch = sourcePatches.stream()
                        .filter(patch -> patchContains(patch, grid, point))
                        .findFirst()
                        .orElse(null);
                if (sourcePatch == null) {
                    continue;
                }
                EnvelopeEstimate estimate = estimateEnvelope(point, profile, facts, slot);
                if (estimate.requiredFactsMissing()) {
                    hardBlocks.add(slotId + ": structure envelope facts are required for Trek structure "
                            + structureId + " but are missing or hash-mismatched.");
                    break;
                }
                if (!estimate.hardBlockReason().isBlank()) {
                    hardBlocks.add(slotId + ": " + estimate.hardBlockReason());
                    continue;
                }
                if (!gridContains(grid, estimate.collisionEnvelope())) {
                    warnings.add(slotId + ": candidate " + pointIndex + " rejected because estimated envelope leaves city grid.");
                    continue;
                }
                if (occupied.stream().anyMatch(existing -> existing.overlaps(estimate.collisionEnvelope()))) {
                    warnings.add(slotId + ": candidate " + pointIndex + " rejected by occupied envelope.");
                    continue;
                }
                String kind = candidateKind(pointIndex, sourcePatch, plannedSlotCenters);
                Score score = score(slot, sourcePatch, point, estimate.collisionEnvelope(),
                        plannedSlotCenters, plannedAnchorCenters);
                drafts.add(new CandidateDraft(slotIndex, slotId, displayRole, structureId, point, sourcePatch,
                        kind, estimate, score));
            }
        }

        Set<String> usedKinds = new LinkedHashSet<>();
        final int[] candidateIndex = {0};
        drafts.stream()
                .sorted(Comparator.comparingDouble((CandidateDraft draft) -> draft.score().total()).reversed())
                .filter(draft -> usedKinds.add(draft.kind()) || usedKinds.size() < MAX_CANDIDATES_PER_SLOT)
                .limit(MAX_CANDIDATES_PER_SLOT)
                .forEach(draft -> candidates.add(candidateJson(draft, ++candidateIndex[0])));
        if (candidates.isEmpty() && hardBlocks.isEmpty()) {
            hardBlocks.add(slotId + ": no available anchor candidates after geometry filtering.");
        }
        return new SlotPlanResult(slotId, displayRole, candidates, hardBlocks, warnings);
    }

    private static JsonObject candidateJson(CandidateDraft draft, int candidateIndex) {
        JsonObject obj = new JsonObject();
        obj.addProperty("candidateId", draft.slotId() + "_" + draft.kind() + "_"
                + String.format(Locale.ROOT, "%02d", candidateIndex));
        obj.addProperty("candidateKind", draft.kind());
        obj.addProperty("slotId", draft.slotId());
        obj.addProperty("displayRole", draft.displayRole());
        obj.addProperty("structureId", draft.structureId());
        obj.add("anchorBlock", draft.anchorBlock().asJson());
        obj.addProperty("rotation", "NONE");
        JsonArray refs = new JsonArray();
        refs.add(draft.patch().landformPatchId());
        if (!draft.patch().mapLabel().isBlank()) {
            refs.add(draft.patch().mapLabel());
        }
        obj.add("sourcePatchRefs", refs);
        obj.add("estimatedCollisionEnvelope", boundsJson(draft.estimate().collisionEnvelope()));
        obj.add("estimatedMaskEnvelope", boundsJson(draft.estimate().maskEnvelope()));
        obj.add("estimatedSafetyEnvelope", boundsJson(draft.estimate().safetyEnvelope()));
        obj.addProperty("geometryStatus", "available");
        obj.addProperty("envelopeMode", draft.estimate().envelopeMode());
        obj.addProperty("selectedEnvelopeGroupKey", draft.estimate().selectedEnvelopeGroupKey());
        obj.addProperty("smallClearanceBlocks", DEFAULT_SMALL_CLEARANCE_BLOCKS);
        obj.addProperty("priority", draft.slotIndex());
        obj.addProperty("roadAccessIntent", "connect_to_city_entry");
        obj.add("intentTerms", defaultIntentTerms(draft.slotId(), draft.displayRole()));
        obj.add("scoreBreakdown", draft.score().asJson());
        obj.addProperty("placementReason", placementReason(draft));
        JsonArray risks = new JsonArray();
        if (draft.patch().metricsSummary().meanSlope() > 6.0) {
            risks.add("slope_medium");
        }
        if (draft.score().relationFit() < 0.35) {
            risks.add("relation_weak");
        }
        obj.add("risks", risks);
        return obj;
    }

    private static String placementReason(CandidateDraft draft) {
        return draft.displayRole() + " candidate on " + draft.patch().mapLabel()
                + " (" + draft.patch().landformType().contractName() + "), kind="
                + draft.kind() + ", score=" + String.format(Locale.ROOT, "%.2f", draft.score().total()) + ".";
    }

    private static Score score(JsonObject slot,
                               LandformPatchSummary patch,
                               BlockPoint point,
                               BlockBounds collision,
                               Map<String, BlockPoint> plannedSlotCenters,
                               Map<String, BlockPoint> plannedAnchorCenters) {
        double slope = patch.metricsSummary().meanSlope();
        double terrainFit = clamp01(1.0 - (slope / 18.0));
        if (patch.landformType().contractName().equals("shore")
                || patch.overlayTags().contains("near_water")
                || patch.landformTags().contains("waterfront")) {
            terrainFit = Math.min(1.0, terrainFit + 0.12);
        }
        double relationFit = relationFit(slot, point, plannedSlotCenters, plannedAnchorCenters);
        double collisionSafety = collision.widthBlocks() > 0 && collision.heightBlocks() > 0 ? 1.0 : 0.0;
        double roadAccess = plannedSlotCenters.isEmpty()
                ? 0.65
                : clamp01(1.0 - (nearestDistance(point, plannedSlotCenters.values()) / 256.0));
        double drama = clamp01((patch.metricsSummary().maxElevation() - patch.metricsSummary().minElevation()) / 48.0
                + (patch.metricsSummary().meanElevation() / 256.0));
        double total = terrainFit * 0.30 + relationFit * 0.30 + collisionSafety * 0.20
                + roadAccess * 0.10 + drama * 0.10;
        return new Score(total, terrainFit, relationFit, collisionSafety, roadAccess, drama);
    }

    private static double relationFit(JsonObject slot,
                                      BlockPoint point,
                                      Map<String, BlockPoint> plannedSlotCenters,
                                      Map<String, BlockPoint> plannedAnchorCenters) {
        JsonArray hints = optionalArray(slot, "relationHints");
        if (hints.isEmpty()) {
            return 0.65;
        }
        double total = 0.0;
        int count = 0;
        for (JsonElement elem : hints) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject hint = elem.getAsJsonObject();
            String targetSlotId = stringValue(hint, "targetSlotId", "");
            String targetAnchorId = stringValue(hint, "targetAnchorId", "");
            BlockPoint target = !targetAnchorId.isBlank()
                    ? plannedAnchorCenters.get(targetAnchorId)
                    : plannedSlotCenters.get(targetSlotId);
            if (target == null) {
                total += 0.55;
                count++;
                continue;
            }
            total += distanceBandScore(manhattan(point, target), stringValue(hint, "distanceBand", "medium"));
            count++;
        }
        return count == 0 ? 0.65 : total / count;
    }

    private static double distanceBandScore(int distance, String band) {
        int min;
        int max;
        switch (band == null ? "" : band.toLowerCase(Locale.ROOT)) {
            case "near" -> {
                min = 32;
                max = 96;
            }
            case "far" -> {
                min = 224;
                max = Integer.MAX_VALUE;
            }
            default -> {
                min = 96;
                max = 224;
            }
        }
        if (distance >= min && distance <= max) {
            return 1.0;
        }
        if (distance < min) {
            return clamp01(distance / (double) Math.max(1, min));
        }
        if (max == Integer.MAX_VALUE) {
            return 1.0;
        }
        return clamp01(1.0 - ((distance - max) / 256.0));
    }

    private static EnvelopeEstimate estimateEnvelope(BlockPoint anchorBlock,
                                                     CityStructureProfileCatalog.StructureProfile profile,
                                                     CityStructureEnvelopeFacts facts,
                                                     JsonObject slot) {
        CityStructureProfileCatalog.Footprint footprint = profile.planningFootprint();
        if (!footprint.valid()) {
            return new EnvelopeEstimate(new BlockBounds(0, 0, 0, 0), new BlockBounds(0, 0, 0, 0),
                    new BlockBounds(0, 0, 0, 0), "", "", true,
                    "structure profile has no usable footprint.");
        }
        int clearance = Math.max(DEFAULT_CLEARANCE_BLOCKS,
                intValue(slot, "clearanceBlocks", profile.clearanceBlocks()));
        int smallClearance = Math.max(0, intValue(slot, "smallClearanceBlocks", DEFAULT_SMALL_CLEARANCE_BLOCKS));
        int vegetationMargin = Math.max(0, intValue(slot, "vegetationMarginBlocks", DEFAULT_VEGETATION_MARGIN_BLOCKS));
        BlockBounds plannedFootprint = footprint.centeredAt(anchorBlock.x(), anchorBlock.z(), "NONE");
        Optional<CityStructureEnvelopeFacts.Fact> fact = facts.validFactFor(profile);
        if (fact.isPresent()) {
            CityStructureEnvelopeFacts.Fact value = fact.get();
            boolean fixedGroupMode = "fixed_footprint".equals(profile.footprintMode()) || value.nearFixedByFacts();
            if (fixedGroupMode) {
                Optional<CityStructureEnvelopeFacts.BBoxGroup> selected = value.dominantGroup();
                if (selected.isEmpty()) {
                    return new EnvelopeEstimate(plannedFootprint, plannedFootprint, plannedFootprint,
                            "fixed_bbox_group", "", false,
                            "structure envelope facts have no bboxGroups for fixed bbox mode.");
                }
                CityStructureEnvelopeFacts.BBoxGroup group = selected.get();
                BlockBounds collision = fromLocal(anchorBlock,
                        CityStructureAnchorPlanner.expand(group.localEnvelope(), smallClearance));
                BlockBounds mask = fromLocal(anchorBlock,
                        CityStructureAnchorPlanner.expand(group.localEnvelope(), vegetationMargin));
                BlockBounds safety = fromLocal(anchorBlock,
                        CityStructureAnchorPlanner.expand(value.maxObservedEnvelope(),
                                Math.max(smallClearance, DEFAULT_ROAD_ACCESS_MARGIN_BLOCKS)));
                return new EnvelopeEstimate(collision, mask, safety, "fixed_bbox_group",
                        group.groupKey(), false, "");
            }
            BlockBounds collision = fromLocal(anchorBlock,
                    CityStructureAnchorPlanner.expand(value.p95Envelope(), clearance));
            BlockBounds mask = fromLocal(anchorBlock,
                    CityStructureAnchorPlanner.expand(value.p99Envelope(), vegetationMargin));
            BlockBounds safety = fromLocal(anchorBlock,
                    CityStructureAnchorPlanner.expand(value.maxObservedEnvelope(),
                            Math.max(clearance, DEFAULT_ROAD_ACCESS_MARGIN_BLOCKS)));
            return new EnvelopeEstimate(collision, mask, safety, "fixed_depth_statistics",
                    "", false, "");
        }
        if (profile.structureId().startsWith("trek:")) {
            return new EnvelopeEstimate(plannedFootprint, plannedFootprint, plannedFootprint,
                    "missing_structure_envelope_facts", "", true, "");
        }
        int radius = profile.jigsawLike()
                ? profile.jigsawExpansionRadius(DEFAULT_JIGSAW_RADIUS_BLOCKS)
                + clearance + DEFAULT_ROAD_ACCESS_MARGIN_BLOCKS
                : clearance;
        BlockBounds collision = CityStructureAnchorPlanner.expand(plannedFootprint, radius);
        return new EnvelopeEstimate(collision, collision, collision,
                profile.jigsawLike() ? "fallback_jigsaw_radius" : "fallback_fixed_footprint",
                "", false, "");
    }

    private static List<BlockPoint> representativePoints(PlanningGrid grid,
                                                         List<LandformPatchSummary> patches,
                                                         Map<String, BlockPoint> plannedCenters) {
        LinkedHashSet<BlockPoint> points = new LinkedHashSet<>();
        BlockPoint cityCenter = new BlockPoint((grid.blockMinX() + grid.blockMaxX()) / 2,
                (grid.blockMinZ() + grid.blockMaxZ()) / 2);
        for (LandformPatchSummary patch : patches) {
            points.add(patch.centerBlock());
            if (!patch.memberCells().isEmpty()) {
                List<PatchMemberCell> cells = patch.memberCells();
                points.add(cellCenter(grid, cells.get(0)));
                points.add(cellCenter(grid, cells.get(cells.size() / 2)));
                points.add(cellCenter(grid, cells.get(cells.size() - 1)));
            }
            points.add(insetPoint(patch.blockBounds(), 0.30, 0.30));
            points.add(insetPoint(patch.blockBounds(), 0.70, 0.70));
            if (!plannedCenters.isEmpty()) {
                BlockPoint nearest = nearest(plannedCenters.values(), patch.centerBlock());
                points.add(toward(patch.blockBounds(), patch.centerBlock(), nearest, 0.35));
            } else {
                points.add(toward(patch.blockBounds(), patch.centerBlock(), cityCenter, 0.25));
            }
        }
        return new ArrayList<>(points);
    }

    private static BlockPoint cellCenter(PlanningGrid grid, PatchMemberCell cell) {
        return new BlockPoint(cell.blockMinX() + grid.cellStepBlocks() / 2,
                cell.blockMinZ() + grid.cellStepBlocks() / 2);
    }

    private static BlockPoint insetPoint(BlockBounds bounds, double fx, double fz) {
        return new BlockPoint(
                bounds.minX() + (int) Math.round((bounds.maxX() - bounds.minX()) * fx),
                bounds.minZ() + (int) Math.round((bounds.maxZ() - bounds.minZ()) * fz));
    }

    private static BlockPoint toward(BlockBounds bounds, BlockPoint from, BlockPoint to, double amount) {
        int x = from.x() + (int) Math.round((to.x() - from.x()) * amount);
        int z = from.z() + (int) Math.round((to.z() - from.z()) * amount);
        return new BlockPoint(clamp(x, bounds.minX(), bounds.maxX()), clamp(z, bounds.minZ(), bounds.maxZ()));
    }

    private static BlockPoint nearest(Iterable<BlockPoint> points, BlockPoint target) {
        BlockPoint best = target;
        int bestDistance = Integer.MAX_VALUE;
        for (BlockPoint point : points) {
            int distance = manhattan(point, target);
            if (distance < bestDistance) {
                best = point;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int nearestDistance(BlockPoint target, Iterable<BlockPoint> points) {
        int best = Integer.MAX_VALUE;
        for (BlockPoint point : points) {
            best = Math.min(best, manhattan(target, point));
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    private static String candidateKind(int pointIndex, LandformPatchSummary patch,
                                        Map<String, BlockPoint> plannedCenters) {
        if (pointIndex == 1) {
            return "best_fit";
        }
        if (pointIndex == 2) {
            return plannedCenters.isEmpty() ? "practical" : "compact";
        }
        if (pointIndex == 3) {
            return "dramatic";
        }
        if (pointIndex == 4) {
            return "compact";
        }
        return patch.landformType().contractName().equals("shore") ? "waterfront" : "practical";
    }

    private static Map<String, LandformPatchSummary> patchesByRef(CityLandformReviewPackage reviewPackage) {
        Map<String, LandformPatchSummary> result = new HashMap<>();
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            result.put(patch.landformPatchId(), patch);
            result.put(patch.mapLabel(), patch);
        }
        return result;
    }

    private static Map<String, JsonObject> slotsById(JsonObject designSlotPlan) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement elem : requiredArray(designSlotPlan, "slots")) {
            JsonObject slot = elem.getAsJsonObject();
            String slotId = requiredString(slot, "slotId");
            if (result.putIfAbsent(slotId, slot) != null) {
                throw new IllegalArgumentException("Duplicate design slotId: " + slotId);
            }
        }
        return result;
    }

    private static List<String> placementOrder(JsonObject designSlotPlan, Set<String> slotIds) {
        JsonArray array = optionalArray(designSlotPlan, "placementOrder");
        List<String> result = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                result.add(elem.getAsString());
            }
        }
        if (result.isEmpty()) {
            result.addAll(slotIds);
        }
        return result;
    }

    private static List<LandformPatchSummary> sourcePatches(JsonObject slot,
                                                            Map<String, LandformPatchSummary> patches) {
        List<LandformPatchSummary> result = new ArrayList<>();
        for (JsonElement elem : requiredArray(slot, "candidatePatchRefs")) {
            String ref = elem.getAsString();
            LandformPatchSummary patch = patches.get(ref);
            if (patch != null && result.stream().noneMatch(existing ->
                    existing.landformPatchId().equals(patch.landformPatchId()))) {
                result.add(patch);
            }
        }
        result.sort(Comparator.comparing(LandformPatchSummary::landformPatchId));
        return result;
    }

    private static boolean patchContains(LandformPatchSummary patch, PlanningGrid grid, BlockPoint point) {
        if (!patch.memberCells().isEmpty()) {
            int cellX = grid.blockToCellX(point.x());
            int cellZ = grid.blockToCellZ(point.z());
            for (PatchMemberCell cell : patch.memberCells()) {
                if (cell.cellX() == cellX && cell.cellZ() == cellZ) {
                    return true;
                }
            }
        }
        return patch.blockBounds().contains(point.x(), point.z());
    }

    private static boolean gridContains(PlanningGrid grid, BlockBounds bounds) {
        return grid.containsBlock(bounds.minX(), bounds.minZ())
                && grid.containsBlock(bounds.maxX(), bounds.maxZ());
    }

    private static BlockBounds fromLocal(BlockPoint anchorBlock, BlockBounds local) {
        int originX = Math.floorDiv(anchorBlock.x(), 16) * 16;
        int originZ = Math.floorDiv(anchorBlock.z(), 16) * 16;
        return new BlockBounds(originX + local.minX(), originZ + local.minZ(),
                originX + local.maxX(), originZ + local.maxZ());
    }

    private static JsonObject quality(List<String> hardBlocks, List<String> warnings,
                                      List<String> needsReview, JsonArray slotCandidates) {
        JsonObject quality = new JsonObject();
        quality.addProperty("passed", hardBlocks.isEmpty());
        quality.addProperty("score", hardBlocks.isEmpty() ? 100 : 0);
        quality.add("hardBlocks", stringArray(hardBlocks));
        quality.add("warnings", stringArray(warnings));
        quality.add("needsReview", stringArray(needsReview));
        JsonObject metrics = new JsonObject();
        metrics.addProperty("slotCount", slotCandidates.size());
        int candidateCount = 0;
        for (JsonElement elem : slotCandidates) {
            candidateCount += optionalArray(elem.getAsJsonObject(), "candidates").size();
        }
        metrics.addProperty("candidateCount", candidateCount);
        quality.add("metrics", metrics);
        return quality;
    }

    private static JsonObject timing(long started) {
        JsonObject timing = new JsonObject();
        timing.addProperty("total", (System.nanoTime() - started) / 1_000_000L);
        return timing;
    }

    private static JsonArray defaultIntentTerms(String slotId, String displayRole) {
        JsonArray terms = new JsonArray();
        terms.add("slot." + slotId);
        if (displayRole != null && !displayRole.isBlank()) {
            terms.add(displayRole);
        }
        return terms;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                values.add(elem.getAsString());
            }
        }
        return values;
    }

    private static List<String> structureIds(JsonObject slot) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String single = stringValue(slot, "structureId", "");
        if (!single.isBlank()) {
            ids.add(single);
        }
        JsonArray array = optionalArray(slot, "structureIds");
        for (String id : strings(array)) {
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static BlockBounds bounds(JsonObject obj, String key) {
        return bounds(requiredObject(obj, key));
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static BlockPoint point(JsonObject obj, String key) {
        JsonObject source = requiredObject(obj, key);
        return new BlockPoint(intValue(source, "x", 0), intValue(source, "z", 0));
    }

    private static void rejectLegacyPayload(JsonObject obj) {
        if (obj == null) {
            return;
        }
        for (String field : List.of("patchGroupPlan", "groups", "zoneChoices", "functionType",
                "functionTag", "functionTags", "function_candidates", "targetVisibleAreaRatio")) {
            if (obj.has(field)) {
                throw CityStructureProfileCatalog.legacyFlow("D4 candidate flow does not accept " + field + ".");
            }
        }
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required.");
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonArray optionalArray(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject requiredObject(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required.");
        }
        return obj.getAsJsonObject(key);
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
    }

    private static int manhattan(BlockPoint a, BlockPoint b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record Result(JsonObject designSlotPlan, JsonObject anchorCandidateSet, JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.add("designSlotPlan", designSlotPlan.deepCopy());
            obj.add("anchorCandidateSet", anchorCandidateSet.deepCopy());
            obj.add("qualityReport", qualityReport.deepCopy());
            obj.add("timingMs", anchorCandidateSet.getAsJsonObject("timingMs").deepCopy());
            return obj;
        }
    }

    private record SlotPlanResult(String slotId, String displayRole, JsonArray candidates,
                                  List<String> hardBlocks, List<String> warnings) {
        Optional<JsonObject> bestCandidate() {
            return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0).getAsJsonObject());
        }

        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("slotId", slotId);
            obj.addProperty("displayRole", displayRole);
            obj.add("candidates", candidates);
            obj.add("hardBlocks", stringArray(hardBlocks));
            obj.add("warnings", stringArray(warnings));
            return obj;
        }
    }

    private record CandidateDraft(int slotIndex, String slotId, String displayRole, String structureId,
                                  BlockPoint anchorBlock, LandformPatchSummary patch, String kind,
                                  EnvelopeEstimate estimate, Score score) {
    }

    private record EnvelopeEstimate(BlockBounds collisionEnvelope, BlockBounds maskEnvelope,
                                    BlockBounds safetyEnvelope, String envelopeMode,
                                    String selectedEnvelopeGroupKey, boolean requiredFactsMissing,
                                    String hardBlockReason) {
    }

    private record Score(double total, double terrainFit, double relationFit, double collisionSafety,
                         double roadAccessPotential, double designDrama) {
        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("total", total);
            obj.addProperty("terrainFit", terrainFit);
            obj.addProperty("relationFit", relationFit);
            obj.addProperty("collisionSafety", collisionSafety);
            obj.addProperty("roadAccessPotential", roadAccessPotential);
            obj.addProperty("designDrama", designDrama);
            return obj;
        }
    }
}
