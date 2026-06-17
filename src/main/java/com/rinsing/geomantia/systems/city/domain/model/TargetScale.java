package com.rinsing.geomantia.systems.city.domain.model;

public record TargetScale(CityScale scale, int radiusBlocks, int cellStepBlocks) {
    public TargetScale {
        if (scale == null) throw new IllegalArgumentException("scale is required");
        if (radiusBlocks <= 0) throw new IllegalArgumentException("radiusBlocks must be positive");
        if (cellStepBlocks <= 0) throw new IllegalArgumentException("cellStepBlocks must be positive");
    }
}
