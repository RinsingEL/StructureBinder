package com.rinsing.geomantia.systems.gis.application.sample;

import com.rinsing.geomantia.systems.gis.domain.cell.SampleSource;
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;

public record SampledCell(
        SampleSource sampleSource,
        double elevation,
        SurfaceType surfaceType,
        String biomeId,
        boolean water,
        double waterDepth
) {
}
