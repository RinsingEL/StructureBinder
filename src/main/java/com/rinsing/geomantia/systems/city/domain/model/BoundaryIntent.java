package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public record BoundaryIntent(
        String schemaVersion,
        String cityId,
        List<Edge> edges) {

    public static final String CURRENT_SCHEMA_VERSION = "boundary_intent.v0.1";

    public BoundaryIntent {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required");
        }
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("cityId is required");
        }
        edges = List.copyOf(edges);
    }

    public record Edge(String edgeId, String treatmentType, String fromZoneId, String toZoneId,
                       List<BlockPoint> polyline, int widthBlocks, int priority,
                       List<String> operationRefs, String reason) {
        public Edge {
            if (edgeId == null || edgeId.isBlank()) {
                throw new IllegalArgumentException("edgeId is required");
            }
            treatmentType = treatmentType == null ? "soft_transition" : treatmentType;
            fromZoneId = fromZoneId == null ? "" : fromZoneId;
            toZoneId = toZoneId == null ? "" : toZoneId;
            polyline = List.copyOf(polyline);
            if (polyline.size() < 2) {
                throw new IllegalArgumentException("polyline must contain at least 2 points");
            }
            widthBlocks = Math.max(1, widthBlocks);
            operationRefs = List.copyOf(operationRefs);
            reason = reason == null ? "" : reason;
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("edgeId", edgeId);
            obj.addProperty("treatmentType", treatmentType);
            obj.addProperty("fromZoneId", fromZoneId);
            obj.addProperty("toZoneId", toZoneId);
            obj.add("polyline", RoadIntent.blockArray(polyline));
            obj.addProperty("widthBlocks", widthBlocks);
            obj.addProperty("priority", priority);
            obj.add("operationRefs", RoadIntent.stringArray(operationRefs));
            obj.addProperty("reason", reason);
            return obj;
        }

        public static Edge fromJson(JsonObject obj) {
            return new Edge(
                    RoadIntent.requiredString(obj, "edgeId"),
                    RoadIntent.stringValue(obj, "treatmentType", "soft_transition"),
                    RoadIntent.stringValue(obj, "fromZoneId", ""),
                    RoadIntent.stringValue(obj, "toZoneId", ""),
                    RoadIntent.blockPoints(RoadIntent.requiredArray(obj, "polyline")),
                    RoadIntent.intValue(obj, "widthBlocks", 3),
                    RoadIntent.intValue(obj, "priority", 0),
                    RoadIntent.strings(RoadIntent.optionalArray(obj, "operationRefs")),
                    RoadIntent.stringValue(obj, "reason", ""));
        }
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", schemaVersion);
        obj.addProperty("cityId", cityId);
        JsonArray array = new JsonArray();
        edges.forEach(edge -> array.add(edge.asJson()));
        obj.add("edges", array);
        return obj;
    }

    public static BoundaryIntent fromJson(JsonObject obj) {
        List<Edge> edges = new ArrayList<>();
        for (JsonElement elem : RoadIntent.requiredArray(obj, "edges")) {
            edges.add(Edge.fromJson(elem.getAsJsonObject()));
        }
        return new BoundaryIntent(
                RoadIntent.requiredString(obj, "schemaVersion"),
                RoadIntent.requiredString(obj, "cityId"),
                edges);
    }
}
