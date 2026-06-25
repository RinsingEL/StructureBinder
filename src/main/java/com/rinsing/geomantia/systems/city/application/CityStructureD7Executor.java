package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.BuildableAreaMap;
import com.rinsing.geomantia.systems.city.domain.model.CityFunctionType;
import com.rinsing.geomantia.systems.city.domain.model.CityQualityReport;
import com.rinsing.geomantia.systems.city.domain.model.FunctionZoneMap;
import com.rinsing.geomantia.systems.city.domain.model.PlanningGrid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

public final class CityStructureD7Executor {
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int MAX_CANDIDATES_PER_TASK = 96;
    private static final int MAX_ATTEMPTS_PER_TASK = 4;

    public Result execute(FunctionZoneMap zoneMap,
                          BuildableAreaMap buildableAreaMap,
                          JsonObject plannedFixedPlacementMap,
                          JsonObject structurePoolMap,
                          long worldSeed,
                          PlacementBackend backend) {
        return execute(zoneMap, buildableAreaMap, plannedFixedPlacementMap, structurePoolMap, worldSeed, backend, null);
    }

    public Result execute(FunctionZoneMap zoneMap,
                          BuildableAreaMap buildableAreaMap,
                          JsonObject plannedFixedPlacementMap,
                          JsonObject structurePoolMap,
                          long worldSeed,
                          PlacementBackend backend,
                          JsonObject previousPlacedStructureMap) {
        if (zoneMap == null) {
            throw new IllegalArgumentException("FunctionZoneMap is required for D7.");
        }
        if (buildableAreaMap == null) {
            throw new IllegalArgumentException("BuildableAreaMap is required for D7.");
        }
        if (plannedFixedPlacementMap == null) {
            throw new IllegalArgumentException("PlannedFixedPlacementMap is required for D7.");
        }
        if (structurePoolMap == null) {
            throw new IllegalArgumentException("StructurePoolMap is required for D7.");
        }
        if (backend == null) {
            backend = PlacementBackend.traceOnly();
        }
        String cityId = requiredString(plannedFixedPlacementMap, "cityId");
        if (!cityId.equals(zoneMap.cityId()) || !cityId.equals(buildableAreaMap.cityId())
                || !cityId.equals(requiredString(structurePoolMap, "cityId"))) {
            throw new IllegalArgumentException("D7 input cityId mismatch.");
        }

        ZoneContext zones = new ZoneContext(zoneMap, buildableAreaMap);
        JsonArray fixedAttempts = new JsonArray();
        JsonArray variableAttempts = new JsonArray();
        JsonArray placedStructures = new JsonArray();
        List<Placed> placed = previousPlaced(previousPlacedStructureMap);
        placed.forEach(existing -> placedStructures.add(existing.asJson()));
        Map<String, Integer> remainingByZone = initialRemaining(zones);
        placed.forEach(existing -> remainingByZone.computeIfPresent(existing.zonePatchId(),
                (id, value) -> Math.max(0, value - existing.visibleAreaCost())));
        Map<String, Integer> failureSummary = new LinkedHashMap<>();
        Map<String, Integer> waitingSummary = new LinkedHashMap<>();
        List<String> hardBlocks = new ArrayList<>();
        boolean waitingForChunks = false;

        List<JsonObject> fixedPlacements = jsonObjects(requiredArray(plannedFixedPlacementMap, "placements"));
        fixedPlacements.sort(Comparator.comparingInt(obj -> intValue(obj, "priority", 100)));
        for (JsonObject fixed : fixedPlacements) {
            if (alreadyPlacedFixed(fixed, placed)) {
                fixedAttempts.add(alreadyPlacedAttempt(fixed));
                continue;
            }
            AttemptResult result = executeFixed(fixed, zones, placed, backend);
            fixedAttempts.add(result.attemptJson());
            if (result.placed() != null) {
                placed.add(result.placed());
                placedStructures.add(result.placed().asJson());
                remainingByZone.computeIfPresent(result.placed().zonePatchId(),
                        (id, value) -> Math.max(0, value - result.placed().visibleAreaCost()));
            } else if (result.waiting()) {
                waitingForChunks = true;
                increment(waitingSummary, result.reasonCode());
                break;
            } else {
                increment(failureSummary, result.reasonCode());
                String policy = stringValue(fixed, "failurePolicy", "block_city");
                if ("block_city".equals(policy)) {
                    hardBlocks.add("FIXED_FOOTPRINT_INVALID:" + requiredString(fixed, "placementId"));
                    break;
                }
            }
        }

        JsonArray startCandidateSets = new JsonArray();
        if (hardBlocks.isEmpty() && !waitingForChunks) {
            for (JsonObject pool : jsonObjects(requiredArray(structurePoolMap, "zonePools"))) {
                String zoneId = requiredString(pool, "zonePatchId");
                ZoneInfo zone = zones.byId(zoneId);
                if (zone == null) {
                    increment(failureSummary, "FOOTPRINT_OUT_OF_ZONE");
                    continue;
                }
                for (JsonElement elem : arrayValue(pool, "variableSelections", new JsonArray())) {
                    JsonObject selection = elem.getAsJsonObject();
                    VariableTask task = task(zone, selection, remainingByZone.getOrDefault(zoneId, 0));
                    if (alreadyPlacedVariable(task, placed)) {
                        variableAttempts.add(alreadyPlacedVariableAttempt(task, zone));
                        continue;
                    }
                    JsonObject startSet = buildStartCandidateSet(cityId, worldSeed, zone, task, placed);
                    startCandidateSets.add(startSet);
                    AttemptResult result = executeVariable(startSet, task, zone, placed, backend);
                    variableAttempts.addAll(result.attempts());
                    if (result.placed() != null) {
                        placed.add(result.placed());
                        placedStructures.add(result.placed().asJson());
                        remainingByZone.computeIfPresent(result.placed().zonePatchId(),
                                (id, value) -> Math.max(0, value - result.placed().visibleAreaCost()));
                    } else if (result.waiting()) {
                        increment(waitingSummary, result.reasonCode());
                    } else {
                        increment(failureSummary, result.reasonCode());
                    }
                }
            }
        }

        JsonObject placedMap = placedMap(cityId, placedStructures, remainingByZone, hardBlocks);
        JsonObject trace = trace(cityId, fixedAttempts, variableAttempts, placedStructures, remainingByZone,
                failureSummary, waitingSummary, hardBlocks);
        JsonObject quality = quality(fixedAttempts, variableAttempts, placedStructures, startCandidateSets, hardBlocks,
                failureSummary, waitingSummary);
        return new Result(startCandidateSets, placedMap, trace, quality);
    }

