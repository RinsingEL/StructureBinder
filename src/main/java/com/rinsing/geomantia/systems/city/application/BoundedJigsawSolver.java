package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.Random;

public final class BoundedJigsawSolver {
    private final PieceRuleEvaluator ruleEvaluator;

    public BoundedJigsawSolver() {
        this(PieceRuleEvaluator.noop());
    }

    public BoundedJigsawSolver(PieceRuleEvaluator ruleEvaluator) {
        this.ruleEvaluator = ruleEvaluator == null ? PieceRuleEvaluator.noop() : ruleEvaluator;
    }

    public JsonObject solve(JsonObject input) {
        JsonObject spec = input == null ? new JsonObject() : input;
        String sourceStructureId = stringValue(spec, "sourceStructureId", "");
        String seedKey = stringValue(spec, "seedKey", sourceStructureId);
        int targetAreaBlocks = intValue(spec, "targetAreaBlocks", 0);
        int maxPieces = Math.max(1, intValue(spec, "maxPieces", 4));
        int maxDepth = Math.max(0, intValue(spec, "maxDepth", 2));
        double budgetHardCapRatio = Math.max(1.0d, doubleValue(spec, "budgetHardCapRatio", 1.2d));
        int areaHardCapBlocks = targetAreaBlocks <= 0
                ? 0
                : Math.max(targetAreaBlocks, intValue(spec, "areaHardCapBlocks",
                (int) Math.ceil(targetAreaBlocks * budgetHardCapRatio)));
        CityConstraintField constraintField = CityConstraintField.fromJson(objectValue(spec, "constraintField", new JsonObject()));
        JsonObject candidatePools = objectValue(spec, "candidatePools", new JsonObject());
        JsonObject trace = traceSkeleton(spec, sourceStructureId, targetAreaBlocks, maxPieces, maxDepth,
                budgetHardCapRatio, areaHardCapBlocks);
        State state = new State(trace, constraintField, targetAreaBlocks, areaHardCapBlocks, maxPieces, maxDepth,
                seedKey, ruleEvaluator);

        JsonArray startPieces = arrayValue(spec, "startPieces", new JsonArray());
        if (startPieces.isEmpty()) {
            addStoppedBranch(state, OpenBranch.start(stringValue(spec, "startPool", "")),
                    "BOUNDED_JIGSAW_POOL_EMPTY", false, true);
            updateMetrics(trace, state);
            return trace;
        }

        acceptBestPassing(state, OpenBranch.start(stringValue(spec, "startPool", "")), startPieces);
        while (!state.openBranches.isEmpty() && state.acceptedPieces < maxPieces) {
            OpenBranch branch = state.openBranches.remove();
            if (branch.depth > maxDepth) {
                addStoppedBranch(state, branch, "JIGSAW_BRANCH_DEPTH_LIMIT", false, true);
                continue;
            }
            if (state.areaHardCapBlocks > 0 && state.visibleAreaCost >= state.areaHardCapBlocks) {
                addStoppedBranch(state, branch, "JIGSAW_AREA_HARD_CAP_REACHED", false, true);
                continue;
            }
            JsonArray pool = candidatePools.has(branch.poolId) && candidatePools.get(branch.poolId).isJsonArray()
                    ? candidatePools.getAsJsonArray(branch.poolId)
                    : new JsonArray();
            if (pool.isEmpty()) {
                addStoppedBranch(state, branch, "BOUNDED_JIGSAW_POOL_EMPTY", false, true);
                continue;
            }
            acceptBestPassing(state, branch, pool);
        }

        while (!state.openBranches.isEmpty()) {
            addStoppedBranch(state, state.openBranches.remove(), "JIGSAW_PIECE_BUDGET_REACHED", false, true);
        }
        if (state.acceptedPieces >= maxPieces) {
            state.terminationReasons.add("MAX_PIECES_REACHED");
        }
        if (state.openBranches.isEmpty() && state.acceptedPieces > 0) {
            state.terminationReasons.add("NO_FRONTIER");
        }
        if (state.acceptedPieces == 0) {
            incrementFailure(trace, "JIGSAW_NO_ACCEPTED_PIECE");
        }
        updateMetrics(trace, state);
        return trace;
    }

