package com.rinsing.geomantia.systems.city.application.outdoor;

import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseExpansionResult;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityUrbanResidualResolverTest {
    @Test
    void absorbsSmallEnclosedHoleAndLeavesNoUnknownResidual() {
        Map<BlockPoint, String> claims = rectangleClaims(10, 10, 16, 16, "urban");
        claims.remove(new BlockPoint(13, 13));
        CityUrbanResidualResolver resolver = new CityUrbanResidualResolver();
        CityUrbanResidualResolver.Config config = config(Set.of("urban"), 8);

        CityUrbanResidualResolver.Result first = resolver.resolve("city", bounds(), terrain(),
                List.of(group("urban"), group("farm")), List.of(), expansion(claims), config);
        CityUrbanResidualResolver.Result second = resolver.resolve("city", bounds(), terrain(),
                List.of(group("urban"), group("farm")), List.of(), expansion(claims), config);

        assertTrue(first.expansion().claims().containsKey(new BlockPoint(13, 13)));
        assertEquals(1, first.urbanSpacePlan().coverageSummary().absorbedResidualBlocks());
        assertEquals(0, first.urbanSpacePlan().coverageSummary().unknownResidualBlocks());
        assertFalse(first.urbanSpacePlan().planHash().isBlank());
        assertEquals(first.urbanSpacePlan().planHash(), first.urbanSpacePlan().withComputedHash().planHash());
        assertEquals(first.urbanSpacePlan(), second.urbanSpacePlan());
        assertEquals(first.expansion(), second.expansion());
    }

    @Test
    void classifiesLongClosedSlitAsNarrowGapAndAbsorbsIt() {
        Map<BlockPoint, String> claims = rectangleClaims(2, 20, 61, 26, "urban");
        for (int x = 10; x <= 49; x++) claims.remove(new BlockPoint(x, 23));

        CityUrbanResidualResolver.Result result = new CityUrbanResidualResolver().resolve("city", bounds(),
                terrain(), List.of(group("urban")), List.of(), expansion(claims), config(Set.of("urban"), 8));

        assertTrue(result.urbanSpacePlan().residualRegions().stream().anyMatch(region ->
                region.residualClass() == CityUrbanSpacePlan.ResidualClass.NARROW_GAP
                        && !region.absorbedGroupId().isBlank()));
        assertEquals(0, result.urbanSpacePlan().coverageSummary().unknownResidualBlocks());
    }

    @Test
    void landscapeClaimsDoNotExpandUrbanEnvelope() {
        Map<BlockPoint, String> claims = rectangleClaims(5, 5, 12, 12, "urban");
        claims.putAll(rectangleClaims(70, 70, 85, 85, "farm"));

        CityUrbanResidualResolver.Result result = new CityUrbanResidualResolver().resolve("city", bounds(),
                terrain(), List.of(group("urban"), group("farm")), List.of(), expansion(claims),
                config(Set.of("urban"), 8));

        assertTrue(result.urbanSpacePlan().workingBounds().maxX() < 70);
        assertFalse(result.urbanSpacePlan().envelopeSpans().stream().anyMatch(span ->
                span.z() >= 70 || span.maxX() >= 70));
    }

    @Test
    void cityFabricAbsorbsResidualEvenWhenOldGrowthBudgetIsAtMaximum() {
        Map<BlockPoint, String> claims = rectangleClaims(10, 10, 16, 16, "urban");
        BlockPoint hole = new BlockPoint(13, 13);
        claims.remove(hole);
        LandUseSeedGroup full = group("urban", claims.size(), List.of());

        CityUrbanResidualResolver.Result result = new CityUrbanResidualResolver().resolve("city", bounds(),
                terrain(), List.of(full), List.of(), expansion(claims), config(Set.of("urban"), 8));

        assertTrue(result.expansion().claims().containsKey(hole));
        assertTrue(result.urbanSpacePlan().residualRegions().stream().anyMatch(region ->
                region.disposition() == CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR
                        && region.absorbedGroupId().equals("urban")));
        assertTrue(result.warnings().isEmpty());
        assertEquals(claims.size() + 1, result.expansion().claimedBlocksByGroup().get("urban"));
    }

    @Test
    void coverageSummaryIsMutuallyExclusiveAcrossOverlappingOwners() {
        Map<BlockPoint, String> claims = rectangleClaims(10, 10, 16, 16, "urban");
        BlockBounds structure = new BlockBounds(14, 14, 18, 18);
        LandUseSeedGroup urban = group("urban", 4096, List.of(structure));
        LandUseAreaPlan.CorridorExclusion corridor = new LandUseAreaPlan.CorridorExclusion("road",
                new BlockBounds(16, 16, 20, 20), "gate");
        CityUrbanResidualResolver.Config config = new CityUrbanResidualResolver.Config(true, 8,
                Set.of("urban"), List.of(structure), config(Set.of("urban"), 8).policy());

        CityUrbanResidualResolver.Result result = new CityUrbanResidualResolver().resolve("city", bounds(),
                terrain(), List.of(urban), List.of(corridor), expansion(claims), config);
        CityUrbanSpacePlan.CoverageSummary coverage = result.urbanSpacePlan().coverageSummary();

        assertEquals(coverage.envelopeBlocks(), coverage.landUseBlocks() + coverage.structureBlocks()
                + coverage.corridorBlocks() + coverage.absorbedResidualBlocks()
                + coverage.explicitResidualBlocks() + coverage.unknownResidualBlocks());
        assertEquals(0, coverage.unknownResidualBlocks());
        assertTrue(coverage.structureBlocks() < 25);
        assertTrue(coverage.corridorBlocks() < 25);
    }

    private static CityUrbanResidualResolver.Config config(Set<String> urbanGroups, int radius) {
        CityUrbanResidualResolver.ResidualPolicy policy = new CityUrbanResidualResolver.ResidualPolicy(
                CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR,
                CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR,
                CityUrbanSpacePlan.ResidualDisposition.COMMON_GREEN,
                CityUrbanSpacePlan.ResidualDisposition.NATURAL_RESERVE,
                CityUrbanSpacePlan.ResidualDisposition.NATURAL_RESERVE);
        return new CityUrbanResidualResolver.Config(true, radius, urbanGroups, List.of(), policy);
    }

    private static LandUseExpansionResult expansion(Map<BlockPoint, String> source) {
        Map<BlockPoint, LandUseExpansionResult.Claim> claims = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        source.forEach((point, groupId) -> {
            claims.put(point, new LandUseExpansionResult.Claim(groupId, 1));
            counts.merge(groupId, 1, Integer::sum);
        });
        return new LandUseExpansionResult(claims, counts, Map.of(), 0, 0);
    }

    private static Map<BlockPoint, String> rectangleClaims(int minX, int minZ, int maxX, int maxZ,
                                                            String groupId) {
        Map<BlockPoint, String> result = new HashMap<>();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) result.put(new BlockPoint(x, z), groupId);
        }
        return result;
    }

    private static LandUseSeedGroup group(String groupId) {
        return group(groupId, 4096, List.of());
    }

    private static LandUseSeedGroup group(String groupId,
                                          int maxAreaBlocks,
                                          List<BlockBounds> structureFootprints) {
        LandUseRule rule = new LandUseRule(groupId, groupId, List.of(groupId), 1, 0, 1, 4096,
                512, 1, 0, 0, 10, 0, 1, true, SurfacePolicy.PAVE,
                VegetationPolicy.CLEAR, BoundaryPolicy.OPEN, groupId);
        return new LandUseSeedGroup(groupId, rule, LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE),
                List.of(groupId), structureFootprints, List.of(new BlockPoint(5, 5)), List.of(),
                1, Math.min(64, maxAreaBlocks), maxAreaBlocks, 512, 1);
    }

    private static BlockBounds bounds() {
        return new BlockBounds(0, 0, 95, 95);
    }

    private static LandUseTerrainField terrain() {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = 0; z < 24; z++) {
            for (int x = 0; x < 24; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        70, 0, 0, 0, false, 0, 32,
                        "minecraft:plains", "plain", "patch", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city", bounds(), 4, cells);
    }
}
