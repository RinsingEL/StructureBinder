package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Freezes internal array roads as straight segments consumed by preview, D6, and Foundation. */
final class CityInternalStreetPlanner {
    List<JsonObject> planSkeleton(String groupId,
                                  String algorithm,
                                  CityBlueprintGroupLayoutPlanner.Parameters parameters,
                                  List<JsonObject> requiredAnchors,
                                  boolean centerAxisStreetEnabled,
                                  int plannedStructureCount,
                                  int plannedSpanBlocks,
                                  CityBlueprintGroupLayoutPlanner.Frame frame) {
        List<Anchor> anchors = requiredAnchors.stream()
                .filter(anchor -> groupId.equals(string(anchor, "placementGroupId")))
                .map(Anchor::parse)
                .toList();
        if (anchors.isEmpty()) return List.of();
        List<JsonObject> planned = switch (algorithm) {
            case "GRID" -> gridSkeleton(groupId, parameters, anchors,
                    Math.max(4, plannedStructureCount));
            case "COURTYARD" -> courtyard(groupId, parameters, anchors);
            case "LINEAR" -> linearSkeleton(groupId, parameters, anchors,
                    Math.max(2, plannedStructureCount), plannedSpanBlocks, frame);
            case "COMPACT" -> compactSkeleton(groupId, parameters, anchors,
                    Math.max(3, plannedStructureCount), plannedSpanBlocks, frame);
            case "CENTER_SYMMETRIC" -> centerAxisStreetEnabled
                    ? centerSkeleton(groupId, parameters, anchors, plannedSpanBlocks, frame) : List.of();
            default -> List.of();
        };
        planned = CityStreetObstacleRouter.repair(planned, requiredAnchors);
        planned.forEach(CityInternalStreetPlanner::markReservedSkeleton);
        return List.copyOf(planned);
    }

