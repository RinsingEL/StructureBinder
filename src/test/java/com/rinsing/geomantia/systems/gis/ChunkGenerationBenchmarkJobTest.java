package com.rinsing.geomantia.systems.gis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.gis.application.benchmark.ChunkGenerationBenchmarkJob;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkGenerationBenchmarkJobTest {
    @TempDir
    Path tempDir;

    @Test
    void viewDistance32UsesSixtyFiveBySixtyFiveTarget() {
        ChunkGenerationBenchmarkJob job = job(100, -200, 32);

        assertEquals(4225, job.targetChunkCount());
        assertEquals(68, job.minChunkX());
        assertEquals(132, job.maxChunkX());
        assertEquals(-232, job.minChunkZ());
        assertEquals(-168, job.maxChunkZ());
        assertTrue(job.isTarget(ChunkPos.asLong(68, -232)));
        assertTrue(job.isTarget(ChunkPos.asLong(132, -168)));
        assertFalse(job.isTarget(ChunkPos.asLong(133, -168)));

        JsonObject json = job.asJson(1_000L, 1_000_000_000L);
        assertTrue(json.get("ok").getAsBoolean());
        assertFalse(json.get("terminal").getAsBoolean());
        assertEquals(65, json.get("diameterChunks").getAsInt());
        assertEquals(4225, json.get("targetChunkCount").getAsInt());
    }

    @Test
    void recordsCompletionPercentilesAndValidColdBaseline() {
        ChunkGenerationBenchmarkJob job = job(0, 0, 1);
        int recorded = 0;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                long packed = ChunkPos.asLong(x, z);
                job.recordLoad(packed, true);
                recorded++;
                job.recordFull(packed, recorded <= 5 ? 3_000_000_000L : 5_000_000_000L);
            }
        }
        job.complete(5_100L, 5_100_000_000L);

        JsonObject json = job.asJson(5_100L, 5_100_000_000L);
        assertTrue(json.get("terminal").getAsBoolean());
        JsonObject timings = json.getAsJsonObject("timings");
        assertEquals(2000L, timings.get("p50Ms").getAsLong());
        assertEquals(4000L, timings.get("p95Ms").getAsLong());
        assertEquals(4000L, timings.get("p100Ms").getAsLong());
        assertEquals(9, json.getAsJsonObject("progress").get("newChunkCount").getAsInt());
        assertTrue(json.get("coldBaselineValid").getAsBoolean());
        assertTrue(json.getAsJsonArray("invalidReasons").isEmpty());
    }

    @Test
    void reportsTicketLifecycle() {
        ChunkGenerationBenchmarkJob job = job(0, 0, 0);
        job.markTicketAcquired();
        JsonObject running = job.asJson(1_500L, 1_500_000_000L);
        assertTrue(running.get("ticketActive").getAsBoolean());
        assertFalse(running.get("ticketReleased").getAsBoolean());

        job.markTicketReleased();
        JsonObject released = job.asJson(1_600L, 1_600_000_000L);
        assertFalse(released.get("ticketActive").getAsBoolean());
        assertTrue(released.get("ticketReleased").getAsBoolean());
    }

    @Test
    void existingChunkInvalidatesColdBaseline() {
        ChunkGenerationBenchmarkJob job = job(0, 0, 0);
        long target = ChunkPos.asLong(0, 0);
        job.recordLoad(target, false);
        job.recordFull(target, 2_000_000_000L);
        job.complete(2_000L, 2_000_000_000L);

        JsonObject json = job.asJson(2_000L, 2_000_000_000L);
        JsonArray invalidReasons = json.getAsJsonArray("invalidReasons");
        assertFalse(json.get("coldBaselineValid").getAsBoolean());
        assertEquals("TARGET_CHUNKS_LOADED_FROM_DISK", invalidReasons.get(0).getAsString());
    }

    private ChunkGenerationBenchmarkJob job(int centerChunkX, int centerChunkZ, int radiusChunks) {
        return new ChunkGenerationBenchmarkJob(
                "chunkgen_test",
                "minecraft:overworld",
                "12345",
                "test.Generator",
                centerChunkX,
                centerChunkZ,
                radiusChunks,
                900,
                1_000L,
                1_000_000_000L,
                tempDir.resolve("chunk_generation_baseline_report.json"));
    }
}