    private boolean acceptBestPassing(State state, OpenBranch branch, JsonArray candidates) {
        String lastReason = "";
        List<AcceptedCandidate> acceptable = new ArrayList<>();
        for (JsonObject candidate : shuffledCandidates(candidates, state.seedKey + ":" + branch.branchId)) {
            JsonObject piece = alignCandidateToBranch(candidate, branch);
            applyBranch(piece, branch);
            BlockBounds footprint = footprint(piece);
            if (footprint != null && !piece.has("visibleAreaCost")) {
                piece.addProperty("visibleAreaCost", area(footprint));
            }
            PieceDecision decision = pieceDecision(state, branch, piece, footprint);
            piece.add("ruleResults", decision.ruleResults().deepCopy());
            piece.add("ruleDecision", decision.asJson());
            if (decision.accepted()) {
                acceptable.add(new AcceptedCandidate(piece, footprint, areaDistance(state, piece, footprint),
                        acceptable.size()));
                continue;
            }
            lastReason = decision.reasonCode();
            addRejectedPiece(state, piece, decision.reasonCode());
        }
        if (!acceptable.isEmpty()) {
            AcceptedCandidate selected = acceptable.stream()
                    .min(Comparator.comparingInt(AcceptedCandidate::areaDistance)
                            .thenComparingInt(AcceptedCandidate::order))
                    .orElseThrow();
            markAreaFit(state, selected.piece(), selected.footprint(), selected.areaDistance());
            addAcceptedPiece(state, selected.piece(), selected.footprint());
            enqueueOpenConnectors(state, selected.piece(), branch.depth + 1);
            return true;
        }
        addStoppedBranch(state, branch, lastReason.isBlank() ? "JIGSAW_NO_ACCEPTED_PIECE" : lastReason,
                false, lastReason.isBlank());
        return false;
    }