    Finalization finalizeSkeleton(List<JsonObject> skeletonBands,
                                  List<JsonObject> mainRoadBands,
                                  List<JsonObject> anchors) {
        List<Anchor> parsed = anchors.stream().map(Anchor::parse).toList();
        List<JsonObject> allSkeleton = new ArrayList<>();
        skeletonBands.forEach(band -> allSkeleton.add(band.deepCopy()));
        List<JsonObject> retained = new ArrayList<>();
        com.google.gson.JsonArray removedIds = new com.google.gson.JsonArray();
        for (JsonObject band : allSkeleton) {
            List<Entrance> served = parsed.stream().flatMap(anchor -> entrances(anchor).stream())
                    .filter(entrance -> distanceToBand(entrance.point(), band) <= serviceDistance(band))
                    .toList();
            boolean districtFrontage = parsed.stream().anyMatch(anchor -> anchor.groupId().equals(
                    string(band, "groupId"))) && ("LINEAR_STREET_BAND".equals(string(band, "roadKind"))
                    || parsed.stream().filter(anchor -> anchor.groupId().equals(string(band, "groupId")))
                    .map(Anchor::layout).filter(layout -> layout.has("frontageTarget"))
                    .map(layout -> point(layout.getAsJsonObject("frontageTarget")))
                    .anyMatch(frontage -> distanceToBand(frontage, band) <= serviceDistance(band) + 1));
            long junctions = allSkeleton.stream().filter(other -> other != band)
                    .filter(other -> bounds(other).overlaps(bounds(band))).count()
                    + mainRoadBands.stream().filter(other -> bounds(other).overlaps(bounds(band))).count();
            if (served.isEmpty() && junctions == 0 && !districtFrontage) {
                removedIds.add(string(band, "streetBandId"));
                continue;
            }
            band.addProperty("planningPhase", "FINAL_NETWORK_AFTER_BUILDING_USE_REVIEW");
            band.addProperty("usageReview", served.isEmpty()
                    ? districtFrontage ? "SERVES_DISTRICT_FRONTAGE" : "SHARED_TRANSIT_JUNCTION"
                    : "SERVES_REAL_ENTRANCE");
            com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
            served.stream().map(Entrance::id).distinct().sorted().forEach(ids::add);
            band.add("servedEntranceIds", ids);
            band.addProperty("junctionCount", junctions);
            retained.add(band);
        }

        List<JsonObject> shortAlleys = new ArrayList<>();
        List<JsonObject> networkExtensions = new ArrayList<>();
        com.google.gson.JsonArray accessOutcomes = new com.google.gson.JsonArray();
        List<JsonObject> network = new ArrayList<>(retained);
        network.addAll(mainRoadBands);
        for (Anchor anchor : parsed) {
            for (Entrance entrance : entrances(anchor)) {
                List<JsonObject> groupNetwork = network.stream()
                        .filter(band -> anchor.groupId().equals(string(band, "groupId"))
                                || "MAIN".equals(string(band, "roadHierarchy"))
                                || anchor.groupId().equals(string(band, "sourceGroupId"))
                                || anchor.groupId().equals(string(band, "targetGroupId")))
                        .toList();
                BlockPoint doorstep = outsideBody(entrance.point(), entrance.direction(), anchor.body(), 1);
                if (roadPointClear(doorstep, 1, parsed) && groupNetwork.stream()
                        .anyMatch(band -> bounds(band).contains(doorstep.x(), doorstep.z()))) {
                    accessOutcomes.add(accessOutcome(entrance, "CONNECTED_TO_SHARED_SKELETON", ""));
                    continue;
                }
                Alley alley = shortestLegalAlley(entrance, anchor, parsed, groupNetwork);
                if (alley == null) {
                    Alley extension = shortestLegalNetworkExtension(entrance, anchor, parsed, groupNetwork);
                    if (extension == null) {
                        accessOutcomes.add(accessOutcome(entrance, "UNRESOLVED",
                                "CITY_INTERNAL_STREET_ENTRANCE_NETWORK_EXTENSION_UNAVAILABLE"));
                        continue;
                    }
                    addDoorstepApproach(anchor, entrance, extension, networkExtensions);
                    int baseIndex = networkExtensions.size();
                    for (int index = 0; index + 1 < extension.path().size(); index++) {
                        JsonObject band = segment(anchor.groupId(), "USAGE_REVIEW_EXTENSIONS",
                                "SHARED_NETWORK_EXTENSION", baseIndex + index, extension.width(),
                                extension.path().get(index), extension.path().get(index + 1));
                        band.addProperty("hardSkeleton", false);
                        band.addProperty("reservedBeforeFill", false);
                        band.addProperty("planningPhase", "FINAL_SHARED_NETWORK_REROUTE");
                        band.addProperty("usageReview", "EXTENDS_SHARED_NETWORK_TO_REMOTE_ENTRANCE");
                        com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
                        ids.add(entrance.id());
                        band.add("servedEntranceIds", ids);
                        networkExtensions.add(band);
                    }
                    network.addAll(networkExtensions.subList(baseIndex, networkExtensions.size()));
                    accessOutcomes.add(accessOutcome(entrance, "CONNECTED_BY_SHARED_EXTENSION", ""));
                    continue;
                }
                addDoorstepApproach(anchor, entrance, alley, shortAlleys);
                int baseIndex = shortAlleys.size();
                for (int index = 0; index + 1 < alley.path().size(); index++) {
                    JsonObject band = segment(anchor.groupId(), "ENTRANCE_SHORT_ALLEYS",
                            "ENTRANCE_SHORT_ALLEY", baseIndex + index, alley.width(),
                            alley.path().get(index), alley.path().get(index + 1));
                    band.addProperty("hardSkeleton", false);
                    band.addProperty("reservedBeforeFill", false);
                    band.addProperty("planningPhase", "FINAL_INDIVIDUAL_ENTRANCE_FALLBACK");
                    band.addProperty("usageReview", "SERVES_SINGLE_NEARBY_ENTRANCE");
                    com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
                    ids.add(entrance.id());
                    band.add("servedEntranceIds", ids);
                    shortAlleys.add(band);
                }
                network.addAll(shortAlleys.subList(baseIndex, shortAlleys.size()));
                accessOutcomes.add(accessOutcome(entrance, "CONNECTED_BY_SHORT_ALLEY", ""));
            }
        }
        int retainedSkeletonCount = retained.size();
        retained.addAll(networkExtensions);
        retained.addAll(shortAlleys);
        JsonObject trace = new JsonObject();
        trace.addProperty("schema", "city_street_first_network_trace");
        trace.addProperty("planningOrder", "CORE_THEN_SHARED_SKELETON_THEN_FILL_THEN_USAGE_REVIEW");
        trace.addProperty("reservedSkeletonSegmentCount", skeletonBands.size());
        trace.addProperty("retainedSkeletonSegmentCount", retainedSkeletonCount);
        trace.addProperty("removedUnusedSegmentCount", removedIds.size());
        trace.addProperty("networkExtensionSegmentCount", networkExtensions.size());
        trace.addProperty("shortAlleySegmentCount", shortAlleys.size());
        trace.add("removedStreetBandIds", removedIds);
        trace.add("accessOutcomes", accessOutcomes);
        return new Finalization(List.copyOf(retained), trace);
    }

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

