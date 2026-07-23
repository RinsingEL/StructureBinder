package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseChunkStatusPreflightTest {
    private final CityLandUseChunkStatusPreflight preflight = new CityLandUseChunkStatusPreflight();

    @Test
    void enumeratesAllOwnersAndAcceptsOnlyProvenPreFeaturesOrAbsentChunks() {
        var areaPlan = CityLandUseChunkCompilerTest.plan("city_a");
        CityLandUseChunkStatusPreflight.PreflightResult result = preflight.inspect(
                areaPlan, CityLandUseChunkCompilerTest.uniformPlan(areaPlan), (x, z) -> x == 0
                        ? CityLandUseChunkStatusPreflight.ChunkEvidence.beforeFeatures(x, z,
                        CityLandUseChunkStatusPreflight.EvidenceSource.LOADED, "minecraft:carvers")
                        : CityLandUseChunkStatusPreflight.ChunkEvidence.notPresent(x, z));

        assertTrue(result.eligible());
        assertEquals(2, result.ownerChunkCount());
        assertEquals(0, result.featuresOrLaterCount());
        assertEquals(0, result.unknownCount());
        assertEquals(0, result.chunks().get(0).chunkX());
        assertEquals(1, result.chunks().get(1).chunkX());
    }

    @Test
    void rejectsWholeActivationWhenAnyOwnerHasReachedFeatures() {
        var areaPlan = CityLandUseChunkCompilerTest.plan("city_a");
        CityLandUseChunkStatusPreflight.PreflightResult result = preflight.inspect(
                areaPlan, CityLandUseChunkCompilerTest.uniformPlan(areaPlan), (x, z) -> x == 1
                        ? CityLandUseChunkStatusPreflight.ChunkEvidence.featuresOrLater(x, z,
                        CityLandUseChunkStatusPreflight.EvidenceSource.DISK, "minecraft:full")
                        : CityLandUseChunkStatusPreflight.ChunkEvidence.notPresent(x, z));

        assertFalse(result.eligible());
        assertEquals("CITY_LAND_USE_CHUNK_ALREADY_AT_FEATURES", result.reasonCode());
        assertEquals(1, result.featuresOrLaterCount());
    }

    @Test
    void treatsMissingDiskEvidenceAsUnknownInsteadOfNotGenerated() {
        var areaPlan = CityLandUseChunkCompilerTest.plan("city_a");
        CityLandUseChunkStatusPreflight.PreflightResult result = preflight.inspect(
                areaPlan, CityLandUseChunkCompilerTest.uniformPlan(areaPlan), (x, z) ->
                        CityLandUseChunkStatusPreflight.ChunkEvidence.unknown(x, z,
                                CityLandUseChunkStatusPreflight.EvidenceSource.DISK,
                                "CITY_LAND_USE_DISK_CHUNK_STATUS_READ_FAILED"));

        assertFalse(result.eligible());
        assertEquals("CITY_LAND_USE_CHUNK_STATUS_UNKNOWN", result.reasonCode());
        assertEquals(2, result.unknownCount());
    }

    @Test
    void ignoresPreserveOpenAreasWithoutCompiledWrites() {
        var areaPlan = CityLandUseChunkCompilerTest.areaPlan("city_noop",
                SurfacePolicy.PRESERVE,
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 31)));
        var surfacePlan = CityLandUseChunkCompilerTest.hashed(areaPlan, List.of());
        int[] probeCalls = {0};

        CityLandUseChunkStatusPreflight.PreflightResult result = preflight.inspect(
                areaPlan, surfacePlan, (x, z) -> {
                    probeCalls[0]++;
                    return CityLandUseChunkStatusPreflight.ChunkEvidence.featuresOrLater(x, z,
                            CityLandUseChunkStatusPreflight.EvidenceSource.DISK, "minecraft:full");
                });

        assertTrue(result.eligible());
        assertEquals(0, result.ownerChunkCount());
        assertEquals(0, probeCalls[0]);
    }
}
