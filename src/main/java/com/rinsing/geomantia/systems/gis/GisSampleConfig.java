package com.rinsing.geomantia.systems.gis;

import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;

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
    public static final int MIN_CELL_STEP_BLOCKS = 1;
    public static final int MAX_CELL_STEP_BLOCKS = 256;

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

    public GisSampleConfig withCellStepBlocks(int cellStepBlocks) {
        return new GisSampleConfig(
                cellStepBlocks,
                regionSizeChunks,
                stableRadiusChunks,
                dependencyMarginCells,
                maxWaterDistanceCells,
                budgetCellsPerBatch,
                defaultSampleMode,
                slopeRadiusCells,
                localReliefRadiusCells,
                roughnessRadiusCells,
                tpiSmallRadiusCells,
                tpiLargeRadiusCells
        );
    }

    public GisSampleConfig {
        if (cellStepBlocks <= 0 || regionSizeChunks <= 0 || stableRadiusChunks < 0) {
            throw new IllegalArgumentException("GIS sampling dimensions must be positive.");
        }
        if (cellStepBlocks < MIN_CELL_STEP_BLOCKS || cellStepBlocks > MAX_CELL_STEP_BLOCKS) {
            throw new IllegalArgumentException("cellStepBlocks must be between 1 and 256.");
        }
        int regionSizeBlocks = regionSizeChunks * 16;
        if (regionSizeBlocks % cellStepBlocks != 0) {
            throw new IllegalArgumentException("cellStepBlocks must divide the GIS region size in blocks.");
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
