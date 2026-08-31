package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContourBandSurfaceClassifierTest {
    private static final int FIELD_BEFORE = 5;
    private static final int CHANNEL_WIDTH = 3;
    private static final int FIELD_AFTER = 5;

    @Test
    void slopedTerrainProducesBandsParallelToElevationContours() {
        BlockBounds bounds = new BlockBounds(0, 0, 47, 31);
        ContourBandSurfaceClassifier.Result result = classify(bounds, List.of(), new BlockPoint(8, 16),
                terrain(bounds, 4, (x, z) -> 64 + x));

        assertEquals(ContourBandSurfaceClassifier.Mode.CONTOUR_NORMAL, result.mode());
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            assertRole(result, 13, z, ContourBandSurfaceClassifier.BandRole.CHANNEL_BEFORE_BANK);
            assertRole(result, 14, z, ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER);
            assertRole(result, 15, z, ContourBandSurfaceClassifier.BandRole.CHANNEL_AFTER_BANK);
        }
    }

    @Test
    void hillTerrainBendsChannelIntoAClosedRing() {
        BlockBounds bounds = new BlockBounds(0, 0, 64, 64);
        BlockPoint center = new BlockPoint(32, 32);
        ContourBandSurfaceClassifier.Result result = classify(bounds, List.of(), center,
                terrain(bounds, 4, (x, z) -> 120 - Math.hypot(x - center.x(), z - center.z())));

        assertEquals(ContourBandSurfaceClassifier.Mode.CONTOUR_NORMAL, result.mode());
        assertRole(result, center.x(), center.z(), ContourBandSurfaceClassifier.BandRole.FIELD);
        assertTrue(role(result, center.x() + 6, center.z()).isChannel());
        assertTrue(role(result, center.x() - 6, center.z()).isChannel());
        assertTrue(role(result, center.x(), center.z() + 6).isChannel());
        assertTrue(role(result, center.x(), center.z() - 6).isChannel());
        assertTrue(role(result, center.x() + 4, center.z() + 4).isChannel());
        assertFalse(role(result, center.x() + 10, center.z()).isChannel());
        Set<BlockPoint> water = waterCells(result);
        BlockPoint ringSeed = water.stream().min(java.util.Comparator
                .comparingDouble((BlockPoint point) -> Math.abs(
                        Math.hypot(point.x() - center.x(), point.z() - center.z()) - 6))
                .thenComparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x)).orElseThrow();
        Set<BlockPoint> firstRing = componentContaining(water, ringSeed, DIRECTIONS_4);
        assertEquals(0, endpointCount(firstRing), "closed contour water must not acquire end caps");
        assertNoSolidWaterSquares(firstRing);
    }

    @Test
    void flatTerrainFallsBackToStableRadialBands() {
        BlockBounds bounds = new BlockBounds(0, 0, 32, 32);
        BlockPoint center = new BlockPoint(16, 16);
        ContourBandSurfaceClassifier.Result result = classify(bounds, List.of(), center,
                terrain(bounds, 4, (x, z) -> 70));

        assertEquals(ContourBandSurfaceClassifier.Mode.RADIAL_FALLBACK, result.mode());
        assertRole(result, 16, 16, ContourBandSurfaceClassifier.BandRole.FIELD);
        assertRole(result, 21, 16, ContourBandSurfaceClassifier.BandRole.CHANNEL_BEFORE_BANK);
        assertRole(result, 22, 16, ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER);
        assertRole(result, 23, 16, ContourBandSurfaceClassifier.BandRole.CHANNEL_AFTER_BANK);
        assertTrue(role(result, 20, 20).isChannel(), "diagonal ring repair must stay inside the channel band");
    }

    @Test
    void exclusionsAreStrictlyRemovedWithoutChangingTheGlobalPhase() {
        BlockBounds bounds = new BlockBounds(0, 0, 31, 16);
        List<LandUseAreaPlan.ScanlineSpan> members = diamondSpans(16, 8, 8);
        List<LandUseAreaPlan.ScanlineSpan> exclusions = verticalSpans(bounds.minZ(), bounds.maxZ(), 15, 17);
        ContourBandSurfaceClassifier.Result result = new ContourBandSurfaceClassifier().classify(
                request(members, exclusions, terrain(bounds, 4, (x, z) -> 70), new BlockPoint(16, 8)));

        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = 15; x <= 17; x++) assertTrue(result.roleAt(x, z).isEmpty());
        }
        assertTrue(result.roleAt(8, 0).isEmpty(), "classification must not fill the member bbox");
        assertRole(result, 21, 8, ContourBandSurfaceClassifier.BandRole.CHANNEL_BEFORE_BANK);
        int expected = members.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::blockCount).sum()
                - exclusions.stream().mapToInt(span -> members.stream()
                .filter(member -> member.z() == span.z())
                .mapToInt(member -> Math.max(0,
                        Math.min(member.maxX(), span.maxX()) - Math.max(member.minX(), span.minX()) + 1))
                .sum()).sum();
        assertEquals(expected, result.fieldBlockCount() + result.channelBlockCount());
    }

    @Test
    void largeExclusionCannotChangeModeOrPhaseOutsideTheExcludedMask() {
        BlockBounds bounds = new BlockBounds(0, 0, 31, 15);
        BlockPoint anchor = new BlockPoint(24, 8);
        LandUseTerrainField terrain = terrain(bounds, 4, (x, z) -> 70 + Math.max(0, x - 16));
        List<LandUseAreaPlan.ScanlineSpan> members = spans(bounds);
        ContourBandSurfaceClassifier classifier = new ContourBandSurfaceClassifier();
        ContourBandSurfaceClassifier.Result full = classifier.classify(
                request(members, List.of(), terrain, anchor));
        ContourBandSurfaceClassifier.Result excluded = classifier.classify(request(members,
                verticalSpans(bounds.minZ(), bounds.maxZ(), 16, 31), terrain, anchor));

        assertEquals(ContourBandSurfaceClassifier.Mode.CONTOUR_NORMAL, full.mode());
        assertEquals(full.mode(), excluded.mode());
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x < 16; x++) {
                assertEquals(full.roleAt(x, z), excluded.roleAt(x, z),
                        "exclusion changed phase at " + x + ',' + z);
            }
        }
    }

    @Test
    void excludedCornerCannotLeaveAFalseDiagonalWaterConnection() {
        List<LandUseAreaPlan.ScanlineSpan> members = List.of(
                new LandUseAreaPlan.ScanlineSpan(0, 0, 0),
                new LandUseAreaPlan.ScanlineSpan(4, 4, 5),
                new LandUseAreaPlan.ScanlineSpan(5, 4, 5));
        List<LandUseAreaPlan.ScanlineSpan> exclusions = List.of(
                new LandUseAreaPlan.ScanlineSpan(4, 4, 4),
                new LandUseAreaPlan.ScanlineSpan(5, 5, 5));
        BlockBounds bounds = new BlockBounds(0, 0, 5, 5);
        ContourBandSurfaceClassifier.Result result = new ContourBandSurfaceClassifier().classify(
                request(members, exclusions, terrain(bounds, 4, (x, z) -> 70), BlockPoint.ORIGIN));
        Set<BlockPoint> water = waterCells(result);

        assertFalse(water.isEmpty());
        assertEquals(componentCount(water, DIRECTIONS_8), componentCount(water, DIRECTIONS_4),
                "unbridgeable excluded corners must not survive as fake diagonal water links");
    }

    @Test
    void vShapedDiagonalWaterUsesOuterConnectorsInsteadOfCreatingAThreeWayBranch() throws Exception {
        BlockBounds bounds = new BlockBounds(0, 0, 2, 1);
        List<LandUseAreaPlan.ScanlineSpan> members = spans(bounds);
        Class<?> maskGridClass = Class.forName(
                ContourBandSurfaceClassifier.class.getName() + "$MaskGrid");
        java.lang.reflect.Method from = maskGridClass.getDeclaredMethod("from", List.class, List.class);
        from.setAccessible(true);
        Object grid = from.invoke(null, members, List.of());
        ContourBandSurfaceClassifier.BandRole[] roles = new ContourBandSurfaceClassifier.BandRole[6];
        java.util.Arrays.fill(roles, ContourBandSurfaceClassifier.BandRole.FIELD);
        roles[0] = ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER;
        roles[2] = ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER;
        roles[4] = ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER;
        java.lang.reflect.Method repair = ContourBandSurfaceClassifier.class.getDeclaredMethod(
                "repairDiagonalWaterLinks", ContourBandSurfaceClassifier.BandRole[].class, maskGridClass);
        repair.setAccessible(true);
        repair.invoke(null, roles, grid);

        Set<BlockPoint> water = new HashSet<>();
        for (int z = 0; z <= 1; z++) {
            for (int x = 0; x <= 2; x++) {
                if (roles[z * 3 + x] == ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER) {
                    water.add(new BlockPoint(x, z));
                }
            }
        }
        assertFalse(water.contains(new BlockPoint(1, 0)), "shared connector would create degree three");
        assertTrue(water.contains(new BlockPoint(0, 1)));
        assertTrue(water.contains(new BlockPoint(2, 1)));
        Set<BlockPoint> path = componentContaining(water, new BlockPoint(0, 0), DIRECTIONS_4);
        assertEquals(water, path);
        assertEquals(2, endpointCount(path));
        assertTrue(path.stream().allMatch(point -> degree4(path, point) <= 2));
    }

    @Test
    void repeatedClassificationIsExactlyDeterministic() {
        BlockBounds bounds = new BlockBounds(-16, -16, 31, 31);
        LandUseTerrainField terrain = terrain(bounds, 4,
                (x, z) -> 80 + 0.4 * x + 0.15 * z + Math.sin(z / 9.0));
        ContourBandSurfaceClassifier.Request request = request(spans(bounds), List.of(), terrain,
                new BlockPoint(4, 4));
        ContourBandSurfaceClassifier classifier = new ContourBandSurfaceClassifier();

        assertEquals(classifier.classify(request), classifier.classify(request));
    }

    @Test
    void phaseDoesNotRestartAtChunkBoundaries() {
        BlockBounds bounds = new BlockBounds(0, 0, 47, 8);
        ContourBandSurfaceClassifier.Result result = classify(bounds, List.of(), new BlockPoint(0, 0),
                terrain(bounds, 4, (x, z) -> 70));

        assertRole(result, 16, 0, ContourBandSurfaceClassifier.BandRole.FIELD);
        assertRole(result, 18, 0, ContourBandSurfaceClassifier.BandRole.CHANNEL_BEFORE_BANK);
        assertRole(result, 19, 0, ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER);
        assertRole(result, 20, 0, ContourBandSurfaceClassifier.BandRole.CHANNEL_AFTER_BANK);
        assertRole(result, 31, 0, ContourBandSurfaceClassifier.BandRole.CHANNEL_BEFORE_BANK);
        assertEquals(13, result.repeatPeriodBlocks());
    }

    @Test
    void productionCellCoordinatesRemainCenteredAtBlockMinForStepSixteenAndNegativeBounds() {
        BlockBounds bounds = new BlockBounds(-80, 48, -16, 112);
        BlockPoint center = new BlockPoint(-48, 80);
        ContourBandSurfaceClassifier.Result result = classify(bounds, List.of(), center,
                terrain(bounds, 16, (x, z) -> 160 - Math.hypot(x - center.x(), z - center.z())));

        assertEquals(ContourBandSurfaceClassifier.Mode.CONTOUR_NORMAL, result.mode());
        assertRole(result, center.x(), center.z(), ContourBandSurfaceClassifier.BandRole.FIELD);
        assertTrue(role(result, center.x() + 6, center.z()).isChannel());
        assertTrue(role(result, center.x() - 6, center.z()).isChannel());
        assertTrue(role(result, center.x(), center.z() + 6).isChannel());
        assertTrue(role(result, center.x(), center.z() - 6).isChannel());
    }

    @Test
    void rotatedSlopeUsesGradientNormalPropagationAndProducesFourConnectedWaterLines() {
        BlockBounds bounds = new BlockBounds(-32, -32, 31, 31);
        ContourBandSurfaceClassifier.Result result = classify(bounds, List.of(), new BlockPoint(0, 0),
                terrain(bounds, 4, (x, z) -> 80 + x + z));

        assertEquals(ContourBandSurfaceClassifier.Mode.CONTOUR_NORMAL, result.mode());
        Set<BlockPoint> water = waterCells(result);
        assertEquals(componentCount(water, DIRECTIONS_8), componentCount(water, DIRECTIONS_4),
                "diagonal contour steps must be repaired into four-neighbor water paths");
        assertLocalTangentsNearlyOrthogonal(water, point -> new Vector(1, 1), point -> true, 0.42);
        Set<BlockPoint> line = largestComponent(water, DIRECTIONS_4);
        assertTrue(endpointCount(line) <= 2, "an open contour water line may have at most two endpoints");
    }

    @Test
    void saddleTerrainKeepsLocalWaterTangentsNearOrthogonalToChangingGradient() {
        BlockBounds bounds = new BlockBounds(-32, -32, 32, 32);
        BlockPoint anchor = new BlockPoint(12, 0);
        DoubleBinaryOperator saddle = (x, z) -> 90 + (x * x - z * z) / 16.0;
        ContourBandSurfaceClassifier.Result result = classify(bounds, List.of(), anchor,
                terrain(bounds, 4, saddle));

        assertEquals(ContourBandSurfaceClassifier.Mode.CONTOUR_NORMAL, result.mode());
        Set<BlockPoint> water = waterCells(result);
        assertEquals(componentCount(water, DIRECTIONS_8), componentCount(water, DIRECTIONS_4));
        assertLocalTangentsNearlyOrthogonal(water,
                point -> new Vector(point.x() / 8.0, -point.z() / 8.0),
                point -> point.x() > 4 && Math.hypot(point.x(), point.z()) > 8,
                0.58);
    }

    private static ContourBandSurfaceClassifier.Result classify(BlockBounds bounds,
                                                                 List<LandUseAreaPlan.ScanlineSpan> exclusions,
                                                                 BlockPoint anchor,
                                                                 LandUseTerrainField terrain) {
        return new ContourBandSurfaceClassifier().classify(request(spans(bounds), exclusions, terrain, anchor));
    }

    private static ContourBandSurfaceClassifier.Request request(
            List<LandUseAreaPlan.ScanlineSpan> members,
            List<LandUseAreaPlan.ScanlineSpan> exclusions,
            LandUseTerrainField terrain,
            BlockPoint anchor) {
        return new ContourBandSurfaceClassifier.Request(members, exclusions, terrain, anchor,
                FIELD_BEFORE, CHANNEL_WIDTH, FIELD_AFTER);
    }

    private static void assertRole(ContourBandSurfaceClassifier.Result result,
                                   int x,
                                   int z,
                                   ContourBandSurfaceClassifier.BandRole expected) {
        assertEquals(expected, role(result, x, z), "unexpected role at " + x + ',' + z);
    }

    private static ContourBandSurfaceClassifier.BandRole role(ContourBandSurfaceClassifier.Result result,
                                                               int x,
                                                               int z) {
        return result.roleAt(x, z).orElseThrow(() -> new AssertionError("missing role at " + x + ',' + z));
    }

    private static List<LandUseAreaPlan.ScanlineSpan> spans(BlockBounds bounds) {
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            spans.add(new LandUseAreaPlan.ScanlineSpan(z, bounds.minX(), bounds.maxX()));
        }
        return List.copyOf(spans);
    }

    private static List<LandUseAreaPlan.ScanlineSpan> diamondSpans(int centerX, int centerZ, int radius) {
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            int halfWidth = radius - Math.abs(z - centerZ);
            spans.add(new LandUseAreaPlan.ScanlineSpan(z, centerX - halfWidth, centerX + halfWidth));
        }
        return List.copyOf(spans);
    }

    private static List<LandUseAreaPlan.ScanlineSpan> verticalSpans(int minZ,
                                                                    int maxZ,
                                                                    int minX,
                                                                    int maxX) {
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) spans.add(new LandUseAreaPlan.ScanlineSpan(z, minX, maxX));
        return List.copyOf(spans);
    }

    private static LandUseTerrainField terrain(BlockBounds bounds,
                                                int step,
                                                DoubleBinaryOperator elevation) {
        int minCellX = Math.floorDiv(bounds.minX(), step);
        int maxCellX = Math.floorDiv(bounds.maxX(), step);
        int minCellZ = Math.floorDiv(bounds.minZ(), step);
        int maxCellZ = Math.floorDiv(bounds.maxZ(), step);
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                int blockMinX = cellX * step;
                int blockMinZ = cellZ * step;
                cells.add(new LandUseTerrainField.Cell(cellX, cellZ, blockMinX, blockMinZ, step,
                        elevation.applyAsDouble(blockMinX, blockMinZ), 0, 0, 0, false, 0, 40,
                        "minecraft:plains", "plain", "test", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city_test",
                bounds, step, cells);
    }

    private static final int[][] DIRECTIONS_4 = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};
    private static final int[][] DIRECTIONS_8 = {
            {-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}
    };

    private static Set<BlockPoint> waterCells(ContourBandSurfaceClassifier.Result result) {
        Set<BlockPoint> water = new HashSet<>();
        result.spans().stream()
                .filter(span -> span.role() == ContourBandSurfaceClassifier.BandRole.CHANNEL_WATER)
                .forEach(span -> {
                    for (int x = span.minX(); x <= span.maxX(); x++) water.add(new BlockPoint(x, span.z()));
                });
        return water;
    }

    private static int componentCount(Set<BlockPoint> points, int[][] directions) {
        Set<BlockPoint> remaining = new HashSet<>(points);
        int count = 0;
        while (!remaining.isEmpty()) {
            BlockPoint start = remaining.iterator().next();
            Set<BlockPoint> component = componentContaining(remaining, start, directions);
            remaining.removeAll(component);
            count++;
        }
        return count;
    }

    private static Set<BlockPoint> largestComponent(Set<BlockPoint> points, int[][] directions) {
        Set<BlockPoint> remaining = new HashSet<>(points);
        Set<BlockPoint> largest = Set.of();
        while (!remaining.isEmpty()) {
            BlockPoint start = remaining.iterator().next();
            Set<BlockPoint> component = componentContaining(remaining, start, directions);
            remaining.removeAll(component);
            if (component.size() > largest.size()) largest = component;
        }
        return largest;
    }

    private static Set<BlockPoint> componentContaining(Set<BlockPoint> points,
                                                       BlockPoint start,
                                                       int[][] directions) {
        assertTrue(points.contains(start), "expected component seed " + start + " to be water");
        Set<BlockPoint> component = new HashSet<>();
        ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
        component.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPoint current = queue.removeFirst();
            for (int[] direction : directions) {
                BlockPoint next = new BlockPoint(current.x() + direction[0], current.z() + direction[1]);
                if (points.contains(next) && component.add(next)) queue.addLast(next);
            }
        }
        return component;
    }

    private static int endpointCount(Set<BlockPoint> component) {
        int endpoints = 0;
        for (BlockPoint point : component) {
            int degree = 0;
            for (int[] direction : DIRECTIONS_4) {
                if (component.contains(new BlockPoint(point.x() + direction[0], point.z() + direction[1]))) {
                    degree++;
                }
            }
            if (degree == 1) endpoints++;
        }
        return endpoints;
    }

    private static int degree4(Set<BlockPoint> component, BlockPoint point) {
        int degree = 0;
        for (int[] direction : DIRECTIONS_4) {
            if (component.contains(new BlockPoint(point.x() + direction[0], point.z() + direction[1]))) degree++;
        }
        return degree;
    }

    private static void assertNoSolidWaterSquares(Set<BlockPoint> water) {
        for (BlockPoint point : water) {
            Set<BlockPoint> square = Set.of(point,
                    new BlockPoint(point.x() + 1, point.z()),
                    new BlockPoint(point.x(), point.z() + 1),
                    new BlockPoint(point.x() + 1, point.z() + 1));
            assertFalse(water.containsAll(square), "one diagonal turn must not become a 2x2 water block at " + point);
        }
    }

    private static void assertLocalTangentsNearlyOrthogonal(Set<BlockPoint> water,
                                                            java.util.function.Function<BlockPoint, Vector> gradient,
                                                            Predicate<BlockPoint> filter,
                                                            double maxMeanAlignment) {
        double alignmentTotal = 0;
        int samples = 0;
        for (BlockPoint center : water) {
            if (!filter.test(center)) continue;
            List<BlockPoint> local = water.stream()
                    .filter(point -> Math.abs(point.x() - center.x()) <= 3
                            && Math.abs(point.z() - center.z()) <= 3)
                    .toList();
            if (local.size() < 5) continue;
            double meanX = local.stream().mapToDouble(BlockPoint::x).average().orElseThrow();
            double meanZ = local.stream().mapToDouble(BlockPoint::z).average().orElseThrow();
            double xx = 0;
            double xz = 0;
            double zz = 0;
            for (BlockPoint point : local) {
                double dx = point.x() - meanX;
                double dz = point.z() - meanZ;
                xx += dx * dx;
                xz += dx * dz;
                zz += dz * dz;
            }
            double tangentAngle = 0.5 * Math.atan2(2 * xz, xx - zz);
            Vector tangent = new Vector(Math.cos(tangentAngle), Math.sin(tangentAngle));
            Vector normal = gradient.apply(center);
            double normalMagnitude = Math.hypot(normal.x, normal.z);
            if (normalMagnitude < 0.02) continue;
            alignmentTotal += Math.abs(tangent.x * normal.x + tangent.z * normal.z) / normalMagnitude;
            samples++;
        }
        assertTrue(samples >= 20, "not enough local tangent samples: " + samples);
        assertTrue(alignmentTotal / samples <= maxMeanAlignment,
                "water tangent/gradient alignment was " + alignmentTotal / samples);
    }

    private record Vector(double x, double z) {
    }
}