    private AttemptResult executeFixed(JsonObject fixed, ZoneContext zones, List<Placed> placed,
                                       PlacementBackend backend) {
        String structureId = requiredString(fixed, "structureId");
        String placementKind = requiredString(fixed, "placementKind");
        String sampleType = requiredString(fixed, "sampleType");
        String placementId = requiredString(fixed, "placementId");
        String landingCandidateId = requiredString(fixed, "landingCandidateId");
        BlockBounds footprint = bounds(requiredObject(fixed, "footprint"));
        BlockBounds clearance = fixed.has("clearanceFootprint") && fixed.get("clearanceFootprint").isJsonObject()
                ? bounds(fixed.getAsJsonObject("clearanceFootprint"))
                : footprint;
        BlockPoint anchor = blockPoint(requiredObject(fixed, "validatedAnchorBlock"));
        BlockPoint commandAnchor = commandAnchor(footprint, fixed);
        JsonObject attempt = baseAttempt("fixed", placementId, requiredString(fixed, "zonePatchId"), structureId,
                placementKind, stringValue(fixed, "placementCommand", ""), landingCandidateId, anchor,
                requiredString(fixed, "rotation"), 0);
        attempt.add("commandAnchorBlock", commandAnchor.asJson());
        attempt.add("requiredPlacementBounds", boundsJson(clearance));
        attempt.add("requiredChunkRange", chunkRangeJson(clearance));
        if (!isD7Eligible(structureId, sampleType, placementKind)) {
            return failed(attempt, "CONFIGURED_STRUCTURE_REGISTRY_MISSING", "Not a D7 configured structure candidate.");
        }
        ZoneInfo zone = zones.byId(requiredString(fixed, "zonePatchId"));
        if (zone == null || !zone.covers(clearance)) {
            return failed(attempt, "FOOTPRINT_OUT_OF_ZONE", "Fixed footprint no longer fits D5 buildable area.");
        }
        if (conflicts(clearance, placed)) {
            return failed(attempt, "AABB_OCCUPIED", "Fixed footprint conflicts with already placed structure.");
        }
        PlacementRequest request = new PlacementRequest(structureId, placementKind,
                stringValue(fixed, "placementCommand", ""), commandAnchor, requiredString(fixed, "rotation"), footprint,
                clearance, "fixed_footprint");
        PlacementResult placement = backend.place(request);
        if (placement.waiting()) {
            return waiting(attempt, placement.reasonCode(), placement.message());
        }
        if (!placement.success()) {
            return failed(attempt, placement.reasonCode(), placement.message());
        }
        attempt.addProperty("status", "placed");
        attempt.addProperty("reasonCode", "");
        attempt.addProperty("message", placement.message());
        Placed placedStructure = new Placed("placed_" + placementId, placementId, landingCandidateId,
                requiredString(fixed, "zonePatchId"), structureId, "fixed_footprint", placementKind,
                stringValue(fixed, "placementCommand", ""), anchor, commandAnchor, requiredString(fixed, "rotation"),
                footprint, clearance, intValue(fixed, "visibleAreaCost", area(footprint)),
                placement.worldMutationApplied());
        return new AttemptResult(attempt, new JsonArray(), placedStructure, "");
    }

