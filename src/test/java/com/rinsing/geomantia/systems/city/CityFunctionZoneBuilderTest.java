package com.rinsing.geomantia.systems.city;

import com.rinsing.geomantia.systems.city.application.CityFunctionZoneBuilder;
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

class CityFunctionZoneBuilderTest {
    private final CityPlanningConfig config = CityPlanningConfig.defaults();
    private final CitySiteContextBuilder siteBuilder = new CitySiteContextBuilder(config);
    private final CityLandformReviewBuilder reviewBuilder = new CityLandformReviewBuilder(config);
    private final CityFunctionZoneBuilder zoneBuilder = new CityFunctionZoneBuilder();

    @Test
    void build_validPatchGroupPlan_producesZonesAndStats() {
        CityLandformReviewPackage review = reviewPackage();
        LandformPatchSummary plain = patchByType(review, LandformType.PLAIN);
        LandformPatchSummary shore = patchByType(review, LandformType.SHORE);

        PatchGroupPlan plan = new PatchGroupPlan(PatchGroupPlan.CURRENT_SCHEMA_VERSION, review.cityId(), List.of(
                group("g1", "中心公共区", "civic_core", plain),
                group("g2", "水岸市场", "market", shore)));

        CityFunctionZoneBuilder.Result result = zoneBuilder.build(review, plan);

        assertTrue(result.qualityReport().passed());
        assertEquals(2, result.functionZonePatches().size());
        assertEquals(2, result.functionZoneTerrainStats().size());
        assertEquals(FunctionZoneMap.CURRENT_SCHEMA_VERSION, result.functionZoneMap().schemaVersion());
        assertTrue(result.functionZoneMap().cellAssignments().size() >= 2);
        assertTrue(result.functionZonePatches().stream()
                .allMatch(zone -> zone.asJson()
                        .getAsJsonObject("cellShape")
                        .get("geometryMode")
                        .getAsString()
                        .equals("patch_envelope_union")));
    }

    @Test
    void build_unknownPatchLabel_hardBlocks() {
        CityLandformReviewPackage review = reviewPackage();
        PatchGroupPlan.Group group = new PatchGroupPlan.Group(
                "g_bad", "", "坏引用", "market", List.of("function.market"), List.of("不存在01"), List.of(),
                "market_core", List.of(), "测试", "", false);
        PatchGroupPlan plan = new PatchGroupPlan(PatchGroupPlan.CURRENT_SCHEMA_VERSION, review.cityId(), List.of(group));

        CityFunctionZoneBuilder.Result result = zoneBuilder.build(review, plan);

        assertFalse(result.qualityReport().passed());
        assertTrue(result.qualityReport().hardBlocks().stream().anyMatch(s -> s.contains("unknown patch label")));
        assertTrue(result.functionZonePatches().isEmpty());
    }

    @Test
    void build_unknownFunctionType_hardBlocks() {
        CityLandformReviewPackage review = reviewPackage();
        LandformPatchSummary plain = patchByType(review, LandformType.PLAIN);
        PatchGroupPlan.Group group = new PatchGroupPlan.Group(
                "g_type", "", "中心区", "invented_function", List.of("function.landmark"),
                List.of(plain.mapLabel()), List.of(plain.landformPatchId()),
                "village_hall", List.of(), "测试", "", false);
        PatchGroupPlan plan = new PatchGroupPlan(PatchGroupPlan.CURRENT_SCHEMA_VERSION, review.cityId(), List.of(group));

        CityFunctionZoneBuilder.Result result = zoneBuilder.build(review, plan);

        assertFalse(result.qualityReport().passed());
        assertTrue(result.qualityReport().hardBlocks().stream().anyMatch(s -> s.contains("Unknown functionType")));
    }

    @Test
    void build_duplicateFunctionType_allowsMultipleInstances() {
        CityLandformReviewPackage review = reviewPackage();
        LandformPatchSummary plain = patchByType(review, LandformType.PLAIN);
        LandformPatchSummary slope = patchByType(review, LandformType.SLOPE);
        PatchGroupPlan plan = new PatchGroupPlan(PatchGroupPlan.CURRENT_SCHEMA_VERSION, review.cityId(), List.of(
                group("g1", "北居住区", "residential", plain),
                group("g2", "南居住区", "residential", slope)));

        CityFunctionZoneBuilder.Result result = zoneBuilder.build(review, plan);

        assertTrue(result.qualityReport().passed());
        assertEquals(2, result.functionZonePatches().size());
        assertEquals(2, result.functionZonePatches().stream()
                .filter(z -> z.functionType() == CityFunctionType.RESIDENTIAL).count());
    }

