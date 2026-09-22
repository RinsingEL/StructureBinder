package com.rinsing.geomantia.api.regions;

import java.util.List;

/** Read-only W facts supplied before the first T1. Inspect reserveAllowed before choosing a mask. */
public record RegionPlanningContext(String runId, String dimensionId, int cellStepBlocks, List<Cell> cells) {
    public RegionPlanningContext { cells = List.copyOf(cells); }

    public record Cell(int gridX, int gridZ, RegionBounds bounds, String continentId,
                       String landform, double height, double waterFraction, boolean reserveAllowed) {}
}
