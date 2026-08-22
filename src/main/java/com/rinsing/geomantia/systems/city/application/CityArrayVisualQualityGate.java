package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rejects legal-but-unrecognizable array geometry before D4 can report quality success. */
final class CityArrayVisualQualityGate {
    static final String SCHEMA_VERSION = "city_array_visual_quality.v0.1";

    Result evaluate(JsonArray anchorsJson, JsonArray streetsJson) {
        List<Anchor> anchors = anchorsJson.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(anchor -> !"connectivity_growth".equals(string(anchor, "blueprintPlacementPhase")))
                .map(Anchor::parse).toList();
        Map<String, List<Anchor>> byGroup = new LinkedHashMap<>();
        anchors.forEach(anchor -> byGroup.computeIfAbsent(anchor.groupId(), ignored -> new ArrayList<>())
                .add(anchor));
        Map<String, List<JsonObject>> roadsByGroup = new LinkedHashMap<>();
        for (JsonElement element : streetsJson) {
            if (!element.isJsonObject()) continue;
            JsonObject road = element.getAsJsonObject();
            roadsByGroup.computeIfAbsent(string(road, "groupId"), ignored -> new ArrayList<>()).add(road);
        }

        List<String> hardBlocks = new ArrayList<>();
        JsonArray groups = new JsonArray();
        for (Map.Entry<String, List<Anchor>> entry : byGroup.entrySet()) {
            String groupId = entry.getKey();
            List<Anchor> group = entry.getValue();
            String algorithm = group.get(0).algorithm();
            List<JsonObject> roads = roadsByGroup.getOrDefault(groupId, List.of());
            JsonObject metrics = switch (algorithm) {
                case "GRID" -> grid(groupId, group, roads, hardBlocks);
                case "COURTYARD" -> courtyard(groupId, group, roads, hardBlocks);
                case "LINEAR" -> linear(groupId, roads, hardBlocks);
                case "CENTER_SYMMETRIC" -> symmetric(groupId, group, roads, hardBlocks);
                case "COMPACT" -> compact(groupId, group, roads, hardBlocks);
                case "ORGANIC_COMPACT" -> organic(groupId, group, roads, hardBlocks);
                default -> new JsonObject();
            };
            metrics.addProperty("groupId", groupId);
            metrics.addProperty("algorithm", algorithm);
            groups.add(metrics);
        }
        JsonObject value = new JsonObject();
        value.addProperty("schemaVersion", SCHEMA_VERSION);
        value.addProperty("passed", hardBlocks.isEmpty());
        JsonArray blocks = new JsonArray();
        hardBlocks.forEach(blocks::add);
        value.add("hardBlocks", blocks);
        value.add("groups", groups);
        return new Result(hardBlocks.isEmpty(), List.copyOf(hardBlocks), value);
    }

    private static JsonObject grid(String groupId, List<Anchor> anchors, List<JsonObject> roads,
                                   List<String> hardBlocks) {
        int maximumError = 0;
        Set<Integer> pitches = new LinkedHashSet<>();
        Set<Integer> rows = new LinkedHashSet<>();
        Set<Integer> columns = new LinkedHashSet<>();
        for (Anchor anchor : anchors) {
            JsonObject theoretical = object(anchor.layout(), "theoreticalAnchor");
            maximumError = Math.max(maximumError, Math.max(Math.abs(anchor.point().x() - intValue(theoretical, "x")),
                    Math.abs(anchor.point().z() - intValue(theoretical, "z"))));
            pitches.add(intValue(anchor.layout(), "gridPitchBlocks"));
            rows.add(intValue(anchor.layout(), "gridRow"));
            columns.add(intValue(anchor.layout(), "gridColumn"));
        }
        boolean mainStreet = roads.stream().anyMatch(road -> "GRID_MAIN_STREET".equals(string(road, "roadKind")));
        boolean lanes = roads.stream().anyMatch(road -> string(road, "roadKind").contains("LANE"));
        boolean connected = connectedRoads(roads);
        if (maximumError > 1) hardBlocks.add(groupId + ": GRID_ROW_COLUMN_ERROR_EXCEEDS_ONE_BLOCK");
        if (pitches.size() != 1) hardBlocks.add(groupId + ": GRID_PITCH_NOT_UNIFORM");
        if (rows.size() > 1 && !mainStreet) hardBlocks.add(groupId + ": GRID_MAIN_STREET_MISSING");
        if (columns.size() > 1 && !lanes) hardBlocks.add(groupId + ": GRID_LANE_MISSING");
        if (!connected) hardBlocks.add(groupId + ": GRID_STREET_NETWORK_DISCONNECTED");
        JsonObject value = new JsonObject();
        value.addProperty("maximumRowColumnErrorBlocks", maximumError);
        value.addProperty("fixedPitch", pitches.size() == 1);
        value.addProperty("mainStreetPresent", mainStreet);
        value.addProperty("lanePresent", lanes);
        value.addProperty("streetNetworkConnected", connected);
        return value;
    }

