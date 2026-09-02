package com.rinsing.geomantia.systems.city.application.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityTerrainFoundationDensityComputerTest {
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

    @Test
    void fullFootprintPlatformSupportsEveryInteriorColumnWithBoundedShoulderAndDepth() {
        CityTerrainFoundationDensityComputer.FoundationPlatformView platform =
                new TestPlatform(10, 20, 19, 29, 70, 32, 2);

        assertTrue(CityTerrainFoundationDensityComputer.computePlatformViews(
                10, 69, 20, List.of(platform)) > 0.0D);
        assertTrue(CityTerrainFoundationDensityComputer.computePlatformViews(
                19, 69, 29, List.of(platform)) > 0.0D);
        double shoulder = CityTerrainFoundationDensityComputer.computePlatformViews(
                20, 69, 25, List.of(platform));
        double interior = CityTerrainFoundationDensityComputer.computePlatformViews(
                19, 69, 25, List.of(platform));
        assertTrue(interior > shoulder);
        assertEquals(0.0D, CityTerrainFoundationDensityComputer.computePlatformViews(
                22, 69, 25, List.of(platform)));
        assertEquals(0.0D, CityTerrainFoundationDensityComputer.computePlatformViews(
                15, 70, 25, List.of(platform)));
        assertEquals(0.0D, CityTerrainFoundationDensityComputer.computePlatformViews(
                15, 37, 25, List.of(platform)));
    }

    private static CityContinuousTerrainRunPlanner.FoundationSegment segment(
            String runId, int x0, int z0, int y0, int x1, int z1, int y1,
            int halfWidth, int maxDepthBlocks, int shoulderBlocks) {
        return new CityContinuousTerrainRunPlanner.FoundationSegment(runId, x0, z0, y0, x1, z1, y1,
                halfWidth, maxDepthBlocks, shoulderBlocks);
    }

    private record TestPlatform(int minX, int minZ, int maxX, int maxZ, int targetY,
                                int maxDepthBlocks, int shoulderBlocks)
            implements CityTerrainFoundationDensityComputer.FoundationPlatformView {
    }
}
