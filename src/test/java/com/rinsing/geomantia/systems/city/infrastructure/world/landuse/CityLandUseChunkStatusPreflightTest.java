package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseChunkStatusPreflightTest {
    private final CityLandUseChunkStatusPreflight preflight = new CityLandUseChunkStatusPreflight();

    @Test
    void enumeratesAllOwnersAndAcceptsOnlyProvenPreFeaturesOrAbsentChunks() {
        CityLandUseChunkStatusPreflight.PreflightResult result = preflight.inspect(
                CityLandUseChunkCompilerTest.plan("city_a"), (x, z) -> x == 0
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
        CityLandUseChunkStatusPreflight.PreflightResult result = preflight.inspect(
                CityLandUseChunkCompilerTest.plan("city_a"), (x, z) -> x == 1
                        ? CityLandUseChunkStatusPreflight.ChunkEvidence.featuresOrLater(x, z,
                        CityLandUseChunkStatusPreflight.EvidenceSource.DISK, "minecraft:full")
                        : CityLandUseChunkStatusPreflight.ChunkEvidence.notPresent(x, z));

        assertFalse(result.eligible());
        assertEquals("CITY_LAND_USE_CHUNK_ALREADY_AT_FEATURES", result.reasonCode());
        assertEquals(1, result.featuresOrLaterCount());
    }

    @Test
    void treatsMissingDiskEvidenceAsUnknownInsteadOfNotGenerated() {
        CityLandUseChunkStatusPreflight.PreflightResult result = preflight.inspect(
                CityLandUseChunkCompilerTest.plan("city_a"), (x, z) ->
                        CityLandUseChunkStatusPreflight.ChunkEvidence.unknown(x, z,
                                CityLandUseChunkStatusPreflight.EvidenceSource.DISK,
                                "CITY_LAND_USE_DISK_CHUNK_STATUS_READ_FAILED"));

        assertFalse(result.eligible());
        assertEquals("CITY_LAND_USE_CHUNK_STATUS_UNKNOWN", result.reasonCode());
        assertEquals(2, result.unknownCount());
    }
}
