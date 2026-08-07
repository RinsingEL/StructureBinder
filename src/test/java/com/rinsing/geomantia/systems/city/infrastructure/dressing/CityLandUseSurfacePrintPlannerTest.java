package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        assertEquals(CityLandUseSurfacePrintPlan.ClassificationMode.RADIAL_FALLBACK,
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
        LandUseAreaPlan areaPlan = new LandUseAreaPlan(LandUseAreaPlan.CURRENT_SCHEMA_VERSION,
                "city_land_use_rules.v0.1", "city_test", "hill-land-use-hash",
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

    private static LandUseAreaPlan areaPlan() {
        LandUseAreaPlan.Area farm = area("farm", "farm_group", SurfacePolicy.CULTIVATE,
                spans(0, 8, 0, 30), new BlockBounds(12, 5, 14, 7));
        LandUseAreaPlan.Area market = area("market", "market_group", SurfacePolicy.PAVE,
                spans(0, 8, 40, 50), new BlockBounds(42, 2, 43, 3));
        return new LandUseAreaPlan(LandUseAreaPlan.CURRENT_SCHEMA_VERSION,
                "city_land_use_rules.v0.1", "city_test", "land-use-hash",
                new BlockBounds(0, 0, 63, 31), List.of(farm, market), List.of(), List.of(), List.of());
    }

    private static LandUseAreaPlan.Area area(String areaId,
                                             String groupId,
                                             SurfacePolicy policy,
                                             List<LandUseAreaPlan.ScanlineSpan> spans,
                                             BlockBounds footprint) {
        return new LandUseAreaPlan.Area(areaId, areaId, areaId, List.of(groupId), List.of(groupId),
                List.of(new BlockPoint(footprint.minX(), footprint.minZ())), spans, List.of(footprint),
                List.of(), List.of(), 1, policy, VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN, areaId);
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
                VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN, id);
        return new LandUseSeedGroup(id, rule, settings, List.of(id),
                List.of(footprint), List.of(new BlockPoint(footprint.minX(), footprint.minZ())),
                List.of(), 1, 100, 200, 200, 1);
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
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION,
                "city_test", bounds, 4, cells);
    }
}
