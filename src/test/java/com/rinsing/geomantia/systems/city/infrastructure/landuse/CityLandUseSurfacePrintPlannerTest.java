package com.rinsing.geomantia.systems.city.infrastructure.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanner;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseSourceResolver;
import com.rinsing.geomantia.systems.city.application.CityBlueprintReferenceCatalog;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityLandUsePreviewRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseSurfacePrintPlannerTest {
    @Test
    void freezesUniformAndContourRecipesWithoutCatalogDependency() {
        LandUseAreaPlan areaPlan = areaPlan();
        List<LandUseSeedGroup> groups = List.of(
                group("farm_group", SurfacePolicy.CULTIVATE, new BlockBounds(12, 5, 14, 7)),
                group("market_group", SurfacePolicy.PAVE, new BlockBounds(42, 2, 43, 3)));

        CityLandUseSurfacePrintPlan first = new CityLandUseSurfacePrintPlanner().plan(
                areaPlan, groups, terrain(new BlockBounds(0, 0, 63, 31), false));
        CityLandUseSurfacePrintPlan second = new CityLandUseSurfacePrintPlanner().plan(
                areaPlan, groups, terrain(new BlockBounds(0, 0, 63, 31), false));

        assertEquals(first, second);
        assertFalse(first.planHash().isBlank());
        CityLandUseSurfacePrintPlan.AreaPrint farm = first.areas().stream()
                .filter(value -> value.landUseAreaId().equals("farm")).findFirst().orElseThrow();
        CityLandUseSurfacePrintPlan.ContourBandsRecipe contour = assertInstanceOf(
                CityLandUseSurfacePrintPlan.ContourBandsRecipe.class, farm.recipe());
        assertEquals(13, contour.repeatPeriodBlocks());
        assertEquals(5, contour.fieldBeforeBlocks());
        assertEquals(3, contour.channelWidthBlocks());
        assertEquals(5, contour.fieldAfterBlocks());
        assertEquals(CityLandUseSurfacePrintPlan.ClassificationMode.DIRECTIONAL_CURVES,
                contour.classificationMode());
        assertTrue(contour.bandSpans().stream().anyMatch(span ->
                span.role() == CityLandUseSurfacePrintPlan.BandRole.FIELD));
        assertTrue(contour.bandSpans().stream().noneMatch(span ->
                span.z() == 6 && span.minX() <= 14 && span.maxX() >= 12));

        CityLandUseSurfacePrintPlan.AreaPrint market = first.areas().stream()
                .filter(value -> value.landUseAreaId().equals("market")).findFirst().orElseThrow();
        CityLandUseSurfacePrintPlan.UniformRecipe uniform = assertInstanceOf(
                CityLandUseSurfacePrintPlan.UniformRecipe.class, market.recipe());
        assertEquals("minecraft:stone_bricks", uniform.surfaceBlockId());
    }

    @Test
    void hillContourFreezesGlobalBandRolesAndDoesNotRestartAtChunkBoundary() {
        List<LandUseAreaPlan.ScanlineSpan> members = diamondSpans(32, 32, 30);
        LandUseAreaPlan.Area farm = area("hill_farm", "farm_group", SurfacePolicy.CULTIVATE,
                members, new BlockBounds(31, 31, 33, 33));
        LandUseAreaPlan areaPlan = new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,
                "city_land_use_rules", "city_test", "hill-land-use-hash",
                new BlockBounds(0, 0, 64, 64), List.of(farm), List.of(), List.of(), List.of());

        CityLandUseSurfacePrintPlan plan = new CityLandUseSurfacePrintPlanner().plan(areaPlan,
                List.of(group("farm_group", SurfacePolicy.CULTIVATE,
                        new BlockBounds(31, 31, 33, 33))),
                terrain(new BlockBounds(0, 0, 64, 64), true));

        CityLandUseSurfacePrintPlan.AreaPrint print = plan.areas().get(0);
        assertEquals(LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS, print.surfaceAlgorithm());
        assertEquals(new BlockPoint(32, 32), print.algorithmAnchor());
        CityLandUseSurfacePrintPlan.ContourBandsRecipe recipe = assertInstanceOf(
                CityLandUseSurfacePrintPlan.ContourBandsRecipe.class, print.recipe());
        assertEquals(CityLandUseSurfacePrintPlan.ClassificationMode.CONTOUR_NORMAL,
                recipe.classificationMode());
        assertTrue(recipe.bandSpans().stream().anyMatch(span -> span.minX() <= 15 && span.maxX() >= 15));
        assertTrue(recipe.bandSpans().stream().anyMatch(span -> span.minX() <= 16 && span.maxX() >= 16));
        assertEquals(members.stream().mapToInt(span -> span.maxX() - span.minX() + 1).sum()
                        - 9,
                recipe.bandSpans().stream().mapToInt(span -> span.maxX() - span.minX() + 1).sum());
    }

    @Test
    void contourRecipeConsumesConfiguredWidthsAndBoundaryMaterial() {
        LandUseAreaPlan areaPlan = areaPlan();
        LandUseSurfaceSettings settings = LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE)
                .withOverrides(true, true, LandUseSurfaceSettings.SurfaceAlgorithm.CONTOUR_BANDS,
                        null, null, null, null, null, "minecraft:spruce_fence",
                        7, 2, 6, null, null);
        CityLandUseSurfacePrintPlan plan = new CityLandUseSurfacePrintPlanner().plan(areaPlan,
                List.of(group("farm_group", SurfacePolicy.CULTIVATE,
                                new BlockBounds(12, 5, 14, 7), settings),
                        group("market_group", SurfacePolicy.PAVE, new BlockBounds(42, 2, 43, 3))),
                terrain(new BlockBounds(0, 0, 63, 31), false));

        CityLandUseSurfacePrintPlan.ContourBandsRecipe recipe = assertInstanceOf(
                CityLandUseSurfacePrintPlan.ContourBandsRecipe.class,
                plan.areas().stream().filter(value -> value.landUseAreaId().equals("farm"))
                        .findFirst().orElseThrow().recipe());
        assertEquals(15, recipe.repeatPeriodBlocks());
        assertEquals(7, recipe.fieldBeforeBlocks());
        assertEquals(2, recipe.channelWidthBlocks());
        assertEquals(6, recipe.fieldAfterBlocks());
        assertEquals("minecraft:spruce_fence", recipe.boundaryBlockId());
    }

    @Test
    void freezesRelayRegionsFromOrderedSemanticStages() {
        LandUseAreaPlan areaPlan = areaPlan();
        LandUseSurfaceSettings settings = LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE)
                .forRelayRegionGrowth();
        LandscapeFillProgram fill = new LandscapeFillProgram("fill:irrigated_fields", "role:cultivated",
                List.of(
                        new LandscapeFillProgram.RoleDefinition("role:cultivated",
                                LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT,
                                LandscapeFillProgram.GrowthForm.PATCH, 0.325),
                        new LandscapeFillProgram.RoleDefinition("role:bank",
                                LandscapeFillProgram.MaterialRole.BANK,
                                LandscapeFillProgram.GrowthForm.CORRIDOR, 0.10),
                        new LandscapeFillProgram.RoleDefinition("role:water",
                                LandscapeFillProgram.MaterialRole.WATER,
                                LandscapeFillProgram.GrowthForm.CORRIDOR, 0.15),
                        new LandscapeFillProgram.RoleDefinition("role:bank",
                                LandscapeFillProgram.MaterialRole.BANK,
                                LandscapeFillProgram.GrowthForm.CORRIDOR, 0.10),
                        new LandscapeFillProgram.RoleDefinition("role:cultivated",
                                LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT,
                                LandscapeFillProgram.GrowthForm.PATCH, 0.325)),
                List.of(new LandscapeFillProgram.ContentWeight("content:wheat", 3),
                        new LandscapeFillProgram.ContentWeight("content:carrot", 1)), 9123L);
        LandUseSeedGroup farm = landscapeGroup("farm_group", new BlockBounds(12, 5, 14, 7), settings, fill);

        CityLandUseSurfacePrintPlan first = new CityLandUseSurfacePrintPlanner().plan(areaPlan,
                List.of(farm, group("market_group", SurfacePolicy.PAVE, new BlockBounds(42, 2, 43, 3))),
                terrain(new BlockBounds(0, 0, 63, 31), false));
        CityLandUseSurfacePrintPlan second = new CityLandUseSurfacePrintPlanner().plan(areaPlan,
                List.of(farm, group("market_group", SurfacePolicy.PAVE, new BlockBounds(42, 2, 43, 3))),
                terrain(new BlockBounds(0, 0, 63, 31), false));

        assertEquals(first, second);
        CityLandUseSurfacePrintPlan.AreaPrint print = first.areas().stream()
                .filter(area -> area.landUseAreaId().equals("farm")).findFirst().orElseThrow();
        assertEquals(LandUseSurfaceSettings.SurfaceAlgorithm.RELAY_REGION_GROWTH,
                print.surfaceAlgorithm());
        CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe recipe = assertInstanceOf(
                CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe.class, print.recipe());
        assertEquals("fill:irrigated_fields", recipe.fillProfileRef());
        assertEquals(List.of("role:cultivated", "role:bank", "role:water", "role:bank"),
                recipe.regionTraces().stream().limit(4).map(CityLandUseSurfacePrintPlan.RegionTrace::roleRef)
                        .toList());
        assertEquals("role:cultivated", recipe.regionTraces().get(4).roleRef());
        assertEquals(recipe.regionTraces().get(0).regionId(), recipe.regionTraces().get(1).parentRegionId());
        assertNotNull(recipe.regionTraces().get(1).sourceFrontier());
        int expectedCoverage = areaPlan.areas().stream().filter(area -> area.areaId().equals("farm"))
                .findFirst().orElseThrow().memberSpans().stream()
                .mapToInt(span -> span.maxX() - span.minX() + 1).sum() - 9;
        assertEquals(expectedCoverage, recipe.regionSpans().stream()
                .mapToInt(span -> span.maxX() - span.minX() + 1).sum());
        assertTrue(recipe.roleDefinitions().stream().anyMatch(role -> role.targetShare() == 0.325));
        assertEquals(2, recipe.contentWeights().size());
    }

    @Test
    void refusesToDeleteFunctionalRolesWhenBranchedMaskCannotBePartitioned() {
        String groupId = "branched_woodland";
        List<LandUseAreaPlan.ScanlineSpan> members = List.of(
                new LandUseAreaPlan.ScanlineSpan(0, 2, 2),
                new LandUseAreaPlan.ScanlineSpan(1, 2, 2),
                new LandUseAreaPlan.ScanlineSpan(2, 2, 2),
                new LandUseAreaPlan.ScanlineSpan(3, 2, 2),
                new LandUseAreaPlan.ScanlineSpan(4, 0, 4));
        LandUseAreaPlan.Area area = new LandUseAreaPlan.Area("woodland", "woodland", "woodland",
                List.of(groupId), List.of(groupId), List.of(new BlockPoint(2, 0)), members,
                List.of(), List.of(), List.of(), 1, SurfacePolicy.CULTIVATE,
                VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN);
        LandUseAreaPlan plan = new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,
                "city_land_use_rules", "city_test", "land-use-hash", new BlockBounds(0, 0, 7, 7),
                List.of(area), List.of(), List.of(), List.of());
        LandscapeFillProgram fill = new LandscapeFillProgram("fill:branched_woodland", "TREE_GROVE",
                List.of(
                        new LandscapeFillProgram.RoleDefinition("TREE_GROVE",
                                LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT,
                                LandscapeFillProgram.GrowthForm.PATCH, 0.4),
                        new LandscapeFillProgram.RoleDefinition("SHRUB_BREAK",
                                LandscapeFillProgram.MaterialRole.GROUND,
                                LandscapeFillProgram.GrowthForm.PATCH, 0.2),
                        new LandscapeFillProgram.RoleDefinition("GRAVEL_PATH",
                                LandscapeFillProgram.MaterialRole.GROUND,
                                LandscapeFillProgram.GrowthForm.CORRIDOR, 0.4)),
                List.of(), 17L);
        LandUseSeedGroup landscape = landscapeGroup(groupId, new BlockBounds(3, 0, 3, 0),
                LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE).forRelayRegionGrowth(), fill);

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new CityLandUseSurfacePrintPlanner().plan(plan,
                        List.of(landscape), terrain(new BlockBounds(0, 0, 7, 7), false)));
        assertTrue(failure.getMessage().startsWith("RELAY_GROWTH_CANDIDATE_RETRIES_EXHAUSTED:"));
    }

    @Test
    void freezesRoadCrossSectionGreenParcelAndOverflowBoundaryCells() throws Exception {
        LandUseAreaPlan areaPlan = areaPlan();
        LandUseTerrainField featureTerrain = terrain(new BlockBounds(0, 0, 63, 31), false);
        List<LandUseSeedGroup> groups = List.of(
                group("farm_group", SurfacePolicy.CULTIVATE, new BlockBounds(12, 5, 14, 7)),
                group("market_group", SurfacePolicy.PAVE, new BlockBounds(42, 2, 43, 3)));
        LandUseSourceResolver.RoadBand road = new LandUseSourceResolver.RoadBand(
                "road::1", "network", "CITY_MAIN_ROAD", new BlockPoint(20, 18),
                new BlockPoint(30, 18), new BlockBounds(20, 15, 30, 21), 7,
                "STAIR_SLAB_STAIR");
        LandUseSourceResolver.GreenParcelSpec green = new LandUseSourceResolver.GreenParcelSpec(
                "market::green", "market", new BlockBounds(39, 0, 47, 8),
                new BlockBounds(42, 2, 43, 3), new BlockPoint(41, 2),
                CityBlueprintReferenceCatalog.GreenParcelPattern.FIELD_GRID,
                CityBlueprintReferenceCatalog.GreenParcelDensity.MEDIUM,
                "minecraft:grass_block", "minecraft:gravel",
                List.of(new CityBlueprintReferenceCatalog.PlantPaletteEntry("minecraft:oak_leaves", 1),
                        new CityBlueprintReferenceCatalog.PlantPaletteEntry("minecraft:poppy", 1)), 42L);
        LandUseSourceResolver.OverflowZoneSpec overflow = new LandUseSourceResolver.OverflowZoneSpec(
                "overflow", new BlockBounds(0, 0, 10, 8), List.of(new BlockBounds(4, 0, 6, 2)),
                "minecraft:stone_brick_wall");

        CityLandUseSurfacePrintPlan first = new CityLandUseSurfacePrintPlanner().plan(areaPlan, groups,
                featureTerrain, List.of(road), List.of(green),
                List.of(overflow));
        CityLandUseSurfacePrintPlan second = new CityLandUseSurfacePrintPlanner().plan(areaPlan, groups,
                featureTerrain, List.of(road), List.of(green),
                List.of(overflow));

        assertEquals(first, second);
        assertTrue(first.featureCells().stream().anyMatch(cell ->
                cell.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB));
        assertTrue(first.featureCells().stream().anyMatch(cell ->
                cell.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR
                        && cell.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH));
        assertTrue(first.featureCells().stream().anyMatch(cell ->
                cell.kind() == CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PATH));
        assertTrue(first.featureCells().stream().filter(cell ->
                        cell.kind() == CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PLANT)
                .allMatch(cell -> Set.of("minecraft:oak_leaves", "minecraft:poppy").contains(cell.blockId())));
        assertTrue(first.featureCells().stream().noneMatch(cell ->
                cell.x() >= 42 && cell.x() <= 43 && cell.z() >= 2 && cell.z() <= 3));
        assertTrue(first.featureCells().stream().anyMatch(cell ->
                cell.kind() == CityLandUseSurfacePrintPlan.FeatureKind.OVERFLOW_BOUNDARY));
        assertTrue(first.featureCells().stream().noneMatch(cell ->
                cell.kind() == CityLandUseSurfacePrintPlan.FeatureKind.OVERFLOW_BOUNDARY
                        && cell.x() >= 4 && cell.x() <= 6 && cell.z() <= 2));
        Path output = Path.of("build", "city-road-greenery-preview");
        var metadata = new CityLandUsePreviewRenderer().render(featureTerrain, areaPlan, first, output);
        assertTrue(Files.size(output.resolve(metadata.get("fileName").getAsString())) > 0);
    }

    @Test
    void freezesOneWholeMainRoadGradeAcrossManyOwnerChunks() {
        var baseTerrain = terrain(new BlockBounds(0, 0, 159, 63), false);
        var gradedTerrain = new LandUseTerrainField(baseTerrain.schema(), baseTerrain.cityId(),
                baseTerrain.planningBounds(), baseTerrain.cellStepBlocks(), baseTerrain.cells().stream()
                .map(cell -> new LandUseTerrainField.Cell(cell.cellX(), cell.cellZ(), cell.blockMinX(),
                        cell.blockMinZ(), cell.cellStepBlocks(), cell.blockMinX() < 72 ? 80 : 56,
                        0, 0, 0, false, 0, cell.waterDistance(), cell.biomeId(), "plain",
                        cell.landformPatchId(), true)).toList());
        var road = new LandUseSourceResolver.RoadBand("long-main", "network", "CITY_MAIN_ROAD",
                new BlockPoint(0, 48), new BlockPoint(144, 48), new BlockBounds(0, 45, 144, 51),
                7, "STAIR_SLAB_STAIR");
        var plan = new CityLandUseSurfacePrintPlanner().plan(areaPlan(), List.of(
                group("farm_group", SurfacePolicy.CULTIVATE, new BlockBounds(12, 5, 14, 7)),
                group("market_group", SurfacePolicy.PAVE, new BlockBounds(42, 2, 43, 3))),
                gradedTerrain, List.of(road), List.of(), List.of());
        var center = plan.featureCells().stream().filter(cell -> cell.z() == 48)
                .sorted(java.util.Comparator.comparingInt(CityLandUseSurfacePrintPlan.FeatureCell::x)).toList();
        assertEquals(145, center.size());
        assertEquals(80, center.get(0).targetSurfaceY());
        assertEquals(56, center.get(144).targetSurfaceY());
        assertTrue(center.get(71).targetSurfaceY() < 80);
        for (int i=1;i<center.size();i++)
            assertTrue(Math.abs(center.get(i).targetSurfaceY()-center.get(i-1).targetSurfaceY()) <= 1);
        assertTrue(center.stream().anyMatch(cell -> cell.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR));
    }

    @Test
    void bridgeUsesIndependentDeckAndRailMaterials() {
        LandUseSourceResolver.RoadBand bridge = new LandUseSourceResolver.RoadBand(
                "bridge::1", "network", "CITY_BRIDGE", new BlockPoint(20, 18),
                new BlockPoint(30, 18), new BlockBounds(20, 15, 30, 21), 7,
                "BRIDGE_DECK_RAIL", "minecraft:spruce_slab", "",
                "minecraft:spruce_fence");

        CityLandUseSurfacePrintPlan result = new CityLandUseSurfacePrintPlanner().plan(areaPlan(),
                List.of(group("farm_group", SurfacePolicy.CULTIVATE, new BlockBounds(12, 5, 14, 7)),
                        group("market_group", SurfacePolicy.PAVE, new BlockBounds(42, 2, 43, 3))),
                terrain(new BlockBounds(0, 0, 63, 31), false), List.of(bridge), List.of(), List.of());

        assertTrue(result.featureCells().stream().anyMatch(cell ->
                cell.kind() == CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_DECK
                        && "minecraft:spruce_slab".equals(cell.blockId())));
        assertTrue(result.featureCells().stream().anyMatch(cell ->
                cell.kind() == CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_RAIL
                        && cell.surfaceOffset() == 1
                        && "minecraft:spruce_fence".equals(cell.blockId())));
        assertTrue(result.featureCells().stream().noneMatch(cell ->
                cell.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR));
    }

    @Test
    void formalLandscapeRejectsAnAreaSeedOutsideItsFinalMask() {
        LandUseSurfaceSettings settings = LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE)
                .forRelayRegionGrowth();
        LandscapeFillProgram fill = new LandscapeFillProgram("fill:green", "GREEN", List.of(
                new LandscapeFillProgram.RoleDefinition("GREEN",
                        LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT,
                        LandscapeFillProgram.GrowthForm.PATCH, 0.5),
                new LandscapeFillProgram.RoleDefinition("GROUND",
                        LandscapeFillProgram.MaterialRole.GROUND,
                        LandscapeFillProgram.GrowthForm.PATCH, 0.5)), List.of(), 91L);
        String groupId = "green::instance_01::parcel_02";
        LandUseSeedGroup landscape = landscapeGroup(groupId, new BlockBounds(20, 20, 22, 22), settings, fill);
        LandUseAreaPlan.Area area = new LandUseAreaPlan.Area("green", "green", "green", List.of(groupId),
                List.of(), List.of(new BlockPoint(19, 20)), spans(0, 8, 0, 8), List.of(), List.of(), List.of(),
                1, SurfacePolicy.CULTIVATE, VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN);
        LandUseAreaPlan plan = new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,
                "city_land_use_rules", "city_test", "land-use-hash", new BlockBounds(0, 0, 31, 31),
                List.of(area), List.of(), List.of(), List.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                new CityLandUseSurfacePrintPlanner().plan(plan, List.of(landscape),
                        terrain(new BlockBounds(0, 0, 31, 31), false)));

        assertEquals("CITY_LAND_USE_SURFACE_PRINT_LANDSCAPE_SEED_NOT_IN_AREA:green:"
                + groupId + ":19:20", failure.getMessage());
    }

    private static LandUseAreaPlan areaPlan() {
        LandUseAreaPlan.Area farm = area("farm", "farm_group", SurfacePolicy.CULTIVATE,
                spans(0, 8, 0, 30), new BlockBounds(12, 5, 14, 7));
        LandUseAreaPlan.Area market = area("market", "market_group", SurfacePolicy.PAVE,
                spans(0, 8, 40, 50), new BlockBounds(42, 2, 43, 3));
        return new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,
                "city_land_use_rules", "city_test", "land-use-hash",
                new BlockBounds(0, 0, 63, 31), List.of(farm, market), List.of(), List.of(), List.of());
    }

    private static LandUseAreaPlan.Area area(String areaId,
                                             String groupId,
                                             SurfacePolicy policy,
                                             List<LandUseAreaPlan.ScanlineSpan> spans,
                                             BlockBounds footprint) {
        return new LandUseAreaPlan.Area(areaId, areaId, areaId, List.of(groupId), List.of(groupId),
                List.of(new BlockPoint(footprint.minX() - 1, footprint.minZ())), spans, List.of(footprint),
                List.of(), List.of(), 1, policy, VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN);
    }

    private static LandUseSeedGroup group(String id, SurfacePolicy policy, BlockBounds footprint) {
        return group(id, policy, footprint, LandUseSurfaceSettings.defaults(policy));
    }

    private static LandUseSeedGroup group(String id,
                                          SurfacePolicy policy,
                                          BlockBounds footprint,
                                          LandUseSurfaceSettings settings) {
        LandUseRule rule = new LandUseRule(id, id, List.of(id), 1, 0, 1, 200,
                200, 1, 0, 0, 10, 0, 1, true, policy,
                VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN);
        return new LandUseSeedGroup(id, rule, settings, List.of(id),
                List.of(footprint), List.of(new BlockPoint(footprint.minX(), footprint.minZ())),
                List.of(), 1, 100, 200, 200, 1);
    }

    private static LandUseSeedGroup landscapeGroup(String id,
                                                   BlockBounds footprint,
                                                   LandUseSurfaceSettings settings,
                                                   LandscapeFillProgram fill) {
        LandUseRule rule = new LandUseRule(id, id, List.of(id), 1, 0, 1, 200,
                200, 1, 0, 0, 10, 0, 1, false, SurfacePolicy.CULTIVATE,
                VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN);
        return new LandUseSeedGroup(id, rule, settings, List.of(id), List.of(footprint),
                List.of(new BlockPoint(footprint.minX() - 1, footprint.minZ())), List.of(),
                1, 100, 200, 200, 1, List.of(), LandUseSeedGroup.GrowthBias.neutral(),
                LandUseSeedGroup.TerrainBias.BALANCED, List.of(),
                LandUseSeedGroup.LayerRole.LANDSCAPE, null, fill);
    }

    private static List<LandUseAreaPlan.ScanlineSpan> spans(int minZ, int maxZ, int minX, int maxX) {
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) spans.add(new LandUseAreaPlan.ScanlineSpan(z, minX, maxX));
        return List.copyOf(spans);
    }

    private static List<LandUseAreaPlan.ScanlineSpan> diamondSpans(int centerX, int centerZ, int radius) {
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            int halfWidth = radius - Math.abs(z - centerZ);
            spans.add(new LandUseAreaPlan.ScanlineSpan(z, centerX - halfWidth, centerX + halfWidth));
        }
        return List.copyOf(spans);
    }

    private static LandUseTerrainField terrain(BlockBounds bounds, boolean hill) {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        int maxCellX = Math.floorDiv(bounds.maxX(), 4);
        int maxCellZ = Math.floorDiv(bounds.maxZ(), 4);
        for (int z = 0; z <= maxCellZ; z++) {
            for (int x = 0; x <= maxCellX; x++) {
                double dx = x * 4 + 1.5 - 32;
                double dz = z * 4 + 1.5 - 32;
                double elevation = hill ? 96 - Math.hypot(dx, dz) * 0.75 : 70;
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        elevation, 0, 0, 0, false, 0, 40,
                        "minecraft:plains", "plain", "p", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA,
                "city_test", bounds, 4, cells);
    }
}
