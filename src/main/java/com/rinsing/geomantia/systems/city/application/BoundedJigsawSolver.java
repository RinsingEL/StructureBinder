package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Random;

public final class BoundedJigsawSolver {
    public JsonObject solve(JsonObject input) {
        JsonObject spec = input == null ? new JsonObject() : input;
        String sourceStructureId = stringValue(spec, "sourceStructureId", "");
        String seedKey = stringValue(spec, "seedKey", sourceStructureId);
        int targetAreaBlocks = intValue(spec, "targetAreaBlocks", 0);
        int maxPieces = Math.max(1, intValue(spec, "maxPieces", 4));
        int maxDepth = Math.max(0, intValue(spec, "maxDepth", 2));
        CityConstraintField constraintField = CityConstraintField.fromJson(objectValue(spec, "constraintField", new JsonObject()));
        JsonObject candidatePools = objectValue(spec, "candidatePools", new JsonObject());
        JsonObject trace = traceSkeleton(spec, sourceStructureId, targetAreaBlocks);
        State state = new State(trace, constraintField, targetAreaBlocks, maxPieces, maxDepth, seedKey);

        JsonArray startPieces = arrayValue(spec, "startPieces", new JsonArray());
        if (startPieces.isEmpty()) {
            addStoppedBranch(state, OpenBranch.start(stringValue(spec, "startPool", "")),
                    "BOUNDED_JIGSAW_POOL_EMPTY", false, true);
            updateMetrics(trace, state);
            return trace;
        }

        acceptFirstPassing(state, OpenBranch.start(stringValue(spec, "startPool", "")), startPieces);
        while (!state.openBranches.isEmpty() && state.acceptedPieces < maxPieces) {
            OpenBranch branch = state.openBranches.remove();
            if (branch.depth > maxDepth) {
                addStoppedBranch(state, branch, "JIGSAW_BRANCH_DEPTH_LIMIT", false, true);
                continue;
            }
            if (targetAreaBlocks > 0 && state.visibleAreaCost >= targetAreaBlocks) {
                addStoppedBranch(state, branch, "JIGSAW_AREA_BUDGET_REACHED", false, true);
                continue;
            }
            JsonArray pool = candidatePools.has(branch.poolId) && candidatePools.get(branch.poolId).isJsonArray()
                    ? candidatePools.getAsJsonArray(branch.poolId)
                    : new JsonArray();
            if (pool.isEmpty()) {
                addStoppedBranch(state, branch, "BOUNDED_JIGSAW_POOL_EMPTY", false, true);
                continue;
            }
            acceptFirstPassing(state, branch, pool);
        }

        while (!state.openBranches.isEmpty()) {
            addStoppedBranch(state, state.openBranches.remove(), "JIGSAW_PIECE_BUDGET_REACHED", false, true);
        }
        if (state.acceptedPieces == 0) {
            incrementFailure(trace, "JIGSAW_NO_ACCEPTED_PIECE");
        }
        updateMetrics(trace, state);
        return trace;
    }

