package com.rinsing.geomantia.systems.gis.domain.region;

import com.rinsing.geomantia.systems.gis.GisSampleConfig;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AtlasRegionStore {
    private final GisSampleConfig config;
    private final Map<RegionKey, AtlasRegion> regions = new LinkedHashMap<>();

    public AtlasRegionStore(GisSampleConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public AtlasRegion getOrCreate(String dimensionId, int regionX, int regionZ) {
        RegionKey key = new RegionKey(dimensionId, config.cellStepBlocks(), regionX, regionZ);
        return regions.computeIfAbsent(key, ignored -> new AtlasRegion(dimensionId, regionX, regionZ, config));
    }

    public AtlasRegion get(String dimensionId, int regionX, int regionZ) {
        return regions.get(new RegionKey(dimensionId, config.cellStepBlocks(), regionX, regionZ));
    }

    public AtlasRegion regionForBlock(String dimensionId, int blockX, int blockZ) {
        int regionX = Math.floorDiv(blockX, config.regionSizeBlocks());
        int regionZ = Math.floorDiv(blockZ, config.regionSizeBlocks());
        return getOrCreate(dimensionId, regionX, regionZ);
    }

    public Collection<AtlasRegion> regions() {
        return regions.values();
    }
}