    private static JsonObject courtyard(String groupId, List<Anchor> anchors, List<JsonObject> roads,
                                        List<String> hardBlocks) {
        JsonObject centerJson = anchors.stream().map(Anchor::layout)
                .filter(layout -> layout.has("courtyardCenter"))
                .map(layout -> layout.getAsJsonObject("courtyardCenter"))
                .findFirst().orElse(new JsonObject());
        BlockPoint center = new BlockPoint(intValue(centerJson, "x"), intValue(centerJson, "z"));
        boolean centerEmpty = anchors.stream().noneMatch(anchor -> contains(anchor.collision(), center));
        Set<String> positions = new HashSet<>();
        for (Anchor anchor : anchors) {
            positions.add(intValue(anchor.layout(), "courtyardRow") + ":"
                    + intValue(anchor.layout(), "courtyardColumn"));
        }
        Set<String> required = Set.of("-1:0", "0:1", "1:1", "1:-1", "0:-1");
        double enclosure = required.stream().filter(positions::contains).count() / 5.0;
        boolean gate = roads.stream().anyMatch(road -> "COURTYARD_GATE".equals(string(road, "roadKind")));
        boolean connected = connectedRoads(roads);
        if (anchors.size() < 5) hardBlocks.add(groupId + ": COURTYARD_REQUIRES_FIVE_BUILDINGS");
        if (!centerEmpty) hardBlocks.add(groupId + ": COURTYARD_CENTER_OCCUPIED");
        if (enclosure < 1.0) hardBlocks.add(groupId + ": COURTYARD_BASE_RING_INCOMPLETE");
        if (!gate) hardBlocks.add(groupId + ": COURTYARD_GATE_MISSING");
        if (!connected) hardBlocks.add(groupId + ": COURTYARD_RING_ROAD_DISCONNECTED");
        JsonObject value = new JsonObject();
        value.addProperty("centerEmpty", centerEmpty);
        value.addProperty("buildingCount", anchors.size());
        value.addProperty("baseRingEnclosureRatio", enclosure);
        value.addProperty("gatePresent", gate);
        value.addProperty("ringRoadConnected", connected);
        return value;
    }

    private static JsonObject linear(String groupId, List<JsonObject> roads, List<String> hardBlocks) {
        boolean present = roads.stream().anyMatch(road -> "LINEAR_STREET_BAND".equals(string(road, "roadKind")));
        boolean connected = connectedRoads(roads);
        if (!present) hardBlocks.add(groupId + ": LINEAR_STREET_BAND_MISSING");
        if (!connected) hardBlocks.add(groupId + ": LINEAR_STREET_BAND_DISCONNECTED");
        JsonObject value = new JsonObject();
        value.addProperty("streetBandPresent", present);
        value.addProperty("streetBandConnected", connected);
        return value;
    }

    private static JsonObject symmetric(String groupId, List<Anchor> anchors, List<JsonObject> roads,
                                        List<String> hardBlocks) {
        boolean pairsVerified = anchors.stream().filter(anchor -> !"required".equals(anchor.phase()))
                .allMatch(anchor -> object(anchor.layout(), "centerSymmetryProof")
                        .has("verified") && object(anchor.layout(), "centerSymmetryProof")
                        .get("verified").getAsBoolean());
        boolean axisEnabled = roads.stream().anyMatch(road -> string(road, "roadKind").startsWith("CENTER_AXIS_"));
        boolean axisValid = !axisEnabled || roads.stream().filter(road -> string(road, "roadKind")
                .startsWith("CENTER_AXIS_")).count() == 2;
        if (!pairsVerified) hardBlocks.add(groupId + ": CENTER_SYMMETRY_PAIR_INVALID");
        if (!axisValid) hardBlocks.add(groupId + ": CENTER_AXIS_STREET_DISCONNECTED");
        JsonObject value = new JsonObject();
        value.addProperty("pairsVerified", pairsVerified);
        value.addProperty("axisStreetEnabled", axisEnabled);
        value.addProperty("axisStreetValid", axisValid);
        return value;
    }

