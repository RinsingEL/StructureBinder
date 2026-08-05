package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.gis.application.sample.SampledCell;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.SampleSource;
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;

import java.util.Objects;

/** Adapts a selected generation-prior provider to the existing W survey sampler contract. */
public final class TerrainPreviewAtlasSampler implements AtlasSampler {
    private final TerrainPreviewProviderSelection selection;

    public TerrainPreviewAtlasSampler(TerrainPreviewProviderSelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    @Override
    public SampledCell sample(AtlasCell cell, SampleMode sampleMode) {
        return sampledCell(cell);
    }

    @Override
    public SampledCell sampleFeature(AtlasCell cell, SampleMode sampleMode) {
        return sampledCell(cell);
    }

    @Override
    public double sampleElevation(AtlasCell cell, SampleMode sampleMode) {
        return sample(cell).elevation();
    }

    private SampledCell sampledCell(AtlasCell cell) {
        TerrainPreviewSample sample = sample(cell);
        return new SampledCell(SampleSource.PRIOR, sample.elevation(),
                sample.water() ? SurfaceType.WATER : SurfaceType.UNKNOWN,
                sample.biomeId(), sample.water(), 0.0);
    }

    private TerrainPreviewSample sample(AtlasCell cell) {
        return Objects.requireNonNull(selection.sample(cell.blockMinX(), cell.blockMinZ()),
                "Terrain preview provider returned null.");
    }
}
