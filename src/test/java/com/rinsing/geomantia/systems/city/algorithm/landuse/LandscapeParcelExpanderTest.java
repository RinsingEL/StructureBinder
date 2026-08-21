package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandscapeParcelExpanderTest {
    @Test
    void openParcelStopsAtPreferredAreaInsteadOfFillingMaximum() {
        LandUseTerrainField terrain = flatTerrain();
        LandUseSeedGroup group = group("farm::parcel_01", new BlockPoint(48, 48), 64, 173, 320);

        LandUseExpansionResult result = new LandscapeParcelExpander().expand("city_test",
                terrain.planningBounds(), terrain, List.of(group), "shape-a");

        assertEquals(173, result.claimedBlocksByGroup().get(group.groupId()));
        assertEquals(173, result.claimedBlocksByGrowthRegion().get(group.groupId()));
        assertEquals(173, result.claims().size());
        assertTrue(isConnected(result.claims().keySet()));
    }

    @Test
    void actualExpansionStopsAtAbruptElevationBand() {
        LandUseTerrainField terrain = terrainWithCliff(48);
        LandUseSeedGroup group = group("farm::parcel_01", new BlockPoint(40, 48), 64, 400, 500);

        LandUseExpansionResult result = new LandscapeParcelExpander().expand("city_test",
                terrain.planningBounds(), terrain, List.of(group), "cliff");

        assertEquals(400, result.claimedBlocksByGroup().get(group.groupId()));
        assertTrue(result.claims().keySet().stream().noneMatch(point -> point.x() >= 48),
                "D6 Landscape expansion crossed an abrupt elevation band");
    }

    @Test
    void stableSaltProducesDeterministicButDifferentOrganicSilhouettes() {
        LandUseTerrainField terrain = flatTerrain();
        BlockPoint seed = new BlockPoint(48, 48);
        LandUseSeedGroup group = group("meadow::parcel_01", seed, 64, 211, 320);
        LandscapeParcelExpander expander = new LandscapeParcelExpander();

        LandUseExpansionResult first = expander.expand("city_test", terrain.planningBounds(), terrain,
                List.of(group), "shape-a");
        LandUseExpansionResult repeated = expander.expand("city_test", terrain.planningBounds(), terrain,
                List.of(group), "shape-a");
        LandUseExpansionResult different = expander.expand("city_test", terrain.planningBounds(), terrain,
                List.of(group), "shape-b");

        assertEquals(first.claims(), repeated.claims());
        assertNotEquals(first.claims().keySet(), different.claims().keySet());
        long asymmetricCells = first.claims().keySet().stream()
                .filter(point -> !first.claims().containsKey(new BlockPoint(
                        seed.x() * 2 - point.x(), seed.z() * 2 - point.z())))
                .count();
        assertTrue(asymmetricCells >= 12, "Landscape silhouette must not collapse to a symmetric distance ball");
    }

    @Test
    void requiredParcelCompetitionIsIndependentOfInputOrder() {
        LandUseTerrainField terrain = flatTerrain();
        LandUseSeedGroup west = group("farm::west", new BlockPoint(28, 48), 64, 180, 300);
        LandUseSeedGroup east = group("farm::east", new BlockPoint(68, 48), 64, 180, 300);
        LandscapeParcelExpander expander = new LandscapeParcelExpander();

        LandUseExpansionResult forward = expander.expand("city_test", terrain.planningBounds(), terrain,
                List.of(west, east), "competition");
        LandUseExpansionResult reversed = expander.expand("city_test", terrain.planningBounds(), terrain,
                List.of(east, west), "competition");

        assertEquals(forward.claims(), reversed.claims());
        assertEquals(180, forward.claimedBlocksByGroup().get(west.groupId()));
        assertEquals(180, forward.claimedBlocksByGroup().get(east.groupId()));
    }

    @Test
    void frozenBranchTopologyRelaysFromDeclaredParentInsteadOfPreviousOrdinal() {
        LandUseTerrainField terrain = flatTerrain();
        String rootId = "fields::instance_01::parcel_01";
        String eastId = "fields::instance_01::parcel_02";
        String southId = "fields::instance_01::parcel_03";
        LandUseSeedGroup root = group(rootId, new BlockPoint(10, 10), 4, 4, 4);
        LandUseSeedGroup east = group(eastId, new BlockPoint(12, 10), 4, 4, 4);
        LandUseSeedGroup south = group(southId, new BlockPoint(0, 0), 4, 4, 4);
        Map<String, Set<BlockPoint>> domains = Map.of(
                rootId, Set.of(new BlockPoint(10, 10), new BlockPoint(11, 10),
                        new BlockPoint(10, 11), new BlockPoint(11, 11)),
                eastId, Set.of(new BlockPoint(12, 10), new BlockPoint(13, 10),
                        new BlockPoint(12, 11), new BlockPoint(13, 11)),
                southId, Set.of(new BlockPoint(10, 12), new BlockPoint(11, 12),
                        new BlockPoint(10, 13), new BlockPoint(11, 13)));

        LandUseExpansionResult result = new LandscapeParcelExpander().expand("city_test",
                terrain.planningBounds(), terrain, List.of(root, east, south), "branch", Set.of(), domains,
                Map.of(rootId, "", eastId, rootId, southId, rootId));

        assertEquals(4, result.claimedBlocksByGroup().get(rootId));
        assertEquals(4, result.claimedBlocksByGroup().get(eastId));
        assertEquals(4, result.claimedBlocksByGroup().get(southId));
        BlockPoint southEffectiveSeed = result.effectiveSeedPointsByGroup().get(southId).get(0);
        assertNotEquals(south.seedPoints().get(0), southEffectiveSeed);
        assertEquals(southId, result.claims().get(southEffectiveSeed).groupId());
        LandUseExpansionResult.ExpansionOrigin southOrigin = result.expansionOriginsByGroup().get(southId);
        assertEquals(LandUseExpansionResult.OriginKind.PARENT_PARCEL_INTERFACE, southOrigin.kind());
        assertEquals(rootId, southOrigin.parentGroupId());
        assertEquals(southEffectiveSeed, southOrigin.start());
        Set<BlockPoint> rootClaims = result.claims().entrySet().stream()
                .filter(entry -> entry.getValue().groupId().equals(rootId))
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        assertTrue(rootClaims.contains(southOrigin.sourceFrontier()));
        assertTrue(adjacent(southOrigin.sourceFrontier(), southEffectiveSeed));

        LandUseAreaPlan.Area southArea = new LandUseGeometryCompiler().compile(
                        terrain.planningBounds(), List.of(root, east, south), result).areas().stream()
                .filter(area -> area.sourceGroupIds().equals(List.of(southId))).findFirst().orElseThrow();
        assertEquals(List.of(southEffectiveSeed), southArea.seedPoints());
    }

    @Test
    void declaredParentCannotFallBackToConfiguredSeedWhenItsInterfaceIsUnavailable() {
        LandUseTerrainField terrain = flatTerrain();
        String rootId = "fields::instance_01::parcel_01";
        String childId = "fields::instance_01::parcel_02";
        LandUseSeedGroup root = group(rootId, new BlockPoint(10, 10), 4, 4, 4);
        LandUseSeedGroup child = group(childId, new BlockPoint(20, 20), 4, 4, 4);
        Map<String, Set<BlockPoint>> domains = Map.of(
                rootId, Set.of(new BlockPoint(10, 10), new BlockPoint(11, 10),
                        new BlockPoint(10, 11), new BlockPoint(11, 11)),
                childId, Set.of(new BlockPoint(20, 20), new BlockPoint(21, 20),
                        new BlockPoint(20, 21), new BlockPoint(21, 21)));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new LandscapeParcelExpander().expand("city_test", terrain.planningBounds(), terrain,
                        List.of(root, child), "no-fallback", Set.of(), domains,
                        Map.of(rootId, "", childId, rootId)));

        assertEquals("CITY_LANDSCAPE_PARENT_INTERFACE_EXHAUSTED:" + childId + ':' + rootId,
                failure.getMessage());
    }

    @Test
    void corridorFillProgramLeavesOneTerrainFollowingRoadGapBetweenParcels() {
        LandUseTerrainField terrain = flatTerrain();
        String rootId = "fields::instance_01::parcel_01";
        String childId = "fields::instance_01::parcel_02";
        LandscapeFillProgram program = new LandscapeFillProgram("fill:natural_fields", "CULTIVATED",
                List.of(
                        new LandscapeFillProgram.RoleDefinition("CULTIVATED",
                                LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT,
                                LandscapeFillProgram.GrowthForm.PATCH, 0.85),
                        new LandscapeFillProgram.RoleDefinition("GROUND_PATH",
                                LandscapeFillProgram.MaterialRole.GROUND,
                                LandscapeFillProgram.GrowthForm.CORRIDOR, 0.15)),
                List.of(), 7L);
        LandUseSeedGroup root = group(rootId, new BlockPoint(10, 10), 2, 2, 2, program);
        LandUseSeedGroup child = group(childId, new BlockPoint(13, 10), 2, 2, 2, program);
        Map<String, Set<BlockPoint>> domains = Map.of(
                rootId, Set.of(new BlockPoint(10, 10), new BlockPoint(11, 10)),
                childId, Set.of(new BlockPoint(12, 10), new BlockPoint(13, 10),
                        new BlockPoint(14, 10)));

        LandUseExpansionResult result = new LandscapeParcelExpander().expand("city_test",
                terrain.planningBounds(), terrain, List.of(root, child), "natural-gap", Set.of(), domains,
                Map.of(rootId, "", childId, rootId));

        LandUseExpansionResult.ExpansionOrigin origin = result.expansionOriginsByGroup().get(childId);
        assertEquals(LandUseExpansionResult.OriginKind.PARENT_PARCEL_ROAD_GAP, origin.kind());
        assertEquals(new BlockPoint(13, 10), origin.start());
        assertEquals(new BlockPoint(11, 10), origin.sourceFrontier());
        assertTrue(!result.claims().containsKey(new BlockPoint(12, 10)),
                "one unclaimed terrain-following cell must remain as the parcel road gap");
    }

    private static LandUseSeedGroup group(String groupId,
                                          BlockPoint seed,
                                          int minimum,
                                          int preferred,
                                          int maximum) {
        LandUseRule rule = new LandUseRule("agriculture", "Agriculture", List.of("agriculture"),
                1, 0, minimum, maximum, 600, 1, 0, 0, 5, 0, 1, false,
                SurfacePolicy.CULTIVATE, VegetationPolicy.CLEAR, BoundaryPolicy.FENCE, "agriculture");
        LandUseSeedGroup.GrowthRegion region = new LandUseSeedGroup.GrowthRegion(groupId,
                List.of(groupId), List.of(seed), minimum, preferred, maximum);
        return new LandUseSeedGroup(groupId, rule, LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE),
                List.of(groupId), List.of(), List.of(seed), List.of(), minimum, preferred, maximum,
                600, 1, List.of(region), LandUseSeedGroup.GrowthBias.neutral(),
                LandUseSeedGroup.TerrainBias.BALANCED, List.of(), LandUseSeedGroup.LayerRole.LANDSCAPE,
                null, null, LandUseSeedGroup.AdmissionPolicy.REQUIRED);
    }

    private static LandUseSeedGroup group(String groupId,
                                          BlockPoint seed,
                                          int minimum,
                                          int preferred,
                                          int maximum,
                                          LandscapeFillProgram program) {
        LandUseSeedGroup source = group(groupId, seed, minimum, preferred, maximum);
        return new LandUseSeedGroup(source.groupId(), source.rule(), source.surfaceSettings(), source.anchorIds(),
                source.structureFootprints(), source.seedPoints(), source.gateSlots(), source.minAreaBlocks(),
                source.preferredAreaBlocks(), source.maxAreaBlocks(), source.actionBudget(),
                source.competitionWeight(), source.growthRegions(), source.growthBias(), source.terrainBias(),
                source.preferredPatchRefs(), source.layerRole(), source.foundationSettings(), program,
                source.admissionPolicy());
    }

    private static LandUseTerrainField flatTerrain() {
        return terrainWithCliff(Integer.MAX_VALUE);
    }

    private static LandUseTerrainField terrainWithCliff(int cliffX) {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = 0; z < 24; z++) {
            for (int x = 0; x < 24; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        x * 4 >= cliffX ? 86 : 70, 0, 0, 0, false, 0, 40,
                        "minecraft:plains", "plain", "plain", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city_test",
                new BlockBounds(0, 0, 95, 95), 4, cells);
    }

    private static boolean isConnected(Set<BlockPoint> cells) {
        if (cells.isEmpty()) return true;
        Set<BlockPoint> visited = new HashSet<>();
        ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
        queue.add(cells.iterator().next());
        while (!queue.isEmpty()) {
            BlockPoint current = queue.removeFirst();
            if (!visited.add(current)) continue;
            for (int[] direction : new int[][]{{0, -1}, {-1, 0}, {1, 0}, {0, 1}}) {
                BlockPoint next = new BlockPoint(current.x() + direction[0], current.z() + direction[1]);
                if (cells.contains(next) && !visited.contains(next)) queue.addLast(next);
            }
        }
        return visited.size() == cells.size();
    }

    private static boolean adjacent(BlockPoint first, BlockPoint second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z()) == 1;
    }
}
