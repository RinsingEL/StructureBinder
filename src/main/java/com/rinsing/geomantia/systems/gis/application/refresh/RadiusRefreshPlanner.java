package com.rinsing.geomantia.systems.gis.application.refresh;

import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RadiusRefreshPlanner {
    private final GisSampleConfig config;

    public RadiusRefreshPlanner(GisSampleConfig config) {
        this.config = config;
    }

    public List<PlannedCell> plan(AtlasRegion region, RefreshJob job) {
        int centerCellX = Math.floorDiv(job.centerBlockX(), config.cellStepBlocks());
        int centerCellZ = Math.floorDiv(job.centerBlockZ(), config.cellStepBlocks());
        int stableRadiusCells = Math.max(0, job.radiusChunks() * 16 / config.cellStepBlocks());
        int sampleRadiusCells = stableRadiusCells + job.dependencyMarginCells();
        List<PlannedCell> cells = new ArrayList<>();
        for (AtlasCell cell : region.cells()) {
            int dx = cell.globalCellX() - centerCellX;
            int dz = cell.globalCellZ() - centerCellZ;
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance <= sampleRadiusCells) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz)) * config.cellStepBlocks() / 16;
                boolean stable = distance <= stableRadiusCells;
                cells.add(new PlannedCell(cell, ring, stable));
            }
        }
        cells.sort(Comparator.comparingInt(PlannedCell::ring)
                .thenComparingInt(planned -> planned.cell().localCellZ())
                .thenComparingInt(planned -> planned.cell().localCellX()));
        return cells;
    }

    public record PlannedCell(AtlasCell cell, int ring, boolean stable) {
    }
}
