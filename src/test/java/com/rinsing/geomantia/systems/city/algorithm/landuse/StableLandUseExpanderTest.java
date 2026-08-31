package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableLandUseExpanderTest {
    @Test
    void overlappingSeedsLaunchEveryFrontierWithoutMakingResultOrderDependent() {
        LandUseTerrainField terrain = flatTerrain();
        List<BlockPoint> sharedSeeds = new ArrayList<>();
        for (int z = 12; z <= 20; z++) sharedSeeds.add(new BlockPoint(24, z));
        LandUseSeedGroup ground = group("agricultural_ground", sharedSeeds);
        LandUseSeedGroup landscape = group("capital_farmland", sharedSeeds);
        StableLandUseExpander expander = new StableLandUseExpander();

        LandUseExpansionResult forward = expander.expand("city_test", terrain.planningBounds(), terrain,
                List.of(ground, landscape), List.of(), "shared-seed");
        LandUseExpansionResult reversed = expander.expand("city_test", terrain.planningBounds(), terrain,
                List.of(landscape, ground), List.of(), "shared-seed");

        assertTrue(forward.claimedBlocksByGroup().getOrDefault(ground.groupId(), 0) > 0);
        assertTrue(forward.claimedBlocksByGroup().getOrDefault(landscape.groupId(), 0) > 0);
        assertEquals(forward.claims(), reversed.claims());
        assertEquals(forward.claimedBlocksByGroup(), reversed.claimedBlocksByGroup());
    }

    private static LandUseSeedGroup group(String groupId, List<BlockPoint> seeds) {
        LandUseRule rule = new LandUseRule("agriculture", "Agriculture", List.of("agriculture"),
                1, 0, 1, 256, 64, 1, 0, 0, 0, 0, 1, true,
                SurfacePolicy.CULTIVATE, VegetationPolicy.CLEAR, BoundaryPolicy.FENCE, "agriculture");
        return new LandUseSeedGroup(groupId, rule, LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE),
                List.of(groupId), List.of(), seeds, List.of(), 1, 32, 64, 256, 1);
    }

    private static LandUseTerrainField flatTerrain() {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x < 16; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        70, 0, 0, 0, false, 0, 40,
                        "minecraft:plains", "plain", "plain", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city_test",
                new BlockBounds(0, 0, 63, 39), 4, cells);
    }
}
