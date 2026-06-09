package com.rinsing.geomantia.world.atlas.sample;

import com.rinsing.geomantia.world.atlas.cell.AtlasCell;
import com.rinsing.geomantia.world.atlas.refresh.SampleMode;

public interface AtlasSampler {
    SampledCell sample(AtlasCell cell, SampleMode sampleMode);
}
