package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public record BuildOperationPlan(
        String schema,
        String cityId,
        String templateDirectory,
        List<Operation> operations) {

    public static final String SCHEMA = "build_operation_plan";

    public BuildOperationPlan {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("schema is required");
        }
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("cityId is required");
        }
        templateDirectory = templateDirectory == null ? "geomantia_templates/d5" : templateDirectory;
        operations = List.copyOf(operations);
    }

    public record Operation(String operationId, String operationType, String sourceIntentId,
                            List<BlockPoint> polyline, int widthBlocks, String material,
                            String edgeMaterial, String templateId, BlockPoint anchorBlock,
                            String note) {
        public Operation {
            if (operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("operationId is required");
            }
            if (operationType == null || operationType.isBlank()) {
                throw new IllegalArgumentException("operationType is required");
            }
            sourceIntentId = sourceIntentId == null ? "" : sourceIntentId;
            polyline = List.copyOf(polyline);
            widthBlocks = Math.max(1, widthBlocks);
            material = material == null ? "" : material;
            edgeMaterial = edgeMaterial == null ? "" : edgeMaterial;
            templateId = templateId == null ? "" : templateId;
            anchorBlock = anchorBlock == null ? new BlockPoint(0, 0) : anchorBlock;
            note = note == null ? "" : note;
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("operationId", operationId);
            obj.addProperty("operationType", operationType);
            obj.addProperty("sourceIntentId", sourceIntentId);
            obj.add("polyline", RoadIntent.blockArray(polyline));
            obj.addProperty("widthBlocks", widthBlocks);
            obj.addProperty("material", material);
            obj.addProperty("edgeMaterial", edgeMaterial);
            obj.addProperty("templateId", templateId);
            obj.add("anchorBlock", anchorBlock.asJson());
            obj.addProperty("note", note);
            return obj;
        }

        public static Operation fromJson(JsonObject obj) {
            return new Operation(
                    RoadIntent.requiredString(obj, "operationId"),
                    RoadIntent.requiredString(obj, "operationType"),
                    RoadIntent.stringValue(obj, "sourceIntentId", ""),
                    RoadIntent.blockPoints(RoadIntent.optionalArray(obj, "polyline")),
                    RoadIntent.intValue(obj, "widthBlocks", 1),
                    RoadIntent.stringValue(obj, "material", ""),
                    RoadIntent.stringValue(obj, "edgeMaterial", ""),
                    RoadIntent.stringValue(obj, "templateId", ""),
                    obj.has("anchorBlock") && obj.get("anchorBlock").isJsonObject()
                            ? RoadIntent.blockPoint(obj.getAsJsonObject("anchorBlock"))
                            : new BlockPoint(0, 0),
                    RoadIntent.stringValue(obj, "note", ""));
        }
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schema", schema);
        obj.addProperty("cityId", cityId);
        obj.addProperty("templateDirectory", templateDirectory);
        JsonArray array = new JsonArray();
        operations.forEach(operation -> array.add(operation.asJson()));
        obj.add("operations", array);
        return obj;
    }

    public static BuildOperationPlan fromJson(JsonObject obj) {
        List<Operation> operations = new ArrayList<>();
        for (JsonElement elem : RoadIntent.requiredArray(obj, "operations")) {
            operations.add(Operation.fromJson(elem.getAsJsonObject()));
        }
        return new BuildOperationPlan(
                RoadIntent.requiredString(obj, "schema"),
                RoadIntent.requiredString(obj, "cityId"),
                RoadIntent.stringValue(obj, "templateDirectory", "geomantia_templates/d5"),
                operations);
    }
}
