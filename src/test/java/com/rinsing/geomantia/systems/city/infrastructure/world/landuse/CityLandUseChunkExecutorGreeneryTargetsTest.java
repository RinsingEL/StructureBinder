package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CityLandUseChunkExecutorGreeneryTargetsTest {
    private static final String SOURCE = "public_greenery::city";

    private static final class Heights implements CityLandUseChunkExecutor.ExecutionWorld {
        private final Map<Integer, Integer> byX;
        Heights(Map<Integer, Integer> byX) { this.byX = byX; }
        @Override public CityLandUseChunkExecutor.ColumnSample sampleColumn(int x, int z) {
            return new CityLandUseChunkExecutor.ColumnSample(byX.getOrDefault(x, 64), "minecraft:grass_block", true);
        }
        @Override public boolean isKnownBlock(String blockId) { return true; }
        @Override public boolean ensureCanWrite(int x, int y, int z) { return true; }
        @Override public CityLandUseChunkExecutor.TargetState inspect(int x, int y, int z) {
            return new CityLandUseChunkExecutor.TargetState("minecraft:air", true);
        }
        @Override public boolean restoreBlock(int x, int y, int z, Object old) { return true; }
        @Override public boolean setBlock(int x, int y, int z, String blockId) { return true; }
    }

    private static CityLandUseChunkCompiler.FeatureOperation green(int x, int z) {
        return new CityLandUseChunkCompiler.FeatureOperation(SOURCE, x, z, "minecraft:grass_block", 0,
                CityLandUseSurfacePrintPlan.FeatureKind.GREEN_GROUND,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NONE, null);
    }

    @Test void smallStepIsFoldedToTheNeighbourhoodMode() {
        var world = new Heights(Map.of(0, 64, 1, 66, 2, 64));
        var targets = CityLandUseChunkExecutor.greenerySectionTargets(world,
                List.of(green(0, 0), green(1, 0), green(2, 0)));
        assertEquals(64, targets.get(new CityLandUseChunkExecutor.ColumnKey(0, 0)));
        assertEquals(64, targets.get(new CityLandUseChunkExecutor.ColumnKey(1, 0)));
        assertEquals(64, targets.get(new CityLandUseChunkExecutor.ColumnKey(2, 0)));
    }

    @Test void largeStepKeepsOriginalTerrain() {
        var world = new Heights(Map.of(0, 64, 1, 70, 2, 64));
        var targets = CityLandUseChunkExecutor.greenerySectionTargets(world,
                List.of(green(0, 0), green(1, 0), green(2, 0)));
        assertFalse(targets.containsKey(new CityLandUseChunkExecutor.ColumnKey(1, 0)));
        assertTrue(targets.containsKey(new CityLandUseChunkExecutor.ColumnKey(0, 0)));
    }

    @Test void pathsAndPlantsDoNotBecomeLevelTargetsOnTheirOwn() {
        var world = new Heights(Map.of(0, 64));
        var plant = new CityLandUseChunkCompiler.FeatureOperation(SOURCE, 0, 0, "minecraft:allium", 1,
                CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PLANT,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NONE, null);
        assertTrue(CityLandUseChunkExecutor.greenerySectionTargets(world, List.of(plant)).isEmpty());
    }
}
