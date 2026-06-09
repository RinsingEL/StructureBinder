package com.rinsing.geomantia.world.atlas;

import com.rinsing.geomantia.world.atlas.refresh.SampleMode;

public record GisSampleConfig(
        int cellStepBlocks,
        int regionSizeChunks,
        int stableRadiusChunks,
        int dependencyMarginCells,
        int maxWaterDistanceCells,
        int budgetCellsPerBatch,
        SampleMode defaultSampleMode,
        int slopeRadiusCells,
        int localReliefRadiusCells,
        int roughnessRadiusCells,
        int tpiSmallRadiusCells,
        int tpiLargeRadiusCells
) {
    public static GisSampleConfig defaults() {
        return new GisSampleConfig(
                4,
                32,
                16,
                12,
                64,
                4096,
                SampleMode.PRIOR,
                1,
                2,
                2,
                3,
                12
        );
    }

    public GisSampleConfig {
        if (cellStepBlocks <= 0 || regionSizeChunks <= 0 || stableRadiusChunks < 0) {
            throw new IllegalArgumentException("GIS sampling dimensions must be positive.");
        }
        if (dependencyMarginCells < 0 || maxWaterDistanceCells < 0 || budgetCellsPerBatch <= 0) {
            throw new IllegalArgumentException("GIS margins and budgets must be non-negative.");
        }
        if (slopeRadiusCells <= 0 || localReliefRadiusCells <= 0 || roughnessRadiusCells <= 0
                || tpiSmallRadiusCells <= 0 || tpiLargeRadiusCells <= 0) {
            throw new IllegalArgumentException("GIS metric radii must be positive.");
        }
        if (dependencyMarginCells < tpiLargeRadiusCells) {
            throw new IllegalArgumentException("dependencyMarginCells must cover the large TPI radius.");
        }
    }

    public int regionSizeBlocks() {
        return regionSizeChunks * 16;
    }

    public int cellsPerRegionSide() {
        return regionSizeBlocks() / cellStepBlocks;
    }
}
