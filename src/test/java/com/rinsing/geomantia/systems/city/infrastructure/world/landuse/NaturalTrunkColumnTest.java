package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import org.junit.jupiter.api.Test;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

class NaturalTrunkColumnTest {
    @Test void smallPlantsAreClearableButSolidTimberIsNot() {
        assertTrue(CityLandUseChunkExecutor.WorldGenExecutionWorld.isLandUseReplaceable(false, false, false, true));
        assertFalse(CityLandUseChunkExecutor.WorldGenExecutionWorld.isLandUseReplaceable(false, false, false, false));
    }

    private final IntFunction<NaturalTrunkColumn.Cell> tree = y -> y == 70
            ? NaturalTrunkColumn.Cell.SOIL : y >= 71 && y <= 80
            ? NaturalTrunkColumn.Cell.TRUNK : y == 82 ? NaturalTrunkColumn.Cell.NATURAL_LEAVES
            : NaturalTrunkColumn.Cell.REPLACEABLE;

    @Test void rootedNaturalTreeCanBeGradedButTemplateColumnCannot() {
        assertTrue(NaturalTrunkColumn.canClear(80, false, tree));
        assertFalse(NaturalTrunkColumn.canClear(80, true, tree));
    }

    @Test void timberWithoutNaturalCanopyOrRootIsNotTerrain() {
        assertFalse(NaturalTrunkColumn.canClear(80, false,
                y -> y == 82 ? NaturalTrunkColumn.Cell.OTHER : tree.apply(y)));
        assertFalse(NaturalTrunkColumn.canClear(80, false,
                y -> y == 70 ? NaturalTrunkColumn.Cell.OTHER : tree.apply(y)));
        assertFalse(NaturalTrunkColumn.canClear(80, false, y -> NaturalTrunkColumn.Cell.TRUNK));
    }
}
