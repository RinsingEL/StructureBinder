package com.rinsing.geomantia.world.atlas.region;

public record RegionKey(String dimensionId, int regionX, int regionZ) {
    public String regionId() {
        return regionId(dimensionId, regionX, regionZ);
    }

    public static String regionId(String dimensionId, int regionX, int regionZ) {
        return dimensionId + ":r." + regionX + "." + regionZ;
    }
}