    private static List<JsonObject> gridSkeleton(String groupId,
                                                 CityBlueprintGroupLayoutPlanner.Parameters parameters,
                                                 List<Anchor> anchors,
                                                 int plannedStructureCount) {
        Anchor core = anchors.stream().filter(anchor -> "required".equals(anchor.phase()))
                .findFirst().orElse(anchors.get(0));
        JsonObject layout = core.layout();
        BlockPoint origin = layout.has("theoreticalAnchor")
                ? point(layout.getAsJsonObject("theoreticalAnchor")) : center(core.collision());
        int pitch = Math.max(1, intValue(layout, "gridPitchBlocks",
                intValue(layout, "spacingBlocks", core.collision().widthBlocks()
                        + parameters.targetEdgeGapBlocks())));
        List<GridOffset> slots = new ArrayList<>();
        for (int index = 0; index < plannedStructureCount; index++) slots.add(squareSpiral(index));
        int minRow = slots.stream().mapToInt(GridOffset::row).min().orElse(0);
        int maxRow = slots.stream().mapToInt(GridOffset::row).max().orElse(1);
        int minColumn = slots.stream().mapToInt(GridOffset::column).min().orElse(0);
        int maxColumn = slots.stream().mapToInt(GridOffset::column).max().orElse(1);
        int lotHalf = Math.max(2, (pitch - parameters.targetEdgeGapBlocks()) / 2);
        int minX = origin.x() + minRow * pitch - lotHalf;
        int maxX = origin.x() + maxRow * pitch + lotHalf;
        int minZ = origin.z() + minColumn * pitch - lotHalf;
        int maxZ = origin.z() + maxColumn * pitch + lotHalf;
        int narrow = Math.max(1, parameters.streetBandWidthBlocks() / 2);
        List<JsonObject> roads = new ArrayList<>();
        for (int row = minRow; row < maxRow; row++) {
            int x = origin.x() + row * pitch + pitch / 2;
            int width = Math.floorMod(row - minRow, 2) == 0
                    ? parameters.streetBandWidthBlocks() : narrow;
            roads.add(segment(groupId, "GRID_BLOCK_SKELETON",
                    width == parameters.streetBandWidthBlocks() ? "GRID_MAIN_STREET" : "GRID_ROW_LANE",
                    roads.size(), width, new BlockPoint(x, minZ), new BlockPoint(x, maxZ)));
        }
        for (int column = minColumn; column < maxColumn; column++) {
            int z = origin.z() + column * pitch + pitch / 2;
            roads.add(segment(groupId, "GRID_BLOCK_SKELETON", "GRID_COLUMN_LANE", roads.size(), narrow,
                    new BlockPoint(minX, z), new BlockPoint(maxX, z)));
        }
        return List.copyOf(roads);
    }

