package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityFoundationPlannerTest {
    @Test
    void closesNearbyStructuresIntoOneBroadFoundation() {
        CityFoundationPlanner.Plan plan = new CityFoundationPlanner().plan(bounds(), flatTerrain(),
                List.of(new BlockBounds(10, 20, 14, 24), new BlockBounds(28, 20, 32, 24)),
                new LandUseSeedGroup.FoundationSettings(3, 10, 24));

        assertEquals(1, componentCount(plan.claims()));
        assertEquals(7, plan.minimumBridgeWidthBlocks());
        assertEquals(24, plan.resolvedCloseRadiusBlocks());
        CityFoundationPlanner.Plan repeated = new CityFoundationPlanner().plan(bounds(), flatTerrain(),
                List.of(new BlockBounds(10, 20, 14, 24), new BlockBounds(28, 20, 32, 24)),
                new LandUseSeedGroup.FoundationSettings(3, 10, 24));
        assertEquals(plan.resolvedCloseRadiusBlocks(), repeated.resolvedCloseRadiusBlocks());
    }

    @Test
    void rejectsStructuresOutsideTheConfiguredJoinGraph() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new CityFoundationPlanner().plan(bounds(), flatTerrain(),
                        List.of(new BlockBounds(2, 2, 5, 5), new BlockBounds(40, 40, 43, 43)),
                        new LandUseSeedGroup.FoundationSettings(1, 2, 4)));

        assertTrue(exception.getMessage().startsWith("CITY_FOUNDATION_JOIN_DISTANCE_EXCEEDED:"));
    }

    @Test
    void includesFrozenStructureFootprintEvenWhenItsTerrainCellsAreBlocked() {
        BlockBounds footprint = new BlockBounds(10, 20, 12, 22);

        CityFoundationPlanner.Plan plan = new CityFoundationPlanner().plan(bounds(), blockedStructureTerrain(),
                List.of(footprint), new LandUseSeedGroup.FoundationSettings(0, 0, 0));

        for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
            for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                assertTrue(plan.claims().contains(new BlockPoint(x, z)));
            }
        }
        assertEquals(9, plan.structureBlocks());
    }

    @Test
    void rejectsAOneBlockTerrainBridgeBetweenOtherwiseBroadIslands() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new CityFoundationPlanner().plan(bounds(), corridorTerrain(),
                        List.of(new BlockBounds(4, 4, 8, 8), new BlockBounds(28, 4, 32, 8)),
                        new LandUseSeedGroup.FoundationSettings(1, 16, 32)));

        assertTrue(exception.getMessage().startsWith("CITY_FOUNDATION_THIN_BRIDGE:"));
    }

    private static int componentCount(Set<BlockPoint> points) {
        Set<BlockPoint> remaining = new HashSet<>(points);
        int count = 0;
        while (!remaining.isEmpty()) {
            count++;
            BlockPoint first = remaining.iterator().next();
            ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
            remaining.remove(first);
            queue.add(first);
            while (!queue.isEmpty()) {
                BlockPoint point = queue.removeFirst();
                for (int[] direction : new int[][]{{0, -1}, {-1, 0}, {1, 0}, {0, 1}}) {
                    BlockPoint next = new BlockPoint(point.x() + direction[0], point.z() + direction[1]);
                    if (remaining.remove(next)) queue.addLast(next);
                }
            }
        }
        return count;
    }

    private static BlockBounds bounds() {
        return new BlockBounds(0, 0, 63, 63);
    }

    private static LandUseTerrainField flatTerrain() {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) cells.add(cell(x, z, false));
        }
        return terrain(cells);
    }

    private static LandUseTerrainField corridorTerrain() {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) {
                boolean leftRoom = x >= 2 && x <= 10 && z >= 2 && z <= 10;
                boolean rightRoom = x >= 26 && x <= 34 && z >= 2 && z <= 10;
                boolean corridor = x >= 9 && x <= 27 && z == 6;
                cells.add(cell(x, z, !(leftRoom || rightRoom || corridor)));
            }
        }
        return terrain(cells);
    }

    private static LandUseTerrainField blockedStructureTerrain() {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) {
                boolean blocked = x >= 10 && x <= 12 && z >= 20 && z <= 22;
                cells.add(cell(x, z, blocked));
            }
        }
        return terrain(cells);
    }

    private static LandUseTerrainField terrain(List<LandUseTerrainField.Cell> cells) {
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city", bounds(), 1, cells);
    }

    private static LandUseTerrainField.Cell cell(int x, int z, boolean water) {
        return new LandUseTerrainField.Cell(x, z, x, z, 1, water ? 62 : 70, 0, 0, 0,
                water, water ? 8 : 0, 0, "minecraft:plains", water ? "river" : "plain", "patch", true);
    }
}
