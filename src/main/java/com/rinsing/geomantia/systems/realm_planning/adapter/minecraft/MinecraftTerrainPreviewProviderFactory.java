package com.rinsing.geomantia.systems.realm_planning.adapter.minecraft;

import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.AtlasSamplerTerrainPreviewProvider;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProvider;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProviderSelector;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Objects;

public final class MinecraftTerrainPreviewProviderFactory {
    private MinecraftTerrainPreviewProviderFactory() {
    }

    public static TerrainPreviewProviderSelector createSelector(ServerLevel level, AtlasSampler fallbackSampler,
            String fallbackSourceFingerprint) {
        Objects.requireNonNull(level, "level");
        TerrainPreviewProvider rtf = RtfTerrainPreviewProvider.probe(level);
        TerrainPreviewProvider fallback = AtlasSamplerTerrainPreviewProvider.current(
                Objects.requireNonNull(fallbackSampler, "fallbackSampler"),
                fallbackSourceFingerprint
        );
        return assemble(rtf, fallback);
    }

    static TerrainPreviewProviderSelector assemble(TerrainPreviewProvider nativeProvider,
            TerrainPreviewProvider fallbackProvider) {
        return new TerrainPreviewProviderSelector(List.of(
                Objects.requireNonNull(nativeProvider, "nativeProvider"),
                Objects.requireNonNull(fallbackProvider, "fallbackProvider")
        ));
    }
}
