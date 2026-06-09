package com.rinsing.geomantia.world.atlas.sample;

import com.rinsing.geomantia.world.atlas.cell.AtlasCell;
import com.rinsing.geomantia.world.atlas.cell.SampleSource;
import com.rinsing.geomantia.world.atlas.cell.SurfaceType;
import com.rinsing.geomantia.world.atlas.refresh.SampleMode;

import java.util.Objects;

public final class SyntheticAtlasSampler implements AtlasSampler {
    private final SyntheticTerrainProfile profile;

    public SyntheticAtlasSampler(SyntheticTerrainProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public SampledCell sample(AtlasCell cell, SampleMode sampleMode) {
        double x = cell.blockMinX();
        double z = cell.blockMinZ();
        double elevation = profile.elevationAt(x, z);
        double waterSurface = profile.seaLevel();
        boolean water = elevation < waterSurface && profile.hasWaterAt(x, z);
        double waterDepth = water ? waterSurface - elevation : 0.0;
        SurfaceType surfaceType = water ? SurfaceType.WATER : profile.surfaceTypeAt(x, z, elevation);
        return new SampledCell(SampleSource.PRIOR, elevation, surfaceType, profile.biomeAt(x, z, elevation, water),
                water, waterDepth);
    }
}
