package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds local paved surfaces from actual structures, closing only configured short gaps. */
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
        Set<BlockPoint> structureMask = rasterize(footprints);
        Set<BlockPoint> marginMask = dilate(structureMask, settings.structureMarginBlocks(), planningBounds);
        int bridgeHalfWidth = Math.max(1, settings.structureMarginBlocks());
        Set<BlockPoint> claims = new HashSet<>();
        int resolvedRadius = 0;
        for (List<BlockBounds> local : footprintGroups(footprints, settings.maxJoinDistanceBlocks())) {
            Set<BlockPoint> localMargin = dilate(rasterize(local), settings.structureMarginBlocks(), planningBounds);
            claims.addAll(closeLocalGaps(localMargin, settings.closeRadiusBlocks(), planningBounds));
            resolvedRadius = Math.max(resolvedRadius, settings.closeRadiusBlocks());
        }
        Set<BlockPoint> stableClaims = claims.stream().sorted(POINT_ORDER)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new Plan(stableClaims, structureMask.size(), marginMask.size(), bridgeHalfWidth * 2 + 1,
                resolvedRadius, components(stableClaims).size());
    }

    private static Set<BlockPoint> closeLocalGaps(Set<BlockPoint> source, int radius, BlockBounds bounds) {
        if (radius <= 0) return source;
        // Closing repairs narrow gaps without paving the entire convex hull of a winding district.
        Set<BlockPoint> expanded = dilate(source, radius, bounds);
        Set<BlockPoint> closed = new HashSet<>(expanded);
        Set<BlockPoint> frontier = new HashSet<>();
        for (BlockPoint point : expanded) for (int[] direction : DIRECTIONS) {
            BlockPoint neighbor = new BlockPoint(point.x()+direction[0],point.z()+direction[1]);
            if (bounds.contains(neighbor.x(),neighbor.z()) && !expanded.contains(neighbor)) { frontier.add(point); break; }
        }
        for (int step=0; step<radius && !frontier.isEmpty(); step++) {
            closed.removeAll(frontier);
            Set<BlockPoint> next = new HashSet<>();
            for (BlockPoint point : frontier) for (int[] direction : DIRECTIONS) {
                BlockPoint neighbor = new BlockPoint(point.x()+direction[0],point.z()+direction[1]);
                if (closed.contains(neighbor)) next.add(neighbor);
            }
            frontier = next;
        }
        closed.addAll(source);
        return closed;
    }

    private static List<List<BlockBounds>> footprintGroups(List<BlockBounds> footprints,
                                                           int maxJoinDistanceBlocks) {
        Set<Integer> remaining = new LinkedHashSet<>();
        for (int index = 0; index < footprints.size(); index++) remaining.add(index);
        List<List<BlockBounds>> groups = new ArrayList<>();
        while (!remaining.isEmpty()) {
            int first = remaining.iterator().next();
            remaining.remove(first);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(first);
            List<BlockBounds> group = new ArrayList<>();
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                group.add(footprints.get(current));
                List<Integer> neighbors = remaining.stream().filter(index ->
                        gap(footprints.get(current), footprints.get(index)) <= maxJoinDistanceBlocks).toList();
                neighbors.forEach(index -> {
                    remaining.remove(index);
                    queue.addLast(index);
                });
            }
            group.sort(Comparator.comparingInt(BlockBounds::minZ).thenComparingInt(BlockBounds::minX));
            groups.add(List.copyOf(group));
        }
        return List.copyOf(groups);
    }

    private static int gap(BlockBounds left, BlockBounds right) {
        int x = left.maxX() < right.minX() ? right.minX() - left.maxX() - 1
                : right.maxX() < left.minX() ? left.minX() - right.maxX() - 1 : 0;
        int z = left.maxZ() < right.minZ() ? right.minZ() - left.maxZ() - 1
                : right.maxZ() < left.minZ() ? left.minZ() - right.maxZ() - 1 : 0;
        return Math.max(0, x) + Math.max(0, z);
    }

    private static Set<BlockPoint> dilate(Set<BlockPoint> source,
                                          int radius,
                                          BlockBounds planningBounds) {
        Set<BlockPoint> result = new HashSet<>(source);
        Set<BlockPoint> frontier = source;
        for (int step = 0; step < radius && !frontier.isEmpty(); step++) {
            Set<BlockPoint> next = new HashSet<>();
            for (BlockPoint point : frontier) for (int[] direction : DIRECTIONS) {
                BlockPoint neighbor = new BlockPoint(point.x() + direction[0], point.z() + direction[1]);
                if (planningBounds.contains(neighbor.x(), neighbor.z()) && result.add(neighbor)) next.add(neighbor);
            }
            frontier = next;
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

    public record Plan(Set<BlockPoint> claims,
                       int structureBlocks,
                       int marginBlocks,
                       int minimumBridgeWidthBlocks,
                       int resolvedCloseRadiusBlocks,
                       int componentCount) {
        public Plan {
            claims = Set.copyOf(claims);
            if (componentCount <= 0) throw new IllegalArgumentException("CITY_FOUNDATION_COMPONENTS_REQUIRED");
        }
    }

}
