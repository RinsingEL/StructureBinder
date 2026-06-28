package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CityStructureMaterializationPlanner {
    public static final String PLAN_SCHEMA = "city_structure_materialization_plan.v0.1";
    public static final String LEDGER_SCHEMA = "city_placed_structure_ledger.v0.1";
    public static final String TRACE_SCHEMA = "city_structure_materialization_trace.v0.1";
    public static final String INFERRED_SCHEMA = "city_inferred_function_area_map.v0.1";

    public Result planWorldgen(JsonObject structureAnchorMap, ChunkStatusInspector inspector, JsonObject previousLedger) {
        return planWorldgen(structureAnchorMap, inspector, null, previousLedger);
    }

    public Result planWorldgen(JsonObject structureAnchorMap, ChunkStatusInspector inspector,
                               PlacementBackend preflightBackend, JsonObject previousLedger) {
        long started = System.nanoTime();
        if (structureAnchorMap == null || !structureAnchorMap.has("anchors")) {
            throw new IllegalArgumentException("structure_anchor_map.json is required for D6.");
        }
        String cityId = requiredString(structureAnchorMap, "cityId");
        ChunkStatusInspector statusInspector = inspector == null ? ChunkStatusInspector.plannedOnly() : inspector;
        PlacementBackend backend = preflightBackend == null ? PlacementBackend.traceOnly() : preflightBackend;
        List<BlockBounds> occupied = ledgerBounds(previousLedger);
        JsonArray planned = new JsonArray();
        JsonArray attempts = new JsonArray();
        JsonArray waiting = new JsonArray();
        JsonArray failures = new JsonArray();

        for (JsonElement elem : requiredArray(structureAnchorMap, "anchors")) {
            JsonObject anchor = elem.getAsJsonObject();
            StructureTask task = StructureTask.from(anchor);
            JsonObject attempt = baseAttempt(task, "worldgen_plan_check");
            ChunkStatusResult status = statusInspector.inspect(task);
            attempt.addProperty("status", status.status());
            attempt.addProperty("reasonCode", status.reasonCode());
            attempt.addProperty("message", status.message());
            if (status.failure()) {
                attempts.add(attempt);
                planned.add(task.asWorldgenPlanJson(status));
                failures.add(status.reasonCode());
                continue;
            }
            PlacementResult preflight = backend.plan(task);
            if (preflight.waiting()) {
                attempt.addProperty("preflightStatus", "waiting");
                attempt.addProperty("preflightReasonCode", preflight.reasonCode());
                attempt.addProperty("preflightMessage", preflight.message());
                waiting.add(preflight.reasonCode());
                attempts.add(attempt);
                planned.add(task.asWorldgenPlanJson(status));
                continue;
            }
            if (!preflight.success()) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", preflight.reasonCode());
                attempt.addProperty("message", preflight.message());
                failures.add(preflight.reasonCode());
                attempts.add(attempt);
                planned.add(task.asWorldgenPlanJson(new ChunkStatusResult(
                        "invalid_anchor", preflight.reasonCode(), preflight.message(), true)));
                continue;
            }
            BlockBounds bbox = preflight.actualFootprint() == null
                    ? task.plannedFootprint() : preflight.actualFootprint();
            if (!contains(task.reservedEnvelope(), bbox)) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", "RESERVED_ENVELOPE_EXCEEDED");
                attempt.addProperty("message", "Preflight bbox exceeded D4 collisionEnvelope.");
                attempt.add("actualFootprint", boundsJson(bbox));
                failures.add("RESERVED_ENVELOPE_EXCEEDED");
                attempts.add(attempt);
                planned.add(task.asWorldgenPlanJson(new ChunkStatusResult(
                        "invalid_anchor", "RESERVED_ENVELOPE_EXCEEDED",
                        "Preflight bbox exceeded D4 collisionEnvelope.", true)));
                continue;
            }
            if (overlaps(occupied, bbox)) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", "LEDGER_OCCUPIED_OVERLAP");
                attempt.addProperty("message", "Preflight bbox overlaps previous or planned structure ledger.");
                attempt.add("actualFootprint", boundsJson(bbox));
                failures.add("LEDGER_OCCUPIED_OVERLAP");
                attempts.add(attempt);
                planned.add(task.asWorldgenPlanJson(new ChunkStatusResult(
                        "invalid_anchor", "LEDGER_OCCUPIED_OVERLAP",
                        "Preflight bbox overlaps previous or planned structure ledger.", true)));
                continue;
            }
            occupied.add(bbox);
            attempt.addProperty("preflightStatus", "accepted");
            attempt.add("actualFootprint", boundsJson(bbox));
            attempt.addProperty("startSignature", preflight.startSignature());
            attempt.add("pieceBoxes", preflight.pieceBoxes());
            attempts.add(attempt);
            planned.add(task.asWorldgenPlanJson(status, bbox, preflight.startSignature(), preflight.pieceBoxes()));
        }

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", PLAN_SCHEMA);
        plan.addProperty("cityId", cityId);
        plan.addProperty("dryRunMode", "worldgen_time_planned_registry");
        plan.addProperty("preflightMode", "registry_structure_start_no_world_mutation");
        plan.addProperty("worldgenPlacementMode", true);
        plan.add("plannedWorldgenStructures", planned);
        plan.add("structures", new JsonArray());
        plan.add("sourceStructureAnchorMap", structureAnchorMap.deepCopy());
        plan.add("timingMs", timing(started));
        JsonObject trace = trace(cityId, attempts, waiting, failures, started);
        trace.addProperty("worldgenPlacementMode", true);
        trace.addProperty("lateMaterialization", false);
        JsonObject quality = quality(planned.size(), waiting, failures);
        return new Result(plan, emptyLedger(cityId), trace, inferred(cityId, new JsonArray()), quality);
    }

    public Result executeWorldgen(JsonObject materializationPlan, JsonObject runtimeLedger,
                                  ChunkStatusInspector inspector, boolean executeStructurePlacement) {
        long started = System.nanoTime();
        if (materializationPlan == null || !materializationPlan.has("plannedWorldgenStructures")) {
            throw new IllegalArgumentException("worldgen structure_materialization_plan.json is required for city_execute_d7.");
        }
        String cityId = requiredString(materializationPlan, "cityId");
        ChunkStatusInspector statusInspector = inspector == null ? ChunkStatusInspector.plannedOnly() : inspector;
        JsonArray runtimePlaced = runtimeLedger != null && runtimeLedger.has("placedStructures")
                && runtimeLedger.get("placedStructures").isJsonArray()
                ? runtimeLedger.getAsJsonArray("placedStructures")
                : new JsonArray();
        JsonArray placed = new JsonArray();
        JsonArray attempts = new JsonArray();
        JsonArray waiting = new JsonArray();
        JsonArray failures = new JsonArray();

        for (JsonElement elem : materializationPlan.getAsJsonArray("plannedWorldgenStructures")) {
            JsonObject item = elem.getAsJsonObject();
            StructureTask task = StructureTask.fromPlan(item);
            JsonObject ledgerItem = ledgerItem(runtimePlaced, task.anchorId());
            JsonObject attempt = baseAttempt(task, executeStructurePlacement
                    ? "worldgen_ledger_check" : "worldgen_status_check");
            if (ledgerItem != null) {
                placed.add(ledgerItem.deepCopy());
                attempt.addProperty("status", "applied");
                attempt.addProperty("reasonCode", "WORLDGEN_PLACEMENT_RECORDED");
                attempt.addProperty("message", "Worldgen hook already recorded this planned structure.");
                attempt.addProperty("worldMutationApplied", true);
                attempts.add(attempt);
                continue;
            }
            ChunkStatusResult status = statusInspector.inspect(task);
            attempt.addProperty("status", status.status());
            attempt.addProperty("reasonCode", status.reasonCode());
            attempt.addProperty("message", status.message());
            attempts.add(attempt);
            if (status.failure()) {
                failures.add(status.reasonCode());
            } else {
                waiting.add("WAITING_FOR_WORLDGEN");
            }
        }

        JsonObject ledger = new JsonObject();
        ledger.addProperty("schemaVersion", LEDGER_SCHEMA);
        ledger.addProperty("cityId", cityId);
        ledger.add("placedStructures", placed);
        JsonObject trace = trace(cityId, attempts, waiting, failures, started);
        trace.addProperty("worldgenPlacementMode", true);
        trace.addProperty("lateMaterialization", false);
        trace.addProperty("executeStructurePlacement", executeStructurePlacement);
        JsonObject quality = quality(placed.size(), waiting, failures);
        return new Result(materializationPlan.deepCopy(), ledger, trace, inferred(cityId, placed), quality);
    }

    public Result plan(JsonObject structureAnchorMap, PlacementBackend backend, JsonObject previousLedger) {
        long started = System.nanoTime();
        if (structureAnchorMap == null || !structureAnchorMap.has("anchors")) {
            throw new IllegalArgumentException("structure_anchor_map.json is required for D6.");
        }
        String cityId = requiredString(structureAnchorMap, "cityId");
        List<BlockBounds> occupied = ledgerBounds(previousLedger);
        JsonArray planned = new JsonArray();
        JsonArray attempts = new JsonArray();
        JsonArray waiting = new JsonArray();
        JsonArray failures = new JsonArray();

        for (JsonElement elem : requiredArray(structureAnchorMap, "anchors")) {
            JsonObject anchor = elem.getAsJsonObject();
            StructureTask task = StructureTask.from(anchor);
            JsonObject attempt = baseAttempt(task, "dry_run");
            PlacementResult result = backend.plan(task);
            if (result.waiting()) {
                attempt.addProperty("status", "waiting");
                attempt.addProperty("reasonCode", result.reasonCode());
                attempt.addProperty("message", result.message());
                waiting.add(result.reasonCode());
                attempts.add(attempt);
                continue;
            }
            if (!result.success()) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", result.reasonCode());
                attempt.addProperty("message", result.message());
                failures.add(result.reasonCode());
                attempts.add(attempt);
                continue;
            }
            BlockBounds bbox = result.actualFootprint() == null ? task.plannedFootprint() : result.actualFootprint();
            if (!contains(task.reservedEnvelope(), bbox)) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", "RESERVED_ENVELOPE_EXCEEDED");
                attempt.addProperty("message", "Dry-run bbox exceeded D4 reservedEnvelope.");
                attempt.add("actualFootprint", boundsJson(bbox));
                failures.add("RESERVED_ENVELOPE_EXCEEDED");
                attempts.add(attempt);
                continue;
            }
            if (overlaps(occupied, bbox)) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", "LEDGER_OCCUPIED_OVERLAP");
                attempt.addProperty("message", "Dry-run bbox overlaps previous or planned structure ledger.");
                attempt.add("actualFootprint", boundsJson(bbox));
                failures.add("LEDGER_OCCUPIED_OVERLAP");
                attempts.add(attempt);
                continue;
            }
            occupied.add(bbox);
            attempt.addProperty("status", "planned");
            attempt.addProperty("reasonCode", "REGISTRY_DRY_RUN_ACCEPTED");
            attempt.add("actualFootprint", boundsJson(bbox));
            attempt.addProperty("startSignature", result.startSignature());
            attempt.add("pieceBoxes", result.pieceBoxes());
            attempts.add(attempt);
            planned.add(task.asPlanJson(bbox, result.startSignature(), result.pieceBoxes()));
        }

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", PLAN_SCHEMA);
        plan.addProperty("cityId", cityId);
        plan.addProperty("dryRunMode", "registry_dry_run_with_profile_bbox");
        plan.add("structures", planned);
        plan.add("sourceStructureAnchorMap", structureAnchorMap.deepCopy());
        plan.add("timingMs", timing(started));
        JsonObject trace = trace(cityId, attempts, waiting, failures, started);
        JsonObject quality = quality(planned.size(), waiting, failures);
        return new Result(plan, emptyLedger(cityId), trace, inferred(cityId, new JsonArray()), quality);
    }

    public Result execute(JsonObject materializationPlan, PlacementBackend backend, JsonObject previousLedger,
                          boolean executeWorldMutation) {
        long started = System.nanoTime();
        if (materializationPlan == null || !materializationPlan.has("structures")) {
            throw new IllegalArgumentException("structure_materialization_plan.json is required for city_execute_d7.");
        }
        String cityId = requiredString(materializationPlan, "cityId");
        List<BlockBounds> occupied = ledgerBounds(previousLedger);
        JsonArray placed = previousLedger != null && previousLedger.has("placedStructures")
                && previousLedger.get("placedStructures").isJsonArray()
                ? previousLedger.getAsJsonArray("placedStructures").deepCopy()
                : new JsonArray();
        JsonArray attempts = new JsonArray();
        JsonArray waiting = new JsonArray();
        JsonArray failures = new JsonArray();

        for (JsonElement elem : materializationPlan.getAsJsonArray("structures")) {
            JsonObject item = elem.getAsJsonObject();
            StructureTask task = StructureTask.fromPlan(item);
            String expectedSignature = stringValue(item, "startSignature", "");
            if (ledgerContains(placed, task.anchorId())) {
                attempts.add(skipped(task, "LEDGER_ALREADY_APPLIED", "Structure already exists in ledger."));
                continue;
            }
            JsonObject attempt = baseAttempt(task, executeWorldMutation ? "true_run" : "dry_run_recheck");
            PlacementResult result = executeWorldMutation ? backend.place(task) : backend.plan(task);
            if (result.waiting()) {
                attempt.addProperty("status", "waiting");
                attempt.addProperty("reasonCode", result.reasonCode());
                attempt.addProperty("message", result.message());
                waiting.add(result.reasonCode());
                attempts.add(attempt);
                continue;
            }
            if (!result.success()) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", result.reasonCode());
                attempt.addProperty("message", result.message());
                failures.add(result.reasonCode());
                attempts.add(attempt);
                continue;
            }
            if (!expectedSignature.isBlank() && !expectedSignature.equals(result.startSignature())) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", "START_SIGNATURE_MISMATCH");
                attempt.addProperty("message", "Generated StructureStart differs from selected dry-run plan.");
                attempt.addProperty("expectedStartSignature", expectedSignature);
                attempt.addProperty("actualStartSignature", result.startSignature());
                failures.add("START_SIGNATURE_MISMATCH");
                attempts.add(attempt);
                continue;
            }
            BlockBounds bbox = result.actualFootprint() == null ? task.plannedFootprint() : result.actualFootprint();
            if (!contains(task.reservedEnvelope(), bbox)) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", "RESERVED_ENVELOPE_EXCEEDED");
                failures.add("RESERVED_ENVELOPE_EXCEEDED");
                attempts.add(attempt);
                continue;
            }
            if (overlaps(occupied, bbox)) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", "LEDGER_OCCUPIED_OVERLAP");
                failures.add("LEDGER_OCCUPIED_OVERLAP");
                attempts.add(attempt);
                continue;
            }
            occupied.add(bbox);
            attempt.addProperty("status", executeWorldMutation ? "applied" : "planned");
            attempt.addProperty("reasonCode", executeWorldMutation ? "STRUCTURE_PLACED" : "REGISTRY_DRY_RUN_ACCEPTED");
            attempt.addProperty("worldMutationApplied", result.worldMutationApplied());
            attempt.add("actualFootprint", boundsJson(bbox));
            attempts.add(attempt);
            if (executeWorldMutation) {
                placed.add(task.asPlacedJson(bbox, result));
            }
        }

        JsonObject ledger = new JsonObject();
        ledger.addProperty("schemaVersion", LEDGER_SCHEMA);
        ledger.addProperty("cityId", cityId);
        ledger.add("placedStructures", placed);
        JsonObject trace = trace(cityId, attempts, waiting, failures, started);
        JsonObject quality = quality(placed.size(), waiting, failures);
        return new Result(materializationPlan.deepCopy(), ledger, trace, inferred(cityId, placed), quality);
    }

    private static JsonObject skipped(StructureTask task, String reasonCode, String message) {
        JsonObject attempt = baseAttempt(task, "true_run");
        attempt.addProperty("status", "skipped");
        attempt.addProperty("reasonCode", reasonCode);
        attempt.addProperty("message", message);
        return attempt;
    }

    private static JsonObject baseAttempt(StructureTask task, String mode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("anchorId", task.anchorId());
        obj.addProperty("structureId", task.structureId());
        obj.addProperty("mode", mode);
        obj.add("anchorBlock", task.anchorBlock().asJson());
        obj.add("plannedFootprint", boundsJson(task.plannedFootprint()));
        obj.add("reservedEnvelope", boundsJson(task.reservedEnvelope()));
        return obj;
    }

    private static JsonObject trace(String cityId, JsonArray attempts, JsonArray waiting,
                                    JsonArray failures, long started) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", TRACE_SCHEMA);
        obj.addProperty("cityId", cityId);
        obj.add("attempts", attempts);
        obj.add("waitingSummary", summarize(waiting));
        obj.add("failureSummary", summarize(failures));
        obj.add("timingMs", timing(started));
        return obj;
    }

    private static JsonObject quality(int count, JsonArray waiting, JsonArray failures) {
        JsonObject obj = new JsonObject();
        obj.addProperty("passed", failures.isEmpty());
        obj.addProperty("score", failures.isEmpty() ? 100 : 0);
        JsonObject metrics = new JsonObject();
        metrics.addProperty("structureCount", count);
        metrics.addProperty("waitingCount", waiting.size());
        metrics.addProperty("failureCount", failures.size());
        obj.add("metrics", metrics);
        return obj;
    }

    private static JsonObject inferred(String cityId, JsonArray placed) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", INFERRED_SCHEMA);
        obj.addProperty("cityId", cityId);
        JsonArray areas = new JsonArray();
        Map<String, JsonArray> byPrimaryTerm = new LinkedHashMap<>();
        for (JsonElement elem : placed) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject placedObj = elem.getAsJsonObject();
            String term = "function.unknown";
            if (placedObj.has("functionTerms") && placedObj.get("functionTerms").isJsonArray()
                    && !placedObj.getAsJsonArray("functionTerms").isEmpty()) {
                term = placedObj.getAsJsonArray("functionTerms").get(0).getAsString();
            }
            byPrimaryTerm.computeIfAbsent(term, ignored -> new JsonArray()).add(placedObj.deepCopy());
        }
        byPrimaryTerm.forEach((term, structures) -> {
            JsonObject area = new JsonObject();
            area.addProperty("inferredAreaId", safe(term));
            area.addProperty("primaryFunctionTerm", term);
            area.add("structures", structures);
            area.add("inferredBounds", boundsJson(union(structures)));
            areas.add(area);
        });
        obj.add("areas", areas);
        return obj;
    }

    private static JsonObject emptyLedger(String cityId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", LEDGER_SCHEMA);
        obj.addProperty("cityId", cityId);
        obj.add("placedStructures", new JsonArray());
        return obj;
    }

    private static JsonObject summarize(JsonArray reasons) {
        JsonObject obj = new JsonObject();
        for (JsonElement elem : reasons) {
            String key = elem.getAsString();
            obj.addProperty(key, obj.has(key) ? obj.get(key).getAsInt() + 1 : 1);
        }
        return obj;
    }

    private static List<BlockBounds> ledgerBounds(JsonObject ledger) {
        List<BlockBounds> result = new ArrayList<>();
        if (ledger == null || !ledger.has("placedStructures") || !ledger.get("placedStructures").isJsonArray()) {
            return result;
        }
        for (JsonElement elem : ledger.getAsJsonArray("placedStructures")) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("actualFootprint")) {
                result.add(bounds(elem.getAsJsonObject().getAsJsonObject("actualFootprint")));
            }
        }
        return result;
    }

    private static boolean ledgerContains(JsonArray placed, String anchorId) {
        for (JsonElement elem : placed) {
            if (elem.isJsonObject() && anchorId.equals(stringValue(elem.getAsJsonObject(), "anchorId", ""))) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject ledgerItem(JsonArray placed, String anchorId) {
        for (JsonElement elem : placed) {
            if (elem.isJsonObject() && anchorId.equals(stringValue(elem.getAsJsonObject(), "anchorId", ""))) {
                return elem.getAsJsonObject();
            }
        }
        return null;
    }

    private static boolean overlaps(List<BlockBounds> existing, BlockBounds candidate) {
        for (BlockBounds bounds : existing) {
            if (bounds.overlaps(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(BlockBounds container, BlockBounds child) {
        return container.minX() <= child.minX()
                && container.minZ() <= child.minZ()
                && container.maxX() >= child.maxX()
                && container.maxZ() >= child.maxZ();
    }

    private static BlockBounds union(JsonArray structures) {
        BlockBounds union = null;
        for (JsonElement elem : structures) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("actualFootprint")) {
                continue;
            }
            BlockBounds bounds = bounds(elem.getAsJsonObject().getAsJsonObject("actualFootprint"));
            union = union == null ? bounds : new BlockBounds(
                    Math.min(union.minX(), bounds.minX()),
                    Math.min(union.minZ(), bounds.minZ()),
                    Math.max(union.maxX(), bounds.maxX()),
                    Math.max(union.maxZ(), bounds.maxZ()));
        }
        return union == null ? new BlockBounds(0, 0, 0, 0) : union;
    }

    private static JsonObject timing(long started) {
        JsonObject timing = new JsonObject();
        timing.addProperty("total", (System.nanoTime() - started) / 1_000_000L);
        return timing;
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

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required.");
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonObject requiredObject(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required.");
        }
        return obj.getAsJsonObject(key);
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
    }

    private static String safe(String raw) {
        return raw == null ? "unknown" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public interface PlacementBackend {
        PlacementResult plan(StructureTask task);

        PlacementResult place(StructureTask task);

        static PlacementBackend traceOnly() {
            return new PlacementBackend() {
                @Override
                public PlacementResult plan(StructureTask task) {
                    return PlacementResult.success(false, "trace-only dry-run", task.plannedFootprint(),
                            "trace_only:" + task.structureId() + ":" + task.anchorId(), new JsonArray());
                }

                @Override
                public PlacementResult place(StructureTask task) {
                    return PlacementResult.success(false, "trace-only true-run", task.plannedFootprint(),
                            "trace_only:" + task.structureId() + ":" + task.anchorId(), new JsonArray());
                }
            };
        }
    }

    public interface ChunkStatusInspector {
        ChunkStatusResult inspect(StructureTask task);

        static ChunkStatusInspector plannedOnly() {
            return task -> ChunkStatusResult.plannedWorldgen("Chunk has not passed structure generation in trace-only mode.");
        }
    }

    public record ChunkStatusResult(String status, String reasonCode, String message, boolean failure) {
        public static ChunkStatusResult plannedWorldgen(String message) {
            return new ChunkStatusResult("planned_worldgen", "WAITING_FOR_WORLDGEN",
                    message == null ? "" : message, false);
        }

        public static ChunkStatusResult alreadyGenerated(String message) {
            return new ChunkStatusResult("already_generated", "STRUCTURE_CHUNK_ALREADY_GENERATED",
                    message == null ? "" : message, true);
        }

        public static ChunkStatusResult invalidAnchor(String message) {
            return new ChunkStatusResult("invalid_anchor", "INVALID_ANCHOR",
                    message == null ? "" : message, true);
        }

        public static ChunkStatusResult registryMissing(String message) {
            return new ChunkStatusResult("registry_missing", "CITY_WORLDGEN_STRUCTURE_HOOK_UNAVAILABLE",
                    message == null ? "" : message, true);
        }
    }

    public record PlacementResult(boolean success, boolean waiting, boolean worldMutationApplied,
                                  String reasonCode, String message, BlockBounds actualFootprint,
                                  String startSignature, JsonArray pieceBoxes) {
        public static PlacementResult success(boolean applied, String message, BlockBounds actualFootprint,
                                              String startSignature, JsonArray pieceBoxes) {
            return new PlacementResult(true, false, applied, "", message, actualFootprint,
                    startSignature == null ? "" : startSignature,
                    pieceBoxes == null ? new JsonArray() : pieceBoxes);
        }

        public static PlacementResult failed(String reasonCode, String message) {
            return new PlacementResult(false, false, false, reasonCode, message, null, "", new JsonArray());
        }

        public static PlacementResult waiting(String reasonCode, String message) {
            return new PlacementResult(false, true, false, reasonCode, message, null, "", new JsonArray());
        }
    }

    public record StructureTask(String anchorId, String structureId, BlockPoint anchorBlock, String rotation,
                                BlockBounds plannedFootprint, BlockBounds reservedEnvelope,
                                List<String> semanticTerms, List<String> functionTerms,
                                String expectedStartSignature, JsonObject sourceAnchor) {
        static StructureTask from(JsonObject anchor) {
            return new StructureTask(
                    requiredString(anchor, "anchorId"),
                    requiredString(anchor, "structureId"),
                    blockPoint(requiredObject(anchor, "commandAnchorBlock")),
                    stringValue(anchor, "rotation", "NONE"),
                    bounds(requiredObject(anchor, "plannedFootprint")),
                    bounds(requiredObject(anchor, "reservedEnvelope")),
                    strings(anchor.getAsJsonArray("semanticTerms")),
                    strings(anchor.getAsJsonArray("functionTerms")),
                    "",
                    anchor.deepCopy());
        }

        static StructureTask fromPlan(JsonObject item) {
            return new StructureTask(
                    requiredString(item, "anchorId"),
                    requiredString(item, "structureId"),
                    blockPoint(requiredObject(item, "commandAnchorBlock")),
                    stringValue(item, "rotation", "NONE"),
                    bounds(requiredObject(item, "plannedFootprint")),
                    bounds(requiredObject(item, "reservedEnvelope")),
                    strings(item.getAsJsonArray("semanticTerms")),
                    strings(item.getAsJsonArray("functionTerms")),
                    !stringValue(item, "expectedStartSignature", "").isBlank()
                            ? stringValue(item, "expectedStartSignature", "")
                            : stringValue(item, "startSignature", ""),
                    item.deepCopy());
        }

        JsonObject asPlanJson(BlockBounds actualFootprint, String startSignature, JsonArray pieceBoxes) {
            JsonObject obj = sourceAnchor.deepCopy();
            obj.add("actualFootprint", boundsJson(actualFootprint));
            obj.addProperty("startSignature", startSignature == null ? "" : startSignature);
            obj.add("pieceBoxes", pieceBoxes == null ? new JsonArray() : pieceBoxes);
            return obj;
        }

        JsonObject asWorldgenPlanJson(ChunkStatusResult status) {
            return asWorldgenPlanJson(status, null, expectedStartSignature, new JsonArray());
        }

        JsonObject asWorldgenPlanJson(ChunkStatusResult status, BlockBounds actualFootprint,
                                      String startSignature, JsonArray pieceBoxes) {
            JsonObject obj = sourceAnchor.deepCopy();
            if (!obj.has("commandAnchorBlock")) {
                obj.add("commandAnchorBlock", anchorBlock.asJson());
            }
            JsonObject chunk = new JsonObject();
            chunk.addProperty("x", Math.floorDiv(anchorBlock.x(), 16));
            chunk.addProperty("z", Math.floorDiv(anchorBlock.z(), 16));
            obj.add("anchorChunk", chunk);
            obj.add("requiredChunkRange", chunkRangeJson(reservedEnvelope));
            obj.addProperty("expectedStartSignature", startSignature == null ? "" : startSignature);
            if (actualFootprint != null) {
                obj.add("actualFootprint", boundsJson(actualFootprint));
            }
            obj.add("pieceBoxes", pieceBoxes == null ? new JsonArray() : pieceBoxes);
            obj.addProperty("worldgenPlacementMode", true);
            obj.addProperty("status", status.status());
            obj.addProperty("reasonCode", status.reasonCode());
            obj.addProperty("message", status.message());
            return obj;
        }

        JsonObject asPlacedJson(BlockBounds actualFootprint, PlacementResult result) {
            JsonObject obj = new JsonObject();
            obj.addProperty("anchorId", anchorId);
            obj.addProperty("structureId", structureId);
            obj.add("anchorBlock", anchorBlock.asJson());
            obj.addProperty("rotation", rotation);
            obj.add("plannedFootprint", boundsJson(plannedFootprint));
            obj.add("actualFootprint", boundsJson(actualFootprint));
            obj.add("reservedEnvelope", boundsJson(reservedEnvelope));
            obj.add("semanticTerms", stringArray(semanticTerms));
            obj.add("functionTerms", stringArray(functionTerms));
            obj.addProperty("worldMutationApplied", result.worldMutationApplied());
            obj.addProperty("startSignature", result.startSignature());
            obj.add("pieceBoxes", result.pieceBoxes());
            return obj;
        }

        private static List<String> strings(JsonArray array) {
            List<String> result = new ArrayList<>();
            if (array != null) {
                for (JsonElement elem : array) {
                    if (!elem.isJsonNull()) {
                        result.add(elem.getAsString());
                    }
                }
            }
            return List.copyOf(result);
        }

        private static JsonArray stringArray(List<String> values) {
            JsonArray array = new JsonArray();
            values.forEach(array::add);
            return array;
        }

        private static BlockPoint blockPoint(JsonObject obj) {
            return new BlockPoint(intValue(obj, "x", 0), intValue(obj, "z", 0));
        }
    }

    private static JsonObject chunkRangeJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minChunkX", Math.floorDiv(bounds.minX(), 16));
        obj.addProperty("minChunkZ", Math.floorDiv(bounds.minZ(), 16));
        obj.addProperty("maxChunkX", Math.floorDiv(bounds.maxX(), 16));
        obj.addProperty("maxChunkZ", Math.floorDiv(bounds.maxZ(), 16));
        return obj;
    }

    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>();
        if (array != null) {
            for (JsonElement elem : array) {
                if (!elem.isJsonNull()) {
                    result.add(elem.getAsString());
                }
            }
        }
        return List.copyOf(result);
    }

    public record Result(JsonObject structureMaterializationPlan, JsonObject placedStructureLedger,
                         JsonObject structureMaterializationTrace, JsonObject inferredFunctionAreaMap,
                         JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.add("structureMaterializationPlan", structureMaterializationPlan);
            if (structureMaterializationPlan.has("plannedWorldgenStructures")) {
                obj.add("plannedWorldgenStructures",
                        structureMaterializationPlan.getAsJsonArray("plannedWorldgenStructures").deepCopy());
                addTopLevelStatus(obj, structureMaterializationPlan.getAsJsonArray("plannedWorldgenStructures"),
                        structureMaterializationTrace.getAsJsonArray("attempts"));
            }
            obj.add("placedStructureLedger", placedStructureLedger);
            obj.add("structureMaterializationTrace", structureMaterializationTrace);
            obj.add("inferredFunctionAreaMap", inferredFunctionAreaMap);
            obj.add("qualityReport", qualityReport);
            obj.add("timingMs", structureMaterializationTrace.getAsJsonObject("timingMs"));
            return obj;
        }

        private static void addTopLevelStatus(JsonObject obj, JsonArray planned, JsonArray attempts) {
            String status = "";
            String reason = "";
            JsonArray source = attempts != null && !attempts.isEmpty() ? attempts : planned;
            for (JsonElement elem : source) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject item = elem.getAsJsonObject();
                String itemStatus = stringValue(item, "status", "");
                String itemReason = stringValue(item, "reasonCode", "");
                if (status.isBlank()) {
                    status = itemStatus;
                    reason = itemReason;
                }
                if ("already_generated".equals(itemStatus)
                        || "invalid_anchor".equals(itemStatus)
                        || "registry_missing".equals(itemStatus)) {
                    status = itemStatus;
                    reason = itemReason;
                    break;
                }
            }
            obj.addProperty("status", status.isBlank() ? "planned_worldgen" : status);
            obj.addProperty("reasonCode", reason.isBlank() ? "WAITING_FOR_WORLDGEN" : reason);
        }
    }
}