    @Test
    void build_memberCells_usesMemberCellUnionShape() {
        CityLandformReviewPackage review = reviewPackageWithMemberCells();
        LandformPatchSummary plain = patchByType(review, LandformType.PLAIN);
        PatchGroupPlan plan = new PatchGroupPlan(PatchGroupPlan.CURRENT_SCHEMA_VERSION, review.cityId(), List.of(
                group("g_cells", "中心公共区", "civic_core", plain)));

        CityFunctionZoneBuilder.Result result = zoneBuilder.build(review, plan);
        FunctionZonePatch zone = result.functionZonePatches().get(0);

        assertTrue(result.qualityReport().passed());
        assertEquals(2, zone.memberCells().size());
        assertEquals("patch_member_cells_union", zone.asJson()
                .getAsJsonObject("cellShape")
                .get("geometryMode")
                .getAsString());
    }

    @Test
    void build_splitRequested_recordsWarningButStillPasses() {
        CityLandformReviewPackage review = reviewPackage();
        LandformPatchSummary plain = patchByType(review, LandformType.PLAIN);
        PatchGroupPlan.Group group = new PatchGroupPlan.Group(
                "g_split", "", "中心区", "civic_core", List.of("function.landmark"),
                List.of(plain.mapLabel()), List.of(plain.landformPatchId()),
                "village_hall", List.of(), "需要后续裁剪", "", true);
        PatchGroupPlan plan = new PatchGroupPlan(PatchGroupPlan.CURRENT_SCHEMA_VERSION, review.cityId(), List.of(group));

        CityFunctionZoneBuilder.Result result = zoneBuilder.build(review, plan);

        assertTrue(result.qualityReport().passed());
        assertTrue(result.qualityReport().warnings().stream().anyMatch(s -> s.contains("splitRequested")));
    }

    private CityLandformReviewPackage reviewPackage() {
        CitySiteContext ctx = siteBuilder.build("city_d4", "realm_d4", "overworld",
                "seed_d4", "candidate_d4", 0, 0, "village", "village", 64, 4, null);
        return reviewBuilder.build(ctx, List.of(
                patch("plain", LandformType.PLAIN, -100, -100, -10, -10),
                patch("shore", LandformType.SHORE, 0, 0, 80, 80),
                patch("slope", LandformType.SLOPE, 100, 100, 150, 150)));
    }

    private CityLandformReviewPackage reviewPackageWithMemberCells() {
        CityLandformReviewPackage review = reviewPackage();
        List<LandformPatchSummary> patches = review.landformPatches().stream()
                .map(patch -> patch.landformType() == LandformType.PLAIN
                        ? patch.withMemberCells(List.of(
                        new PatchMemberCell(0, 0, -100, -100),
                        new PatchMemberCell(1, 0, -96, -100)))
                        : patch)
                .toList();
        return new CityLandformReviewPackage(
                review.schemaVersion(),
                review.cityId(),
                review.grid(),
                review.targetScale(),
                review.reviewMapImage(),
                review.legend(),
                patches,
                review.planningContext(),
                review.aiPromptContext(),
                review.debugRefs());
    }

    private PatchGroupPlan.Group group(String id, String zoneName, String functionType, LandformPatchSummary patch) {
        return new PatchGroupPlan.Group(
                id, "", zoneName, functionType, semanticTerms(functionType),
                List.of(patch.mapLabel()), List.of(patch.landformPatchId()),
                "main_role", List.of(), "测试分组理由", "", false);
    }

    private List<String> semanticTerms(String functionType) {
        return switch (functionType) {
            case "civic_core" -> List.of("function.landmark");
            case "residential" -> List.of("function.村庄");
            case "production" -> List.of("function.utility");
            case "market" -> List.of("function.trade");
            case "farm_or_pasture" -> List.of("function.农场");
            case "defense" -> List.of("function.瞭望塔");
            case "harbor_or_waterfront" -> List.of("function.灯塔", "function.贸易船");
            case "sacred_or_cultural" -> List.of("function.教堂");
            default -> List.of("function.landmark");
        };
    }

    private LandformPatchSummary patchByType(CityLandformReviewPackage review, LandformType type) {
        return review.landformPatches().stream()
                .filter(p -> p.landformType() == type)
                .findFirst()
                .orElseThrow();
    }

    private static LandformPatch patch(String id, LandformType type, int minX, int minZ, int maxX, int maxZ) {
        boolean water = type == LandformType.SHORE || type == LandformType.WATER;
        return new LandformPatch(id, "region_0", type,
                Math.max(1, (maxX - minX) * (maxZ - minZ) / 256), minX, minZ, maxX, maxZ,
                70.0, 65.0, 75.0, type == LandformType.SLOPE ? 12.0 : 1.5,
                water ? 8.0 : 50.0, water, false, 0.9,
                EnumSet.noneOf(PatchFlag.class));
    }
}
