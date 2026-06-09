package com.rinsing.geomantia.world.atlas.sample;

import com.rinsing.geomantia.world.atlas.cell.SampleSource;
import com.rinsing.geomantia.world.atlas.cell.SurfaceType;

public record SampledCell(
        SampleSource sampleSource,
        double elevation,
        SurfaceType surfaceType,
        String biomeId,
        boolean water,
        double waterDepth
) {
}
