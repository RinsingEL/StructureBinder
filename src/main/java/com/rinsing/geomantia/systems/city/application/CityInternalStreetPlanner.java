package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Freezes internal array roads as straight segments consumed by preview, D6, and Foundation. */
final class CityInternalStreetPlanner {
    List<JsonObject> plan(String groupId,
                          String algorithm,
                          CityBlueprintGroupLayoutPlanner.Parameters parameters,
                          List<JsonObject> anchors,
                          boolean centerAxisStreetEnabled) {
        List<Anchor> internal = anchors.stream()
                .filter(anchor -> groupId.equals(string(anchor, "placementGroupId")))
                .filter(anchor -> !"connectivity_growth".equals(string(anchor, "blueprintPlacementPhase")))
                .map(Anchor::parse)
                .toList();
        if (internal.isEmpty()) return List.of();
        return switch (algorithm) {
            case "GRID" -> grid(groupId, parameters, internal);
            case "COURTYARD" -> courtyard(groupId, parameters, internal);
            case "COMPACT" -> compact(groupId, parameters, internal);
            case "CENTER_SYMMETRIC" -> centerAxisStreetEnabled
                    ? centerAxis(groupId, parameters, internal) : List.of();
            default -> List.of();
        };
    }

    private static List<JsonObject> grid(String groupId,
                                         CityBlueprintGroupLayoutPlanner.Parameters parameters,
                                         List<Anchor> anchors) {
        Map<Integer, List<Anchor>> rows = grouped(anchors, "gridRow");
        Map<Integer, List<Anchor>> columns = grouped(anchors, "gridColumn");
        BlockBounds extent = union(anchors.stream().map(Anchor::collision).toList());
        int narrow = Math.max(1, parameters.streetBandWidthBlocks() / 2);
        List<JsonObject> roads = new ArrayList<>();
        List<Integer> rowKeys = new ArrayList<>(rows.keySet());
        for (int index = 0; index + 1 < rowKeys.size(); index++) {
            int x = midpoint(theoreticalCoordinate(rows.get(rowKeys.get(index)), true),
                    theoreticalCoordinate(rows.get(rowKeys.get(index + 1)), true));
            int width = (index & 1) == 0 ? parameters.streetBandWidthBlocks() : narrow;
            roads.add(segment(groupId, "GRID_STREET_NETWORK",
                    width == parameters.streetBandWidthBlocks() ? "GRID_MAIN_STREET" : "GRID_ROW_LANE",
                    roads.size(), width, new BlockPoint(x, extent.minZ()),
                    new BlockPoint(x, extent.maxZ())));
        }
        List<Integer> columnKeys = new ArrayList<>(columns.keySet());
        for (int index = 0; index + 1 < columnKeys.size(); index++) {
            int z = midpoint(theoreticalCoordinate(columns.get(columnKeys.get(index)), false),
                    theoreticalCoordinate(columns.get(columnKeys.get(index + 1)), false));
            roads.add(segment(groupId, "GRID_STREET_NETWORK", "GRID_COLUMN_LANE", roads.size(), narrow,
                    new BlockPoint(extent.minX(), z), new BlockPoint(extent.maxX(), z)));
        }
        return List.copyOf(roads);
    }

