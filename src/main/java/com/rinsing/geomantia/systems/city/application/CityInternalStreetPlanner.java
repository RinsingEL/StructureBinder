package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Freezes internal array roads as straight segments consumed by preview, D6, and Foundation. */
final class CityInternalStreetPlanner {
    List<JsonObject> plan(String groupId,
                          String algorithm,
                          CityBlueprintGroupLayoutPlanner.Parameters parameters,
                          List<JsonObject> anchors,
                          boolean centerAxisStreetEnabled) {
        List<Anchor> all = anchors.stream()
                .filter(anchor -> !"connectivity_growth".equals(string(anchor, "blueprintPlacementPhase")))
                .map(Anchor::parse)
                .toList();
        List<Anchor> internal = all.stream().filter(anchor -> groupId.equals(
                string(anchor.source(), "placementGroupId"))).toList();
        if (internal.isEmpty()) return List.of();
        return switch (algorithm) {
            case "GRID" -> grid(groupId, parameters, internal);
            case "COURTYARD" -> courtyard(groupId, parameters, internal);
            case "COMPACT" -> compact(groupId, parameters, internal, all);
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
        BlockBounds usedExtent = union(anchors.stream().map(Anchor::collision).toList());
        int narrow = Math.max(1, parameters.streetBandWidthBlocks() / 2);
        List<JsonObject> roads = new ArrayList<>();
        List<Integer> rowKeys = new ArrayList<>(rows.keySet());
        for (int index = 0; index + 1 < rowKeys.size(); index++) {
            int width = (index & 1) == 0 ? parameters.streetBandWidthBlocks() : narrow;
            List<Anchor> first = rows.get(rowKeys.get(index));
            List<Anchor> second = rows.get(rowKeys.get(index + 1));
            Gap gap = collisionGap(first, second, true);
            if (gap.availableBlocks() < width + 2) continue;
            roads.add(segment(groupId, "GRID_STREET_NETWORK",
                    width == parameters.streetBandWidthBlocks() ? "GRID_MAIN_STREET" : "GRID_ROW_LANE",
                    roads.size(), width, new BlockPoint(gap.center(), usedExtent.minZ()),
                    new BlockPoint(gap.center(), usedExtent.maxZ())));
        }
        List<Integer> columnKeys = new ArrayList<>(columns.keySet());
        for (int index = 0; index + 1 < columnKeys.size(); index++) {
            List<Anchor> first = columns.get(columnKeys.get(index));
            List<Anchor> second = columns.get(columnKeys.get(index + 1));
            Gap gap = collisionGap(first, second, false);
            if (gap.availableBlocks() < narrow + 2) continue;
            roads.add(segment(groupId, "GRID_STREET_NETWORK", "GRID_COLUMN_LANE", roads.size(), narrow,
                    new BlockPoint(usedExtent.minX(), gap.center()),
                    new BlockPoint(usedExtent.maxX(), gap.center())));
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
        int half = Math.max(parameters.streetBandWidthBlocks() + 2, pitch / 2 - 1);
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
                                            List<Anchor> anchors,
                                            List<Anchor> allAnchors) {
        List<LaneTarget> lane = anchors.stream()
                .filter(anchor -> anchor.layout().has("compactLaneTarget"))
                .map(anchor -> new LaneTarget(compactRoadPoint(anchor,
                        parameters.streetBandWidthBlocks() + 2, allAnchors,
                        parameters.maximumEdgeGapBlocks())))
                .distinct()
                .sorted(Comparator.comparingInt((LaneTarget target) -> target.point().x())
                        .thenComparingInt(target -> target.point().z()))
                .toList();
        if (lane.size() < 2) return List.of();
        List<JsonObject> roads = new ArrayList<>();
        List<LaneTarget> connected = new ArrayList<>();
        List<LaneTarget> remaining = new ArrayList<>(lane);
        connected.add(remaining.remove(0));
        while (!remaining.isEmpty()) {
            LaneTarget selected = null;
            List<BlockPoint> selectedPath = List.of();
            double selectedDistance = Double.POSITIVE_INFINITY;
            for (LaneTarget from : connected) {
                for (LaneTarget target : remaining) {
                    List<BlockPoint> path = compactPath(from.point(), target.point(),
                            parameters.streetBandWidthBlocks() + 2,
                            allAnchors, parameters.maximumEdgeGapBlocks());
                    if (path.size() < 2) continue;
                    double distance = Math.hypot(from.point().x() - target.point().x(),
                            from.point().z() - target.point().z());
                    if (distance < selectedDistance) {
                        selected = target;
                        selectedPath = path;
                        selectedDistance = distance;
                    }
                }
            }
            if (selected == null) {
                break;
            }
            for (int pathIndex = 0; pathIndex + 1 < selectedPath.size(); pathIndex++) {
                roads.add(segment(groupId, "COMPACT_ALLEY_NETWORK", "COMPACT_ALLEY",
                        roads.size(), parameters.streetBandWidthBlocks(), selectedPath.get(pathIndex),
                        selectedPath.get(pathIndex + 1)));
            }
            remaining.remove(selected);
            connected.add(selected);
        }
        return List.copyOf(roads);
    }

    private static BlockPoint compactRoadPoint(Anchor anchor, int width, List<Anchor> allAnchors,
                                               int maximumDistance) {
        JsonObject placement = object(anchor.source(), "templatePlacementPlan");
        JsonObject transformed = object(placement, "transformed");
        if (transformed.has("roadEntrances") && transformed.get("roadEntrances").isJsonArray()
                && !transformed.getAsJsonArray("roadEntrances").isEmpty()
                && transformed.getAsJsonArray("roadEntrances").get(0).isJsonObject()) {
            JsonObject entrance = transformed.getAsJsonArray("roadEntrances").get(0).getAsJsonObject();
            JsonObject position = object(entrance, "worldPosition");
            if (position.size() > 0) {
                BlockPoint point = point(position);
                String direction = string(entrance, "direction");
                int firstDistance = width / 2 + 1;
                for (int distance = firstDistance;
                     distance <= firstDistance + maximumDistance; distance++) {
                    BlockPoint candidate = switch (direction) {
                        case "NORTH" -> new BlockPoint(point.x(), point.z() - distance);
                        case "EAST" -> new BlockPoint(point.x() + distance, point.z());
                        case "SOUTH" -> new BlockPoint(point.x(), point.z() + distance);
                        case "WEST" -> new BlockPoint(point.x() - distance, point.z());
                        default -> point;
                    };
                    if (!blocked(candidate, width, allAnchors)) return candidate;
                }
                return point;
            }
        }
        return point(anchor.layout().getAsJsonObject("compactLaneTarget"));
    }

    private static List<BlockPoint> compactPath(BlockPoint start, BlockPoint target, int width,
                                                List<Anchor> anchors, int margin) {
        BlockBounds search = expand(union(anchors.stream().map(Anchor::body).toList()),
                Math.max(8, margin));
        ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
        Map<BlockPoint, BlockPoint> previous = new HashMap<>();
        Set<BlockPoint> visited = new HashSet<>();
        if (blocked(start, width, anchors) || blocked(target, width, anchors)) return List.of();
        queue.add(start);
        visited.add(start);
        int[][] directions = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        while (!queue.isEmpty()) {
            BlockPoint current = queue.removeFirst();
            if (current.equals(target)) break;
            for (int[] direction : directions) {
                BlockPoint next = new BlockPoint(current.x() + direction[0], current.z() + direction[1]);
                if (!search.contains(next.x(), next.z()) || !visited.add(next)) continue;
                if (blocked(next, width, anchors)) continue;
                previous.put(next, current);
                queue.addLast(next);
            }
        }
        if (!visited.contains(target)) return List.of();
        List<BlockPoint> reverse = new ArrayList<>();
        BlockPoint current = target;
        while (current != null) {
            reverse.add(current);
            if (current.equals(start)) break;
            current = previous.get(current);
        }
        java.util.Collections.reverse(reverse);
        List<BlockPoint> compressed = new ArrayList<>();
        for (BlockPoint point : reverse) {
            if (compressed.size() >= 2) {
                BlockPoint first = compressed.get(compressed.size() - 2);
                BlockPoint second = compressed.get(compressed.size() - 1);
                if (first.x() == second.x() && second.x() == point.x()
                        || first.z() == second.z() && second.z() == point.z()) {
                    compressed.set(compressed.size() - 1, point);
                    continue;
                }
            }
            compressed.add(point);
        }
        return List.copyOf(compressed);
    }

    private static boolean blocked(BlockPoint point, int width, List<Anchor> anchors) {
        int lower = (width - 1) / 2;
        int upper = width / 2;
        BlockBounds clearance = new BlockBounds(point.x() - lower, point.z() - lower,
                point.x() + upper, point.z() + upper);
        return anchors.stream().anyMatch(anchor -> clearance.overlaps(anchor.body()));
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static List<JsonObject> centerAxis(String groupId,
                                               CityBlueprintGroupLayoutPlanner.Parameters parameters,
                                               List<Anchor> anchors) {
        Anchor center = anchors.stream().filter(anchor -> "required".equals(anchor.phase()))
                .findFirst().orElse(anchors.get(0));
        BlockBounds extent = union(anchors.stream().map(Anchor::collision).toList());
        BlockBounds core = center.collision();
        int x = (core.minX() + core.maxX()) / 2;
        int z = (core.minZ() + core.maxZ()) / 2;
        List<Anchor> otherAnchors = anchors.stream().filter(anchor -> anchor != center).toList();
        List<JsonObject> vertical = new ArrayList<>();
        if (extent.minZ() < core.minZ()) {
            vertical.add(segment(groupId, "CENTER_AXIS_STREET", "CENTER_AXIS_NORTH", vertical.size(),
                    parameters.streetBandWidthBlocks(), new BlockPoint(x, extent.minZ()),
                    new BlockPoint(x, core.minZ() - 1)));
        }
        if (core.maxZ() < extent.maxZ()) {
            vertical.add(segment(groupId, "CENTER_AXIS_STREET", "CENTER_AXIS_SOUTH", vertical.size(),
                    parameters.streetBandWidthBlocks(), new BlockPoint(x, core.maxZ() + 1),
                    new BlockPoint(x, extent.maxZ())));
        }
        if (vertical.size() == 2 && roadsClear(vertical, otherAnchors)) return List.copyOf(vertical);

        List<JsonObject> horizontal = new ArrayList<>();
        if (extent.minX() < core.minX()) {
            horizontal.add(segment(groupId, "CENTER_AXIS_STREET", "CENTER_AXIS_WEST", horizontal.size(),
                    parameters.streetBandWidthBlocks(), new BlockPoint(extent.minX(), z),
                    new BlockPoint(core.minX() - 1, z)));
        }
        if (core.maxX() < extent.maxX()) {
            horizontal.add(segment(groupId, "CENTER_AXIS_STREET", "CENTER_AXIS_EAST", horizontal.size(),
                    parameters.streetBandWidthBlocks(), new BlockPoint(core.maxX() + 1, z),
                    new BlockPoint(extent.maxX(), z)));
        }
        return horizontal.size() == 2 && roadsClear(horizontal, otherAnchors)
                ? List.copyOf(horizontal) : List.of();
    }

    private static boolean roadsClear(List<JsonObject> roads, List<Anchor> anchors) {
        for (JsonObject road : roads) {
            BlockBounds surface = CityStructureCandidateEnvelope.bounds(road.getAsJsonObject("bounds"));
            BlockBounds crossSection = intValue(road, "axisX", 0) != 0
                    ? new BlockBounds(surface.minX(), surface.minZ() - 1,
                    surface.maxX(), surface.maxZ() + 1)
                    : new BlockBounds(surface.minX() - 1, surface.minZ(),
                    surface.maxX() + 1, surface.maxZ());
            if (anchors.stream().map(Anchor::body).anyMatch(crossSection::overlaps)) return false;
        }
        return true;
    }

    private static Map<Integer, List<Anchor>> grouped(List<Anchor> anchors, String key) {
        Map<Integer, List<Anchor>> result = new TreeMap<>();
        for (Anchor anchor : anchors) {
            if (!anchor.layout().has(key)) continue;
            result.computeIfAbsent(anchor.layout().get(key).getAsInt(), ignored -> new ArrayList<>()).add(anchor);
        }
        return result;
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
        BlockBounds bounds = segmentBounds(width, start, end);
        JsonObject value = new JsonObject();
        value.addProperty("schemaVersion", "city_internal_street_band.v0.2");
        value.addProperty("streetBandId", groupId + "::" + networkId + "::segment_"
                + String.format("%03d", segmentIndex + 1));
        value.addProperty("roadNetworkId", groupId + "::" + networkId);
        value.addProperty("roadKind", roadKind);
        value.addProperty("segmentIndex", segmentIndex);
        value.addProperty("groupId", groupId);
        value.addProperty("geometryMode", "STRAIGHT_AXIS_CLIPPED_BY_TERRAIN");
        value.addProperty("widthBlocks", width);
        value.addProperty("surfacePolicy", "FOLLOW_TERRAIN_STEP_GRADED");
        value.addProperty("crossSectionProfile", "STAIR_SLAB_STAIR");
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

    private static BlockBounds segmentBounds(int width, BlockPoint start, BlockPoint end) {
        int lowerHalf = (width - 1) / 2;
        int upperHalf = width / 2;
        if (start.x() == end.x()) {
            return new BlockBounds(start.x() - lowerHalf, Math.min(start.z(), end.z()),
                    start.x() + upperHalf, Math.max(start.z(), end.z()));
        }
        return new BlockBounds(Math.min(start.x(), end.x()), start.z() - lowerHalf,
                Math.max(start.x(), end.x()), start.z() + upperHalf);
    }

    private static Gap collisionGap(List<Anchor> first, List<Anchor> second, boolean xAxis) {
        int firstMax = first.stream().map(Anchor::collision)
                .mapToInt(bounds -> xAxis ? bounds.maxX() : bounds.maxZ()).max().orElseThrow();
        int secondMin = second.stream().map(Anchor::collision)
                .mapToInt(bounds -> xAxis ? bounds.minX() : bounds.minZ()).min().orElseThrow();
        int freeMin = firstMax + 1;
        int freeMax = secondMin - 1;
        return new Gap(midpoint(freeMin, freeMax), Math.max(0, freeMax - freeMin + 1));
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

    private static JsonObject object(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonObject()
                ? value.getAsJsonObject(key) : new JsonObject();
    }

    private static int intValue(JsonObject value, String key, int fallback) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsInt() : fallback;
    }

    private record Anchor(String anchorId, String phase, BlockBounds body, BlockBounds collision,
                          JsonObject layout, JsonObject source) {
        static Anchor parse(JsonObject value) {
            return new Anchor(string(value, "anchorId"), string(value, "blueprintPlacementPhase"),
                    bodyBounds(value),
                    CityStructureCandidateEnvelope.bounds(value.getAsJsonObject("collisionEnvelope")),
                    value.getAsJsonObject("blueprintLayout"), value);
        }
    }

    private static BlockBounds bodyBounds(JsonObject value) {
        for (String key : List.of("actualFootprint", "plannedFootprint", "bodyEnvelope")) {
            if (value.has(key) && value.get(key).isJsonObject()) {
                return CityStructureCandidateEnvelope.bounds(value.getAsJsonObject(key));
            }
        }
        return CityStructureCandidateEnvelope.bounds(value.getAsJsonObject("collisionEnvelope"));
    }

    private record Gap(int center, int availableBlocks) {
    }

    private record LaneTarget(BlockPoint point) {
    }
}
