package com.rinsing.geomantia.systems.city.domain.model;

public record MetricsSummary(
        double meanElevation,
        double minElevation,
        double maxElevation,
        double meanSlope,
        double meanWaterDistance) {
}
