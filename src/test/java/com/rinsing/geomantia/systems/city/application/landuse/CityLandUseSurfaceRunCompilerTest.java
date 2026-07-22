package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseSurfaceRunCompilerTest {
    private final CityLandUseSurfaceRunCompiler compiler = new CityLandUseSurfaceRunCompiler();

    @Test
    void keepsWorldPhaseAndOneRunAcrossChunkBoundary() {
        CityLandUseSurfaceRunCompiler.Plan plan = compiler.compile(request(
                rectangle(30, 35, 14, 18), List.of(), CityLandUseSurfaceRunCompiler.WorldAxis.Z,
                13, 6), (x, z) -> sample(70));

        assertEquals(1, plan.runs().size());
        CityLandUseSurfaceRunCompiler.Run run = plan.runs().get(0);
        assertEquals(5, run.placements().size());
        assertEquals(List.of(0, 1, 2, 3, 4), run.placements().stream()
                .map(CityLandUseSurfaceRunCompiler.Placement::runOrdinal).toList());
        assertTrue(run.placements().stream().anyMatch(value -> Math.floorDiv(
                value.terrainSamplePoint().z(), 16) == 0));
        assertTrue(run.placements().stream().anyMatch(value -> Math.floorDiv(
                value.terrainSamplePoint().z(), 16) == 1));
        assertTrue(run.placements().stream().allMatch(value ->
                value.footprint().minX() == 32 && value.footprint().maxX() == 34));
    }

    @Test
    void exclusionHoleSplitsGlobalMaskRunWithoutBoundingBoxFill() {
        List<LandUseAreaPlan.ScanlineSpan> exclusions = List.of(
                new LandUseAreaPlan.ScanlineSpan(3, 32, 34));

        CityLandUseSurfaceRunCompiler.Plan plan = compiler.compile(request(
                rectangle(30, 35, 0, 6), exclusions, CityLandUseSurfaceRunCompiler.WorldAxis.Z,
                13, 6), (x, z) -> sample(70));

        assertEquals(2, plan.runs().size());
        assertEquals(List.of(3, 3), plan.runs().stream()
                .map(run -> run.placements().size()).sorted().toList());
        assertTrue(plan.runs().stream().flatMap(run -> run.placements().stream())
                .noneMatch(value -> value.footprint().contains(33, 3)));
    }

    @Test
    void localCliffUsesConcreteLinedEndCapAndFreezesItsFootprint() {
        CityLandUseSurfaceRunCompiler.Plan plan = compiler.compile(request(
                rectangle(0, 5, 5, 7), List.of(), CityLandUseSurfaceRunCompiler.WorldAxis.X,
                13, 5), (x, z) -> sample(x >= 3 ? 60 : 70));

        CityLandUseSurfaceRunCompiler.Run run = plan.runs().get(0);
        CityLandUseSurfaceRunCompiler.Placement endCap = run.placements().get(2);
        assertEquals(CityContinuousTerrainRunPlanner.Decision.END_CAP, endCap.decision());
        assertEquals(CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF, endCap.appliedContentRef());
        assertEquals("sha256:end", endCap.appliedContentHash());
        assertEquals(new BlockBounds(2, 5, 3, 7), endCap.footprint());
        assertEquals(new BlockPoint(2, 7), endCap.placementAnchor());
        assertEquals(270, endCap.rotationDegrees());
        assertEquals(CityContinuousTerrainRunPlanner.Decision.TERMINATE,
                run.placements().get(3).decision());
        assertEquals("CITY_LAND_USE_SURFACE_RUN_LOCAL_CLIFF_TERMINATED",
                run.terminationReasonCode());
    }

    @Test
    void negativeXDirectionOrdersOutwardAndRotatesEndCapBackTowardCenter() {
        CityLandUseSurfaceRunCompiler.Plan plan = compiler.compile(request(
                rectangle(0, 5, 5, 7), List.of(), CityLandUseSurfaceRunCompiler.WorldAxis.X,
                13, 5, -1), (x, z) -> sample(x >= 3 ? 70 : 60));

        CityLandUseSurfaceRunCompiler.Run run = plan.runs().get(0);
        assertEquals(List.of(5, 4, 3, 2, 1, 0), run.placements().stream()
                .map(value -> value.terrainSamplePoint().x()).toList());
        CityLandUseSurfaceRunCompiler.Placement endCap = run.placements().get(2);
        assertEquals(CityContinuousTerrainRunPlanner.Decision.END_CAP, endCap.decision());
        assertEquals(new BlockBounds(2, 5, 3, 7), endCap.footprint());
        assertEquals(new BlockPoint(3, 5), endCap.placementAnchor());
        assertEquals(90, endCap.rotationDegrees());
        assertEquals(CityContinuousTerrainRunPlanner.Decision.TERMINATE,
                run.placements().get(3).decision());
    }

    private static CityLandUseSurfaceRunCompiler.Request request(
            List<LandUseAreaPlan.ScanlineSpan> members,
            List<LandUseAreaPlan.ScanlineSpan> exclusions,
            CityLandUseSurfaceRunCompiler.WorldAxis axis,
            int period,
            int offset) {
        return request(members, exclusions, axis, period, offset, 1);
    }

    private static CityLandUseSurfaceRunCompiler.Request request(
            List<LandUseAreaPlan.ScanlineSpan> members,
            List<LandUseAreaPlan.ScanlineSpan> exclusions,
            CityLandUseSurfaceRunCompiler.WorldAxis axis,
            int period,
            int offset,
            int directionSign) {
        return new CityLandUseSurfaceRunCompiler.Request("farm", members, exclusions, axis,
                BlockPoint.ORIGIN, period, offset, directionSign,
                new CityLandUseSurfaceRunCompiler.PrefabSpec(
                        CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF,
                        "sha256:straight", 3, 2, 1),
                new CityLandUseSurfaceRunCompiler.PrefabSpec(
                        CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF,
                        "sha256:end", 3, 2, 2),
                new CityLandUseSurfaceRunCompiler.TerrainPolicy(2, false, 8, 8,
                        CityContinuousTerrainRunPlanner.FoundationMode.NONE, 0, 0));
    }

    private static List<LandUseAreaPlan.ScanlineSpan> rectangle(int minX, int maxX, int minZ, int maxZ) {
        List<LandUseAreaPlan.ScanlineSpan> result = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) {
            result.add(new LandUseAreaPlan.ScanlineSpan(z, minX, maxX));
        }
        return List.copyOf(result);
    }

    private static CityContinuousTerrainRunPlanner.TerrainSample sample(int y) {
        return new CityContinuousTerrainRunPlanner.TerrainSample(y, false, true);
    }
}
