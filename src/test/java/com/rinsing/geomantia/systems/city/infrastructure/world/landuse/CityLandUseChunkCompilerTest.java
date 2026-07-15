package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.CardinalDirection;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CityLandUseChunkCompilerTest {
    private final LandUseAreaPlanCodec codec = new LandUseAreaPlanCodec();
    private final CityLandUseChunkCompiler compiler = new CityLandUseChunkCompiler();

    @Test
    void compilesTypedCodecPlanPerOwnerAndAppliesAllExclusions() {
        LandUseAreaPlan plan = plan("city_a");
        JsonObject wirePlan = codec.toJson(plan);

        CityLandUseChunkCompiler.ChunkFragment west = compiler.compile(wirePlan, 0, 0);
        CityLandUseChunkCompiler.ChunkFragment east = compiler.compile(wirePlan, 1, 0);

        assertEquals(17, west.surfaceOperations().size());
        assertEquals(List.of(new BlockPoint(0, 0), new BlockPoint(4, 0)),
                west.boundaryOperations().stream()
                        .map(operation -> new BlockPoint(operation.x(), operation.z())).toList());
        assertEquals(2, west.footprintExcludedCount());
        assertEquals(2, west.corridorExcludedCount());
        assertEquals(2, west.gateExcludedCount());
        assertEquals("minecraft:stone_bricks", west.surfaceOperations().get(0).blockId());
        assertEquals("minecraft:oak_fence", west.boundaryOperations().get(0).blockId());

        assertEquals(2, east.surfaceOperations().size());
        assertEquals(1, east.boundaryOperations().size());
        assertEquals(16, east.surfaceOperations().get(0).x());
        assertEquals(plan.planHash(), east.planHash());
    }

    @Test
    void rejectsChangedPlanBehindFrozenHash() {
        LandUseAreaPlan plan = plan("city_a");
        LandUseAreaPlan changed = new LandUseAreaPlan(plan.schemaVersion(), plan.ruleVersion(), "city_b",
                plan.planHash(), plan.planningBounds(), plan.areas(), plan.unclaimedSpans(),
                plan.corridorExclusions(), plan.warnings());

        assertThrows(IllegalArgumentException.class, () -> compiler.compile(changed, 0, 0));
    }

    static LandUseAreaPlan plan(String cityId) {
        LandUseAreaPlan.Area area = new LandUseAreaPlan.Area("area_plaza", "plaza_default", "plaza",
                List.of("group_a"), List.of("anchor_a"), List.of(new BlockPoint(0, 0)),
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 17),
                        new LandUseAreaPlan.ScanlineSpan(1, 0, 3)),
                List.of(new BlockBounds(1, 0, 1, 0)),
                List.of(new LandUseAreaPlan.BoundaryLoop(List.of(
                        new BlockPoint(0, 0), new BlockPoint(1, 0), new BlockPoint(2, 0),
                        new BlockPoint(3, 0), new BlockPoint(4, 0), new BlockPoint(16, 0)), false)),
                List.of(new LandUseAreaPlan.GateSlot("gate_a", new BlockPoint(3, 0),
                        CardinalDirection.NORTH, "anchor_a")),
                12.0, SurfacePolicy.PAVE, VegetationPolicy.CLEAR, BoundaryPolicy.FENCE, "plaza_fill");
        LandUseAreaPlan unhashed = new LandUseAreaPlan(LandUseAreaPlan.CURRENT_SCHEMA_VERSION,
                "land_use_rules.v0.1", cityId, "", new BlockBounds(0, 0, 31, 15), List.of(area),
                List.of(), List.of(new LandUseAreaPlan.CorridorExclusion("road", new BlockBounds(2, 0, 2, 0),
                "d5_road")), List.of());
        return new LandUseAreaPlanCodec().withComputedHash(unhashed);
    }
}
