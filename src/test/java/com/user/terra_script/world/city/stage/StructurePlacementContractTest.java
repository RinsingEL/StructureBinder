package com.user.terra_script.world.city.stage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructurePlacementContractTest {
    @Test
    void originOffsetYDirectlyControlsSurfaceAlignedOriginY() {
        assertEquals(64, StructurePlacementContract.resolveSurfaceAlignedOriginY(64, 0));
        assertEquals(63, StructurePlacementContract.resolveSurfaceAlignedOriginY(64, -1));
        assertEquals(66, StructurePlacementContract.resolveSurfaceAlignedOriginY(64, 2));
    }

    @Test
    void missingTemplateFallsBackToSurfaceY() {
        assertEquals(64, StructurePlacementContract.resolveSurfaceAlignedOriginY("missing:test_template", 64));
    }
}
