package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfaceRunCompiler;
import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.TestDecorationCatalogs;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityNbtPrefabBatchPlacer;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseSurfacePrefabOwnerCompilerTest {
    private final CityLandUseSurfacePrefabOwnerCompiler compiler =
            new CityLandUseSurfacePrefabOwnerCompiler();

    @Test
    void keepsOneCompletePrefabIdentityAcrossBothIntersectingChunkOwners(@TempDir Path temp) throws Exception {
        CityDecorationContentCatalog catalog = catalog(temp);
        CityLandUseSurfacePrintPlan.SurfacePlacement place = placement(
                catalog, "cross_chunk", 0, new BlockPoint(15, 8), 0,
                CityContinuousTerrainRunPlanner.Decision.PLACE, null);
        CityLandUseSurfacePrintPlan.SurfacePlacement terminate = placement(
                catalog, "terminate", 1, new BlockPoint(17, 8), 0,
                CityContinuousTerrainRunPlanner.Decision.TERMINATE, null);
        CityLandUseSurfacePrintPlan plan = plan(catalog, List.of(place, terminate), null, null);

        CityNbtPrefabBatchPlacer.BatchRequest left = compiler.compile(plan, catalog, 0, 0, 0, 255);
        CityNbtPrefabBatchPlacer.BatchRequest right = compiler.compile(plan, catalog, 1, 0, 0, 255);
        CityNbtPrefabBatchPlacer.BatchRequest distant = compiler.compile(plan, catalog, 2, 0, 0, 255);

        assertEquals(1, left.placements().size());
        assertEquals(1, right.placements().size());
        assertTrue(distant.placements().isEmpty());
        CityNbtPrefabBatchPlacer.PrefabPlacement leftPlacement = left.placements().get(0);
        CityNbtPrefabBatchPlacer.PrefabPlacement rightPlacement = right.placements().get(0);
        assertEquals(leftPlacement, rightPlacement);
        assertEquals(new BlockPos(15, 64, 8), leftPlacement.anchor());
        assertTrue(leftPlacement.ignoreTemplateAir());
        assertEquals("surface_replaceable", leftPlacement.replacePolicy());
        assertEquals(0, leftPlacement.groundPlaneLocalY());
        assertEquals(CityNbtPrefabBatchPlacer.ContentRejectionPolicy.FAIL_BATCH,
                left.contentRejectionPolicy());
        assertEquals(List.of(
                        new CityNbtPrefabBatchPlacer.SurfaceFallbackCell(15, 8),
                        new CityNbtPrefabBatchPlacer.SurfaceFallbackCell(16, 8),
                        new CityNbtPrefabBatchPlacer.SurfaceFallbackCell(17, 8)),
                leftPlacement.surfaceFallback().cells());
        assertEquals(0, left.ownerBounds().minX());
        assertEquals(15, left.ownerBounds().maxX());
        assertEquals(16, right.ownerBounds().minX());
        assertEquals(31, right.ownerBounds().maxX());
    }

    @Test
    void preparedIndexDoesNotResolveOrCopyPlacementsForUnrelatedOwner(@TempDir Path temp) throws Exception {
        CityDecorationContentCatalog catalog = catalog(temp);
        CityLandUseSurfacePrintPlan.SurfacePlacement placement = placement(
                catalog, "local", 0, new BlockPoint(4, 4), 0,
                CityContinuousTerrainRunPlanner.Decision.PLACE, null);
        CityLandUseSurfacePrefabOwnerCompiler.PreparedPlan prepared = compiler.prepare(
                plan(catalog, List.of(placement), null, null), catalog);
        AtomicInteger resolverCalls = new AtomicInteger();

        CityNbtPrefabBatchPlacer.BatchRequest distant = compiler.compilePrepared(
                prepared, 20, 20, 0, 255, request -> {
                    resolverCalls.incrementAndGet();
                    return request.coarseTargetY();
                });

        assertEquals(1, prepared.indexedPlacementCount(0, 0));
        assertEquals(0, prepared.indexedPlacementCount(20, 20));
        assertEquals(0, resolverCalls.get(), "unrelated owner must not touch placement/template state");
        assertTrue(distant.placements().isEmpty());
    }

    @Test
    void keepsOutOfWorldPlacementAsExplicitFallback(@TempDir Path temp) throws Exception {
        CityDecorationContentCatalog catalog = catalog(temp);
        CityLandUseSurfacePrintPlan.SurfacePlacement placement = placement(
                catalog, "out_of_world", 0, new BlockPoint(4, 4), 0,
                CityContinuousTerrainRunPlanner.Decision.PLACE, null);

        CityNbtPrefabBatchPlacer.BatchRequest batch = compiler.compile(
                plan(catalog, List.of(placement), null, null), catalog, 0, 0, 0, 63);

        assertEquals(1, batch.placements().size());
        assertEquals("CITY_NBT_PREFAB_TARGET_Y_OUT_OF_WORLD",
                batch.placements().get(0).forcedFallbackReason());
        assertFalse(batch.placements().get(0).surfaceFallback().cells().isEmpty());
    }

    @Test
    void compilesEndCapFromAppliedIdentityAndUsesCatalogPolicy(@TempDir Path temp)
            throws Exception {
        CityDecorationContentCatalog catalog = catalog(temp);
        CityLandUseSurfacePrintPlan.SurfacePlacement endCap = placement(
                catalog, "endcap", 0, new BlockPoint(4, 10), 270,
                CityContinuousTerrainRunPlanner.Decision.END_CAP, null);

        CityNbtPrefabBatchPlacer.BatchRequest batch = compiler.compile(
                plan(catalog, List.of(endCap), null, null), catalog, 0, 0, -64, 319);

        CityNbtPrefabBatchPlacer.PrefabPlacement compiled = batch.placements().get(0);
        CityDecorationContentCatalog.Content expected = catalog.requireContent(
                CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF);
        assertEquals(expected.contentId(), compiled.contentRef());
        assertEquals(expected.contentHash(), compiled.contentHash());
        assertEquals(expected.template(), compiled.templateNbt());
        assertEquals(new BlockPos(4, 64, 10), compiled.anchor());
        assertEquals(270, compiled.rotationDegrees());
        assertTrue(compiled.ignoreTemplateAir());
        assertEquals(expected.replacePolicy(), compiled.replacePolicy());
        assertEquals(expected.groundPlaneLocalY(), compiled.groundPlaneLocalY());
    }

    @Test
    void preservesEveryCatalogAllowedRotation(@TempDir Path temp) throws Exception {
        CityDecorationContentCatalog catalog = catalog(temp);
        List<CityLandUseSurfacePrintPlan.SurfacePlacement> placements = new ArrayList<>();
        List<Integer> rotations = List.of(0, 90, 180, 270);
        for (int index = 0; index < rotations.size(); index++) {
            placements.add(placement(catalog, "rotation_" + rotations.get(index), index,
                    new BlockPoint(6, 6), rotations.get(index),
                    CityContinuousTerrainRunPlanner.Decision.PLACE, null));
        }

        CityNbtPrefabBatchPlacer.BatchRequest batch = compiler.compile(
                plan(catalog, placements, null, null), catalog, 0, 0, 0, 255);

        assertEquals(rotations, batch.placements().stream()
                .map(CityNbtPrefabBatchPlacer.PrefabPlacement::rotationDegrees).toList());
    }

    @Test
    void rejectsCatalogAndAppliedContentHashDrift(@TempDir Path temp) throws Exception {
        CityDecorationContentCatalog catalog = catalog(temp);
        CityLandUseSurfacePrintPlan.SurfacePlacement valid = placement(
                catalog, "valid", 0, new BlockPoint(4, 4), 0,
                CityContinuousTerrainRunPlanner.Decision.PLACE, null);
        CityLandUseSurfacePrintPlan catalogDrift = plan(catalog, List.of(valid), null, "sha256:stale-catalog");

        IllegalArgumentException catalogFailure = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(catalogDrift, catalog, 0, 0, 0, 255));
        assertTrue(catalogFailure.getMessage().contains(
                "CITY_LAND_USE_SURFACE_PREFAB_CATALOG_HASH_MISMATCH"));

        CityLandUseSurfacePrintPlan.SurfacePlacement stale = placement(
                catalog, "stale", 0, new BlockPoint(4, 4), 0,
                CityContinuousTerrainRunPlanner.Decision.PLACE, "sha256:stale-content");
        IllegalArgumentException contentFailure = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(plan(catalog, List.of(stale), null, null),
                        catalog, 0, 0, 0, 255));
        assertTrue(contentFailure.getMessage().contains(
                "CITY_LAND_USE_SURFACE_PREFAB_APPLIED_HASH_MISMATCH"));
    }

    @Test
    void rejectsRecipeSizeDriftFromCatalog(@TempDir Path temp) throws Exception {
        CityDecorationContentCatalog catalog = catalog(temp);
        CityDecorationContentCatalog.Content straight = catalog.requireContent(
                CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF);
        CityLandUseSurfaceRunCompiler.PrefabSpec staleSize = new CityLandUseSurfaceRunCompiler.PrefabSpec(
                straight.contentId(), straight.contentHash(), straight.size().widthBlocks(),
                straight.size().heightBlocks() + 1, straight.size().depthBlocks());
        CityLandUseSurfacePrintPlan.SurfacePlacement valid = placement(
                catalog, "valid", 0, new BlockPoint(4, 4), 0,
                CityContinuousTerrainRunPlanner.Decision.PLACE, null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(plan(catalog, List.of(valid), staleSize, null),
                        catalog, 0, 0, 0, 255));

        assertTrue(failure.getMessage().contains("CITY_LAND_USE_SURFACE_PREFAB_CONTENT_SIZE_MISMATCH"));
    }

    private static CityDecorationContentCatalog catalog(Path temp) throws Exception {
        return TestDecorationCatalogs.loadManagedDefault(temp.resolve("city_decoration"));
    }

    private static CityLandUseSurfacePrintPlan plan(
            CityDecorationContentCatalog catalog,
            List<CityLandUseSurfacePrintPlan.SurfacePlacement> placements,
            CityLandUseSurfaceRunCompiler.PrefabSpec straightOverride,
            String catalogHashOverride) {
        CityDecorationContentCatalog.Content straightContent = catalog.requireContent(
                CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF);
        CityDecorationContentCatalog.Content endCapContent = catalog.requireContent(
                CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF);
        CityLandUseSurfaceRunCompiler.PrefabSpec straight = straightOverride == null
                ? spec(straightContent) : straightOverride;
        CityLandUseSurfaceRunCompiler.PrefabSpec endCap = spec(endCapContent);
        String runId = "farm/surface/run";
        CityLandUseSurfacePrintPlan.SurfaceRun run = new CityLandUseSurfacePrintPlan.SurfaceRun(
                runId, CityLandUseSurfaceRunCompiler.WorldAxis.Z, 5, placements,
                null, "", List.of());
        CityLandUseSurfacePrintPlan.CultivateLinedRecipe recipe =
                new CityLandUseSurfacePrintPlan.CultivateLinedRecipe(
                        "minecraft:farmland", "minecraft:wheat", 13, 5, 3, 5, 5,
                        straight, endCap,
                        new CityLandUseSurfaceRunCompiler.TerrainPolicy(1, false, 2, 8,
                                CityContinuousTerrainRunPlanner.FoundationMode.NONE, 0, 0),
                        List.of(run), List.of());
        CityLandUseSurfacePrintPlan.AreaPrint area = new CityLandUseSurfacePrintPlan.AreaPrint(
                "farm/surface/0_0", "farm", List.of("farm_group"),
                LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE),
                memberSpans(), List.of(),
                BlockPoint.ORIGIN, CityLandUseSurfaceRunCompiler.WorldAxis.Z, recipe);
        return new CityLandUseSurfacePrintPlan(CityLandUseSurfacePrintPlan.CURRENT_SCHEMA_VERSION,
                "city_test", "sha256:land-use", catalogHashOverride == null
                ? catalog.catalogHash() : catalogHashOverride, "sha256:surface-print", List.of(area));
    }

    private static CityLandUseSurfacePrintPlan.SurfacePlacement placement(
            CityDecorationContentCatalog catalog,
            String id,
            int ordinal,
            BlockPoint anchor,
            int rotation,
            CityContinuousTerrainRunPlanner.Decision decision,
            String appliedHashOverride) {
        CityDecorationContentCatalog.Content straight = catalog.requireContent(
                CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF);
        CityDecorationContentCatalog.Content applied = decision == CityContinuousTerrainRunPlanner.Decision.END_CAP
                ? catalog.requireContent(CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF) : straight;
        int width = rotation == 0 || rotation == 180
                ? applied.size().widthBlocks() : applied.size().depthBlocks();
        int depth = rotation == 0 || rotation == 180
                ? applied.size().depthBlocks() : applied.size().widthBlocks();
        BlockBounds footprint = new BlockBounds(anchor.x(), anchor.z(),
                anchor.x() + width - 1, anchor.z() + depth - 1);
        return new CityLandUseSurfacePrintPlan.SurfacePlacement(
                id, "farm/surface/run", ordinal, anchor, anchor, rotation, footprint,
                63, 64, false, CityContinuousTerrainRunPlanner.TerrainClass.SAFE, decision,
                straight.contentId(), straight.contentHash(), applied.contentId(),
                appliedHashOverride == null ? applied.contentHash() : appliedHashOverride,
                "CITY_LAND_USE_SURFACE_TEST");
    }

    private static CityLandUseSurfaceRunCompiler.PrefabSpec spec(
            CityDecorationContentCatalog.Content content) {
        return new CityLandUseSurfaceRunCompiler.PrefabSpec(content.contentId(), content.contentHash(),
                content.size().widthBlocks(), content.size().heightBlocks(), content.size().depthBlocks());
    }

    private static List<LandUseAreaPlan.ScanlineSpan> memberSpans() {
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        for (int z = 0; z <= 15; z++) {
            spans.add(new LandUseAreaPlan.ScanlineSpan(z, 0, 63));
        }
        return List.copyOf(spans);
    }
}
