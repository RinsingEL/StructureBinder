package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Bounded local repair of program-owned streets; authored buildings are never moved or removed. */
final class CityStreetObstacleRouter {
    private static final int LIMIT = 100_000;

    static BlockBounds crossSection(JsonObject road) {
        BlockBounds b = CityStructureCandidateEnvelope.bounds(road.getAsJsonObject("bounds"));
        if (road.has("crossSectionProfile") && "SURFACE_ONLY".equals(road.get("crossSectionProfile").getAsString())) return b;
        return road.get("axisX").getAsInt() != 0
                ? new BlockBounds(b.minX(), b.minZ() - 1, b.maxX(), b.maxZ() + 1)
                : new BlockBounds(b.minX() - 1, b.minZ(), b.maxX() + 1, b.maxZ());
    }

    static List<JsonObject> repair(List<JsonObject> roads, List<JsonObject> anchors) {
        List<BlockBounds> bodies = anchors.stream().map(a -> CityStructureCandidateEnvelope.bounds(
                a.has("actualFootprint") ? a.getAsJsonObject("actualFootprint")
                        : a.has("plannedFootprint") ? a.getAsJsonObject("plannedFootprint")
                        : a.getAsJsonObject("collisionEnvelope"))).toList();
        List<JsonObject> result = new ArrayList<>();
        for (JsonObject road : roads) {
            if (bodies.stream().noneMatch(crossSection(road)::overlaps)) {
                result.add(road);
                continue;
            }
            int width = road.get("widthBlocks").getAsInt();
            BlockPoint start = freeEndpoint(point(road, "start"), width, bodies);
            BlockPoint end = freeEndpoint(point(road, "end"), width, bodies);
            List<BlockPoint> path = route(start, end, width, bodies);
            if (path.size() < 2) throw new IllegalStateException(
                    "CITY_INTERNAL_STREET_REROUTE_UNAVAILABLE: " + road.get("streetBandId").getAsString());
            int segment = 0;
            for (int i = 0; i + 1 < path.size(); i++) {
                BlockPoint a = path.get(i), b = path.get(i + 1);
                JsonObject repaired = road.deepCopy();
                repaired.addProperty("streetBandId", road.get("streetBandId").getAsString() + "::detour_" + (++segment));
                repaired.addProperty("sourceStreetBandId", road.get("streetBandId").getAsString());
                repaired.addProperty("obstacleRepair", "BOUNDED_FULL_CROSS_SECTION_REROUTE");
                repaired.addProperty("segmentIndex", segment - 1);
                repaired.add("start", a.asJson()); repaired.add("end", b.asJson());
                repaired.addProperty("axisX", Integer.compare(b.x(), a.x()));
                repaired.addProperty("axisZ", Integer.compare(b.z(), a.z()));
                int low = (width - 1) / 2, high = width / 2;
                BlockBounds bounds = a.x() == b.x()
                        ? new BlockBounds(a.x() - low, Math.min(a.z(), b.z()), a.x() + high, Math.max(a.z(), b.z()))
                        : new BlockBounds(Math.min(a.x(), b.x()), a.z() - low, Math.max(a.x(), b.x()), a.z() + high);
                repaired.add("bounds", CityStructureCandidateEnvelope.boundsJson(bounds));
                repaired.add("platformBounds", CityStructureCandidateEnvelope.boundsJson(bounds));
                result.add(repaired);
            }
        }
        return List.copyOf(result);
    }

    private static BlockPoint point(JsonObject road, String field) {
        JsonObject p = road.getAsJsonObject(field);
        return new BlockPoint(p.get("x").getAsInt(), p.get("z").getAsInt());
    }

    private static boolean clear(BlockPoint p, int width, List<BlockBounds> bodies) {
        return clear(p, width, bodies, false);
    }
    private static boolean clear(BlockPoint p, int width, List<BlockBounds> bodies, boolean surfaceOnly) {
        int low = (width - 1) / 2 + (surfaceOnly ? 0 : 1), high = width / 2 + (surfaceOnly ? 0 : 1);
        BlockBounds b = new BlockBounds(p.x() - low, p.z() - low, p.x() + high, p.z() + high);
        return bodies.stream().noneMatch(b::overlaps);
    }

    private static BlockPoint freeEndpoint(BlockPoint p, int width, List<BlockBounds> bodies) {
        for (int radius = 0; radius <= 32; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int dz = radius - Math.abs(dx);
                for (int sign : new int[]{-1, 1}) {
                    BlockPoint candidate = new BlockPoint(p.x() + dx, p.z() + dz * sign);
                    if (clear(candidate, width, bodies)) return candidate;
                }
            }
        }
        throw new IllegalStateException("CITY_INTERNAL_STREET_ENDPOINT_BLOCKED: " + p);
    }

    private record Node(BlockPoint point, int distance, int estimate) {}
    private static int distance(BlockPoint a, BlockPoint b) { return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()); }

    static List<BlockPoint> narrowRoute(BlockPoint start, BlockPoint end, List<BlockBounds> bodies) {
        if (!clear(start, 1, bodies, true) || !clear(end, 1, bodies, true)) return List.of();
        return route(start, end, 1, bodies, true);
    }

    private static List<BlockPoint> route(BlockPoint start, BlockPoint end, int width, List<BlockBounds> bodies) {
        return route(start, end, width, bodies, false);
    }
    private static List<BlockPoint> route(BlockPoint start, BlockPoint end, int width, List<BlockBounds> bodies,
                                          boolean surfaceOnly) {
        BlockBounds domain = new BlockBounds(Math.min(start.x(), end.x()) - 48, Math.min(start.z(), end.z()) - 48,
                Math.max(start.x(), end.x()) + 48, Math.max(start.z(), end.z()) + 48);
        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingInt(Node::estimate)
                .thenComparingInt(n -> n.point().x()).thenComparingInt(n -> n.point().z()));
        Map<BlockPoint, Integer> costs = new HashMap<>();
        Map<BlockPoint, BlockPoint> previous = new HashMap<>();
        costs.put(start, 0); queue.add(new Node(start, 0, distance(start, end)));
        int visited = 0;
        while (!queue.isEmpty() && visited++ < LIMIT) {
            Node node = queue.remove();
            if (node.distance() != costs.get(node.point())) continue;
            if (node.point().equals(end)) {
                List<BlockPoint> path = new ArrayList<>();
                for (BlockPoint p = end; p != null; p = previous.get(p)) path.add(p);
                Collections.reverse(path);
                List<BlockPoint> corners = new ArrayList<>();
                for (BlockPoint p : path) {
                    if (corners.size() >= 2) {
                        BlockPoint a = corners.get(corners.size() - 2), b = corners.get(corners.size() - 1);
                        if (a.x() == b.x() && b.x() == p.x() || a.z() == b.z() && b.z() == p.z())
                            corners.remove(corners.size() - 1);
                    }
                    corners.add(p);
                }
                return corners;
            }
            for (int[] d : new int[][]{{1,0},{0,1},{-1,0},{0,-1}}) {
                BlockPoint p = new BlockPoint(node.point().x() + d[0], node.point().z() + d[1]);
                int cost = node.distance() + 1;
                if (!domain.contains(p.x(), p.z()) || cost >= costs.getOrDefault(p, Integer.MAX_VALUE)
                        || !clear(p, width, bodies, surfaceOnly)) continue;
                costs.put(p, cost); previous.put(p, node.point());
                queue.add(new Node(p, cost, cost + distance(p, end)));
            }
        }
        return List.of();
    }
}
