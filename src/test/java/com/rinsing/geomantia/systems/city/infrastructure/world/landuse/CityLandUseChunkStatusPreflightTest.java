package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CityLandUseChunkStatusPreflightTest {
    private final CityLandUseChunkStatusPreflight preflight = new CityLandUseChunkStatusPreflight();

    @Test void preparedOwnersRecheckLiveStatusAndRejectDifferentPlanIdentity() {
        var area = CityLandUseChunkCompilerTest.plan("city_a");
        var surface = CityLandUseChunkCompilerTest.uniformPlan(area);
        var prepared = preflight.prepare(area, surface);
        assertTrue(preflight.inspect(area, surface, prepared,
                CityLandUseChunkStatusPreflight.ChunkEvidence::notPresent).eligible());
        assertFalse(preflight.inspect(area, surface, prepared, (x, z) ->
                CityLandUseChunkStatusPreflight.ChunkEvidence.featuresOrLater(x, z,
                        CityLandUseChunkStatusPreflight.EvidenceSource.LOADED, "minecraft:full")).eligible());
        var other = CityLandUseChunkCompilerTest.plan("city_b");
        assertThrows(IllegalArgumentException.class, () -> preflight.inspect(other,
                CityLandUseChunkCompilerTest.uniformPlan(other), prepared,
                (x, z) -> { throw new AssertionError("stale input must fail before probing"); }));
        var changed = CityLandUseChunkCompilerTest.areaPlan("city_a", SurfacePolicy.PAVE,
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 48)));
        assertThrows(IllegalArgumentException.class, () -> preflight.inspect(changed,
                CityLandUseChunkCompilerTest.uniformPlan(changed), prepared,
                (x, z) -> { throw new AssertionError("changed plan must fail before probing"); }));
    }

    @Test void checksFeatureOnlyOwnerOutsideAreaBounds() {
        var area = CityLandUseChunkCompilerTest.plan("city_a");
        var base = CityLandUseChunkCompilerTest.uniformPlan(area);
        var surface = new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(
                base.schema(), base.cityId(), base.sourceLandUsePlanHash(), "", base.areas(),
                base.sharedBoundarySpans(), List.of(new CityLandUseSurfacePrintPlan.FeatureCell(
                "road", 96, 96, "minecraft:stone_brick_slab", 0,
                CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NONE))));
        var prepared = preflight.prepare(area, surface);
        assertTrue(prepared.owners().contains(new CityLandUseChunkStatusPreflight.OwnerChunk(6, 6)));
        var result = preflight.inspect(area, surface, prepared, (x, z) -> x == 6 && z == 6
                ? CityLandUseChunkStatusPreflight.ChunkEvidence.featuresOrLater(x, z,
                CityLandUseChunkStatusPreflight.EvidenceSource.LOADED, "minecraft:full")
                : CityLandUseChunkStatusPreflight.ChunkEvidence.notPresent(x, z));
        assertFalse(result.eligible());
        assertEquals(3, result.ownerChunkCount());
    }

    @Test void cancelledPreparationStopsBeforeWorkAndKeepsInterruptFlag() {
        var area = CityLandUseChunkCompilerTest.plan("city_a");
        var surface = CityLandUseChunkCompilerTest.uniformPlan(area);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class, () -> preflight.prepare(area, surface));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

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
