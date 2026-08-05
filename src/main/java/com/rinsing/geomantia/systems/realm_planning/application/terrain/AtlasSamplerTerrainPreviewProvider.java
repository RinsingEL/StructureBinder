package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.gis.application.sample.SampledCell;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;

import java.util.Objects;

public final class AtlasSamplerTerrainPreviewProvider implements TerrainPreviewProvider {
    private static final String PREVIEW_REGION_ID = "terrain_preview";

    private final AtlasSampler sampler;
    private final SampleMode sampleMode;
    private final TerrainPreviewProviderDescriptor descriptor;

    public AtlasSamplerTerrainPreviewProvider(AtlasSampler sampler, SampleMode sampleMode,
            TerrainPreviewProviderDescriptor descriptor) {
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.sampleMode = Objects.requireNonNull(sampleMode, "sampleMode");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    public static AtlasSamplerTerrainPreviewProvider current(AtlasSampler sampler, String sourceFingerprint) {
        return new AtlasSamplerTerrainPreviewProvider(
                sampler,
                SampleMode.PRIOR,
                new TerrainPreviewProviderDescriptor(
                        "current_atlas_sampler",
                        TerrainPreviewSourceKind.GIS_ATLAS_SAMPLER,
                        false,
                        sourceFingerprint,
                        "base_height_feature_sample"
                )
        );
    }

    @Override
    public TerrainPreviewProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public TerrainPreviewProviderAvailability availability() {
        return TerrainPreviewProviderAvailability.ready();
    }

    @Override
    public TerrainPreviewSample sample(int blockX, int blockZ) {
        AtlasCell probe = new AtlasCell(PREVIEW_REGION_ID, blockX, blockZ, 0, 0, blockX, blockZ);
        SampledCell sampled = Objects.requireNonNull(sampler.sampleFeature(probe, sampleMode),
                "AtlasSampler returned a null feature sample.");
        return new TerrainPreviewSample(
                blockX,
                blockZ,
                sampled.elevation(),
                sampled.water(),
                sampled.biomeId()
        );
    }
}