    private AttemptResult executeVariable(JsonObject startSet, VariableTask task, ZoneInfo zone,
                                          List<Placed> placed, PlacementBackend backend) {
        JsonArray attempts = new JsonArray();
        List<JsonObject> candidates = jsonObjects(requiredArray(startSet, "candidates")).stream()
                .filter(candidate -> boolValue(candidate, "hardPassed", false))
                .sorted(Comparator.comparingDouble(candidate -> -doubleValue(candidate, "score", 0)))
                .toList();
        if (candidates.isEmpty()) {
            JsonObject attempt = baseAttempt("variable", task.taskId(), zone.zonePatchId(), task.structureId(),
                    task.placementKind(), task.placementCommand(), "", new BlockPoint(0, 0), "NONE", 0);
            attempts.add(failedJson(attempt, "NO_START_CANDIDATE", "No hard-passed StartCandidateSet candidate."));
            return new AttemptResult(new JsonObject(), attempts, null, "NO_START_CANDIDATE");
        }
        Set<String> tried = new HashSet<>();
        String lastReason = "START_RETRY_BUDGET_EXHAUSTED";
        for (int retry = 0; retry < Math.min(task.retryBudget(), MAX_ATTEMPTS_PER_TASK); retry++) {
            JsonObject candidate = chooseCandidate(candidates, task.seedKey() + ":" + retry, tried);
            if (candidate == null) {
                lastReason = "START_RETRY_BUDGET_EXHAUSTED";
                break;
            }
            tried.add(requiredString(candidate, "startCandidateId"));
            BlockBounds footprint = bounds(requiredObject(candidate, "candidateFootprint"));
            BlockBounds requiredBounds = bounds(requiredObject(candidate, "requiredPlacementBounds"));
            BlockPoint anchor = blockPoint(requiredObject(candidate, "anchorBlock"));
            JsonObject attempt = baseAttempt("variable", task.taskId(), zone.zonePatchId(), task.structureId(),
                    task.placementKind(), task.placementCommand(), requiredString(candidate, "startCandidateId"),
                    anchor, requiredString(candidate, "rotation"), retry);
            attempt.add("scoreBreakdown", requiredObject(candidate, "scoreBreakdown"));
            attempt.add("requiredPlacementBounds", boundsJson(requiredBounds));
            attempt.add("requiredChunkRange", chunkRangeJson(requiredBounds));
            String validatorReason = validatorFailure(zone, footprint, placed, task);
            if (!validatorReason.isBlank()) {
                lastReason = validatorReason;
                attempts.add(failedJson(attempt, validatorReason, "D7 validator rejected start candidate."));
                continue;
            }
            PlacementRequest request = new PlacementRequest(task.structureId(), task.placementKind(),
                    task.placementCommand(), anchor, requiredString(candidate, "rotation"), footprint, requiredBounds,
                    "variable_area", task.materializationMode(), constraintField(zone, placed), task.targetAreaBlocks());
            PlacementResult placement = "bounded_jigsaw".equals(task.materializationMode())
                    ? backend.placeBoundedJigsaw(request)
                    : backend.place(request);
            if (placement.waiting()) {
                attempts.add(waitingJson(attempt, placement.reasonCode(), placement.message()));
                return new AttemptResult(new JsonObject(), attempts, null, placement.reasonCode(), true);
            }
            if (!placement.success()) {
                lastReason = placement.reasonCode();
                attempts.add(failedJson(attempt, placement.reasonCode(), placement.message()));
                continue;
            }
            if (placement.trace() != null) {
                attempt.add("boundedJigsawTrace", placement.trace());
            }
            attempt.addProperty("status", "placed");
            attempt.addProperty("reasonCode", "");
            attempt.addProperty("message", placement.message());
            attempts.add(attempt);
            BlockBounds placedFootprint = placement.footprint() == null ? footprint : placement.footprint();
            BlockBounds placedClearance = placement.requiredLoadBounds() == null ? requiredBounds : placement.requiredLoadBounds();
            Placed placedStructure = new Placed("placed_" + task.taskId(), task.selectionId(),
                    requiredString(candidate, "startCandidateId"), zone.zonePatchId(), task.structureId(),
                    "variable_area", task.placementKind(), task.placementCommand(), anchor, anchor,
                    requiredString(candidate, "rotation"), placedFootprint, placedClearance,
                    Math.min(task.targetAreaBlocks(), area(placedFootprint)), placement.worldMutationApplied());
            return new AttemptResult(new JsonObject(), attempts, placedStructure, "");
        }
        return new AttemptResult(new JsonObject(), attempts, null, lastReason);
    }

    private JsonObject buildStartCandidateSet(String cityId, long worldSeed, ZoneInfo zone, VariableTask task,
                                              List<Placed> placed) {
        JsonObject set = new JsonObject();
        set.addProperty("schemaVersion", "city_start_candidate_set.v0.1");
        set.addProperty("cityId", cityId);
        set.addProperty("zonePatchId", zone.zonePatchId());
        set.addProperty("taskId", task.taskId());
        set.addProperty("structureId", task.structureId());
        set.addProperty("seedKey", task.seedKey());
        JsonArray candidates = new JsonArray();
        int index = 1;
        for (CellAnchor anchor : zone.anchors()) {
            for (String rotation : task.rotations()) {
                BlockBounds footprint = task.startFootprint().boundsAt(anchor.blockMinX(), anchor.blockMinZ(), rotation);
                boolean hard = zone.covers(footprint) && !conflicts(footprint, placed) && area(footprint) <= task.targetAreaBlocks();
                BlockBounds requiredBounds = task.requiredBounds(footprint.center());
                JsonObject candidate = new JsonObject();
                candidate.addProperty("startCandidateId", task.taskId() + "_start_" + String.format(Locale.ROOT, "%03d", index++));
                candidate.add("anchorBlock", footprint.center().asJson());
                candidate.addProperty("rotation", rotation);
                candidate.add("candidateFootprint", boundsJson(footprint));
                candidate.add("requiredPlacementBounds", boundsJson(requiredBounds));
                candidate.add("requiredChunkRange", chunkRangeJson(requiredBounds));
                candidate.addProperty("hardPassed", hard);
                double score = score(zone, footprint, worldSeed, task.seedKey());
                candidate.addProperty("score", hard ? score : 0);
                JsonObject scoreBreakdown = new JsonObject();
                scoreBreakdown.addProperty("interiorScore", score);
                scoreBreakdown.addProperty("buildableFit", hard ? 1.0 : 0.0);
                candidate.add("scoreBreakdown", scoreBreakdown);
                JsonArray risks = new JsonArray();
                if (!hard) {
                    risks.add("hard_filter_failed");
                }
                candidate.add("riskFlags", risks);
                candidates.add(candidate);
                if (candidates.size() >= MAX_CANDIDATES_PER_TASK) {
                    set.add("candidates", candidates);
                    return set;
                }
            }
        }
        set.add("candidates", candidates);
        return set;
    }

