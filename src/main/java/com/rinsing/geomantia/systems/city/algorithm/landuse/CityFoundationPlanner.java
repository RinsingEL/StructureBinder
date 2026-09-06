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

/** Builds one broad city execution domain from the frozen structure footprints. */
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
            claims.addAll(convexConstructionEnvelope(localMargin));
            resolvedRadius = Math.max(resolvedRadius, settings.closeRadiusBlocks());
        }
        Set<BlockPoint> stableClaims = claims.stream().sorted(POINT_ORDER)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new Plan(stableClaims, structureMask.size(), marginMask.size(), bridgeHalfWidth * 2 + 1,
                resolvedRadius, components(stableClaims).size());
    }

    /** Fill the near-connected construction group's envelope, not a set of per-building islands. */
    private static Set<BlockPoint> convexConstructionEnvelope(Set<BlockPoint> source) {
        List<BlockPoint> sorted = source.stream().sorted(Comparator.comparingInt(BlockPoint::x)
                .thenComparingInt(BlockPoint::z)).toList();
        if (sorted.size() < 3) return source;
        List<BlockPoint> hull = new ArrayList<>();
        for (BlockPoint point : sorted) {
            while (hull.size() >= 2 && cross(hull.get(hull.size()-2), hull.get(hull.size()-1), point) <= 0)
                hull.remove(hull.size()-1);
            hull.add(point);
        }
        int lowerSize = hull.size();
        for (int i = sorted.size()-2; i >= 0; i--) {
            BlockPoint point = sorted.get(i);
            while (hull.size() > lowerSize && cross(hull.get(hull.size()-2), hull.get(hull.size()-1), point) <= 0)
                hull.remove(hull.size()-1);
            hull.add(point);
        }
        hull.remove(hull.size()-1);
        Set<BlockPoint> result = new HashSet<>(source);
        BlockBounds extent = bounds(source);
        for (int z = extent.minZ(); z <= extent.maxZ(); z++) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < hull.size(); i++) {
                BlockPoint a = hull.get(i), b = hull.get((i+1)%hull.size());
                if (z < Math.min(a.z(), b.z()) || z > Math.max(a.z(), b.z())) continue;
                if (a.z() == b.z()) { min = Math.min(min, Math.min(a.x(), b.x())); max = Math.max(max, Math.max(a.x(), b.x())); }
                else { double x = a.x() + (double)(z-a.z())*(b.x()-a.x())/(b.z()-a.z()); min = Math.min(min,x); max = Math.max(max,x); }
            }
            if (!Double.isFinite(min)) continue;
            for (int x = (int)Math.floor(min); x <= (int)Math.ceil(max); x++) result.add(new BlockPoint(x,z));
        }
        return result;
    }

    private static long cross(BlockPoint a, BlockPoint b, BlockPoint c) {
        return (long)(b.x()-a.x())*(c.z()-a.z()) - (long)(b.z()-a.z())*(c.x()-a.x());
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
        for (int step = 0; step < radius; step++) {
            Set<BlockPoint> next = new HashSet<>(result);
            for (BlockPoint point : result) {
                for (int[] direction : DIRECTIONS) {
                    BlockPoint neighbor = new BlockPoint(point.x() + direction[0], point.z() + direction[1]);
                    if (planningBounds.contains(neighbor.x(), neighbor.z())) next.add(neighbor);
                }
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
