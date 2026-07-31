package com.rinsing.geomantia.systems.gis.adapter.minecraft;

import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftPriorAtlasSamplerTest {
    private static final TestState AIR = state(false, false, false, SurfaceType.UNKNOWN);
    private static final TestState STONE = state(true, true, false, SurfaceType.ROCK);
    private static final TestState DEEPSLATE = state(true, true, false, SurfaceType.ROCK);
    private static final TestState DIRT = state(true, true, false, SurfaceType.DIRT);
    private static final TestState GRASS = state(true, true, false, SurfaceType.GRASS);
    private static final TestState SAND = state(true, true, false, SurfaceType.SAND);
    private static final TestState SNOW = state(true, true, false, SurfaceType.SNOW);
    private static final TestState WATER = state(true, false, true, SurfaceType.UNKNOWN);
    private static final MinecraftPriorAtlasSampler.ColumnStateAdapter<TestState> STATE_ADAPTER =
            new MinecraftPriorAtlasSampler.ColumnStateAdapter<>() {
                @Override
                public boolean worldSurface(TestState state) {
                    return state.worldSurface();
                }

                @Override
                public boolean oceanFloor(TestState state) {
                    return state.oceanFloor();
                }

                @Override
                public boolean water(TestState state) {
                    return state.water();
                }

                @Override
                public SurfaceType surfaceType(TestState state) {
                    return state.surfaceType();
                }
            };

    @Test
    void landColumnUsesFirstAirAboveSurfaceAndClassifiesSurfaceBlock() {
        IntFunction<TestState> column = column(-4, 12,
                layer(-4, -1, DEEPSLATE),
                layer(0, 6, STONE),
                layer(7, 7, DIRT),
                layer(8, 8, GRASS));

        var sample = sampleColumn(column, -4, 12, 5);

        assertEquals(9, sample.surfaceHeight());
        assertEquals(SurfaceType.GRASS, sample.surfaceType());
        assertFalse(sample.water());
        assertEquals(0.0, sample.waterDepth());
    }

    @Test
    void seaColumnKeepsWaterSurfaceHeightAndDerivesOceanFloorDepth() {
        IntFunction<TestState> column = column(0, 10,
                layer(0, 2, STONE),
                layer(3, 4, WATER));

        var sample = sampleColumn(column, 0, 10, 5);

        assertEquals(5, sample.surfaceHeight());
        assertEquals(SurfaceType.WATER, sample.surfaceType());
        assertTrue(sample.water());
        assertEquals(2.0, sample.waterDepth());
    }

    @Test
    void nonMotionBlockingWaterDoesNotRaiseOceanFloor() {
        IntFunction<TestState> column = column(0, 12,
                layer(0, 1, SAND),
                layer(2, 6, WATER));

        var sample = sampleColumn(column, 0, 12, 7);

        assertEquals(7, sample.surfaceHeight());
        assertTrue(sample.water());
        assertEquals(5.0, sample.waterDepth());
    }

    @Test
    void fluidAtSeaLevelKeepsExistingStrictWaterBoundary() {
        IntFunction<TestState> column = column(0, 10,
                layer(0, 2, STONE),
                layer(3, 5, WATER));

        var sample = sampleColumn(column, 0, 10, 5);

        assertEquals(6, sample.surfaceHeight());
        assertFalse(sample.water());
        assertEquals(SurfaceType.UNKNOWN, sample.surfaceType());
        assertEquals(0.0, sample.waterDepth());
    }

    @Test
    void allAirColumnFallsBackToMinimumBuildHeight() {
        IntFunction<TestState> column = column(-8, 8);

        var sample = sampleColumn(column, -8, 8, 3);

        assertEquals(-8, sample.surfaceHeight());
        assertEquals(SurfaceType.UNKNOWN, sample.surfaceType());
        assertFalse(sample.water());
        assertEquals(0.0, sample.waterDepth());
    }

    @Test
    void topmostBlockCanReturnExclusiveMaximumBuildHeight() {
        IntFunction<TestState> column = column(-2, 4, layer(3, 3, SNOW));

        var sample = sampleColumn(column, -2, 4, 0);

        assertEquals(4, sample.surfaceHeight());
        assertEquals(SurfaceType.SNOW, sample.surfaceType());
        assertFalse(sample.water());
    }

    @Test
    void rejectsEmptyBuildHeightRange() {
        IntFunction<TestState> column = column(0, 1);

        assertThrows(IllegalArgumentException.class,
                () -> sampleColumn(column, 0, 0, 0));
    }

    private static MinecraftPriorAtlasSampler.ColumnSample sampleColumn(IntFunction<TestState> column,
            int minBuildHeight, int maxBuildHeight, int seaLevel) {
        return MinecraftPriorAtlasSampler.sampleColumn(
                column, STATE_ADAPTER, minBuildHeight, maxBuildHeight, seaLevel);
    }

    private static IntFunction<TestState> column(int minY, int maxY, Layer... layers) {
        TestState[] states = new TestState[maxY - minY];
        Arrays.fill(states, AIR);
        for (Layer layer : layers) {
            for (int y = layer.minY(); y <= layer.maxY(); y++) {
                states[y - minY] = layer.state();
            }
        }
        return y -> y >= minY && y < maxY ? states[y - minY] : AIR;
    }

    private static Layer layer(int minY, int maxY, TestState state) {
        return new Layer(minY, maxY, state);
    }

    private static TestState state(boolean worldSurface, boolean oceanFloor,
            boolean water, SurfaceType surfaceType) {
        return new TestState(worldSurface, oceanFloor, water, surfaceType);
    }

    private record TestState(boolean worldSurface, boolean oceanFloor, boolean water, SurfaceType surfaceType) {
    }

    private record Layer(int minY, int maxY, TestState state) {
    }
}
