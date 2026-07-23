package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public record RoadIntent(
        String schemaVersion,
        String cityId,
        List<Node> nodes,
        List<Edge> edges,
        CityQualityReport connectivityReport) {

    public static final String CURRENT_SCHEMA_VERSION = "road_intent.v0.1";

    public RoadIntent {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required");
        }
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("cityId is required");
        }
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        if (connectivityReport == null) {
            throw new IllegalArgumentException("connectivityReport is required");
        }
    }

    public record Node(String nodeId, String nodeType, String zonePatchId, BlockPoint block, String label) {
        public Node {
            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException("nodeId is required");
            }
            nodeType = nodeType == null ? "unknown" : nodeType;
            zonePatchId = zonePatchId == null ? "" : zonePatchId;
            if (block == null) {
                throw new IllegalArgumentException("block is required");
            }
            label = label == null ? "" : label;
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("nodeId", nodeId);
            obj.addProperty("nodeType", nodeType);
            obj.addProperty("zonePatchId", zonePatchId);
            obj.add("block", block.asJson());
            obj.addProperty("label", label);
            return obj;
        }

        public static Node fromJson(JsonObject obj) {
            return new Node(
                    requiredString(obj, "nodeId"),
                    stringValue(obj, "nodeType", "unknown"),
                    stringValue(obj, "zonePatchId", ""),
                    blockPoint(requiredObject(obj, "block")),
                    stringValue(obj, "label", ""));
        }
    }

    public record Edge(String edgeId, String edgeType, String fromNodeId, String toNodeId,
                       List<BlockPoint> polyline, int widthBlocks, List<String> operationRefs,
                       String reason) {
        public Edge {
            if (edgeId == null || edgeId.isBlank()) {
                throw new IllegalArgumentException("edgeId is required");
            }
            edgeType = edgeType == null ? "secondary" : edgeType;
            fromNodeId = fromNodeId == null ? "" : fromNodeId;
            toNodeId = toNodeId == null ? "" : toNodeId;
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
            obj.addProperty("edgeType", edgeType);
            obj.addProperty("fromNodeId", fromNodeId);
            obj.addProperty("toNodeId", toNodeId);
            obj.add("polyline", blockArray(polyline));
            obj.addProperty("widthBlocks", widthBlocks);
            obj.add("operationRefs", stringArray(operationRefs));
            obj.addProperty("reason", reason);
            return obj;
        }

        public static Edge fromJson(JsonObject obj) {
            return new Edge(
                    requiredString(obj, "edgeId"),
                    stringValue(obj, "edgeType", "secondary"),
                    stringValue(obj, "fromNodeId", ""),
                    stringValue(obj, "toNodeId", ""),
                    blockPoints(requiredArray(obj, "polyline")),
                    intValue(obj, "widthBlocks", 3),
                    strings(optionalArray(obj, "operationRefs")),
                    stringValue(obj, "reason", ""));
        }
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", schemaVersion);
        obj.addProperty("cityId", cityId);
        JsonArray nodeArray = new JsonArray();
        nodes.forEach(node -> nodeArray.add(node.asJson()));
        obj.add("nodes", nodeArray);
        JsonArray edgeArray = new JsonArray();
        edges.forEach(edge -> edgeArray.add(edge.asJson()));
        obj.add("edges", edgeArray);
        obj.add("connectivityReport", connectivityReport.asJson());
        return obj;
    }

    public static RoadIntent fromJson(JsonObject obj) {
        List<Node> nodes = new ArrayList<>();
        for (JsonElement elem : requiredArray(obj, "nodes")) {
            nodes.add(Node.fromJson(elem.getAsJsonObject()));
        }
        List<Edge> edges = new ArrayList<>();
        for (JsonElement elem : requiredArray(obj, "edges")) {
            edges.add(Edge.fromJson(elem.getAsJsonObject()));
        }
        return new RoadIntent(
                requiredString(obj, "schemaVersion"),
                requiredString(obj, "cityId"),
                nodes,
                edges,
                CityQualityReport.fromJson(requiredObject(obj, "connectivityReport")));
    }

    static JsonArray blockArray(List<BlockPoint> points) {
        JsonArray array = new JsonArray();
        points.forEach(point -> array.add(point.asJson()));
        return array;
    }

    static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    static List<BlockPoint> blockPoints(JsonArray array) {
        List<BlockPoint> points = new ArrayList<>();
        for (JsonElement elem : array) {
            points.add(blockPoint(elem.getAsJsonObject()));
        }
        return points;
    }

    static BlockPoint blockPoint(JsonObject obj) {
        return new BlockPoint(intValue(obj, "x", 0), intValue(obj, "z", 0));
    }

    static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                values.add(elem.getAsString());
            }
        }
        return values;
    }

    static JsonObject requiredObject(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required");
        }
        return obj.getAsJsonObject(key);
    }

    static JsonArray requiredArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required");
        }
        return obj.getAsJsonArray(key);
    }

    static JsonArray optionalArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return obj.getAsJsonArray(key);
    }

    static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }

    static int intValue(JsonObject obj, String key, int defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsInt();
    }
}
