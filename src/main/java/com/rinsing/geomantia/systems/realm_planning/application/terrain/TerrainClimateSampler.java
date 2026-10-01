package com.rinsing.geomantia.systems.realm_planning.application.terrain;

/** Optional native climate; missing values must not be inferred from biome labels. */
public interface TerrainClimateSampler {
    default com.google.gson.JsonObject climateSourceDetails() { return new com.google.gson.JsonObject(); }
    boolean climateAvailable();
    TerrainClimateSample sampleClimate(int blockX, int blockZ);
}
