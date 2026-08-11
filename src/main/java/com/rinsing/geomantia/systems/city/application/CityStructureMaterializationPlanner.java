package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** D6/D7 fixed-template planner. Configured StructureStart geometry is intentionally unsupported. */
public final class CityStructureMaterializationPlanner {
    public static final String PLAN_SCHEMA = "city_template_placement_plan.v0.1";
    public static final String LEDGER_SCHEMA = "city_template_placement_ledger.v0.1";
    public static final String TRACE_SCHEMA = "city_template_placement_trace.v0.1";
    public static final String INFERRED_SCHEMA = "city_inferred_function_area_map.v0.1";
    public static final String TEMPLATE_MATERIALIZATION_SOURCE = "structure_template_nbt";
    public static final String TEMPLATE_DATUM_POLICY_GENERATOR_BASE_HEIGHT =
            CityTemplateTerrainPosePolicy.DATUM_POLICY_GENERATOR_BASE_HEIGHT;

    public Result planWorldgen(JsonObject anchorMap, ChunkStatusInspector inspector, JsonObject previousLedger,
                               TemplateMetadataInspector metadataInspector) {
        long started = System.nanoTime();
        JsonArray anchors = requiredArray(anchorMap, "anchors");
        String cityId = requiredString(anchorMap, "cityId");
        if (metadataInspector == null) {
            throw new IllegalArgumentException("D6_TEMPLATE_METADATA_INSPECTOR_REQUIRED");
        }
        ChunkStatusInspector statusInspector = inspector == null ? ChunkStatusInspector.plannedOnly() : inspector;
        List<BlockBounds> occupied = ledgerCollisionBounds(previousLedger);
        JsonArray planned = new JsonArray();
        JsonArray attempts = new JsonArray();
        JsonArray failures = new JsonArray();
        JsonArray waiting = new JsonArray();

        for (JsonElement element : anchors) {
            TemplateTask task = TemplateTask.from(element);
            JsonObject attempt = baseAttempt(task, "template_metadata_check");
            String failure = task.validationFailure();
            if (failure == null) {
                TemplateMetadata metadata = metadataInspector.inspect(task.templateRef());
                if (!metadata.readable()) {
                    failure = metadata.reasonCode();
                    attempt.addProperty("message", metadata.message());
                } else if (!task.templateHash().equals(metadata.templateHash())) {
                    failure = "TEMPLATE_HASH_MISMATCH";
                    attempt.addProperty("message", "D6 catalog hash differs from the current world template NBT.");
                } else if (!task.templateSize().equals(metadata.rawSize())) {
                    failure = "TEMPLATE_RAW_SIZE_MISMATCH";
                    attempt.addProperty("message", "D6 catalog rawSize differs from the current world template NBT.");
                }
            }
            if (failure == null && overlaps(occupied, task.collisionEnvelope())) {
                failure = "LEDGER_OCCUPIED_OVERLAP";
                attempt.addProperty("message", "Template collision overlaps a previous or planned placement.");
            }
            if (failure != null) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", failure);
                failures.add(failure);
                attempts.add(attempt);
                continue;
            }

            ChunkStatusResult status = statusInspector.inspect(task.asStatusTask());
            if (status.failure()) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", status.reasonCode());
                attempt.addProperty("message", status.message());
                failures.add(status.reasonCode());
                attempts.add(attempt);
                continue;
            }
            occupied.add(task.collisionEnvelope());
            JsonObject planItem = task.asPlanJson(status);
            planned.add(planItem);
            attempt.addProperty("status", "planned_worldgen");
            attempt.addProperty("reasonCode", "WAITING_FOR_TEMPLATE_MATERIALIZATION");
            attempt.addProperty("message", "Fixed NBT geometry is locked; worldgen placement is pending.");
            attempt.add("actualFootprint", boundsJson(task.footprint()));
            attempt.add("collisionEnvelope", boundsJson(task.collisionEnvelope()));
            attempt.add("maskEnvelope", boundsJson(task.maskEnvelope()));
            attempts.add(attempt);
        }

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", PLAN_SCHEMA);
        plan.addProperty("cityId", cityId);
        plan.addProperty("materializationSource", TEMPLATE_MATERIALIZATION_SOURCE);
        plan.addProperty("preflightMode", "current_world_template_nbt");
        plan.addProperty("worldgenPlacementMode", true);
        plan.addProperty("locked", failures.isEmpty() && planned.size() == anchors.size() && !planned.isEmpty());
        plan.add("plannedWorldgenStructures", planned);
        plan.add("sourceStructureAnchorMap", anchorMap.deepCopy());
        plan.add("timingMs", timing(started));
        return result(cityId, plan, emptyLedger(cityId), attempts, waiting, failures, planned);
    }

    public Result executeWorldgen(JsonObject plan, JsonObject runtimeLedger, ChunkStatusInspector inspector,
                                  boolean observeRuntimePlacement) {
        long started = System.nanoTime();
        if (plan == null || !PLAN_SCHEMA.equals(stringValue(plan, "schemaVersion", ""))) {
            throw removed();
        }
        String cityId = requiredString(plan, "cityId");
        JsonArray planned = requiredArray(plan, "plannedWorldgenStructures");
        JsonArray runtimePlaced = runtimeLedger != null && runtimeLedger.has("placedStructures")
                && runtimeLedger.get("placedStructures").isJsonArray()
                ? runtimeLedger.getAsJsonArray("placedStructures") : new JsonArray();
        ChunkStatusInspector statusInspector = inspector == null ? ChunkStatusInspector.plannedOnly() : inspector;
        JsonArray placed = new JsonArray();
        JsonArray attempts = new JsonArray();
        JsonArray failures = new JsonArray();
        JsonArray waiting = new JsonArray();

        for (JsonElement element : planned) {
            TemplateTask task = TemplateTask.from(element);
            JsonObject attempt = baseAttempt(task, "template_runtime_ledger_check");
            String validation = task.validationFailure();
            if (validation != null || !task.locked()) {
                String reason = validation == null ? "TEMPLATE_PLAN_NOT_LOCKED" : validation;
                fail(attempt, failures, reason, "D7 requires a valid locked fixed-template D6 plan.");
                attempts.add(attempt);
                continue;
            }
            JsonObject runtimeItem = findByAnchor(runtimePlaced, task.anchorId());
            if (runtimeItem != null) {
                String drift = runtimeDrift(task, runtimeItem);
                if (drift != null) {
                    fail(attempt, failures, drift, "Runtime ledger differs from the locked D6 template plan.");
                    attempts.add(attempt);
                    continue;
                }
                placed.add(task.asLedgerJson(runtimeItem));
                attempt.addProperty("status", "applied");
                attempt.addProperty("reasonCode", "TEMPLATE_MATERIALIZATION_RECORDED");
                attempt.addProperty("message", "Runtime template placement matches the D6 lock.");
                attempts.add(attempt);
                continue;
            }
            ChunkStatusResult status = statusInspector.inspect(task.asStatusTask());
            if (status.failure()) {
                fail(attempt, failures, status.reasonCode(), status.message());
            } else {
                attempt.addProperty("status", "waiting");
                attempt.addProperty("reasonCode", observeRuntimePlacement
                        ? "WAITING_FOR_WORLDGEN" : "WORLDGEN_OBSERVATION_DISABLED");
                attempt.addProperty("message", observeRuntimePlacement
                        ? status.message() : "Runtime placement observation was not requested.");
                waiting.add(attempt.get("reasonCode").getAsString());
            }
            attempts.add(attempt);
        }

        JsonObject ledger = new JsonObject();
        ledger.addProperty("schemaVersion", LEDGER_SCHEMA);
        ledger.addProperty("cityId", cityId);
        ledger.add("placedStructures", placed);
        JsonObject outputPlan = plan.deepCopy();
        outputPlan.add("timingMs", timing(started));
        return result(cityId, outputPlan, ledger, attempts, waiting, failures, placed);
    }

    private static Result result(String cityId, JsonObject plan, JsonObject ledger, JsonArray attempts,
                                 JsonArray waiting, JsonArray failures, JsonArray geometrySource) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", TRACE_SCHEMA);
        trace.addProperty("cityId", cityId);
        trace.add("attempts", attempts);
        trace.add("waiting", waiting);
        trace.add("failures", failures);
        trace.add("waitingSummary", summarize(waiting));
        trace.add("failureSummary", summarize(failures));
        JsonObject inferred = new JsonObject();
        inferred.addProperty("schemaVersion", INFERRED_SCHEMA);
        inferred.addProperty("cityId", cityId);
        JsonArray areas = new JsonArray();
        for (JsonElement element : geometrySource) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            JsonObject footprint = objectValue(item, "actualFootprint");
            if (footprint == null) continue;
            JsonObject area = new JsonObject();
            area.addProperty("anchorId", stringValue(item, "anchorId", ""));
            area.add("footprint", footprint.deepCopy());
            areas.add(area);
        }
        inferred.add("areas", areas);

        JsonObject quality = new JsonObject();
        quality.addProperty("passed", failures.isEmpty());
        quality.addProperty("plannedCount", geometrySource.size());
        quality.addProperty("waitingCount", waiting.size());
        quality.addProperty("failureCount", failures.size());
        quality.add("waitingSummary", summarize(waiting));
        quality.add("failureSummary", summarize(failures));
        return new Result(plan, ledger, trace, inferred, quality);
    }

    private static String runtimeDrift(TemplateTask task, JsonObject runtime) {
        if (!task.templateId().equals(stringValue(runtime, "templateId", ""))) {
            return "STRUCTURE_TEMPLATE_ID_DRIFT";
        }
        if (!task.templateRef().equals(stringValue(runtime, "templateRef", ""))) {
            return "STRUCTURE_TEMPLATE_REF_DRIFT";
        }
        if (!task.templateHash().equals(stringValue(runtime, "templateHash", ""))) {
            return "STRUCTURE_TEMPLATE_HASH_DRIFT";
        }
        if (!task.variantId().equals(stringValue(runtime, "variantId", ""))) {
            return "STRUCTURE_TEMPLATE_VARIANT_DRIFT";
        }
        if (!task.rotation().equals(stringValue(runtime, "rotation", ""))
                || !task.mirror().equals(stringValue(runtime, "mirror", ""))) {
            return "STRUCTURE_TEMPLATE_TRANSFORM_DRIFT";
        }
        if (!task.templateSize().equals(size(objectValue(runtime, "rawSize")))) {
            return "STRUCTURE_TEMPLATE_RAW_SIZE_DRIFT";
        }
        JsonObject actual = objectValue(runtime, "actualFootprint");
        if (actual == null || !task.footprint().equals(bounds(actual))) {
            return "STRUCTURE_TEMPLATE_FOOTPRINT_DRIFT";
        }
        if (!task.collisionEnvelope().equals(optionalBounds(runtime, "collisionEnvelope"))) {
            return "STRUCTURE_TEMPLATE_COLLISION_DRIFT";
        }
        if (!task.maskEnvelope().equals(optionalBounds(runtime, "maskEnvelope"))) {
            return "STRUCTURE_TEMPLATE_MASK_DRIFT";
        }
        if (!ownerChunksEqual(task.footprint(), runtime.get("ownerChunks"))) {
            return "STRUCTURE_TEMPLATE_OWNER_CHUNKS_DRIFT";
        }
        return null;
    }

    private static void fail(JsonObject attempt, JsonArray failures, String reason, String message) {
        attempt.addProperty("status", "failed");
        attempt.addProperty("reasonCode", reason);
        attempt.addProperty("message", message == null ? "" : message);
        failures.add(reason);
    }

    private static JsonObject baseAttempt(TemplateTask task, String mode) {
        JsonObject attempt = new JsonObject();
        attempt.addProperty("anchorId", task.anchorId());
        attempt.addProperty("templateId", task.templateId());
        attempt.addProperty("templateRef", task.templateRef());
        attempt.addProperty("mode", mode);
        return attempt;
    }

    private static JsonObject findByAnchor(JsonArray items, String anchorId) {
        for (JsonElement element : items) {
            if (element.isJsonObject()
                    && anchorId.equals(stringValue(element.getAsJsonObject(), "anchorId", ""))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private static List<BlockBounds> ledgerCollisionBounds(JsonObject ledger) {
        List<BlockBounds> result = new ArrayList<>();
        if (ledger == null || !ledger.has("placedStructures") || !ledger.get("placedStructures").isJsonArray()) {
            return result;
        }
        for (JsonElement element : ledger.getAsJsonArray("placedStructures")) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            JsonObject value = objectValue(item, "collisionEnvelope");
            if (value == null) value = objectValue(item, "actualFootprint");
            if (value != null) result.add(bounds(value));
        }
        return result;
    }

    private static boolean overlaps(List<BlockBounds> existing, BlockBounds candidate) {
        for (BlockBounds bounds : existing) {
            if (bounds.overlaps(candidate)) return true;
        }
        return false;
    }

    private static boolean contains(BlockBounds outer, BlockBounds inner) {
        return outer.minX() <= inner.minX() && outer.minZ() <= inner.minZ()
                && outer.maxX() >= inner.maxX() && outer.maxZ() >= inner.maxZ();
    }

    private static JsonObject emptyLedger(String cityId) {
        JsonObject ledger = new JsonObject();
        ledger.addProperty("schemaVersion", LEDGER_SCHEMA);
        ledger.addProperty("cityId", cityId);
        ledger.add("placedStructures", new JsonArray());
        return ledger;
    }

    private static JsonObject summarize(JsonArray reasons) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonElement element : reasons) counts.merge(element.getAsString(), 1, Integer::sum);
        JsonObject result = new JsonObject();
        counts.forEach(result::addProperty);
        return result;
    }

    private static JsonObject timing(long started) {
        JsonObject timing = new JsonObject();
        timing.addProperty("total", Math.max(0.0, (System.nanoTime() - started) / 1_000_000.0));
        return timing;
    }

    private static JsonArray ownerChunks(BlockBounds footprint) {
        JsonArray result = new JsonArray();
        for (int x = Math.floorDiv(footprint.minX(), 16); x <= Math.floorDiv(footprint.maxX(), 16); x++) {
            for (int z = Math.floorDiv(footprint.minZ(), 16); z <= Math.floorDiv(footprint.maxZ(), 16); z++) {
                JsonObject chunk = new JsonObject();
                chunk.addProperty("x", x);
                chunk.addProperty("z", z);
                result.add(chunk);
            }
        }
        return result;
    }

    private static JsonObject templateJson(TemplateTask task) {
        JsonObject template = new JsonObject();
        template.addProperty("templateId", task.templateId());
        template.addProperty("templateRef", task.templateRef());
        template.addProperty("templateHash", task.templateHash());
        template.addProperty("variantId", task.variantId());
        template.addProperty("rotation", task.rotation());
        template.addProperty("mirror", task.mirror());
        template.add("rawSize", sizeJson(task.templateSize()));
        template.addProperty("terrainPosePolicy", task.terrainPosePolicy());
        template.addProperty("templateDatumPolicy", task.templateDatumPolicy());
        template.addProperty("materializationSource", TEMPLATE_MATERIALIZATION_SOURCE);
        return template;
    }

    private static JsonObject sizeJson(CityTemplatePlacementGeometry.Size size) {
        JsonObject result = new JsonObject();
        result.addProperty("width", size.width());
        result.addProperty("height", size.height());
        result.addProperty("depth", size.depth());
        return result;
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject result = new JsonObject();
        result.addProperty("minX", bounds.minX());
        result.addProperty("minZ", bounds.minZ());
        result.addProperty("maxX", bounds.maxX());
        result.addProperty("maxZ", bounds.maxZ());
        return result;
    }

    private static BlockBounds bounds(JsonObject object) {
        return new BlockBounds(requiredInt(object, "minX"), requiredInt(object, "minZ"),
                requiredInt(object, "maxX"), requiredInt(object, "maxZ"));
    }

    private static JsonObject objectValue(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : null;
    }

    private static CityTemplatePlacementGeometry.Size size(JsonObject object) {
        if (object == null) return null;
        return new CityTemplatePlacementGeometry.Size(requiredInt(object, "width"),
                requiredInt(object, "height"), requiredInt(object, "depth"));
    }

    private static BlockBounds optionalBounds(JsonObject object, String key) {
        JsonObject value = objectValue(object, key);
        return value == null ? null : bounds(value);
    }

    private static boolean ownerChunksEqual(BlockBounds footprint, JsonElement supplied) {
        if (supplied == null || !supplied.isJsonArray()) return false;
        Set<String> expected = new LinkedHashSet<>();
        for (JsonElement element : ownerChunks(footprint)) {
            JsonObject chunk = element.getAsJsonObject();
            expected.add(requiredInt(chunk, "x") + ":" + requiredInt(chunk, "z"));
        }
        Set<String> actual = new LinkedHashSet<>();
        for (JsonElement element : supplied.getAsJsonArray()) {
            if (!element.isJsonObject()) return false;
            JsonObject chunk = element.getAsJsonObject();
            actual.add(requiredInt(chunk, "x") + ":" + requiredInt(chunk, "z"));
        }
        return expected.equals(actual) && actual.size() == supplied.getAsJsonArray().size();
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) throw removed();
        return object.getAsJsonArray(key);
    }

    private static String requiredString(JsonObject object, String key) {
        String value = stringValue(object, key, "");
        if (value.isBlank()) throw removed();
        return value;
    }

    private static int requiredInt(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) throw removed();
        return object.get(key).getAsInt();
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static IllegalArgumentException removed() {
        return new IllegalArgumentException("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED");
    }

    @FunctionalInterface
    public interface TemplateMetadataInspector {
        TemplateMetadata inspect(String templateRef);
    }

    public record TemplateMetadata(boolean readable, String reasonCode, String message, String templateHash,
                                   CityTemplatePlacementGeometry.Size rawSize) {
        public static TemplateMetadata readable(String hash, CityTemplatePlacementGeometry.Size size) {
            return new TemplateMetadata(true, "TEMPLATE_METADATA_CONFIRMED", "", hash, size);
        }

        public static TemplateMetadata unreadable(String reasonCode, String message) {
            return new TemplateMetadata(false, reasonCode, message, "", null);
        }
    }

    public interface ChunkStatusInspector {
        ChunkStatusResult inspect(StructureTask task);

        static ChunkStatusInspector plannedOnly() {
            return task -> ChunkStatusResult.plannedWorldgen("Waiting for the worldgen structure stage.");
        }
    }

    public record ChunkStatusResult(String status, String reasonCode, String message, boolean failure) {
        public static ChunkStatusResult plannedWorldgen(String message) {
            return new ChunkStatusResult("planned_worldgen", "WAITING_FOR_WORLDGEN", message, false);
        }

        public static ChunkStatusResult alreadyGenerated(String message) {
            return new ChunkStatusResult("already_generated", "STRUCTURE_CHUNK_ALREADY_GENERATED", message, true);
        }

        public static ChunkStatusResult invalidAnchor(String message) {
            return new ChunkStatusResult("invalid_anchor", "INVALID_ANCHOR", message, true);
        }

        public static ChunkStatusResult registryMissing(String message) {
            return new ChunkStatusResult("registry_missing", "CITY_WORLDGEN_STRUCTURE_HOOK_UNAVAILABLE", message, true);
        }
    }

    public record StructureTask(String anchorId, BlockPoint anchorBlock) {
    }

    private record TemplateTask(String anchorId, String templateId, String templateRef, String templateHash,
                                String variantId, String rotation, String mirror,
                                CityTemplatePlacementGeometry.Size templateSize, BlockPoint anchorBlock,
                                BlockBounds footprint, BlockBounds collisionEnvelope, BlockBounds maskEnvelope,
                                String terrainPosePolicy, String templateDatumPolicy, int maskMarginBlocks,
                                boolean locked, JsonObject source) {
        static TemplateTask from(JsonElement element) {
            if (element == null || !element.isJsonObject()) throw removed();
            JsonObject source = element.getAsJsonObject();
            for (String forbidden : List.of("structureId", "structureIds", "pieceBoxes", "startSignature",
                    "expectedStartSignature", "lockedBBoxGroupKey", "actualBBoxGroupKey",
                    "selectedEnvelopeGroupKey", "availableEnvelopeGroupKeys")) {
                if (source.has(forbidden)) throw removed();
            }
            String templateId = requiredString(source, "templateId");
            String templateRef = requiredString(source, "templateRef");
            String templateHash = requiredString(source, "templateHash");
            String variantId = stringValue(source, "variantId", stringValue(source, "variant", ""));
            if (variantId.isBlank()) throw removed();
            String rotation = requiredString(source, "rotation");
            String mirror = requiredString(source, "mirror");
            JsonObject size = objectValue(source, "rawSize");
            if (size == null) throw removed();
            CityTemplatePlacementGeometry.Size templateSize = new CityTemplatePlacementGeometry.Size(
                    requiredInt(size, "width"), requiredInt(size, "height"), requiredInt(size, "depth"));
            JsonObject anchor = objectValue(source, "anchorBlock");
            if (anchor == null) anchor = objectValue(source, "commandAnchorBlock");
            if (anchor == null) throw removed();
            BlockPoint anchorBlock = new BlockPoint(requiredInt(anchor, "x"), requiredInt(anchor, "z"));
            BlockBounds footprint;
            try {
                footprint = CityTemplatePlacementGeometry.of(templateSize,
                        CityTemplatePlacementGeometry.Rotation.valueOf(rotation),
                        CityTemplatePlacementGeometry.Mirror.valueOf(mirror), List.of()).worldBounds(anchorBlock);
            } catch (IllegalArgumentException ex) {
                throw removed();
            }
            JsonObject collision = objectValue(source, "collisionEnvelope");
            if (collision == null) collision = objectValue(source, "reservedEnvelope");
            if (collision == null) throw removed();
            BlockBounds collisionEnvelope = bounds(collision);
            int maskMargin = Math.max(0, source.has("maskMarginBlocks")
                    ? source.get("maskMarginBlocks").getAsInt() : CityStructureAnchorPlanner.DEFAULT_MASK_MARGIN_BLOCKS);
            JsonObject mask = objectValue(source, "maskEnvelope");
            BlockBounds maskEnvelope = mask == null ? expand(collisionEnvelope, maskMargin) : bounds(mask);
            String terrain = CityTemplateTerrainPosePolicy.freezeForTemplate(templateId, templateRef,
                    stringValue(source, "terrainPosePolicy", ""));
            return new TemplateTask(requiredString(source, "anchorId"), templateId, templateRef, templateHash,
                    variantId, rotation, mirror, templateSize, anchorBlock, footprint, collisionEnvelope,
                    maskEnvelope, terrain, CityTemplateTerrainPosePolicy.templateDatumPolicy(terrain), maskMargin,
                    source.has("locked") && source.get("locked").getAsBoolean(), source.deepCopy());
        }

        String validationFailure() {
            JsonObject supplied = objectValue(source, "actualFootprint");
            if (supplied == null) supplied = objectValue(source, "plannedFootprint");
            if (supplied != null && !footprint.equals(bounds(supplied))) return "STRUCTURE_TEMPLATE_FOOTPRINT_DRIFT";
            JsonObject lockedBounds = objectValue(source, "lockedActualFootprint");
            if (lockedBounds != null && !footprint.equals(bounds(lockedBounds))) {
                return "STRUCTURE_TEMPLATE_FOOTPRINT_DRIFT";
            }
            if (!contains(collisionEnvelope, footprint)) return "TEMPLATE_COLLISION_EXCLUDES_FOOTPRINT";
            if (!contains(maskEnvelope, collisionEnvelope)) return "TEMPLATE_MASK_EXCLUDES_COLLISION";
            if (source.has("ownerChunks") && !ownerChunksEqual(footprint, source.get("ownerChunks"))) {
                return "STRUCTURE_TEMPLATE_OWNER_CHUNKS_DRIFT";
            }
            String sourceKind = stringValue(source, "materializationSource", TEMPLATE_MATERIALIZATION_SOURCE);
            if (!TEMPLATE_MATERIALIZATION_SOURCE.equals(sourceKind)) return "CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED";
            return null;
        }

        StructureTask asStatusTask() {
            return new StructureTask(anchorId, anchorBlock);
        }

        JsonObject asPlanJson(ChunkStatusResult status) {
            JsonObject result = source.deepCopy();
            result.remove("commandAnchorBlock");
            result.remove("templateFootprint");
            result.add("anchorBlock", anchorBlock.asJson());
            result.add("plannedFootprint", boundsJson(footprint));
            result.add("actualFootprint", boundsJson(footprint));
            result.add("lockedActualFootprint", boundsJson(footprint));
            result.add("reservedEnvelope", boundsJson(collisionEnvelope));
            result.add("collisionEnvelope", boundsJson(collisionEnvelope));
            result.add("lockedCollisionEnvelope", boundsJson(collisionEnvelope));
            result.add("maskEnvelope", boundsJson(maskEnvelope));
            result.add("ownerChunks", ownerChunks(footprint));
            result.addProperty("locked", true);
            result.addProperty("materializationSource", TEMPLATE_MATERIALIZATION_SOURCE);
            result.addProperty("terrainPosePolicy", terrainPosePolicy);
            result.addProperty("templateDatumPolicy", templateDatumPolicy);
            result.addProperty("status", status.status());
            result.addProperty("reasonCode", status.reasonCode());
            result.addProperty("message", status.message());
            result.add("structureTemplate", templateJson(this));
            return result;
        }

        JsonObject asLedgerJson(JsonObject runtime) {
            JsonObject result = asPlanJson(new ChunkStatusResult("applied",
                    "TEMPLATE_MATERIALIZATION_RECORDED", "", false));
            result.remove("status");
            result.remove("reasonCode");
            result.remove("message");
            if (runtime != null) {
                for (String key : List.of("placementChunk", "placedAt", "templateDatumY", "terrainDatumY")) {
                    if (runtime.has(key)) result.add(key, runtime.get(key).deepCopy());
                }
            }
            return result;
        }
    }

    public static BlockBounds expand(BlockBounds bounds, int amount) {
        return new BlockBounds(bounds.minX() - amount, bounds.minZ() - amount,
                bounds.maxX() + amount, bounds.maxZ() + amount);
    }

    public record Result(JsonObject structureMaterializationPlan, JsonObject placedStructureLedger,
                         JsonObject structureMaterializationTrace, JsonObject inferredFunctionAreaMap,
                         JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject result = new JsonObject();
            boolean passed = qualityReport.get("passed").getAsBoolean();
            result.addProperty("ok", passed);
            result.add("structureMaterializationPlan", structureMaterializationPlan);
            result.add("placedStructureLedger", placedStructureLedger);
            result.add("structureMaterializationTrace", structureMaterializationTrace);
            result.add("inferredFunctionAreaMap", inferredFunctionAreaMap);
            result.add("qualityReport", qualityReport);
            JsonObject failureSummary = structureMaterializationTrace.has("failureSummary")
                    && structureMaterializationTrace.get("failureSummary").isJsonObject()
                    ? structureMaterializationTrace.getAsJsonObject("failureSummary") : null;
            if (!passed && failureSummary != null && failureSummary.size() == 1) {
                result.addProperty("reasonCode", failureSummary.keySet().iterator().next());
            } else if (!passed) {
                result.addProperty("reasonCode", "D6_PREFLIGHT_FAILED");
            }
            JsonObject waitingSummary = structureMaterializationTrace.has("waitingSummary")
                    && structureMaterializationTrace.get("waitingSummary").isJsonObject()
                    ? structureMaterializationTrace.getAsJsonObject("waitingSummary") : null;
            if (waitingSummary != null && waitingSummary.has("WAITING_FOR_WORLDGEN")
                    && waitingSummary.get("WAITING_FOR_WORLDGEN").getAsInt() > 0) {
                result.addProperty("status", "waiting_for_worldgen");
                result.addProperty("reasonCode", "WAITING_FOR_WORLDGEN");
            }
            if (structureMaterializationPlan.has("plannedWorldgenStructures")) {
                result.add("plannedWorldgenStructures",
                        structureMaterializationPlan.getAsJsonArray("plannedWorldgenStructures").deepCopy());
            }
            return result;
        }
    }
}
