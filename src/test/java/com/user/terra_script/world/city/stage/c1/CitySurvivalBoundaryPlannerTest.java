package com.user.terra_script.world.city.stage.c1;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitySurvivalBoundaryPlannerTest {
    @Test
    void selectsOnlyOwnedNonOverlappingChunks() {
        FakeAccess access = new FakeAccess();
        access.ownSquare("demo", -4, -4, 4, 4);
        access.occupied.put(ChunkPos.asLong(0, 0), "other_city");

        CitySurvivalBoundaryPlanner.Result result = CitySurvivalBoundaryPlanner.plan(request("demo", 0, 0, 12), access);

        assertFalse(result.choices.isEmpty());
        for (CitySurvivalBoundaryPlanner.ChunkChoice choice : result.choices) {
            long key = ChunkPos.asLong(choice.chunkX, choice.chunkZ);
            assertTrue(access.owned.contains(key));
            assertFalse("other_city".equals(access.occupied.get(key)));
        }
    }

    @Test
    void terrainRiskIsSoftTagNotHardReject() {
        FakeAccess access = new FakeAccess();
        access.ownSquare("demo", -3, -3, 3, 3);
        access.water.add(ChunkPos.asLong(0, 0));
        access.rough.add(ChunkPos.asLong(1, 0));

        CitySurvivalBoundaryPlanner.Result result = CitySurvivalBoundaryPlanner.plan(request("demo", 0, 0, 10), access);

        assertFalse(result.choices.isEmpty());
        assertTrue(result.riskTags.contains("water_city_candidate"));
        assertTrue(result.riskTags.contains("requires_terrain_adaptation"));
        assertTrue(result.blockingErrors.isEmpty());
    }

    @Test
    void reanchorsWhenRequestedCenterIsOutsideSovereignty() {
        FakeAccess access = new FakeAccess();
        access.ownSquare("demo", 5, 5, 7, 7);

        CitySurvivalBoundaryPlanner.Result result = CitySurvivalBoundaryPlanner.plan(request("demo", 0, 0, 5), access);

        assertTrue(result.reanchored);
        assertTrue(result.warnings.contains("requested_center_outside_sovereignty"));
        assertTrue(result.warnings.contains("center_reanchored_to_owned_chunk"));
        assertFalse(result.choices.isEmpty());
    }

    @Test
    void returnsFailSafeWhenNoOwnedChunksExist() {
        CitySurvivalBoundaryPlanner.Result result = CitySurvivalBoundaryPlanner.plan(request("demo", 0, 0, 5), new FakeAccess());

        assertEquals("fail_safe", result.status);
        assertTrue(result.fallbackUsed);
        assertTrue(result.blockingErrors.contains("no_owned_non_overlapping_chunks"));
    }

    private static CitySurvivalBoundaryPlanner.Request request(String territoryId, int centerX, int centerZ, int target) {
        CitySurvivalBoundaryPlanner.Request request = new CitySurvivalBoundaryPlanner.Request();
        request.cityId = "city_test";
        request.territoryId = territoryId;
        request.centerX = centerX;
        request.centerZ = centerZ;
        request.targetChunkCount = target;
        return request;
    }

    private static final class FakeAccess implements CitySurvivalBoundaryPlanner.ChunkAccess {
        final Set<Long> owned = new HashSet<>();
        final Set<Long> water = new HashSet<>();
        final Set<Long> rough = new HashSet<>();
        final Map<Long, String> occupied = new HashMap<>();

        void ownSquare(String territoryId, int minX, int minZ, int maxX, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    owned.add(ChunkPos.asLong(x, z));
                }
            }
        }

        @Override
        public boolean isWithinSovereignty(long chunkKey, String territoryId) {
            return owned.contains(chunkKey);
        }

        @Override
        public String cityIdAt(long chunkKey) {
            return occupied.get(chunkKey);
        }

        @Override
        public double terrainRisk(int chunkX, int chunkZ) {
            return rough.contains(ChunkPos.asLong(chunkX, chunkZ)) ? 0.8 : 0.0;
        }

        @Override
        public boolean waterLike(int chunkX, int chunkZ) {
            return water.contains(ChunkPos.asLong(chunkX, chunkZ));
        }

        @Override
        public boolean roughLike(int chunkX, int chunkZ) {
            return rough.contains(ChunkPos.asLong(chunkX, chunkZ));
        }
    }
}
