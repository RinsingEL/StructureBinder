package com.rinsing.geomantia.systems.gis.domain.region;

public record RegionKey(String dimensionId, int cellStepBlocks, int regionX, int regionZ) {
    public String regionId() {
        return regionId(dimensionId, cellStepBlocks, regionX, regionZ);
    }

    public static String regionId(String dimensionId, int cellStepBlocks, int regionX, int regionZ) {
        return dimensionId + ":step." + cellStepBlocks + ":r." + regionX + "." + regionZ;
    }
}
