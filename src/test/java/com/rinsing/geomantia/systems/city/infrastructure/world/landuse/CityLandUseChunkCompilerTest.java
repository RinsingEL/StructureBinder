package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfaceRunCompiler;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.CardinalDirection;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(0, west.surfaceOperations().get(0).surfaceOffset());
        assertEquals(0, west.surfaceOperations().get(0).layerOrder());
        assertFalse(west.surfaceOperations().get(0).requireReplaceableTarget());
        assertEquals("minecraft:oak_fence", west.boundaryOperations().get(0).blockId());
        assertEquals("minecraft:dirt", west.microFillBlockId());
        assertTrue(west.gradingMaskCells().stream().anyMatch(cell -> cell.x() == 16 && cell.z() == 0));

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

    @Test
    void cultivateAreaUsesLandUseBatchSurfacePrintingAndKeepsBoundary() {
        CityLandUseChunkCompiler.ChunkFragment fragment = compiler.compile(
                plan("city_farm", SurfacePolicy.CULTIVATE), 0, 0);

        assertEquals(17, fragment.surfaceOperations().size());
        assertTrue(fragment.surfaceOperations().stream()
                .allMatch(operation -> "minecraft:farmland".equals(operation.blockId())));
        assertEquals(0, fragment.gradingMaskCells().size());
        assertEquals(2, fragment.boundaryOperations().size());
        assertEquals("minecraft:oak_fence", fragment.boundaryOperations().get(0).blockId());
    }

    @Test
    void legacyPaletteWithoutCultivateMaterialKeepsCultivateSurfacePrintingDisabled() {
        CityLandUseChunkCompiler.MaterialPalette legacy = new CityLandUseChunkCompiler.MaterialPalette(
                Map.of("PAVE", "minecraft:stone_bricks"),
                Map.of("FENCE", "minecraft:oak_fence"));

        CityLandUseChunkCompiler.ChunkFragment fragment = new CityLandUseChunkCompiler(legacy)
                .compile(plan("city_legacy_farm", SurfacePolicy.CULTIVATE), 0, 0);

        assertTrue(fragment.surfaceOperations().isEmpty());
        assertEquals(2, fragment.boundaryOperations().size());
    }

    @Test
    void legacyPaletteWithoutReservedSubgradeKeepsMicroFillDisabled() {
        CityLandUseChunkCompiler.MaterialPalette legacy = new CityLandUseChunkCompiler.MaterialPalette(
                Map.of("PAVE", "minecraft:stone_bricks"),
                Map.of("FENCE", "minecraft:oak_fence"));

        CityLandUseChunkCompiler.ChunkFragment fragment =
                new CityLandUseChunkCompiler(legacy).compile(plan("city_legacy"), 0, 0);

        assertEquals(null, fragment.microFillBlockId());
        assertTrue(fragment.gradingMaskCells().isEmpty());
    }

    @Test
    void strictSurfacePlanUsesCustomUniformBlockInsteadOfPalette() {
        LandUseAreaPlan areaPlan = plan("city_custom_pave");
        LandUseAreaPlan.Area source = areaPlan.areas().get(0);
        CityLandUseSurfacePrintPlan.AreaPrint area = new CityLandUseSurfacePrintPlan.AreaPrint(
                "area_plaza/surface/0_0", source.areaId(), source.sourceGroupIds(),
                new LandUseSurfaceSettings(true, true, "example:granite_setts", "", "PAVE"),
                source.memberSpans(), exclusions(areaPlan, source), new BlockPoint(0, 0),
                CityLandUseSurfaceRunCompiler.WorldAxis.X,
                new CityLandUseSurfacePrintPlan.UniformRecipe("example:granite_setts"));
        CityLandUseSurfacePrintPlan surfacePlan = hashedSurfacePlan(
                areaPlan, "city_custom_pave", List.of(area));

        CityLandUseChunkCompiler.ChunkFragment fragment = compiler.compile(areaPlan, surfacePlan, 0, 0);

        assertFalse(fragment.surfaceOperations().isEmpty());
        assertTrue(fragment.surfaceOperations().stream()
                .allMatch(operation -> operation.blockId().equals("example:granite_setts")));
        assertTrue(fragment.surfaceOperations().stream()
                .allMatch(operation -> operation.stage() == CityLandUseChunkCompiler.SurfaceStage.BASE));
    }

    @Test
    void cultivatePlanKeepsGlobalFootprintsAcrossChunksAndRestoresCropsForTerminatedOrMissingPlacements() {
        SurfacePlans plans = cultivatePlans();

        CityLandUseChunkCompiler.ChunkFragment west = compiler.compile(
                plans.areaPlan(), plans.surfacePlan(), 0, 0);
        CityLandUseChunkCompiler.ChunkFragment east = compiler.compile(
                plans.areaPlan(), plans.surfacePlan(), 1, 0);

        assertOperation(west, 15, 1, "example:rich_farmland", CityLandUseChunkCompiler.SurfaceStage.BASE);
        assertNoOperation(west, 15, 1, "example:barley");
        assertOperation(east, 16, 1, "example:rich_farmland", CityLandUseChunkCompiler.SurfaceStage.BASE);
        assertOperation(east, 17, 1, "example:rich_farmland", CityLandUseChunkCompiler.SurfaceStage.BASE);
        assertNoOperation(east, 16, 1, "example:barley");
        assertNoOperation(east, 17, 1, "example:barley");

        assertOperation(west, 15, 2, "example:barley", CityLandUseChunkCompiler.SurfaceStage.CROP);
        assertOperation(east, 16, 2, "example:barley", CityLandUseChunkCompiler.SurfaceStage.CROP);
        assertOperation(east, 17, 2, "example:barley", CityLandUseChunkCompiler.SurfaceStage.CROP);
        assertOperation(east, 19, 1, "example:barley", CityLandUseChunkCompiler.SurfaceStage.CROP);

        assertNoOperation(west, 14, 0, "example:rich_farmland");
        assertNoOperation(east, 18, 1, "example:rich_farmland");
        assertNoOperation(west, 15, 3, "example:rich_farmland");
        assertFalse(west.boundaryOperations().stream()
                .anyMatch(operation -> operation.x() == 15 && operation.z() == 1));
        assertTrue(west.boundaryOperations().stream()
                .anyMatch(operation -> operation.x() == 14 && operation.z() == 1));
        assertBaseBeforeCrop(west);
        assertBaseBeforeCrop(east);
    }

    @Test
    void strictSurfacePlanRejectsOwnCityAndSourceHashMismatches() {
        SurfacePlans plans = cultivatePlans();
        CityLandUseSurfacePrintPlan valid = plans.surfacePlan();
        CityLandUseSurfacePrintPlan changedBehindHash = new CityLandUseSurfacePrintPlan(
                valid.schemaVersion(), valid.cityId(), valid.sourceLandUsePlanHash(), valid.catalogHash(),
                valid.planHash(), List.of());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(plans.areaPlan(), changedBehindHash, 0, 0))
                .getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_PLAN_HASH_MISMATCH"));

        CityLandUseSurfacePrintPlan wrongCity = new CityLandUseSurfacePrintPlanCodec().withComputedHash(
                new CityLandUseSurfacePrintPlan(valid.schemaVersion(), "other_city",
                        valid.sourceLandUsePlanHash(), valid.catalogHash(), "", valid.areas()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(plans.areaPlan(), wrongCity, 0, 0))
                .getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_CITY_MISMATCH"));

        CityLandUseSurfacePrintPlan wrongSource = new CityLandUseSurfacePrintPlanCodec().withComputedHash(
                new CityLandUseSurfacePrintPlan(valid.schemaVersion(), valid.cityId(),
                        "other-land-use-hash", valid.catalogHash(), "", valid.areas()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(plans.areaPlan(), wrongSource, 0, 0))
                .getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_SOURCE_HASH_MISMATCH"));
    }

    @Test
    void preparedSurfacePlanScansPlacementsOnceAcrossManyOwnerCompiles() {
        SurfacePlans plans = cultivatePlans();
        CityLandUseChunkCompiler.PreparedSurfacePlan prepared = compiler.prepare(
                plans.areaPlan(), plans.surfacePlan());

        for (int chunkX = -32; chunkX <= 32; chunkX++) {
            compiler.compilePrepared(prepared, chunkX, 0);
        }

        assertEquals(2, prepared.placementScanCount());
        assertTrue(prepared.indexedOwnerCount() > 0);
    }

    static LandUseAreaPlan plan(String cityId) {
        return plan(cityId, SurfacePolicy.PAVE);
    }

    static LandUseAreaPlan plan(String cityId, SurfacePolicy surfacePolicy) {
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
                12.0, surfacePolicy, VegetationPolicy.CLEAR, BoundaryPolicy.FENCE, "plaza_fill");
        LandUseAreaPlan unhashed = new LandUseAreaPlan(LandUseAreaPlan.CURRENT_SCHEMA_VERSION,
                "land_use_rules.v0.1", cityId, "", new BlockBounds(0, 0, 31, 15), List.of(area),
                List.of(), List.of(new LandUseAreaPlan.CorridorExclusion("road", new BlockBounds(2, 0, 2, 0),
                "d5_road")), List.of());
        return new LandUseAreaPlanCodec().withComputedHash(unhashed);
    }

    private static SurfacePlans cultivatePlans() {
        List<LandUseAreaPlan.ScanlineSpan> members = List.of(
                new LandUseAreaPlan.ScanlineSpan(0, 14, 18),
                new LandUseAreaPlan.ScanlineSpan(1, 14, 19),
                new LandUseAreaPlan.ScanlineSpan(2, 15, 19),
                new LandUseAreaPlan.ScanlineSpan(3, 15, 18));
        LandUseAreaPlan.Area area = new LandUseAreaPlan.Area("farm", "farm", "agriculture",
                List.of("farm_group"), List.of("barn"), List.of(new BlockPoint(14, 0)), members,
                List.of(new BlockBounds(14, 0, 14, 0)), List.of(new LandUseAreaPlan.BoundaryLoop(
                List.of(new BlockPoint(14, 1), new BlockPoint(15, 1)), false)),
                List.of(new LandUseAreaPlan.GateSlot("farm_gate", new BlockPoint(15, 3),
                        CardinalDirection.SOUTH, "barn")), 10, SurfacePolicy.CULTIVATE,
                VegetationPolicy.CLEAR, BoundaryPolicy.FENCE, "farm");
        LandUseAreaPlan rawAreaPlan = new LandUseAreaPlan(LandUseAreaPlan.CURRENT_SCHEMA_VERSION,
                "land_use_rules.v0.1", "city_farm", "", new BlockBounds(0, 0, 31, 15),
                List.of(area), List.of(), List.of(new LandUseAreaPlan.CorridorExclusion(
                "road", new BlockBounds(18, 1, 18, 1), "road")), List.of());
        LandUseAreaPlan areaPlan = new LandUseAreaPlanCodec().withComputedHash(rawAreaPlan);

        CityLandUseSurfacePrintPlan.SurfaceRun placed = run("farm/place", 1,
                placement("farm/place/0", "farm/place", 0, new BlockBounds(15, 1, 17, 1),
                        CityContinuousTerrainRunPlanner.Decision.PLACE, "safe"), null, "");
        CityLandUseSurfacePrintPlan.SurfaceRun terminated = run("farm/terminated", 2,
                placement("farm/terminated/0", "farm/terminated", 0, new BlockBounds(15, 2, 17, 2),
                        CityContinuousTerrainRunPlanner.Decision.TERMINATE, "blocked"), 0, "blocked");
        CityLandUseSurfaceRunCompiler.PrefabSpec straight = new CityLandUseSurfaceRunCompiler.PrefabSpec(
                CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF, "sha256:straight", 3, 2, 1);
        CityLandUseSurfaceRunCompiler.PrefabSpec endCap = new CityLandUseSurfaceRunCompiler.PrefabSpec(
                CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF, "sha256:endcap", 3, 2, 2);
        CityLandUseSurfacePrintPlan.CultivateLinedRecipe recipe =
                new CityLandUseSurfacePrintPlan.CultivateLinedRecipe(
                        "example:rich_farmland", "example:barley", 13, 5, 3, 5, 5,
                        straight, endCap, new CityLandUseSurfaceRunCompiler.TerrainPolicy(
                        1, false, 2, 8, CityContinuousTerrainRunPlanner.FoundationMode.NONE, 0, 0),
                        List.of(placed, terminated), List.of());
        CityLandUseSurfacePrintPlan.AreaPrint printArea = new CityLandUseSurfacePrintPlan.AreaPrint(
                "farm/surface/14_0", "farm", List.of("farm_group"),
                new LandUseSurfaceSettings(true, true, "example:rich_farmland",
                        "example:barley", "CULTIVATE"), members, exclusions(areaPlan, area),
                new BlockPoint(14, 0), CityLandUseSurfaceRunCompiler.WorldAxis.Z, recipe);
        return new SurfacePlans(areaPlan, hashedSurfacePlan(areaPlan, "city_farm", List.of(printArea)));
    }

    private static CityLandUseSurfacePrintPlan.SurfaceRun run(
            String runId,
            int cross,
            CityLandUseSurfacePrintPlan.SurfacePlacement placement,
            Integer terminationOrdinal,
            String terminationReason) {
        return new CityLandUseSurfacePrintPlan.SurfaceRun(runId,
                CityLandUseSurfaceRunCompiler.WorldAxis.Z, cross, List.of(placement),
                terminationOrdinal, terminationReason, List.of());
    }

    private static CityLandUseSurfacePrintPlan.SurfacePlacement placement(
            String placementId,
            String runId,
            int ordinal,
            BlockBounds footprint,
            CityContinuousTerrainRunPlanner.Decision decision,
            String reason) {
        return new CityLandUseSurfacePrintPlan.SurfacePlacement(placementId, runId, ordinal,
                new BlockPoint((footprint.minX() + footprint.maxX()) / 2, footprint.minZ()),
                new BlockPoint(footprint.minX(), footprint.minZ()), 0, footprint, 70, 70, false,
                CityContinuousTerrainRunPlanner.TerrainClass.SAFE, decision,
                CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF, "sha256:straight",
                CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF, "sha256:straight", reason);
    }

    private static CityLandUseSurfacePrintPlan hashedSurfacePlan(
            LandUseAreaPlan areaPlan,
            String cityId,
            List<CityLandUseSurfacePrintPlan.AreaPrint> areas) {
        return new CityLandUseSurfacePrintPlanCodec().withComputedHash(
                new CityLandUseSurfacePrintPlan(CityLandUseSurfacePrintPlan.CURRENT_SCHEMA_VERSION,
                        cityId, areaPlan.planHash(), areas.stream().anyMatch(value ->
                        value.recipe() instanceof CityLandUseSurfacePrintPlan.CultivateLinedRecipe)
                        ? "sha256:catalog" : "", "", areas));
    }

    private static List<LandUseAreaPlan.ScanlineSpan> exclusions(
            LandUseAreaPlan plan,
            LandUseAreaPlan.Area area) {
        java.util.ArrayList<LandUseAreaPlan.ScanlineSpan> result = new java.util.ArrayList<>();
        for (BlockBounds bounds : area.structureFootprintExclusions()) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                result.add(new LandUseAreaPlan.ScanlineSpan(z, bounds.minX(), bounds.maxX()));
            }
        }
        for (LandUseAreaPlan.CorridorExclusion exclusion : plan.corridorExclusions()) {
            BlockBounds bounds = exclusion.blockBounds();
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                result.add(new LandUseAreaPlan.ScanlineSpan(z, bounds.minX(), bounds.maxX()));
            }
        }
        area.gateSlots().forEach(gate -> result.add(new LandUseAreaPlan.ScanlineSpan(
                gate.block().z(), gate.block().x(), gate.block().x())));
        result.sort(java.util.Comparator.comparingInt(LandUseAreaPlan.ScanlineSpan::z)
                .thenComparingInt(LandUseAreaPlan.ScanlineSpan::minX));
        return List.copyOf(result);
    }

    private static void assertOperation(CityLandUseChunkCompiler.ChunkFragment fragment,
                                        int x,
                                        int z,
                                        String blockId,
                                        CityLandUseChunkCompiler.SurfaceStage stage) {
        assertTrue(fragment.surfaceOperations().stream().anyMatch(operation -> operation.x() == x
                && operation.z() == z && operation.blockId().equals(blockId) && operation.stage() == stage),
                x + "," + z + "=" + blockId);
    }

    private static void assertNoOperation(CityLandUseChunkCompiler.ChunkFragment fragment,
                                          int x,
                                          int z,
                                          String blockId) {
        assertFalse(fragment.surfaceOperations().stream().anyMatch(operation -> operation.x() == x
                && operation.z() == z && operation.blockId().equals(blockId)),
                x + "," + z + " must not contain " + blockId);
    }

    private static void assertBaseBeforeCrop(CityLandUseChunkCompiler.ChunkFragment fragment) {
        int firstCrop = -1;
        for (int index = 0; index < fragment.surfaceOperations().size(); index++) {
            if (fragment.surfaceOperations().get(index).stage() == CityLandUseChunkCompiler.SurfaceStage.CROP) {
                firstCrop = index;
                break;
            }
        }
        if (firstCrop >= 0) {
            assertTrue(fragment.surfaceOperations().subList(0, firstCrop).stream()
                    .allMatch(operation -> operation.stage() == CityLandUseChunkCompiler.SurfaceStage.BASE));
            assertTrue(fragment.surfaceOperations().subList(firstCrop, fragment.surfaceOperations().size()).stream()
                    .allMatch(operation -> operation.stage() == CityLandUseChunkCompiler.SurfaceStage.CROP));
        }
    }

    private record SurfacePlans(LandUseAreaPlan areaPlan,
                                CityLandUseSurfacePrintPlan surfacePlan) {
    }
}
