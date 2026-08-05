package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.gis.application.sample.SampledCell;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.SampleSource;
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainPreviewProviderSelectorTest {
    @Test
    void selectsFirstAvailableNativeProviderWithoutFallback() {
        TerrainPreviewProvider nativeProvider = provider(
                descriptor("rtf_native", TerrainPreviewSourceKind.GENERATOR_NATIVE, true, "rtf:seed:settings"),
                TerrainPreviewProviderAvailability.ready(),
                new TerrainPreviewSample(10, 20, 96.5, false, "minecraft:windswept_hills")
        );
        TerrainPreviewProviderSelection selection = new TerrainPreviewProviderSelector(List.of(
                nativeProvider,
                provider(descriptor("fallback", TerrainPreviewSourceKind.GIS_ATLAS_SAMPLER, false, "fallback:v1"),
                        TerrainPreviewProviderAvailability.ready(), null)
        )).select();

        assertEquals("rtf_native", selection.providerId());
        assertEquals("generator_native", selection.sourceKind());
        assertTrue(selection.fastPath());
        assertEquals("", selection.fallbackReason());
        assertEquals("rtf:seed:settings", selection.sourceFingerprint());
        assertEquals("unspecified", selection.samplingSemantics());
        assertEquals(96.5, selection.sample(10, 20).elevation());
    }

    @Test
    void recordsWhyUnavailableNativeProviderFellBackToCurrentSampler() {
        TerrainPreviewProviderSelection selection = new TerrainPreviewProviderSelector(List.of(
                provider(descriptor("rtf_native", TerrainPreviewSourceKind.GENERATOR_NATIVE, true, "rtf:v1"),
                        TerrainPreviewProviderAvailability.unavailable("rtf_api_not_present"), null),
                provider(descriptor("current_atlas_sampler", TerrainPreviewSourceKind.GIS_ATLAS_SAMPLER,
                                false, "minecraft:seed:settings"),
                        TerrainPreviewProviderAvailability.ready(),
                        new TerrainPreviewSample(-17, 31, 64.0, true, "minecraft:ocean"))
        )).select();

        assertEquals("current_atlas_sampler", selection.providerId());
        assertEquals("gis_atlas_sampler", selection.sourceKind());
        assertFalse(selection.fastPath());
        assertEquals("rtf_native=rtf_api_not_present", selection.fallbackReason());
        assertEquals("minecraft:seed:settings", selection.sourceFingerprint());
    }

    @Test
    void explicitSwitchDisablesNativeProviderAndRecordsTheReason() {
        TerrainPreviewProviderSelection selection = new TerrainPreviewProviderSelector(List.of(
                provider(descriptor("rtf_native", TerrainPreviewSourceKind.GENERATOR_NATIVE, true, "rtf:v1"),
                        TerrainPreviewProviderAvailability.ready(), null),
                provider(descriptor("current_atlas_sampler", TerrainPreviewSourceKind.GIS_ATLAS_SAMPLER,
                                false, "minecraft:v1"),
                        TerrainPreviewProviderAvailability.ready(), null)
        )).select(false);

        assertEquals("current_atlas_sampler", selection.providerId());
        assertFalse(selection.fastPath());
        assertEquals("generator_native_disabled", selection.fallbackReason());
    }

    @Test
    void failsWithProviderDiagnosticsWhenNoProviderIsAvailable() {
        TerrainPreviewProviderSelector selector = new TerrainPreviewProviderSelector(List.of(
                provider(descriptor("rtf_native", TerrainPreviewSourceKind.GENERATOR_NATIVE, true, "rtf:v1"),
                        TerrainPreviewProviderAvailability.unavailable("missing_runtime_api"), null),
                provider(descriptor("current", TerrainPreviewSourceKind.GIS_ATLAS_SAMPLER, false, "world:v1"),
                        TerrainPreviewProviderAvailability.unavailable("server_not_ready"), null)
        ));

        IllegalStateException error = assertThrows(IllegalStateException.class, selector::select);
        assertTrue(error.getMessage().contains("rtf_native=missing_runtime_api"));
        assertTrue(error.getMessage().contains("current=server_not_ready"));
    }

    @Test
    void currentAtlasSamplerAdapterPreservesStableFeatureFieldsAndCoordinates() {
        AtlasSampler sampler = new AtlasSampler() {
            @Override
            public SampledCell sample(AtlasCell cell, SampleMode sampleMode) {
                throw new AssertionError("Preview adapter must use the feature sampling path.");
            }

            @Override
            public SampledCell sampleFeature(AtlasCell cell, SampleMode sampleMode) {
                assertEquals(-33, cell.blockMinX());
                assertEquals(127, cell.blockMinZ());
                assertEquals(SampleMode.PRIOR, sampleMode);
                return new SampledCell(SampleSource.PRIOR, 71.25, SurfaceType.WATER,
                        "minecraft:river", true, 3.0);
            }
        };
        TerrainPreviewProvider provider = AtlasSamplerTerrainPreviewProvider.current(sampler, "seed:42:noise:v3");

        TerrainPreviewSample sample = provider.sample(-33, 127);

        assertEquals(-33, sample.blockX());
        assertEquals(127, sample.blockZ());
        assertEquals(71.25, sample.elevation());
        assertTrue(sample.water());
        assertEquals("minecraft:river", sample.biomeId());
        assertEquals("seed:42:noise:v3", provider.descriptor().sourceFingerprint());
    }

    @Test
    void rejectsDuplicateProviderIdsBecauseFallbackDiagnosticsWouldBeAmbiguous() {
        TerrainPreviewProvider first = provider(
                descriptor("same", TerrainPreviewSourceKind.GENERATOR_NATIVE, true, "native:v1"),
                TerrainPreviewProviderAvailability.ready(), null);
        TerrainPreviewProvider second = provider(
                descriptor("same", TerrainPreviewSourceKind.GIS_ATLAS_SAMPLER, false, "fallback:v1"),
                TerrainPreviewProviderAvailability.ready(), null);

        assertThrows(IllegalArgumentException.class,
                () -> new TerrainPreviewProviderSelector(List.of(first, second)));
    }

    private static TerrainPreviewProviderDescriptor descriptor(String providerId,
            TerrainPreviewSourceKind sourceKind, boolean fastPath, String fingerprint) {
        return new TerrainPreviewProviderDescriptor(providerId, sourceKind, fastPath, fingerprint);
    }

    private static TerrainPreviewProvider provider(TerrainPreviewProviderDescriptor descriptor,
            TerrainPreviewProviderAvailability availability, TerrainPreviewSample sample) {
        return new TerrainPreviewProvider() {
            @Override
            public TerrainPreviewProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public TerrainPreviewProviderAvailability availability() {
                return availability;
            }

            @Override
            public TerrainPreviewSample sample(int blockX, int blockZ) {
                return sample;
            }
        };
    }
}
