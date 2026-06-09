package com.rinsing.geomantia.world.atlas;

import com.rinsing.geomantia.world.atlas.cell.CellStateFlag;
import com.rinsing.geomantia.world.atlas.cell.LandformType;
import com.rinsing.geomantia.world.atlas.landform.LandformClassifier;
import com.rinsing.geomantia.world.atlas.landform.PatchMerger;
import com.rinsing.geomantia.world.atlas.metrics.AtlasMetricsComputer;
import com.rinsing.geomantia.world.atlas.region.AtlasRegion;
import com.rinsing.geomantia.world.atlas.sample.SyntheticAtlasSampler;
import com.rinsing.geomantia.world.atlas.sample.SyntheticTerrainProfile;
import com.rinsing.geomantia.world.atlas.refresh.SampleMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LandformAlgorithmTest {
    @Test
    void syntheticWaterProfileProducesWaterShoreAndPatchSummaries() {
        GisSampleConfig sampleConfig = GisSampleConfig.defaults();
        GisClassifierConfig classifierConfig = GisClassifierConfig.defaults();
        AtlasRegion region = new AtlasRegion("minecraft:overworld", 0, 0, sampleConfig);
        SyntheticAtlasSampler sampler = new SyntheticAtlasSampler(SyntheticTerrainProfile.water());

        for (var cell : region.cells()) {
            var sample = sampler.sample(cell, SampleMode.PRIOR);
            cell.setSample(sample.sampleSource(), sample.elevation(), sample.surfaceType(), sample.biomeId(),
                    sample.water(), sample.waterDepth());
        }
        new AtlasMetricsComputer(sampleConfig).compute(region);
        new LandformClassifier(classifierConfig).classify(region);
        var patches = new PatchMerger(classifierConfig).merge(region);

        assertTrue(region.cells().stream().allMatch(cell -> cell.hasFlag(CellStateFlag.SAMPLED)));
        assertTrue(region.cells().stream().anyMatch(cell -> cell.landformType() == LandformType.WATER));
        assertTrue(region.cells().stream().anyMatch(cell -> cell.landformType() == LandformType.SHORE));
        assertTrue(patches.stream().anyMatch(patch -> patch.touchesWater()));
        assertTrue(patches.stream().anyMatch(patch -> patch.cellCount() > 10));
    }

    @Test
    void syntheticMountainProfileProducesLargeTpiLandforms() {
        GisSampleConfig sampleConfig = GisSampleConfig.defaults();
        GisClassifierConfig classifierConfig = GisClassifierConfig.defaults();
        AtlasRegion region = new AtlasRegion("minecraft:overworld", 0, 0, sampleConfig);
        SyntheticAtlasSampler sampler = new SyntheticAtlasSampler(SyntheticTerrainProfile.mountain());

        for (var cell : region.cells()) {
            var sample = sampler.sample(cell, SampleMode.PRIOR);
            cell.setSample(sample.sampleSource(), sample.elevation(), sample.surfaceType(), sample.biomeId(),
                    sample.water(), sample.waterDepth());
        }
        new AtlasMetricsComputer(sampleConfig).compute(region);
        new LandformClassifier(classifierConfig).classify(region);

        assertTrue(region.cells().stream().anyMatch(cell -> cell.landformType() == LandformType.RIDGE));
        assertTrue(region.cells().stream().anyMatch(cell -> cell.landformType() == LandformType.VALLEY));
        assertTrue(region.cells().stream().anyMatch(cell -> cell.tpiLarge() > 8.0));
        assertTrue(region.cells().stream().anyMatch(cell -> cell.tpiLarge() < -8.0));
    }
}
