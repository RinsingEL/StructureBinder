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
    private static final int MAX_SATELLITE_STARTS_PER_VARIABLE_SELECTION = 4;
    private static final int DEFAULT_SAMPLE_CANDIDATE_COUNT = 8;
    private static final int DEFAULT_SAMPLE_SEEDS_PER_CANDIDATE = 2;
    private static final double DEFAULT_BUDGET_HARD_CAP_RATIO = 1.2d;

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
                    VariableProgress progress = variableProgress(task, placed);
                    if (progress.remainingTargetBlocks() <= 0) {
                        variableAttempts.add(alreadyPlacedVariableAttempt(task, zone));
                        continue;
                    }
                    if (progress.placedCount() > 0 && !task.boundedConfig().enableSatelliteStarts()) {
                        variableAttempts.add(singleStartAlreadyMaterializedAttempt(task, zone, progress));
                        continue;
                    }
                    int starts = progress.placedCount();
                    int failedStarts = 0;
                    Set<String> blockedStartCandidateIds = new HashSet<>(progress.anchorCandidateIds());
                    int maxStarts = task.boundedConfig().enableSatelliteStarts()
                            ? MAX_SATELLITE_STARTS_PER_VARIABLE_SELECTION
                            : 1;
                    while (starts < maxStarts) {
                        int remainingTarget = remainingTargetBlocks(task, placed);
                        int remainingZoneArea = remainingByZone.getOrDefault(zoneId, 0);
                        int runTarget = Math.min(remainingTarget, remainingZoneArea);
                        if (hardCapBlocks(runTarget, task.boundedConfig()) < task.startAreaBlocks()) {
                            break;
                        }
                        VariableTask runTask = task.withTargetAreaBlocks(runTarget);
                        JsonObject startSet = buildStartCandidateSet(cityId, worldSeed, zone, runTask,
                                placed, blockedStartCandidateIds, starts);
                        startCandidateSets.add(startSet);
                        AttemptResult result = executeVariable(startSet, runTask, zone, placed, backend);
                        variableAttempts.addAll(result.attempts());
                        blockedStartCandidateIds.addAll(result.attemptedStartCandidateIds());
                        if (result.placed() != null) {
                            placed.add(result.placed());
                            placedStructures.add(result.placed().asJson());
                            remainingByZone.computeIfPresent(result.placed().zonePatchId(),
                                    (id, value) -> Math.max(0, value - result.placed().visibleAreaCost()));
                            blockedStartCandidateIds.add(result.placed().anchorCandidateId());
                            starts++;
                            failedStarts = 0;
                        } else if (result.waiting()) {
                            increment(waitingSummary, result.reasonCode());
                            break;
                        } else {
                            failedStarts++;
                            increment(failureSummary, result.reasonCode());
                            if (!task.boundedConfig().enableSatelliteStarts()
                                    || "NO_START_CANDIDATE".equals(result.reasonCode())
                                    || "START_RETRY_BUDGET_EXHAUSTED".equals(result.reasonCode())
                                    || failedStarts >= MAX_ATTEMPTS_PER_TASK) {
                                break;
                            }
                        }
                    }
                }
            }
        }

        JsonArray materializationJobs = materializationJobs(cityId, fixedAttempts, variableAttempts);
        JsonObject ledger = chunkMaterializationLedger(cityId, placedStructures);
        JsonObject placedMap = placedMap(cityId, placedStructures, remainingByZone, hardBlocks, ledger);
        JsonObject trace = trace(cityId, fixedAttempts, variableAttempts, placedStructures, remainingByZone,
                failureSummary, waitingSummary, hardBlocks, materializationJobs, ledger);
        JsonObject quality = quality(fixedAttempts, variableAttempts, placedStructures, startCandidateSets,
                materializationJobs, ledger, hardBlocks, failureSummary, waitingSummary);
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
        attempt.addProperty("materializationMode", "minecraft_place_structure");
        attempt.addProperty("sourcePlanRef", "PlannedFixedPlacementMap:" + placementId);
        attempt.addProperty("retryBudget", 1);
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
        attempt.addProperty("worldMutationApplied", placement.worldMutationApplied());
        Placed placedStructure = new Placed("placed_" + placementId, placementId, landingCandidateId,
                requiredString(fixed, "zonePatchId"), structureId, "fixed_footprint", placementKind,
                stringValue(fixed, "placementCommand", ""), anchor, commandAnchor, requiredString(fixed, "rotation"),
                footprint, clearance, intValue(fixed, "visibleAreaCost", area(footprint)),
                "minecraft_place_structure", null, placement.worldMutationApplied());
        return new AttemptResult(attempt, new JsonArray(), placedStructure, "");
    }

    private AttemptResult executeVariable(JsonObject startSet, VariableTask task, ZoneInfo zone,
                                          List<Placed> placed, PlacementBackend backend) {
        if ("bounded_jigsaw".equals(task.materializationMode())) {
            return executeBoundedVariable(startSet, task, zone, placed, backend);
        }
        JsonArray attempts = new JsonArray();
        List<JsonObject> candidates = jsonObjects(requiredArray(startSet, "candidates")).stream()
                .filter(candidate -> boolValue(candidate, "hardPassed", false))
                .sorted(Comparator.comparingDouble(candidate -> -doubleValue(candidate, "score", 0)))
                .toList();
        if (candidates.isEmpty()) {
            JsonObject attempt = baseAttempt("variable", task.taskId(), zone.zonePatchId(), task.structureId(),
                    task.placementKind(), task.placementCommand(), "", new BlockPoint(0, 0), "NONE", 0);
            attempt.addProperty("materializationMode", task.materializationMode());
            attempt.addProperty("sourcePlanRef", "StructurePoolMap:" + task.selectionId());
            attempt.addProperty("retryBudget", task.retryBudget());
            attempts.add(failedJson(attempt, "NO_START_CANDIDATE", "No hard-passed StartCandidateSet candidate."));
            return new AttemptResult(new JsonObject(), attempts, null, "NO_START_CANDIDATE");
        }
        Set<String> tried = new HashSet<>();
        Set<String> attemptedStartCandidateIds = new LinkedHashSet<>();
        String lastReason = "START_RETRY_BUDGET_EXHAUSTED";
        for (int retry = 0; retry < Math.min(task.retryBudget(), MAX_ATTEMPTS_PER_TASK); retry++) {
            JsonObject candidate = chooseCandidate(candidates, stringValue(startSet, "seedKey", task.seedKey())
                    + ":" + retry, tried);
            if (candidate == null) {
                lastReason = "START_RETRY_BUDGET_EXHAUSTED";
                break;
            }
            String startCandidateId = requiredString(candidate, "startCandidateId");
            tried.add(startCandidateId);
            attemptedStartCandidateIds.add(startCandidateId);
            BlockBounds footprint = bounds(requiredObject(candidate, "candidateFootprint"));
            BlockBounds requiredBounds = bounds(requiredObject(candidate, "requiredPlacementBounds"));
            BlockPoint anchor = blockPoint(requiredObject(candidate, "anchorBlock"));
            JsonObject attempt = baseAttempt("variable", task.taskId(), zone.zonePatchId(), task.structureId(),
                    task.placementKind(), task.placementCommand(), requiredString(candidate, "startCandidateId"),
                    anchor, requiredString(candidate, "rotation"), retry);
            attempt.addProperty("materializationMode", task.materializationMode());
            attempt.addProperty("sourcePlanRef", "StructurePoolMap:" + task.selectionId());
            attempt.addProperty("retryBudget", task.retryBudget());
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
            if ("bounded_jigsaw".equals(task.materializationMode()) && placement.trace() != null) {
                attempt.add("boundedJigsawTrace", compactBoundedTrace(placement.trace(), true));
            }
            if (placement.waiting()) {
                attempts.add(waitingJson(attempt, placement.reasonCode(), placement.message()));
                return new AttemptResult(new JsonObject(), attempts, null, placement.reasonCode(), true,
                        attemptedStartCandidateIds);
            }
            if (!placement.success()) {
                lastReason = placement.reasonCode();
                attempts.add(failedJson(attempt, placement.reasonCode(), placement.message()));
                continue;
            }
            attempt.addProperty("status", "placed");
            attempt.addProperty("reasonCode", "");
            attempt.addProperty("message", placement.message());
            attempt.addProperty("worldMutationApplied", placement.worldMutationApplied());
            attempts.add(attempt);
            BlockBounds placedFootprint = placement.footprint() == null ? footprint : placement.footprint();
            BlockBounds placedClearance = placement.requiredLoadBounds() == null ? requiredBounds : placement.requiredLoadBounds();
            Placed placedStructure = new Placed("placed_" + task.taskId() + "_" + safeId(startCandidateId),
                    task.selectionId(), startCandidateId, zone.zonePatchId(), task.structureId(),
                    "variable_area", task.placementKind(), task.placementCommand(), anchor, anchor,
                    requiredString(candidate, "rotation"), placedFootprint, placedClearance,
                    Math.min(task.targetAreaBlocks(), visibleAreaCost(placement, placedFootprint)),
                    task.materializationMode(),
                    compactBoundedTrace(placement.trace(), true), placement.worldMutationApplied());
            return new AttemptResult(new JsonObject(), attempts, placedStructure, "", false,
                    attemptedStartCandidateIds);
        }
        return new AttemptResult(new JsonObject(), attempts, null, lastReason, false, attemptedStartCandidateIds);
    }

    private AttemptResult executeBoundedVariable(JsonObject startSet, VariableTask task, ZoneInfo zone,
                                                 List<Placed> placed, PlacementBackend backend) {
        JsonArray attempts = new JsonArray();
        List<JsonObject> candidates = jsonObjects(requiredArray(startSet, "candidates")).stream()
                .filter(candidate -> boolValue(candidate, "hardPassed", false))
                .sorted(Comparator.comparingDouble(candidate -> -doubleValue(candidate, "score", 0)))
                .limit(Math.max(1, task.boundedConfig().sampleCandidateCount()))
                .toList();
        if (candidates.isEmpty()) {
            JsonObject attempt = baseAttempt("variable", task.taskId(), zone.zonePatchId(), task.structureId(),
                    task.placementKind(), task.placementCommand(), "", new BlockPoint(0, 0), "NONE", 0);
            attempt.addProperty("materializationMode", task.materializationMode());
            attempt.addProperty("sourcePlanRef", "StructurePoolMap:" + task.selectionId());
            attempt.addProperty("retryBudget", task.retryBudget());
            attempts.add(failedJson(attempt, "NO_START_CANDIDATE", "No hard-passed StartCandidateSet candidate."));
            return new AttemptResult(new JsonObject(), attempts, null, "NO_START_CANDIDATE");
        }

        JsonArray samples = new JsonArray();
        Set<String> attemptedStartCandidateIds = new LinkedHashSet<>();
        PlanSample selected = null;
        String lastReason = "JIGSAW_NO_ACCEPTED_PIECE";
        JsonObject lastPlanTrace = null;
        int sampleIndex = 0;
        for (JsonObject candidate : candidates) {
            String startCandidateId = requiredString(candidate, "startCandidateId");
            attemptedStartCandidateIds.add(startCandidateId);
            BlockBounds footprint = bounds(requiredObject(candidate, "candidateFootprint"));
            String validatorReason = validatorFailure(zone, footprint, placed, task);
            for (int seedIndex = 0; seedIndex < Math.max(1, task.boundedConfig().sampleSeedsPerCandidate()); seedIndex++) {
                sampleIndex++;
                JsonObject sample = sampleBase(task, zone, candidate, sampleIndex, seedIndex);
                if (!validatorReason.isBlank()) {
                    sample.addProperty("status", "failed");
                    sample.addProperty("reasonCode", validatorReason);
                    sample.addProperty("message", "D7 validator rejected start candidate before bounded planning.");
                    sample.add("terminationReport", terminationReportForReason(validatorReason));
                    sample.add("rejectionReport", new JsonObject());
                    sample.addProperty("feasibility", "FAILED");
                    sample.addProperty("score", 0.0d);
                    sample.add("scoreBreakdown", new JsonObject());
                    samples.add(sample);
                    lastReason = validatorReason;
                    continue;
                }
                PlacementRequest request = boundedRequest(task, zone, placed, candidate,
                        "sample_" + sampleIndex + "_seed_" + seedIndex);
                PlacementResult planned = backend.planBoundedJigsaw(request);
                if (planned.trace() != null) {
                    sample.add("boundedJigsawTraceSummary", boundedTraceSummary(planned.trace()));
                    lastPlanTrace = planned.trace().deepCopy();
                }
                sample.addProperty("status", planned.waiting() ? "waiting" : planned.success() ? "planned" : "failed");
                sample.addProperty("reasonCode", planned.reasonCode());
                sample.addProperty("message", planned.message());
                sample.addProperty("worldMutationApplied", planned.worldMutationApplied());
                Score score = scoreSample(planned.trace(), planned.success(), planned.waiting(), planned.reasonCode(),
                        task.boundedConfig());
                sample.addProperty("score", score.value());
                sample.addProperty("feasibility", score.feasibility());
                sample.addProperty("limitingFactor", score.limitingFactor());
                sample.add("scoreBreakdown", score.breakdown());
                sample.add("terminationReport", reportFromTrace(planned.trace(), "terminationReport",
                        terminationReportForReason(planned.reasonCode())));
                sample.add("rejectionReport", reportFromTrace(planned.trace(), "rejectionReport", new JsonObject()));
                samples.add(sample);
                if (planned.waiting()) {
                    return new AttemptResult(new JsonObject(), attemptsWithPlanning(attempts, task, zone, candidate,
                            samples, sample, planned, "waiting"), null, planned.reasonCode(), true,
                            attemptedStartCandidateIds);
                }
                if (!planned.success()) {
                    lastReason = planned.reasonCode().isBlank() ? lastReason : planned.reasonCode();
                    continue;
                }
                PlanSample candidateSample = new PlanSample(sample.deepCopy(), request, planned, score,
                        startCandidateId, candidate.deepCopy());
                if (selected == null || candidateSample.score().value() > selected.score().value()) {
                    selected = candidateSample;
                }
            }
        }

        if (selected == null) {
            JsonObject attempt = baseAttempt("variable", task.taskId(), zone.zonePatchId(), task.structureId(),
                    task.placementKind(), task.placementCommand(), "", BlockPoint.ORIGIN, "NONE", 0);
            attempt.addProperty("materializationMode", task.materializationMode());
            attempt.addProperty("sourcePlanRef", "StructurePoolMap:" + task.selectionId());
            attempt.addProperty("retryBudget", task.retryBudget());
            attempt.add("boundedJigsawSamples", samples);
            attempt.add("terminationReport", aggregateSampleReport(samples, "terminationReport"));
            attempt.add("rejectionReport", aggregateSampleReport(samples, "rejectionReport"));
            attempt.addProperty("feasibility", "FAILED");
            attempt.addProperty("limitingFactor", dominantLimitingFactor(samples));
            if (lastPlanTrace != null) {
                attempt.add("boundedJigsawTrace", compactBoundedTrace(lastPlanTrace, true));
            }
            attempts.add(failedJson(attempt, lastReason, "No bounded dry-run sample produced an accepted plan."));
            return new AttemptResult(new JsonObject(), attempts, null, lastReason, false, attemptedStartCandidateIds);
        }

        JsonObject selectedCandidate = selected.candidate();
        PlacementResult materialized = backend.materializeBoundedJigsaw(selected.request(), selected.result().trace());
        JsonObject attempt = baseAttempt("variable", task.taskId(), zone.zonePatchId(), task.structureId(),
                task.placementKind(), task.placementCommand(), selected.startCandidateId(),
                blockPoint(requiredObject(selectedCandidate, "anchorBlock")),
                requiredString(selectedCandidate, "rotation"), 0);
        attempt.addProperty("materializationMode", task.materializationMode());
        attempt.addProperty("sourcePlanRef", "StructurePoolMap:" + task.selectionId());
        attempt.addProperty("retryBudget", task.retryBudget());
        attempt.add("scoreBreakdown", selected.score().breakdown());
        attempt.add("requiredPlacementBounds", requiredObject(selectedCandidate, "requiredPlacementBounds").deepCopy());
        attempt.add("requiredChunkRange", requiredObject(selectedCandidate, "requiredChunkRange").deepCopy());
        attempt.add("boundedJigsawSamples", samples);
        attempt.addProperty("selectedSampleId", stringValue(selected.sample(), "sampleId", ""));
        attempt.addProperty("selectedPlanScore", selected.score().value());
        attempt.addProperty("feasibility", selected.score().feasibility());
        attempt.addProperty("limitingFactor", selected.score().limitingFactor());
        attempt.add("terminationReport", reportFromTrace(selected.result().trace(), "terminationReport",
                terminationReportForReason(selected.result().reasonCode())));
        attempt.add("rejectionReport", reportFromTrace(selected.result().trace(), "rejectionReport", new JsonObject()));
        JsonObject finalTrace = materialized.trace() == null ? selected.result().trace() : materialized.trace();
        if (finalTrace != null) {
            finalTrace.addProperty("selectedSampleId", stringValue(selected.sample(), "sampleId", ""));
            finalTrace.addProperty("selectedPlanScore", selected.score().value());
            finalTrace.addProperty("feasibility", selected.score().feasibility());
            finalTrace.addProperty("limitingFactor", selected.score().limitingFactor());
            finalTrace.add("scoreBreakdown", selected.score().breakdown().deepCopy());
            attempt.add("boundedJigsawTrace", compactBoundedTrace(finalTrace, true));
        }
        if (materialized.waiting()) {
            attempts.add(waitingJson(attempt, materialized.reasonCode(), materialized.message()));
            return new AttemptResult(new JsonObject(), attempts, null, materialized.reasonCode(), true,
                    attemptedStartCandidateIds);
        }
        if (!materialized.success()) {
            attempts.add(failedJson(attempt, materialized.reasonCode(), materialized.message()));
            return new AttemptResult(new JsonObject(), attempts, null, materialized.reasonCode(), false,
                    attemptedStartCandidateIds);
        }
        attempt.addProperty("status", "placed");
        attempt.addProperty("reasonCode", "");
        attempt.addProperty("message", materialized.message());
        attempt.addProperty("worldMutationApplied", materialized.worldMutationApplied());
        attempts.add(attempt);

        BlockBounds placedFootprint = materialized.footprint() == null
                ? bounds(requiredObject(selectedCandidate, "candidateFootprint"))
                : materialized.footprint();
        BlockBounds placedClearance = materialized.requiredLoadBounds() == null
                ? bounds(requiredObject(selectedCandidate, "requiredPlacementBounds"))
                : materialized.requiredLoadBounds();
        Placed placedStructure = new Placed("placed_" + task.taskId() + "_" + safeId(selected.startCandidateId()),
                task.selectionId(), selected.startCandidateId(), zone.zonePatchId(), task.structureId(),
                "variable_area", task.placementKind(), task.placementCommand(),
                blockPoint(requiredObject(selectedCandidate, "anchorBlock")),
                blockPoint(requiredObject(selectedCandidate, "anchorBlock")),
                requiredString(selectedCandidate, "rotation"), placedFootprint, placedClearance,
                visibleAreaCost(materialized, placedFootprint), task.materializationMode(),
                compactBoundedTrace(finalTrace, true), materialized.worldMutationApplied());
        return new AttemptResult(new JsonObject(), attempts, placedStructure, "", false, attemptedStartCandidateIds);
    }

    private JsonObject buildStartCandidateSet(String cityId, long worldSeed, ZoneInfo zone, VariableTask task,
                                              List<Placed> placed) {
        return buildStartCandidateSet(cityId, worldSeed, zone, task, placed, Set.of(), 0);
    }

    private JsonObject buildStartCandidateSet(String cityId, long worldSeed, ZoneInfo zone, VariableTask task,
                                              List<Placed> placed, Set<String> blockedStartCandidateIds,
                                              int startIndex) {
        JsonObject set = new JsonObject();
        set.addProperty("schemaVersion", "city_start_candidate_set.v0.1");
        set.addProperty("cityId", cityId);
        set.addProperty("zonePatchId", zone.zonePatchId());
        set.addProperty("taskId", task.taskId());
        set.addProperty("structureId", task.structureId());
        set.addProperty("startIndex", startIndex);
        set.addProperty("remainingTargetAreaBlocks", task.targetAreaBlocks());
        set.addProperty("seedKey", task.seedKey() + ":" + startIndex);
        JsonArray candidates = new JsonArray();
        int index = 1;
        for (CellAnchor anchor : zone.anchors()) {
            for (String rotation : task.rotations()) {
                BlockBounds footprint = task.startFootprint().boundsAt(anchor.blockMinX(), anchor.blockMinZ(), rotation);
                String candidateId = task.taskId() + "_start_" + String.format(Locale.ROOT, "%03d", index++);
                boolean blocked = blockedStartCandidateIds.contains(candidateId);
                boolean hard = !blocked && zone.covers(footprint) && !conflicts(footprint, placed)
                        && area(footprint) <= hardCapBlocks(task.targetAreaBlocks(), task.boundedConfig());
                BlockBounds requiredBounds = task.requiredBounds(footprint.center());
                JsonObject candidate = new JsonObject();
                candidate.addProperty("startCandidateId", candidateId);
                candidate.add("anchorBlock", footprint.center().asJson());
                candidate.addProperty("rotation", rotation);
                candidate.add("candidateFootprint", boundsJson(footprint));
                candidate.add("requiredPlacementBounds", boundsJson(requiredBounds));
                candidate.add("requiredChunkRange", chunkRangeJson(requiredBounds));
                candidate.addProperty("hardPassed", hard);
                StartCandidateScore score = score(zone, footprint, worldSeed, task.seedKey());
                candidate.addProperty("score", hard ? score.value() : 0);
                JsonObject scoreBreakdown = score.asJson();
                scoreBreakdown.addProperty("buildableFit", hard ? 1.0 : 0.0);
                candidate.add("scoreBreakdown", scoreBreakdown);
                JsonArray risks = new JsonArray();
                if (!hard) {
                    risks.add(blocked ? "previously_selected_start" : "hard_filter_failed");
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

    private PlacementRequest boundedRequest(VariableTask task, ZoneInfo zone, List<Placed> placed,
                                            JsonObject candidate, String sampleKey) {
        return new PlacementRequest(task.structureId(), task.placementKind(), task.placementCommand(),
                blockPoint(requiredObject(candidate, "anchorBlock")), requiredString(candidate, "rotation"),
                bounds(requiredObject(candidate, "candidateFootprint")),
                bounds(requiredObject(candidate, "requiredPlacementBounds")),
                "variable_area", task.materializationMode(), constraintField(zone, placed), task.targetAreaBlocks(),
                task.boundedConfig().asJson(), sampleKey == null ? "" : sampleKey);
    }

    private JsonObject sampleBase(VariableTask task, ZoneInfo zone, JsonObject candidate, int sampleIndex,
                                  int seedIndex) {
        JsonObject sample = new JsonObject();
        sample.addProperty("sampleId", task.taskId() + "_sample_" + sampleIndex);
        sample.addProperty("sampleIndex", sampleIndex);
        sample.addProperty("seedIndex", seedIndex);
        sample.addProperty("taskId", task.taskId());
        sample.addProperty("zonePatchId", zone.zonePatchId());
        sample.addProperty("structureId", task.structureId());
        sample.addProperty("anchorCandidateId", requiredString(candidate, "startCandidateId"));
        sample.add("candidateBlock", requiredObject(candidate, "anchorBlock").deepCopy());
        sample.addProperty("rotation", requiredString(candidate, "rotation"));
        sample.addProperty("startCandidateScore", doubleValue(candidate, "score", 0));
        sample.add("startCandidateScoreBreakdown", requiredObject(candidate, "scoreBreakdown").deepCopy());
        sample.add("requiredPlacementBounds", requiredObject(candidate, "requiredPlacementBounds").deepCopy());
        sample.add("requiredChunkRange", requiredObject(candidate, "requiredChunkRange").deepCopy());
        sample.add("boundedJigsawConfig", task.boundedConfig().asJson());
        return sample;
    }

    private JsonArray attemptsWithPlanning(JsonArray attempts, VariableTask task, ZoneInfo zone, JsonObject candidate,
                                           JsonArray samples, JsonObject selectedSample, PlacementResult result,
                                           String status) {
        JsonObject attempt = baseAttempt("variable", task.taskId(), zone.zonePatchId(), task.structureId(),
                task.placementKind(), task.placementCommand(), requiredString(candidate, "startCandidateId"),
                blockPoint(requiredObject(candidate, "anchorBlock")), requiredString(candidate, "rotation"), 0);
        attempt.addProperty("materializationMode", task.materializationMode());
        attempt.addProperty("sourcePlanRef", "StructurePoolMap:" + task.selectionId());
        attempt.addProperty("retryBudget", task.retryBudget());
        attempt.add("scoreBreakdown", objectValue(selectedSample, "scoreBreakdown", new JsonObject()).deepCopy());
        attempt.add("boundedJigsawSamples", samples.deepCopy());
        attempt.addProperty("selectedSampleId", stringValue(selectedSample, "sampleId", ""));
        attempt.addProperty("selectedPlanScore", doubleValue(selectedSample, "score", 0));
        attempt.addProperty("feasibility", stringValue(selectedSample, "feasibility", "FAILED"));
        attempt.addProperty("limitingFactor", stringValue(selectedSample, "limitingFactor", ""));
        attempt.add("terminationReport", objectValue(selectedSample, "terminationReport", new JsonObject()).deepCopy());
        attempt.add("rejectionReport", objectValue(selectedSample, "rejectionReport", new JsonObject()).deepCopy());
        attempt.add("requiredPlacementBounds", requiredObject(candidate, "requiredPlacementBounds").deepCopy());
        attempt.add("requiredChunkRange", requiredObject(candidate, "requiredChunkRange").deepCopy());
        if (result.trace() != null) {
            attempt.add("boundedJigsawTrace", compactBoundedTrace(result.trace(), true));
        }
        attempts.add("waiting".equals(status)
                ? waitingJson(attempt, result.reasonCode(), result.message())
                : failedJson(attempt, result.reasonCode(), result.message()));
        return attempts;
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
        if (area(footprint) > hardCapBlocks(task.targetAreaBlocks(), task.boundedConfig())) {
            return "JIGSAW_AREA_HARD_CAP_REACHED";
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
        BoundedJigsawConfig boundedConfig = boundedConfig(selection, structureId);
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
                MAX_ATTEMPTS_PER_TASK,
                boundedConfig);
    }

    private BoundedJigsawConfig boundedConfig(JsonObject selection, String structureId) {
        boolean village = structureId != null && structureId.contains("village");
        JsonObject config = objectValue(selection, "boundedJigsawConfig", new JsonObject());
        int defaultMaxPieces = village ? 16 : 8;
        int defaultMaxDepth = village ? 4 : 3;
        int defaultMaxPools = village ? 24 : 12;
        return new BoundedJigsawConfig(
                Math.max(1, intValue(config, "maxPieces", defaultMaxPieces)),
                Math.max(0, intValue(config, "maxDepth", defaultMaxDepth)),
                Math.max(1, intValue(config, "maxPools", defaultMaxPools)),
                Math.max(1.0d, doubleValue(config, "budgetHardCapRatio", DEFAULT_BUDGET_HARD_CAP_RATIO)),
                Math.max(1, intValue(config, "sampleCandidateCount", DEFAULT_SAMPLE_CANDIDATE_COUNT)),
                Math.max(1, intValue(config, "sampleSeedsPerCandidate", DEFAULT_SAMPLE_SEEDS_PER_CANDIDATE)),
                boolValue(config, "enableSatelliteStarts", false));
    }

    private boolean conflicts(BlockBounds bounds, List<Placed> placed) {
        return placed.stream().anyMatch(existing -> existing.clearanceFootprint().overlaps(bounds));
    }

    private VariableProgress variableProgress(VariableTask task, List<Placed> placed) {
        int visibleArea = 0;
        int count = 0;
        Set<String> anchorCandidateIds = new LinkedHashSet<>();
        for (Placed existing : placed) {
            if (!"variable_area".equals(existing.footprintMode())
                    || !task.selectionId().equals(existing.sourceSelectionId())
                    || !task.structureId().equals(existing.structureId())) {
                continue;
            }
            visibleArea += existing.visibleAreaCost();
            count++;
            anchorCandidateIds.add(existing.anchorCandidateId());
        }
        return new VariableProgress(visibleArea, Math.max(0, task.targetAreaBlocks() - visibleArea),
                count, anchorCandidateIds);
    }

    private int remainingTargetBlocks(VariableTask task, List<Placed> placed) {
        return variableProgress(task, placed).remainingTargetBlocks();
    }

    private int visibleAreaCost(PlacementResult placement, BlockBounds placedFootprint) {
        if (placement.trace() != null && placement.trace().has("metrics")
                && placement.trace().get("metrics").isJsonObject()) {
            int visibleArea = intValue(placement.trace().getAsJsonObject("metrics"), "visibleAreaCost", 0);
            if (visibleArea > 0) {
                return visibleArea;
            }
        }
        if (placement.trace() != null && placement.trace().has("plan")
                && placement.trace().get("plan").isJsonObject()) {
            int visibleArea = intValue(placement.trace().getAsJsonObject("plan"), "visibleAreaCost", 0);
            if (visibleArea > 0) {
                return visibleArea;
            }
        }
        return area(placedFootprint);
    }

    private Score scoreSample(JsonObject trace, boolean success, boolean waiting, String reasonCode,
                              BoundedJigsawConfig config) {
        JsonObject metrics = objectValue(trace, "metrics", new JsonObject());
        JsonObject plan = objectValue(trace, "plan", new JsonObject());
        JsonObject quality = objectValue(plan, "quality", new JsonObject());
        JsonArray rejected = arrayValue(trace, "rejectedPieces", new JsonArray());
        JsonArray stopped = arrayValue(trace, "stoppedBranches", new JsonArray());
        int acceptedPieces = intValue(metrics, "acceptedPieceCount",
                arrayValue(trace, "acceptedPieces", new JsonArray()).size());
        int visibleArea = intValue(metrics, "visibleAreaCost", intValue(plan, "visibleAreaCost", 0));
        int targetArea = intValue(metrics, "targetAreaBlocks", intValue(trace, "targetAreaBlocks", 0));
        double fillRatio = targetArea <= 0 ? 0.0d : visibleArea / (double) targetArea;
        boolean startPieceOnly = boolValue(quality, "startPieceOnly", acceptedPieces <= 1);
        double compactness = doubleValue(metrics, "compactness", doubleValue(quality, "compactness", 0.0d));
        int maxDepth = Math.max(1, intValue(metrics, "maxDepth", config.maxDepth()));
        int maxAcceptedDepth = intValue(metrics, "maxAcceptedDepth", intValue(quality, "maxAcceptedDepth", 0));
        double rejectionRatio = rejected.size() + acceptedPieces + stopped.size() == 0
                ? 0.0d
                : rejected.size() / (double) (rejected.size() + acceptedPieces + stopped.size());
        JsonObject rejectionReport = reportFromTrace(trace, "rejectionReport", new JsonObject());
        double boundaryRatio = ratio(rejectionReport, "BOUNDARY_LIMITED", rejected.size());
        double terrainRatio = ratio(rejectionReport, "TERRAIN_LIMITED", rejected.size());
        double occupiedRatio = ratio(rejectionReport, "OCCUPIED_LIMITED", rejected.size());

        double pieceScore = Math.min(1.0d, acceptedPieces / Math.max(1.0d, config.maxPieces() * 0.6d)) * 25.0d;
        double fillScore = targetArea <= 0 ? 10.0d : Math.min(fillRatio, 1.0d) * 25.0d;
        double overshootPenalty = Math.max(0.0d, fillRatio - 1.0d) * 12.0d;
        double startOnlyPenalty = startPieceOnly ? 20.0d : 0.0d;
        double compactnessScore = compactness * 15.0d;
        double depthScore = Math.min(1.0d, maxAcceptedDepth / (double) maxDepth) * 10.0d;
        double rejectionPenalty = rejectionRatio * 10.0d;
        double failurePenalty = (boundaryRatio + terrainRatio + occupiedRatio) * 8.0d;
        double statusPenalty = waiting ? 30.0d : success ? 0.0d : 45.0d;
        double value = Math.max(0.0d, Math.min(100.0d,
                pieceScore + fillScore + compactnessScore + depthScore
                        - overshootPenalty - startOnlyPenalty - rejectionPenalty - failurePenalty - statusPenalty));

        String feasibility;
        if (!success || acceptedPieces == 0) {
            feasibility = "FAILED";
        } else if (acceptedPieces < 6 || fillRatio < 0.25d || startPieceOnly) {
            feasibility = "HAMLET";
        } else if (fillRatio < 0.70d) {
            feasibility = "PARTIAL";
        } else {
            feasibility = "FULL";
        }
        String limitingFactor = dominantLimitingFactor(reportFromTrace(trace, "terminationReport",
                terminationReportForReason(reasonCode)), rejectionReport, reasonCode, feasibility);

        JsonObject breakdown = new JsonObject();
        breakdown.addProperty("acceptedPieceCount", acceptedPieces);
        breakdown.addProperty("visibleArea", visibleArea);
        breakdown.addProperty("targetArea", targetArea);
        breakdown.addProperty("fillRatio", fillRatio);
        breakdown.addProperty("startPieceOnly", startPieceOnly);
        breakdown.addProperty("compactness", compactness);
        breakdown.addProperty("maxAcceptedDepth", maxAcceptedDepth);
        breakdown.addProperty("maxDepth", maxDepth);
        breakdown.addProperty("rejectionRatio", rejectionRatio);
        breakdown.addProperty("boundaryFailureRatio", boundaryRatio);
        breakdown.addProperty("terrainFailureRatio", terrainRatio);
        breakdown.addProperty("occupiedFailureRatio", occupiedRatio);
        breakdown.addProperty("pieceScore", pieceScore);
        breakdown.addProperty("fillScore", fillScore);
        breakdown.addProperty("compactnessScore", compactnessScore);
        breakdown.addProperty("depthScore", depthScore);
        breakdown.addProperty("overshootPenalty", overshootPenalty);
        breakdown.addProperty("startOnlyPenalty", startOnlyPenalty);
        breakdown.addProperty("rejectionPenalty", rejectionPenalty);
        breakdown.addProperty("failurePenalty", failurePenalty);
        breakdown.addProperty("statusPenalty", statusPenalty);
        breakdown.addProperty("score", value);
        breakdown.addProperty("feasibility", feasibility);
        breakdown.addProperty("limitingFactor", limitingFactor);
        return new Score(value, feasibility, limitingFactor, breakdown);
    }

    private static double ratio(JsonObject report, String key, int total) {
        if (total <= 0) {
            return 0.0d;
        }
        return intValue(report, key, 0) / (double) total;
    }

    private static String dominantLimitingFactor(JsonObject terminationReport, JsonObject rejectionReport,
                                                 String fallbackReason, String feasibility) {
        String best = "";
        int bestCount = 0;
        for (String key : List.of("BOUNDARY_LIMITED", "TERRAIN_LIMITED", "OCCUPIED_LIMITED", "POOL_LIMITED",
                "CONNECTOR_LIMITED", "MAX_PIECES_REACHED", "MAX_DEPTH_REACHED", "AREA_HARD_CAP_REACHED",
                "NO_FRONTIER")) {
            int count = intValue(terminationReport, key, 0) + intValue(rejectionReport, key, 0);
            if (count > bestCount) {
                best = key;
                bestCount = count;
            }
        }
        if (!best.isBlank()) {
            return best;
        }
        if (!fallbackReason.isBlank()) {
            return terminationCategory(fallbackReason);
        }
        return "FULL".equals(feasibility) ? "" : "NO_FRONTIER";
    }

    private static String dominantLimitingFactor(JsonArray samples) {
        JsonObject aggregate = aggregateSampleReport(samples, "terminationReport");
        JsonObject rejections = aggregateSampleReport(samples, "rejectionReport");
        return dominantLimitingFactor(aggregate, rejections, "", "FAILED");
    }

    private static JsonObject reportFromTrace(JsonObject trace, String key, JsonObject fallback) {
        return trace != null && trace.has(key) && trace.get(key).isJsonObject()
                ? trace.getAsJsonObject(key).deepCopy()
                : fallback.deepCopy();
    }

    private static JsonObject compactBoundedTrace(JsonObject trace, boolean includePieceDetails) {
        if (trace == null) {
            return null;
        }
        JsonObject compact = new JsonObject();
        copyString(trace, compact, "schemaVersion");
        copyString(trace, compact, "capability");
        copyString(trace, compact, "sourceStructureId");
        copyString(trace, compact, "startPool");
        copyInt(trace, compact, "targetAreaBlocks");
        copyInt(trace, compact, "maxPieces");
        copyInt(trace, compact, "maxDepth");
        copyDouble(trace, compact, "budgetHardCapRatio");
        copyInt(trace, compact, "areaHardCapBlocks");
        copyBoolean(trace, compact, "fallbackUsed");
        copyString(trace, compact, "worldPasteMode");
        copyString(trace, compact, "selectedSampleId");
        copyDouble(trace, compact, "selectedPlanScore");
        copyString(trace, compact, "feasibility");
        copyString(trace, compact, "limitingFactor");
        copyObject(trace, compact, "scoreBreakdown");
        copyObject(trace, compact, "failureSummary");
        copyObject(trace, compact, "rejectionReport");
        copyObject(trace, compact, "terminationReport");
        copyObject(trace, compact, "metrics");
        JsonObject terrainSummary = compactTerrainProbeSummary(objectValue(trace, "terrainProbeSummary", null));
        if (terrainSummary != null) {
            compact.add("terrainProbeSummary", terrainSummary);
        }
        copyObject(trace, compact, "poolAdapterReport");
        if (includePieceDetails) {
            copyArray(trace, compact, "acceptedPieces");
            copyArray(trace, compact, "rejectedPieces", 24);
            copyArray(trace, compact, "stoppedBranches", 24);
            JsonObject plan = compactPlan(objectValue(trace, "plan", null));
            if (plan != null) {
                compact.add("plan", plan);
            }
        } else {
            compact.add("acceptedPieces", pieceSummary(arrayValue(trace, "acceptedPieces", new JsonArray())));
            compact.add("rejectedPieces", limitedArray(arrayValue(trace, "rejectedPieces", new JsonArray()), 8));
            compact.add("stoppedBranches", limitedArray(arrayValue(trace, "stoppedBranches", new JsonArray()), 8));
        }
        compact.addProperty("acceptedPieceCount", arrayValue(trace, "acceptedPieces", new JsonArray()).size());
        compact.addProperty("rejectedPieceCount", arrayValue(trace, "rejectedPieces", new JsonArray()).size());
        compact.addProperty("stoppedBranchCount", arrayValue(trace, "stoppedBranches", new JsonArray()).size());
        return compact;
    }

    private static JsonObject boundedTraceSummary(JsonObject trace) {
        return compactBoundedTrace(trace, false);
    }

    private static JsonObject compactPlan(JsonObject plan) {
        if (plan == null) {
            return null;
        }
        JsonObject compact = new JsonObject();
        copyString(plan, compact, "schemaVersion");
        copyString(plan, compact, "planId");
        copyString(plan, compact, "jobId");
        copyString(plan, compact, "sourceStructureId");
        copyInt(plan, compact, "visibleAreaCost");
        copyObject(plan, compact, "estimatedFootprint");
        copyObject(plan, compact, "requiredChunkRange");
        copyObject(plan, compact, "quality");
        copyArray(plan, compact, "pieces");
        copyArray(plan, compact, "stoppedBranches", 24);
        return compact;
    }

    private static JsonObject compactTerrainProbeSummary(JsonObject summary) {
        if (summary == null) {
            return null;
        }
        JsonObject compact = new JsonObject();
        copyString(summary, compact, "schemaVersion");
        copyBoolean(summary, compact, "waiting");
        copyString(summary, compact, "missingChunks");
        copyInt(summary, compact, "probeCount");
        if (summary.has("probes") && summary.get("probes").isJsonArray()) {
            compact.add("probeSamples", limitedArray(summary.getAsJsonArray("probes"), 12));
        }
        return compact;
    }

    private static JsonArray pieceSummary(JsonArray pieces) {
        JsonArray summary = new JsonArray();
        for (JsonElement elem : pieces) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject piece = elem.getAsJsonObject();
            JsonObject item = new JsonObject();
            copyString(piece, item, "pieceId");
            copyString(piece, item, "templateId");
            copyString(piece, item, "poolId");
            copyString(piece, item, "rotation");
            copyInt(piece, item, "depth");
            copyInt(piece, item, "visibleAreaCost");
            copyObject(piece, item, "anchorBlock");
            copyObject(piece, item, "footprint");
            copyString(piece, item, "validatorResult");
            copyString(piece, item, "pasteStatus");
            copyBoolean(piece, item, "worldMutationApplied");
            summary.add(item);
        }
        return summary;
    }

    private static JsonArray limitedArray(JsonArray array, int limit) {
        JsonArray limited = new JsonArray();
        int count = Math.min(array.size(), Math.max(0, limit));
        for (int i = 0; i < count; i++) {
            limited.add(array.get(i).deepCopy());
        }
        if (array.size() > count) {
            JsonObject omitted = new JsonObject();
            omitted.addProperty("omittedCount", array.size() - count);
            omitted.addProperty("truncated", true);
            limited.add(omitted);
        }
        return limited;
    }

    private static void copyArray(JsonObject source, JsonObject target, String key) {
        if (source != null && source.has(key) && source.get(key).isJsonArray()) {
            target.add(key, source.getAsJsonArray(key).deepCopy());
        }
    }

    private static void copyArray(JsonObject source, JsonObject target, String key, int limit) {
        if (source != null && source.has(key) && source.get(key).isJsonArray()) {
            target.add(key, limitedArray(source.getAsJsonArray(key), limit));
        }
    }

    private static void copyObject(JsonObject source, JsonObject target, String key) {
        if (source != null && source.has(key) && source.get(key).isJsonObject()) {
            target.add(key, source.getAsJsonObject(key).deepCopy());
        }
    }

    private static void copyString(JsonObject source, JsonObject target, String key) {
        if (source != null && source.has(key) && !source.get(key).isJsonNull()) {
            String value = source.get(key).getAsString();
            if (!value.isBlank()) {
                target.addProperty(key, value);
            }
        }
    }

    private static void copyInt(JsonObject source, JsonObject target, String key) {
        if (source != null && source.has(key) && !source.get(key).isJsonNull()) {
            target.addProperty(key, source.get(key).getAsInt());
        }
    }

    private static void copyDouble(JsonObject source, JsonObject target, String key) {
        if (source != null && source.has(key) && !source.get(key).isJsonNull()) {
            target.addProperty(key, source.get(key).getAsDouble());
        }
    }

    private static void copyBoolean(JsonObject source, JsonObject target, String key) {
        if (source != null && source.has(key) && !source.get(key).isJsonNull()) {
            target.addProperty(key, source.get(key).getAsBoolean());
        }
    }

    private static JsonObject terminationReportForReason(String reasonCode) {
        JsonObject report = reportSkeleton();
        String category = terminationCategory(reasonCode);
        if (!category.isBlank()) {
            report.addProperty(category, 1);
            report.addProperty("totalTerminationEvents", 1);
        }
        return report;
    }

    private static JsonObject aggregateSampleReport(JsonArray samples, String key) {
        JsonObject aggregate = reportSkeleton();
        for (JsonElement elem : samples) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject report = objectValue(elem.getAsJsonObject(), key, null);
            if (report == null) {
                continue;
            }
            for (String reportKey : report.keySet()) {
                aggregate.addProperty(reportKey, intValue(aggregate, reportKey, 0)
                        + intValue(report, reportKey, 0));
            }
        }
        return aggregate;
    }

    private static JsonObject reportSkeleton() {
        JsonObject report = new JsonObject();
        report.addProperty("MAX_PIECES_REACHED", 0);
        report.addProperty("MAX_DEPTH_REACHED", 0);
        report.addProperty("NO_FRONTIER", 0);
        report.addProperty("AREA_HARD_CAP_REACHED", 0);
        report.addProperty("BOUNDARY_LIMITED", 0);
        report.addProperty("TERRAIN_LIMITED", 0);
        report.addProperty("OCCUPIED_LIMITED", 0);
        report.addProperty("POOL_LIMITED", 0);
        report.addProperty("CONNECTOR_LIMITED", 0);
        report.addProperty("OTHER_LIMITED", 0);
        report.addProperty("totalTerminationEvents", 0);
        return report;
    }

    private static String terminationCategory(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "OTHER_LIMITED";
        }
        return switch (reasonCode) {
            case "JIGSAW_PIECE_BUDGET_REACHED" -> "MAX_PIECES_REACHED";
            case "JIGSAW_BRANCH_DEPTH_LIMIT" -> "MAX_DEPTH_REACHED";
            case "JIGSAW_AREA_HARD_CAP_REACHED", "START_BUDGET_EXCEEDED" -> "AREA_HARD_CAP_REACHED";
            case "JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA", "START_FOOTPRINT_OUT_OF_ZONE",
                    "FOOTPRINT_OUT_OF_ZONE" -> "BOUNDARY_LIMITED";
            case "JIGSAW_RULE_TERRAIN_TOO_UNEVEN", "JIGSAW_RULE_FLUID_OVERLAP",
                    "JIGSAW_RULE_TERRAIN_SUPPORT_TOO_LOW", "JIGSAW_RULE_CHUNK_WAITING",
                    "STRUCTURE_CHUNK_NOT_LOADED" -> "TERRAIN_LIMITED";
            case "JIGSAW_PIECE_RESERVED_CONFLICT", "START_RUNTIME_OCCUPIED", "AABB_OCCUPIED" -> "OCCUPIED_LIMITED";
            case "BOUNDED_JIGSAW_POOL_EMPTY", "BOUNDED_JIGSAW_POOL_MISSING",
                    "UNSUPPORTED_POOL_ELEMENT", "BOUNDED_JIGSAW_UNSUPPORTED" -> "POOL_LIMITED";
            case "JIGSAW_CONNECTOR_ALIGNMENT_PENDING", "JIGSAW_CONNECTOR_ALIGNMENT_FAILED",
                    "JIGSAW_CONNECTOR_TARGET_MISMATCH", "JIGSAW_NO_ACCEPTED_PIECE" -> "CONNECTOR_LIMITED";
            default -> "OTHER_LIMITED";
        };
    }

    private static int hardCapBlocks(int targetAreaBlocks, BoundedJigsawConfig config) {
        if (targetAreaBlocks <= 0) {
            return 0;
        }
        return Math.max(targetAreaBlocks,
                (int) Math.ceil(targetAreaBlocks * Math.max(1.0d, config.budgetHardCapRatio())));
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
        attempt.addProperty("worldMutationApplied", false);
        return attempt;
    }

    private JsonObject waitingJson(JsonObject attempt, String reasonCode, String message) {
        attempt.addProperty("status", "waiting");
        attempt.addProperty("reasonCode", reasonCode);
        attempt.addProperty("message", message);
        attempt.addProperty("worldMutationApplied", false);
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
                                 List<String> hardBlocks, JsonObject ledger) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_placed_structure_map.v0.1");
        obj.addProperty("cityId", cityId);
        obj.add("placedStructures", placedStructures);
        obj.add("remainingVisibleAreaByZone", remainingJson(remainingByZone));
        obj.add("chunkMaterializationLedger", ledger.deepCopy());
        obj.add("quality", new CityQualityReport(hardBlocks.isEmpty(), hardBlocks.isEmpty() ? 100 : 0,
                hardBlocks, List.of(), List.of(), metric("placedStructureCount", placedStructures.size())).asJson());
        return obj;
    }

    private JsonObject trace(String cityId, JsonArray fixedAttempts, JsonArray variableAttempts,
                             JsonArray placedStructures, Map<String, Integer> remainingByZone,
                             Map<String, Integer> failureSummary, Map<String, Integer> waitingSummary,
                             List<String> hardBlocks, JsonArray materializationJobs, JsonObject ledger) {
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
        obj.add("materializationJobs", materializationJobs);
        obj.add("placedStructures", placedStructures);
        obj.add("chunkMaterializationLedger", ledger.deepCopy());
        obj.add("remainingVisibleAreaByZone", remainingJson(remainingByZone));
        obj.add("failureSummary", failureJson(failureSummary));
        obj.add("waitingSummary", failureJson(waitingSummary));
        JsonArray debugRefs = new JsonArray();
        debugRefs.add("start_candidate_preview.png");
        debugRefs.add("placed_structure_preview.png");
        debugRefs.add("bounded_piece_preview.png");
        obj.add("debugRefs", debugRefs);
        return obj;
    }

    private JsonObject quality(JsonArray fixedAttempts, JsonArray variableAttempts, JsonArray placedStructures,
                               JsonArray startCandidateSets, JsonArray materializationJobs, JsonObject ledger,
                               List<String> hardBlocks,
                               Map<String, Integer> failureSummary, Map<String, Integer> waitingSummary) {
        JsonObject metrics = new JsonObject();
        metrics.addProperty("fixedAttemptCount", fixedAttempts.size());
        metrics.addProperty("variableAttemptCount", variableAttempts.size());
        metrics.addProperty("startCandidateSetCount", startCandidateSets.size());
        metrics.addProperty("materializationJobCount", materializationJobs.size());
        metrics.addProperty("ledgerAppliedJobCount", ledger.getAsJsonArray("appliedJobs").size());
        metrics.addProperty("ledgerAppliedPieceCount", ledger.getAsJsonArray("appliedPieces").size());
        metrics.addProperty("placedStructureCount", placedStructures.size());
        metrics.addProperty("failureReasonCount", failureSummary.size());
        metrics.addProperty("waitingReasonCount", waitingSummary.size());
        int score = Math.max(0, 100 - hardBlocks.size() * 50 - failureSummary.size() * 8 - waitingSummary.size() * 2);
        List<String> warnings = waitingSummary.isEmpty() ? List.of() : List.of("D7 waiting for loaded chunks.");
        return new CityQualityReport(hardBlocks.isEmpty(), score, hardBlocks, warnings, List.of(), metrics).asJson();
    }

    private JsonArray materializationJobs(String cityId, JsonArray fixedAttempts, JsonArray variableAttempts) {
        Map<String, JsonObject> byJob = new LinkedHashMap<>();
        collectJobs(cityId, fixedAttempts, byJob);
        collectJobs(cityId, variableAttempts, byJob);
        JsonArray jobs = new JsonArray();
        byJob.values().forEach(jobs::add);
        return jobs;
    }

    private void collectJobs(String cityId, JsonArray attempts, Map<String, JsonObject> byJob) {
        for (JsonElement elem : attempts) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject attempt = elem.getAsJsonObject();
            String jobId = jobId(attempt);
            JsonObject job = byJob.computeIfAbsent(jobId, id -> materializationJob(cityId, id, attempt));
            mergeAttemptIntoJob(job, attempt);
        }
    }

    private JsonObject materializationJob(String cityId, String jobId, JsonObject attempt) {
        JsonObject job = new JsonObject();
        job.addProperty("schemaVersion", "city_materialization_job.v0.1");
        job.addProperty("jobId", jobId);
        job.addProperty("cityId", cityId);
        job.addProperty("zonePatchId", stringValue(attempt, "zonePatchId", ""));
        job.addProperty("structureId", stringValue(attempt, "structureId", ""));
        job.addProperty("footprintMode", stringValue(attempt, "footprintMode", ""));
        job.addProperty("placementMode", placementMode(attempt));
        job.addProperty("materializationMode", stringValue(attempt, "materializationMode", "minecraft_place_structure"));
        job.addProperty("sourcePlanRef", stringValue(attempt, "sourcePlanRef", ""));
        job.addProperty("status", "pending");
        job.addProperty("retryBudget", intValue(attempt, "retryBudget", 1));
        job.addProperty("attemptCount", 0);
        job.add("attempts", new JsonArray());
        return job;
    }

    private void mergeAttemptIntoJob(JsonObject job, JsonObject attempt) {
        job.addProperty("status", mergedJobStatus(stringValue(job, "status", "pending"), jobStatus(attempt)));
        if (attempt.has("candidateBlock") && attempt.get("candidateBlock").isJsonObject()) {
            job.add("anchorBlock", attempt.getAsJsonObject("candidateBlock").deepCopy());
        }
        if (attempt.has("requiredPlacementBounds") && attempt.get("requiredPlacementBounds").isJsonObject()) {
            job.add("requiredPlacementBounds", attempt.getAsJsonObject("requiredPlacementBounds").deepCopy());
        }
        if (attempt.has("requiredChunkRange") && attempt.get("requiredChunkRange").isJsonObject()) {
            job.add("requiredChunkRange", attempt.getAsJsonObject("requiredChunkRange").deepCopy());
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("retryIndex", intValue(attempt, "retryIndex", 0));
        summary.addProperty("anchorCandidateId", stringValue(attempt, "anchorCandidateId", ""));
        summary.addProperty("status", stringValue(attempt, "status", "pending"));
        summary.addProperty("jobStatus", jobStatus(attempt));
        summary.addProperty("reasonCode", stringValue(attempt, "reasonCode", ""));
        summary.addProperty("worldMutationApplied", boolValue(attempt, "worldMutationApplied", false));
        job.getAsJsonArray("attempts").add(summary);
        job.addProperty("attemptCount", job.getAsJsonArray("attempts").size());
    }

    private String jobStatus(JsonObject attempt) {
        String status = stringValue(attempt, "status", "pending");
        if ("already_placed".equals(status)) {
            return "applied";
        }
        if ("placed".equals(status)) {
            return boolValue(attempt, "worldMutationApplied", false) ? "applied" : "planned";
        }
        if ("waiting".equals(status)) {
            return "waiting_chunks";
        }
        if ("failed".equals(status)) {
            return "failed";
        }
        return status.isBlank() ? "pending" : status;
    }

    private String mergedJobStatus(String current, String next) {
        if ("applied".equals(current) || "applied".equals(next)) {
            return "applied";
        }
        return next == null || next.isBlank() ? current : next;
    }

    private String placementMode(JsonObject attempt) {
        String footprintMode = stringValue(attempt, "footprintMode", "");
        if ("fixed".equals(footprintMode) || "fixed_footprint".equals(footprintMode)) {
            return "fixed_footprint";
        }
        return stringValue(attempt, "materializationMode", "minecraft_place_structure");
    }

    private String jobId(JsonObject attempt) {
        String footprintMode = stringValue(attempt, "footprintMode", "");
        String prefix = "fixed".equals(footprintMode) || "fixed_footprint".equals(footprintMode) ? "fixed" : "variable";
        String suffix = "variable".equals(prefix) || "variable_area".equals(footprintMode)
                ? "_" + safeId(stringValue(attempt, "anchorCandidateId", "unknown"))
                : "";
        return "job_" + prefix + "_" + safeId(stringValue(attempt, "taskId", "unknown")) + suffix;
    }

    private JsonObject chunkMaterializationLedger(String cityId, JsonArray placedStructures) {
        JsonObject ledger = new JsonObject();
        ledger.addProperty("schemaVersion", "city_chunk_materialization_ledger.v0.1");
        ledger.addProperty("ledgerId", "ledger_" + safeId(cityId));
        ledger.addProperty("cityId", cityId);
        JsonArray appliedJobs = new JsonArray();
        JsonArray appliedPieces = new JsonArray();
        for (JsonElement elem : placedStructures) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject placed = elem.getAsJsonObject();
            if (!boolValue(placed, "worldMutationApplied", false)) {
                continue;
            }
            String jobId = jobIdFromPlaced(placed);
            JsonObject applied = new JsonObject();
            applied.addProperty("jobId", jobId);
            applied.addProperty("placedId", requiredString(placed, "placedId"));
            applied.addProperty("idempotencyKey", idempotencyKey(placed));
            applied.addProperty("structureId", requiredString(placed, "structureId"));
            applied.addProperty("zonePatchId", requiredString(placed, "zonePatchId"));
            applied.addProperty("footprintMode", requiredString(placed, "footprintMode"));
            applied.addProperty("sourceSelectionId", requiredString(placed, "sourceSelectionId"));
            applied.addProperty("anchorCandidateId", requiredString(placed, "anchorCandidateId"));
            applied.add("footprint", requiredObject(placed, "footprint").deepCopy());
            applied.add("clearanceFootprint", requiredObject(placed, "clearanceFootprint").deepCopy());
            applied.add("requiredChunkRange", chunkRangeJson(bounds(requiredObject(placed, "clearanceFootprint"))));
            applied.addProperty("worldMutationApplied", true);
            appliedJobs.add(applied);
            addAppliedPieces(appliedPieces, placed, jobId);
        }
        ledger.add("appliedJobs", appliedJobs);
        ledger.add("appliedPieces", appliedPieces);
        ledger.addProperty("appliedJobCount", appliedJobs.size());
        ledger.addProperty("appliedPieceCount", appliedPieces.size());
        return ledger;
    }

    private String jobIdFromPlaced(JsonObject placed) {
        String placedId = requiredString(placed, "placedId");
        String taskId = placedId.startsWith("placed_") ? placedId.substring("placed_".length()) : placedId;
        if ("fixed_footprint".equals(requiredString(placed, "footprintMode"))) {
            return "job_fixed_" + safeId(taskId);
        }
        return "job_variable_" + safeId(taskId);
    }

    private String idempotencyKey(JsonObject placed) {
        return safeId(requiredString(placed, "structureId")) + ":"
                + safeId(requiredString(placed, "sourceSelectionId")) + ":"
                + safeId(requiredString(placed, "anchorCandidateId"));
    }

    private void addAppliedPieces(JsonArray appliedPieces, JsonObject placed, String jobId) {
        JsonObject boundedTrace = objectValue(placed, "boundedJigsawTrace", null);
        if (boundedTrace == null) {
            return;
        }
        JsonArray pieces = boundedTracePieces(boundedTrace);
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        for (JsonElement elem : pieces) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject piece = elem.getAsJsonObject();
            if ("failed".equals(stringValue(piece, "validatorResult", ""))) {
                continue;
            }
            String pieceId = stringValue(piece, "pieceId", "piece_" + (++index));
            String pieceKey = idempotencyKey(placed) + ":" + safeId(pieceId);
            if (!seen.add(pieceKey)) {
                continue;
            }
            JsonObject footprint = objectValue(piece, "footprint", requiredObject(placed, "footprint"));
            JsonObject applied = new JsonObject();
            applied.addProperty("jobId", jobId);
            applied.addProperty("placedId", requiredString(placed, "placedId"));
            applied.addProperty("pieceId", pieceId);
            applied.addProperty("idempotencyKey", pieceKey);
            applied.addProperty("structureId", requiredString(placed, "structureId"));
            applied.addProperty("sourceSelectionId", requiredString(placed, "sourceSelectionId"));
            applied.addProperty("anchorCandidateId", requiredString(placed, "anchorCandidateId"));
            applied.addProperty("templateId", stringValue(piece, "templateId", ""));
            applied.addProperty("poolId", stringValue(piece, "poolId", ""));
            if (piece.has("anchorBlock") && piece.get("anchorBlock").isJsonObject()) {
                applied.add("anchorBlock", piece.getAsJsonObject("anchorBlock").deepCopy());
            }
            applied.addProperty("rotation", stringValue(piece, "rotation", stringValue(placed, "rotation", "NONE")));
            applied.add("footprint", footprint.deepCopy());
            applied.add("requiredChunkRange", chunkRangeJson(bounds(footprint)));
            applied.addProperty("visibleAreaCost", intValue(piece, "visibleAreaCost", area(bounds(footprint))));
            applied.addProperty("worldMutationApplied", true);
            appliedPieces.add(applied);
        }
    }

    private JsonArray boundedTracePieces(JsonObject boundedTrace) {
        JsonObject plan = objectValue(boundedTrace, "plan", null);
        JsonArray pieces;
        if (plan != null && plan.has("pieces") && plan.get("pieces").isJsonArray()
                && !plan.getAsJsonArray("pieces").isEmpty()) {
            pieces = plan.getAsJsonArray("pieces");
        } else {
            pieces = arrayValue(boundedTrace, "acceptedPieces", new JsonArray());
        }
        if ("start_piece_adapter".equals(stringValue(boundedTrace, "worldPasteMode", "")) && !pieces.isEmpty()) {
            JsonArray startOnly = new JsonArray();
            startOnly.add(pieces.get(0).deepCopy());
            return startOnly;
        }
        return pieces;
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

    private static StartCandidateScore score(ZoneInfo zone, BlockBounds footprint, long worldSeed, String seedKey) {
        BlockPoint center = footprint.center();
        BlockPoint zoneCenter = zone.bounds().center();
        double distance = Math.hypot(center.x() - zoneCenter.x(), center.z() - zoneCenter.z());
        double jitter = new Random((seedKey + ":" + worldSeed + ":" + center.x() + ":" + center.z()).hashCode()).nextDouble();
        double interiorScore = Math.max(1.0, 1000.0 - distance + jitter);
        ExpansionMetrics expansion = zone.expansionMetrics(footprint, 3);
        double expansionScore = expansion.buildableRatio() * 420.0d;
        double corridorScore = Math.min(1.0d, expansion.corridorReachCells() / 12.0d) * 180.0d;
        double reservedPenalty = expansion.reservedRatio() * 360.0d
                + Math.max(0, 3 - expansion.nearestReservedDistanceCells()) * 45.0d;
        double value = Math.max(1.0d, interiorScore + expansionScore + corridorScore - reservedPenalty);
        return new StartCandidateScore(value, interiorScore, expansionScore, corridorScore, reservedPenalty,
                expansion.buildableRatio(), expansion.reservedRatio(), expansion.nearestReservedDistanceCells(),
                expansion.corridorReachCells(), jitter);
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
        attempt.addProperty("materializationMode", "minecraft_place_structure");
        attempt.addProperty("sourcePlanRef", "PlannedFixedPlacementMap:" + requiredString(fixed, "placementId"));
        attempt.addProperty("retryBudget", 1);
        attempt.addProperty("status", "already_placed");
        attempt.addProperty("reasonCode", "");
        attempt.addProperty("message", "Existing real placement ledger entry reused.");
        attempt.addProperty("worldMutationApplied", true);
        return attempt;
    }

    private JsonObject alreadyPlacedVariableAttempt(VariableTask task, ZoneInfo zone) {
        JsonObject attempt = baseAttempt("variable", task.taskId(), zone.zonePatchId(), task.structureId(),
                task.placementKind(), task.placementCommand(), "", BlockPoint.ORIGIN, "NONE", 0);
        attempt.addProperty("materializationMode", task.materializationMode());
        attempt.addProperty("sourcePlanRef", "StructurePoolMap:" + task.selectionId());
        attempt.addProperty("retryBudget", task.retryBudget());
        attempt.addProperty("status", "already_placed");
        attempt.addProperty("reasonCode", "");
        attempt.addProperty("message", "Existing real placement ledger entry reused.");
        attempt.addProperty("worldMutationApplied", true);
        return attempt;
    }

    private JsonObject singleStartAlreadyMaterializedAttempt(VariableTask task, ZoneInfo zone,
                                                             VariableProgress progress) {
        JsonObject attempt = baseAttempt("variable", task.taskId(), zone.zonePatchId(), task.structureId(),
                task.placementKind(), task.placementCommand(), "", BlockPoint.ORIGIN, "NONE", 0);
        attempt.addProperty("materializationMode", task.materializationMode());
        attempt.addProperty("sourcePlanRef", "StructurePoolMap:" + task.selectionId());
        attempt.addProperty("retryBudget", task.retryBudget());
        attempt.addProperty("status", "already_placed");
        attempt.addProperty("reasonCode", "SINGLE_START_ALREADY_PLACED");
        attempt.addProperty("message", "Default D7 v2 single-start strategy keeps existing main start and does not open satellite starts.");
        attempt.addProperty("worldMutationApplied", true);
        attempt.addProperty("feasibility", progress.visibleAreaCost() >= task.targetAreaBlocks() * 0.7d
                ? "FULL" : "PARTIAL");
        attempt.addProperty("visibleAreaCost", progress.visibleAreaCost());
        attempt.addProperty("remainingTargetBlocks", progress.remainingTargetBlocks());
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
            JsonObject boundedTrace = objectValue(obj, "boundedJigsawTrace", null);
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
                    stringValue(obj, "materializationMode", "minecraft_place_structure"),
                    boundedTrace == null ? null : boundedTrace.deepCopy(),
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
                                int retryBudget, BoundedJigsawConfig boundedConfig) {
        int startAreaBlocks() {
            return startFootprint.widthBlocks() * startFootprint.depthBlocks();
        }

        VariableTask withTargetAreaBlocks(int targetAreaBlocks) {
            return new VariableTask(selectionId, taskId, structureId, placementKind, placementCommand,
                    materializationMode, targetAreaBlocks, weight, startFootprint, maxAreaBlocks, rotations,
                    seedKey, retryBudget, boundedConfig);
        }

        BlockBounds requiredBounds(BlockPoint anchor) {
            int side = (int) Math.ceil(Math.sqrt(Math.max(maxAreaBlocks, targetAreaBlocks)));
            int radius = Math.max(Math.max(startFootprint.widthBlocks(), startFootprint.depthBlocks()),
                    Math.max(16, (side + 1) / 2));
            return boundsAround(anchor, radius + 16);
        }
    }

    private record VariableProgress(int visibleAreaCost, int remainingTargetBlocks, int placedCount,
                                    Set<String> anchorCandidateIds) {
        VariableProgress {
            anchorCandidateIds = Set.copyOf(anchorCandidateIds);
        }
    }

    private record AttemptResult(JsonObject attemptJson, JsonArray attempts, Placed placed, String reasonCode,
                                 boolean waiting, Set<String> attemptedStartCandidateIds) {
        AttemptResult {
            attemptedStartCandidateIds = Set.copyOf(attemptedStartCandidateIds);
        }

        AttemptResult(JsonObject attemptJson, JsonArray attempts, Placed placed, String reasonCode) {
            this(attemptJson, attempts, placed, reasonCode, false, Set.of());
        }

        AttemptResult(JsonObject attemptJson, JsonArray attempts, Placed placed, String reasonCode,
                      boolean waiting) {
            this(attemptJson, attempts, placed, reasonCode, waiting, Set.of());
        }
    }

    private record BoundedJigsawConfig(int maxPieces, int maxDepth, int maxPools, double budgetHardCapRatio,
                                       int sampleCandidateCount, int sampleSeedsPerCandidate,
                                       boolean enableSatelliteStarts) {
        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("maxPieces", maxPieces);
            obj.addProperty("maxDepth", maxDepth);
            obj.addProperty("maxPools", maxPools);
            obj.addProperty("budgetHardCapRatio", budgetHardCapRatio);
            obj.addProperty("sampleCandidateCount", sampleCandidateCount);
            obj.addProperty("sampleSeedsPerCandidate", sampleSeedsPerCandidate);
            obj.addProperty("enableSatelliteStarts", enableSatelliteStarts);
            return obj;
        }
    }

    private record Score(double value, String feasibility, String limitingFactor, JsonObject breakdown) {
    }

    private record PlanSample(JsonObject sample, PlacementRequest request, PlacementResult result, Score score,
                               String startCandidateId, JsonObject candidate) {
    }

    private record StartCandidateScore(double value, double interiorScore, double expansionScore,
                                       double corridorScore, double reservedPenalty, double localBuildableRatio,
                                       double localReservedRatio, int nearestReservedDistanceCells,
                                       int corridorReachCells, double jitter) {
        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("interiorScore", interiorScore);
            obj.addProperty("expansionScore", expansionScore);
            obj.addProperty("corridorScore", corridorScore);
            obj.addProperty("reservedPenalty", reservedPenalty);
            obj.addProperty("localBuildableRatio", localBuildableRatio);
            obj.addProperty("localReservedRatio", localReservedRatio);
            obj.addProperty("nearestReservedDistanceCells", nearestReservedDistanceCells);
            obj.addProperty("corridorReachCells", corridorReachCells);
            obj.addProperty("jitter", jitter);
            obj.addProperty("score", value);
            return obj;
        }
    }

    private record ExpansionMetrics(double buildableRatio, double reservedRatio, int nearestReservedDistanceCells,
                                    int corridorReachCells) {
    }

    private record Placed(String placedId, String sourceSelectionId, String anchorCandidateId, String zonePatchId,
                          String structureId, String footprintMode, String placementKind, String placementCommand,
                          BlockPoint anchorBlock, BlockPoint commandAnchorBlock, String rotation, BlockBounds footprint,
                          BlockBounds clearanceFootprint, int visibleAreaCost, String materializationMode,
                          JsonObject boundedJigsawTrace, boolean worldMutationApplied) {
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
            obj.addProperty("materializationMode", materializationMode);
            obj.add("anchorBlock", anchorBlock.asJson());
            obj.add("commandAnchorBlock", commandAnchorBlock.asJson());
            obj.addProperty("rotation", rotation);
            obj.add("footprint", boundsJson(footprint));
            obj.add("clearanceFootprint", boundsJson(clearanceFootprint));
            obj.addProperty("visibleAreaCost", visibleAreaCost);
            if (boundedJigsawTrace != null) {
                obj.add("boundedJigsawTrace", boundedJigsawTrace.deepCopy());
            }
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
        private final Set<Long> reservedCells = new LinkedHashSet<>();
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
            for (BuildableAreaMap.ReservedCell cell : buildable.reservedCells()) {
                reservedCells.add(key(grid.blockToCellX(cell.blockMinX()), grid.blockToCellZ(cell.blockMinZ())));
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
            if (this.bounds == null || !contains(this.bounds, bounds)) {
                return false;
            }
            int rawMinCellX = grid.blockToCellX(bounds.minX());
            int rawMaxCellX = grid.blockToCellX(bounds.maxX());
            int rawMinCellZ = grid.blockToCellZ(bounds.minZ());
            int rawMaxCellZ = grid.blockToCellZ(bounds.maxZ());
            if (rawMinCellX < 0 || rawMaxCellX >= grid.cellsX()
                    || rawMinCellZ < 0 || rawMaxCellZ >= grid.cellsZ()) {
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

        ExpansionMetrics expansionMetrics(BlockBounds footprint, int radiusCells) {
            if (footprint == null) {
                return new ExpansionMetrics(0.0d, 1.0d, 0, 0);
            }
            int radius = Math.max(1, radiusCells);
            int minCellX = clampCellX(grid.blockToCellX(footprint.minX()) - radius);
            int maxCellX = clampCellX(grid.blockToCellX(footprint.maxX()) + radius);
            int minCellZ = clampCellZ(grid.blockToCellZ(footprint.minZ()) - radius);
            int maxCellZ = clampCellZ(grid.blockToCellZ(footprint.maxZ()) + radius);
            int total = 0;
            int buildable = 0;
            int reserved = 0;
            int nearestReserved = Integer.MAX_VALUE;
            int centerCellX = grid.blockToCellX(footprint.center().x());
            int centerCellZ = grid.blockToCellZ(footprint.center().z());
            for (int x = minCellX; x <= maxCellX; x++) {
                for (int z = minCellZ; z <= maxCellZ; z++) {
                    total++;
                    long key = key(x, z);
                    if (buildableCells.contains(key)) {
                        buildable++;
                    }
                    if (reservedCells.contains(key)) {
                        reserved++;
                        nearestReserved = Math.min(nearestReserved,
                                Math.abs(x - centerCellX) + Math.abs(z - centerCellZ));
                    }
                }
            }
            if (nearestReserved == Integer.MAX_VALUE) {
                nearestReserved = radius + 1;
            }
            int corridorReach = corridorReach(centerCellX, centerCellZ, radius + 3);
            return new ExpansionMetrics(total == 0 ? 0.0d : buildable / (double) total,
                    total == 0 ? 0.0d : reserved / (double) total, nearestReserved, corridorReach);
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
            JsonArray reserved = new JsonArray();
            for (BuildableAreaMap.ReservedCell cell : buildable.reservedCells()) {
                reserved.add(cell.asJson());
            }
            obj.add("reservedCells", reserved);
            return obj;
        }

        private int clampCellX(int x) {
            return Math.max(0, Math.min(grid.cellsX() - 1, x));
        }

        private int clampCellZ(int z) {
            return Math.max(0, Math.min(grid.cellsZ() - 1, z));
        }

        private int corridorReach(int centerCellX, int centerCellZ, int maxCells) {
            return corridorReach(centerCellX, centerCellZ, 1, 0, maxCells)
                    + corridorReach(centerCellX, centerCellZ, -1, 0, maxCells)
                    + corridorReach(centerCellX, centerCellZ, 0, 1, maxCells)
                    + corridorReach(centerCellX, centerCellZ, 0, -1, maxCells);
        }

        private int corridorReach(int centerCellX, int centerCellZ, int dx, int dz, int maxCells) {
            int reach = 0;
            for (int step = 1; step <= maxCells; step++) {
                int x = centerCellX + dx * step;
                int z = centerCellZ + dz * step;
                if (x < 0 || x >= grid.cellsX() || z < 0 || z >= grid.cellsZ()) {
                    break;
                }
                long key = key(x, z);
                if (!buildableCells.contains(key) || reservedCells.contains(key)) {
                    break;
                }
                reach++;
            }
            return reach;
        }

        private long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }

        private boolean contains(BlockBounds container, BlockBounds child) {
            return child.minX() >= container.minX() && child.maxX() <= container.maxX()
                    && child.minZ() >= container.minZ() && child.maxZ() <= container.maxZ();
        }
    }

    private record CellAnchor(int blockMinX, int blockMinZ) {
    }

    public record PlacementRequest(String structureId, String placementKind, String placementCommand,
                                   BlockPoint anchorBlock, String rotation, BlockBounds footprint,
                                   BlockBounds requiredLoadBounds,
                                   String footprintMode, String materializationMode, JsonObject constraintField,
                                   int targetAreaBlocks, JsonObject boundedJigsawConfig, String planSampleKey) {
        public PlacementRequest(String structureId, String placementKind, String placementCommand,
                                BlockPoint anchorBlock, String rotation, BlockBounds footprint,
                                BlockBounds requiredLoadBounds,
                                String footprintMode) {
            this(structureId, placementKind, placementCommand, anchorBlock, rotation, footprint, requiredLoadBounds,
                    footprintMode, "minecraft_place_structure", new JsonObject(),
                    footprint == null ? 0 : area(footprint), new JsonObject(), "");
        }

        public PlacementRequest(String structureId, String placementKind, String placementCommand,
                                BlockPoint anchorBlock, String rotation, BlockBounds footprint,
                                BlockBounds requiredLoadBounds,
                                String footprintMode, String materializationMode, JsonObject constraintField,
                                int targetAreaBlocks) {
            this(structureId, placementKind, placementCommand, anchorBlock, rotation, footprint, requiredLoadBounds,
                    footprintMode, materializationMode, constraintField, targetAreaBlocks, new JsonObject(), "");
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
            return failed(reasonCode, message, null, null, null);
        }

        public static PlacementResult failed(String reasonCode, String message, JsonObject trace) {
            return failed(reasonCode, message, trace, null, null);
        }

        public static PlacementResult failed(String reasonCode, String message, JsonObject trace,
                                             BlockBounds footprint, BlockBounds requiredLoadBounds) {
            return new PlacementResult(false, false, false, reasonCode, message == null ? "" : message,
                    trace, footprint, requiredLoadBounds);
        }

        public static PlacementResult waiting(String reasonCode, String message) {
            return waiting(reasonCode, message, null);
        }

        public static PlacementResult waiting(String reasonCode, String message, JsonObject trace) {
            return new PlacementResult(false, true, false, reasonCode, message == null ? "" : message,
                    trace, null, null);
        }
    }

    public interface PlacementBackend {
        PlacementResult place(PlacementRequest request);

        default PlacementResult placeBoundedJigsaw(PlacementRequest request) {
            return PlacementResult.failed("BOUNDED_JIGSAW_UNSUPPORTED",
                    "Placement backend does not support bounded jigsaw materialization.");
        }

        default PlacementResult planBoundedJigsaw(PlacementRequest request) {
            return placeBoundedJigsaw(request);
        }

        default PlacementResult materializeBoundedJigsaw(PlacementRequest request, JsonObject selectedPlanTrace) {
            if (selectedPlanTrace == null) {
                return PlacementResult.failed("JIGSAW_NO_ACCEPTED_PIECE",
                        "Selected bounded jigsaw plan trace is missing.");
            }
            BlockBounds footprint = traceFootprint(selectedPlanTrace);
            return PlacementResult.placed("Materialized selected bounded jigsaw plan through default backend.",
                    selectedPlanTrace.deepCopy(), footprint, footprint);
        }

        static PlacementBackend traceOnly() {
            return new PlacementBackend() {
                @Override
                public PlacementResult place(PlacementRequest request) {
                    return PlacementResult.dryRunAccepted("Trace-only placement backend accepted configured structure.");
                }
            };
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

    private static BlockBounds traceFootprint(JsonObject trace) {
        JsonObject plan = objectValue(trace, "plan", null);
        if (plan != null && plan.has("estimatedFootprint") && plan.get("estimatedFootprint").isJsonObject()) {
            return bounds(plan.getAsJsonObject("estimatedFootprint"));
        }
        BlockBounds union = null;
        JsonArray pieces = plan != null && plan.has("pieces") && plan.get("pieces").isJsonArray()
                ? plan.getAsJsonArray("pieces")
                : arrayValue(trace, "acceptedPieces", new JsonArray());
        for (JsonElement elem : pieces) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject footprint = objectValue(elem.getAsJsonObject(), "footprint", null);
            if (footprint == null) {
                continue;
            }
            BlockBounds bounds = bounds(footprint);
            union = union == null ? bounds : new BlockBounds(
                    Math.min(union.minX(), bounds.minX()),
                    Math.min(union.minZ(), bounds.minZ()),
                    Math.max(union.maxX(), bounds.maxX()),
                    Math.max(union.maxZ(), bounds.maxZ()));
        }
        return union;
    }
}
