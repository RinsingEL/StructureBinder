package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainScalePatchServiceTest {
    @Test
    void mergesOneTScalePatchAcrossGisRegionAndWPatchBoundaries() {
        TerrainScalePatchService.Result result = new TerrainScalePatchService().analyze(
                "minecraft:overworld", "realm_a",
                List.of(
                        new TerrainScalePatchService.SeedCell(3, 0, 384, 0, 128, "w_plain_1"),
                        new TerrainScalePatchService.SeedCell(4, 0, 512, 0, 128, "w_plain_2")),
                selection((x, z) -> new TerrainPreviewSample(x, z, 64.0, false, "minecraft:plains")));

        assertEquals(32, result.cellStepBlocks());
        assertEquals(32, result.cellCount());
        assertEquals(1, result.patches().size());
        TerrainScalePatchService.Patch patch = result.patches().get(0);
        assertEquals("plain", patch.type());
        assertEquals(List.of("w_plain_1", "w_plain_2"), patch.sourcePatchRefs());
        assertTrue(patch.cells().stream().anyMatch(cell -> cell.blockX() == 480));
        assertTrue(patch.cells().stream().anyMatch(cell -> cell.blockX() == 512));
    }

    @Test
    void reclassifiesCoarseRidgeCellFromFineTerrainInsteadOfKeepingWType() {
        TerrainScalePatchService.Result result = new TerrainScalePatchService().analyze(
                "minecraft:overworld", "realm_water",
                List.of(new TerrainScalePatchService.SeedCell(0, 0, 0, 0, 128, "w_ridge_1")),
                selection((x, z) -> new TerrainPreviewSample(x, z, 62.0, x >= 64,
                        x >= 64 ? "minecraft:ocean" : "minecraft:plains")));

        assertEquals(16, result.cellCount());
        assertTrue(result.patches().stream().anyMatch(patch -> "water".equals(patch.type())));
        assertTrue(result.patches().stream().noneMatch(patch -> "ridge".equals(patch.type())));
        assertTrue(result.patches().stream().flatMap(patch -> patch.cells().stream())
                .filter(TerrainScalePatchService.PatchCell::water)
                .allMatch(cell -> "water".equals(cell.type())));
    }

    private static TerrainPreviewProviderSelection selection(SampleFunction samples) {
        TerrainPreviewProviderDescriptor descriptor = new TerrainPreviewProviderDescriptor(
                "synthetic", TerrainPreviewSourceKind.GENERATOR_NATIVE, true, "terrain-scale-test");
        TerrainPreviewProvider provider = new TerrainPreviewProvider() {
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
                return samples.sample(blockX, blockZ);
            }
        };
        return new TerrainPreviewProviderSelection(provider, "synthetic", "generator_native", true, "",
                "terrain-scale-test", "block_min_height_water");
    }

    @FunctionalInterface
    private interface SampleFunction {
        TerrainPreviewSample sample(int blockX, int blockZ);
    }
}
