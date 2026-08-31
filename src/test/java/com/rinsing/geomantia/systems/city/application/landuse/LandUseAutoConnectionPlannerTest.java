package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseAutoConnectionPlanner;
import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseExpansionResult;
import com.rinsing.geomantia.systems.city.algorithm.landuse.StableLandUseExpander;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandUseAutoConnectionPlannerTest {
    @Test
    void keepsEveryCompatiblePairWithinThreshold() {
        List<LandUseSeedGroup> groups = List.of(
                group("a", SurfacePolicy.PAVE),
                group("b", SurfacePolicy.PAVE),
                group("c", SurfacePolicy.PAVE));
        LandUseAutoConnectionPlanner.Plan plan = new LandUseAutoConnectionPlanner().plan(groups,
                expansion(Map.of(
                        new BlockPoint(0, 0), "a",
                        new BlockPoint(20, 0), "b",
                        new BlockPoint(40, 0), "c")));

        assertEquals(3, plan.connections().size());
        assertEquals(2, plan.targetsFor("a").size());
        assertEquals(2, plan.targetsFor("b").size());
        assertEquals(2, plan.targetsFor("c").size());
        LandUseAutoConnectionPlanner.Plan reversed = new LandUseAutoConnectionPlanner().plan(
                List.of(groups.get(2), groups.get(1), groups.get(0)), expansion(Map.of(
                        new BlockPoint(40, 0), "c",
                        new BlockPoint(20, 0), "b",
                        new BlockPoint(0, 0), "a")));
        assertEquals(plan.connections(), reversed.connections());
    }

    @Test
    void connectsOnlyMatchingPaveOrCultivateSurfaces() {
        List<LandUseSeedGroup> groups = List.of(
                group("pave_a", SurfacePolicy.PAVE),
                group("pave_b", SurfacePolicy.PAVE),
                group("farm_a", SurfacePolicy.CULTIVATE),
                group("farm_b", SurfacePolicy.CULTIVATE),
                group("natural", SurfacePolicy.PRESERVE));
        Map<BlockPoint, String> claims = new LinkedHashMap<>();
        claims.put(new BlockPoint(0, 0), "pave_a");
        claims.put(new BlockPoint(65, 0), "pave_b");
        claims.put(new BlockPoint(0, 100), "farm_a");
        claims.put(new BlockPoint(65, 100), "farm_b");
        claims.put(new BlockPoint(1, 0), "natural");

        LandUseAutoConnectionPlanner.Plan plan = new LandUseAutoConnectionPlanner().plan(groups, expansion(claims));

        assertEquals(2, plan.connections().size());
        assertTrue(plan.connections().stream().anyMatch(value ->
                value.groupA().equals("pave_a") && value.groupB().equals("pave_b")
                        && value.initialBoundaryGapBlocks() == 64));
        assertTrue(plan.connections().stream().anyMatch(value ->
                value.groupA().equals("farm_a") && value.groupB().equals("farm_b")
                        && value.initialBoundaryGapBlocks() == 64));
    }

    @Test
    void resolvedBlockSettingsDoNotSplitTheSameCompatibilityCategory() {
        LandUseSeedGroup stone = group("stone", SurfacePolicy.PAVE);
        LandUseSeedGroup andesite = group("andesite", SurfacePolicy.PAVE,
                new LandUseSurfaceSettings(true, true, "minecraft:polished_andesite", "", "PAVE"));
        LandUseExpansionResult probe = expansion(Map.of(
                new BlockPoint(0, 0), "stone", new BlockPoint(10, 0), "andesite"));

        assertEquals(1, new LandUseAutoConnectionPlanner().plan(List.of(stone, andesite), probe)
                .connections().size());
    }

    @Test
    void promotedPaveDefaultsAutoConnectUnlessExplicitlyDisabled() {
        LandUseSurfaceSettings defaults = LandUseSurfaceSettings.defaults(SurfacePolicy.PRESERVE);

        LandUseSurfaceSettings promoted = defaults.withOverrides(
                true, null, "minecraft:cobblestone", null);
        LandUseSurfaceSettings disabled = defaults.withOverrides(
                true, false, "minecraft:cobblestone", null);

        assertTrue(promoted.autoConnect());
        assertEquals("PAVE", promoted.compatibilityCategory());
        assertEquals(false, disabled.autoConnect());
    }

    @Test
    void rejectsGapAboveThresholdAndHandlesNegativeBuckets() {
        LandUseAutoConnectionPlanner planner = new LandUseAutoConnectionPlanner();
        List<LandUseSeedGroup> groups = List.of(group("a", SurfacePolicy.PAVE), group("b", SurfacePolicy.PAVE));

        assertTrue(planner.plan(groups, expansion(Map.of(
                new BlockPoint(0, 0), "a", new BlockPoint(66, 0), "b"))).connections().isEmpty());
        assertEquals(1, planner.plan(groups, expansion(Map.of(
                new BlockPoint(-66, 0), "a", new BlockPoint(-1, 0), "b"))).connections().size());
    }

    @Test
    void guidancePullsTheSameAreaBudgetTowardItsTarget() {
        LandUseSeedGroup group = group("a", SurfacePolicy.PAVE, new BlockPoint(20, 20), 80);
        LandUseTerrainField terrain = flatTerrain();
        StableLandUseExpander expander = new StableLandUseExpander();
        LandUseExpansionResult ordinary = expander.expand("city_test", terrain.planningBounds(), terrain,
                List.of(group), List.of(), "test");
        LandUseAutoConnectionPlanner.Plan guidance = new LandUseAutoConnectionPlanner.Plan(List.of(), Map.of(
                "a", List.of(new LandUseAutoConnectionPlanner.Target(
                        "auto_surface:pave:a:b", "b", new BlockPoint(55, 20)))));

        LandUseExpansionResult directed = expander.expand("city_test", terrain.planningBounds(), terrain,
                List.of(group), List.of(), "test", guidance);

        int ordinaryMaxX = ordinary.claims().keySet().stream().mapToInt(BlockPoint::x).max().orElseThrow();
        int directedMaxX = directed.claims().keySet().stream().mapToInt(BlockPoint::x).max().orElseThrow();
        assertTrue(directedMaxX > ordinaryMaxX);
        assertEquals(ordinary.claims().size(), directed.claims().size());
    }

    @Test
    void growthRegionsKeepIndependentAreaCapsWithinOneFunctionalGroup() {
        LandUseSeedGroup defaults = group("district", SurfacePolicy.PAVE);
        LandUseSeedGroup group = new LandUseSeedGroup(defaults.groupId(), defaults.rule(),
                defaults.surfaceSettings(), defaults.anchorIds(), defaults.structureFootprints(),
                List.of(new BlockPoint(4, 4), new BlockPoint(44, 4)), defaults.gateSlots(),
                2, 5, 5, defaults.actionBudget(), defaults.competitionWeight(), List.of(
                new LandUseSeedGroup.GrowthRegion("district::small", List.of("small"),
                        List.of(new BlockPoint(4, 4)), 1, 1, 1),
                new LandUseSeedGroup.GrowthRegion("district::large", List.of("large"),
                        List.of(new BlockPoint(44, 4)), 1, 4, 4)));

        LandUseExpansionResult result = new StableLandUseExpander().expand("city_test",
                flatTerrain().planningBounds(), flatTerrain(), List.of(group), List.of(), "test");

        assertEquals(5, result.claimedBlocksByGroup().get("district"));
        assertEquals(1, result.claimedBlocksByGrowthRegion().get("district::small"));
        assertEquals(4, result.claimedBlocksByGrowthRegion().get("district::large"));
    }

    @Test
    void programDerivedGrowthBiasChangesShapeWithoutChangingAreaBudget() {
        LandUseSeedGroup defaults = group("directed", SurfacePolicy.CULTIVATE,
                new BlockPoint(30, 20), 96);
        LandUseSeedGroup away = new LandUseSeedGroup(defaults.groupId(), defaults.rule(),
                defaults.surfaceSettings(), defaults.anchorIds(), defaults.structureFootprints(),
                defaults.seedPoints(), defaults.gateSlots(), defaults.minAreaBlocks(),
                defaults.preferredAreaBlocks(), defaults.maxAreaBlocks(), defaults.actionBudget(),
                defaults.competitionWeight(), defaults.growthRegions(),
                new LandUseSeedGroup.GrowthBias(LandUseSeedGroup.GrowthBiasMode.AWAY_FROM_REFERENCE,
                        new BlockPoint(10, 20)));
        LandUseSeedGroup toward = new LandUseSeedGroup(defaults.groupId(), defaults.rule(),
                defaults.surfaceSettings(), defaults.anchorIds(), defaults.structureFootprints(),
                defaults.seedPoints(), defaults.gateSlots(), defaults.minAreaBlocks(),
                defaults.preferredAreaBlocks(), defaults.maxAreaBlocks(), defaults.actionBudget(),
                defaults.competitionWeight(), defaults.growthRegions(),
                new LandUseSeedGroup.GrowthBias(LandUseSeedGroup.GrowthBiasMode.TOWARD_REFERENCE,
                        new BlockPoint(10, 20)));

        StableLandUseExpander expander = new StableLandUseExpander();
        LandUseExpansionResult awayResult = expander.expand("city_test", flatTerrain().planningBounds(),
                flatTerrain(), List.of(away), List.of(), "bias");
        LandUseExpansionResult towardResult = expander.expand("city_test", flatTerrain().planningBounds(),
                flatTerrain(), List.of(toward), List.of(), "bias");

        double awayMeanX = awayResult.claims().keySet().stream().mapToInt(BlockPoint::x).average().orElseThrow();
        double towardMeanX = towardResult.claims().keySet().stream().mapToInt(BlockPoint::x).average().orElseThrow();
        assertTrue(awayMeanX > towardMeanX);
        assertEquals(awayResult.claims().size(), towardResult.claims().size());
    }

    @Test
    void terrainPolicyChangesSoftReachWithoutChangingHardTerrainGate() {
        LandUseRule rule = new LandUseRule("terrain", "terrain", List.of("terrain"), 1, 0, 1, 512,
                40, 1, 5, 0, 0, 0, 1, true, SurfacePolicy.PAVE,
                VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN, "terrain");
        LandUseSeedGroup defaults = new LandUseSeedGroup("terrain", rule,
                LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE), List.of("terrain"), List.of(),
                List.of(new BlockPoint(31, 20)), List.of(), 1, 256, 512, 40, 1);
        LandUseSeedGroup conform = withTerrainAndPatch(defaults, LandUseSeedGroup.TerrainBias.CONFORM, List.of());
        LandUseSeedGroup assertive = withTerrainAndPatch(defaults, LandUseSeedGroup.TerrainBias.ASSERTIVE,
                List.of());

        StableLandUseExpander expander = new StableLandUseExpander();
        LandUseExpansionResult conformResult = expander.expand("city_test", roughTerrain().planningBounds(),
                roughTerrain(), List.of(conform), List.of(), "terrain");
        LandUseExpansionResult assertiveResult = expander.expand("city_test", roughTerrain().planningBounds(),
                roughTerrain(), List.of(assertive), List.of(), "terrain");

        assertTrue(assertiveResult.claims().size() > conformResult.claims().size());
        assertTrue(assertiveResult.claims().keySet().stream().allMatch(point ->
                roughTerrain().planningBounds().contains(point.x(), point.z())));
    }

    @Test
    void preferredPatchActsAsSoftAffinityForAttachedSources() {
        LandUseRule rule = new LandUseRule("patch", "patch", List.of("patch"), 1, 0, 1, 256,
                256, 1, 0, 0, 0, 0, 1, true, SurfacePolicy.PAVE,
                VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN, "patch");
        LandUseSeedGroup defaults = new LandUseSeedGroup("patch", rule,
                LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE), List.of("attached"), List.of(),
                List.of(new BlockPoint(31, 20)), List.of(), 1, 200, 200, 256, 1);
        LandUseSeedGroup neutral = withTerrainAndPatch(defaults, LandUseSeedGroup.TerrainBias.BALANCED, List.of());
        LandUseSeedGroup preferred = withTerrainAndPatch(defaults, LandUseSeedGroup.TerrainBias.BALANCED,
                List.of("preferred"));

        StableLandUseExpander expander = new StableLandUseExpander();
        LandUseExpansionResult neutralResult = expander.expand("city_test", patchTerrain().planningBounds(),
                patchTerrain(), List.of(neutral), List.of(), "patch");
        LandUseExpansionResult preferredResult = expander.expand("city_test", patchTerrain().planningBounds(),
                patchTerrain(), List.of(preferred), List.of(), "patch");
        long neutralPreferred = neutralResult.claims().keySet().stream().filter(point -> point.x() < 32).count();
        long biasedPreferred = preferredResult.claims().keySet().stream().filter(point -> point.x() < 32).count();

        assertEquals(neutralResult.claims().size(), preferredResult.claims().size());
        assertTrue(biasedPreferred > neutralPreferred);
    }

    @Test
    void rejectsGrowthRegionBudgetsThatDoNotSumToGroupBudget() {
        LandUseSeedGroup defaults = group("invalid", SurfacePolicy.PAVE);
        assertThrows(IllegalArgumentException.class, () -> new LandUseSeedGroup(defaults.groupId(), defaults.rule(),
                defaults.surfaceSettings(), defaults.anchorIds(), defaults.structureFootprints(),
                defaults.seedPoints(), defaults.gateSlots(), 1, 32, 256, defaults.actionBudget(),
                defaults.competitionWeight(), List.of(new LandUseSeedGroup.GrowthRegion("invalid::region",
                defaults.anchorIds(), defaults.seedPoints(), 1, 31, 256))));
    }

    private static LandUseExpansionResult expansion(Map<BlockPoint, String> claims) {
        Map<BlockPoint, LandUseExpansionResult.Claim> values = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        claims.forEach((point, groupId) -> {
            values.put(point, new LandUseExpansionResult.Claim(groupId, 0));
            counts.merge(groupId, 1, Integer::sum);
        });
        return new LandUseExpansionResult(values, counts, 0, 0);
    }

    private static LandUseSeedGroup group(String id, SurfacePolicy surfacePolicy) {
        return group(id, surfacePolicy, new BlockPoint(0, 0), 256);
    }

    private static LandUseSeedGroup group(String id,
                                          SurfacePolicy surfacePolicy,
                                          LandUseSurfaceSettings settings) {
        LandUseSeedGroup defaults = group(id, surfacePolicy);
        return new LandUseSeedGroup(defaults.groupId(), defaults.rule(), settings, defaults.anchorIds(),
                defaults.structureFootprints(), defaults.seedPoints(), defaults.gateSlots(), defaults.minAreaBlocks(),
                defaults.preferredAreaBlocks(), defaults.maxAreaBlocks(), defaults.actionBudget(),
                defaults.competitionWeight());
    }

    private static LandUseSeedGroup group(String id,
                                          SurfacePolicy surfacePolicy,
                                          BlockPoint seed,
                                          int maxAreaBlocks) {
        LandUseRule rule = new LandUseRule(id, id, List.of(id), 1, 0, 1, 256,
                256, 1, 0, 0, 10, 0, 1, true, surfacePolicy,
                VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN, id);
        return new LandUseSeedGroup(id, rule, LandUseSurfaceSettings.defaults(surfacePolicy),
                List.of(id), List.of(), List.of(seed),
                List.of(), 1, Math.min(32, maxAreaBlocks), maxAreaBlocks, 256, 1);
    }

    private static LandUseTerrainField flatTerrain() {
        List<LandUseTerrainField.Cell> cells = new java.util.ArrayList<>();
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x < 16; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        70, 0, 0, 0, false, 0, 40,
                        "minecraft:plains", "plain", "p", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city_test",
                new BlockBounds(0, 0, 63, 39), 4, cells);
    }

    private static LandUseSeedGroup withTerrainAndPatch(LandUseSeedGroup source,
                                                        LandUseSeedGroup.TerrainBias terrainBias,
                                                        List<String> preferredPatchRefs) {
        return new LandUseSeedGroup(source.groupId(), source.rule(), source.surfaceSettings(), source.anchorIds(),
                source.structureFootprints(), source.seedPoints(), source.gateSlots(), source.minAreaBlocks(),
                source.preferredAreaBlocks(), source.maxAreaBlocks(), source.actionBudget(),
                source.competitionWeight(), source.growthRegions(), source.growthBias(), terrainBias,
                preferredPatchRefs);
    }

    private static LandUseTerrainField roughTerrain() {
        List<LandUseTerrainField.Cell> cells = new java.util.ArrayList<>();
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x < 16; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        70, 20, 0, 0, false, 0, 40,
                        "minecraft:plains", "rough", "rough", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city_test",
                new BlockBounds(0, 0, 63, 39), 4, cells);
    }

    private static LandUseTerrainField patchTerrain() {
        List<LandUseTerrainField.Cell> cells = new java.util.ArrayList<>();
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x < 16; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        70, 0, 0, 0, false, 0, 40,
                        "minecraft:plains", "plain", x < 8 ? "preferred" : "other", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city_test",
                new BlockBounds(0, 0, 63, 39), 4, cells);
    }
}
