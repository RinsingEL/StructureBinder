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
import java.time.Instant;
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
    public static final String SESSION_SCHEMA = "city_d4_candidate_session.v0.2";
    public static final String SLOT_CANDIDATE_SET_SCHEMA = "city_d4_slot_candidate_set.v0.2";
    public static final String DESIGN_TIME_REPORT_SCHEMA = "city_d4_design_time_report.v0.2";

    private static final int MAX_CANDIDATES_PER_SLOT = 5;
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
        candidateSet.addProperty("planningMode", "all_slots_tentative_order_debug");
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
                    intValue(candidate, "smallClearanceBlocks",
                            CityStructureCandidateEnvelope.DEFAULT_SMALL_CLEARANCE_BLOCKS));
            anchor.addProperty("selectionReason", stringValue(selection, "selectionReason", ""));
            CityStructureAnchorPlanner.applyPlacementProvenance(anchor, anchor);
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

    public SessionResult createSession(Path baseDirectory,
                                       CityLandformReviewPackage reviewPackage,
                                       JsonObject terraSenseProfileSource,
                                       JsonObject designSlotPlan,
                                       String requestedSessionId) throws IOException {
        long started = System.nanoTime();
        if (reviewPackage == null) {
            throw new IllegalArgumentException("CityLandformReviewPackage is required for D4 candidate session.");
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
        Map<String, JsonObject> slots = slotsById(designSlotPlan);
        List<String> order = placementOrder(designSlotPlan, slots.keySet());
        List<String> hardBlocks = new ArrayList<>();
        for (String slotId : order) {
            if (!slots.containsKey(slotId)) {
                hardBlocks.add(slotId + ": placementOrder references missing slot.");
            }
        }
        JsonObject session = new JsonObject();
        session.addProperty("schemaVersion", SESSION_SCHEMA);
        session.addProperty("sessionId", requestedSessionId == null || requestedSessionId.isBlank()
                ? cityId + "_d4_session" : requestedSessionId);
        session.addProperty("cityId", cityId);
        session.addProperty("planningMode", "sequential_slot_session");
        session.add("sourceDesignSlotPlan", designSlotPlan.deepCopy());
        session.add("sourceTerraSenseProfileSource", terraSenseProfileSource.deepCopy());
        session.add("structureProfileCatalog", catalog.asJson());
        session.add("placementOrder", stringArray(order));
        session.addProperty("currentSlotIndex", order.isEmpty() ? -1 : 0);
        session.addProperty("currentSlotId", order.isEmpty() ? "" : order.get(0));
        session.addProperty("selectedAnchorCount", 0);
        session.addProperty("remainingSlotCount", order.size());
        session.add("selectedAnchors", new JsonArray());
        session.add("occupiedEnvelopes", new JsonArray());
        session.add("rejectedSelections", new JsonArray());
        session.add("candidateHistory", new JsonArray());
        JsonObject timing = new JsonObject();
        long now = System.currentTimeMillis();
        timing.addProperty("createdAt", Instant.ofEpochMilli(now).toString());
        timing.addProperty("updatedAt", Instant.ofEpochMilli(now).toString());
        timing.addProperty("createdAtEpochMs", now);
        timing.addProperty("updatedAtEpochMs", now);
        timing.addProperty("toolRuntimeMs", elapsedMs(started));
        timing.addProperty("agentThinkTimeMs", 0);
        timing.addProperty("totalWallClockMs", 0);
        timing.add("stepTimings", new JsonArray());
        session.add("timing", timing);
        JsonObject quality = quality(hardBlocks, new ArrayList<>(catalog.warnings()),
                new ArrayList<>(catalog.needsReview()), new JsonArray());
        session.add("quality", quality);
        return new SessionResult(session, quality, designTimeReport(session));
    }

    public NextCandidateResult planNext(Path baseDirectory,
                                        CityLandformReviewPackage reviewPackage,
                                        JsonObject session,
                                        CityStructureEnvelopeFacts envelopeFacts) throws IOException {
        long started = System.nanoTime();
        ensureSession(session);
        if (reviewPackage == null) {
            throw new IllegalArgumentException("CityLandformReviewPackage is required for D4 candidate session.");
        }
        String cityId = requiredString(session, "cityId");
        if (!reviewPackage.cityId().equals(cityId)) {
            throw new IllegalArgumentException("D4 candidate session cityId mismatch.");
        }
        JsonObject designSlotPlan = requiredObject(session, "sourceDesignSlotPlan");
        JsonObject profileSource = requiredObject(session, "sourceTerraSenseProfileSource");
        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(baseDirectory, profileSource);
        Map<String, CityStructureProfileCatalog.StructureProfile> profiles = catalog.byId();
        CityStructureEnvelopeFacts facts = envelopeFacts == null ? CityStructureEnvelopeFacts.empty() : envelopeFacts;
        Map<String, LandformPatchSummary> patches = patchesByRef(reviewPackage);
        Map<String, JsonObject> slots = slotsById(designSlotPlan);
        List<String> order = placementOrder(designSlotPlan, slots.keySet());
        int currentIndex = firstUnselectedIndex(session, order);
        if (currentIndex < 0) {
            throw new IllegalArgumentException("D4_SESSION_ALREADY_FINALIZABLE: all design slots are selected.");
        }
        String slotId = order.get(currentIndex);
        JsonObject slot = slots.get(slotId);
        if (slot == null) {
            throw new IllegalArgumentException("D4_SLOT_ORDER_VIOLATION: placementOrder references missing slot "
                    + slotId + ".");
        }
        Map<String, BlockPoint> plannedSlotCenters = plannedSlotCenters(session);
        Map<String, BlockPoint> plannedAnchorCenters = plannedAnchorCenters(session);
        List<BlockBounds> occupied = occupiedBounds(session);
        SlotPlanResult slotResult = candidatesForSlot(currentIndex + 1, slot, reviewPackage.grid(), patches,
                profiles, facts, occupied, plannedSlotCenters, plannedAnchorCenters);
        JsonArray slotCandidates = new JsonArray();
        slotCandidates.add(slotResult.asJson());
        List<String> warnings = new ArrayList<>(catalog.warnings());
        warnings.addAll(slotResult.warnings());
        List<String> hardBlocks = new ArrayList<>(slotResult.hardBlocks());
        JsonObject quality = quality(hardBlocks, warnings, new ArrayList<>(catalog.needsReview()), slotCandidates);

        JsonObject candidateSet = new JsonObject();
        candidateSet.addProperty("schemaVersion", SLOT_CANDIDATE_SET_SCHEMA);
        candidateSet.addProperty("cityId", cityId);
        candidateSet.addProperty("sessionId", requiredString(session, "sessionId"));
        candidateSet.addProperty("planningMode", "sequential_current_slot");
        candidateSet.addProperty("currentSlotIndex", currentIndex);
        candidateSet.addProperty("currentSlotId", slotId);
        candidateSet.addProperty("quickPreflightStatus", "deferred_to_d6");
        candidateSet.add("grid", reviewPackage.grid().asJson());
        candidateSet.add("sourceDesignSlotPlan", designSlotPlan.deepCopy());
        candidateSet.add("sourceTerraSenseProfileSource", profileSource.deepCopy());
        candidateSet.add("structureProfileCatalog", catalog.asJson());
        candidateSet.add("selectedAnchors", optionalArray(session, "selectedAnchors").deepCopy());
        candidateSet.add("occupiedEnvelopes", optionalArray(session, "occupiedEnvelopes").deepCopy());
        candidateSet.add("slotCandidates", slotCandidates);
        candidateSet.add("quality", quality.deepCopy());
        candidateSet.add("timingMs", timing(started));

        JsonObject updatedSession = session.deepCopy();
        updatedSession.addProperty("currentSlotIndex", currentIndex);
        updatedSession.addProperty("currentSlotId", slotId);
        updatedSession.addProperty("selectedAnchorCount", optionalArray(updatedSession, "selectedAnchors").size());
        updatedSession.addProperty("remainingSlotCount", Math.max(0,
                order.size() - optionalArray(updatedSession, "selectedAnchors").size()));
        long now = System.currentTimeMillis();
        updatedSession.addProperty("lastCandidateReturnedAtEpochMs", now);
        updatedSession.addProperty("lastCandidateSlotId", slotId);
        JsonObject history = new JsonObject();
        history.addProperty("slotId", slotId);
        history.addProperty("generatedAt", Instant.ofEpochMilli(now).toString());
        history.addProperty("candidateCount", slotResult.candidates().size());
        history.addProperty("hardBlockCount", hardBlocks.size());
        history.addProperty("warningCount", warnings.size());
        optionalArray(updatedSession, "candidateHistory").add(history);
        long elapsed = elapsedMs(started);
        updateTiming(updatedSession, elapsed, 0);
        JsonObject step = stepTiming(updatedSession, slotId);
        step.addProperty("candidateGenerationMs", longValue(step, "candidateGenerationMs", 0) + elapsed);
        step.addProperty("candidateCount", slotResult.candidates().size());
        step.addProperty("rejectedSelectionCount", intValue(step, "rejectedSelectionCount", 0));
        if (!step.has("previewRenderMs")) {
            step.addProperty("previewRenderMs", 0);
        }
        updatedSession.add("quality", quality.deepCopy());
        return new NextCandidateResult(updatedSession, candidateSet, quality);
    }

    public SelectionResult selectSession(JsonObject session,
                                         JsonObject slotCandidateSet,
                                         String slotId,
                                         String candidateId,
                                         String anchorId,
                                         String selectionReason,
                                         boolean quickPreflight) {
        long started = System.nanoTime();
        ensureSession(session);
        if (slotCandidateSet == null || !slotCandidateSet.has("slotCandidates")) {
            throw new IllegalArgumentException("slotCandidateSet.slotCandidates array is required.");
        }
        String currentSlotId = requiredString(session, "currentSlotId");
        if (!currentSlotId.equals(slotId)) {
            throw new IllegalArgumentException("D4_SLOT_ORDER_VIOLATION: current slot is " + currentSlotId
                    + " but selection targets " + slotId + ".");
        }
        if (selectedSlotIds(session).contains(slotId)) {
            throw new IllegalArgumentException("D4_SLOT_ALREADY_SELECTED: slot already selected: " + slotId);
        }
        JsonObject candidate = findCandidate(slotCandidateSet, slotId, candidateId)
                .orElseThrow(() -> new IllegalArgumentException("D4_SELECTED_CANDIDATE_NOT_FOUND: "
                        + slotId + "/" + candidateId));
        BlockBounds selectedBounds = sessionOccupiedBounds(candidate);
        for (JsonObject occupied : occupiedEnvelopeObjects(session)) {
            BlockBounds existing = bounds(requiredObject(occupied, "blockBounds"));
            if (existing.overlaps(selectedBounds)) {
                JsonObject rejection = rejection(slotId, candidateId,
                        "D4_SELECTED_CANDIDATE_OCCUPIED_OVERLAP",
                        "Selected candidate estimated envelope overlaps frozen occupied envelope.");
                optionalArray(session, "rejectedSelections").add(rejection);
                throw new IllegalArgumentException("D4_SELECTED_CANDIDATE_OCCUPIED_OVERLAP: selected candidate "
                        + candidateId + " overlaps " + stringValue(occupied, "sourceAnchorId", "occupied"));
            }
        }
        JsonObject updatedSession = session.deepCopy();
        JsonObject selected = candidate.deepCopy();
        selected.addProperty("anchorId", anchorId == null || anchorId.isBlank() ? slotId + "_01" : anchorId);
        selected.addProperty("selectionReason", selectionReason == null ? "" : selectionReason);
        selected.addProperty("selectedAt", Instant.now().toString());
        selected.addProperty("quickPreflightStatus", "deferred_to_d6");
        selected.addProperty("quickPreflightRequested", quickPreflight);
        selected.addProperty("selectedOrder", optionalArray(updatedSession, "selectedAnchors").size() + 1);
        optionalArray(updatedSession, "selectedAnchors").add(selected);
        JsonObject occupied = new JsonObject();
        occupied.addProperty("sourceAnchorId", requiredString(selected, "anchorId"));
        occupied.addProperty("sourceSlotId", slotId);
        occupied.addProperty("sourceCandidateId", candidateId);
        occupied.addProperty("envelopeType", "estimated_collision");
        occupied.add("blockBounds", boundsJson(sessionOccupiedBounds(selected)));
        if (selected.has("estimatedCollisionEnvelope")) {
            occupied.add("estimatedCollisionEnvelope",
                    selected.getAsJsonObject("estimatedCollisionEnvelope").deepCopy());
        }
        optionalArray(updatedSession, "occupiedEnvelopes").add(occupied);

        JsonObject designSlotPlan = requiredObject(updatedSession, "sourceDesignSlotPlan");
        List<String> order = placementOrder(designSlotPlan, slotsById(designSlotPlan).keySet());
        int nextIndex = firstUnselectedIndex(updatedSession, order);
        updatedSession.addProperty("currentSlotIndex", nextIndex);
        updatedSession.addProperty("currentSlotId", nextIndex < 0 ? "" : order.get(nextIndex));
        updatedSession.addProperty("selectedAnchorCount", optionalArray(updatedSession, "selectedAnchors").size());
        updatedSession.addProperty("remainingSlotCount", nextIndex < 0 ? 0
                : order.size() - optionalArray(updatedSession, "selectedAnchors").size());

        long agentThink = agentThinkTimeMs(session, slotId);
        long elapsed = elapsedMs(started);
        updateTiming(updatedSession, elapsed, agentThink);
        JsonObject step = stepTiming(updatedSession, slotId);
        step.addProperty("selectionValidationMs", longValue(step, "selectionValidationMs", 0) + elapsed);
        step.addProperty("agentThinkTimeMs", longValue(step, "agentThinkTimeMs", 0) + agentThink);
        step.addProperty("quickPreflightMs", longValue(step, "quickPreflightMs", 0));
        step.addProperty("selectedCandidateId", candidateId);
        step.addProperty("selectedAnchorId", requiredString(selected, "anchorId"));
        step.addProperty("quickPreflightStatus", "deferred_to_d6");

        JsonObject quickReport = new JsonObject();
        quickReport.addProperty("status", "deferred_to_d6");
        quickReport.addProperty("reasonCode", "D4_QUICK_PREFLIGHT_DEFERRED_TO_D6");
        quickReport.addProperty("quickPreflightRequested", quickPreflight);
        quickReport.addProperty("message", "D4 v0.2 defers MC actual bbox probe to D6.");
        return new SelectionResult(updatedSession, selected, quickReport, designTimeReport(updatedSession));
    }

    public FinalizeResult finalizeSession(JsonObject session) {
        ensureSession(session);
        JsonObject designSlotPlan = requiredObject(session, "sourceDesignSlotPlan");
        List<String> order = placementOrder(designSlotPlan, slotsById(designSlotPlan).keySet());
        if (firstUnselectedIndex(session, order) >= 0) {
            throw new IllegalArgumentException("D4_SESSION_NOT_FINALIZABLE: all placementOrder slots must be selected.");
        }
        JsonArray anchors = new JsonArray();
        int index = 0;
        for (JsonElement elem : optionalArray(session, "selectedAnchors")) {
            index++;
            anchors.add(anchorFromSelected(elem.getAsJsonObject(), index));
        }
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", CityStructureAnchorPlanner.PLAN_SCHEMA);
        plan.addProperty("cityId", requiredString(session, "cityId"));
        plan.add("anchors", anchors);
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", SELECTION_PLAN_SCHEMA);
        trace.addProperty("planningMode", "sequential_slot_session");
        trace.addProperty("sessionId", requiredString(session, "sessionId"));
        trace.addProperty("selectedAnchorCount", anchors.size());
        trace.add("sourceSession", session.deepCopy());
        plan.add("candidateSelectionTrace", trace);
        return new FinalizeResult(session.deepCopy(), plan, designTimeReport(session));
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
                CityStructureCandidateEnvelope.Estimate estimate =
                        CityStructureCandidateEnvelope.estimate(point, profile, facts, slot);
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
        obj.add("diagnosticMaxObservedEnvelope", boundsJson(draft.estimate().diagnosticMaxObservedEnvelope()));
        obj.addProperty("geometryStatus", "available");
        obj.addProperty("envelopeMode", draft.estimate().envelopeMode());
        obj.addProperty("selectedEnvelopeGroupKey", draft.estimate().selectedEnvelopeGroupKey());
        obj.addProperty("smallClearanceBlocks", CityStructureCandidateEnvelope.DEFAULT_SMALL_CLEARANCE_BLOCKS);
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

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static void ensureSession(JsonObject session) {
        if (session == null || !SESSION_SCHEMA.equals(stringValue(session, "schemaVersion", ""))) {
            throw new IllegalArgumentException("D4_CANDIDATE_SESSION_NOT_FOUND: "
                    + "city_d4_candidate_session.v0.2 session object is required.");
        }
    }

    private static int firstUnselectedIndex(JsonObject session, List<String> order) {
        Set<String> selected = selectedSlotIds(session);
        for (int i = 0; i < order.size(); i++) {
            if (!selected.contains(order.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static Set<String> selectedSlotIds(JsonObject session) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement elem : optionalArray(session, "selectedAnchors")) {
            if (elem.isJsonObject()) {
                result.add(stringValue(elem.getAsJsonObject(), "slotId", ""));
            }
        }
        return result;
    }

    private static Map<String, BlockPoint> plannedSlotCenters(JsonObject session) {
        Map<String, BlockPoint> result = new LinkedHashMap<>();
        for (JsonElement elem : optionalArray(session, "selectedAnchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject selected = elem.getAsJsonObject();
            result.put(requiredString(selected, "slotId"), point(selected, "anchorBlock"));
        }
        return result;
    }

    private static Map<String, BlockPoint> plannedAnchorCenters(JsonObject session) {
        Map<String, BlockPoint> result = new LinkedHashMap<>();
        for (JsonElement elem : optionalArray(session, "selectedAnchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject selected = elem.getAsJsonObject();
            result.put(requiredString(selected, "anchorId"), point(selected, "anchorBlock"));
        }
        return result;
    }

    private static List<BlockBounds> occupiedBounds(JsonObject session) {
        List<BlockBounds> result = new ArrayList<>();
        for (JsonObject occupied : occupiedEnvelopeObjects(session)) {
            result.add(bounds(requiredObject(occupied, "blockBounds")));
        }
        return result;
    }

    private static BlockBounds sessionOccupiedBounds(JsonObject candidate) {
        return bounds(candidate, "estimatedCollisionEnvelope");
    }

    private static List<JsonObject> occupiedEnvelopeObjects(JsonObject session) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement elem : optionalArray(session, "occupiedEnvelopes")) {
            if (elem.isJsonObject()) {
                result.add(elem.getAsJsonObject());
            }
        }
        return result;
    }

    private static Optional<JsonObject> findCandidate(JsonObject slotCandidateSet, String slotId, String candidateId) {
        for (JsonElement slotElem : requiredArray(slotCandidateSet, "slotCandidates")) {
            JsonObject slot = slotElem.getAsJsonObject();
            if (!slotId.equals(stringValue(slot, "slotId", ""))) {
                continue;
            }
            for (JsonElement candElem : optionalArray(slot, "candidates")) {
                if (!candElem.isJsonObject()) {
                    continue;
                }
                JsonObject candidate = candElem.getAsJsonObject();
                if (candidateId.equals(stringValue(candidate, "candidateId", ""))) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static JsonObject rejection(String slotId, String candidateId, String reasonCode, String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("slotId", slotId);
        obj.addProperty("candidateId", candidateId);
        obj.addProperty("reasonCode", reasonCode);
        obj.addProperty("message", message);
        obj.addProperty("rejectedAt", Instant.now().toString());
        return obj;
    }

    private static JsonObject anchorFromSelected(JsonObject selected, int index) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("anchorId", requiredString(selected, "anchorId"));
        anchor.addProperty("slotId", requiredString(selected, "slotId"));
        anchor.addProperty("candidateId", requiredString(selected, "candidateId"));
        anchor.addProperty("displayRole", stringValue(selected, "displayRole", requiredString(selected, "slotId")));
        anchor.addProperty("structureId", requiredString(selected, "structureId"));
        anchor.add("sourcePatchIds", selected.getAsJsonArray("sourcePatchRefs").deepCopy());
        anchor.add("anchorBlock", selected.getAsJsonObject("anchorBlock").deepCopy());
        anchor.addProperty("rotation", stringValue(selected, "rotation", "NONE"));
        anchor.add("intentTerms", selected.has("intentTerms") && selected.get("intentTerms").isJsonArray()
                ? selected.getAsJsonArray("intentTerms").deepCopy()
                : defaultIntentTerms(requiredString(selected, "slotId"), stringValue(selected, "displayRole", "")));
        anchor.addProperty("priority", intValue(selected, "priority", index));
        anchor.addProperty("roadAccessIntent", stringValue(selected, "roadAccessIntent", "connect_to_city_entry"));
        if (!stringValue(selected, "selectedEnvelopeGroupKey", "").isBlank()) {
            anchor.addProperty("envelopeGroupKey", stringValue(selected, "selectedEnvelopeGroupKey", ""));
        }
        anchor.addProperty("smallClearanceBlocks",
                intValue(selected, "smallClearanceBlocks",
                        CityStructureCandidateEnvelope.DEFAULT_SMALL_CLEARANCE_BLOCKS));
        anchor.addProperty("selectionReason", stringValue(selected, "selectionReason", ""));
        CityStructureAnchorPlanner.applyPlacementProvenance(anchor, anchor);
        return anchor;
    }

    private static long agentThinkTimeMs(JsonObject session, String slotId) {
        if (!slotId.equals(stringValue(session, "lastCandidateSlotId", ""))) {
            return 0;
        }
        long returnedAt = longValue(session, "lastCandidateReturnedAtEpochMs", 0);
        if (returnedAt <= 0) {
            return 0;
        }
        return Math.max(0, System.currentTimeMillis() - returnedAt);
    }

    private static void updateTiming(JsonObject session, long toolRuntimeMs, long agentThinkTimeMs) {
        JsonObject timing = session.has("timing") && session.get("timing").isJsonObject()
                ? session.getAsJsonObject("timing")
                : new JsonObject();
        long now = System.currentTimeMillis();
        if (!timing.has("createdAtEpochMs")) {
            timing.addProperty("createdAtEpochMs", now);
            timing.addProperty("createdAt", Instant.ofEpochMilli(now).toString());
        }
        timing.addProperty("updatedAtEpochMs", now);
        timing.addProperty("updatedAt", Instant.ofEpochMilli(now).toString());
        timing.addProperty("toolRuntimeMs", longValue(timing, "toolRuntimeMs", 0) + toolRuntimeMs);
        timing.addProperty("agentThinkTimeMs", longValue(timing, "agentThinkTimeMs", 0) + agentThinkTimeMs);
        timing.addProperty("totalWallClockMs", Math.max(0,
                now - longValue(timing, "createdAtEpochMs", now)));
        if (!timing.has("stepTimings") || !timing.get("stepTimings").isJsonArray()) {
            timing.add("stepTimings", new JsonArray());
        }
        session.add("timing", timing);
    }

    private static JsonObject stepTiming(JsonObject session, String slotId) {
        JsonObject timing = requiredObject(session, "timing");
        JsonArray steps = optionalArray(timing, "stepTimings");
        for (JsonElement elem : steps) {
            if (elem.isJsonObject() && slotId.equals(stringValue(elem.getAsJsonObject(), "slotId", ""))) {
                return elem.getAsJsonObject();
            }
        }
        JsonObject step = new JsonObject();
        step.addProperty("slotId", slotId);
        step.addProperty("candidateGenerationMs", 0);
        step.addProperty("selectionValidationMs", 0);
        step.addProperty("quickPreflightMs", 0);
        step.addProperty("previewRenderMs", 0);
        step.addProperty("agentThinkTimeMs", 0);
        step.addProperty("rejectedSelectionCount", 0);
        steps.add(step);
        return step;
    }

    private static JsonObject designTimeReport(JsonObject session) {
        ensureSession(session);
        JsonObject timing = session.has("timing") && session.get("timing").isJsonObject()
                ? session.getAsJsonObject("timing")
                : new JsonObject();
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", DESIGN_TIME_REPORT_SCHEMA);
        report.addProperty("cityId", requiredString(session, "cityId"));
        report.addProperty("sessionId", requiredString(session, "sessionId"));
        report.addProperty("slotCount", optionalArray(session, "placementOrder").size());
        report.addProperty("selectedAnchorCount", optionalArray(session, "selectedAnchors").size());
        report.addProperty("rejectedSelectionCount", optionalArray(session, "rejectedSelections").size());
        report.addProperty("totalWallClockMs", longValue(timing, "totalWallClockMs", 0));
        report.addProperty("toolRuntimeMs", longValue(timing, "toolRuntimeMs", 0));
        report.addProperty("agentThinkTimeMs", longValue(timing, "agentThinkTimeMs", 0));
        report.add("stepTimings", optionalArray(timing, "stepTimings").deepCopy());
        JsonObject failureReasons = new JsonObject();
        for (JsonElement elem : optionalArray(session, "rejectedSelections")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            String reason = stringValue(elem.getAsJsonObject(), "reasonCode", "UNKNOWN");
            failureReasons.addProperty(reason, intValue(failureReasons, reason, 0) + 1);
        }
        report.add("failureReasons", failureReasons);
        return report;
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

    private static long longValue(JsonObject obj, String key, long defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : defaultValue;
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

    public record SessionResult(JsonObject session, JsonObject qualityReport, JsonObject designTimeReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.addProperty("sessionId", session.get("sessionId").getAsString());
            obj.addProperty("currentSlotId", stringValue(session, "currentSlotId", ""));
            obj.addProperty("selectedAnchorCount", intValue(session, "selectedAnchorCount", 0));
            obj.addProperty("remainingSlotCount", intValue(session, "remainingSlotCount", 0));
            obj.addProperty("quickPreflightStatus", "deferred_to_d6");
            obj.add("session", session.deepCopy());
            obj.add("qualityReport", qualityReport.deepCopy());
            obj.add("designTimeReport", designTimeReport.deepCopy());
            obj.add("timingMs", requiredObject(session, "timing").deepCopy());
            return obj;
        }
    }

    public record NextCandidateResult(JsonObject session, JsonObject slotCandidateSet, JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.addProperty("sessionId", slotCandidateSet.get("sessionId").getAsString());
            obj.addProperty("currentSlotId", stringValue(slotCandidateSet, "currentSlotId", ""));
            obj.addProperty("quickPreflightStatus", "deferred_to_d6");
            obj.add("session", session.deepCopy());
            obj.add("slotCandidateSet", slotCandidateSet.deepCopy());
            obj.add("qualityReport", qualityReport.deepCopy());
            obj.add("timingMs", slotCandidateSet.getAsJsonObject("timingMs").deepCopy());
            return obj;
        }
    }

    public record SelectionResult(JsonObject session, JsonObject selectedAnchor,
                                  JsonObject quickPreflightReport, JsonObject designTimeReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", true);
            obj.addProperty("sessionId", session.get("sessionId").getAsString());
            obj.addProperty("selectedAnchorCount", intValue(session, "selectedAnchorCount", 0));
            obj.addProperty("remainingSlotCount", intValue(session, "remainingSlotCount", 0));
            obj.addProperty("nextSlotId", stringValue(session, "currentSlotId", ""));
            obj.add("selectedAnchor", selectedAnchor.deepCopy());
            obj.add("quickPreflightReport", quickPreflightReport.deepCopy());
            obj.add("session", session.deepCopy());
            obj.add("designTimeReport", designTimeReport.deepCopy());
            obj.add("timingMs", requiredObject(session, "timing").deepCopy());
            return obj;
        }
    }

    public record FinalizeResult(JsonObject session, JsonObject structureAnchorPlan, JsonObject designTimeReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", true);
            obj.addProperty("sessionId", session.get("sessionId").getAsString());
            obj.addProperty("status", "finalized");
            obj.addProperty("selectedAnchorCount", intValue(session, "selectedAnchorCount", 0));
            obj.add("structureAnchorPlan", structureAnchorPlan.deepCopy());
            obj.add("designTimeReport", designTimeReport.deepCopy());
            obj.add("timingMs", requiredObject(session, "timing").deepCopy());
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
                                  CityStructureCandidateEnvelope.Estimate estimate, Score score) {
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