    private static List<JsonObject> linearSkeleton(String groupId,
                                                   CityBlueprintGroupLayoutPlanner.Parameters parameters,
                                                   List<Anchor> anchors,
                                                   int plannedStructureCount,
                                                   int plannedSpanBlocks,
                                                   CityBlueprintGroupLayoutPlanner.Frame frame) {
        Anchor core = anchors.stream().filter(anchor -> "required".equals(anchor.phase()))
                .findFirst().orElse(anchors.get(0));
        JsonObject layout = core.layout();
        int pitch = Math.max(1, intValue(layout, "spacingBlocks",
                core.collision().widthBlocks() + parameters.targetEdgeGapBlocks()));
        int length = Math.max(plannedSpanBlocks, Math.max(1, plannedStructureCount / 2) * pitch);
        BlockPoint start = frame == null ? center(core.collision()) : frame.center();
        int dx = frame == null ? 1 : cardinal(frame.axisX(), frame.axisZ())[0];
        int dz = frame == null ? 0 : cardinal(frame.axisX(), frame.axisZ())[1];
        BlockPoint end = new BlockPoint(start.x() + dx * length, start.z() + dz * length);
        return List.of(segment(groupId, "LINEAR_BLOCK_SKELETON", "LINEAR_STREET_BAND", 0,
                parameters.streetBandWidthBlocks(), start, end));
    }

    private static List<JsonObject> compactSkeleton(String groupId,
                                                    CityBlueprintGroupLayoutPlanner.Parameters parameters,
                                                    List<Anchor> anchors,
                                                    int plannedStructureCount,
                                                    int plannedSpanBlocks,
                                                    CityBlueprintGroupLayoutPlanner.Frame frame) {
        Anchor core = anchors.stream().filter(anchor -> "required".equals(anchor.phase()))
                .findFirst().orElse(anchors.get(0));
        BlockPoint lane = compactRoadPoint(core, parameters.streetBandWidthBlocks(),
                anchors, parameters.maximumEdgeGapBlocks());
        BlockPoint coreCenter = center(core.body());
        int dx = lane.x() - coreCenter.x();
        int dz = lane.z() - coreCenter.z();
        int[] outward = Math.abs(dx) >= Math.abs(dz)
                ? new int[]{dx >= 0 ? 1 : -1, 0}
                : new int[]{0, dz >= 0 ? 1 : -1};
        int tangentX = -outward[1];
        int tangentZ = outward[0];
        int half = Math.max(6, Math.min(Math.max(6, plannedSpanBlocks / 4),
                Math.max(60, parameters.maximumEdgeGapBlocks() * 3)));
        int bend = Math.max(3, parameters.streetBandWidthBlocks() + 1);
        BlockPoint start = new BlockPoint(lane.x() - tangentX * half, lane.z() - tangentZ * half);
        BlockPoint middle = new BlockPoint(lane.x() + tangentX * half, lane.z() + tangentZ * half);
        BlockPoint end = new BlockPoint(middle.x() + outward[0] * bend,
                middle.z() + outward[1] * bend);
        for (int shift = 0; shift <= parameters.maximumEdgeGapBlocks(); shift++) {
            BlockBounds firstBounds = segmentBounds(parameters.streetBandWidthBlocks(), start, middle);
            BlockBounds secondBounds = segmentBounds(parameters.streetBandWidthBlocks(), middle, end);
            if (!firstBounds.overlaps(core.collision()) && !secondBounds.overlaps(core.collision())) break;
            start = new BlockPoint(start.x() + outward[0], start.z() + outward[1]);
            middle = new BlockPoint(middle.x() + outward[0], middle.z() + outward[1]);
            end = new BlockPoint(end.x() + outward[0], end.z() + outward[1]);
        }
        List<JsonObject> roads = new ArrayList<>();
        roads.add(segment(groupId, "COMPACT_BLOCK_SKELETON", "COMPACT_ALLEY", 0,
                parameters.streetBandWidthBlocks(), start, middle));
        roads.add(segment(groupId, "COMPACT_BLOCK_SKELETON", "COMPACT_ALLEY", 1,
                parameters.streetBandWidthBlocks(), middle, end));
        int oppositeBranchLength = Math.max(half,
                parameters.maximumEdgeGapBlocks() + parameters.streetBandWidthBlocks() + 7);
        BlockPoint oppositeEnd = new BlockPoint(middle.x() - outward[0] * oppositeBranchLength,
                middle.z() - outward[1] * oppositeBranchLength);
        roads.add(segment(groupId, "COMPACT_BLOCK_SKELETON", "COMPACT_ALLEY", 2,
                parameters.streetBandWidthBlocks(), middle, oppositeEnd));
        return List.copyOf(roads);
    }

