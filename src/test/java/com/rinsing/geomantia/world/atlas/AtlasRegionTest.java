package com.rinsing.geomantia.world.atlas;

import com.rinsing.geomantia.world.atlas.region.AtlasRegion;
import com.rinsing.geomantia.world.atlas.region.AtlasRegionStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AtlasRegionTest {
    @Test
    void regionCoordinatesAreStableForNegativeAndPositiveBlocks() {
        GisSampleConfig config = GisSampleConfig.defaults();
        AtlasRegionStore store = new AtlasRegionStore(config);

        AtlasRegion origin = store.regionForBlock("minecraft:overworld", 0, 0);
        AtlasRegion west = store.regionForBlock("minecraft:overworld", -1, 0);
        AtlasRegion far = store.regionForBlock("minecraft:overworld", 700, -700);

        assertEquals(0, origin.regionX());
        assertEquals(0, origin.regionZ());
        assertEquals(-1, west.regionX());
        assertEquals(0, west.regionZ());
        assertEquals(1, far.regionX());
        assertEquals(-2, far.regionZ());
        assertSame(origin, store.regionForBlock("minecraft:overworld", 12, 12));
    }

    @Test
    void cellsExposeContractCoordinatesAndRegionBounds() {
        GisSampleConfig config = GisSampleConfig.defaults();
        AtlasRegion region = new AtlasRegion("minecraft:overworld", 1, -1, config);

        assertEquals(512, region.blockMinX());
        assertEquals(-512, region.blockMinZ());
        assertEquals(128, region.cellsPerSide());
        assertEquals(512, region.cell(0, 0).blockMinX());
        assertEquals(-512, region.cell(0, 0).blockMinZ());
        assertEquals(512 + 127 * 4, region.cell(127, 127).blockMinX());
        assertEquals(-512 + 127 * 4, region.cell(127, 127).blockMinZ());
    }
}
