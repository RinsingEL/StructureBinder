package com.rinsing.geomantia.systems.realm_planning.application.terrain;

public interface TerrainPreviewProvider {
    TerrainPreviewProviderDescriptor descriptor();

    TerrainPreviewProviderAvailability availability();

    TerrainPreviewSample sample(int blockX, int blockZ);
}
