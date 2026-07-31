package com.rinsing.geomantia.systems.gis.adapter.minecraft;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.gis.application.benchmark.ChunkGenerationBenchmarkJob;
import com.rinsing.geomantia.systems.gis.preview.AtlasJson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MinecraftChunkGenerationBenchmarkService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final TicketType<ChunkPos> BENCHMARK_TICKET = TicketType.create(
            "geomantia_chunk_generation_benchmark", Comparator.comparingLong(ChunkPos::toLong));
    private static final Map<MinecraftServer, MinecraftChunkGenerationBenchmarkService> INSTANCES =
            new IdentityHashMap<>();

    private final MinecraftServer server;
    private final Set<Long> pendingFullConfirmation = new HashSet<>();
    private ChunkGenerationBenchmarkJob activeJob;
    private ChunkGenerationBenchmarkJob lastJob;
    private ServerLevel activeLevel;
    private ChunkPos activeTicketCenter;
    private long lastReportWriteNanos;

    private MinecraftChunkGenerationBenchmarkService(MinecraftServer server) {
        this.server = server;
    }

    public static synchronized MinecraftChunkGenerationBenchmarkService forServer(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, MinecraftChunkGenerationBenchmarkService::new);
    }

    public static synchronized void closeServer(MinecraftServer server) {
        MinecraftChunkGenerationBenchmarkService service = INSTANCES.remove(server);
        if (service != null) {
            service.cancelActive("Server stopping.");
        }
    }

    public synchronized JsonObject start(ServerLevel level, int centerBlockX, int centerBlockZ, int radiusChunks,
            int timeoutSeconds, boolean requireFresh, Path debugRoot) throws IOException {
        requireServerThread();
        if (activeJob != null && !activeJob.terminal()) {
            throw new IllegalArgumentException("GIS_CHUNK_BENCHMARK_ALREADY_RUNNING: " + activeJob.jobId());
        }
        ChunkPos center = new ChunkPos(centerBlockX >> 4, centerBlockZ >> 4);
        List<Long> loadedAtStart = loadedTargets(level, center, radiusChunks);
        if (requireFresh && !loadedAtStart.isEmpty()) {
            throw new IllegalArgumentException("GIS_CHUNK_BENCHMARK_TARGET_ALREADY_LOADED: loadedAtStart="
                    + loadedAtStart.size());
        }

        long startedAtEpochMs = System.currentTimeMillis();
        long startedAtNanos = System.nanoTime();
        String jobId = "chunkgen_" + Long.toUnsignedString(startedAtEpochMs, 36)
                + "_" + UUID.randomUUID().toString().substring(0, 8);
        Path reportPath = debugRoot.resolve("chunk_generation_benchmark").resolve(jobId)
                .resolve("chunk_generation_baseline_report.json");
        Files.createDirectories(reportPath.getParent());
        ChunkGenerationBenchmarkJob job = new ChunkGenerationBenchmarkJob(
                jobId,
                level.dimension().location().toString(),
                Long.toString(level.getSeed()),
                level.getChunkSource().getGenerator().getClass().getName(),
                center.x,
                center.z,
                radiusChunks,
                timeoutSeconds,
                startedAtEpochMs,
                startedAtNanos,
                reportPath);
        for (long chunkPos : loadedAtStart) {
            job.recordLoadedAtStart(chunkPos);
        }

        activeJob = job;
        activeLevel = level;
        activeTicketCenter = center;
        pendingFullConfirmation.clear();
        lastReportWriteNanos = startedAtNanos;
        writeReport(job, startedAtEpochMs, startedAtNanos);
        try {
            level.getChunkSource().addRegionTicket(BENCHMARK_TICKET, center, radiusChunks, center);
            job.markTicketAcquired();
            writeReport(job, System.currentTimeMillis(), System.nanoTime());
        } catch (RuntimeException ex) {
            job.fail(ex.getMessage(), System.currentTimeMillis(), System.nanoTime());
            writeReport(job, System.currentTimeMillis(), System.nanoTime());
            lastJob = job;
            clearActiveState();
            throw ex;
        }
        LOGGER.info("Started GIS chunk generation benchmark {} in {} at chunk {},{} radius={} targets={}",
                jobId, job.dimensionId(), center.x, center.z, radiusChunks, job.targetChunkCount());
        return job.asJson(System.currentTimeMillis(), System.nanoTime());
    }

    public synchronized JsonObject status(String jobId) {
        ChunkGenerationBenchmarkJob job = findJob(jobId);
        if (job == null) {
            throw new IllegalArgumentException("GIS_CHUNK_BENCHMARK_NOT_FOUND: " + jobId);
        }
        return job.asJson(System.currentTimeMillis(), System.nanoTime());
    }

    public synchronized void onChunkLoad(ServerLevel level, ChunkPos chunkPos, boolean newChunk) {
        ChunkGenerationBenchmarkJob job = activeJob;
        if (job == null || job.terminal() || level != activeLevel || !job.isTarget(chunkPos.toLong())) {
            return;
        }
        job.recordLoad(chunkPos.toLong(), newChunk);
        pendingFullConfirmation.add(chunkPos.toLong());
    }

    public synchronized void tick() {
        requireServerThread();
        ChunkGenerationBenchmarkJob job = activeJob;
        ServerLevel level = activeLevel;
        if (job == null || level == null || job.terminal()) {
            return;
        }
        long nowNanos = System.nanoTime();
        long nowEpochMs = System.currentTimeMillis();
        var iterator = pendingFullConfirmation.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.next();
            int chunkX = ChunkPos.getX(packed);
            int chunkZ = ChunkPos.getZ(packed);
            if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
                job.recordFull(packed, nowNanos);
                iterator.remove();
            }
        }

        if (job.completedChunkCount() >= job.targetChunkCount()) {
            job.complete(nowEpochMs, nowNanos);
            finishActive(job, nowEpochMs, nowNanos);
            return;
        }
        if (nowNanos - job.startedAtNanos() >= job.timeoutSeconds() * 1_000_000_000L) {
            job.timeout(nowEpochMs, nowNanos);
            finishActive(job, nowEpochMs, nowNanos);
            return;
        }
        if (nowNanos - lastReportWriteNanos >= 1_000_000_000L) {
            writeReportQuietly(job, nowEpochMs, nowNanos);
            lastReportWriteNanos = nowNanos;
        }
    }

    public synchronized void cancelActive(String message) {
        ChunkGenerationBenchmarkJob job = activeJob;
        if (job == null || job.terminal()) {
            return;
        }
        long nowEpochMs = System.currentTimeMillis();
        long nowNanos = System.nanoTime();
        job.cancel(message, nowEpochMs, nowNanos);
        finishActive(job, nowEpochMs, nowNanos);
    }

    private void finishActive(ChunkGenerationBenchmarkJob job, long nowEpochMs, long nowNanos) {
        removeActiveTicket();
        writeReportQuietly(job, nowEpochMs, nowNanos);
        JsonObject report = job.asJson(nowEpochMs, nowNanos);
        JsonObject progress = report.getAsJsonObject("progress");
        JsonObject timings = report.getAsJsonObject("timings");
        LOGGER.info("GIS chunk generation benchmark {} finished status={} coldValid={} chunks={}/{} "
                        + "p50Ms={} p95Ms={} p100Ms={} chunksPerSecond={}",
                job.jobId(), job.status(), report.get("coldBaselineValid").getAsBoolean(),
                progress.get("completedChunks").getAsInt(), job.targetChunkCount(),
                timingValue(timings, "p50Ms"), timingValue(timings, "p95Ms"),
                timingValue(timings, "p100Ms"), timings.get("chunksPerSecond").getAsDouble());
        lastJob = job;
        clearActiveState();
    }

    private void removeActiveTicket() {
        if (activeLevel == null || activeTicketCenter == null || activeJob == null) {
            return;
        }
        activeLevel.getChunkSource().removeRegionTicket(
                BENCHMARK_TICKET, activeTicketCenter, activeJob.radiusChunks(), activeTicketCenter);
        activeJob.markTicketReleased();
    }

    private List<Long> loadedTargets(ServerLevel level, ChunkPos center, int radiusChunks) {
        List<Long> loaded = new ArrayList<>();
        for (int chunkX = center.x - radiusChunks; chunkX <= center.x + radiusChunks; chunkX++) {
            for (int chunkZ = center.z - radiusChunks; chunkZ <= center.z + radiusChunks; chunkZ++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
                    loaded.add(ChunkPos.asLong(chunkX, chunkZ));
                }
            }
        }
        return loaded;
    }

    private ChunkGenerationBenchmarkJob findJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return activeJob != null ? activeJob : lastJob;
        }
        if (activeJob != null && jobId.equals(activeJob.jobId())) {
            return activeJob;
        }
        if (lastJob != null && jobId.equals(lastJob.jobId())) {
            return lastJob;
        }
        return null;
    }

    private void clearActiveState() {
        activeJob = null;
        activeLevel = null;
        activeTicketCenter = null;
        pendingFullConfirmation.clear();
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("GIS chunk generation benchmark must run on the server thread.");
        }
    }

    private static void writeReport(ChunkGenerationBenchmarkJob job, long nowEpochMs, long nowNanos)
            throws IOException {
        Files.writeString(job.reportPath(), AtlasJson.GSON.toJson(job.asJson(nowEpochMs, nowNanos)));
    }

    private static void writeReportQuietly(ChunkGenerationBenchmarkJob job, long nowEpochMs, long nowNanos) {
        try {
            writeReport(job, nowEpochMs, nowNanos);
        } catch (IOException ex) {
            LOGGER.warn("Failed to write GIS chunk generation benchmark report {}", job.reportPath(), ex);
        }
    }

    private static long timingValue(JsonObject timings, String key) {
        return timings.has(key) ? timings.get(key).getAsLong() : -1L;
    }
}
