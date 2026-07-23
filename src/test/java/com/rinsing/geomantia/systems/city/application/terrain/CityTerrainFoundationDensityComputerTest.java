package com.rinsing.geomantia.systems.city.application.terrain;

import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationTerrainRunCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationFoundationDensityComputer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityTerrainFoundationDensityComputerTest {
    @Test
    void decorationAdapterIsPointwiseEquivalentToCommonSegments() {
        List<CityContinuousTerrainRunPlanner.FoundationSegment> common = List.of(
                segment("a", 0, 0, 70, 6, 0, 72, 1, 5, 2),
                segment("b", 3, -3, 68, 3, 3, 70, 0, 3, 1));
        List<CityDecorationTerrainRunCompiler.FoundationSegment> decoration = common.stream()
                .map(value -> new CityDecorationTerrainRunCompiler.FoundationSegment(
                        value.runId(), value.x0(), value.z0(), value.y0(), value.x1(), value.z1(), value.y1(),
                        value.halfWidth(), value.maxDepthBlocks(), value.shoulderBlocks()))
                .toList();

        for (int y = 63; y <= 73; y++) {
            for (int z = -5; z <= 5; z++) {
                for (int x = -2; x <= 8; x++) {
                    assertEquals(CityTerrainFoundationDensityComputer.compute(x, y, z, common),
                            CityDecorationFoundationDensityComputer.compute(x, y, z, decoration));
                }
            }
        }
    }

    @Test
    void multipleSegmentsUseMaximumContributionInsteadOfAddingDensity() {
        CityContinuousTerrainRunPlanner.FoundationSegment horizontal =
                segment("horizontal", 0, 0, 70, 4, 0, 70, 1, 4, 2);
        CityContinuousTerrainRunPlanner.FoundationSegment vertical =
                segment("vertical", 2, -2, 72, 2, 2, 72, 1, 6, 1);

        double first = CityTerrainFoundationDensityComputer.compute(2, 69, 0, List.of(horizontal));
        double second = CityTerrainFoundationDensityComputer.compute(2, 69, 0, List.of(vertical));
        double combined = CityTerrainFoundationDensityComputer.compute(2, 69, 0,
                List.of(horizontal, vertical));

        assertEquals(Math.max(first, second), combined);
        assertTrue(combined <= 0.5D);
    }

    @Test
    void mergedDecorationAndLandUseViewsContributeThroughOneMaximum() {
        CityContinuousTerrainRunPlanner.FoundationSegment landUse =
                segment("land-use", 0, 0, 70, 4, 0, 70, 1, 4, 2);
        CityDecorationTerrainRunCompiler.FoundationSegment decoration =
                new CityDecorationTerrainRunCompiler.FoundationSegment(
                        "decoration", 2, -2, 72, 2, 2, 72, 1, 6, 1);
        List<CityTerrainFoundationDensityComputer.FoundationSegmentView> merged =
                List.of(decoration, landUse);

        double decorationOnly = CityTerrainFoundationDensityComputer.computeViews(
                2, 69, 0, List.of(decoration));
        double landUseOnly = CityTerrainFoundationDensityComputer.computeViews(
                2, 69, 0, List.of(landUse));

        assertEquals(Math.max(decorationOnly, landUseOnly),
                CityTerrainFoundationDensityComputer.computeViews(2, 69, 0, merged));
    }

    @Test
    void shoulderDepthAndEndpointsRemainStrictlyBounded() {
        CityContinuousTerrainRunPlanner.FoundationSegment segment =
                segment("bounded", 0, 0, 70, 4, 0, 70, 0, 4, 2);

        double center = CityTerrainFoundationDensityComputer.compute(2, 69, 0, List.of(segment));
        double shoulder = CityTerrainFoundationDensityComputer.compute(2, 69, 1, List.of(segment));
        assertTrue(center > shoulder);
        assertTrue(shoulder > 0.0D);
        assertTrue(CityTerrainFoundationDensityComputer.compute(2, 66, 0, List.of(segment)) > 0.0D);
        assertEquals(0.0D, CityTerrainFoundationDensityComputer.compute(2, 70, 0, List.of(segment)));
        assertEquals(0.0D, CityTerrainFoundationDensityComputer.compute(2, 65, 0, List.of(segment)));
        assertEquals(0.0D, CityTerrainFoundationDensityComputer.compute(2, 69, 3, List.of(segment)));
        assertEquals(0.0D, CityTerrainFoundationDensityComputer.compute(-1, 69, 0, List.of(segment)));
        assertEquals(0.0D, CityTerrainFoundationDensityComputer.compute(5, 69, 0, List.of(segment)));
    }

    private static CityContinuousTerrainRunPlanner.FoundationSegment segment(
            String runId, int x0, int z0, int y0, int x1, int z1, int y1,
            int halfWidth, int maxDepthBlocks, int shoulderBlocks) {
        return new CityContinuousTerrainRunPlanner.FoundationSegment(runId, x0, z0, y0, x1, z1, y1,
                halfWidth, maxDepthBlocks, shoulderBlocks);
    }
}