    private static List<JsonObject> courtyard(String groupId,
                                              CityBlueprintGroupLayoutPlanner.Parameters parameters,
                                              List<Anchor> anchors) {
        JsonObject centerJson = anchors.stream().map(Anchor::layout)
                .filter(layout -> layout.has("courtyardCenter"))
                .map(layout -> layout.getAsJsonObject("courtyardCenter"))
                .findFirst().orElse(null);
        if (centerJson == null) return List.of();
        BlockPoint center = point(centerJson);
        int pitch = anchors.stream().mapToInt(anchor -> intValue(anchor.layout(), "spacingBlocks", 1))
                .max().orElse(1);
        int half = Math.max(parameters.streetBandWidthBlocks() + 1, pitch / 2);
        int gateHalf = Math.max(1, parameters.streetBandWidthBlocks() / 2);
        int width = parameters.streetBandWidthBlocks();
        List<JsonObject> roads = new ArrayList<>();
        roads.add(segment(groupId, "COURTYARD_RING_NETWORK", "COURTYARD_RING_NORTH", roads.size(), width,
                new BlockPoint(center.x() - half, center.z() - half),
                new BlockPoint(center.x() + half, center.z() - half)));
        roads.add(segment(groupId, "COURTYARD_RING_NETWORK", "COURTYARD_RING_EAST", roads.size(), width,
                new BlockPoint(center.x() + half, center.z() - half),
                new BlockPoint(center.x() + half, center.z() + half)));
        roads.add(segment(groupId, "COURTYARD_RING_NETWORK", "COURTYARD_RING_WEST", roads.size(), width,
                new BlockPoint(center.x() - half, center.z() - half),
                new BlockPoint(center.x() - half, center.z() + half)));
        roads.add(segment(groupId, "COURTYARD_RING_NETWORK", "COURTYARD_RING_SOUTH_WEST", roads.size(), width,
                new BlockPoint(center.x() - half, center.z() + half),
                new BlockPoint(center.x() - gateHalf, center.z() + half)));
        roads.add(segment(groupId, "COURTYARD_RING_NETWORK", "COURTYARD_RING_SOUTH_EAST", roads.size(), width,
                new BlockPoint(center.x() + gateHalf, center.z() + half),
                new BlockPoint(center.x() + half, center.z() + half)));
        roads.add(segment(groupId, "COURTYARD_RING_NETWORK", "COURTYARD_GATE", roads.size(), width,
                new BlockPoint(center.x(), center.z() + half),
                new BlockPoint(center.x(), center.z() + pitch)));
        return List.copyOf(roads);
    }

    private static List<JsonObject> compact(String groupId,
                                            CityBlueprintGroupLayoutPlanner.Parameters parameters,
                                            List<Anchor> anchors) {
        List<BlockPoint> lane = anchors.stream().map(Anchor::layout)
                .filter(layout -> layout.has("compactLaneTarget"))
                .map(layout -> point(layout.getAsJsonObject("compactLaneTarget")))
                .distinct()
                .sorted(Comparator.comparingInt(BlockPoint::x).thenComparingInt(BlockPoint::z))
                .toList();
        if (lane.size() < 2) return List.of();
        List<JsonObject> roads = new ArrayList<>();
        for (int index = 0; index + 1 < lane.size(); index++) {
            BlockPoint first = lane.get(index);
            BlockPoint second = lane.get(index + 1);
            BlockPoint elbow = new BlockPoint(second.x(), first.z());
            if (!first.equals(elbow)) {
                roads.add(segment(groupId, "COMPACT_ALLEY_NETWORK", "COMPACT_ALLEY",
                        roads.size(), parameters.streetBandWidthBlocks(), first, elbow));
            }
            if (!elbow.equals(second)) {
                roads.add(segment(groupId, "COMPACT_ALLEY_NETWORK", "COMPACT_ALLEY",
                        roads.size(), parameters.streetBandWidthBlocks(), elbow, second));
            }
        }
        return List.copyOf(roads);
    }

    private static List<JsonObject> centerAxis(String groupId,
                                               CityBlueprintGroupLayoutPlanner.Parameters parameters,
                                               List<Anchor> anchors) {
        Anchor center = anchors.stream().filter(anchor -> "required".equals(anchor.phase()))
                .findFirst().orElse(anchors.get(0));
        BlockBounds extent = union(anchors.stream().map(Anchor::collision).toList());
        BlockBounds core = center.collision();
        int x = (core.minX() + core.maxX()) / 2;
        List<JsonObject> roads = new ArrayList<>();
        if (extent.minZ() < core.minZ()) {
            roads.add(segment(groupId, "CENTER_AXIS_STREET", "CENTER_AXIS_NORTH", roads.size(),
                    parameters.streetBandWidthBlocks(), new BlockPoint(x, extent.minZ()),
                    new BlockPoint(x, core.minZ() - 1)));
        }
        if (core.maxZ() < extent.maxZ()) {
            roads.add(segment(groupId, "CENTER_AXIS_STREET", "CENTER_AXIS_SOUTH", roads.size(),
                    parameters.streetBandWidthBlocks(), new BlockPoint(x, core.maxZ() + 1),
                    new BlockPoint(x, extent.maxZ())));
        }
        return List.copyOf(roads);
    }