    private static List<JsonObject> centerSkeleton(String groupId,
                                                   CityBlueprintGroupLayoutPlanner.Parameters parameters,
                                                   List<Anchor> anchors,
                                                   int plannedSpanBlocks,
                                                   CityBlueprintGroupLayoutPlanner.Frame frame) {
        Anchor core = anchors.stream().filter(anchor -> "required".equals(anchor.phase()))
                .findFirst().orElse(anchors.get(0));
        int[] axis = frame == null ? new int[]{0, 1} : cardinal(frame.axisX(), frame.axisZ());
        BlockPoint center = center(core.collision());
        int half = Math.max(parameters.streetBandWidthBlocks() + 4, plannedSpanBlocks / 2);
        BlockPoint negative = new BlockPoint(center.x() - axis[0] * half, center.z() - axis[1] * half);
        BlockPoint positive = new BlockPoint(center.x() + axis[0] * half, center.z() + axis[1] * half);
        BlockPoint beforeCore = boundaryPoint(core.body(), center, -axis[0], -axis[1]);
        BlockPoint afterCore = boundaryPoint(core.body(), center, axis[0], axis[1]);
        return List.of(
                segment(groupId, "CENTER_AXIS_BLOCK_SKELETON", "CENTER_AXIS_PRIMARY", 0,
                        parameters.streetBandWidthBlocks(), negative, beforeCore),
                segment(groupId, "CENTER_AXIS_BLOCK_SKELETON", "CENTER_AXIS_PRIMARY", 1,
                        parameters.streetBandWidthBlocks(), afterCore, positive));
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

    private static void markReservedSkeleton(JsonObject band) {
        band.addProperty("planningPhase", "STREET_SKELETON_BEFORE_FILL");
        band.addProperty("reservedBeforeFill", true);
        band.addProperty("usageReview", "PENDING_FINAL_BUILDING_FRONTAGE");
    }

    private static List<Entrance> entrances(Anchor anchor) {
        JsonObject transformed = object(object(anchor.source(), "templatePlacementPlan"), "transformed");
        if (!transformed.has("roadEntrances") || !transformed.get("roadEntrances").isJsonArray()) {
            return List.of();
        }
        List<Entrance> result = new ArrayList<>();
        for (var element : transformed.getAsJsonArray("roadEntrances")) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            JsonObject position = object(value, "worldPosition");
            if (position.size() == 0) continue;
            result.add(new Entrance(anchor.anchorId() + "::" + string(value, "entranceId"),
                    point(position), string(value, "direction"), anchor.groupId()));
        }
        return List.copyOf(result);
    }

    private static int serviceDistance(JsonObject band) {
        return Math.max(2, intValue(band, "widthBlocks", 1) / 2 + 2);
    }

    private static int distanceToBand(BlockPoint point, JsonObject band) {
        JsonObject startJson = object(band, "start");
        JsonObject endJson = object(band, "end");
        if (startJson.size() == 0 || endJson.size() == 0) {
            BlockBounds bounds = bounds(band);
            int x = Math.max(bounds.minX(), Math.min(bounds.maxX(), point.x()));
            int z = Math.max(bounds.minZ(), Math.min(bounds.maxZ(), point.z()));
            return Math.abs(point.x() - x) + Math.abs(point.z() - z);
        }
        BlockPoint start = point(startJson);
        BlockPoint end = point(endJson);
        BlockPoint nearest = nearestPoint(point, start, end);
        return Math.abs(point.x() - nearest.x()) + Math.abs(point.z() - nearest.z());
    }

    private static BlockPoint nearestPoint(BlockPoint point, BlockPoint start, BlockPoint end) {
        if (start.x() == end.x()) {
            return new BlockPoint(start.x(), Math.max(Math.min(start.z(), end.z()),
                    Math.min(Math.max(start.z(), end.z()), point.z())));
        }
        return new BlockPoint(Math.max(Math.min(start.x(), end.x()),
                Math.min(Math.max(start.x(), end.x()), point.x())), start.z());
    }