    private List<JsonObject> shuffledCandidates(JsonArray candidates, String seedKey) {
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement elem : candidates) {
            if (elem.isJsonObject()) {
                list.add(elem.getAsJsonObject());
            }
        }
        Collections.shuffle(list, new Random(seedKey.hashCode()));
        return list;
    }

    private int areaDistance(State state, JsonObject piece, BlockBounds footprint) {
        if (state.targetAreaBlocks <= 0 || footprint == null) {
            return 0;
        }
        int projectedArea = state.visibleAreaCost + intValue(piece, "visibleAreaCost", area(footprint));
        return Math.abs(state.targetAreaBlocks - projectedArea);
    }

    private void markAreaFit(State state, JsonObject piece, BlockBounds footprint, int areaDistance) {
        int pieceArea = footprint == null ? 0 : intValue(piece, "visibleAreaCost", area(footprint));
        int projectedArea = state.visibleAreaCost + pieceArea;
        JsonObject areaFit = new JsonObject();
        areaFit.addProperty("ruleId", "visible_area_target_fit");
        areaFit.addProperty("status", state.targetAreaBlocks <= 0 ? "not_applicable" : "passed");
        areaFit.addProperty("targetAreaBlocks", state.targetAreaBlocks);
        areaFit.addProperty("areaHardCapBlocks", state.areaHardCapBlocks);
        areaFit.addProperty("currentVisibleAreaCost", state.visibleAreaCost);
        areaFit.addProperty("pieceVisibleAreaCost", pieceArea);
        areaFit.addProperty("projectedVisibleAreaCost", projectedArea);
        areaFit.addProperty("distanceToTargetBlocks", state.targetAreaBlocks <= 0 ? 0 : areaDistance);
        if (state.targetAreaBlocks > 0) {
            areaFit.addProperty("fillRatio", projectedArea / (double) state.targetAreaBlocks);
            if (projectedArea > state.targetAreaBlocks) {
                areaFit.addProperty("status", "warning");
                areaFit.addProperty("reasonCode", "JIGSAW_AREA_SOFT_BUDGET_EXCEEDED");
            }
        }
        piece.add("areaTargetFit", areaFit.deepCopy());
        JsonArray ruleResults = arrayValue(piece, "ruleResults", new JsonArray());
        ruleResults.add(areaFit);
        piece.add("ruleResults", ruleResults);
        if (piece.has("ruleDecision") && piece.get("ruleDecision").isJsonObject()) {
            piece.getAsJsonObject("ruleDecision").add("ruleResults", ruleResults.deepCopy());
        }
    }

    private PieceDecision pieceDecision(State state, OpenBranch branch, JsonObject piece, BlockBounds footprint) {
        JsonArray ruleResults = new JsonArray();
        if ("failed".equals(stringValue(piece, "alignmentStatus", ""))) {
            String reason = stringValue(piece, "alignmentReasonCode", "JIGSAW_CONNECTOR_ALIGNMENT_FAILED");
            ruleResults.add(ruleResult("connector_alignment", "failed", reason));
            return PieceDecision.reject(reason, ruleResults);
        }
        if ("connector_alignment_pending".equals(stringValue(piece, "prototypePlacementStatus", ""))) {
            ruleResults.add(ruleResult("connector_alignment", "failed", "JIGSAW_CONNECTOR_ALIGNMENT_PENDING"));
            return PieceDecision.reject("JIGSAW_CONNECTOR_ALIGNMENT_PENDING", ruleResults);
        }
        if (!matchesConnector(branch, piece)) {
            ruleResults.add(ruleResult("connector_alignment", "failed", "JIGSAW_CONNECTOR_TARGET_MISMATCH"));
            return PieceDecision.reject("JIGSAW_CONNECTOR_TARGET_MISMATCH", ruleResults);
        }
        ruleResults.add(ruleResult("connector_alignment", "passed", ""));
        if (footprint == null) {
            ruleResults.add(ruleResult("piece_footprint", "failed", "JIGSAW_PIECE_FOOTPRINT_MISSING"));
            return PieceDecision.reject("JIGSAW_PIECE_FOOTPRINT_MISSING", ruleResults);
        }
        int pieceArea = intValue(piece, "visibleAreaCost", area(footprint));
        if (state.areaHardCapBlocks > 0 && state.visibleAreaCost + pieceArea > state.areaHardCapBlocks) {
            ruleResults.add(ruleResult("visible_area_budget", "failed", "JIGSAW_AREA_HARD_CAP_REACHED"));
            return PieceDecision.reject("JIGSAW_AREA_HARD_CAP_REACHED", ruleResults);
        }
        if (state.targetAreaBlocks > 0 && state.visibleAreaCost + pieceArea > state.targetAreaBlocks) {
            ruleResults.add(ruleResult("visible_area_budget", "warning", "JIGSAW_AREA_SOFT_BUDGET_EXCEEDED"));
        } else {
            ruleResults.add(ruleResult("visible_area_budget", "passed", ""));
        }
        for (BlockBounds accepted : state.acceptedFootprints) {
            if (accepted.overlaps(footprint)) {
                ruleResults.add(ruleResult("runtime_occupied", "failed", "JIGSAW_PIECE_RESERVED_CONFLICT"));
                return PieceDecision.reject("JIGSAW_PIECE_RESERVED_CONFLICT", ruleResults);
            }
        }
        ruleResults.add(ruleResult("runtime_occupied", "passed", ""));
        CityConstraintField.ValidationResult validation = state.constraintField.validatePiece(footprint,
                remainingBudgetForConstraint(state));
        append(ruleResults, validation.ruleResults());
        if (!validation.passed()) {
            return PieceDecision.reject(validation.reasonCode(), ruleResults);
        }
        PieceDecision runtimeDecision = state.ruleEvaluator.evaluate(piece, footprint, state.visibleAreaCost,
                state.targetAreaBlocks);
        append(ruleResults, runtimeDecision.ruleResults());
        if (!runtimeDecision.accepted()) {
            return PieceDecision.reject(runtimeDecision.reasonCode(), ruleResults);
        }
        return PieceDecision.accept(ruleResults);
    }

    private boolean matchesConnector(OpenBranch branch, JsonObject piece) {
        if (branch.target.isBlank()) {
            return true;
        }
        String attachTarget = stringValue(piece, "attachTarget", stringValue(piece, "target", ""));
        if (branch.target.equals(attachTarget)) {
            return true;
        }
        for (JsonElement elem : arrayValue(piece, "connectorRefs", new JsonArray())) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject connector = elem.getAsJsonObject();
            if (branch.target.equals(stringValue(connector, "name", ""))
                    || branch.name.equals(stringValue(connector, "target", ""))) {
                return true;
            }
        }
        return false;
    }

    private void addAcceptedPiece(State state, JsonObject piece, BlockBounds footprint) {
        normalizeAcceptedPieceId(state, piece);
        piece.addProperty("validatorResult", "passed");
        state.trace.getAsJsonArray("acceptedPieces").add(piece.deepCopy());
        JsonObject plan = state.trace.getAsJsonObject("plan");
        plan.getAsJsonArray("pieces").add(piece.deepCopy());
        state.acceptedFootprints.add(footprint);
        state.acceptedPieces++;
        state.visibleAreaCost += intValue(piece, "visibleAreaCost", area(footprint));
        state.estimatedFootprint = state.estimatedFootprint == null ? footprint : union(state.estimatedFootprint, footprint);
        plan.add("estimatedFootprint", boundsJson(state.estimatedFootprint));
        plan.addProperty("visibleAreaCost", state.visibleAreaCost);
    }

    private static void normalizeAcceptedPieceId(State state, JsonObject piece) {
        String originalId = stringValue(piece, "pieceId", "piece_" + state.acceptedPieces);
        String baseId = originalId.isBlank() ? "piece_" + state.acceptedPieces : originalId;
        String uniqueId = baseId;
        int suffix = 2;
        while (state.acceptedPieceIds.contains(uniqueId)) {
            uniqueId = baseId + "_inst_" + suffix++;
        }
        if (!uniqueId.equals(originalId)) {
            piece.addProperty("sourcePieceId", originalId);
            piece.addProperty("pieceId", uniqueId);
        }
        state.acceptedPieceIds.add(uniqueId);
    }

    private void addRejectedPiece(State state, JsonObject piece, String reasonCode) {
        piece.addProperty("validatorResult", "failed");
        piece.addProperty("reasonCode", reasonCode);
        state.trace.getAsJsonArray("rejectedPieces").add(piece.deepCopy());
        incrementFailure(state.trace, reasonCode);
    }

    private void addStoppedBranch(State state, OpenBranch branch, String reasonCode, boolean endcapAttempted,
                                  boolean countFailure) {
        JsonObject stopped = new JsonObject();
        stopped.addProperty("branchId", branch.branchId);
        stopped.addProperty("parentPieceId", branch.parentPieceId);
        stopped.addProperty("parentConnectorId", branch.parentConnectorId);
        stopped.addProperty("poolId", branch.poolId);
        stopped.addProperty("depth", branch.depth);
        stopped.addProperty("action", "stop_branch");
        stopped.addProperty("reasonCode", reasonCode);
        JsonArray ruleResults = new JsonArray();
        ruleResults.add(ruleResult("branch_stop", reasonCode == null || reasonCode.isBlank() ? "warning" : "failed",
                reasonCode));
        stopped.add("ruleResults", ruleResults);
        stopped.addProperty("endcapAttempted", endcapAttempted);
        stopped.addProperty("endcapStatus", endcapAttempted ? "not_implemented" : "not_attempted");
        state.trace.getAsJsonArray("stoppedBranches").add(stopped.deepCopy());
        state.trace.getAsJsonObject("plan").getAsJsonArray("stoppedBranches").add(stopped.deepCopy());
        if (countFailure) {
            incrementFailure(state.trace, reasonCode);
        }
        state.terminationReasons.add(terminationCategory(reasonCode));
    }

    private void enqueueOpenConnectors(State state, JsonObject piece, int nextDepth) {
        String pieceId = stringValue(piece, "pieceId", "piece_" + state.acceptedPieces);
        for (JsonElement elem : arrayValue(piece, "connectorRefs", new JsonArray())) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject connector = elem.getAsJsonObject();
            if (boolValue(connector, "consumedByParent", false)) {
                continue;
            }
            String pool = stringValue(connector, "pool", "");
            if (pool.isBlank()) {
                continue;
            }
            String connectorId = stringValue(connector, "connectorId", "connector_" + state.openBranches.size());
            state.openBranches.add(new OpenBranch(
                    "branch_" + safeId(pieceId) + "_" + safeId(connectorId),
                    pieceId,
                    connectorId,
                    pool,
                    stringValue(connector, "name", ""),
                    stringValue(connector, "target", ""),
                    connector.deepCopy(),
                    nextDepth));
        }
    }

    private JsonObject alignCandidateToBranch(JsonObject candidate, OpenBranch branch) {
        JsonObject piece = candidate.deepCopy();
        if ("child_pool_prototype".equals(stringValue(piece, "adapterScope", ""))
                && !branch.parentConnectorId.isBlank()) {
            return BoundedJigsawConnectorAligner.alignToParentConnector(branch.connectorRef, piece);
        }
        return piece;
    }

    private void applyBranch(JsonObject piece, OpenBranch branch) {
        piece.addProperty("branchId", branch.branchId);
        piece.addProperty("parentPieceId", branch.parentPieceId);
        piece.addProperty("parentConnectorId", branch.parentConnectorId);
        piece.addProperty("depth", branch.depth);
    }

    private JsonObject traceSkeleton(JsonObject spec, String sourceStructureId, int targetAreaBlocks,
                                     int maxPieces, int maxDepth, double budgetHardCapRatio,
                                     int areaHardCapBlocks) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", "city_bounded_jigsaw_trace.v0.1");
        trace.addProperty("capability", "bounded_jigsaw_supported");
        trace.addProperty("sourceStructureId", sourceStructureId);
        trace.addProperty("startPool", stringValue(spec, "startPool", ""));
        trace.addProperty("targetAreaBlocks", targetAreaBlocks);
        trace.addProperty("maxPieces", maxPieces);
        trace.addProperty("maxDepth", maxDepth);
        trace.addProperty("budgetHardCapRatio", budgetHardCapRatio);
        trace.addProperty("areaHardCapBlocks", areaHardCapBlocks);
        trace.add("acceptedPieces", new JsonArray());
        trace.add("rejectedPieces", new JsonArray());
        trace.add("stoppedBranches", new JsonArray());
        trace.addProperty("fallbackUsed", false);
        trace.add("failureSummary", new JsonObject());
        trace.add("rejectionReport", new JsonObject());
        trace.add("terminationReport", new JsonObject());
        trace.add("metrics", new JsonObject());
        trace.add("plan", planSkeleton(spec, sourceStructureId));
        return trace;
    }

    private JsonObject planSkeleton(JsonObject spec, String sourceStructureId) {
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_bounded_jigsaw_plan.v0.1");
        plan.addProperty("planId", stringValue(spec, "planId", "bounded_" + safeId(sourceStructureId)));
        plan.addProperty("jobId", stringValue(spec, "jobId", "job_" + safeId(sourceStructureId)));
        plan.addProperty("sourceStructureId", sourceStructureId);
        plan.add("pieces", new JsonArray());
        plan.add("stoppedBranches", new JsonArray());
        plan.addProperty("visibleAreaCost", 0);
        JsonObject quality = new JsonObject();
        quality.addProperty("acceptedPieceCount", 0);
        quality.addProperty("stoppedBranchCount", 0);
        quality.addProperty("startPieceOnly", true);
        quality.add("warnings", new JsonArray());
        plan.add("quality", quality);
        return plan;
    }

    private void updateMetrics(JsonObject trace, State state) {
        JsonObject metrics = new JsonObject();
        metrics.addProperty("acceptedPieceCount", state.acceptedPieces);
        metrics.addProperty("rejectedPieceCount", trace.getAsJsonArray("rejectedPieces").size());
        metrics.addProperty("stoppedBranchCount", trace.getAsJsonArray("stoppedBranches").size());
        metrics.addProperty("visibleAreaCost", state.visibleAreaCost);
        metrics.addProperty("targetAreaBlocks", state.targetAreaBlocks);
        metrics.addProperty("areaHardCapBlocks", state.areaHardCapBlocks);
        metrics.addProperty("maxPieces", state.maxPieces);
        metrics.addProperty("maxDepth", state.maxDepth);
        metrics.addProperty("areaDistanceToTargetBlocks", areaDistanceToTarget(state));
        if (state.targetAreaBlocks > 0) {
            metrics.addProperty("areaFillRatio", state.visibleAreaCost / (double) state.targetAreaBlocks);
        }
        metrics.addProperty("openConnectorCount", state.openBranches.size());
        metrics.addProperty("maxAcceptedDepth", maxAcceptedDepth(trace.getAsJsonArray("acceptedPieces")));
        metrics.addProperty("compactness", compactness(state.estimatedFootprint, state.visibleAreaCost));
        JsonObject rejectionReport = rejectionReport(trace);
        JsonObject terminationReport = terminationReport(state);
        trace.add("rejectionReport", rejectionReport);
        trace.add("terminationReport", terminationReport);
        metrics.add("rejectionReport", rejectionReport.deepCopy());
        metrics.add("terminationReport", terminationReport.deepCopy());
        trace.add("metrics", metrics);
        JsonObject plan = trace.getAsJsonObject("plan");
        if (state.estimatedFootprint != null) {
            plan.add("estimatedFootprint", boundsJson(state.estimatedFootprint));
            plan.add("requiredChunkRange", chunkRangeJson(state.estimatedFootprint));
        }
        JsonObject quality = plan.getAsJsonObject("quality");
        quality.addProperty("acceptedPieceCount", state.acceptedPieces);
        quality.addProperty("stoppedBranchCount", trace.getAsJsonArray("stoppedBranches").size());
        quality.addProperty("startPieceOnly", state.acceptedPieces <= 1);
        quality.addProperty("areaDistanceToTargetBlocks", areaDistanceToTarget(state));
        quality.addProperty("maxAcceptedDepth", maxAcceptedDepth(trace.getAsJsonArray("acceptedPieces")));
        quality.addProperty("compactness", compactness(state.estimatedFootprint, state.visibleAreaCost));
        quality.add("terminationReport", terminationReport.deepCopy());
        quality.add("rejectionReport", rejectionReport.deepCopy());
        if (state.targetAreaBlocks > 0) {
            quality.addProperty("areaFillRatio", state.visibleAreaCost / (double) state.targetAreaBlocks);
        }
    }

    private static int remainingBudgetForConstraint(State state) {
        if (state.areaHardCapBlocks > 0) {
            return Math.max(0, state.areaHardCapBlocks - state.visibleAreaCost);
        }
        return Math.max(0, state.targetAreaBlocks - state.visibleAreaCost);
    }

    private static JsonObject rejectionReport(JsonObject trace) {
        JsonObject report = reportSkeleton();
        JsonArray rejected = trace.getAsJsonArray("rejectedPieces");
        for (JsonElement elem : rejected) {
            if (!elem.isJsonObject()) {
                continue;
            }
            String category = terminationCategory(stringValue(elem.getAsJsonObject(), "reasonCode", ""));
            report.addProperty(category, intValue(report, category, 0) + 1);
        }
        report.addProperty("totalRejectedPieces", rejected.size());
        return report;
    }

    private static JsonObject terminationReport(State state) {
        JsonObject report = reportSkeleton();
        for (String reason : state.terminationReasons) {
            report.addProperty(reason, intValue(report, reason, 0) + 1);
        }
        int total = 0;
        for (String key : report.keySet()) {
            if (!"totalRejectedPieces".equals(key) && !"totalTerminationEvents".equals(key)) {
                total += intValue(report, key, 0);
            }
        }
        report.addProperty("totalTerminationEvents", total);
        return report;
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
        return report;
    }

    private static String terminationCategory(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "OTHER_LIMITED";
        }
        return switch (reasonCode) {
            case "JIGSAW_PIECE_BUDGET_REACHED" -> "MAX_PIECES_REACHED";
            case "JIGSAW_BRANCH_DEPTH_LIMIT" -> "MAX_DEPTH_REACHED";
            case "JIGSAW_AREA_HARD_CAP_REACHED" -> "AREA_HARD_CAP_REACHED";
            case "JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA", "START_FOOTPRINT_OUT_OF_ZONE",
                    "FOOTPRINT_OUT_OF_ZONE" -> "BOUNDARY_LIMITED";
            case "JIGSAW_RULE_TERRAIN_TOO_UNEVEN", "JIGSAW_RULE_FLUID_OVERLAP",
                    "JIGSAW_RULE_TERRAIN_SUPPORT_TOO_LOW", "JIGSAW_RULE_CHUNK_WAITING",
                    "STRUCTURE_CHUNK_NOT_LOADED" -> "TERRAIN_LIMITED";
            case "JIGSAW_PIECE_RESERVED_CONFLICT", "START_RUNTIME_OCCUPIED", "AABB_OCCUPIED" -> "OCCUPIED_LIMITED";
            case "BOUNDED_JIGSAW_POOL_EMPTY", "BOUNDED_JIGSAW_POOL_MISSING",
                    "UNSUPPORTED_POOL_ELEMENT" -> "POOL_LIMITED";
            case "JIGSAW_CONNECTOR_ALIGNMENT_PENDING", "JIGSAW_CONNECTOR_ALIGNMENT_FAILED",
                    "JIGSAW_CONNECTOR_TARGET_MISMATCH", "JIGSAW_NO_ACCEPTED_PIECE" -> "CONNECTOR_LIMITED";
            default -> "OTHER_LIMITED";
        };
    }

    private static int maxAcceptedDepth(JsonArray acceptedPieces) {
        int max = 0;
        for (JsonElement elem : acceptedPieces) {
            if (elem.isJsonObject()) {
                max = Math.max(max, intValue(elem.getAsJsonObject(), "depth", 0));
            }
        }
        return max;
    }

    private static double compactness(BlockBounds estimatedFootprint, int visibleAreaCost) {
        if (estimatedFootprint == null || visibleAreaCost <= 0) {
            return 0.0d;
        }
        return Math.min(1.0d, visibleAreaCost / (double) area(estimatedFootprint));
    }

    private int areaDistanceToTarget(State state) {
        return state.targetAreaBlocks <= 0 ? 0 : Math.abs(state.targetAreaBlocks - state.visibleAreaCost);
    }

    private static void incrementFailure(JsonObject trace, String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return;
        }
        JsonObject summary = trace.getAsJsonObject("failureSummary");
        summary.addProperty(reasonCode, intValue(summary, reasonCode, 0) + 1);
    }

    private static void append(JsonArray target, JsonArray source) {
        if (target == null || source == null) {
            return;
        }
        for (JsonElement elem : source) {
            target.add(elem.deepCopy());
        }
    }

    private static JsonObject ruleResult(String ruleId, String status, String reasonCode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ruleId", ruleId);
        obj.addProperty("status", status);
        obj.addProperty("reasonCode", reasonCode == null ? "" : reasonCode);
        return obj;
    }

    private static BlockBounds footprint(JsonObject piece) {
        JsonObject obj = objectValue(piece, "footprint", null);
        return obj == null ? null : bounds(obj);
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static JsonObject chunkRangeJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minChunkX", Math.floorDiv(bounds.minX(), 16));
        obj.addProperty("minChunkZ", Math.floorDiv(bounds.minZ(), 16));
        obj.addProperty("maxChunkX", Math.floorDiv(bounds.maxX(), 16));
        obj.addProperty("maxChunkZ", Math.floorDiv(bounds.maxZ(), 16));
        return obj;
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static BlockBounds union(BlockBounds left, BlockBounds right) {
        return new BlockBounds(
                Math.min(left.minX(), right.minX()),
                Math.min(left.minZ(), right.minZ()),
                Math.max(left.maxX(), right.maxX()),
                Math.max(left.maxZ(), right.maxZ()));
    }

    private static int area(BlockBounds bounds) {
        return bounds.widthBlocks() * bounds.heightBlocks();
    }

    private static JsonArray arrayValue(JsonObject obj, String key, JsonArray defaultValue) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : defaultValue;
    }

    private static JsonObject objectValue(JsonObject obj, String key, JsonObject defaultValue) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : defaultValue;
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

    private static String safeId(String raw) {
        return raw == null || raw.isBlank() ? "unknown" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static final class State {
        private final JsonObject trace;
        private final CityConstraintField constraintField;
        private final int targetAreaBlocks;
        private final int areaHardCapBlocks;
        private final int maxPieces;
        private final int maxDepth;
        private final String seedKey;
        private final PieceRuleEvaluator ruleEvaluator;
        private final Queue<OpenBranch> openBranches = new ArrayDeque<>();
        private final List<BlockBounds> acceptedFootprints = new ArrayList<>();
        private final List<String> acceptedPieceIds = new ArrayList<>();
        private final List<String> terminationReasons = new ArrayList<>();
        private int acceptedPieces;
        private int visibleAreaCost;
        private BlockBounds estimatedFootprint;

        private State(JsonObject trace, CityConstraintField constraintField, int targetAreaBlocks,
                      int areaHardCapBlocks, int maxPieces, int maxDepth, String seedKey,
                      PieceRuleEvaluator ruleEvaluator) {
            this.trace = trace;
            this.constraintField = constraintField;
            this.targetAreaBlocks = targetAreaBlocks;
            this.areaHardCapBlocks = areaHardCapBlocks;
            this.maxPieces = maxPieces;
            this.maxDepth = maxDepth;
            this.seedKey = seedKey;
            this.ruleEvaluator = ruleEvaluator == null ? PieceRuleEvaluator.noop() : ruleEvaluator;
        }
    }

    private static boolean boolValue(JsonObject obj, String key, boolean defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsBoolean();
    }

    private record OpenBranch(String branchId, String parentPieceId, String parentConnectorId, String poolId,
                              String name, String target, JsonObject connectorRef, int depth) {
        static OpenBranch start(String startPool) {
            return new OpenBranch("branch_start", "", "", startPool == null ? "" : startPool,
                    "", "", new JsonObject(), 0);
        }
    }

    private record AcceptedCandidate(JsonObject piece, BlockBounds footprint, int areaDistance, int order) {
    }

    public interface PieceRuleEvaluator {
        PieceDecision evaluate(JsonObject piece, BlockBounds footprint, int visibleAreaCostSoFar,
                               int targetAreaBlocks);

        static PieceRuleEvaluator noop() {
            return (piece, footprint, visibleAreaCostSoFar, targetAreaBlocks) -> PieceDecision.accept(new JsonArray());
        }
    }

    public record PieceDecision(String decision, String reasonCode, JsonArray ruleResults) {
        public static PieceDecision accept(JsonArray ruleResults) {
            return new PieceDecision("accept", "", ruleResults == null ? new JsonArray() : ruleResults);
        }

        public static PieceDecision reject(String reasonCode, JsonArray ruleResults) {
            return new PieceDecision("reject", reasonCode == null ? "" : reasonCode,
                    ruleResults == null ? new JsonArray() : ruleResults);
        }

        public boolean accepted() {
            return "accept".equals(decision);
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("decision", decision);
            obj.addProperty("reasonCode", reasonCode);
            obj.add("ruleResults", ruleResults.deepCopy());
            return obj;
        }
    }
}
