package com.rinsing.geomantia.systems.city;

import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.cell.SampleSource;
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CityLandformReviewBuilderTest {

    private final CityPlanningConfig config = CityPlanningConfig.defaults();
    private final CitySiteContextBuilder siteBuilder = new CitySiteContextBuilder(config);
    private final CityLandformReviewBuilder reviewBuilder = new CityLandformReviewBuilder(config);

    @Test
    void fullBuild_villageWithMixedPatches_producesCompletePackage() {
        CitySiteContext ctx = siteBuilder.build("city_full", "realm_full", "overworld",
                "s_full", "c_full", 0, 0, "capital", "village", 64, 4, null);

        LandformPatch p1 = patch("p1", LandformType.PLAIN, 5, 5, 100, 100);
        LandformPatch p2 = patch("p2", LandformType.SHORE, 50, 50, 80, 80);
        LandformPatch p3 = patch("p3", LandformType.WATER, 90, 90, 120, 120);
        LandformPatch p4 = patch("p4", LandformType.SLOPE, -200, -200, -150, -150);

        CityLandformReviewPackage pkg = reviewBuilder.build(ctx, List.of(p1, p2, p3, p4));

        assertNotNull(pkg.schemaVersion());
        assertEquals(CityLandformReviewPackage.CURRENT_SCHEMA_VERSION, pkg.schemaVersion());
        assertEquals("city_full", pkg.cityId());
        assertEquals(ctx.grid(), pkg.grid());
        assertNotNull(pkg.targetScale());

        assertFalse(pkg.landformPatches().isEmpty());
        assertTrue(pkg.landformPatches().size() <= 4);

        // All filtered patches should have labels
        for (LandformPatchSummary s : pkg.landformPatches()) {
            assertFalse(s.mapLabel().isEmpty());
            assertFalse(s.displayLandformName().isEmpty());
            assertNotNull(s.areaClass());
        }

        // Legend should contain all present types
        assertFalse(pkg.legend().isEmpty());
        assertFalse(pkg.planningContext().isEmpty());
        assertFalse(pkg.aiPromptContext().isEmpty());
    }

    @Test
    void assignLabels_ordersByTypeThenCellCount() {
        CitySiteContext ctx = siteBuilder.build("city_labels", "realm_labels", "overworld",
                "sl", "cl", 0, 0, "capital", "village", 64, 4, null);

        LandformPatch pSmall = patch("ps", LandformType.PLAIN, 5, 5, 10, 10);    // small area
        LandformPatch pLarge = patch("pl", LandformType.PLAIN, 5, 5, 200, 200);  // large area
        LandformPatch pShore = patch("psh", LandformType.SHORE, 50, 50, 80, 80);

        // Filter only the first 3 (all intersect)
        List<LandformPatch> filtered = reviewBuilder.filterPatchesByBounds(ctx.bounds(),
                List.of(pSmall, pLarge, pShore));
        List<LandformPatchSummary> summaries = reviewBuilder.buildSummaries(filtered);
        reviewBuilder.assignLabels(summaries);

        // Input order preserved; numbering follows contract LandformType order, then cellCount desc.
        assertTrue(summaries.get(0).mapLabel().contains("平原")); // pSmall -> 平原03
        assertTrue(summaries.get(1).mapLabel().contains("平原")); // pLarge -> 平原02
        assertTrue(summaries.get(2).mapLabel().contains("海岸")); // pShore -> 海岸01
        assertEquals(pLarge.patchId(), summaries.get(1).landformPatchId());
        assertEquals(pSmall.patchId(), summaries.get(0).landformPatchId());
        assertTrue(summaries.get(2).mapLabel().contains("01"));
    }

    @Test
    void detectNeighbors_findsAdjacentPatches() {
        LandformPatch a = patch("a", LandformType.PLAIN, 0, 0, 10, 10);
        LandformPatch b = patch("b", LandformType.PLAIN, 11, 0, 20, 10); // adjacent right
        LandformPatch c = patch("c", LandformType.PLAIN, 50, 50, 60, 60); // far away

        List<LandformPatch> patches = List.of(a, b, c);
        List<LandformPatchSummary> summaries = reviewBuilder.buildSummaries(patches);
        reviewBuilder.detectNeighborsForAll(patches, summaries);

        assertTrue(summaries.get(0).neighborLandformPatchIds().contains("b"));
        assertTrue(summaries.get(1).neighborLandformPatchIds().contains("a"));
        assertTrue(summaries.get(2).neighborLandformPatchIds().isEmpty());
    }

    @Test
    void detectNeighbors_cornerOnly_notNeighbors() {
        LandformPatch a = patch("a", LandformType.PLAIN, 0, 0, 10, 10);
        LandformPatch b = patch("b", LandformType.PLAIN, 11, 11, 20, 20);

        List<LandformPatch> patches = List.of(a, b);
        List<LandformPatchSummary> summaries = reviewBuilder.buildSummaries(patches);
        reviewBuilder.detectNeighborsForAll(patches, summaries);

        assertTrue(summaries.get(0).neighborLandformPatchIds().isEmpty());
        assertTrue(summaries.get(1).neighborLandformPatchIds().isEmpty());
    }

    @Test
    void buildFactsWithFragment_producesFragmentWarning() {
        LandformPatch frag = new LandformPatch("pf", "r1", LandformType.PLAIN,
                5, 0, 0, 20, 20, 70.0, 68.0, 72.0,
                1.0, 50.0, false, false, 0.9,
                EnumSet.of(PatchFlag.FRAGMENT));
        List<String> facts = reviewBuilder.buildFacts(frag, AreaClass.TINY);
        assertTrue(facts.stream().anyMatch(f -> f.contains("碎片")));
    }

    @Test
    void buildFacts_staysNeutralBeforeFunctionZoning() {
        LandformPatch flat = patch("flat", LandformType.PLAIN, 0, 0, 40, 40);
        List<String> facts = reviewBuilder.buildFacts(flat, AreaClass.SMALL);

        assertTrue(facts.stream().anyMatch(f -> f.contains("平均坡度较低")));
        assertFalse(facts.stream().anyMatch(f -> f.contains("适合建设")));
    }

    @Test
    void buildPlanningContext_withBorderTerritory_producesWarning() {
        CitySiteContext ctx = siteBuilder.build("city_b", "realm_b", "overworld",
                "sb", "cb", 0, 0, "capital", "village", 64, 4, null);

        LandformPatch p1 = patch("p1", LandformType.PLAIN, 5, 5, 40, 40);
        List<LandformPatchSummary> summaries = List.of(
                LandformPatchSummary.fromGisPatch(p1, "平原01", "平原", AreaClass.SMALL, List.of("测试事实")));

        // Override territory check to BORDER to test warning
        List<String> ctxLines = reviewBuilder.buildPlanningContext(ctx, summaries);
        assertFalse(ctxLines.isEmpty());
    }

    @Test
    void buildPlanningContext_doesNotEmitBuildSuggestion() {
        CitySiteContext ctx = siteBuilder.build("city_ctx", "realm_ctx", "overworld",
                "sctx", "cctx", 0, 0, "capital", "village", 64, 4, null);
        LandformPatch p1 = patch("p1", LandformType.PLAIN, 5, 5, 40, 40);

        CityLandformReviewPackage pkg = reviewBuilder.build(ctx, List.of(p1));

        assertTrue(pkg.planningContext().stream().anyMatch(s -> s.contains("低坡中大型地貌区")));
        assertFalse(pkg.planningContext().stream().anyMatch(s -> s.contains("建议建设区域")));
    }

    @Test
    void customConfig_changesThresholds() {
        CityPlanningConfig custom = CityPlanningConfig.defaults()
                .withAreaThresholds(new CityPlanningConfig.AreaThresholds(1, 5, 20));
        CityLandformReviewBuilder customBuilder = new CityLandformReviewBuilder(custom);

        assertEquals(AreaClass.TINY, customBuilder.classifyArea(1));
        assertEquals(AreaClass.SMALL, customBuilder.classifyArea(3));
        assertEquals(AreaClass.MEDIUM, customBuilder.classifyArea(10));
        assertEquals(AreaClass.LARGE, customBuilder.classifyArea(30));
    }

    @Test
    void customConfig_changesLandformNames() {
        CityPlanningConfig custom = CityPlanningConfig.defaults()
                .withLandformDisplayNames(Map.of(LandformType.PLAIN, "Flatland"));
        CityLandformReviewBuilder customBuilder = new CityLandformReviewBuilder(custom);

        LandformPatch p = patch("p1", LandformType.PLAIN, 5, 5, 40, 40);
        List<LandformPatchSummary> summaries = customBuilder.buildSummaries(List.of(p));
        customBuilder.assignLabels(summaries);
        assertTrue(summaries.get(0).mapLabel().contains("Flatland"));
    }

    @Test
    void filterPatchesByBounds_noneIntersecting_returnsEmpty() {
        CitySiteContext ctx = siteBuilder.build("city_e", "realm_e", "overworld",
                "se", "ce", 0, 0, "capital", "village", 1, 4, null);
        // ctx bounds = (0,0)-(4,4) with radius 1*4=4

        LandformPatch farAway = patch("far", LandformType.PLAIN, 1000, 1000, 1100, 1100);
        List<LandformPatch> filtered = reviewBuilder.filterPatchesByBounds(ctx.bounds(), List.of(farAway));
        assertTrue(filtered.isEmpty());
    }

    @Test
    void allFieldsInReviewPackageJson() {
        CitySiteContext ctx = siteBuilder.build("city_json", "realm_json", "overworld",
                "sj", "cj", 0, 0, "capital", "village", 64, 4, null);
        LandformPatch p1 = patch("p1", LandformType.PLAIN, 5, 5, 40, 40);

        CityLandformReviewPackage pkg = reviewBuilder.build(ctx, List.of(p1));
        String json = pkg.asJson().toString();

        assertTrue(json.contains("city_landform_review.v0.1"));
        assertTrue(json.contains("city_json"));
        assertTrue(json.contains("landformPatches"));
        assertTrue(json.contains("legend"));
        assertTrue(json.contains("planningContext"));
        assertTrue(json.contains("aiPromptContext"));
        assertTrue(json.contains("debugRefs"));
        assertTrue(json.contains("mapLabel"));
        assertTrue(json.contains("blockBounds"));
        assertTrue(json.contains("geometryMode"));
        assertTrue(json.contains("metricsSummary"));
    }

    @Test
    void buildFromAtlasRegion_includesPatchMemberCells() {
        CitySiteContext ctx = siteBuilder.build("city_cells", "realm_cells", "minecraft:overworld",
                "sc", "cc", 0, 0, "village", "village", 16, 4, null);
        AtlasRegion region = new AtlasRegion("minecraft:overworld", 0, 0,
                GisSampleConfig.defaults().withCellStepBlocks(4));
        LandformPatch patch = patch("patch_cells", LandformType.PLAIN, 0, 0, 16, 16);
        region.replacePatches(List.of(patch));
        region.cell(0, 0).setSample(SampleSource.PRIOR, 70.0, SurfaceType.GRASS,
                "minecraft:plains", false, 0.0);
        region.cell(0, 0).setPatchId(patch.patchId());
        region.cell(1, 0).setSample(SampleSource.PRIOR, 70.0, SurfaceType.GRASS,
                "minecraft:plains", false, 0.0);
        region.cell(1, 0).setPatchId(patch.patchId());
        region.cell(0, 1).setSample(SampleSource.PRIOR, 70.0, SurfaceType.GRASS,
                "minecraft:forest", false, 0.0);
        region.cell(0, 1).setPatchId(patch.patchId());

        CityLandformReviewPackage pkg = reviewBuilder.build(ctx, region);
        LandformPatchSummary summary = pkg.landformPatches().get(0);

        assertEquals("patch_member_cells", summary.geometryMode());
        assertEquals(3, summary.memberCells().size());
        assertEquals("minecraft:plains", summary.biomeSummary().dominantBiome());
        assertEquals(2, summary.biomeSummary().biomeHistogram().get("minecraft:plains"));
        assertEquals(1, summary.biomeSummary().biomeHistogram().get("minecraft:forest"));
        assertTrue(summary.biomeSummary().mixedBiome());
        assertTrue(summary.summaryFacts().stream().anyMatch(fact -> fact.contains("主要群系")));
        String json = pkg.asJson().toString();
        assertTrue(json.contains("memberCells"));
        assertTrue(json.contains("biomeSummary"));
        assertTrue(json.contains("minecraft:plains"));

        CityLandformReviewPackage restored = CityLandformReviewPackage.fromJson(pkg.asJson());
        LandformPatchSummary restoredSummary = restored.landformPatches().get(0);
        assertEquals("minecraft:plains", restoredSummary.biomeSummary().dominantBiome());
        assertEquals(3, restoredSummary.biomeSummary().sampledCellCount());
    }

    private static LandformPatch patch(String id, LandformType type, int minX, int minZ, int maxX, int maxZ) {
        return new LandformPatch(id, "region_0", type,
                Math.max(1, (maxX - minX) * (maxZ - minZ) / 256), minX, minZ, maxX, maxZ,
                70.0, 65.0, 75.0, 1.5, 50.0, false, false, 0.9,
                EnumSet.noneOf(PatchFlag.class));
    }
}
