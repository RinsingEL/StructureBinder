package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LandUseGeometryCompiler {
    private static final int[][] DIRECTIONS = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};

    public CompiledGeometry compile(BlockBounds planningBounds,
                                    List<LandUseSeedGroup> seedGroups,
                                    LandUseExpansionResult expansion) {
        Map<String, LandUseSeedGroup> groups = new HashMap<>();
        seedGroups.forEach(group -> groups.put(group.groupId(), group));
        Map<String, Set<BlockPoint>> byMergeKey = new LinkedHashMap<>();
        for (Map.Entry<BlockPoint, LandUseExpansionResult.Claim> entry : expansion.claims().entrySet()) {
            LandUseSeedGroup group = groups.get(entry.getValue().groupId());
            if (group == null) continue;
            String settingsSignature = group.surfaceSettings().exactSignature();
            String mergeKey = group.rule().mergeSameType()
                    ? group.rule().ruleRef() + ":surface:" + settingsSignature
                    : group.rule().ruleRef() + ":surface:" + settingsSignature + ":group:" + group.groupId();
            byMergeKey.computeIfAbsent(mergeKey, ignored -> new HashSet<>()).add(entry.getKey());
        }
        List<LandUseAreaPlan.Area> areas = new ArrayList<>();
        Set<BlockPoint> claimed = new HashSet<>();
        for (Map.Entry<String, Set<BlockPoint>> entry : byMergeKey.entrySet()) {
            for (Set<BlockPoint> component : components(entry.getValue())) {
                claimed.addAll(component);
                Set<String> sourceGroupIds = new LinkedHashSet<>();
                double claimCost = 0;
                List<BlockPoint> orderedComponent = component.stream().sorted(pointOrder()).toList();
                for (BlockPoint point : orderedComponent) {
                    LandUseExpansionResult.Claim claim = expansion.claims().get(point);
                    sourceGroupIds.add(claim.groupId());
                    claimCost += claim.cumulativeCost();
                }
                List<String> sortedGroupIds = sourceGroupIds.stream().sorted().toList();
                LandUseSeedGroup primary = groups.get(sortedGroupIds.get(0));
                List<String> anchorIds = sortedGroupIds.stream().flatMap(id -> groups.get(id).anchorIds().stream())
                        .distinct().sorted().toList();
                List<BlockPoint> seeds = sortedGroupIds.stream().flatMap(id -> groups.get(id).seedPoints().stream())
                        .distinct().sorted(pointOrder()).toList();
                List<com.rinsing.geomantia.systems.city.domain.model.BlockBounds> structureFootprints =
                        sortedGroupIds.stream().flatMap(id -> groups.get(id).structureFootprints().stream())
                                .distinct().sorted(Comparator.comparingInt(
                                                com.rinsing.geomantia.systems.city.domain.model.BlockBounds::minZ)
                                        .thenComparingInt(
                                                com.rinsing.geomantia.systems.city.domain.model.BlockBounds::minX)
                                        .thenComparingInt(
                                                com.rinsing.geomantia.systems.city.domain.model.BlockBounds::maxZ)
                                        .thenComparingInt(
                                                com.rinsing.geomantia.systems.city.domain.model.BlockBounds::maxX))
                                .toList();
                List<LandUseAreaPlan.GateSlot> gates = sortedGroupIds.stream()
                        .flatMap(id -> groups.get(id).gateSlots().stream())
                        .map(gate -> projectGate(gate, component)).distinct().toList();
                String areaId = safeId(primary.rule().landUseType() + '_' + String.join("_", sortedGroupIds));
                areas.add(new LandUseAreaPlan.Area(areaId, primary.rule().ruleRef(), primary.rule().landUseType(),
                        sortedGroupIds, anchorIds, seeds, scanlines(component), structureFootprints,
                        boundaryLoops(component), gates, claimCost, primary.rule().surfacePolicy(),
                        primary.rule().vegetationPolicy(), primary.rule().boundaryPolicy(),
                        primary.rule().decorationPolicy()));
            }
        }
        areas.sort(Comparator.comparing(LandUseAreaPlan.Area::areaId)
                .thenComparing(area -> String.join("\u0000", area.sourceGroupIds()))
                .thenComparingInt(area -> area.memberSpans().isEmpty()
                        ? Integer.MAX_VALUE : area.memberSpans().get(0).z())
                .thenComparingInt(area -> area.memberSpans().isEmpty()
                        ? Integer.MAX_VALUE : area.memberSpans().get(0).minX()));
        return new CompiledGeometry(List.copyOf(areas), unclaimedScanlines(planningBounds, claimed));
    }

    public List<LandUseAreaPlan.ScanlineSpan> scanlines(Set<BlockPoint> points) {
        Map<Integer, List<Integer>> byZ = new java.util.TreeMap<>();
        for (BlockPoint point : points) byZ.computeIfAbsent(point.z(), ignored -> new ArrayList<>()).add(point.x());
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : byZ.entrySet()) {
            List<Integer> xs = entry.getValue().stream().distinct().sorted().toList();
            if (xs.isEmpty()) continue;
            int start = xs.get(0);
            int previous = start;
            for (int index = 1; index < xs.size(); index++) {
                int x = xs.get(index);
                if (x != previous + 1) {
                    spans.add(new LandUseAreaPlan.ScanlineSpan(entry.getKey(), start, previous));
                    start = x;
                }
                previous = x;
            }
            spans.add(new LandUseAreaPlan.ScanlineSpan(entry.getKey(), start, previous));
        }
        return spans;
    }

    public List<LandUseAreaPlan.BoundaryLoop> boundaryLoops(Set<BlockPoint> points) {
        Set<Edge> edges = new HashSet<>();
        for (BlockPoint point : points) {
            int x = point.x();
            int z = point.z();
            if (!points.contains(new BlockPoint(x, z - 1))) edges.add(new Edge(x, z, x + 1, z));
            if (!points.contains(new BlockPoint(x + 1, z))) edges.add(new Edge(x + 1, z, x + 1, z + 1));
            if (!points.contains(new BlockPoint(x, z + 1))) edges.add(new Edge(x + 1, z + 1, x, z + 1));
            if (!points.contains(new BlockPoint(x - 1, z))) edges.add(new Edge(x, z + 1, x, z));
        }
        List<LandUseAreaPlan.BoundaryLoop> loops = new ArrayList<>();
        while (!edges.isEmpty()) {
            Edge first = edges.stream().min(Edge.ORDER).orElseThrow();
            List<BlockPoint> vertices = new ArrayList<>();
            Edge current = first;
            int guard = edges.size() + 1;
            while (guard-- > 0 && edges.remove(current)) {
                vertices.add(new BlockPoint(current.fromX(), current.fromZ()));
                int x = current.toX();
                int z = current.toZ();
                Edge next = edges.stream().filter(edge -> edge.fromX() == x && edge.fromZ() == z)
                        .min(Edge.ORDER).orElse(null);
                if (next == null) {
                    vertices.add(new BlockPoint(x, z));
                    break;
                }
                current = next;
                if (current.equals(first)) break;
            }
            if (vertices.size() >= 3) loops.add(new LandUseAreaPlan.BoundaryLoop(vertices, signedArea(vertices) < 0));
        }
        loops.sort(Comparator.comparingInt((LandUseAreaPlan.BoundaryLoop loop) -> loop.points().get(0).z())
                .thenComparingInt(loop -> loop.points().get(0).x()));
        return loops;
    }

    private static List<Set<BlockPoint>> components(Set<BlockPoint> points) {
        Set<BlockPoint> remaining = new HashSet<>(points);
        List<Set<BlockPoint>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockPoint first = remaining.stream().min(pointOrder()).orElseThrow();
            Set<BlockPoint> component = new HashSet<>();
            ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
            queue.add(first);
            remaining.remove(first);
            while (!queue.isEmpty()) {
                BlockPoint point = queue.removeFirst();
                component.add(point);
                for (int[] direction : DIRECTIONS) {
                    BlockPoint next = new BlockPoint(point.x() + direction[0], point.z() + direction[1]);
                    if (remaining.remove(next)) queue.addLast(next);
                }
            }
            result.add(component);
        }
        result.sort(Comparator.comparing(component -> component.stream().min(pointOrder()).orElseThrow(), pointOrder()));
        return result;
    }

    private static LandUseAreaPlan.GateSlot projectGate(LandUseAreaPlan.GateSlot gate, Set<BlockPoint> area) {
        BlockPoint nearest = area.stream().filter(point -> isBoundary(point, area))
                .min(Comparator.comparingInt((BlockPoint point) -> manhattan(point, gate.block()))
                        .thenComparing(pointOrder())).orElse(gate.block());
        return new LandUseAreaPlan.GateSlot(gate.gateId(), nearest, gate.direction(), gate.sourceAnchorId());
    }

    private static boolean isBoundary(BlockPoint point, Set<BlockPoint> area) {
        for (int[] direction : DIRECTIONS) {
            if (!area.contains(new BlockPoint(point.x() + direction[0], point.z() + direction[1]))) return true;
        }
        return false;
    }

    private static int manhattan(BlockPoint left, BlockPoint right) {
        return Math.abs(left.x() - right.x()) + Math.abs(left.z() - right.z());
    }

    public List<LandUseAreaPlan.ScanlineSpan> unclaimedScanlines(BlockBounds bounds, Set<BlockPoint> claimed) {
        Map<Integer, List<Integer>> claimedByZ = new HashMap<>();
        for (BlockPoint point : claimed) {
            if (bounds.contains(point.x(), point.z())) {
                claimedByZ.computeIfAbsent(point.z(), ignored -> new ArrayList<>()).add(point.x());
            }
        }
        List<LandUseAreaPlan.ScanlineSpan> result = new ArrayList<>();
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            List<Integer> xs = claimedByZ.getOrDefault(z, List.of()).stream().distinct().sorted().toList();
            int cursor = bounds.minX();
            for (int x : xs) {
                if (x < cursor) continue;
                if (x > cursor) result.add(new LandUseAreaPlan.ScanlineSpan(z, cursor, x - 1));
                cursor = x + 1;
                if (cursor > bounds.maxX()) break;
            }
            if (cursor <= bounds.maxX()) result.add(new LandUseAreaPlan.ScanlineSpan(z, cursor, bounds.maxX()));
        }
        return result;
    }

    private static long signedArea(List<BlockPoint> points) {
        long area = 0;
        for (int index = 0; index < points.size(); index++) {
            BlockPoint left = points.get(index);
            BlockPoint right = points.get((index + 1) % points.size());
            area += (long) left.x() * right.z() - (long) right.x() * left.z();
        }
        return area;
    }

    private static String safeId(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static Comparator<BlockPoint> pointOrder() {
        return Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x);
    }

    public record CompiledGeometry(List<LandUseAreaPlan.Area> areas,
                                   List<LandUseAreaPlan.ScanlineSpan> unclaimedSpans) {
    }

    private record Edge(int fromX, int fromZ, int toX, int toZ) {
        private static final Comparator<Edge> ORDER = Comparator.comparingInt(Edge::fromZ)
                .thenComparingInt(Edge::fromX).thenComparingInt(Edge::toZ).thenComparingInt(Edge::toX);
    }
}
