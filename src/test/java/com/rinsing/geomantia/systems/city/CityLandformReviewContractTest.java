package com.rinsing.geomantia.systems.city;

import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CityLandformReviewContractTest {

    private final CityPlanningConfig config = CityPlanningConfig.defaults();
    private final CitySiteContextBuilder siteBuilder = new CitySiteContextBuilder(config);
    private final CityLandformReviewBuilder reviewBuilder = new CityLandformReviewBuilder(config);

    @Test
    void filterPatches_onlyKeepsIntersectingPatches() {
        CitySiteContext ctx = siteBuilder.build("city1", "realm1", "overworld",
                "s1", "c1", 0, 0, "capital", "village", 64, 4, null);

        LandformPatch inside = patch("p1", LandformType.PLAIN, 10, 10, 50, 50);
        LandformPatch overlap = patch("p2", LandformType.SHORE, 200, 200, 300, 300);
        LandformPatch outside = patch("p3", LandformType.WATER, 2000, 2000, 2100, 2100);

        // bounds is anchor(0,0) + radius 256*4=256 → (-256,-256) to (256,256)
        // overlap patch at (200,200)-(300,300) overlaps (200-256 overlap)
        // outside patch is far away

        List<LandformPatch> filtered = reviewBuilder.filterPatchesByBounds(ctx.bounds(),
                List.of(inside, overlap, outside));
        assertEquals(2, filtered.size());
    }

    @Test
    void classifyArea_byCellCount() {
        assertEquals(AreaClass.TINY, reviewBuilder.classifyArea(1));
        assertEquals(AreaClass.TINY, reviewBuilder.classifyArea(3));
        assertEquals(AreaClass.SMALL, reviewBuilder.classifyArea(4));
        assertEquals(AreaClass.SMALL, reviewBuilder.classifyArea(12));
        assertEquals(AreaClass.MEDIUM, reviewBuilder.classifyArea(13));
        assertEquals(AreaClass.MEDIUM, reviewBuilder.classifyArea(48));
        assertEquals(AreaClass.LARGE, reviewBuilder.classifyArea(49));
        assertEquals(AreaClass.LARGE, reviewBuilder.classifyArea(100));
    }

    @Test
    void areAdjacent_edgeTouching_true() {
        LandformPatch a = patch("a", LandformType.PLAIN, 0, 0, 10, 10);
        LandformPatch b = patch("b", LandformType.PLAIN, 11, 0, 20, 10);
        assertTrue(reviewBuilder.areAdjacent(a, b));
    }

    @Test
    void areAdjacent_cornerOnly_false() {
        LandformPatch a = patch("a", LandformType.PLAIN, 0, 0, 10, 10);
        LandformPatch b = patch("b", LandformType.PLAIN, 11, 11, 20, 20);
        assertFalse(reviewBuilder.areAdjacent(a, b));
    }

    @Test
    void areAdjacent_overlapping_false() {
        LandformPatch a = patch("a", LandformType.PLAIN, 0, 0, 10, 10);
        LandformPatch b = patch("b", LandformType.PLAIN, 5, 5, 15, 15);
        assertFalse(reviewBuilder.areAdjacent(a, b));
    }

    @Test
    void buildFacts_waterAdjacent_producesWaterFact() {
        LandformPatch waterPatch = new LandformPatch("pw", "r1", LandformType.SHORE,
                50, 0, 0, 50, 50, 68.0, 62.0, 75.0,
                1.5, 8.0, true, false, 0.9, EnumSet.noneOf(PatchFlag.class));
        List<String> facts = reviewBuilder.buildFacts(waterPatch, AreaClass.MEDIUM);
        assertTrue(facts.stream().anyMatch(f -> f.contains("毗邻") || f.contains("水")));
    }

    @Test
    void buildFacts_largeArea_producesSizeFact() {
        LandformPatch largePatch = new LandformPatch("pl", "r1", LandformType.PLAIN,
                100, 0, 0, 200, 200, 70.0, 65.0, 75.0,
                1.0, 100.0, false, false, 1.0, EnumSet.noneOf(PatchFlag.class));
        List<String> facts = reviewBuilder.buildFacts(largePatch, AreaClass.LARGE);
        assertTrue(facts.stream().anyMatch(f -> f.contains("最大")));
    }

    @Test
    void buildFacts_steep_producesSlopeWarning() {
        LandformPatch steepPatch = new LandformPatch("ps", "r1", LandformType.SLOPE,
                30, 0, 0, 80, 80, 90.0, 70.0, 110.0,
                15.0, 200.0, false, false, 0.8, EnumSet.noneOf(PatchFlag.class));
        List<String> facts = reviewBuilder.buildFacts(steepPatch, AreaClass.SMALL);
        assertTrue(facts.stream().anyMatch(f -> f.contains("平均坡度较高")));
    }

    @Test
    void buildWithTwoPatches_producesDistinctLabels() {
        CitySiteContext ctx = siteBuilder.build("city2", "realm2", "overworld",
                "s2", "c2", 0, 0, "capital", "village", 64, 4, null);

        LandformPatch p1 = patch("gis_p1", LandformType.PLAIN, 5, 5, 40, 40);
        LandformPatch p2 = patch("gis_p2", LandformType.PLAIN, 50, 50, 80, 80);

        CityLandformReviewPackage pkg = reviewBuilder.build(ctx, List.of(p1, p2));
        assertEquals(2, pkg.landformPatches().size());
        List<String> labels = pkg.landformPatches().stream()
                .map(LandformPatchSummary::mapLabel).toList();
        assertTrue(labels.get(0).contains("01"));
        assertTrue(labels.get(1).contains("02"));
        assertTrue(labels.get(0).contains("平原"));
    }

    @Test
    void buildWithMultipleTypes_producesLegendWithAllTypes() {
        CitySiteContext ctx = siteBuilder.build("city3", "realm3", "overworld",
                "s3", "c3", 0, 0, "capital", "village", 64, 4, null);

        LandformPatch p1 = patch("p1", LandformType.PLAIN, 5, 5, 40, 40);
        LandformPatch p2 = patch("p2", LandformType.SHORE, 50, 50, 80, 80);
        LandformPatch p3 = patch("p3", LandformType.WATER, 90, 90, 120, 120);

        CityLandformReviewPackage pkg = reviewBuilder.build(ctx, List.of(p1, p2, p3));
        assertEquals(3, pkg.legend().size());
    }

    @Test
    void aiPromptContext_containsScaleAndCount() {
        CitySiteContext ctx = siteBuilder.build("city4", "realm4", "overworld",
                "s4", "c4", 0, 0, "capital", "village", 64, 4, null);
        LandformPatch p1 = patch("p1", LandformType.PLAIN, 5, 5, 40, 40);

        String prompt = reviewBuilder.buildAiPromptContext(ctx,
                List.of(LandformPatchSummary.fromGisPatch(p1, "平原01", "平原", AreaClass.SMALL, List.of())));
        assertTrue(prompt.contains("village"));
        assertTrue(prompt.contains("capital"));
        assertTrue(prompt.contains("realm4"));
    }

    @Test
    void buildPlanningContext_containsCount() {
        CitySiteContext ctx = siteBuilder.build("city5", "realm5", "overworld",
                "s5", "c5", 0, 0, "capital", "village", 64, 4, null);
        LandformPatch p1 = patch("p1", LandformType.PLAIN, 5, 5, 40, 40);

        CityLandformReviewPackage pkg = reviewBuilder.build(ctx, List.of(p1));
        assertTrue(pkg.planningContext().stream().anyMatch(s -> s.contains("1个")));
    }

    private static LandformPatch patch(String id, LandformType type, int minX, int minZ, int maxX, int maxZ) {
        return new LandformPatch(id, "region_0", type,
                (maxX - minX) * (maxZ - minZ) / 256, minX, minZ, maxX, maxZ,
                70.0, 65.0, 75.0, 1.5, 50.0, false, false, 0.9,
                EnumSet.noneOf(PatchFlag.class));
    }
}
