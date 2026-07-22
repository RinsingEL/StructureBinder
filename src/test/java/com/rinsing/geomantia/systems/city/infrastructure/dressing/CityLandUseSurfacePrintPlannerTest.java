package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanner;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfaceRunCompiler;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseSurfacePrintPlannerTest {
    @Test
    void freezesUniformAndGlobalFiveThreeFiveCultivateRecipes(@TempDir Path temp) throws Exception {
        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader(state -> {
        }).load(CityDecorationDefaultCatalogBootstrap.ensureInstalled(temp.resolve("city_decoration")));
        LandUseAreaPlan areaPlan = areaPlan();
        List<LandUseSeedGroup> groups = List.of(
                group("farm_group", SurfacePolicy.CULTIVATE, new BlockBounds(12, 5, 14, 7)),
                group("market_group", SurfacePolicy.PAVE, new BlockBounds(42, 2, 43, 3)));

        CityLandUseSurfacePrintPlan first = new CityLandUseSurfacePrintPlanner().plan(
                areaPlan, groups, terrain(), catalog);
        CityLandUseSurfacePrintPlan second = new CityLandUseSurfacePrintPlanner().plan(
                areaPlan, groups, terrain(), catalog);

        assertEquals(first, second);
        assertFalse(first.planHash().isBlank());
        assertEquals(areaPlan.planHash(), first.sourceLandUsePlanHash());
        assertEquals(catalog.catalogHash(), first.catalogHash());
        assertEquals(2, first.areas().size());

        CityLandUseSurfacePrintPlan.AreaPrint farm = first.areas().stream()
                .filter(value -> value.landUseAreaId().equals("farm")).findFirst().orElseThrow();
        assertEquals(new BlockPoint(0, 0), farm.origin());
        assertEquals(CityLandUseSurfaceRunCompiler.WorldAxis.X, farm.continuationAxis());
        assertTrue(farm.exclusionSpans().stream().anyMatch(span ->
                span.z() == 6 && span.minX() == 12 && span.maxX() == 14));
        CityLandUseSurfacePrintPlan.CultivateLinedRecipe cultivate = assertInstanceOf(
                CityLandUseSurfacePrintPlan.CultivateLinedRecipe.class, farm.recipe());
        assertEquals(13, cultivate.repeatPeriodBlocks());
        assertEquals(5, cultivate.fieldBeforeBlocks());
        assertEquals(3, cultivate.channelWidthBlocks());
        assertEquals(5, cultivate.fieldAfterBlocks());
        assertEquals("minecraft:farmland", cultivate.surfaceBlockId());
        assertEquals("minecraft:wheat", cultivate.cropBlockId());
        assertEquals(catalog.requireContent(CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF).contentHash(),
                cultivate.straightPrefab().contentHash());
        assertEquals(catalog.requireContent(CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF).contentHash(),
                cultivate.endCapPrefab().contentHash());
        assertTrue(cultivate.runs().size() >= 2, "structure exclusion should split the global channel run");
        assertTrue(cultivate.runs().stream().flatMap(run -> run.placements().stream())
                .allMatch(placement -> placement.rotationDegrees() == 270));
        assertFalse(cultivate.foundationSegments().isEmpty());
        assertTrue(cultivate.foundationSegments().stream().allMatch(segment ->
                segment.maxDepthBlocks() == 2 && segment.shoulderBlocks() == 1));

        CityLandUseSurfacePrintPlan.AreaPrint market = first.areas().stream()
                .filter(value -> value.landUseAreaId().equals("market")).findFirst().orElseThrow();
        CityLandUseSurfacePrintPlan.UniformRecipe uniform = assertInstanceOf(
                CityLandUseSurfacePrintPlan.UniformRecipe.class, market.recipe());
        assertEquals("minecraft:stone_bricks", uniform.surfaceBlockId());
    }

    @Test
    void radialCultivateFreezesFourOutwardSectorsAcrossChunksWithoutFillingOutsideMask(
            @TempDir Path temp) throws Exception {
        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader(state -> {
        }).load(CityDecorationDefaultCatalogBootstrap.ensureInstalled(temp.resolve("city_decoration")));
        List<LandUseAreaPlan.ScanlineSpan> memberSpans = diamondSpans(32, 32, 32);
        LandUseAreaPlan.Area farm = area("radial_farm", "farm_group", SurfacePolicy.CULTIVATE,
                memberSpans, new BlockBounds(31, 31, 33, 33));
        LandUseAreaPlan areaPlan = new LandUseAreaPlan(LandUseAreaPlan.CURRENT_SCHEMA_VERSION,
                "city_land_use_rules.v0.1", "city_test", "radial-land-use-hash",
                new BlockBounds(0, 0, 64, 64), List.of(farm), List.of(), List.of(), List.of());
        LandUseSurfaceSettings radial = LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE)
                .withOverrides(null, null, null, null,
                        LandUseSurfaceSettings.DirectionMode.RADIAL, null);
        LandUseSeedGroup group = group("farm_group", SurfacePolicy.CULTIVATE,
                new BlockBounds(31, 31, 33, 33), radial);

        CityLandUseSurfacePrintPlan first = new CityLandUseSurfacePrintPlanner().plan(
                areaPlan, List.of(group), terrain(new BlockBounds(0, 0, 64, 64)), catalog);
        CityLandUseSurfacePrintPlan second = new CityLandUseSurfacePrintPlanner().plan(
                areaPlan, List.of(group), terrain(new BlockBounds(0, 0, 64, 64)), catalog);

        assertEquals(first, second);
        CityLandUseSurfacePrintPlan.AreaPrint print = first.areas().get(0);
        assertEquals(LandUseSurfaceSettings.DirectionMode.RADIAL, print.directionMode());
        assertEquals(new BlockPoint(32, 32), print.directionCenter());
        CityLandUseSurfacePrintPlan.CultivateLinedRecipe recipe = assertInstanceOf(
                CityLandUseSurfacePrintPlan.CultivateLinedRecipe.class, print.recipe());
        Set<Integer> rotations = new HashSet<>();
        recipe.runs().stream().flatMap(run -> run.placements().stream())
                .forEach(placement -> rotations.add(placement.rotationDegrees()));
        assertEquals(Set.of(0, 90, 180, 270), rotations);
        assertTrue(recipe.runs().stream().anyMatch(run -> {
            Set<Integer> ownerChunks = new HashSet<>();
            run.placements().forEach(placement -> ownerChunks.add(
                    Math.floorDiv(placement.terrainSamplePoint().x(), 16)));
            return run.continuationAxis() == CityLandUseSurfaceRunCompiler.WorldAxis.X
                    && ownerChunks.size() >= 2;
        }), "one globally compiled radial run should remain continuous across owner chunks");
        Set<BlockPoint> members = cells(memberSpans);
        recipe.runs().stream().flatMap(run -> run.placements().stream()).forEach(placement -> {
            for (int z = placement.footprint().minZ(); z <= placement.footprint().maxZ(); z++) {
                for (int x = placement.footprint().minX(); x <= placement.footprint().maxX(); x++) {
                    assertTrue(members.contains(new BlockPoint(x, z)), "radial must not fill the area bbox");
                }
            }
        });
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

    private static LandUseTerrainField terrain() {
        return terrain(new BlockBounds(0, 0, 63, 31));
    }

    private static LandUseTerrainField terrain(BlockBounds bounds) {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        int maxCellX = Math.floorDiv(bounds.maxX(), 4);
        int maxCellZ = Math.floorDiv(bounds.maxZ(), 4);
        for (int z = 0; z <= maxCellZ; z++) {
            for (int x = 0; x <= maxCellX; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        70, 0, 0, 0, false, 0, 40,
                        "minecraft:plains", "plain", "p", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION,
                "city_test", bounds, 4, cells);
    }

    private static Set<BlockPoint> cells(List<LandUseAreaPlan.ScanlineSpan> spans) {
        Set<BlockPoint> result = new HashSet<>();
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            for (int x = span.minX(); x <= span.maxX(); x++) {
                result.add(new BlockPoint(x, span.z()));
            }
        }
        return result;
    }
}