    private static Map<Integer, List<Anchor>> grouped(List<Anchor> anchors, String key) {
        Map<Integer, List<Anchor>> result = new TreeMap<>();
        for (Anchor anchor : anchors) {
            if (!anchor.layout().has(key)) continue;
            result.computeIfAbsent(anchor.layout().get(key).getAsInt(), ignored -> new ArrayList<>()).add(anchor);
        }
        return result;
    }

    private static int theoreticalCoordinate(List<Anchor> anchors, boolean x) {
        return (int) Math.round(anchors.stream().map(Anchor::layout)
                .map(layout -> layout.getAsJsonObject("theoreticalAnchor"))
                .mapToInt(point -> point.get(x ? "x" : "z").getAsInt()).average().orElse(0.0));
    }

    private static JsonObject segment(String groupId,
                                      String networkId,
                                      String roadKind,
                                      int segmentIndex,
                                      int width,
                                      BlockPoint start,
                                      BlockPoint end) {
        if (start.x() != end.x() && start.z() != end.z()) {
            throw new IllegalArgumentException("CITY_INTERNAL_STREET_SEGMENT_NOT_AXIS_ALIGNED");
        }
        int half = width / 2;
        BlockBounds bounds = new BlockBounds(Math.min(start.x(), end.x()) - half,
                Math.min(start.z(), end.z()) - half, Math.max(start.x(), end.x()) + half,
                Math.max(start.z(), end.z()) + half);
        JsonObject value = new JsonObject();
        value.addProperty("schemaVersion", "city_internal_street_band.v0.1");
        value.addProperty("streetBandId", groupId + "::" + networkId + "::segment_"
                + String.format("%03d", segmentIndex + 1));
        value.addProperty("roadNetworkId", groupId + "::" + networkId);
        value.addProperty("roadKind", roadKind);
        value.addProperty("segmentIndex", segmentIndex);
        value.addProperty("groupId", groupId);
        value.addProperty("geometryMode", "STRAIGHT_AXIS_CLIPPED_BY_TERRAIN");
        value.addProperty("widthBlocks", width);
        value.addProperty("surfacePolicy", "FOLLOW_TERRAIN_NO_LEVEL");
        value.addProperty("hardSkeleton", true);
        value.addProperty("axisX", Integer.compare(end.x(), start.x()));
        value.addProperty("axisZ", Integer.compare(end.z(), start.z()));
        value.add("start", start.asJson());
        value.add("end", end.asJson());
        value.add("bounds", CityStructureCandidateEnvelope.boundsJson(bounds));
        value.add("platformBounds", CityStructureCandidateEnvelope.boundsJson(bounds));
        value.addProperty("platformPolicy", "LOCAL_HARD_SKELETON");
        return value;
    }

    private static int midpoint(int first, int second) {
        return (int) Math.round((first + second) / 2.0);
    }

    private static BlockBounds union(List<BlockBounds> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("CITY_INTERNAL_STREET_ANCHORS_REQUIRED");
        BlockBounds result = values.get(0);
        for (int index = 1; index < values.size(); index++) {
            BlockBounds next = values.get(index);
            result = new BlockBounds(Math.min(result.minX(), next.minX()),
                    Math.min(result.minZ(), next.minZ()), Math.max(result.maxX(), next.maxX()),
                    Math.max(result.maxZ(), next.maxZ()));
        }
        return result;
    }

    private static BlockPoint point(JsonObject value) {
        return new BlockPoint(value.get("x").getAsInt(), value.get("z").getAsInt());
    }

    private static String string(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }

    private static int intValue(JsonObject value, String key, int fallback) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsInt() : fallback;
    }

    private record Anchor(String anchorId, String phase, BlockBounds collision, JsonObject layout) {
        static Anchor parse(JsonObject value) {
            return new Anchor(string(value, "anchorId"), string(value, "blueprintPlacementPhase"),
                    CityStructureCandidateEnvelope.bounds(value.getAsJsonObject("collisionEnvelope")),
                    value.getAsJsonObject("blueprintLayout"));
        }
    }
}
