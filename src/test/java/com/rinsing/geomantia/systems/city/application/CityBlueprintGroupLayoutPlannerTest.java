package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityBlueprintGroupLayoutPlannerTest {
    private final CityBlueprintGroupLayoutPlanner planner = new CityBlueprintGroupLayoutPlanner();

    @Test
    void densityCompilesIntoAlgorithmSpacingAndClaimArea() {
        var dense = planner.parameters("COMPACT", CityBlueprint.DensityClass.DENSE);
        var sparse = planner.parameters("COMPACT", CityBlueprint.DensityClass.SPARSE);
        BlockBounds footprint = new BlockBounds(0, 0, 14, 14);

        assertTrue(dense.targetEdgeGapBlocks() < sparse.targetEdgeGapBlocks());
        assertTrue(dense.maximumEdgeGapBlocks() < sparse.maximumEdgeGapBlocks());
        assertTrue(dense.landUseHandoffGapBlocks() < sparse.landUseHandoffGapBlocks());
        assertTrue(planner.claimedArea(footprint, dense) < planner.claimedArea(footprint, sparse));
    }

    @Test
    void compactLayoutKeepsTwoDimensionalMorphologyAcrossIncrementalSlots() {
        BlockPoint center = new BlockPoint(0, 0);
        var frame = planner.frame(center, new BlockPoint(100, 0), 17L, "civic");
        Set<Integer> xs = new LinkedHashSet<>();
        Set<Integer> zs = new LinkedHashSet<>();
        for (int slot = 1; slot <= 10; slot++) {
            var proposal = planner.propose("COMPACT", CityBlueprint.DensityClass.BALANCED,
                    17L, "civic", slot, frame, center, null, false, 18);
            xs.add(proposal.guides().get(0).x());
            zs.add(proposal.guides().get(0).z());
            assertEquals(slot, proposal.slotIndex());
        }

        assertTrue(xs.size() >= 5, "compact layout must spread across x");
        assertTrue(zs.size() >= 5, "compact layout must spread across z");
    }

    @Test
    void outwardGuidanceBiasesTheSameMorphologyTowardItsConnectionTarget() {
        BlockPoint center = new BlockPoint(0, 0);
        BlockPoint target = new BlockPoint(200, 0);
        var frame = planner.frame(center, target, 23L, "market");
        var free = planner.propose("ORGANIC_COMPACT", CityBlueprint.DensityClass.BALANCED,
                23L, "market", 6, frame, center, target, false, 20);
        var outward = planner.propose("ORGANIC_COMPACT", CityBlueprint.DensityClass.BALANCED,
                23L, "market", 6, frame, center, target, true, 20);

        assertTrue(outward.guides().get(0).x() > free.guides().get(0).x(),
                "outward mode should bias the organic slot toward the target without changing algorithms");
        assertEquals("ORGANIC_COMPACT", outward.algorithm());
        assertTrue(outward.outwardGuided());
    }

    @Test
    void gridLinearAndCourtyardRemainDistinctSpatialGrammars() {
        BlockPoint center = new BlockPoint(0, 0);
        var frame = planner.frame(center, new BlockPoint(100, 0), 31L, "district");
        BlockPoint grid = planner.propose("GRID", CityBlueprint.DensityClass.BALANCED,
                31L, "district", 4, frame, center, null, false, 16).guides().get(0);
        BlockPoint linear = planner.propose("LINEAR", CityBlueprint.DensityClass.BALANCED,
                31L, "district", 4, frame, center, null, false, 16).guides().get(0);
        BlockPoint courtyard = planner.propose("COURTYARD", CityBlueprint.DensityClass.BALANCED,
                31L, "district", 4, frame, center, null, false, 16).guides().get(0);

        assertTrue(!grid.equals(linear) && !grid.equals(courtyard) && !linear.equals(courtyard));
    }
}