    private static Alley shortestLegalAlley(Entrance entrance,
                                             Anchor source,
                                             List<Anchor> anchors,
                                             List<JsonObject> network) {
        if (network.isEmpty()) return null;
        List<Alley> candidates = new ArrayList<>();
        for (JsonObject band : network) {
            JsonObject startJson = object(band, "start");
            JsonObject endJson = object(band, "end");
            if (startJson.size() == 0 || endJson.size() == 0) continue;
            int width = Math.max(1, Math.min(3, intValue(band, "widthBlocks", 1)));
            BlockPoint start = outsideBody(entrance.point(), entrance.direction(), source.body(), width);
            if (!entranceApproachClear(entrance, source, start, anchors)) continue;
            BlockPoint target = nearestPoint(start, point(startJson), point(endJson));
            int distance = Math.abs(start.x() - target.x()) + Math.abs(start.z() - target.z());
            if (distance == 0 || distance > 32) continue;
            for (List<BlockPoint> path : orthogonalPaths(start, target)) {
                if (path.size() >= 2 && alleyClear(path, width, anchors)) {
                    candidates.add(new Alley(path, width, distance));
                }
            }
        }
        return candidates.stream().min(Comparator.comparingInt(Alley::length)
                .thenComparing(alley -> alley.path().toString())).orElse(null);
    }

    private static boolean entranceApproachClear(Entrance entrance, Anchor source, BlockPoint start,
                                                  List<Anchor> anchors) {
        BlockPoint doorstep = outsideBody(entrance.point(), entrance.direction(), source.body(), 1);
        return alleyClear(List.of(doorstep, start), 1, anchors);
    }

    private static void addDoorstepApproach(Anchor source, Entrance entrance, Alley alley,
                                            List<JsonObject> bands) {
        BlockPoint doorstep = outsideBody(entrance.point(), entrance.direction(), source.body(), 1);
        BlockPoint start = alley.path().get(0);
        if (doorstep.equals(start)) return;
        JsonObject band = segment(source.groupId(), "ENTRANCE_APPROACH",
                "ENTRANCE_SHORT_ALLEY", bands.size(), 1, doorstep, start);
        band.addProperty("hardSkeleton", false);
        band.addProperty("reservedBeforeFill", false);
        band.addProperty("planningPhase", "FINAL_INDIVIDUAL_ENTRANCE_FALLBACK");
        com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
        ids.add(entrance.id());
        band.add("servedEntranceIds", ids);
        bands.add(band);
    }

    private static List<List<BlockPoint>> orthogonalPaths(BlockPoint start, BlockPoint target) {
        List<List<BlockPoint>> result = new ArrayList<>();
        if (start.x() == target.x() || start.z() == target.z()) {
            result.add(List.of(start, target));
        } else {
            result.add(List.of(start, new BlockPoint(target.x(), start.z()), target));
            result.add(List.of(start, new BlockPoint(start.x(), target.z()), target));
        }
        return result;
    }

    private static Alley shortestLegalNetworkExtension(Entrance entrance,
                                                        Anchor source,
                                                        List<Anchor> anchors,
                                                        List<JsonObject> network) {
        Alley wide = shortestLegalNetworkExtension(entrance, source, anchors, network, 3);
        return wide != null ? wide : shortestLegalNetworkExtension(entrance, source, anchors, network, 1);
    }