    private JsonObject chooseCandidate(List<JsonObject> candidates, String seedKey, Set<String> tried) {
        List<JsonObject> pool = candidates.stream()
                .filter(candidate -> !tried.contains(requiredString(candidate, "startCandidateId")))
                .toList();
        if (pool.isEmpty()) {
            return null;
        }
        double total = pool.stream().mapToDouble(candidate -> Math.max(1.0, doubleValue(candidate, "score", 1))).sum();
        double pick = new Random(seedKey.hashCode()).nextDouble() * total;
        double cursor = 0;
        for (JsonObject candidate : pool) {
            cursor += Math.max(1.0, doubleValue(candidate, "score", 1));
            if (cursor >= pick) {
                return candidate;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private JsonObject constraintField(ZoneInfo zone, List<Placed> placed) {
        JsonObject obj = zone.asConstraintJson();
        JsonArray occupied = new JsonArray();
        for (Placed existing : placed) {
            JsonObject item = new JsonObject();
            item.addProperty("placedId", existing.placedId());
            item.addProperty("structureId", existing.structureId());
            item.addProperty("footprintMode", existing.footprintMode());
            item.add("footprint", boundsJson(existing.clearanceFootprint()));
            occupied.add(item);
        }
        obj.add("occupiedFootprints", occupied);
        return obj;
    }

    private String validatorFailure(ZoneInfo zone, BlockBounds footprint, List<Placed> placed, VariableTask task) {
        if (!zone.covers(footprint)) {
            return "START_FOOTPRINT_OUT_OF_ZONE";
        }
        if (conflicts(footprint, placed)) {
            return "START_RUNTIME_OCCUPIED";
        }
        if (area(footprint) > task.targetAreaBlocks()) {
            return "START_BUDGET_EXCEEDED";
        }
        return "";
    }

    private BlockPoint commandAnchor(BlockBounds footprint, JsonObject placement) {
        JsonObject offset = objectValue(placement, "footprintOriginOffset", new JsonObject());
        int offsetX = intValue(offset, "x", 0);
        int offsetZ = intValue(offset, "z", 0);
        return new BlockPoint(footprint.minX() - offsetX, footprint.minZ() - offsetZ);
    }

    private VariableTask task(ZoneInfo zone, JsonObject selection, int remainingArea) {
        String selectionId = requiredString(selection, "selectionId");
        String structureId = requiredString(selection, "structureId");
        String taskId = "task_" + safeId(zone.zonePatchId()) + "_" + safeId(selectionId);
        Footprint footprint = footprint(requiredObject(selection, "startFootprint"));
        if (!footprint.valid()) {
            footprint = new Footprint(16, 16, 12);
        }
        int targetArea = intValue(selection, "targetVisibleAreaBlocks", 0);
        if (targetArea <= 0) {
            targetArea = Math.max(1, (int) Math.round(remainingArea * doubleValue(selection, "targetVisibleAreaRatio", 0.1)));
        }
        JsonObject expectedRange = objectValue(selection, "expectedAreaRange", new JsonObject());
        int maxArea = Math.max(targetArea, intValue(expectedRange, "maxAreaBlocks", targetArea));
        String seedKey = zone.zonePatchId() + ":" + taskId + ":" + structureId;
        return new VariableTask(selectionId, taskId, structureId,
                requiredString(selection, "placementKind"),
                stringValue(selection, "placementCommand", ""),
                stringValue(selection, "materializationMode", "minecraft_place_structure"),
                targetArea,
                Math.max(1, intValue(selection, "weight", 1)),
                footprint,
                maxArea,
                List.of("NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"),
                seedKey,
                MAX_ATTEMPTS_PER_TASK);
    }

    private boolean conflicts(BlockBounds bounds, List<Placed> placed) {
        return placed.stream().anyMatch(existing -> existing.clearanceFootprint().overlaps(bounds));
    }

    private AttemptResult failed(JsonObject attempt, String reasonCode, String message) {
        return new AttemptResult(failedJson(attempt, reasonCode, message), new JsonArray(), null, reasonCode);
    }

    private AttemptResult waiting(JsonObject attempt, String reasonCode, String message) {
        return new AttemptResult(waitingJson(attempt, reasonCode, message), new JsonArray(), null, reasonCode, true);
    }

    private JsonObject failedJson(JsonObject attempt, String reasonCode, String message) {
        attempt.addProperty("status", "failed");
        attempt.addProperty("reasonCode", reasonCode);
        attempt.addProperty("message", message);
        return attempt;
    }

    private JsonObject waitingJson(JsonObject attempt, String reasonCode, String message) {
        attempt.addProperty("status", "waiting");
        attempt.addProperty("reasonCode", reasonCode);
        attempt.addProperty("message", message);
        return attempt;
    }

    private JsonObject baseAttempt(String footprintMode, String taskId, String zonePatchId, String structureId,
                                   String placementKind, String placementCommand, String anchorCandidateId,
                                   BlockPoint candidateBlock, String rotation, int retryIndex) {
        JsonObject attempt = new JsonObject();
        attempt.addProperty("taskId", taskId);
        attempt.addProperty("zonePatchId", zonePatchId);
        attempt.addProperty("structureId", structureId);
        attempt.addProperty("footprintMode", footprintMode);
        attempt.addProperty("placementKind", placementKind);
        attempt.addProperty("placementCommand", placementCommand);
        attempt.addProperty("anchorCandidateId", anchorCandidateId);
        attempt.add("candidateBlock", candidateBlock.asJson());
        attempt.addProperty("rotation", rotation);
        attempt.addProperty("retryIndex", retryIndex);
        return attempt;
    }

    private JsonObject placedMap(String cityId, JsonArray placedStructures, Map<String, Integer> remainingByZone,
                                 List<String> hardBlocks) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_placed_structure_map.v0.1");
        obj.addProperty("cityId", cityId);
        obj.add("placedStructures", placedStructures);
        obj.add("remainingVisibleAreaByZone", remainingJson(remainingByZone));
        obj.add("quality", new CityQualityReport(hardBlocks.isEmpty(), hardBlocks.isEmpty() ? 100 : 0,
                hardBlocks, List.of(), List.of(), metric("placedStructureCount", placedStructures.size())).asJson());
        return obj;
    }

    private JsonObject trace(String cityId, JsonArray fixedAttempts, JsonArray variableAttempts,
                             JsonArray placedStructures, Map<String, Integer> remainingByZone,
                             Map<String, Integer> failureSummary, Map<String, Integer> waitingSummary,
                             List<String> hardBlocks) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_structure_generation_trace.v0.1");
        obj.addProperty("cityId", cityId);
        obj.addProperty("status", hardBlocks.isEmpty()
                ? (!waitingSummary.isEmpty() ? "waiting" : (failureSummary.isEmpty() ? "passed" : "partial"))
                : "failed");
        JsonArray attempts = new JsonArray();
        attempts.addAll(fixedAttempts);
        attempts.addAll(variableAttempts);
        obj.add("attempts", attempts);
        obj.add("fixedPlacements", fixedAttempts);
        obj.add("variableAttempts", variableAttempts);
        obj.add("placedStructures", placedStructures);
        obj.add("remainingVisibleAreaByZone", remainingJson(remainingByZone));
        obj.add("failureSummary", failureJson(failureSummary));
        obj.add("waitingSummary", failureJson(waitingSummary));
        JsonArray debugRefs = new JsonArray();
        debugRefs.add("start_candidate_preview.png");
        debugRefs.add("placed_structure_preview.png");
        obj.add("debugRefs", debugRefs);
        return obj;
    }

    private JsonObject quality(JsonArray fixedAttempts, JsonArray variableAttempts, JsonArray placedStructures,
                               JsonArray startCandidateSets, List<String> hardBlocks,
                               Map<String, Integer> failureSummary, Map<String, Integer> waitingSummary) {
        JsonObject metrics = new JsonObject();
        metrics.addProperty("fixedAttemptCount", fixedAttempts.size());
        metrics.addProperty("variableAttemptCount", variableAttempts.size());
        metrics.addProperty("startCandidateSetCount", startCandidateSets.size());
        metrics.addProperty("placedStructureCount", placedStructures.size());
        metrics.addProperty("failureReasonCount", failureSummary.size());
        metrics.addProperty("waitingReasonCount", waitingSummary.size());
        int score = Math.max(0, 100 - hardBlocks.size() * 50 - failureSummary.size() * 8 - waitingSummary.size() * 2);
        List<String> warnings = waitingSummary.isEmpty() ? List.of() : List.of("D7 waiting for loaded chunks.");
        return new CityQualityReport(hardBlocks.isEmpty(), score, hardBlocks, warnings, List.of(), metrics).asJson();
    }

    private Map<String, Integer> initialRemaining(ZoneContext zones) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        for (ZoneInfo zone : zones.zones()) {
            remaining.put(zone.zonePatchId(), zone.buildableAreaBlocks());
        }
        return remaining;
    }