    private static JsonObject compact(String groupId, List<Anchor> anchors, List<JsonObject> roads,
                                      List<String> hardBlocks) {
        List<JsonObject> alley = roads.stream().filter(road -> "COMPACT_ALLEY".equals(string(road, "roadKind")))
                .toList();
        boolean connected = connectedRoads(alley);
        boolean frontage = anchors.stream().allMatch(anchor -> doubleValue(anchor.layout(),
                "frontageAlignmentScore") + 1.0e-9 >= doubleValue(anchor.layout(),
                "frontageMinimumAlignmentScore"));
        if (alley.isEmpty()) hardBlocks.add(groupId + ": COMPACT_ALLEY_MISSING");
        if (!connected) hardBlocks.add(groupId + ": COMPACT_ALLEY_DISCONNECTED");
        if (!frontage) hardBlocks.add(groupId + ": COMPACT_FRONTAGE_NOT_FACING_ALLEY");
        JsonObject value = new JsonObject();
        value.addProperty("alleySegmentCount", alley.size());
        value.addProperty("alleyConnected", connected);
        value.addProperty("frontagePassed", frontage);
        return value;
    }

    private static JsonObject organic(String groupId, List<Anchor> anchors, List<JsonObject> roads,
                                      List<String> hardBlocks) {
        boolean noFormalRoads = roads.isEmpty();
        boolean gapsConnected = connectedGapGraph(anchors, 1.0, 3.0);
        if (!noFormalRoads) hardBlocks.add(groupId + ": ORGANIC_FORMAL_ROAD_FORBIDDEN");
        if (!gapsConnected) hardBlocks.add(groupId + ": ORGANIC_ONE_TO_THREE_BLOCK_GAPS_DISCONNECTED");
        JsonObject value = new JsonObject();
        value.addProperty("formalRoadAbsent", noFormalRoads);
        value.addProperty("oneToThreeBlockGapGraphConnected", gapsConnected);
        return value;
    }

    private static boolean connectedRoads(List<JsonObject> roads) {
        if (roads.isEmpty()) return false;
        List<BlockBounds> bounds = roads.stream().map(road -> CityStructureCandidateEnvelope.bounds(
                road.getAsJsonObject("bounds"))).toList();
        return connected(bounds.size(), (first, second) -> edgeGap(bounds.get(first), bounds.get(second)) <= 0.0);
    }

    private static boolean connectedGapGraph(List<Anchor> anchors, double minimum, double maximum) {
        if (anchors.size() <= 1) return true;
        return connected(anchors.size(), (first, second) -> {
            double gap = edgeGap(anchors.get(first).collision(), anchors.get(second).collision());
            return gap >= minimum && gap <= maximum;
        });
    }

    private static boolean connected(int count, Edge edge) {
        if (count == 0) return false;
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            if (!visited.add(current)) continue;
            for (int next = 0; next < count; next++) {
                if (!visited.contains(next) && edge.connected(current, next)) queue.addLast(next);
            }
        }
        return visited.size() == count;
    }

    private static double edgeGap(BlockBounds first, BlockBounds second) {
        int dx = axisGap(first.minX(), first.maxX(), second.minX(), second.maxX());
        int dz = axisGap(first.minZ(), first.maxZ(), second.minZ(), second.maxZ());
        return Math.hypot(dx, dz);
    }

    private static int axisGap(int firstMin, int firstMax, int secondMin, int secondMax) {
        if (firstMax < secondMin) return Math.max(0, secondMin - firstMax - 1);
        if (secondMax < firstMin) return Math.max(0, firstMin - secondMax - 1);
        return 0;
    }

    private static boolean contains(BlockBounds bounds, BlockPoint point) {
        return point.x() >= bounds.minX() && point.x() <= bounds.maxX()
                && point.z() >= bounds.minZ() && point.z() <= bounds.maxZ();
    }

    private static JsonObject object(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : new JsonObject();
    }

    private static int intValue(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsInt() : 0;
    }

    private static double doubleValue(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsDouble() : 0.0;
    }

    private static String string(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }

    private record Anchor(String groupId, String algorithm, String phase, BlockPoint point,
                          BlockBounds collision, JsonObject layout) {
        static Anchor parse(JsonObject value) {
            JsonObject layout = object(value, "blueprintLayout");
            return new Anchor(string(value, "placementGroupId"), string(layout, "algorithm"),
                    string(value, "blueprintPlacementPhase"), new BlockPoint(
                    intValue(value.getAsJsonObject("anchorBlock"), "x"),
                    intValue(value.getAsJsonObject("anchorBlock"), "z")),
                    CityStructureCandidateEnvelope.bounds(value.getAsJsonObject("collisionEnvelope")), layout);
        }
    }

    record Result(boolean passed, List<String> hardBlocks, JsonObject json) {
    }

    @FunctionalInterface
    private interface Edge {
        boolean connected(int first, int second);
    }
}
