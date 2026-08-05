package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.application.sample.SampledCell;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.SampleSource;
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TerrainPreviewAtlasSamplerTest {
    @Test
    void mapsGeneratorPriorSampleIntoWorldSurveyContract() {
        TerrainPreviewProvider provider = new TerrainPreviewProvider() {
            @Override
            public TerrainPreviewProviderDescriptor descriptor() {
                return new TerrainPreviewProviderDescriptor("rtf_native",
                        TerrainPreviewSourceKind.GENERATOR_NATIVE, true, "rtf:seed", "estimated_heightmap");
            }

            @Override
            public TerrainPreviewProviderAvailability availability() {
                return TerrainPreviewProviderAvailability.ready();
            }

            @Override
            public TerrainPreviewSample sample(int blockX, int blockZ) {
                return new TerrainPreviewSample(blockX, blockZ, 87.0, false,
                        "minecraft:snowy_plains", "rtf:flats", "rtf:tundra");
            }
        };
        TerrainPreviewProviderSelection selection = new TerrainPreviewProviderSelector(
                java.util.List.of(provider)).select();
        TerrainPreviewAtlasSampler sampler = new TerrainPreviewAtlasSampler(selection);
        AtlasCell cell = new AtlasCell("w", -2, 7, 0, 0, -192, 960);

        SampledCell sample = sampler.sample(cell, SampleMode.PRIOR);

        assertEquals(SampleSource.PRIOR, sample.sampleSource());
        assertEquals(87.0, sample.elevation());
        assertEquals(SurfaceType.UNKNOWN, sample.surfaceType());
        assertEquals("minecraft:snowy_plains", sample.biomeId());
        assertFalse(sample.water());
        assertEquals(0.0, sample.waterDepth());
        assertEquals(87.0, sampler.sampleElevation(cell, SampleMode.PRIOR));
    }
}