    private static boolean isD7Eligible(String structureId, String sampleType, String placementKind) {
        return validResourceId(structureId)
                && "structure_assembly".equals(sampleType)
                && "minecraft_place_structure".equals(placementKind);
    }

    private static double score(ZoneInfo zone, BlockBounds footprint, long worldSeed, String seedKey) {
        BlockPoint center = footprint.center();
        BlockPoint zoneCenter = zone.bounds().center();
        double distance = Math.hypot(center.x() - zoneCenter.x(), center.z() - zoneCenter.z());
        double jitter = new Random((seedKey + ":" + worldSeed + ":" + center.x() + ":" + center.z()).hashCode()).nextDouble();
        return Math.max(1.0, 1000.0 - distance + jitter);
    }

    private static int area(BlockBounds bounds) {
        return bounds.widthBlocks() * bounds.heightBlocks();
    }

    private static void increment(Map<String, Integer> summary, String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return;
        }
        summary.merge(reasonCode, 1, Integer::sum);
    }

    private static JsonObject failureJson(Map<String, Integer> failureSummary) {
        JsonObject obj = new JsonObject();
        failureSummary.forEach(obj::addProperty);
        return obj;
    }

    private static JsonObject remainingJson(Map<String, Integer> remaining) {
        JsonObject obj = new JsonObject();
        remaining.forEach(obj::addProperty);
        return obj;
    }

    private static JsonObject chunkRangeJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minChunkX", chunk(bounds.minX()));
        obj.addProperty("minChunkZ", chunk(bounds.minZ()));
        obj.addProperty("maxChunkX", chunk(bounds.maxX()));
        obj.addProperty("maxChunkZ", chunk(bounds.maxZ()));
        return obj;
    }

    private static int chunk(int blockCoord) {
        return Math.floorDiv(blockCoord, 16);
    }

    private static BlockBounds boundsAround(BlockPoint center, int radiusBlocks) {
        int radius = Math.max(1, radiusBlocks);
        return new BlockBounds(center.x() - radius, center.z() - radius,
                center.x() + radius, center.z() + radius);
    }

    private static JsonObject metric(String key, int value) {
        JsonObject metrics = new JsonObject();
        metrics.addProperty(key, value);
        return metrics;
    }

    private static List<JsonObject> jsonObjects(JsonArray array) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement elem : array) {
            if (elem.isJsonObject()) {
                result.add(elem.getAsJsonObject());
            }
        }
        return result;
    }

    private static Footprint footprint(JsonObject obj) {
        return new Footprint(
                firstInt(obj, 0, "widthBlocks", "width", "x"),
                firstInt(obj, 0, "depthBlocks", "depth", "z"),
                firstInt(obj, 0, "heightBlocks", "height", "y"));
    }

    private static int firstInt(JsonObject obj, int defaultValue, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsInt();
            }
        }
        return defaultValue;
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static BlockPoint blockPoint(JsonObject obj) {
        return new BlockPoint(intValue(obj, "x", 0), intValue(obj, "z", 0));
    }

    private static boolean validResourceId(String id) {
        return id != null && RESOURCE_ID.matcher(id).matches();
    }

    private static boolean alreadyPlacedFixed(JsonObject fixed, List<Placed> placed) {
        String placementId = requiredString(fixed, "placementId");
        String structureId = requiredString(fixed, "structureId");
        String candidateId = requiredString(fixed, "landingCandidateId");
        return placed.stream().anyMatch(existing -> existing.worldMutationApplied()
                && "fixed_footprint".equals(existing.footprintMode())
                && placementId.equals(existing.sourceSelectionId())
                && structureId.equals(existing.structureId())
                && candidateId.equals(existing.anchorCandidateId()));
    }

    private static boolean alreadyPlacedVariable(VariableTask task, List<Placed> placed) {
        return placed.stream().anyMatch(existing -> existing.worldMutationApplied()
                && "variable_area".equals(existing.footprintMode())
                && task.selectionId().equals(existing.sourceSelectionId())
                && task.structureId().equals(existing.structureId()));
    }

    private JsonObject alreadyPlacedAttempt(JsonObject fixed) {
        BlockBounds footprint = bounds(requiredObject(fixed, "footprint"));
        BlockBounds clearance = objectValue(fixed, "clearanceFootprint", null) == null
                ? footprint
                : bounds(requiredObject(fixed, "clearanceFootprint"));
        JsonObject attempt = baseAttempt("fixed", requiredString(fixed, "placementId"),
                requiredString(fixed, "zonePatchId"), requiredString(fixed, "structureId"),
                requiredString(fixed, "placementKind"), stringValue(fixed, "placementCommand", ""),
                requiredString(fixed, "landingCandidateId"), blockPoint(requiredObject(fixed, "validatedAnchorBlock")),
                requiredString(fixed, "rotation"), 0);
        attempt.add("commandAnchorBlock", commandAnchor(footprint, fixed).asJson());
        attempt.add("requiredPlacementBounds", boundsJson(clearance));
        attempt.add("requiredChunkRange", chunkRangeJson(clearance));
        attempt.addProperty("status", "already_placed");
        attempt.addProperty("reasonCode", "");
        attempt.addProperty("message", "Existing real placement ledger entry reused.");
        return attempt;
    }

    private JsonObject alreadyPlacedVariableAttempt(VariableTask task, ZoneInfo zone) {
        JsonObject attempt = baseAttempt("variable", task.taskId(), zone.zonePatchId(), task.structureId(),
                task.placementKind(), task.placementCommand(), "", BlockPoint.ORIGIN, "NONE", 0);
        attempt.addProperty("status", "already_placed");
        attempt.addProperty("reasonCode", "");
        attempt.addProperty("message", "Existing real placement ledger entry reused.");
        return attempt;
    }

    private static List<Placed> previousPlaced(JsonObject previousPlacedStructureMap) {
        if (previousPlacedStructureMap == null || !previousPlacedStructureMap.has("placedStructures")
                || !previousPlacedStructureMap.get("placedStructures").isJsonArray()) {
            return new ArrayList<>();
        }
        List<Placed> placed = new ArrayList<>();
        for (JsonElement elem : previousPlacedStructureMap.getAsJsonArray("placedStructures")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject obj = elem.getAsJsonObject();
            if (!boolValue(obj, "worldMutationApplied", false)) {
                continue;
            }
            BlockBounds footprint = bounds(requiredObject(obj, "footprint"));
            placed.add(new Placed(
                    requiredString(obj, "placedId"),
                    requiredString(obj, "sourceSelectionId"),
                    requiredString(obj, "anchorCandidateId"),
                    requiredString(obj, "zonePatchId"),
                    requiredString(obj, "structureId"),
                    requiredString(obj, "footprintMode"),
                    requiredString(obj, "placementKind"),
                    stringValue(obj, "placementCommand", ""),
                    blockPoint(requiredObject(obj, "anchorBlock")),
                    blockPoint(requiredObject(obj, "commandAnchorBlock")),
                    requiredString(obj, "rotation"),
                    footprint,
                    bounds(objectValue(obj, "clearanceFootprint", boundsJson(footprint))),
                    intValue(obj, "visibleAreaCost", area(footprint)),
                    true));
        }
        return placed;
    }

    private static String safeId(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsInt();
    }

    private static double doubleValue(JsonObject obj, String key, double defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsDouble();
    }

    private static boolean boolValue(JsonObject obj, String key, boolean defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsBoolean();
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required.");
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonArray arrayValue(JsonObject obj, String key, JsonArray defaultValue) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : defaultValue;
    }

    private static JsonObject requiredObject(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required.");
        }
        return obj.getAsJsonObject(key);
    }

    private static JsonObject objectValue(JsonObject obj, String key, JsonObject defaultValue) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : defaultValue;
    }

    private record Footprint(int widthBlocks, int depthBlocks, int heightBlocks) {
        boolean valid() {
            return widthBlocks > 0 && depthBlocks > 0;
        }

        BlockBounds boundsAt(int minX, int minZ, String rotation) {
            int width = widthBlocks;
            int depth = depthBlocks;
            if ("CLOCKWISE_90".equals(rotation) || "COUNTERCLOCKWISE_90".equals(rotation)) {
                width = depthBlocks;
                depth = widthBlocks;
            }
            return new BlockBounds(minX, minZ, minX + width - 1, minZ + depth - 1);
        }
    }

    private record VariableTask(String selectionId, String taskId, String structureId, String placementKind,
                                String placementCommand, String materializationMode, int targetAreaBlocks, int weight,
                                Footprint startFootprint, int maxAreaBlocks, List<String> rotations, String seedKey,
                                int retryBudget) {
        BlockBounds requiredBounds(BlockPoint anchor) {
            int side = (int) Math.ceil(Math.sqrt(Math.max(maxAreaBlocks, targetAreaBlocks)));
            int radius = Math.max(Math.max(startFootprint.widthBlocks(), startFootprint.depthBlocks()),
                    Math.max(16, (side + 1) / 2));
            return boundsAround(anchor, radius + 16);
        }
    }

    private record AttemptResult(JsonObject attemptJson, JsonArray attempts, Placed placed, String reasonCode,
                                 boolean waiting) {
        AttemptResult(JsonObject attemptJson, JsonArray attempts, Placed placed, String reasonCode) {
            this(attemptJson, attempts, placed, reasonCode, false);
        }
    }

    private record Placed(String placedId, String sourceSelectionId, String anchorCandidateId, String zonePatchId,
                          String structureId, String footprintMode, String placementKind, String placementCommand,
                          BlockPoint anchorBlock, BlockPoint commandAnchorBlock, String rotation, BlockBounds footprint,
                          BlockBounds clearanceFootprint, int visibleAreaCost, boolean worldMutationApplied) {
        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("placedId", placedId);
            obj.addProperty("sourceSelectionId", sourceSelectionId);
            obj.addProperty("anchorCandidateId", anchorCandidateId);
            obj.addProperty("zonePatchId", zonePatchId);
            obj.addProperty("structureId", structureId);
            obj.addProperty("footprintMode", footprintMode);
            obj.addProperty("placementKind", placementKind);
            obj.addProperty("placementCommand", placementCommand);
            obj.add("anchorBlock", anchorBlock.asJson());
            obj.add("commandAnchorBlock", commandAnchorBlock.asJson());
            obj.addProperty("rotation", rotation);
            obj.add("footprint", boundsJson(footprint));
            obj.add("clearanceFootprint", boundsJson(clearanceFootprint));
            obj.addProperty("visibleAreaCost", visibleAreaCost);
            obj.addProperty("worldMutationApplied", worldMutationApplied);
            return obj;
        }
    }

    private static final class ZoneContext {
        private final List<ZoneInfo> zones;
        private final Map<String, ZoneInfo> byId = new HashMap<>();

        ZoneContext(FunctionZoneMap zoneMap, BuildableAreaMap buildableAreaMap) {
            Map<String, CityFunctionType> zoneTypes = new HashMap<>();
            Map<String, BlockBounds> zoneBounds = new HashMap<>();
            zoneMap.zones().forEach(zone -> {
                zoneTypes.put(zone.zonePatchId(), zone.functionType());
                zoneBounds.put(zone.zonePatchId(), zone.cellShape());
            });
            List<ZoneInfo> infos = new ArrayList<>();
            for (BuildableAreaMap.ZoneBuildability buildable : buildableAreaMap.zones()) {
                ZoneInfo info = new ZoneInfo(zoneMap.grid(), buildable.zonePatchId(),
                        zoneTypes.get(buildable.zonePatchId()), zoneBounds.get(buildable.zonePatchId()), buildable);
                infos.add(info);
                byId.put(info.zonePatchId(), info);
            }
            this.zones = List.copyOf(infos);
        }

        List<ZoneInfo> zones() {
            return zones;
        }

        ZoneInfo byId(String zoneId) {
            return byId.get(zoneId);
        }
    }

    private static final class ZoneInfo {
        private final PlanningGrid grid;
        private final String zonePatchId;
        private final CityFunctionType functionType;
        private final BlockBounds bounds;
        private final BuildableAreaMap.ZoneBuildability buildable;
        private final Set<Long> buildableCells = new LinkedHashSet<>();
        private final List<CellAnchor> anchors = new ArrayList<>();

        ZoneInfo(PlanningGrid grid, String zonePatchId, CityFunctionType functionType, BlockBounds bounds,
                 BuildableAreaMap.ZoneBuildability buildable) {
            this.grid = grid;
            this.zonePatchId = zonePatchId;
            this.functionType = functionType;
            this.bounds = bounds;
            this.buildable = buildable;
            for (BuildableAreaMap.BuildableCell cell : buildable.buildableCells()) {
                int x = grid.blockToCellX(cell.blockMinX());
                int z = grid.blockToCellZ(cell.blockMinZ());
                buildableCells.add(key(x, z));
                anchors.add(new CellAnchor(cell.blockMinX(), cell.blockMinZ()));
            }
            anchors.sort(Comparator.comparingInt(CellAnchor::blockMinX).thenComparingInt(CellAnchor::blockMinZ));
        }

        String zonePatchId() {
            return zonePatchId;
        }

        BlockBounds bounds() {
            return bounds;
        }

        int buildableAreaBlocks() {
            return buildable.buildableAreaBlocks();
        }

        List<CellAnchor> anchors() {
            return anchors;
        }

        boolean covers(BlockBounds bounds) {
            if (bounds == null) {
                return false;
            }
            int minCellX = clampCellX(grid.blockToCellX(bounds.minX()));
            int maxCellX = clampCellX(grid.blockToCellX(bounds.maxX()));
            int minCellZ = clampCellZ(grid.blockToCellZ(bounds.minZ()));
            int maxCellZ = clampCellZ(grid.blockToCellZ(bounds.maxZ()));
            for (int x = minCellX; x <= maxCellX; x++) {
                for (int z = minCellZ; z <= maxCellZ; z++) {
                    if (!buildableCells.contains(key(x, z))) {
                        return false;
                    }
                }
            }
            return true;
        }

        JsonObject asConstraintJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("schemaVersion", "city_constraint_field.v0.1");
            obj.addProperty("zonePatchId", zonePatchId);
            obj.add("allowedArea", boundsJson(bounds));
            obj.addProperty("originBlockX", grid.originBlockX());
            obj.addProperty("originBlockZ", grid.originBlockZ());
            obj.addProperty("cellStepBlocks", grid.cellStepBlocks());
            obj.addProperty("cellsX", grid.cellsX());
            obj.addProperty("cellsZ", grid.cellsZ());
            JsonArray cells = new JsonArray();
            for (BuildableAreaMap.BuildableCell cell : buildable.buildableCells()) {
                JsonObject cellJson = new JsonObject();
                cellJson.addProperty("blockMinX", cell.blockMinX());
                cellJson.addProperty("blockMinZ", cell.blockMinZ());
                cells.add(cellJson);
            }
            obj.add("buildableCells", cells);
            return obj;
        }

        private int clampCellX(int x) {
            return Math.max(0, Math.min(grid.cellsX() - 1, x));
        }

        private int clampCellZ(int z) {
            return Math.max(0, Math.min(grid.cellsZ() - 1, z));
        }

        private long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }
    }

    private record CellAnchor(int blockMinX, int blockMinZ) {
    }

    public record PlacementRequest(String structureId, String placementKind, String placementCommand,
                                   BlockPoint anchorBlock, String rotation, BlockBounds footprint,
                                   BlockBounds requiredLoadBounds,
                                   String footprintMode, String materializationMode, JsonObject constraintField,
                                   int targetAreaBlocks) {
        public PlacementRequest(String structureId, String placementKind, String placementCommand,
                                BlockPoint anchorBlock, String rotation, BlockBounds footprint,
                                BlockBounds requiredLoadBounds,
                                String footprintMode) {
            this(structureId, placementKind, placementCommand, anchorBlock, rotation, footprint, requiredLoadBounds,
                    footprintMode, "minecraft_place_structure", new JsonObject(),
                    footprint == null ? 0 : area(footprint));
        }
    }

    public record PlacementResult(boolean success, boolean waiting, boolean worldMutationApplied,
                                  String reasonCode, String message, JsonObject trace, BlockBounds footprint,
                                  BlockBounds requiredLoadBounds) {
        public static PlacementResult placed(String message) {
            return placed(message, null, null, null);
        }

        public static PlacementResult placed(String message, JsonObject trace, BlockBounds footprint,
                                             BlockBounds requiredLoadBounds) {
            return new PlacementResult(true, false, true, "", message == null ? "" : message,
                    trace, footprint, requiredLoadBounds);
        }

        public static PlacementResult dryRunAccepted(String message) {
            return dryRunAccepted(message, null, null, null);
        }

        public static PlacementResult dryRunAccepted(String message, JsonObject trace, BlockBounds footprint,
                                                     BlockBounds requiredLoadBounds) {
            return new PlacementResult(true, false, false, "", message == null ? "" : message,
                    trace, footprint, requiredLoadBounds);
        }

        public static PlacementResult failed(String reasonCode, String message) {
            return new PlacementResult(false, false, false, reasonCode, message == null ? "" : message,
                    null, null, null);
        }

        public static PlacementResult waiting(String reasonCode, String message) {
            return new PlacementResult(false, true, false, reasonCode, message == null ? "" : message,
                    null, null, null);
        }
    }

    public interface PlacementBackend {
        PlacementResult place(PlacementRequest request);

        default PlacementResult placeBoundedJigsaw(PlacementRequest request) {
            return PlacementResult.failed("BOUNDED_JIGSAW_UNSUPPORTED",
                    "Placement backend does not support bounded jigsaw materialization.");
        }

        static PlacementBackend traceOnly() {
            return request -> PlacementResult.dryRunAccepted("Trace-only placement backend accepted configured structure.");
        }
    }

    public record Result(JsonArray startCandidateSets, JsonObject placedStructureMap,
                         JsonObject structureGenerationTrace, JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", boolValue(qualityReport, "passed", false));
            obj.add("startCandidateSets", startCandidateSets);
            obj.add("placedStructureMap", placedStructureMap);
            obj.add("structureGenerationTrace", structureGenerationTrace);
            obj.add("qualityReport", qualityReport);
            return obj;
        }
    }
}
