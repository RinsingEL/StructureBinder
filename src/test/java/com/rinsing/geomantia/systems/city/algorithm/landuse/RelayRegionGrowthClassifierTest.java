package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayRegionGrowthClassifierTest {
    @Test
    void everyCellComesFromAdjacentGrowthAndRegionSpansAuditExactAreas() {
        List<LandUseAreaPlan.ScanlineSpan> members = rectangleSpans(0, 19, 0, 13);
        List<LandUseAreaPlan.ScanlineSpan> exclusions = List.of(
                new LandUseAreaPlan.ScanlineSpan(0, 16, 19),
                new LandUseAreaPlan.ScanlineSpan(1, 18, 19),
                new LandUseAreaPlan.ScanlineSpan(12, 0, 1),
                new LandUseAreaPlan.ScanlineSpan(13, 0, 3));
        RelayRegionGrowthClassifier.Result result = classify(members, exclusions, new BlockPoint(4, 6),
                0x71a2L, farmStages());

        Set<BlockPoint> expected = cells(members);
        expected.removeAll(cells(exclusions));
        assertEquals(expected, expandedRoleCells(result));
        assertEquals(expected.size(), result.coveredBlockCount());
        assertGrowthProvenance(result);

        Map<String, Integer> spanAreas = new HashMap<>();
        result.regionSpans().forEach(span -> spanAreas.merge(span.regionId(),
                span.maxX() - span.minX() + 1, Integer::sum));
        for (RelayRegionGrowthClassifier.RegionTrace trace : result.regions()) {
            assertEquals(trace.targetAreaBlocks(), trace.actualAreaBlocks());
            assertEquals(trace.actualAreaBlocks(), spanAreas.get(trace.regionId()));
            assertTrue(result.regionSpans().stream().filter(span -> span.regionId().equals(trace.regionId()))
                    .allMatch(span -> span.roleRef().equals(trace.roleRef())));
        }
    }

    @Test
    void everyRegionStartsAtItsParentLocalInterfaceIncludingExplicitOlderParent() {
        List<RelayRegionGrowthClassifier.GrowthStage> stages = List.of(
                stage("field-a", "", "CULTIVATED", 0.38, RelayRegionGrowthClassifier.GrowthForm.PATCH),
                stage("path", "", "BANK", 0.12, RelayRegionGrowthClassifier.GrowthForm.CORRIDOR),
                stage("field-b", "field-a", "CULTIVATED", 0.50,
                        RelayRegionGrowthClassifier.GrowthForm.PATCH));
        RelayRegionGrowthClassifier.Result result = classify(rectangleSpans(0, 19, 0, 11), List.of(),
                new BlockPoint(3, 5), 191L, stages);

        assertEquals("field-a", result.regions().get(1).parentRegionId());
        assertEquals("field-a", result.regions().get(2).parentRegionId());
        for (int index = 1; index < result.regions().size(); index++) {
            RelayRegionGrowthClassifier.RegionTrace region = result.regions().get(index);
            RelayRegionGrowthClassifier.ExpansionStep start = region.expansionTrace().get(0);
            assertEquals(RelayRegionGrowthClassifier.ProvenanceKind.RELAY_INTERFACE,
                    start.provenanceKind());
            assertTrue(adjacent(start.point(), start.from()));
            assertEquals(region.parentRegionId(),
                    result.regionAt(start.from().x(), start.from().z()).orElseThrow());
        }
    }

    @Test
    void corridorUsesNarrowDirectionContinuousFrontierGrowthWhilePatchBranches() {
        RelayRegionGrowthClassifier.Result result = classify(rectangleSpans(0, 23, 0, 15), List.of(),
                new BlockPoint(4, 7), 0x5eedL, List.of(
                        stage("patch-a", "", "FIELD", 0.45, RelayRegionGrowthClassifier.GrowthForm.PATCH),
                        stage("channel", "", "WATER", 0.12, RelayRegionGrowthClassifier.GrowthForm.CORRIDOR),
                        stage("patch-b", "", "FIELD", 0.43, RelayRegionGrowthClassifier.GrowthForm.PATCH)));

        RelayRegionGrowthClassifier.RegionTrace corridor = result.regions().get(1);
        int continuesFromPrevious = 0;
        for (int index = 2; index < corridor.expansionTrace().size(); index++) {
            if (corridor.expansionTrace().get(index).from()
                    .equals(corridor.expansionTrace().get(index - 1).point())) continuesFromPrevious++;
        }
        assertTrue(continuesFromPrevious >= corridor.actualAreaBlocks() * 0.20,
                "corridor must retain directional continuation across multiple active tips");
        Set<BlockPoint> corridorCells = traceCells(corridor);
        Set<BlockPoint> patch = traceCells(result.regions().get(0));
        double corridorDenseShare = corridorCells.stream()
                .filter(point -> neighborCount(point, corridorCells) >= 3).count()
                / (double) corridorCells.size();
        double patchDenseShare = patch.stream().filter(point -> neighborCount(point, patch) >= 3).count()
                / (double) patch.size();
        assertTrue(corridorDenseShare < patchDenseShare,
                "corridor must remain locally narrower than PATCH growth");
        assertTrue(patch.stream().anyMatch(point -> neighborCount(point, patch) >= 3),
                "PATCH must be allowed to branch into a locally cohesive area");
    }

    @Test
    void stableSeedAndNormalizedMaskMakeResultsIndependentOfInputSpanOrder() {
        List<LandUseAreaPlan.ScanlineSpan> ordered = new ArrayList<>(rectangleSpans(-7, 12, -5, 8));
        ordered.add(new LandUseAreaPlan.ScanlineSpan(0, -2, 4));
        List<LandUseAreaPlan.ScanlineSpan> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled, new java.util.Random(77L));
        List<LandUseAreaPlan.ScanlineSpan> exclusions = List.of(
                new LandUseAreaPlan.ScanlineSpan(-4, 9, 12),
                new LandUseAreaPlan.ScanlineSpan(7, -7, -4));

        RelayRegionGrowthClassifier.Result first = classify(ordered, exclusions,
                new BlockPoint(0, 0), 404L, farmStages());
        RelayRegionGrowthClassifier.Result second = classify(shuffled, exclusions,
                new BlockPoint(0, 0), 404L, farmStages());
        RelayRegionGrowthClassifier.Result differentSeed = classify(ordered, exclusions,
                new BlockPoint(0, 0), 405L, farmStages());

        assertEquals(first, second);
        assertNotEquals(first.regionSpans(), differentSeed.regionSpans());
    }

    @Test
    void exclusionThatDisconnectsTheMaskHardFailsWithoutReseedingOrShapeFallback() {
        List<LandUseAreaPlan.ScanlineSpan> exclusions = new ArrayList<>();
        for (int z = 0; z <= 8; z++) exclusions.add(new LandUseAreaPlan.ScanlineSpan(z, 5, 5));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> classify(
                rectangleSpans(0, 10, 0, 8), exclusions, new BlockPoint(2, 4), 1L, farmStages()));

        assertEquals("RELAY_GROWTH_MASK_DISCONNECTED", error.getMessage());
    }

    @Test
    void sourceMustBeAnExactAllowedCellAndCannotFallBackToANearestShapeCenter() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> classify(
                rectangleSpans(0, 8, 0, 8), List.of(new LandUseAreaPlan.ScanlineSpan(4, 4, 4)),
                new BlockPoint(4, 4), 2L, farmStages()));

        assertEquals("RELAY_GROWTH_SOURCE_NOT_ALLOWED", error.getMessage());
    }

    @Test
    void regionAssignmentIsNotAConcentricDistanceLayer() {
        BlockPoint source = new BlockPoint(10, 10);
        RelayRegionGrowthClassifier.Result result = classify(rectangleSpans(0, 20, 0, 20), List.of(),
                source, 8821L, List.of(
                        stage("first", "", "FLOWER", 0.34, RelayRegionGrowthClassifier.GrowthForm.PATCH),
                        stage("break", "", "LEAF", 0.14, RelayRegionGrowthClassifier.GrowthForm.CORRIDOR),
                        stage("last", "", "FLOWER", 0.52, RelayRegionGrowthClassifier.GrowthForm.PATCH)));

        Set<String> regionsOnSameManhattanShell = new HashSet<>();
        for (int z = 0; z <= 20; z++) {
            for (int x = 0; x <= 20; x++) {
                if (Math.abs(x - source.x()) + Math.abs(z - source.z()) == 8) {
                    regionsOnSameManhattanShell.add(result.regionAt(x, z).orElseThrow());
                }
            }
        }
        assertTrue(regionsOnSameManhattanShell.size() >= 2,
                "frontier relay regions must not collapse into global distance rings");
    }

    @Test
    void parentRelaySourceCompletesExactRolesAcrossARealBottleneckParcel() {
        List<LandUseAreaPlan.ScanlineSpan> members = List.of(
                new LandUseAreaPlan.ScanlineSpan(0, 0, 5),
                new LandUseAreaPlan.ScanlineSpan(1, 0, 6),
                new LandUseAreaPlan.ScanlineSpan(2, 0, 6),
                new LandUseAreaPlan.ScanlineSpan(3, 0, 6),
                new LandUseAreaPlan.ScanlineSpan(4, 0, 0),
                new LandUseAreaPlan.ScanlineSpan(4, 4, 7),
                new LandUseAreaPlan.ScanlineSpan(5, 4, 14),
                new LandUseAreaPlan.ScanlineSpan(6, 7, 14),
                new LandUseAreaPlan.ScanlineSpan(7, 7, 14),
                new LandUseAreaPlan.ScanlineSpan(8, 7, 14),
                new LandUseAreaPlan.ScanlineSpan(9, 7, 14),
                new LandUseAreaPlan.ScanlineSpan(10, 7, 14),
                new LandUseAreaPlan.ScanlineSpan(11, 7, 14));

        RelayRegionGrowthClassifier.Result result = classify(members, List.of(), new BlockPoint(7, 11),
                0x4f67a2L, List.of(
                        stage("green-a", "", "GREEN", 0.425,
                                RelayRegionGrowthClassifier.GrowthForm.PATCH),
                        stage("ground", "", "GROUND", 0.15,
                                RelayRegionGrowthClassifier.GrowthForm.PATCH),
                        stage("green-b", "", "GREEN", 0.425,
                                RelayRegionGrowthClassifier.GrowthForm.PATCH)));

        assertEquals(List.of(39, 13, 39), result.regions().stream()
                .map(RelayRegionGrowthClassifier.RegionTrace::actualAreaBlocks).toList());
        assertEquals(91, result.coveredBlockCount());
        assertGrowthProvenance(result);
    }

    private static RelayRegionGrowthClassifier.Result classify(
            List<LandUseAreaPlan.ScanlineSpan> members,
            List<LandUseAreaPlan.ScanlineSpan> exclusions,
            BlockPoint source,
            long seed,
            List<RelayRegionGrowthClassifier.GrowthStage> stages) {
        return new RelayRegionGrowthClassifier().classify(new RelayRegionGrowthClassifier.Request(
                members, exclusions, source, seed, stages));
    }

    private static List<RelayRegionGrowthClassifier.GrowthStage> farmStages() {
        return List.of(
                stage("field-a", "", "CULTIVATED", 0.55, RelayRegionGrowthClassifier.GrowthForm.PATCH),
                stage("bank-a", "", "BANK", 0.08, RelayRegionGrowthClassifier.GrowthForm.CORRIDOR),
                stage("water", "", "WATER", 0.06, RelayRegionGrowthClassifier.GrowthForm.CORRIDOR),
                stage("bank-b", "", "BANK", 0.08, RelayRegionGrowthClassifier.GrowthForm.CORRIDOR),
                stage("field-b", "", "CULTIVATED", 0.23, RelayRegionGrowthClassifier.GrowthForm.PATCH));
    }

    private static RelayRegionGrowthClassifier.GrowthStage stage(
            String regionId,
            String parentRegionId,
            String roleRef,
            double share,
            RelayRegionGrowthClassifier.GrowthForm form) {
        return new RelayRegionGrowthClassifier.GrowthStage(regionId, parentRegionId, roleRef, share, form);
    }

    private static void assertGrowthProvenance(RelayRegionGrowthClassifier.Result result) {
        Set<BlockPoint> grown = new HashSet<>();
        for (RelayRegionGrowthClassifier.RegionTrace region : result.regions()) {
            Set<BlockPoint> currentRegion = new HashSet<>();
            for (RelayRegionGrowthClassifier.ExpansionStep step : region.expansionTrace()) {
                if (step.provenanceKind() == RelayRegionGrowthClassifier.ProvenanceKind.ROOT_SOURCE) {
                    assertEquals(0, step.ordinal());
                } else {
                    assertTrue(adjacent(step.point(), step.from()));
                    assertTrue(grown.contains(step.from()), "provenance must already be grown: " + step);
                    if (step.provenanceKind() == RelayRegionGrowthClassifier.ProvenanceKind.REGION_FRONTIER) {
                        assertTrue(currentRegion.contains(step.from()));
                    }
                }
                assertTrue(grown.add(step.point()), "cell claimed twice: " + step.point());
                currentRegion.add(step.point());
            }
        }
    }

    private static Set<BlockPoint> expandedRoleCells(RelayRegionGrowthClassifier.Result result) {
        Set<BlockPoint> cells = new HashSet<>();
        result.roleSpans().forEach(span -> {
            for (int x = span.minX(); x <= span.maxX(); x++) cells.add(new BlockPoint(x, span.z()));
        });
        return cells;
    }

    private static Set<BlockPoint> traceCells(RelayRegionGrowthClassifier.RegionTrace trace) {
        Set<BlockPoint> cells = new HashSet<>();
        trace.expansionTrace().forEach(step -> cells.add(step.point()));
        return cells;
    }

    private static int neighborCount(BlockPoint point, Set<BlockPoint> cells) {
        int count = 0;
        if (cells.contains(new BlockPoint(point.x(), point.z() - 1))) count++;
        if (cells.contains(new BlockPoint(point.x() - 1, point.z()))) count++;
        if (cells.contains(new BlockPoint(point.x() + 1, point.z()))) count++;
        if (cells.contains(new BlockPoint(point.x(), point.z() + 1))) count++;
        return count;
    }

    private static boolean adjacent(BlockPoint first, BlockPoint second) {
        return Math.abs((long) first.x() - second.x()) + Math.abs((long) first.z() - second.z()) == 1;
    }

    private static List<LandUseAreaPlan.ScanlineSpan> rectangleSpans(int minX, int maxX, int minZ, int maxZ) {
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) spans.add(new LandUseAreaPlan.ScanlineSpan(z, minX, maxX));
        return List.copyOf(spans);
    }

    private static Set<BlockPoint> cells(List<LandUseAreaPlan.ScanlineSpan> spans) {
        Set<BlockPoint> cells = new HashSet<>();
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            for (int x = span.minX(); x <= span.maxX(); x++) cells.add(new BlockPoint(x, span.z()));
        }
        return cells;
    }
}
