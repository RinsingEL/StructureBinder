package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public record WorldMutationReport(
        String schemaVersion,
        String cityId,
        String backend,
        boolean executed,
        boolean undoSupported,
        int changedBlocks,
        int executedOperations,
        int skippedOperations,
        int failedOperations,
        List<OperationResult> operationResults,
        List<String> warnings,
        List<String> failures) {

    public static final String CURRENT_SCHEMA_VERSION = "world_mutation_report.v0.1";

    public WorldMutationReport {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required");
        }
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("cityId is required");
        }
        backend = backend == null ? "unknown" : backend;
        operationResults = List.copyOf(operationResults);
        warnings = List.copyOf(warnings);
        failures = List.copyOf(failures);
    }

    public record OperationResult(String operationId, String status, int changedBlocks, String reason) {
        public OperationResult {
            if (operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("operationId is required");
            }
            status = status == null ? "skipped" : status;
            reason = reason == null ? "" : reason;
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("operationId", operationId);
            obj.addProperty("status", status);
            obj.addProperty("changedBlocks", changedBlocks);
            obj.addProperty("reason", reason);
            return obj;
        }
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", schemaVersion);
        obj.addProperty("cityId", cityId);
        obj.addProperty("backend", backend);
        obj.addProperty("executed", executed);
        obj.addProperty("undoSupported", undoSupported);
        obj.addProperty("changedBlocks", changedBlocks);
        obj.addProperty("executedOperations", executedOperations);
        obj.addProperty("skippedOperations", skippedOperations);
        obj.addProperty("failedOperations", failedOperations);
        JsonArray results = new JsonArray();
        operationResults.forEach(result -> results.add(result.asJson()));
        obj.add("operationResults", results);
        obj.add("warnings", RoadIntent.stringArray(warnings));
        obj.add("failures", RoadIntent.stringArray(failures));
        return obj;
    }
}
