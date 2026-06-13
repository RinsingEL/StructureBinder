package com.rinsing.geomantia.systems.realm_planning;

import java.util.LinkedHashMap;
import java.util.Map;

public record WorldFeatureCell(
        int gridX,
        int gridZ,
        double heightP10,
        double heightP50,
        double heightP90,
        double robustRelief,
        double slopeMean,
        double slopeP90,
        double steepFrac,
        double waterFrac,
        int microSampleCount,
        Map<String, Integer> biomeHistogram
) {
    public WorldFeatureCell {
        biomeHistogram = Map.copyOf(biomeHistogram == null ? Map.of() : new LinkedHashMap<>(biomeHistogram));
    }
}
