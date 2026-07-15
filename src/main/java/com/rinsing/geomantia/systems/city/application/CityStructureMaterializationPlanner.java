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
    public static final String TEMPLATE_MATERIALIZATION_SOURCE = "structure_template_nbt";
    public static final String TEMPLATE_DATUM_POLICY_WORLDGEN_SURFACE =
            "worldgen_surface_motion_blocking_no_leaves";
    public static final int DEFAULT_COLLISION_CLEARANCE_BLOCKS = 4;

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
        List<BlockBounds> occupied = expandedLedgerBounds(previousLedger, DEFAULT_COLLISION_CLEARANCE_BLOCKS);
        JsonArray planned = new JsonArray();
        JsonArray attempts = new JsonArray();
        JsonArray waiting = new JsonArray();
        JsonArray failures = new JsonArray();
        int lockedCount = 0;
        boolean hasTemplatePlan = false;

        for (JsonElement elem : requiredArray(structureAnchorMap, "anchors")) {
            JsonObject anchor = elem.getAsJsonObject();
            StructureTask task = StructureTask.from(anchor);
            JsonObject attempt = baseAttempt(task, "worldgen_plan_check");
            if (task.templateSemantic()) {
                hasTemplatePlan = true;
                TemplateValidation validation = task.validateTemplate(false);
                if (!validation.valid()) {
                    addTemplateFailure(attempt, validation);
                    failures.add(validation.reasonCode());
                    attempts.add(attempt);
                    planned.add(task.asWorldgenPlanJson(templateFailureStatus(validation)));
                    continue;
                }
                BlockBounds bbox = task.templateActualFootprint();
                if (overlaps(occupied, bbox)) {
                    addFailure(attempt, "LEDGER_OCCUPIED_OVERLAP",
                            "Template footprint overlaps previous or planned structure ledger.");
                    attempt.add("actualFootprint", boundsJson(bbox));
                    failures.add("LEDGER_OCCUPIED_OVERLAP");
                    attempts.add(attempt);
                    planned.add(task.asWorldgenPlanJson(new ChunkStatusResult(
                            "invalid_anchor", "LEDGER_OCCUPIED_OVERLAP",
                            "Template footprint overlaps previous or planned structure ledger.", true)));
                    continue;
                }
                occupied.add(bbox);
                lockedCount++;
                attempt.addProperty("status", "planned_worldgen");
                attempt.addProperty("reasonCode", "WAITING_FOR_TEMPLATE_MATERIALIZATION");
                attempt.addProperty("message", "Structure template footprint is locked without StructureStart preflight.");
                attempt.addProperty("preflightStatus", "accepted");
                attempt.addProperty("locked", true);
                attempt.add("actualFootprint", boundsJson(bbox));
                attempt.add("lockedActualFootprint", boundsJson(bbox));
                attempt.add("lockedCollisionEnvelope", boundsJson(bbox));
                attempts.add(attempt);
                planned.add(task.asWorldgenPlanJson(
                        ChunkStatusResult.plannedWorldgen("Template materialization is pending."),
                        bbox, bbox, "", "", new JsonArray()));
                continue;
            }
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
            BlockBounds lockedCollision = expand(bbox, DEFAULT_COLLISION_CLEARANCE_BLOCKS);
            String lockedGroupKey = actualBBoxGroupKey(task, bbox, preflight.pieceBoxes());
            if (!task.acceptsBBoxGroupKey(lockedGroupKey)) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", "BBOX_GROUP_NOT_IN_FACTS");
                attempt.addProperty("message", "Locked preflight bbox group is not present in structure envelope facts.");
                attempt.add("actualFootprint", boundsJson(bbox));
                attempt.add("actualLocalBounds", boundsJson(localBounds(task, bbox)));
                attempt.addProperty("actualBBoxGroupKey", lockedGroupKey);
                attempt.add("availableEnvelopeGroupKeys", stringArray(task.availableEnvelopeGroupKeys()));
                failures.add("BBOX_GROUP_NOT_IN_FACTS");
                attempts.add(attempt);
                planned.add(task.asWorldgenPlanJson(new ChunkStatusResult(
                        "invalid_anchor", "BBOX_GROUP_NOT_IN_FACTS",
                        "Locked preflight bbox group is not present in structure envelope facts.", true)));
                continue;
            }
            if (overlaps(occupied, lockedCollision)) {
                attempt.addProperty("status", "failed");
                attempt.addProperty("reasonCode", "LEDGER_OCCUPIED_OVERLAP");
                attempt.addProperty("message", "Locked preflight collision envelope overlaps previous or planned structure ledger.");
                attempt.add("actualFootprint", boundsJson(bbox));
                attempt.add("lockedActualFootprint", boundsJson(bbox));
                attempt.add("lockedCollisionEnvelope", boundsJson(lockedCollision));
                failures.add("LEDGER_OCCUPIED_OVERLAP");
                attempts.add(attempt);
                planned.add(task.asWorldgenPlanJson(new ChunkStatusResult(
                        "invalid_anchor", "LEDGER_OCCUPIED_OVERLAP",
                        "Locked preflight collision envelope overlaps previous or planned structure ledger.", true)));
                continue;
            }
            occupied.add(lockedCollision);
            lockedCount++;
            attempt.addProperty("preflightStatus", "accepted");
            attempt.add("actualFootprint", boundsJson(bbox));
            attempt.addProperty("locked", true);
            attempt.add("lockedActualFootprint", boundsJson(bbox));
            attempt.add("lockedCollisionEnvelope", boundsJson(lockedCollision));
            attempt.add("actualLocalBounds", boundsJson(localBounds(task, bbox)));
            attempt.addProperty("actualBBoxGroupKey", lockedGroupKey);
            attempt.addProperty("lockedBBoxGroupKey", lockedGroupKey);
            attempt.add("availableEnvelopeGroupKeys", stringArray(task.availableEnvelopeGroupKeys()));
            attempt.addProperty("startSignature", preflight.startSignature());
            attempt.add("pieceBoxes", preflight.pieceBoxes());
            attempts.add(attempt);
            planned.add(task.asWorldgenPlanJson(status, bbox, lockedCollision, lockedGroupKey,
                    preflight.startSignature(), preflight.pieceBoxes()));
        }

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", PLAN_SCHEMA);
        plan.addProperty("cityId", cityId);
        plan.addProperty("dryRunMode", "worldgen_time_planned_registry");
        plan.addProperty("preflightMode", "registry_structure_start_no_world_mutation");
        plan.addProperty("worldgenPlacementMode", true);
        if (hasTemplatePlan) {
            plan.addProperty("materializationSource", TEMPLATE_MATERIALIZATION_SOURCE);
            plan.addProperty("preflightMode", "structure_template_nbt_no_registry");
        }
        plan.addProperty("locked", failures.isEmpty() && waiting.isEmpty()
                && lockedCount == planned.size() && lockedCount > 0);
        plan.addProperty("collisionClearanceBlocks", DEFAULT_COLLISION_CLEARANCE_BLOCKS);
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
            if (task.templateSemantic()) {
                TemplateValidation validation = task.validateTemplate(true);
                if (!validation.valid()) {
                    addTemplateFailure(attempt, validation);
                    failures.add(validation.reasonCode());
                    attempts.add(attempt);
                    continue;
                }
                if (ledgerItem != null) {
                    String driftReason = templateLedgerDriftReason(task, ledgerItem);
                    if (driftReason != null) {
                        addFailure(attempt, driftReason, "Runtime template ledger differs from the locked D6 template plan.");
                        failures.add(driftReason);
                        attempts.add(attempt);
                        continue;
                    }
                    placed.add(task.asTemplateLedgerJson(ledgerItem));
                    attempt.addProperty("status", "applied");
                    attempt.addProperty("reasonCode", "TEMPLATE_MATERIALIZATION_RECORDED");
                    attempt.addProperty("message", "Template materialization ledger matches the locked D6 plan.");
                    attempt.addProperty("worldMutationApplied", stringValue(ledgerItem, "worldMutationApplied", "true"));
                    attempts.add(attempt);
                } else {
                    attempt.addProperty("status", "planned_worldgen");
                    attempt.addProperty("reasonCode", "WAITING_FOR_TEMPLATE_MATERIALIZATION");
                    attempt.addProperty("message", "No template materialization ledger entry exists yet.");
                    waiting.add("WAITING_FOR_TEMPLATE_MATERIALIZATION");
                    attempts.add(attempt);
                }
                continue;
            }
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
        List<BlockBounds> occupied = expandedLedgerBounds(previousLedger, 0);
        JsonArray planned = new JsonArray();
        JsonArray attempts = new JsonArray();
        JsonArray waiting = new JsonArray();
        JsonArray failures = new JsonArray();
        boolean hasTemplatePlan = false;

        for (JsonElement elem : requiredArray(structureAnchorMap, "anchors")) {
            JsonObject anchor = elem.getAsJsonObject();
            StructureTask task = StructureTask.from(anchor);
            JsonObject attempt = baseAttempt(task, "dry_run");
            if (task.templateSemantic()) {
                hasTemplatePlan = true;
                TemplateValidation validation = task.validateTemplate(false);
                if (!validation.valid()) {
                    addTemplateFailure(attempt, validation);
                    failures.add(validation.reasonCode());
                    attempts.add(attempt);
                    continue;
                }
                BlockBounds bbox = task.templateActualFootprint();
                if (overlaps(occupied, bbox)) {
                    addFailure(attempt, "LEDGER_OCCUPIED_OVERLAP",
                            "Template footprint overlaps previous or planned structure ledger.");
                    failures.add("LEDGER_OCCUPIED_OVERLAP");
                    attempts.add(attempt);
                    continue;
                }
                occupied.add(bbox);
                attempt.addProperty("status", "planned");
                attempt.addProperty("reasonCode", "STRUCTURE_TEMPLATE_ACCEPTED");
                attempt.add("actualFootprint", boundsJson(bbox));
                attempt.add("lockedActualFootprint", boundsJson(bbox));
                attempts.add(attempt);
                planned.add(task.asPlanJson(bbox, "", new JsonArray()));
                continue;
            }
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
                attempt.addProperty("message",
                        "Legacy debug late-materialize dry-run bbox exceeded planned reservedEnvelope.");
                attempt.add("actualFootprint", boundsJson(bbox));
                attempt.add("actualLocalBounds", boundsJson(localBounds(task, bbox)));
                attempt.addProperty("actualBBoxGroupKey", actualBBoxGroupKey(task, bbox, result.pieceBoxes()));
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
            attempt.add("actualLocalBounds", boundsJson(localBounds(task, bbox)));
            attempt.addProperty("actualBBoxGroupKey", actualBBoxGroupKey(task, bbox, result.pieceBoxes()));
            attempt.addProperty("startSignature", result.startSignature());
            attempt.add("pieceBoxes", result.pieceBoxes());
            attempts.add(attempt);
            planned.add(task.asPlanJson(bbox, result.startSignature(), result.pieceBoxes()));
        }

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", PLAN_SCHEMA);
        plan.addProperty("cityId", cityId);
        plan.addProperty("dryRunMode", "registry_dry_run_with_profile_bbox");
        if (hasTemplatePlan) {
            plan.addProperty("materializationSource", TEMPLATE_MATERIALIZATION_SOURCE);
            plan.addProperty("dryRunMode", "structure_template_nbt");
        }
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
            if (task.templateSemantic()) {
                TemplateValidation validation = task.validateTemplate(true);
                JsonObject attempt = baseAttempt(task, executeWorldMutation ? "true_run" : "dry_run_recheck");
                if (!validation.valid()) {
                    addTemplateFailure(attempt, validation);
                    failures.add(validation.reasonCode());
                    attempts.add(attempt);
                    continue;
                }
                JsonObject existing = ledgerItem(placed, task.anchorId());
                if (existing != null) {
                    String driftReason = templateLedgerDriftReason(task, existing);
                    if (driftReason != null) {
                        addFailure(attempt, driftReason, "Runtime template ledger differs from the locked D6 template plan.");
                        failures.add(driftReason);
                        attempts.add(attempt);
                    } else {
                        attempts.add(skipped(task, "LEDGER_ALREADY_APPLIED",
                                "Template structure already exists in ledger."));
                    }
                    continue;
                }
                attempt.addProperty("status", "waiting");
                attempt.addProperty("reasonCode", "WAITING_FOR_TEMPLATE_MATERIALIZATION");
                attempt.addProperty("message", "Template materialization is owned by the template execution adapter.");
                waiting.add("WAITING_FOR_TEMPLATE_MATERIALIZATION");
                attempts.add(attempt);
                continue;
            }
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
                attempt.addProperty("message", "Generated StructureStart differs from selected locked plan.");
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
                attempt.addProperty("message",
                        "Legacy debug late-materialize bbox exceeded planned reservedEnvelope.");
                attempt.add("actualFootprint", boundsJson(bbox));
                attempt.add("actualLocalBounds", boundsJson(localBounds(task, bbox)));
                attempt.addProperty("actualBBoxGroupKey", actualBBoxGroupKey(task, bbox, result.pieceBoxes()));
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
            attempt.add("actualLocalBounds", boundsJson(localBounds(task, bbox)));
            attempt.addProperty("actualBBoxGroupKey", actualBBoxGroupKey(task, bbox, result.pieceBoxes()));
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
        obj.add("collisionEnvelope", boundsJson(task.collisionEnvelope()));
        obj.add("maskEnvelope", boundsJson(task.maskEnvelope()));
        obj.addProperty("envelopeMode", task.envelopeMode());
        obj.addProperty("selectedEnvelopeGroupKey", task.selectedEnvelopeGroupKey());
        return obj;
    }

    private static void addFailure(JsonObject attempt, String reasonCode, String message) {
        attempt.addProperty("status", "failed");
        attempt.addProperty("reasonCode", reasonCode);
        attempt.addProperty("message", message);
    }

    private static void addTemplateFailure(JsonObject attempt, TemplateValidation validation) {
        addFailure(attempt, validation.reasonCode(), validation.message());
        JsonArray errors = new JsonArray();
        validation.errors().forEach(errors::add);
        attempt.add("templateValidationErrors", errors);
        attempt.addProperty("materializationSource", TEMPLATE_MATERIALIZATION_SOURCE);
    }

    private static ChunkStatusResult templateFailureStatus(TemplateValidation validation) {
        return new ChunkStatusResult("invalid_anchor", validation.reasonCode(), validation.message(), true);
    }

    private static String templateLedgerDriftReason(StructureTask task, JsonObject ledgerItem) {
        TemplateFacts expected = task.templateFacts();
        TemplateFacts actual = TemplateFacts.from(ledgerItem, task.anchorBlock());
        JsonObject nested = ledgerItem.has("structureTemplate")
                && ledgerItem.get("structureTemplate").isJsonObject()
                ? ledgerItem.getAsJsonObject("structureTemplate") : null;
        if (hasValue(ledgerItem, "templateHash")
                && !expected.templateHash().equals(stringValue(ledgerItem, "templateHash", ""))) {
            return "STRUCTURE_TEMPLATE_HASH_DRIFT";
        }
        if (hasValue(nested, "templateHash")
                && !expected.templateHash().equals(stringValue(nested, "templateHash", ""))) {
            return "STRUCTURE_TEMPLATE_HASH_DRIFT";
        }
        if (hasValue(ledgerItem, "templateId") && !expected.templateId().equals(actual.templateId())) {
            return "STRUCTURE_TEMPLATE_FIELD_DRIFT";
        }
        if (hasValue(ledgerItem, "templateRef") && !expected.templateRef().equals(actual.templateRef())) {
            return "STRUCTURE_TEMPLATE_FIELD_DRIFT";
        }
        if (hasValue(ledgerItem, "variantId") && !expected.variantId().equals(actual.variantId())) {
            return "STRUCTURE_TEMPLATE_FIELD_DRIFT";
        }
        if (hasValue(ledgerItem, "rotation") && !expected.rotation().equals(actual.rotation())) {
            return "STRUCTURE_TEMPLATE_FIELD_DRIFT";
        }
        if (hasValue(ledgerItem, "mirror") && !expected.mirror().equals(actual.mirror())) {
            return "STRUCTURE_TEMPLATE_FIELD_DRIFT";
        }
        BlockBounds actualFootprint = optionalBoundsValue(ledgerItem, "actualFootprint");
        if (actualFootprint != null && !expected.actualFootprint().equals(actualFootprint)) {
            return "STRUCTURE_TEMPLATE_FOOTPRINT_DRIFT";
        }
        BlockBounds lockedFootprint = actual.lockedActualFootprint();
        if (lockedFootprint != null && !expected.actualFootprint().equals(lockedFootprint)) {
            return "STRUCTURE_TEMPLATE_FOOTPRINT_DRIFT";
        }
        return null;
    }

    private static BlockBounds optionalBoundsValue(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? bounds(object.getAsJsonObject(key)) : null;
    }

    private static boolean hasValue(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                && (!object.get(key).isJsonPrimitive() || !object.get(key).getAsString().isBlank());
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

    private static List<BlockBounds> expandedLedgerBounds(JsonObject ledger, int amount) {
        List<BlockBounds> result = new ArrayList<>();
        for (BlockBounds bounds : ledgerBounds(ledger)) {
            result.add(expand(bounds, amount));
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

    public static BlockBounds expand(BlockBounds bounds, int amount) {
        int margin = Math.max(0, amount);
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static BlockBounds localBounds(StructureTask task, BlockBounds worldBounds) {
        int originX = Math.floorDiv(task.anchorBlock().x(), 16) * 16;
        int originZ = Math.floorDiv(task.anchorBlock().z(), 16) * 16;
        return new BlockBounds(
                worldBounds.minX() - originX,
                worldBounds.minZ() - originZ,
                worldBounds.maxX() - originX,
                worldBounds.maxZ() - originZ);
    }

    private static String actualBBoxGroupKey(StructureTask task, BlockBounds worldBounds, JsonArray pieceBoxes) {
        int pieceCount = pieceBoxes == null ? 0 : pieceBoxes.size();
        return CityStructureEnvelopeProfiler.bboxGroupKey(localBounds(task, worldBounds), pieceCount);
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

    private static JsonObject sizeJson(CityTemplatePlacementGeometry.Size size) {
        JsonObject obj = new JsonObject();
        obj.addProperty("width", size.width());
        obj.addProperty("height", size.height());
        obj.addProperty("depth", size.depth());
        return obj;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
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
                                BlockBounds collisionEnvelope, BlockBounds maskEnvelope,
                                String envelopeMode, String selectedEnvelopeGroupKey,
                                List<String> availableEnvelopeGroupKeys,
                                List<String> semanticTerms, List<String> functionTerms,
                                String expectedStartSignature, int maskMarginBlocks, JsonObject sourceAnchor) {
        static StructureTask from(JsonObject anchor) {
            BlockBounds reserved = bounds(requiredObject(anchor, "reservedEnvelope"));
            return new StructureTask(
                    requiredString(anchor, "anchorId"),
                    requiredString(anchor, "structureId"),
                    blockPoint(requiredObject(anchor, "commandAnchorBlock")),
                    stringValue(anchor, "rotation", "NONE"),
                    bounds(requiredObject(anchor, "plannedFootprint")),
                    reserved,
                    optionalBounds(anchor, "collisionEnvelope", reserved),
                    optionalBounds(anchor, "maskEnvelope", reserved),
                    stringValue(anchor, "envelopeMode", ""),
                    stringValue(anchor, "selectedEnvelopeGroupKey", ""),
                    strings(anchor.getAsJsonArray("availableEnvelopeGroupKeys")),
                    strings(anchor.getAsJsonArray("semanticTerms")),
                    strings(anchor.getAsJsonArray("functionTerms")),
                    "",
                    maskMarginBlocks(anchor),
                    anchor.deepCopy());
        }

        boolean templateSemantic() {
            return templateFacts().semantic();
        }

        TemplateFacts templateFacts() {
            return TemplateFacts.from(sourceAnchor, anchorBlock);
        }

        BlockBounds templateActualFootprint() {
            return templateFacts().actualFootprint();
        }

        TemplateValidation validateTemplate(boolean requireLockedFootprint) {
            return templateFacts().validate(requireLockedFootprint);
        }

        static StructureTask fromPlan(JsonObject item) {
            BlockBounds reserved = bounds(requiredObject(item, "reservedEnvelope"));
            return new StructureTask(
                    requiredString(item, "anchorId"),
                    requiredString(item, "structureId"),
                    blockPoint(requiredObject(item, "commandAnchorBlock")),
                    stringValue(item, "rotation", "NONE"),
                    bounds(requiredObject(item, "plannedFootprint")),
                    reserved,
                    optionalBounds(item, "collisionEnvelope", reserved),
                    optionalBounds(item, "maskEnvelope", reserved),
                    stringValue(item, "envelopeMode", ""),
                    stringValue(item, "selectedEnvelopeGroupKey", ""),
                    strings(item.getAsJsonArray("availableEnvelopeGroupKeys")),
                    strings(item.getAsJsonArray("semanticTerms")),
                    strings(item.getAsJsonArray("functionTerms")),
                    !stringValue(item, "expectedStartSignature", "").isBlank()
                            ? stringValue(item, "expectedStartSignature", "")
                            : stringValue(item, "startSignature", ""),
                    maskMarginBlocks(item),
                    item.deepCopy());
        }

        JsonObject asPlanJson(BlockBounds actualFootprint, String startSignature, JsonArray pieceBoxes) {
            JsonObject obj = outputAnchorJson();
            obj.add("actualFootprint", boundsJson(actualFootprint));
            obj.addProperty("startSignature", startSignature == null ? "" : startSignature);
            obj.add("pieceBoxes", pieceBoxes == null ? new JsonArray() : pieceBoxes);
            if (templateSemantic()) {
                obj.add("reservedEnvelope", boundsJson(actualFootprint));
                obj.add("collisionEnvelope", boundsJson(actualFootprint));
                obj.add("maskEnvelope", boundsJson(expand(actualFootprint, maskMarginBlocks)));
                addTemplateFields(obj, templateFacts(), actualFootprint);
            }
            return obj;
        }

        boolean acceptsBBoxGroupKey(String actualGroupKey) {
            if (!"fixed_bbox_group".equals(envelopeMode) || availableEnvelopeGroupKeys.isEmpty()) {
                return true;
            }
            return availableEnvelopeGroupKeys.contains(actualGroupKey);
        }

        JsonObject asWorldgenPlanJson(ChunkStatusResult status) {
            return asWorldgenPlanJson(status, null, expectedStartSignature, new JsonArray());
        }

        JsonObject asWorldgenPlanJson(ChunkStatusResult status, BlockBounds actualFootprint,
                                      String startSignature, JsonArray pieceBoxes) {
            BlockBounds lockedCollision = actualFootprint == null
                    ? null : expand(actualFootprint, DEFAULT_COLLISION_CLEARANCE_BLOCKS);
            String lockedGroupKey = actualFootprint == null
                    ? "" : actualBBoxGroupKey(this, actualFootprint, pieceBoxes);
            return asWorldgenPlanJson(status, actualFootprint, lockedCollision, lockedGroupKey,
                    startSignature, pieceBoxes);
        }

        JsonObject asWorldgenPlanJson(ChunkStatusResult status, BlockBounds actualFootprint,
                                      BlockBounds lockedCollisionEnvelope, String lockedBBoxGroupKey,
                                      String startSignature, JsonArray pieceBoxes) {
            JsonObject obj = outputAnchorJson();
            if (!obj.has("commandAnchorBlock")) {
                obj.add("commandAnchorBlock", anchorBlock.asJson());
            }
            JsonObject chunk = new JsonObject();
            chunk.addProperty("x", Math.floorDiv(anchorBlock.x(), 16));
            chunk.addProperty("z", Math.floorDiv(anchorBlock.z(), 16));
            obj.add("anchorChunk", chunk);
            BlockBounds chunkRangeSource = lockedCollisionEnvelope == null ? reservedEnvelope : lockedCollisionEnvelope;
            obj.add("requiredChunkRange", chunkRangeJson(chunkRangeSource));
            obj.addProperty("expectedStartSignature", startSignature == null ? "" : startSignature);
            if (actualFootprint != null) {
                obj.add("actualFootprint", boundsJson(actualFootprint));
                obj.addProperty("locked", true);
                obj.add("lockedActualFootprint", boundsJson(actualFootprint));
                obj.addProperty("d4SelectedEnvelopeGroupKey", selectedEnvelopeGroupKey);
                obj.addProperty("selectedEnvelopeGroupKey", lockedBBoxGroupKey == null ? "" : lockedBBoxGroupKey);
                obj.addProperty("actualBBoxGroupKey", lockedBBoxGroupKey == null ? "" : lockedBBoxGroupKey);
                obj.addProperty("lockedBBoxGroupKey", lockedBBoxGroupKey == null ? "" : lockedBBoxGroupKey);
            }
            if (lockedCollisionEnvelope != null) {
                obj.add("reservedEnvelope", boundsJson(lockedCollisionEnvelope));
                obj.add("collisionEnvelope", boundsJson(lockedCollisionEnvelope));
                obj.add("lockedCollisionEnvelope", boundsJson(lockedCollisionEnvelope));
                obj.add("maskEnvelope", boundsJson(expand(lockedCollisionEnvelope, maskMarginBlocks)));
                obj.addProperty("maskMarginBlocks", maskMarginBlocks);
            }
            obj.addProperty("collisionClearanceBlocks", DEFAULT_COLLISION_CLEARANCE_BLOCKS);
            obj.add("pieceBoxes", pieceBoxes == null ? new JsonArray() : pieceBoxes);
            obj.addProperty("worldgenPlacementMode", true);
            obj.addProperty("status", status.status());
            obj.addProperty("reasonCode", status.reasonCode());
            obj.addProperty("message", status.message());
            if (templateSemantic()) {
                addTemplateFields(obj, templateFacts(), actualFootprint);
            }
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
            obj.add("collisionEnvelope", boundsJson(collisionEnvelope));
            obj.add("maskEnvelope", boundsJson(maskEnvelope));
            obj.addProperty("envelopeMode", envelopeMode);
            obj.addProperty("selectedEnvelopeGroupKey", selectedEnvelopeGroupKey);
            obj.add("semanticTerms", stringArray(semanticTerms));
            obj.add("functionTerms", stringArray(functionTerms));
            obj.addProperty("worldMutationApplied", result.worldMutationApplied());
            obj.addProperty("startSignature", result.startSignature());
            obj.add("pieceBoxes", result.pieceBoxes());
            return obj;
        }

        JsonObject asTemplateLedgerJson(JsonObject runtimeLedgerItem) {
            JsonObject obj = runtimeLedgerItem.deepCopy();
            BlockBounds footprint = templateActualFootprint();
            obj.addProperty("anchorId", anchorId);
            obj.addProperty("structureId", structureId);
            obj.add("anchorBlock", anchorBlock.asJson());
            obj.add("plannedFootprint", boundsJson(footprint));
            obj.add("actualFootprint", boundsJson(footprint));
            obj.add("reservedEnvelope", boundsJson(footprint));
            obj.add("collisionEnvelope", boundsJson(footprint));
            obj.add("maskEnvelope", boundsJson(expand(footprint, maskMarginBlocks)));
            obj.add("pieceBoxes", new JsonArray());
            obj.remove("startSignature");
            obj.remove("expectedStartSignature");
            addTemplateFields(obj, templateFacts(), footprint);
            return obj;
        }

        private JsonObject outputAnchorJson() {
            JsonObject obj = sourceAnchor.deepCopy();
            obj.remove("safetyEnvelope");
            obj.remove("estimatedSafetyEnvelope");
            obj.remove("groupSafetyEnvelope");
            if (templateSemantic()) {
                // Historical artifacts may carry this snapshot, but new plans derive it from templateSize.
                obj.remove("templateFootprint");
            }
            return obj;
        }

        private static void addTemplateFields(JsonObject target, TemplateFacts facts, BlockBounds lockedFootprint) {
            target.addProperty("templateId", facts.templateId());
            target.addProperty("templateRef", facts.templateRef());
            target.addProperty("templateHash", facts.templateHash());
            target.addProperty("variantId", facts.variantId());
            target.addProperty("rotation", facts.rotation());
            target.addProperty("mirror", facts.mirror());
            target.addProperty("materializationSource", TEMPLATE_MATERIALIZATION_SOURCE);
            target.addProperty("templateDatumPolicy", TEMPLATE_DATUM_POLICY_WORLDGEN_SURFACE);
            if (facts.templateSize() != null) {
                target.add("templateSize", sizeJson(facts.templateSize()));
            }
            if (lockedFootprint != null) {
                target.add("lockedActualFootprint", boundsJson(lockedFootprint));
            }
            JsonObject template = new JsonObject();
            template.addProperty("templateId", facts.templateId());
            template.addProperty("templateRef", facts.templateRef());
            template.addProperty("templateHash", facts.templateHash());
            template.addProperty("variantId", facts.variantId());
            template.addProperty("rotation", facts.rotation());
            template.addProperty("mirror", facts.mirror());
            template.addProperty("materializationSource", TEMPLATE_MATERIALIZATION_SOURCE);
            template.addProperty("templateDatumPolicy", TEMPLATE_DATUM_POLICY_WORLDGEN_SURFACE);
            if (facts.templateSize() != null) {
                template.add("templateSize", sizeJson(facts.templateSize()));
            }
            if (lockedFootprint != null) {
                template.add("lockedActualFootprint", boundsJson(lockedFootprint));
            }
            target.add("structureTemplate", template);
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

        private static int maskMarginBlocks(JsonObject obj) {
            return Math.max(0, intValue(obj, "maskMarginBlocks",
                    intValue(obj, "d5MaskMarginBlocks", CityStructureAnchorPlanner.DEFAULT_MASK_MARGIN_BLOCKS)));
        }

        private static JsonArray stringArray(List<String> values) {
            JsonArray array = new JsonArray();
            values.forEach(array::add);
            return array;
        }

        private static BlockPoint blockPoint(JsonObject obj) {
            return new BlockPoint(intValue(obj, "x", 0), intValue(obj, "z", 0));
        }

        private static BlockBounds optionalBounds(JsonObject obj, String key, BlockBounds fallback) {
            return obj.has(key) && obj.get(key).isJsonObject() ? bounds(obj.getAsJsonObject(key)) : fallback;
        }
    }

    private record TemplateFacts(boolean semantic, String templateId, String templateRef, String templateHash,
                                 String variantId, String rotation, String mirror,
                                 CityTemplatePlacementGeometry.Size templateSize,
                                 BlockBounds actualFootprint, BlockBounds suppliedFootprint,
                                 BlockBounds lockedActualFootprint, String materializationSource) {
        static TemplateFacts from(JsonObject source, BlockPoint fallbackAnchor) {
            JsonObject nested = source != null && source.has("structureTemplate")
                    && source.get("structureTemplate").isJsonObject()
                    ? source.getAsJsonObject("structureTemplate") : null;
            boolean semantic = nested != null
                    || TEMPLATE_MATERIALIZATION_SOURCE.equals(stringValue(source, "materializationSource", ""))
                    || hasAny(source, "templateHash", "templateRef", "variantId", "mirror", "templateSize",
                    "templateFootprint")
                    || hasAny(nested, "templateHash", "templateRef", "variantId", "mirror", "templateSize",
                    "templateFootprint");
            String templateId = readString(nested, source, "templateId", "");
            String templateRef = readString(nested, source, "templateRef", "");
            if (templateRef.isBlank()) {
                templateRef = readString(nested, source, "nbtFile", "");
            }
            String templateHash = readString(nested, source, "templateHash", "");
            if (templateHash.isBlank()) {
                templateHash = readString(nested, source, "contentHash", "");
            }
            String variantId = readString(nested, source, "variantId", "");
            String rotation = readString(nested, source, "rotation", "");
            String mirror = readString(nested, source, "mirror", "");
            CityTemplatePlacementGeometry.Size templateSize = readSize(nested, source);
            BlockBounds suppliedFootprint = readBounds(nested, source, "actualFootprint");
            if (suppliedFootprint == null) {
                // Read-only legacy fallback. New D4/D6/D7 artifacts never emit templateFootprint.
                suppliedFootprint = readBounds(nested, source, "templateFootprint");
            }
            BlockBounds lockedFootprint = readBounds(nested, source, "lockedActualFootprint");
            if (suppliedFootprint == null) {
                suppliedFootprint = lockedFootprint;
            }
            BlockPoint anchor = readAnchor(nested, source, fallbackAnchor);
            BlockBounds actualFootprint = derivedFootprint(templateSize, rotation, mirror, anchor);
            if (actualFootprint == null) {
                actualFootprint = suppliedFootprint;
            }
            return new TemplateFacts(semantic, templateId, templateRef, templateHash, variantId,
                    rotation, mirror, templateSize, actualFootprint, suppliedFootprint, lockedFootprint,
                    readString(nested, source, "materializationSource", ""));
        }

        TemplateValidation validate(boolean requireLockedFootprint) {
            if (!semantic) {
                return TemplateValidation.ok();
            }
            List<String> errors = new ArrayList<>();
            if (templateId.isBlank()) errors.add("STRUCTURE_TEMPLATE_MISSING_TEMPLATE_ID");
            if (templateRef.isBlank()) errors.add("STRUCTURE_TEMPLATE_MISSING_TEMPLATE_REF");
            if (templateHash.isBlank()) errors.add("STRUCTURE_TEMPLATE_MISSING_TEMPLATE_HASH");
            if (variantId.isBlank()) errors.add("STRUCTURE_TEMPLATE_MISSING_VARIANT_ID");
            if (rotation.isBlank() || mirror.isBlank()) errors.add("STRUCTURE_TEMPLATE_MISSING_TRANSFORM");
            if (actualFootprint == null) errors.add("STRUCTURE_TEMPLATE_MISSING_FOOTPRINT");
            if (templateSize != null && suppliedFootprint != null && !actualFootprint.equals(suppliedFootprint)) {
                errors.add("STRUCTURE_TEMPLATE_FOOTPRINT_DRIFT");
            }
            if (requireLockedFootprint && lockedActualFootprint == null) {
                errors.add("STRUCTURE_TEMPLATE_MISSING_LOCKED_ACTUAL_FOOTPRINT");
            }
            if (actualFootprint != null && lockedActualFootprint != null
                    && !actualFootprint.equals(lockedActualFootprint)) {
                errors.add("STRUCTURE_TEMPLATE_FOOTPRINT_DRIFT");
            }
            if (!materializationSource.isBlank() && !TEMPLATE_MATERIALIZATION_SOURCE.equals(materializationSource)) {
                errors.add("STRUCTURE_TEMPLATE_MATERIALIZATION_SOURCE_INVALID");
            }
            if (errors.isEmpty()) {
            return TemplateValidation.ok();
            }
            return new TemplateValidation(false, "STRUCTURE_TEMPLATE_PLAN_INVALID", errors,
                    "Structure template plan is missing or has inconsistent locked template fields.");
        }

        private static boolean hasAny(JsonObject object, String... keys) {
            if (object == null) return false;
            for (String key : keys) {
                if (object.has(key)) return true;
            }
            return false;
        }

        private static String readString(JsonObject nested, JsonObject source, String key, String fallback) {
            String value = stringValue(nested, key, "");
            return value.isBlank() ? stringValue(source, key, fallback) : value;
        }

        private static BlockBounds readBounds(JsonObject nested, JsonObject source, String key) {
            if (nested != null && nested.has(key) && nested.get(key).isJsonObject()) {
                return bounds(nested.getAsJsonObject(key));
            }
            return source != null && source.has(key) && source.get(key).isJsonObject()
                    ? bounds(source.getAsJsonObject(key)) : null;
        }

        private static CityTemplatePlacementGeometry.Size readSize(JsonObject nested, JsonObject source) {
            JsonObject value = readObject(nested, source, "templateSize");
            if (value == null) {
                value = readObject(nested, source, "rawSize");
            }
            if (value == null) {
                return null;
            }
            try {
                return new CityTemplatePlacementGeometry.Size(intValue(value, "width", 0),
                        intValue(value, "height", 0), intValue(value, "depth", 0));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        private static BlockPoint readAnchor(JsonObject nested, JsonObject source, BlockPoint fallback) {
            JsonObject value = readObject(nested, source, "anchorBlock");
            if (value == null) {
                value = readObject(nested, source, "commandAnchorBlock");
            }
            return value == null ? fallback : new BlockPoint(intValue(value, "x", fallback.x()),
                    intValue(value, "z", fallback.z()));
        }

        private static JsonObject readObject(JsonObject nested, JsonObject source, String key) {
            if (nested != null && nested.has(key) && nested.get(key).isJsonObject()) {
                return nested.getAsJsonObject(key);
            }
            return source != null && source.has(key) && source.get(key).isJsonObject()
                    ? source.getAsJsonObject(key) : null;
        }

        private static BlockBounds derivedFootprint(CityTemplatePlacementGeometry.Size size,
                                                     String rotation, String mirror, BlockPoint anchor) {
            if (size == null || anchor == null || rotation == null || rotation.isBlank()
                    || mirror == null || mirror.isBlank()) {
                return null;
            }
            try {
                return CityTemplatePlacementGeometry.of(size,
                        CityTemplatePlacementGeometry.Rotation.valueOf(rotation),
                        CityTemplatePlacementGeometry.Mirror.valueOf(mirror), List.of())
                        .worldBounds(anchor);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    private record TemplateValidation(boolean valid, String reasonCode, List<String> errors, String message) {
        static TemplateValidation ok() {
            return new TemplateValidation(true, "", List.of(), "");
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
