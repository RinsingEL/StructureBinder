package com.user.terra_script.territory.io;

import com.user.terra_script.world.TerritoryManager;
import com.google.gson.JsonObject;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerritoryResultRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void t3DatRoundTripPreservesClaimedAndWildChunks() throws Exception {
        TerritoryManager.TerritoryConfig config = config("han@c7", "han", 7);
        TerritoryManager.TerritoryResult result = result(
                config,
                Set.of(chunk(10, 20), chunk(11, 20)),
                Set.of(chunk(12, 20))
        );

        TerritoryResultRepository.T3WriteResult write = TerritoryResultRepository.writeT3(tempDir, result);
        TerritoryResultRepository.T3DatData dat = TerritoryResultRepository.readT3Dat(tempDir, config.id).orElseThrow();
        TerritoryManager.TerritoryResult loaded =
                TerritoryResultRepository.readT3Result(tempDir, config.id, config).orElseThrow();

        assertTrue(write.canonicalWritten);
        assertFalse(write.skippedCanonicalOverwrite);
        assertEquals(config.id, dat.storedTerritoryId);
        assertEquals(2, dat.claimedCount());
        assertEquals(1, dat.wildCount());
        assertEquals(2, loaded.claimedChunks.size());
        assertEquals(1, loaded.wildChunks.size());
        assertEquals(3L, loaded.stats.area_pixels);
    }

    @Test
    void importT3SupportsDryRunAndWritesTargetArtifacts() throws Exception {
        TerritoryManager.TerritoryConfig sourceConfig = config("r15_test_01", "r15_test_01", 15);
        TerritoryManager.TerritoryResult source = result(
                sourceConfig,
                Set.of(chunk(30, 40), chunk(31, 40)),
                Set.of(chunk(32, 40))
        );
        TerritoryResultRepository.writeT3(tempDir, source);

        TerritoryManager.TerritoryConfig targetConfig = config("r15_test_01@c15", "r15_test_01", 15);
        TerritoryResultRepository.T3ImportResult dryRun =
                TerritoryResultRepository.importT3(tempDir, sourceConfig.id, targetConfig, true);
        TerritoryResultRepository.T3ImportResult imported =
                TerritoryResultRepository.importT3(tempDir, sourceConfig.id, targetConfig, false);

        assertTrue(dryRun.ok);
        assertTrue(dryRun.dryRun);
        assertFalse(dryRun.canonicalWritten);
        assertTrue(imported.ok);
        assertFalse(imported.dryRun);
        assertTrue(imported.canonicalWritten);
        assertEquals(targetConfig.id, imported.importedResult.config.id);
        assertTrue(imported.importedResult.claimedChunks.contains(chunk(30, 40)));
        assertTrue(imported.importedResult.wildChunks.contains(chunk(32, 40)));
        assertTrue(TerritoryResultRepository.readT3Dat(tempDir, targetConfig.id).isPresent());
    }

    @Test
    void zeroAreaWriteKeepsExistingCanonicalT3() throws Exception {
        TerritoryManager.TerritoryConfig config = config("preserve@c9", "preserve", 9);
        TerritoryManager.TerritoryResult existing = result(
                config,
                Set.of(chunk(50, 60), chunk(51, 60)),
                Set.of()
        );
        TerritoryResultRepository.writeT3(tempDir, existing);

        TerritoryManager.TerritoryResult zero = result(config, Set.of(), Set.of());
        zero.stats = stats(0L, 0, 0, 0, 0);

        TerritoryResultRepository.T3WriteResult write = TerritoryResultRepository.writeT3(tempDir, zero);
        TerritoryResultRepository.T3DatData preserved =
                TerritoryResultRepository.readT3Dat(tempDir, config.id).orElseThrow();

        assertFalse(write.canonicalWritten);
        assertTrue(write.diagnosticWritten);
        assertTrue(write.skippedCanonicalOverwrite);
        assertNotNull(write.diagnosticFolder);
        assertTrue(Files.exists(Path.of(write.diagnosticFolder).resolve("TerritorySummary.json")));
        assertEquals(2, preserved.claimedCount());
        assertEquals(0, preserved.wildCount());
    }

    @Test
    void readT3ResultKeepsT3StatsWhenT4SummaryExists() throws Exception {
        TerritoryManager.TerritoryConfig config = config("restore@c1", "restore", 1);
        TerritoryManager.TerritoryResult result = result(
                config,
                Set.of(chunk(-394, -258), chunk(-393, -258)),
                Set.of(chunk(-392, -258))
        );
        TerritoryResultRepository.writeT3(tempDir, result);

        JsonObject t4Summary = new JsonObject();
        t4Summary.addProperty("schema_version", 1);
        t4Summary.addProperty("stage", "T4");
        t4Summary.addProperty("mode", "TERRAIN_SCAN");
        JsonObject territory = new JsonObject();
        territory.addProperty("id", config.id);
        territory.addProperty("region_id", config.regionId);
        t4Summary.add("territory", territory);
        TerritoryResultRepository.writeT4(tempDir, config.id, t4Summary, new byte[]{0, 1, 2, 3});

        TerritoryManager.TerritoryResult loaded =
                TerritoryResultRepository.readT3Result(tempDir, config.id, config).orElseThrow();

        assertEquals(2, loaded.claimedChunks.size());
        assertEquals(1, loaded.wildChunks.size());
        assertEquals(3L, loaded.stats.area_pixels);
        assertTrue(loaded.stats.maxX >= loaded.stats.minX);
        assertTrue(loaded.stats.maxZ >= loaded.stats.minZ);
    }

    private static TerritoryManager.TerritoryConfig config(String instanceId, String territoryId, int continentId) {
        return new TerritoryManager.TerritoryConfig(
                instanceId,
                territoryId,
                territoryId,
                continentId,
                0,
                0,
                0,
                0,
                100,
                35,
                0,
                2.0,
                5.0,
                0xFF0000
        );
    }

    private static TerritoryManager.TerritoryResult result(
            TerritoryManager.TerritoryConfig config,
            Set<Long> claimed,
            Set<Long> wild
    ) {
        TerritoryManager.TerritoryResult result = new TerritoryManager.TerritoryResult(config);
        result.claimedChunks.addAll(claimed);
        result.wildChunks.addAll(wild);
        result.stats = stats(
                claimed.size() + wild.size(),
                claimed,
                wild
        );
        return result;
    }

    private static TerritoryManager.TerritoryStats stats(long area, Set<Long> claimed, Set<Long> wild) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long chunkKey : claimed) {
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            minX = Math.min(minX, chunkX * 16);
            maxX = Math.max(maxX, chunkX * 16 + 15);
            minZ = Math.min(minZ, chunkZ * 16);
            maxZ = Math.max(maxZ, chunkZ * 16 + 15);
        }
        for (long chunkKey : wild) {
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            minX = Math.min(minX, chunkX * 16);
            maxX = Math.max(maxX, chunkX * 16 + 15);
            minZ = Math.min(minZ, chunkZ * 16);
            maxZ = Math.max(maxZ, chunkZ * 16 + 15);
        }
        if (area == 0) {
            minX = 0;
            maxX = 0;
            minZ = 0;
            maxZ = 0;
        }
        return stats(area, minX, maxX, minZ, maxZ);
    }

    private static TerritoryManager.TerritoryStats stats(long area, int minX, int maxX, int minZ, int maxZ) {
        TerritoryManager.TerritoryStats stats = new TerritoryManager.TerritoryStats();
        stats.area_pixels = area;
        stats.minX = minX;
        stats.maxX = maxX;
        stats.minZ = minZ;
        stats.maxZ = maxZ;
        return stats;
    }

    private static long chunk(int x, int z) {
        return ChunkPos.asLong(x, z);
    }
}
