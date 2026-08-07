package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds one terrain-safe, broad city foundation from the frozen structure footprints. */
public final class CityFoundationPlanner {
    private static final int[][] DIRECTIONS = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};
    private static final Comparator<BlockPoint> POINT_ORDER = Comparator.comparingInt(BlockPoint::z)
            .thenComparingInt(BlockPoint::x);

    public Plan plan(BlockBounds planningBounds,
                     LandUseTerrainField terrain,
                     List<BlockBounds> structureFootprints,
                     LandUseSeedGroup.FoundationSettings settings) {
        if (planningBounds == null || terrain == null || settings == null) {
            throw new IllegalArgumentException("CITY_FOUNDATION_INPUT_REQUIRED");
        }
        List<BlockBounds> footprints = structureFootprints == null ? List.of()
                : structureFootprints.stream().distinct().sorted(Comparator.comparingInt(BlockBounds::minZ)
                .thenComparingInt(BlockBounds::minX).thenComparingInt(BlockBounds::maxZ)
                .thenComparingInt(BlockBounds::maxX)).toList();
        if (footprints.isEmpty()) throw new IllegalArgumentException("CITY_FOUNDATION_FOOTPRINTS_REQUIRED");
        for (BlockBounds footprint : footprints) {
            if (!contains(planningBounds, footprint)) {
                throw new IllegalArgumentException("CITY_FOUNDATION_FOOTPRINT_OUTSIDE_PLANNING_BOUNDS:"
                        + footprint.minX() + ':' + footprint.minZ());
            }
        }
        validateJoinGraph(footprints, settings.maxJoinDistanceBlocks());

        TerrainIndex terrainIndex = new TerrainIndex(terrain);
        Set<BlockPoint> structureMask = rasterize(footprints);
        Set<BlockPoint> marginMask = dilate(structureMask, settings.structureMarginBlocks(), planningBounds,
                terrainIndex);
        int bridgeHalfWidth = Math.max(1, settings.structureMarginBlocks());
        int minimumRadius = settings.closeRadiusBlocks();
        int maximumRadius = settings.maxJoinDistanceBlocks();
        Attempt resolved = attempt(marginMask, minimumRadius, planningBounds, terrainIndex,
                bridgeHalfWidth, footprints.size() > 1);
        if (!resolved.valid() && maximumRadius > minimumRadius) {
            Attempt maximum = attempt(marginMask, maximumRadius, planningBounds, terrainIndex,
                    bridgeHalfWidth, footprints.size() > 1);
            if (maximum.valid()) {
                int low = minimumRadius + 1;
                int high = maximumRadius - 1;
                resolved = maximum;
                while (low <= high) {
                    int middle = low + (high - low) / 2;
                    Attempt candidate = attempt(marginMask, middle, planningBounds, terrainIndex,
                            bridgeHalfWidth, footprints.size() > 1);
                    if (candidate.valid()) {
                        resolved = candidate;
                        high = middle - 1;
                    } else {
                        low = middle + 1;
                    }
                }
            } else if (maximum.singleComponent() || maximum.expandedConnected()) {
                throw new IllegalArgumentException("CITY_FOUNDATION_THIN_BRIDGE:requiredWidth="
                        + (bridgeHalfWidth * 2 + 1) + ":maxJoin=" + maximumRadius);
            } else {
                throw disconnected(maximum.components(), maximumRadius);
            }
        } else if (!resolved.valid()) {
            if (resolved.singleComponent() || resolved.expandedConnected()) {
                throw new IllegalArgumentException("CITY_FOUNDATION_THIN_BRIDGE:requiredWidth="
                        + (bridgeHalfWidth * 2 + 1) + ":maxJoin=" + maximumRadius);
            }
            throw disconnected(resolved.components(), maximumRadius);
        }
        Set<BlockPoint> stableClaims = resolved.claims().stream().sorted(POINT_ORDER)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new Plan(stableClaims, structureMask.size(), marginMask.size(), bridgeHalfWidth * 2 + 1,
                resolved.radius());
    }

    private static Attempt attempt(Set<BlockPoint> source,
                                   int radius,
                                   BlockBounds planningBounds,
                                   TerrainIndex terrain,
                                   int bridgeHalfWidth,
                                   boolean requireDurableBridge) {
        Set<BlockPoint> expanded = dilate(source, radius, planningBounds, terrain);
        boolean expandedConnected = components(expanded).size() == 1;
        Set<BlockPoint> closed = expanded;
        for (int step = 0; step < radius && !closed.isEmpty(); step++) {
            closed = erodeWithinBounds(closed, planningBounds);
        }
        closed.addAll(source);
        List<Set<BlockPoint>> closedComponents = components(closed);
        boolean singleComponent = closedComponents.size() == 1;
        boolean durable = singleComponent;
        if (durable && requireDurableBridge) {
            Set<BlockPoint> durableCore = erode(closed, bridgeHalfWidth);
            durable = !durableCore.isEmpty() && components(durableCore).size() == 1;
        }
        return new Attempt(radius, closed, closedComponents, expandedConnected, singleComponent && durable);
    }

    private static IllegalArgumentException disconnected(List<Set<BlockPoint>> components, int maximumRadius) {
        return new IllegalArgumentException("CITY_FOUNDATION_DISCONNECTED:" + components.size()
                + ":nearestGap=" + minimumComponentGap(components) + ":maxJoin=" + maximumRadius);
    }

    private static void validateJoinGraph(List<BlockBounds> footprints, int maxJoinDistanceBlocks) {
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        visited.add(0);
        queue.add(0);
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            for (int index = 0; index < footprints.size(); index++) {
                if (!visited.contains(index)
                        && gap(footprints.get(current), footprints.get(index)) <= maxJoinDistanceBlocks) {
                    visited.add(index);
                    queue.addLast(index);
                }
            }
        }
        if (visited.size() != footprints.size()) {
            throw new IllegalArgumentException("CITY_FOUNDATION_JOIN_DISTANCE_EXCEEDED:connected="
                    + visited.size() + "/" + footprints.size() + ":maxJoin=" + maxJoinDistanceBlocks);
        }
    }

    private static int gap(BlockBounds left, BlockBounds right) {
        int x = left.maxX() < right.minX() ? right.minX() - left.maxX() - 1
                : right.maxX() < left.minX() ? left.minX() - right.maxX() - 1 : 0;
        int z = left.maxZ() < right.minZ() ? right.minZ() - left.maxZ() - 1
                : right.maxZ() < left.minZ() ? left.minZ() - right.maxZ() - 1 : 0;
        return Math.max(0, x) + Math.max(0, z);
    }

    private static Set<BlockPoint> erodeWithinBounds(Set<BlockPoint> source, BlockBounds planningBounds) {
        Set<BlockPoint> result = new HashSet<>();
        for (BlockPoint point : source) {
            boolean interior = true;
            for (int[] direction : DIRECTIONS) {
                BlockPoint neighbor = new BlockPoint(point.x() + direction[0], point.z() + direction[1]);
                if (planningBounds.contains(neighbor.x(), neighbor.z()) && !source.contains(neighbor)) {
                    interior = false;
                    break;
                }
            }
            if (interior) result.add(point);
        }
        return result;
    }

    private static Set<BlockPoint> dilate(Set<BlockPoint> source,
                                          int radius,
                                          BlockBounds planningBounds,
                                          TerrainIndex terrain) {
        Set<BlockPoint> result = new HashSet<>(source);
        for (int step = 0; step < radius; step++) {
            Set<BlockPoint> next = new HashSet<>(result);
            for (BlockPoint point : result) {
                for (int[] direction : DIRECTIONS) {
                    BlockPoint neighbor = new BlockPoint(point.x() + direction[0], point.z() + direction[1]);
                    if (planningBounds.contains(neighbor.x(), neighbor.z())
                            && passable(terrain.cellAt(neighbor))) next.add(neighbor);
                }
            }
            result = next;
        }
        return result;
    }

    private static Set<BlockPoint> erode(Set<BlockPoint> source, int radius) {
        Set<BlockPoint> result = new HashSet<>(source);
        for (int step = 0; step < radius && !result.isEmpty(); step++) {
            Set<BlockPoint> next = new HashSet<>();
            for (BlockPoint point : result) {
                boolean interior = true;
                for (int[] direction : DIRECTIONS) {
                    if (!result.contains(new BlockPoint(point.x() + direction[0], point.z() + direction[1]))) {
                        interior = false;
                        break;
                    }
                }
                if (interior) next.add(point);
            }
            result = next;
        }
        return result;
    }

    private static List<Set<BlockPoint>> components(Set<BlockPoint> points) {
        Set<BlockPoint> remaining = new HashSet<>(points);
        List<Set<BlockPoint>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockPoint first = remaining.stream().min(POINT_ORDER).orElseThrow();
            Set<BlockPoint> component = new HashSet<>();
            ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
            remaining.remove(first);
            queue.add(first);
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
        result.sort(Comparator.comparing(component -> component.stream().min(POINT_ORDER).orElseThrow(), POINT_ORDER));
        return result;
    }

    private static int minimumComponentGap(List<Set<BlockPoint>> components) {
        if (components.size() < 2) return 0;
        int minimum = Integer.MAX_VALUE;
        for (int left = 0; left < components.size(); left++) {
            BlockBounds leftBounds = bounds(components.get(left));
            for (int right = left + 1; right < components.size(); right++) {
                minimum = Math.min(minimum, gap(leftBounds, bounds(components.get(right))));
            }
        }
        return minimum;
    }

    private static BlockBounds bounds(Set<BlockPoint> points) {
        return new BlockBounds(points.stream().mapToInt(BlockPoint::x).min().orElseThrow(),
                points.stream().mapToInt(BlockPoint::z).min().orElseThrow(),
                points.stream().mapToInt(BlockPoint::x).max().orElseThrow(),
                points.stream().mapToInt(BlockPoint::z).max().orElseThrow());
    }

    private static boolean contains(BlockBounds outer, BlockBounds inner) {
        return inner.minX() >= outer.minX() && inner.maxX() <= outer.maxX()
                && inner.minZ() >= outer.minZ() && inner.maxZ() <= outer.maxZ();
    }

    private static Set<BlockPoint> rasterize(List<BlockBounds> bounds) {
        Set<BlockPoint> result = new HashSet<>();
        for (BlockBounds value : bounds) {
            for (int z = value.minZ(); z <= value.maxZ(); z++) {
                for (int x = value.minX(); x <= value.maxX(); x++) result.add(new BlockPoint(x, z));
            }
        }
        return result;
    }

    private static boolean passable(LandUseTerrainField.Cell cell) {
        return cell != null && cell.sampled() && !cell.water() && cell.slope() < 45.0 && cell.localRelief() < 48.0;
    }

    public record Plan(Set<BlockPoint> claims,
                       int structureBlocks,
                       int marginBlocks,
                       int minimumBridgeWidthBlocks,
                       int resolvedCloseRadiusBlocks) {
        public Plan {
            claims = Set.copyOf(claims);
        }
    }

    private record Attempt(int radius,
                           Set<BlockPoint> claims,
                           List<Set<BlockPoint>> components,
                           boolean expandedConnected,
                           boolean valid) {
        private boolean singleComponent() {
            return components.size() == 1;
        }
    }

    private static final class TerrainIndex {
        private final int step;
        private final Map<CellKey, LandUseTerrainField.Cell> cells = new HashMap<>();

        private TerrainIndex(LandUseTerrainField field) {
            step = field.cellStepBlocks();
            field.cells().forEach(cell -> cells.put(new CellKey(cell.cellX(), cell.cellZ()), cell));
        }

        private LandUseTerrainField.Cell cellAt(BlockPoint point) {
            return cells.get(new CellKey(Math.floorDiv(point.x(), step), Math.floorDiv(point.z(), step)));
        }
    }

    private record CellKey(int x, int z) {
    }
}