    private static Alley shortestLegalNetworkExtension(Entrance entrance, Anchor source,
                                                        List<Anchor> anchors, List<JsonObject> network, int width) {
        if (network.isEmpty()) return null;
        BlockPoint start = outsideBody(entrance.point(), entrance.direction(), source.body(), width);
        List<BlockBounds> searchParts = new ArrayList<>(anchors.stream().map(Anchor::body).toList());
        if (!roadPointClear(start, width, anchors)
                || !entranceApproachClear(entrance, source, start, anchors)) return null;
        List<BlockBounds> networkBounds = network.stream().map(CityInternalStreetPlanner::bounds).toList();
        searchParts.addAll(networkBounds);
        searchParts.add(new BlockBounds(start.x(), start.z(), start.x(), start.z()));
        BlockBounds search = expand(union(searchParts), 16);
        ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
        Map<BlockPoint, BlockPoint> previous = new HashMap<>();
        Map<BlockPoint, Integer> distance = new HashMap<>();
        queue.add(start);
        distance.put(start, 0);
        BlockPoint found = null;
        int[][] directions = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        while (!queue.isEmpty()) {
            BlockPoint current = queue.removeFirst();
            int currentDistance = distance.get(current);
            if (currentDistance > 0 && networkBounds.stream().anyMatch(value -> value.contains(current.x(), current.z()))) {
                found = current;
                break;
            }
            if (currentDistance >= 160) continue;
            for (int[] direction : directions) {
                BlockPoint next = new BlockPoint(current.x() + direction[0], current.z() + direction[1]);
                if (!search.contains(next.x(), next.z()) || distance.containsKey(next)
                        || !roadPointClear(next, width, anchors)) continue;
                previous.put(next, current);
                distance.put(next, currentDistance + 1);
                queue.addLast(next);
            }
        }
        if (found == null) return null;
        List<BlockPoint> cells = new ArrayList<>();
        for (BlockPoint point = found; point != null; point = previous.get(point)) cells.add(point);
        Collections.reverse(cells);
        List<BlockPoint> corners = compressOrthogonalPath(cells);
        return new Alley(corners, width, Math.max(0, cells.size() - 1));
    }

    private static boolean roadPointClear(BlockPoint point, int width, List<Anchor> anchors) {
        int lower = (width - 1) / 2 + (width == 1 ? 0 : 1);
        int upper = width / 2 + (width == 1 ? 0 : 1);
        BlockBounds road = new BlockBounds(point.x() - lower, point.z() - lower,
                point.x() + upper, point.z() + upper);
        return anchors.stream().map(Anchor::body).noneMatch(road::overlaps);
    }

    private static List<BlockPoint> compressOrthogonalPath(List<BlockPoint> cells) {
        if (cells.size() <= 2) return List.copyOf(cells);
        List<BlockPoint> result = new ArrayList<>();
        result.add(cells.get(0));
        int previousDx = Integer.compare(cells.get(1).x(), cells.get(0).x());
        int previousDz = Integer.compare(cells.get(1).z(), cells.get(0).z());
        for (int index = 2; index < cells.size(); index++) {
            int dx = Integer.compare(cells.get(index).x(), cells.get(index - 1).x());
            int dz = Integer.compare(cells.get(index).z(), cells.get(index - 1).z());
            if (dx != previousDx || dz != previousDz) result.add(cells.get(index - 1));
            previousDx = dx;
            previousDz = dz;
        }
        result.add(cells.get(cells.size() - 1));
        return List.copyOf(result);
    }

    private static boolean alleyClear(List<BlockPoint> path, int width, List<Anchor> anchors) {
        for (int index = 0; index + 1 < path.size(); index++) {
            BlockPoint start = path.get(index);
            BlockPoint end = path.get(index + 1);
            int dx = Integer.compare(end.x(), start.x());
            int dz = Integer.compare(end.z(), start.z());
            int length = Math.abs(end.x() - start.x()) + Math.abs(end.z() - start.z());
            for (int step = 0; step <= length; step++) {
                BlockPoint point = new BlockPoint(start.x() + dx * step, start.z() + dz * step);
                int lower = (width - 1) / 2 + (width == 1 ? 0 : 1);
                int upper = width / 2 + (width == 1 ? 0 : 1);
                BlockBounds road = new BlockBounds(point.x() - lower, point.z() - lower,
                        point.x() + upper, point.z() + upper);
                if (anchors.stream().map(Anchor::body).anyMatch(road::overlaps)) return false;
            }
        }
        return true;
    }

    private static BlockPoint outsideBody(BlockPoint entrance, String direction, BlockBounds body, int width) {
        int dx = switch (direction) { case "EAST" -> 1; case "WEST" -> -1; default -> 0; };
        int dz = switch (direction) { case "SOUTH" -> 1; case "NORTH" -> -1; default -> 0; };
        BlockPoint result = entrance;
        for (int step = 0; step <= Math.max(body.widthBlocks(), body.heightBlocks()) + 2; step++) {
            if (!body.contains(result.x(), result.z())) {
                int crossSectionClearance = Math.max((width - 1) / 2, width / 2) + (width == 1 ? 0 : 1);
                return new BlockPoint(result.x() + dx * crossSectionClearance,
                        result.z() + dz * crossSectionClearance);
            }
            result = new BlockPoint(result.x() + dx, result.z() + dz);
        }
        return entrance;
    }

