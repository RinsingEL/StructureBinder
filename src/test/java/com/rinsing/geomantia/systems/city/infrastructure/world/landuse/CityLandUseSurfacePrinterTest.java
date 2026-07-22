package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseSurfacePrinterTest {
    private final CityLandUseSurfacePrinter printer = new CityLandUseSurfacePrinter();

    @Test
    void uniformRecipeCanPrintSurfaceThenBatchPlantOverlay() {
        CityLandUseSurfacePrinter.Recipe recipe = new CityLandUseSurfacePrinter.Recipe("uniform_farmland",
                new CityLandUseSurfacePrinter.UniformPattern(List.of(
                        CityLandUseSurfacePrinter.Layer.surface("minecraft:farmland"),
                        CityLandUseSurfacePrinter.Layer.overlay("minecraft:wheat", 1))));

        List<CityLandUseSurfacePrinter.PrintOperation> operations = printer.operationsAt(recipe, 80, -33);

        assertEquals(List.of("minecraft:farmland", "minecraft:wheat"),
                operations.stream().map(CityLandUseSurfacePrinter.PrintOperation::blockId).toList());
        assertEquals(List.of(0, 1),
                operations.stream().map(CityLandUseSurfacePrinter.PrintOperation::surfaceOffset).toList());
        assertFalse(operations.get(0).requireReplaceableTarget());
        assertTrue(operations.get(1).requireReplaceableTarget());
    }

    @Test
    void repeatedCrossSectionKeepsWorldPhaseAcrossChunkBoundary() {
        CityLandUseSurfacePrinter.Layer farmland =
                CityLandUseSurfacePrinter.Layer.surface("minecraft:farmland");
        CityLandUseSurfacePrinter.Layer slab =
                CityLandUseSurfacePrinter.Layer.surface("minecraft:oak_slab");
        CityLandUseSurfacePrinter.Layer channel =
                CityLandUseSurfacePrinter.Layer.surface("minecraft:water");
        CityLandUseSurfacePrinter.Recipe recipe = new CityLandUseSurfacePrinter.Recipe("farm_cross_section",
                new CityLandUseSurfacePrinter.CrossSectionRepeatPattern(
                        CityLandUseSurfacePrinter.Axis.X, 0, 0, List.of(
                        new CityLandUseSurfacePrinter.Band(5, List.of(farmland)),
                        new CityLandUseSurfacePrinter.Band(1, List.of(slab)),
                        new CityLandUseSurfacePrinter.Band(1, List.of(channel)),
                        new CityLandUseSurfacePrinter.Band(1, List.of(slab)),
                        new CityLandUseSurfacePrinter.Band(5, List.of(farmland)))));

        assertEquals("minecraft:oak_slab", blockAt(recipe, 31, 0));
        assertEquals("minecraft:water", blockAt(recipe, 32, 0));
        assertEquals("minecraft:oak_slab", blockAt(recipe, 33, 0));
        assertEquals("minecraft:farmland", blockAt(recipe, 34, 0));
        assertEquals("minecraft:water", blockAt(recipe, -7, 0));
    }

    @Test
    void rejectsAmbiguousLayersAtTheSameSurfaceOffset() {
        assertThrows(IllegalArgumentException.class, () -> new CityLandUseSurfacePrinter.UniformPattern(List.of(
                CityLandUseSurfacePrinter.Layer.surface("minecraft:farmland"),
                CityLandUseSurfacePrinter.Layer.surface("minecraft:dirt"))));
    }

    private String blockAt(CityLandUseSurfacePrinter.Recipe recipe, int x, int z) {
        return printer.operationsAt(recipe, x, z).get(0).blockId();
    }
}
