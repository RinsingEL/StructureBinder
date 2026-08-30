package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
            try {
                ResolvedPlatform platform = resolvePlatform(local, planningBounds, settings, bridgeHalfWidth);
                claims.addAll(platform.claims());
                resolvedRadius = Math.max(resolvedRadius, platform.radius());
            } catch (IllegalArgumentException failure) {
                if (local.size() == 1 || failure.getMessage() == null
                        || !failure.getMessage().startsWith("CITY_FOUNDATION_THIN_BRIDGE:")) throw failure;
                List<List<BlockBounds>> localGroups = footprintGroups(local, settings.closeRadiusBlocks());
                if (localGroups.size() == 1) {
                    localGroups = local.stream().map(List::of).toList();
                }
                for (List<BlockBounds> localGroup : localGroups) {
                    try {
                        ResolvedPlatform platform = resolvePlatform(localGroup, planningBounds,
                                settings, bridgeHalfWidth);
                        claims.addAll(platform.claims());
                        resolvedRadius = Math.max(resolvedRadius, platform.radius());
                    } catch (IllegalArgumentException localFailure) {
                        if (localGroup.size() == 1 || localFailure.getMessage() == null
                                || !localFailure.getMessage().startsWith("CITY_FOUNDATION_THIN_BRIDGE:")) {
                            throw localFailure;
                        }
                        for (BlockBounds footprint : localGroup) {
                            ResolvedPlatform platform = resolvePlatform(List.of(footprint), planningBounds,
                                    settings, bridgeHalfWidth);
                            claims.addAll(platform.claims());
                            resolvedRadius = Math.max(resolvedRadius, platform.radius());
                        }
                    }
                }
            }
        }
        Set<BlockPoint> stableClaims = claims.stream().sorted(POINT_ORDER)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new Plan(stableClaims, structureMask.size(), marginMask.size(), bridgeHalfWidth * 2 + 1,
                resolvedRadius, components(stableClaims).size());
    }

    private static ResolvedPlatform resolvePlatform(List<BlockBounds> footprints,
                                                    BlockBounds planningBounds,
                                                    LandUseSeedGroup.FoundationSettings settings,
                                                    int bridgeHalfWidth) {
        Set<BlockPoint> structureMask = rasterize(footprints);
        Set<BlockPoint> marginMask = dilate(structureMask, settings.structureMarginBlocks(), planningBounds);
        int minimumRadius = settings.closeRadiusBlocks();
        int maximumRadius = settings.maxJoinDistanceBlocks();
        Attempt resolved = attempt(marginMask, minimumRadius, planningBounds,
                bridgeHalfWidth, footprints.size() > 1);
        if (!resolved.valid() && maximumRadius > minimumRadius) {
            Attempt maximum = attempt(marginMask, maximumRadius, planningBounds,
                    bridgeHalfWidth, footprints.size() > 1);
            if (maximum.valid()) {
                int low = minimumRadius + 1;
                int high = maximumRadius - 1;
                resolved = maximum;
                while (low <= high) {
                    int middle = low + (high - low) / 2;
                    Attempt candidate = attempt(marginMask, middle, planningBounds,
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
        return new ResolvedPlatform(resolved.claims(), resolved.radius());
    }

    private static Attempt attempt(Set<BlockPoint> source,
                                   int radius,
                                   BlockBounds planningBounds,
                                   int bridgeHalfWidth,
                                   boolean requireDurableBridge) {
        ClosedMask morphology = closeWithinBounds(source, radius, planningBounds);
        Set<BlockPoint> expanded = morphology.expanded();
        boolean expandedConnected = components(expanded).size() == 1;
        Set<BlockPoint> closed = morphology.closed();
        List<Set<BlockPoint>> closedComponents = components(closed);
        boolean singleComponent = closedComponents.size() == 1;
        boolean durable = singleComponent;
        if (durable && requireDurableBridge) {
            Set<BlockPoint> durableCore = erode(closed, bridgeHalfWidth);
            durable = !durableCore.isEmpty() && components(durableCore).size() == 1;
        }
        return new Attempt(radius, closed, closedComponents, expandedConnected, singleComponent && durable);
    }

    private static ClosedMask closeWithinBounds(Set<BlockPoint> source,
                                                int radius,
                                                BlockBounds planningBounds) {
        BlockBounds sourceBounds = bounds(source);
        int minX = clamp((long) sourceBounds.minX() - radius, planningBounds.minX(), planningBounds.maxX());
        int minZ = clamp((long) sourceBounds.minZ() - radius, planningBounds.minZ(), planningBounds.maxZ());
        int maxX = clamp((long) sourceBounds.maxX() + radius, planningBounds.minX(), planningBounds.maxX());
        int maxZ = clamp((long) sourceBounds.maxZ() + radius, planningBounds.minZ(), planningBounds.maxZ());
        int width = maxX - minX + 1;
        int height = maxZ - minZ + 1;
        boolean[] current = new boolean[Math.multiplyExact(width, height)];
        for (BlockPoint point : source) current[index(point.x(), point.z(), minX, minZ, width)] = true;

        for (int step = 0; step < radius; step++) {
            boolean[] next = Arrays.copyOf(current, current.length);
            for (int z = 0; z < height; z++) {
                int row = z * width;
                for (int x = 0; x < width; x++) {
                    int cell = row + x;
                    if (!current[cell]) continue;
                    if (z > 0) next[cell - width] = true;
                    if (x > 0) next[cell - 1] = true;
                    if (x + 1 < width) next[cell + 1] = true;
                    if (z + 1 < height) next[cell + width] = true;
                }
            }
            current = next;
        }
        Set<BlockPoint> expanded = points(current, minX, minZ, width, height);

        for (int step = 0; step < radius; step++) {
            boolean[] next = new boolean[current.length];
            boolean any = false;
            for (int z = 0; z < height; z++) {
                int worldZ = minZ + z;
                int row = z * width;
                for (int x = 0; x < width; x++) {
                    int cell = row + x;
                    if (!current[cell]) continue;
                    int worldX = minX + x;
                    boolean north = worldZ == planningBounds.minZ()
                            || z > 0 && current[cell - width];
                    boolean west = worldX == planningBounds.minX()
                            || x > 0 && current[cell - 1];
                    boolean east = worldX == planningBounds.maxX()
                            || x + 1 < width && current[cell + 1];
                    boolean south = worldZ == planningBounds.maxZ()
                            || z + 1 < height && current[cell + width];
                    if (north && west && east && south) {
                        next[cell] = true;
                        any = true;
                    }
                }
            }
            current = next;
            if (!any) break;
        }
        for (BlockPoint point : source) current[index(point.x(), point.z(), minX, minZ, width)] = true;
        return new ClosedMask(expanded, points(current, minX, minZ, width, height));
    }

    private static Set<BlockPoint> points(boolean[] mask,
                                          int minX,
                                          int minZ,
                                          int width,
                                          int height) {
        Set<BlockPoint> result = new HashSet<>();
        for (int z = 0; z < height; z++) {
            int row = z * width;
            for (int x = 0; x < width; x++) {
                if (mask[row + x]) result.add(new BlockPoint(minX + x, minZ + z));
            }
        }
        return result;
    }

    private static int index(int x, int z, int minX, int minZ, int width) {
        return Math.addExact(Math.multiplyExact(z - minZ, width), x - minX);
    }

    private static int clamp(long value, int minimum, int maximum) {
        return (int) Math.max(minimum, Math.min(maximum, value));
    }

    private static IllegalArgumentException disconnected(List<Set<BlockPoint>> components, int maximumRadius) {
        return new IllegalArgumentException("CITY_FOUNDATION_DISCONNECTED:" + components.size()
                + ":nearestGap=" + minimumComponentGap(components) + ":maxJoin=" + maximumRadius);
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

    private record ClosedMask(Set<BlockPoint> expanded, Set<BlockPoint> closed) {
    }

    private record ResolvedPlatform(Set<BlockPoint> claims, int radius) {
        private ResolvedPlatform {
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

}
