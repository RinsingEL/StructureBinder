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
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city_test",
                new BlockBounds(0, 0, 63, 39), 4, cells);
    }
}
