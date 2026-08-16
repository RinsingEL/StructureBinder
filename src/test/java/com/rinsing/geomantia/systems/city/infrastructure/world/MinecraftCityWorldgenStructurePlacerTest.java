package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftCityWorldgenStructurePlacerTest {
    @Test
    void foundationDatumUsesWholeFootprintMedianInsteadOfAnchorHeight() {
        BlockBounds footprint = new BlockBounds(0, 0, 8, 8);

        int datum = MinecraftCityWorldgenStructurePlacer.medianFoundationDatum(
                footprint, (x, z) -> x == 0 && z == 0 ? 100 : 64);

        assertEquals(64, datum);
    }

    @Test
    void foundationDatumIncludesFarFootprintEdgesInSampling() {
        BlockBounds footprint = new BlockBounds(2, 3, 6, 7);

        int datum = MinecraftCityWorldgenStructurePlacer.medianFoundationDatum(
                footprint, (x, z) -> x == 6 ? 80 : 60);

        assertEquals(70, datum);
    }
}
