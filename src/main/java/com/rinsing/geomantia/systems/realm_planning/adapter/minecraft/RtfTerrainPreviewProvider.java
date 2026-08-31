package com.rinsing.geomantia.systems.realm_planning.adapter.minecraft;

import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProvider;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProviderAvailability;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProviderDescriptor;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewSample;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewSourceKind;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class RtfTerrainPreviewProvider implements TerrainPreviewProvider {
    private static final String GENERIC_PROVIDER_ID = "rtf_heightmap_preview";

    private final ServerLevel level;
    private final RtfTerrainPreviewReflectionBridge.Probe probe;
    private final TerrainPreviewProviderDescriptor descriptor;

    private RtfTerrainPreviewProvider(ServerLevel level, RtfTerrainPreviewReflectionBridge.Probe probe) {
        this.level = Objects.requireNonNull(level, "level");
        this.probe = Objects.requireNonNull(probe, "probe");
        RtfTerrainPreviewReflectionBridge.Binding binding = probe.binding();
        String providerId = binding == null ? GENERIC_PROVIDER_ID
                : GENERIC_PROVIDER_ID + "_" + binding.apiVariant();
        String fingerprintMaterial = binding == null ? probe.unavailableReason()
                : level.dimension().location() + "|" + level.getSeed() + "|"
                + binding.apiVariant() + "|" + binding.presetFingerprintMaterial();
        this.descriptor = new TerrainPreviewProviderDescriptor(
                providerId,
                TerrainPreviewSourceKind.GENERATOR_NATIVE,
                true,
                sha256(fingerprintMaterial),
                "estimated_coarse_heightmap"
        );
    }

    public static RtfTerrainPreviewProvider probe(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        RtfTerrainPreviewReflectionBridge.Probe result;
        try {
            Object randomState = level.getChunkSource().randomState();
            result = RtfTerrainPreviewReflectionBridge.probe(randomState, level.registryAccess());
        } catch (RuntimeException ex) {
            result = RtfTerrainPreviewReflectionBridge.Probe.unavailable(
                    "random_state_unavailable:" + ex.getClass().getSimpleName());
        }
        return new RtfTerrainPreviewProvider(level, result);
    }

    @Override
    public TerrainPreviewProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public TerrainPreviewProviderAvailability availability() {
        return probe.available()
                ? TerrainPreviewProviderAvailability.ready()
                : TerrainPreviewProviderAvailability.unavailable(probe.unavailableReason());
    }

    @Override
    public TerrainPreviewSample sample(int blockX, int blockZ) {
        if (!probe.available()) {
            throw new IllegalStateException("RTF terrain preview provider is unavailable: " + probe.unavailableReason());
        }
        RtfTerrainPreviewReflectionBridge.RawSample raw = probe.binding().sample(blockX, blockZ);
        return new TerrainPreviewSample(
                blockX,
                blockZ,
                raw.elevation(),
                raw.water(),
                minecraftBiomeId(blockX, blockZ, raw.elevation()),
                raw.terrainId(),
                raw.sourceBiomeId()
        );
    }

    private String minecraftBiomeId(int blockX, int blockZ, int height) {
        Holder<Biome> biome = level.getUncachedNoiseBiome(
                Math.floorDiv(blockX, 4),
                Math.floorDiv(Math.max(level.getMinBuildHeight(), height), 4),
                Math.floorDiv(blockZ, 4)
        );
        ResourceLocation biomeId = level.registryAccess().registryOrThrow(Registries.BIOME).getKey(biome.value());
        return biomeId == null ? "unknown" : biomeId.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}
