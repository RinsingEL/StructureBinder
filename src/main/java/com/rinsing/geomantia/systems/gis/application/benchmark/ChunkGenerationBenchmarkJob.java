package com.rinsing.geomantia.systems.gis.application.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.ChunkPos;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public final class ChunkGenerationBenchmarkJob {
    public static final String SCHEMA = "gis_chunk_generation_benchmark";

    private final String jobId;
    private final String dimensionId;
    private final String worldSeed;
    private final String generatorClass;
    private final int centerChunkX;
    private final int centerChunkZ;
    private final int radiusChunks;
    private final int timeoutSeconds;
    private final long startedAtEpochMs;
    private final long startedAtNanos;
    private final Path reportPath;
    private final Set<Long> observedLoads = new HashSet<>();
    private final Set<Long> completedChunks = new HashSet<>();
    private int newChunkCount;
    private int existingChunkCount;
    private int loadedAtStartCount;
    private long p50Ms = -1L;
    private long p95Ms = -1L;
    private long p100Ms = -1L;
    private long finishedAtEpochMs;
    private long finishedAtNanos;
    private String status = "running";
    private String errorMessage = "";
    private boolean ticketActive;
    private boolean ticketReleased;

    public ChunkGenerationBenchmarkJob(String jobId, String dimensionId, String worldSeed, String generatorClass,
            int centerChunkX, int centerChunkZ, int radiusChunks, int timeoutSeconds,
            long startedAtEpochMs, long startedAtNanos, Path reportPath) {
        this.jobId = jobId;
        this.dimensionId = dimensionId;
        this.worldSeed = worldSeed;
        this.generatorClass = generatorClass;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.radiusChunks = radiusChunks;
        this.timeoutSeconds = timeoutSeconds;
        this.startedAtEpochMs = startedAtEpochMs;
        this.startedAtNanos = startedAtNanos;
        this.reportPath = reportPath;
    }

    public synchronized boolean isTarget(long chunkPos) {
        int chunkX = ChunkPos.getX(chunkPos);
        int chunkZ = ChunkPos.getZ(chunkPos);
        return chunkX >= minChunkX() && chunkX <= maxChunkX()
                && chunkZ >= minChunkZ() && chunkZ <= maxChunkZ();
    }

    public synchronized void recordLoadedAtStart(long chunkPos) {
        if (!isTarget(chunkPos) || !completedChunks.add(chunkPos)) {
            return;
        }
        loadedAtStartCount++;
        existingChunkCount++;
        observedLoads.add(chunkPos);
        updateMilestones(startedAtNanos);
    }

    public synchronized void recordLoad(long chunkPos, boolean newChunk) {
        if (!isTarget(chunkPos) || !observedLoads.add(chunkPos)) {
            return;
        }
        if (newChunk) {
            newChunkCount++;
        } else {
            existingChunkCount++;
        }
    }

    public synchronized boolean recordFull(long chunkPos, long nowNanos) {
        if (!isTarget(chunkPos) || !completedChunks.add(chunkPos)) {
            return false;
        }
        updateMilestones(nowNanos);
        return true;
    }

    public synchronized void complete(long nowEpochMs, long nowNanos) {
        status = "completed";
        finishedAtEpochMs = nowEpochMs;
        finishedAtNanos = nowNanos;
        if (p100Ms < 0L && completedChunks.size() >= targetChunkCount()) {
            p100Ms = elapsedMs(nowNanos);
        }
    }

    public synchronized void timeout(long nowEpochMs, long nowNanos) {
        status = "timed_out";
        finishedAtEpochMs = nowEpochMs;
        finishedAtNanos = nowNanos;
        errorMessage = "Target chunks did not reach FULL before timeout.";
    }

    public synchronized void fail(String message, long nowEpochMs, long nowNanos) {
        status = "failed";
        finishedAtEpochMs = nowEpochMs;
        finishedAtNanos = nowNanos;
        errorMessage = message == null ? "" : message;
    }

    public synchronized void cancel(String message, long nowEpochMs, long nowNanos) {
        status = "cancelled";
        finishedAtEpochMs = nowEpochMs;
        finishedAtNanos = nowNanos;
        errorMessage = message == null ? "" : message;
    }

    public synchronized void markTicketAcquired() {
        ticketActive = true;
        ticketReleased = false;
    }

    public synchronized void markTicketReleased() {
        ticketActive = false;
        ticketReleased = true;
    }

    public synchronized JsonObject asJson(long nowEpochMs, long nowNanos) {
        long elapsedMs = elapsedMs(terminal() ? finishedAtNanos : nowNanos);
        int completed = completedChunks.size();
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("ok", "running".equals(status) || "completed".equals(status));
        root.addProperty("jobId", jobId);
        root.addProperty("status", status);
        root.addProperty("terminal", terminal());
        root.addProperty("errorMessage", errorMessage);
        root.addProperty("dimensionId", dimensionId);
        root.addProperty("worldSeed", worldSeed);
        root.addProperty("generatorClass", generatorClass);
        root.addProperty("centerChunkX", centerChunkX);
        root.addProperty("centerChunkZ", centerChunkZ);
        root.addProperty("centerBlockX", centerChunkX * 16 + 8);
        root.addProperty("centerBlockZ", centerChunkZ * 16 + 8);
        root.addProperty("radiusChunks", radiusChunks);
        root.addProperty("diameterChunks", radiusChunks * 2 + 1);
        root.addProperty("targetChunkCount", targetChunkCount());
        root.addProperty("timeoutSeconds", timeoutSeconds);
        root.addProperty("ticketActive", ticketActive);
        root.addProperty("ticketReleased", ticketReleased);
        root.addProperty("startedAt", Instant.ofEpochMilli(startedAtEpochMs).toString());
        root.addProperty("updatedAt", Instant.ofEpochMilli(nowEpochMs).toString());
        if (terminal()) {
            root.addProperty("finishedAt", Instant.ofEpochMilli(finishedAtEpochMs).toString());
        }
        root.addProperty("elapsedMs", elapsedMs);
        root.add("targetBounds", targetBoundsJson());

        JsonObject progress = new JsonObject();
        progress.addProperty("completedChunks", completed);
        progress.addProperty("remainingChunks", Math.max(0, targetChunkCount() - completed));
        progress.addProperty("progressPercent", targetChunkCount() == 0
                ? 100.0 : completed * 100.0 / targetChunkCount());
        progress.addProperty("observedLoadCount", observedLoads.size());
        progress.addProperty("newChunkCount", newChunkCount);
        progress.addProperty("existingChunkCount", existingChunkCount);
        progress.addProperty("loadedAtStartCount", loadedAtStartCount);
        progress.addProperty("unclassifiedFullCount", Math.max(0, completed - observedLoads.size()));
        root.add("progress", progress);

        JsonObject timings = new JsonObject();
        addOptionalTiming(timings, "p50Ms", p50Ms);
        addOptionalTiming(timings, "p95Ms", p95Ms);
        addOptionalTiming(timings, "p100Ms", p100Ms);
        timings.addProperty("chunksPerSecond", elapsedMs <= 0L ? 0.0 : completed * 1000.0 / elapsedMs);
        root.add("timings", timings);

        boolean coldBaselineValid = "completed".equals(status)
                && loadedAtStartCount == 0
                && existingChunkCount == 0
                && newChunkCount == targetChunkCount()
                && observedLoads.size() == targetChunkCount();
        root.addProperty("coldBaselineValid", coldBaselineValid);
        JsonArray invalidReasons = new JsonArray();
        if (loadedAtStartCount > 0) {
            invalidReasons.add("TARGET_CHUNKS_LOADED_AT_START");
        }
        if (existingChunkCount > 0) {
            invalidReasons.add("TARGET_CHUNKS_LOADED_FROM_DISK");
        }
        if (terminal() && observedLoads.size() < targetChunkCount()) {
            invalidReasons.add("TARGET_CHUNK_LOAD_EVENTS_INCOMPLETE");
        }
        if (!"completed".equals(status)) {
            invalidReasons.add("BENCHMARK_NOT_COMPLETED");
        }
        root.add("invalidReasons", invalidReasons);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("chunkGenerationBaselineReport", reportPath.toAbsolutePath().toString());
        root.add("artifacts", artifacts);
        return root;
    }

    public synchronized String jobId() {
        return jobId;
    }

    public synchronized String dimensionId() {
        return dimensionId;
    }

    public synchronized int radiusChunks() {
        return radiusChunks;
    }

    public synchronized int centerChunkX() {
        return centerChunkX;
    }

    public synchronized int centerChunkZ() {
        return centerChunkZ;
    }

    public synchronized int minChunkX() {
        return centerChunkX - radiusChunks;
    }

    public synchronized int maxChunkX() {
        return centerChunkX + radiusChunks;
    }

    public synchronized int minChunkZ() {
        return centerChunkZ - radiusChunks;
    }

    public synchronized int maxChunkZ() {
        return centerChunkZ + radiusChunks;
    }

    public synchronized int targetChunkCount() {
        int diameter = radiusChunks * 2 + 1;
        return diameter * diameter;
    }

    public synchronized int completedChunkCount() {
        return completedChunks.size();
    }

    public synchronized int timeoutSeconds() {
        return timeoutSeconds;
    }

    public synchronized long startedAtNanos() {
        return startedAtNanos;
    }

    public synchronized boolean terminal() {
        return !"running".equals(status);
    }

    public synchronized String status() {
        return status;
    }

    public synchronized Path reportPath() {
        return reportPath;
    }

    private void updateMilestones(long nowNanos) {
        int completed = completedChunks.size();
        long elapsed = elapsedMs(nowNanos);
        if (p50Ms < 0L && completed >= threshold(0.50)) {
            p50Ms = elapsed;
        }
        if (p95Ms < 0L && completed >= threshold(0.95)) {
            p95Ms = elapsed;
        }
        if (p100Ms < 0L && completed >= targetChunkCount()) {
            p100Ms = elapsed;
        }
    }

    private int threshold(double fraction) {
        return (int) Math.ceil(targetChunkCount() * fraction);
    }

    private long elapsedMs(long nowNanos) {
        return Math.max(0L, (nowNanos - startedAtNanos) / 1_000_000L);
    }

    private JsonObject targetBoundsJson() {
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minChunkX", minChunkX());
        bounds.addProperty("maxChunkX", maxChunkX());
        bounds.addProperty("minChunkZ", minChunkZ());
        bounds.addProperty("maxChunkZ", maxChunkZ());
        bounds.addProperty("minBlockX", minChunkX() * 16);
        bounds.addProperty("maxBlockX", maxChunkX() * 16 + 15);
        bounds.addProperty("minBlockZ", minChunkZ() * 16);
        bounds.addProperty("maxBlockZ", maxChunkZ() * 16 + 15);
        return bounds;
    }

    private static void addOptionalTiming(JsonObject timings, String key, long value) {
        if (value >= 0L) {
            timings.addProperty(key, value);
        }
    }
}
