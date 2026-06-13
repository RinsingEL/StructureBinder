package com.rinsing.geomantia.systems.gis.application.sample;

import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;

public interface AtlasSampler {
    SampledCell sample(AtlasCell cell, SampleMode sampleMode);

    default SampledCell sampleFeature(AtlasCell cell, SampleMode sampleMode) {
        return sample(cell, sampleMode);
    }

    default double sampleElevation(AtlasCell cell, SampleMode sampleMode) {
        return sampleFeature(cell, sampleMode).elevation();
    }
}