    private static JsonObject accessOutcome(Entrance entrance, String status, String reasonCode) {
        JsonObject value = new JsonObject();
        value.addProperty("entranceId", entrance.id());
        value.addProperty("groupId", entrance.groupId());
        value.addProperty("status", status);
        value.addProperty("reasonCode", reasonCode);
        return value;
    }

    private static BlockBounds bounds(JsonObject band) {
        JsonObject value = object(band, "bounds");
        return CityStructureCandidateEnvelope.bounds(value);
    }

    private static BlockPoint center(BlockBounds bounds) {
        return new BlockPoint((bounds.minX() + bounds.maxX()) / 2,
                (bounds.minZ() + bounds.maxZ()) / 2);
    }

    private static int[] cardinal(double axisX, double axisZ) {
        if (Math.abs(axisX) >= Math.abs(axisZ)) return new int[]{axisX >= 0.0 ? 1 : -1, 0};
        return new int[]{0, axisZ >= 0.0 ? 1 : -1};
    }

    private static BlockPoint boundaryPoint(BlockBounds body, BlockPoint center, int dx, int dz) {
        if (dx < 0) return new BlockPoint(body.minX() - 1, center.z());
        if (dx > 0) return new BlockPoint(body.maxX() + 1, center.z());
        if (dz < 0) return new BlockPoint(center.x(), body.minZ() - 1);
        return new BlockPoint(center.x(), body.maxZ() + 1);
    }

    private static GridOffset squareSpiral(int index) {
        if (index <= 0) return new GridOffset(0, 0);
        int x = 0;
        int z = 0;
        int dx = 1;
        int dz = 0;
        int segmentLength = 1;
        int segmentUsed = 0;
        int turns = 0;
        for (int i = 0; i < index; i++) {
            x += dx;
            z += dz;
            segmentUsed++;
            if (segmentUsed == segmentLength) {
                segmentUsed = 0;
                int nextDx = -dz;
                dz = dx;
                dx = nextDx;
                turns++;
                if ((turns & 1) == 0) segmentLength++;
            }
        }
        return new GridOffset(x, z);
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
        value.addProperty("schema", "city_internal_street_band");
        value.addProperty("streetBandId", groupId + "::" + networkId + "::segment_"
                + String.format("%03d", segmentIndex + 1));
        value.addProperty("roadNetworkId", groupId + "::" + networkId);
        value.addProperty("roadKind", roadKind);
        value.addProperty("roadHierarchy", "SECONDARY");
        value.addProperty("segmentIndex", segmentIndex);
        value.addProperty("groupId", groupId);
        value.addProperty("geometryMode", "STRAIGHT_AXIS_CLIPPED_BY_TERRAIN");
        value.addProperty("widthBlocks", width);
        value.addProperty("surfacePolicy", "FOLLOW_TERRAIN_STEP_GRADED");
        value.addProperty("crossSectionProfile", width == 1 ? "SURFACE_ONLY" : "STAIR_SLAB_STAIR");
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

    private record Anchor(String anchorId, String groupId, String phase, BlockBounds body, BlockBounds collision,
                          JsonObject layout, JsonObject source) {
        static Anchor parse(JsonObject value) {
            return new Anchor(string(value, "anchorId"), string(value, "placementGroupId"),
                    string(value, "blueprintPlacementPhase"),
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

    record Finalization(List<JsonObject> streetBands, JsonObject trace) {
        Finalization {
            streetBands = List.copyOf(streetBands);
            trace = trace.deepCopy();
        }
    }

    private record Entrance(String id, BlockPoint point, String direction, String groupId) {
    }

    private record Alley(List<BlockPoint> path, int width, int length) {
        Alley {
            path = List.copyOf(path);
        }
    }

    private record GridOffset(int row, int column) {
    }
}
