package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseChunkCompilerTest {
    private final CityLandUseChunkCompiler compiler = new CityLandUseChunkCompiler();

    @Test
    void uniformPlanUsesRuntimeBlockAndPreservesFrozenExclusions() {
        LandUseAreaPlan areaPlan = areaPlan("city_uniform", SurfacePolicy.PAVE,
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 17)));
        LandUseAreaPlan.Area source = areaPlan.areas().get(0);
        LandUseSurfaceSettings settings = new LandUseSurfaceSettings(
                true, true, "example:granite_setts", "", "PAVE");
        CityLandUseSurfacePrintPlan.AreaPrint print = new CityLandUseSurfacePrintPlan.AreaPrint(
                "area/surface", source.areaId(), source.sourceGroupIds(), settings,
                source.memberSpans(), List.of(new LandUseAreaPlan.ScanlineSpan(0, 1, 2)),
                new CityLandUseSurfacePrintPlan.UniformRecipe(settings.surfaceBlockId()));
        CityLandUseSurfacePrintPlan surfacePlan = hashed(areaPlan, List.of(print));

        CityLandUseChunkCompiler.ChunkFragment fragment = compiler.compile(areaPlan, surfacePlan, 0, 0);

        assertOperation(fragment, 0, 0, "example:granite_setts",
                CityLandUseChunkCompiler.SurfaceStage.BASE);
        assertNoOperation(fragment, 1, 0, "example:granite_setts");
        assertNoOperation(fragment, 2, 0, "example:granite_setts");
        assertTrue(fragment.surfaceOperations().stream()
                .allMatch(operation -> operation.stage() == CityLandUseChunkCompiler.SurfaceStage.BASE));
        assertFalse(fragment.gradingMaskCells().isEmpty());
    }

    @Test
    void contourPlanConsumesFrozenRolesAcrossChunkBoundary() {
        List<LandUseAreaPlan.ScanlineSpan> members = List.of(
                new LandUseAreaPlan.ScanlineSpan(0, 14, 17));
        LandUseAreaPlan areaPlan = areaPlan("city_contour", SurfacePolicy.CULTIVATE, members);
        BlockPoint anchor = new BlockPoint(15, 0);
        LandUseSurfaceSettings settings = new LandUseSurfaceSettings(true, true,
                "example:rich_farmland", "example:barley", "CULTIVATE",
                LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS, anchor,
                "example:channel_bank", "minecraft:water", "example:bank_slab");
        CityLandUseSurfacePrintPlan.ContourBandsRecipe recipe =
                new CityLandUseSurfacePrintPlan.ContourBandsRecipe(
                        settings.surfaceBlockId(), settings.cropBlockId(), settings.channelBankBlockId(),
                        settings.channelWaterBlockId(), settings.channelBankOverlayBlockId(), 13, 5, 3, 5,
                        CityLandUseSurfacePrintPlan.ClassificationMode.CONTOUR_NORMAL, anchor,
                        List.of(
                                new CityLandUseSurfacePrintPlan.BandSpan(
                                        0, 14, 14, CityLandUseSurfacePrintPlan.BandRole.FIELD),
                                new CityLandUseSurfacePrintPlan.BandSpan(
                                        0, 15, 15, CityLandUseSurfacePrintPlan.BandRole.CHANNEL_BEFORE_BANK),
                                new CityLandUseSurfacePrintPlan.BandSpan(
                                        0, 16, 16, CityLandUseSurfacePrintPlan.BandRole.CHANNEL_WATER),
                                new CityLandUseSurfacePrintPlan.BandSpan(
                                        0, 17, 17, CityLandUseSurfacePrintPlan.BandRole.CHANNEL_END_CAP)));
        CityLandUseSurfacePrintPlan.AreaPrint print = new CityLandUseSurfacePrintPlan.AreaPrint(
                "area/surface", "area", List.of("group"), settings, members, List.of(),
                LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS, anchor, recipe);
        CityLandUseSurfacePrintPlan surfacePlan = hashed(areaPlan, List.of(print));

        CityLandUseChunkCompiler.ChunkFragment west = compiler.compile(areaPlan, surfacePlan, 0, 0);
        CityLandUseChunkCompiler.ChunkFragment east = compiler.compile(areaPlan, surfacePlan, 1, 0);

        assertOperation(west, 14, 0, "example:rich_farmland", CityLandUseChunkCompiler.SurfaceStage.BASE);
        assertOperation(west, 14, 0, "example:barley", CityLandUseChunkCompiler.SurfaceStage.CROP);
        assertOperation(west, 15, 0, "example:bank_slab",
                CityLandUseChunkCompiler.SurfaceStage.CHANNEL_OVERLAY);
        assertOperation(east, 16, 0, "minecraft:water", CityLandUseChunkCompiler.SurfaceStage.BASE);
        assertOperation(east, 17, 0, "example:bank_slab",
                CityLandUseChunkCompiler.SurfaceStage.CHANNEL_OVERLAY);
        assertNoOperation(east, 16, 0, "example:barley");
    }

    @Test
    void rejectsSurfacePlanHashCityAndSourceMismatches() {
        LandUseAreaPlan areaPlan = areaPlan("city_validation", SurfacePolicy.PAVE,
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 3)));
        CityLandUseSurfacePrintPlan valid = uniformPlan(areaPlan);
        CityLandUseSurfacePrintPlan changedBehindHash = new CityLandUseSurfacePrintPlan(
                valid.schemaVersion(), valid.cityId(), valid.sourceLandUsePlanHash(),
                valid.planHash(), List.of());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(areaPlan, changedBehindHash, 0, 0))
                .getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_PLAN_HASH_MISMATCH"));

        CityLandUseSurfacePrintPlan wrongCity = new CityLandUseSurfacePrintPlanCodec().withComputedHash(
                new CityLandUseSurfacePrintPlan(valid.schemaVersion(), "other_city",
                        valid.sourceLandUsePlanHash(), "", valid.areas()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(areaPlan, wrongCity, 0, 0))
                .getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_CITY_MISMATCH"));

        CityLandUseSurfacePrintPlan wrongSource = new CityLandUseSurfacePrintPlanCodec().withComputedHash(
                new CityLandUseSurfacePrintPlan(valid.schemaVersion(), valid.cityId(),
                        "other-land-use-hash", "", valid.areas()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(areaPlan, wrongSource, 0, 0))
                .getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_SOURCE_HASH_MISMATCH"));
    }

    @Test
    void preparedPlanIndexesOnlyRelevantOwnerChunks() {
        LandUseAreaPlan areaPlan = areaPlan("city_prepared", SurfacePolicy.PAVE,
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 17)));
        CityLandUseChunkCompiler.PreparedSurfacePlan prepared = compiler.prepare(
                areaPlan, uniformPlan(areaPlan));

        assertEquals(2, prepared.indexedOwnerCount());
        assertTrue(compiler.compilePrepared(prepared, 0, 0).hasRelevantCells());
        assertTrue(compiler.compilePrepared(prepared, 1, 0).hasRelevantCells());
        assertFalse(compiler.compilePrepared(prepared, 2, 0).hasRelevantCells());
    }

    @Test
    void gradingContextAloneDoesNotMakeAnOwnerRelevant() {
        LandUseAreaPlan areaPlan = areaPlan("city_excluded", SurfacePolicy.PAVE,
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 3)));
        LandUseAreaPlan.Area source = areaPlan.areas().get(0);
        LandUseSurfaceSettings settings = new LandUseSurfaceSettings(
                true, true, "minecraft:stone_bricks", "", "PAVE");
        CityLandUseSurfacePrintPlan surfacePlan = hashed(areaPlan, List.of(
                new CityLandUseSurfacePrintPlan.AreaPrint(
                        "area/surface", source.areaId(), source.sourceGroupIds(), settings,
                        source.memberSpans(), source.memberSpans(),
                        new CityLandUseSurfacePrintPlan.UniformRecipe(settings.surfaceBlockId()))));

        CityLandUseChunkCompiler.ChunkFragment fragment = compiler.compile(areaPlan, surfacePlan, 0, 0);

        assertFalse(fragment.gradingMaskCells().isEmpty());
        assertTrue(fragment.surfaceOperations().isEmpty());
        assertTrue(fragment.boundaryOperations().isEmpty());
        assertFalse(fragment.hasRelevantCells());
    }

    @Test
    void contourBoundaryIsGeneratedOnlyOnFieldRoles() {
        List<LandUseAreaPlan.ScanlineSpan> members = List.of(
                new LandUseAreaPlan.ScanlineSpan(0, 0, 3));
        LandUseAreaPlan.Area area = new LandUseAreaPlan.Area(
                "area", "area", "land_use", List.of("group"), List.of("anchor"),
                List.of(new BlockPoint(0, 0)), members, List.of(),
                List.of(new LandUseAreaPlan.BoundaryLoop(List.of(
                        new BlockPoint(0, 0), new BlockPoint(1, 0),
                        new BlockPoint(2, 0), new BlockPoint(3, 0)), false)),
                List.of(), 10, SurfacePolicy.CULTIVATE, VegetationPolicy.CLEAR,
                BoundaryPolicy.FENCE, "area");
        LandUseAreaPlan areaPlan = new LandUseAreaPlanCodec().withComputedHash(new LandUseAreaPlan(
                LandUseAreaPlan.CURRENT_SCHEMA_VERSION, "land_use_rules.v0.1", "city_boundary", "",
                new BlockBounds(0, 0, 3, 0), List.of(area), List.of(), List.of(), List.of()));
        BlockPoint anchor = new BlockPoint(0, 0);
        LandUseSurfaceSettings settings = new LandUseSurfaceSettings(true, true,
                "minecraft:farmland", "minecraft:wheat", "CULTIVATE",
                LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS, anchor,
                "minecraft:dirt", "minecraft:water", "minecraft:oak_slab");
        CityLandUseSurfacePrintPlan.ContourBandsRecipe recipe =
                new CityLandUseSurfacePrintPlan.ContourBandsRecipe(
                        settings.surfaceBlockId(), settings.cropBlockId(), settings.channelBankBlockId(),
                        settings.channelWaterBlockId(), settings.channelBankOverlayBlockId(),
                        13, 5, 3, 5, CityLandUseSurfacePrintPlan.ClassificationMode.CONTOUR_NORMAL,
                        anchor, List.of(
                        new CityLandUseSurfacePrintPlan.BandSpan(
                                0, 0, 0, CityLandUseSurfacePrintPlan.BandRole.FIELD),
                        new CityLandUseSurfacePrintPlan.BandSpan(
                                0, 1, 1, CityLandUseSurfacePrintPlan.BandRole.CHANNEL_BEFORE_BANK),
                        new CityLandUseSurfacePrintPlan.BandSpan(
                                0, 2, 2, CityLandUseSurfacePrintPlan.BandRole.CHANNEL_WATER),
                        new CityLandUseSurfacePrintPlan.BandSpan(
                                0, 3, 3, CityLandUseSurfacePrintPlan.BandRole.CHANNEL_END_CAP)));
        CityLandUseSurfacePrintPlan surfacePlan = hashed(areaPlan, List.of(
                new CityLandUseSurfacePrintPlan.AreaPrint(
                        "area/surface", area.areaId(), area.sourceGroupIds(), settings,
                        members, List.of(), LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS,
                        anchor, recipe)));

        CityLandUseChunkCompiler.ChunkFragment fragment = compiler.compile(areaPlan, surfacePlan, 0, 0);

        assertEquals(1, fragment.boundaryOperations().size());
        assertEquals(0, fragment.boundaryOperations().get(0).x());
        assertEquals(0, fragment.boundaryOperations().get(0).z());
    }

    @Test
    void relayRegionsConsumeFrozenRegionSpansAcrossChunkBoundary() {
        List<LandUseAreaPlan.ScanlineSpan> members = List.of(new LandUseAreaPlan.ScanlineSpan(0, 14, 17));
        LandUseAreaPlan areaPlan = areaPlan("city_layers", SurfacePolicy.CULTIVATE, members);
        BlockPoint source = new BlockPoint(14, 0);
        LandUseSurfaceSettings settings = LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE)
                .forRelayRegionGrowth();
        CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe recipe =
                new CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe(
                        settings.surfaceBlockId(), settings.cropBlockId(), settings.channelBankBlockId(),
                        settings.channelWaterBlockId(), settings.channelBankOverlayBlockId(), "",
                        "fill:irrigated", "role:field", 81L, source,
                        List.of(new CityLandUseSurfacePrintPlan.RelayRoleDefinition("role:field",
                                        LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT,
                                        LandscapeFillProgram.GrowthForm.PATCH, 0.25),
                                new CityLandUseSurfacePrintPlan.RelayRoleDefinition("role:bank",
                                        LandscapeFillProgram.MaterialRole.BANK,
                                        LandscapeFillProgram.GrowthForm.CORRIDOR, 0.25),
                                new CityLandUseSurfacePrintPlan.RelayRoleDefinition("role:water",
                                        LandscapeFillProgram.MaterialRole.WATER,
                                        LandscapeFillProgram.GrowthForm.CORRIDOR, 0.25),
                                new CityLandUseSurfacePrintPlan.RelayRoleDefinition("role:field",
                                        LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT,
                                        LandscapeFillProgram.GrowthForm.PATCH, 0.25)),
                        List.of(new CityLandUseSurfacePrintPlan.RelayContentWeight("content:wheat", 1)),
                        List.of(new CityLandUseSurfacePrintPlan.RegionSpan(0, 14, 14, "r1", "role:field"),
                                new CityLandUseSurfacePrintPlan.RegionSpan(0, 15, 15, "r2", "role:bank"),
                                new CityLandUseSurfacePrintPlan.RegionSpan(0, 16, 16, "r3", "role:water"),
                                new CityLandUseSurfacePrintPlan.RegionSpan(0, 17, 17, "r4", "role:field")),
                        List.of(new CityLandUseSurfacePrintPlan.RegionTrace("r1", "", "role:field",
                                        LandscapeFillProgram.GrowthForm.PATCH, source, null, 1, 1),
                                new CityLandUseSurfacePrintPlan.RegionTrace("r2", "r1", "role:bank",
                                        LandscapeFillProgram.GrowthForm.CORRIDOR, new BlockPoint(15, 0),
                                        source, 1, 1),
                                new CityLandUseSurfacePrintPlan.RegionTrace("r3", "r2", "role:water",
                                        LandscapeFillProgram.GrowthForm.CORRIDOR, new BlockPoint(16, 0),
                                        new BlockPoint(15, 0), 1, 1),
                                new CityLandUseSurfacePrintPlan.RegionTrace("r4", "r3", "role:field",
                                        LandscapeFillProgram.GrowthForm.PATCH, new BlockPoint(17, 0),
                                        new BlockPoint(16, 0), 1, 1)));
        CityLandUseSurfacePrintPlan.AreaPrint print = new CityLandUseSurfacePrintPlan.AreaPrint(
                "area/surface", "area", List.of("group"), settings, members, List.of(),
                LandUseSurfaceSettings.SurfaceAlgorithm.RELAY_REGION_GROWTH, source, recipe);
        CityLandUseSurfacePrintPlan surfacePlan = hashed(areaPlan, List.of(print));

        CityLandUseChunkCompiler.ChunkFragment west = compiler.compile(areaPlan, surfacePlan, 0, 0);
        CityLandUseChunkCompiler.ChunkFragment east = compiler.compile(areaPlan, surfacePlan, 1, 0);

        assertOperation(west, 14, 0, "minecraft:farmland", CityLandUseChunkCompiler.SurfaceStage.BASE);
        assertOperation(west, 14, 0, "minecraft:wheat", CityLandUseChunkCompiler.SurfaceStage.CROP);
        assertOperation(west, 15, 0, "minecraft:oak_slab",
                CityLandUseChunkCompiler.SurfaceStage.CHANNEL_OVERLAY);
        assertOperation(east, 16, 0, "minecraft:water", CityLandUseChunkCompiler.SurfaceStage.BASE);
        assertOperation(east, 17, 0, "minecraft:wheat", CityLandUseChunkCompiler.SurfaceStage.CROP);
        assertNoOperation(east, 16, 0, "minecraft:wheat");
    }

    @Test
    void boundaryUsesFrozenRecipeBlockBeforeLegacyPalette() {
        List<LandUseAreaPlan.ScanlineSpan> members = List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 3));
        LandUseAreaPlan.Area area = new LandUseAreaPlan.Area(
                "area", "area", "greenbelt", List.of("group"), List.of("anchor"),
                List.of(new BlockPoint(0, 0)), members, List.of(),
                List.of(new LandUseAreaPlan.BoundaryLoop(List.of(
                        new BlockPoint(0, 0), new BlockPoint(1, 0),
                        new BlockPoint(2, 0), new BlockPoint(3, 0)), false)),
                List.of(), 10, SurfacePolicy.PAVE, VegetationPolicy.CLEAR,
                BoundaryPolicy.FENCE, "greenbelt");
        LandUseAreaPlan areaPlan = new LandUseAreaPlanCodec().withComputedHash(new LandUseAreaPlan(
                LandUseAreaPlan.CURRENT_SCHEMA_VERSION, "land_use_rules.v0.1", "city_frozen_boundary", "",
                new BlockBounds(0, 0, 3, 0), List.of(area), List.of(), List.of(), List.of()));
        LandUseSurfaceSettings settings = LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE)
                .withOverrides(true, true, LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM,
                        "minecraft:grass_block", null, null, null, null,
                        "minecraft:spruce_fence", null, null, null, null, null);
        CityLandUseSurfacePrintPlan surfacePlan = hashed(areaPlan, List.of(
                new CityLandUseSurfacePrintPlan.AreaPrint(
                        "area/surface", area.areaId(), area.sourceGroupIds(), settings,
                        members, List.of(), new CityLandUseSurfacePrintPlan.UniformRecipe(
                        settings.surfaceBlockId(), settings.boundaryBlockId()))));

        CityLandUseChunkCompiler.ChunkFragment fragment = compiler.compile(areaPlan, surfacePlan, 0, 0);

        assertEquals(4, fragment.boundaryOperations().size());
        assertTrue(fragment.boundaryOperations().stream()
                .allMatch(operation -> operation.blockId().equals("minecraft:spruce_fence")));
    }

    static LandUseAreaPlan areaPlan(String cityId,
                                    SurfacePolicy policy,
                                    List<LandUseAreaPlan.ScanlineSpan> members) {
        LandUseAreaPlan.Area area = new LandUseAreaPlan.Area("area", "area", "land_use",
                List.of("group"), List.of("anchor"), List.of(new BlockPoint(members.get(0).minX(), 0)),
                members, List.of(), List.of(), List.of(), 10, policy,
                VegetationPolicy.CLEAR, BoundaryPolicy.OPEN, "area");
        return new LandUseAreaPlanCodec().withComputedHash(new LandUseAreaPlan(
                LandUseAreaPlan.CURRENT_SCHEMA_VERSION, "land_use_rules.v0.1", cityId, "",
                new BlockBounds(0, 0, 31, 15), List.of(area), List.of(), List.of(), List.of()));
    }

    static LandUseAreaPlan plan(String cityId) {
        return areaPlan(cityId, SurfacePolicy.PAVE,
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 17)));
    }

    static CityLandUseSurfacePrintPlan uniformPlan(LandUseAreaPlan areaPlan) {
        LandUseAreaPlan.Area source = areaPlan.areas().get(0);
        LandUseSurfaceSettings settings = new LandUseSurfaceSettings(
                true, true, "minecraft:stone_bricks", "", "PAVE");
        return hashed(areaPlan, List.of(new CityLandUseSurfacePrintPlan.AreaPrint(
                "area/surface", source.areaId(), source.sourceGroupIds(), settings,
                source.memberSpans(), List.of(),
                new CityLandUseSurfacePrintPlan.UniformRecipe(settings.surfaceBlockId()))));
    }

    static CityLandUseSurfacePrintPlan hashed(
            LandUseAreaPlan areaPlan,
            List<CityLandUseSurfacePrintPlan.AreaPrint> areas) {
        return new CityLandUseSurfacePrintPlanCodec().withComputedHash(
                new CityLandUseSurfacePrintPlan(CityLandUseSurfacePrintPlan.CURRENT_SCHEMA_VERSION,
                        areaPlan.cityId(), areaPlan.planHash(), "", areas));
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
}