    private boolean acceptFirstPassing(State state, OpenBranch branch, JsonArray candidates) {
        String lastReason = "";
        for (JsonObject candidate : shuffledCandidates(candidates, state.seedKey + ":" + branch.branchId)) {
            JsonObject piece = alignCandidateToBranch(candidate, branch);
            applyBranch(piece, branch);
            BlockBounds footprint = footprint(piece);
            if (footprint != null && !piece.has("visibleAreaCost")) {
                piece.addProperty("visibleAreaCost", area(footprint));
            }
            String reason = rejectionReason(state, branch, piece, footprint);
            if (reason.isBlank()) {
                addAcceptedPiece(state, piece, footprint);
                enqueueOpenConnectors(state, piece, branch.depth + 1);
                return true;
            }
            lastReason = reason;
            addRejectedPiece(state, piece, reason);
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

    private String rejectionReason(State state, OpenBranch branch, JsonObject piece, BlockBounds footprint) {
        if ("failed".equals(stringValue(piece, "alignmentStatus", ""))) {
            return stringValue(piece, "alignmentReasonCode", "JIGSAW_CONNECTOR_ALIGNMENT_FAILED");
        }
        if ("connector_alignment_pending".equals(stringValue(piece, "prototypePlacementStatus", ""))) {
            return "JIGSAW_CONNECTOR_ALIGNMENT_PENDING";
        }
        if (!matchesConnector(branch, piece)) {
            return "JIGSAW_CONNECTOR_TARGET_MISMATCH";
        }
        if (footprint == null) {
            return "JIGSAW_PIECE_FOOTPRINT_MISSING";
        }
        int pieceArea = intValue(piece, "visibleAreaCost", area(footprint));
        if (state.targetAreaBlocks > 0 && state.visibleAreaCost + pieceArea > state.targetAreaBlocks) {
            return "JIGSAW_AREA_BUDGET_REACHED";
        }
        for (BlockBounds accepted : state.acceptedFootprints) {
            if (accepted.overlaps(footprint)) {
                return "JIGSAW_PIECE_RESERVED_CONFLICT";
            }
        }
        CityConstraintField.ValidationResult validation = state.constraintField.validatePiece(footprint,
                Math.max(0, state.targetAreaBlocks - state.visibleAreaCost));
        return validation.passed() ? "" : validation.reasonCode();
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
        stopped.addProperty("endcapAttempted", endcapAttempted);
        stopped.addProperty("endcapStatus", endcapAttempted ? "not_implemented" : "not_attempted");
        state.trace.getAsJsonArray("stoppedBranches").add(stopped.deepCopy());
        state.trace.getAsJsonObject("plan").getAsJsonArray("stoppedBranches").add(stopped.deepCopy());
        if (countFailure) {
            incrementFailure(state.trace, reasonCode);
        }
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

    private JsonObject traceSkeleton(JsonObject spec, String sourceStructureId, int targetAreaBlocks) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", "city_bounded_jigsaw_trace.v0.1");
        trace.addProperty("capability", "bounded_jigsaw_supported");
        trace.addProperty("sourceStructureId", sourceStructureId);
        trace.addProperty("startPool", stringValue(spec, "startPool", ""));
        trace.addProperty("targetAreaBlocks", targetAreaBlocks);
        trace.add("acceptedPieces", new JsonArray());
        trace.add("rejectedPieces", new JsonArray());
        trace.add("stoppedBranches", new JsonArray());
        trace.addProperty("fallbackUsed", false);
        trace.add("failureSummary", new JsonObject());
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
        metrics.addProperty("openConnectorCount", state.openBranches.size());
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
    }

    private static void incrementFailure(JsonObject trace, String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return;
        }
        JsonObject summary = trace.getAsJsonObject("failureSummary");
        summary.addProperty(reasonCode, intValue(summary, reasonCode, 0) + 1);
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

    private static String safeId(String raw) {
        return raw == null || raw.isBlank() ? "unknown" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static final class State {
        private final JsonObject trace;
        private final CityConstraintField constraintField;
        private final int targetAreaBlocks;
        private final int maxPieces;
        private final int maxDepth;
        private final String seedKey;
        private final Queue<OpenBranch> openBranches = new ArrayDeque<>();
        private final List<BlockBounds> acceptedFootprints = new ArrayList<>();
        private int acceptedPieces;
        private int visibleAreaCost;
        private BlockBounds estimatedFootprint;

        private State(JsonObject trace, CityConstraintField constraintField, int targetAreaBlocks,
                      int maxPieces, int maxDepth, String seedKey) {
            this.trace = trace;
            this.constraintField = constraintField;
            this.targetAreaBlocks = targetAreaBlocks;
            this.maxPieces = maxPieces;
            this.maxDepth = maxDepth;
            this.seedKey = seedKey;
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
}
