package com.rinsing.geomantia.systems.realm_planning.application.terrain;

/** Region fields drive RTF's preview; final fields are retained separately. */
public record TerrainClimateSample(double regionTemperature, double regionMoisture,
        double temperature, double moisture, boolean water) {
    public TerrainClimateSample {
        if (!Double.isFinite(regionTemperature) || !Double.isFinite(regionMoisture)
                || !Double.isFinite(temperature) || !Double.isFinite(moisture)) {
            throw new IllegalArgumentException("CLIMATE_SAMPLE_NOT_FINITE");
        }
    }
}
