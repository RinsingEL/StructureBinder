package com.user.terra_script.domain.territory.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.core.stage.StageBase;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.core.workflow.FileStageStatusStore;
import com.user.terra_script.event.ServerTickTracker;
import com.user.terra_script.territory.io.TerritoryResultRepository;
import com.user.terra_script.world.TerritoryManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.core.BlockPos;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class T4Stage extends StageBase {
    private static final int COLUMNS_PER_TICK_BATCH = 512;
    public static final class RuntimeOptions {
        public final int sampleStride;
        public final int maxChunksPerTerritory;
        public final boolean loadedOnly;

        public RuntimeOptions(
                int sampleStride,
                int maxChunksPerTerritory,
                boolean loadedOnly
        ) {
            this.sampleStride = Math.max(1, Math.min(16, sampleStride));
            this.maxChunksPerTerritory = maxChunksPerTerritory;
            this.loadedOnly = loadedOnly;
        }

        public static RuntimeOptions defaults() {
            return new RuntimeOptions(2, -1, false);
        }

        public static RuntimeOptions autoTriggerDefaults() {
            return new RuntimeOptions(4, 256, true);
        }
    }

    private static volatile RuntimeOptions RUNTIME_OPTIONS = RuntimeOptions.defaults();

    public static void configure(RuntimeOptions options) {
        RUNTIME_OPTIONS = options == null ? RuntimeOptions.defaults() : options;
    }

    @Override
    public String id() {
        return "T4";
    }

    @Override
    public List<String> dependsOn() {
        return List.of();
    }

    @Override
    protected void execute(StageContext ctx) throws Exception {
        runLegacyTerrainScan(ctx, RUNTIME_OPTIONS);
    }

    private static void runLegacyTerrainScan(StageContext ctx, RuntimeOptions options) throws Exception {
        TerritoryManager.ensureLoaded();
        TerritoryManager.restoreT3ResultsFromDisk(ctx.server);
        List<TerritoryManager.TerritoryResult> results = new ArrayList<>(TerritoryManager.getAllResults());
        if (results.isEmpty()) {
            throw new IllegalStateException("T4 requires territory results from T3");
        }
        JsonObject stageStart = new JsonObject();
        stageStart.addProperty("territory_count", results.size());
        stageStart.addProperty("sample_stride", options.sampleStride);
        stageStart.addProperty("max_chunks_per_territory", options.maxChunksPerTerritory);
        stageStart.addProperty("loaded_only", options.loadedOnly);
        TStageTraceLogger.stage(ctx, "T4", "stage_started", stageStart);
        ServerLevel level = ctx.server.overworld();
        if (level == null) {
            throw new IllegalStateException("T4 requires overworld");
        }
        long totalBlocks = estimateTotalBlocksFromChunks(level, results, options.sampleStride, options.maxChunksPerTerritory, options.loadedOnly);
        if (totalBlocks <= 0) {
            if (options.loadedOnly) {
                sendProgress(ctx.server, "T4 warning: loaded_only=true and no loaded territory chunks found. " +
                        "Load target area chunks first or set loaded_only=false.");
            }
            totalBlocks = 1L;
        }
        ProgressTracker progress = new ProgressTracker(ctx, "T4", totalBlocks);
        progress.start();
        sendProgress(ctx.server,
                "T4 options: sample_stride=" + options.sampleStride
                        + ", max_chunks_per_territory=" + options.maxChunksPerTerritory
                        + ", loaded_only=" + options.loadedOnly);

        int succeeded = 0;
        int failed = 0;
        JsonArray failures = new JsonArray();

        for (TerritoryManager.TerritoryResult result : results) {
            String territoryId = result != null && result.config != null ? result.config.id : null;
            if (territoryId == null || territoryId.isBlank()) continue;
            JsonObject territoryStart = new JsonObject();
            territoryStart.addProperty("territory_id", territoryId);
            territoryStart.addProperty("loaded_only", options.loadedOnly);
            territoryStart.addProperty("sample_stride", options.sampleStride);
            TStageTraceLogger.territory(ctx, "T4", territoryId, "territory_started", territoryStart);
            try {
                Analysis analysis = analyzeTerritory(level, result, progress, options);
                TerritoryResultRepository.writeT4(ctx.server, territoryId, analysis.summary, analysis.datBytes);
                JsonObject territoryDone = new JsonObject();
                territoryDone.addProperty("territory_id", territoryId);
                territoryDone.addProperty("processed_block_count", analysis.processedBlockCount);
                territoryDone.addProperty("empty", analysis.summary.has("empty") && analysis.summary.get("empty").getAsBoolean());
                if (analysis.summary.has("reason")) territoryDone.addProperty("reason", analysis.summary.get("reason").getAsString());
                TStageTraceLogger.territory(ctx, "T4", territoryId, "territory_completed", territoryDone);
                succeeded++;
            } catch (Exception e) {
                failed++;
                JsonObject err = new JsonObject();
                err.addProperty("territory_id", territoryId);
                err.addProperty("error", e.getMessage());
                failures.add(err);
                TStageTraceLogger.territory(ctx, "T4", territoryId, "territory_failed", err);
            }
        }
        progress.finish();

        JsonObject batch = new JsonObject();
        batch.addProperty("step", "T4");
        batch.addProperty("mode", "TERRAIN_SCAN");
        batch.addProperty("triggered_by", "T3");
        batch.addProperty("territories_total", results.size());
        batch.addProperty("territories_succeeded", succeeded);
        batch.addProperty("territories_failed", failed);
        if (failures.size() > 0) batch.add("failures", failures);
        TerritoryResultRepository.writeT4BatchReport(ctx.server, batch);
        TStageTraceLogger.stage(ctx, "T4", "stage_completed", batch);

        if (succeeded <= 0) {
            StringBuilder sb = new StringBuilder("T4 failed for all territories");
            if (failures.size() > 0) {
                sb.append(": ");
                int limit = Math.min(3, failures.size());
                for (int i = 0; i < limit; i++) {
                    JsonObject f = failures.get(i).getAsJsonObject();
                    if (i > 0) sb.append(" | ");
                    sb.append(f.get("territory_id").getAsString())
                            .append(" -> ")
                            .append(f.get("error").getAsString());
                }
            }
            throw new IllegalStateException(sb.toString());
        }
    }

    private static Analysis analyzeTerritory(
            ServerLevel level,
            TerritoryManager.TerritoryResult result,
            ProgressTracker progress,
            RuntimeOptions options
    ) throws Exception {
        String territoryId = result.config.id;
        Set<Long> chunkKeys = new HashSet<>();
        if (result.claimedChunks != null) chunkKeys.addAll(result.claimedChunks);
        if (result.wildChunks != null) chunkKeys.addAll(result.wildChunks);

        int step = Math.max(1, options.sampleStride);

        if (chunkKeys.isEmpty()) {
            JsonObject root = new JsonObject();
            root.addProperty("schema_version", 1);
            root.addProperty("stage", "T4");
            root.addProperty("mode", "TERRAIN_SCAN");
            root.addProperty("empty", true);
            root.addProperty("reason", "no_claimed_chunks_in_t3_result");

            JsonObject territory = new JsonObject();
            territory.addProperty("id", result.config.id);
            territory.addProperty("name", result.config.name);
            territory.addProperty("region_id", result.config.regionId);
            territory.addProperty("capital_x", result.config.capitalX);
            territory.addProperty("capital_z", result.config.capitalZ);
            root.add("territory", territory);

            JsonObject analysis = new JsonObject();
            analysis.addProperty("border_security", "UNKNOWN");
            analysis.addProperty("hinterland_depth", "UNKNOWN");
            analysis.addProperty("max_distance_to_border", 0);
            analysis.addProperty("max_distance_to_capital", 0);
            analysis.add("choke_points", new JsonArray());
            root.add("analysis", analysis);

            return new Analysis(root, encodeDat(result.config.id, step, List.of()), 0L);
        }

        Set<Long> ownedColumns = new HashSet<>(Math.max(256, chunkKeys.size() * 256));
        Map<Long, Integer> heights = new HashMap<>(Math.max(256, chunkKeys.size() * 256));
        Map<Long, Float> temperatures = new HashMap<>(Math.max(256, chunkKeys.size() * 256));
        Map<Long, String> biomesAt = new HashMap<>(Math.max(256, chunkKeys.size() * 256));
        List<Long> orderedChunks = new ArrayList<>(chunkKeys);
        orderedChunks.sort(Comparator
                .comparingLong((Long key) -> chunkDistanceSqToCapital(key, result.config.capitalX, result.config.capitalZ))
                .thenComparingLong(Long::longValue));
        int chunkLimit = options.maxChunksPerTerritory > 0
                ? Math.min(options.maxChunksPerTerritory, orderedChunks.size())
                : orderedChunks.size();
        List<BlockColumn> columns = new ArrayList<>();
        for (int ci = 0; ci < chunkLimit; ci++) {
            long chunkKey = orderedChunks.get(ci);
            int cx = ChunkPos.getX(chunkKey);
            int cz = ChunkPos.getZ(chunkKey);
            if (options.loadedOnly && !level.hasChunk(cx, cz)) {
                continue;
            }
            int baseX = cx * 16;
            int baseZ = cz * 16;
            for (int dx = 0; dx < 16; dx += step) {
                for (int dz = 0; dz < 16; dz += step) {
                    columns.add(new BlockColumn(baseX + dx, baseZ + dz, cx, cz));
                }
            }
        }

        scanColumnsWithTickBudget(level, columns, ownedColumns, heights, temperatures, biomesAt, progress, options.loadedOnly);

        if (ownedColumns.isEmpty()) {
            JsonObject root = new JsonObject();
            root.addProperty("schema_version", 1);
            root.addProperty("stage", "T4");
            root.addProperty("mode", "TERRAIN_SCAN");
            root.addProperty("empty", true);
            root.addProperty("reason", options.loadedOnly
                    ? "no_columns_collected_from_chunks_loaded_only"
                    : "no_columns_collected_from_chunks");
            JsonObject territory = new JsonObject();
            territory.addProperty("id", result.config.id);
            territory.addProperty("name", result.config.name);
            territory.addProperty("region_id", result.config.regionId);
            territory.addProperty("capital_x", result.config.capitalX);
            territory.addProperty("capital_z", result.config.capitalZ);
            root.add("territory", territory);
            return new Analysis(root, encodeDat(result.config.id, step, List.of()), 0L);
        }

        Map<Long, Integer> distBorder = computeBorderDistances(ownedColumns);
        Map<Long, Integer> distCapital = computeCapitalDistances(ownedColumns, result.config.capitalX, result.config.capitalZ);
        int maxBorder = maxDistance(distBorder.values());
        int maxCapital = maxDistance(distCapital.values());

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long sumHeight = 0;
        double sumSlope = 0.0;
        double sumTemp = 0.0;
        Map<String, Integer> biomeCounts = new HashMap<>();
        List<CellRecord> records = new ArrayList<>(ownedColumns.size());
        List<ChokePoint> chokePoints = new ArrayList<>();

        for (long key : ownedColumns) {
            int worldX = unpackX(key);
            int worldZ = unpackZ(key);
            int height = heights.getOrDefault(key, 0);
            double slope = localSlope(ownedColumns, heights, worldX, worldZ);
            float temp = temperatures.getOrDefault(key, 0.0f);
            String biomeId = biomesAt.getOrDefault(key, "unknown");
            int dBorder = Math.max(0, distBorder.getOrDefault(key, 0));
            int dCapital = Math.max(0, distCapital.getOrDefault(key, 0));
            int strategic = strategicValue(dBorder, maxBorder, dCapital, maxCapital);

            records.add(new CellRecord(worldX, worldZ, height, (float) slope, temp, dBorder, dCapital, strategic));

            if (worldX < minX) minX = worldX;
            if (worldX > maxX) maxX = worldX;
            if (worldZ < minZ) minZ = worldZ;
            if (worldZ > maxZ) maxZ = worldZ;
            sumHeight += height;
            sumSlope += slope;
            sumTemp += temp;
            biomeCounts.merge(biomeId, 1, Integer::sum);

            if (dBorder <= Math.max(2, 8 / step) && slope >= 3.0) {
                chokePoints.add(new ChokePoint(worldX, worldZ, slope));
            }
        }

        records.sort(Comparator.comparingInt((CellRecord r) -> r.worldX).thenComparingInt(r -> r.worldZ));
        chokePoints.sort(Comparator.comparingDouble((ChokePoint c) -> c.slope).reversed());
        if (chokePoints.size() > 5) chokePoints = chokePoints.subList(0, 5);

        int n = Math.max(1, records.size());
        double avgHeight = (double) sumHeight / n;
        double avgSlope = sumSlope / n;
        double avgTemp = sumTemp / n;

        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.addProperty("stage", "T4");
        root.addProperty("mode", "TERRAIN_SCAN");

        JsonObject territory = new JsonObject();
        territory.addProperty("id", result.config.id);
        territory.addProperty("name", result.config.name);
        territory.addProperty("region_id", result.config.regionId);
        territory.addProperty("capital_x", result.config.capitalX);
        territory.addProperty("capital_z", result.config.capitalZ);
        root.add("territory", territory);

        JsonObject bbox = new JsonObject();
        bbox.addProperty("min_x", minX);
        bbox.addProperty("max_x", maxX);
        bbox.addProperty("min_z", minZ);
        bbox.addProperty("max_z", maxZ);
        root.add("bbox", bbox);

        JsonObject grid = new JsonObject();
        grid.addProperty("sample_step", step);
        grid.addProperty("max_chunks_per_territory", options.maxChunksPerTerritory);
        grid.addProperty("loaded_only", options.loadedOnly);
        grid.addProperty("cell_count", records.size());
        grid.addProperty("estimated_block_count", records.size() * (long) step * step);
        root.add("grid", grid);

        JsonObject terrain = new JsonObject();
        terrain.addProperty("avg_height", round3(avgHeight));
        terrain.addProperty("avg_slope", round3(avgSlope));
        terrain.addProperty("avg_temperature", round3(avgTemp));
        JsonObject biomes = new JsonObject();
        biomeComposition(biomeCounts, n).forEach(biomes::addProperty);
        terrain.add("biome_composition", biomes);
        root.add("terrain", terrain);

        JsonObject strategy = new JsonObject();
        strategy.addProperty("border_security", borderSecurity(avgSlope));
        strategy.addProperty("hinterland_depth", hinterlandDepth(maxBorder));
        strategy.addProperty("max_distance_to_border", maxBorder);
        strategy.addProperty("max_distance_to_capital", maxCapital);
        JsonArray choke = new JsonArray();
        for (ChokePoint cp : chokePoints) {
            JsonObject item = new JsonObject();
            item.addProperty("x", cp.x);
            item.addProperty("z", cp.z);
            item.addProperty("slope", round3(cp.slope));
            choke.add(item);
        }
        strategy.add("choke_points", choke);
        root.add("analysis", strategy);

        long processedBlockCount = records.size();
        return new Analysis(root, encodeDat(result.config.id, step, records), processedBlockCount);
    }

    private static long estimateTotalBlocksFromChunks(
            ServerLevel level,
            List<TerritoryManager.TerritoryResult> results,
            int sampleStride,
            int maxChunksPerTerritory,
            boolean loadedOnly
    ) {
        long totalSamples = 0L;
        int step = Math.max(1, sampleStride);
        long samplesPerChunk = (long) samplesPerAxis(step) * samplesPerAxis(step);
        for (TerritoryManager.TerritoryResult result : results) {
            if (result == null) continue;
            Set<Long> chunks = new HashSet<>();
            if (result.claimedChunks != null) chunks.addAll(result.claimedChunks);
            if (result.wildChunks != null) chunks.addAll(result.wildChunks);
            int count = chunks.size();
            if (maxChunksPerTerritory > 0) count = Math.min(count, maxChunksPerTerritory);
            if (!loadedOnly) {
                totalSamples += (long) count * samplesPerChunk;
                continue;
            }

            int loadedCount = 0;
            int scanned = 0;
            for (Long key : chunks) {
                if (key == null) continue;
                if (scanned >= count) break;
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                if (level.hasChunk(cx, cz)) loadedCount++;
                scanned++;
            }
            totalSamples += (long) loadedCount * samplesPerChunk;
        }
        return totalSamples;
    }

    private static void sendProgress(MinecraftServer server, String message) {
        String full = "[TerraScript][T4] " + message;
        System.out.println(full);
        if (server == null || server.getPlayerList() == null) return;
        server.getPlayerList().broadcastSystemMessage(Component.literal(full), false);
    }

    private static byte[] encodeDat(String territoryId, int step, List<CellRecord> records) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(1);
            dos.writeUTF(territoryId);
            dos.writeInt(step);
            dos.writeInt(records.size());
            for (CellRecord r : records) {
                dos.writeInt(r.worldX);
                dos.writeInt(r.worldZ);
                dos.writeShort(r.height);
                dos.writeFloat(r.slope);
                dos.writeFloat(r.temperature);
                dos.writeShort(Math.min(Short.MAX_VALUE, r.distBorder));
                dos.writeShort(Math.min(Short.MAX_VALUE, r.distCapital));
                dos.writeByte(Math.max(0, Math.min(255, r.strategic)));
            }
            dos.flush();
            return baos.toByteArray();
        }
    }

    private static int strategicValue(int distBorder, int maxBorder, int distCapital, int maxCapital) {
        double borderNorm = maxBorder <= 0 ? 0.0 : (double) distBorder / maxBorder;
        double capitalNorm = maxCapital <= 0 ? 0.0 : (double) distCapital / maxCapital;
        double score = 0.6 * borderNorm + 0.4 * (1.0 - capitalNorm);
        return (int) Math.round(Math.max(0.0, Math.min(1.0, score)) * 255.0);
    }

    private static double localSlope(Set<Long> ownedColumns, Map<Long, Integer> heights, int x, int z) {
        long self = packBlock(x, z);
        if (!ownedColumns.contains(self)) return 0.0;
        int baseH = heights.getOrDefault(self, 0);
        int[][] dirs = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        double sum = 0.0;
        int count = 0;
        for (int[] d : dirs) {
            long nk = packBlock(x + d[0], z + d[1]);
            if (!ownedColumns.contains(nk)) continue;
            int nh = heights.getOrDefault(nk, baseH);
            sum += Math.abs(baseH - nh);
            count++;
        }
        if (count == 0) return 0.0;
        return sum / count;
    }

    private static Map<Long, Integer> computeBorderDistances(Set<Long> ownedColumns) {
        Map<Long, Integer> dist = new HashMap<>(Math.max(256, ownedColumns.size() * 2));
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (long key : ownedColumns) {
            if (isBorderCell(ownedColumns, key)) {
                dist.put(key, 0);
                queue.add(key);
            }
        }
        bfsOwned(ownedColumns, dist, queue);
        return dist;
    }

    private static Map<Long, Integer> computeCapitalDistances(Set<Long> ownedColumns, int capitalX, int capitalZ) {
        Map<Long, Integer> dist = new HashMap<>(Math.max(256, ownedColumns.size() * 2));
        ArrayDeque<Long> queue = new ArrayDeque<>();
        long start = snapToOwned(ownedColumns, capitalX, capitalZ);
        dist.put(start, 0);
        queue.add(start);
        bfsOwned(ownedColumns, dist, queue);
        return dist;
    }

    private static boolean isBorderCell(Set<Long> ownedColumns, long key) {
        int x = unpackX(key);
        int z = unpackZ(key);
        int[][] dirs = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            long nk = packBlock(x + d[0], z + d[1]);
            if (!ownedColumns.contains(nk)) return true;
        }
        return false;
    }

    private static void bfsOwned(Set<Long> ownedColumns, Map<Long, Integer> dist, ArrayDeque<Long> q) {
        int[][] dirs = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            long key = q.poll();
            int x = unpackX(key);
            int z = unpackZ(key);
            int base = dist.getOrDefault(key, 0);
            for (int[] d : dirs) {
                long nk = packBlock(x + d[0], z + d[1]);
                if (!ownedColumns.contains(nk)) continue;
                if (dist.containsKey(nk)) continue;
                dist.put(nk, base + 1);
                q.add(nk);
            }
        }
    }

    private static long snapToOwned(Set<Long> ownedColumns, int startX, int startZ) {
        long start = packBlock(startX, startZ);
        if (ownedColumns.contains(start)) return start;
        int bestX = startX;
        int bestZ = startZ;
        int bestDist = Integer.MAX_VALUE;
        for (long key : ownedColumns) {
            int x = unpackX(key);
            int z = unpackZ(key);
            int dx = x - startX;
            int dz = z - startZ;
            int dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                bestX = x;
                bestZ = z;
            }
        }
        return packBlock(bestX, bestZ);
    }

    private static int maxDistance(Iterable<Integer> distValues) {
        int max = 0;
        for (int d : distValues) {
            if (d > max) max = d;
        }
        return max;
    }

    private static Map<String, Double> biomeComposition(Map<String, Integer> counts, int total) {
        Map<String, Double> out = new HashMap<>();
        if (counts == null || counts.isEmpty() || total <= 0) return out;
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        int limit = Math.min(8, entries.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> e = entries.get(i);
            double ratio = (double) e.getValue() / total;
            if (ratio < 0.01) continue;
            out.put(e.getKey(), round3(ratio));
        }
        return out;
    }

    private static void scanColumnsWithTickBudget(
            ServerLevel level,
            List<BlockColumn> columns,
            Set<Long> ownedColumns,
            Map<Long, Integer> heights,
            Map<Long, Float> temperatures,
            Map<Long, String> biomesAt,
            ProgressTracker progress,
            boolean loadedOnly
    ) throws Exception {
        if (columns == null || columns.isEmpty()) return;
        MinecraftServer server = level.getServer();
        boolean sameThread = server != null && server.isSameThread();

        if (!loadedOnly) {
            // Fast path: sample directly from generator/noise, no chunk loading.
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            RandomState randomState = level.getChunkSource().randomState();
            for (int i = 0; i < columns.size(); i++) {
                ColumnSample sample = readColumnSampleFromGenerator(level, generator, randomState, columns.get(i));
                long key = packBlock(sample.worldX, sample.worldZ);
                ownedColumns.add(key);
                heights.put(key, sample.height);
                temperatures.put(key, sample.temperature);
                biomesAt.put(key, sample.biomeId);
                progress.onBlockScanned();
            }
            return;
        }

        int total = columns.size();
        if (sameThread) {
            // Fallback: when called from server thread command path, avoid deadlock.
            for (int i = 0; i < total; i++) {
                ColumnSample sample = readColumnSampleLoaded(level, columns.get(i));
                long key = packBlock(sample.worldX, sample.worldZ);
                ownedColumns.add(key);
                heights.put(key, sample.height);
                temperatures.put(key, sample.temperature);
                biomesAt.put(key, sample.biomeId);
                progress.onBlockScanned();
            }
            return;
        }

        long lastTick = ServerTickTracker.currentTick();
        for (int from = 0; from < total; from += COLUMNS_PER_TICK_BATCH) {
            int to = Math.min(total, from + COLUMNS_PER_TICK_BATCH);
            List<BlockColumn> batch = columns.subList(from, to);

            CompletableFuture<List<ColumnSample>> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    List<ColumnSample> samples = new ArrayList<>(batch.size());
                    for (BlockColumn col : batch) {
                        samples.add(readColumnSampleLoaded(level, col));
                    }
                    future.complete(samples);
                } catch (Exception ex) {
                    future.completeExceptionally(ex);
                }
            });

            List<ColumnSample> samples = future.get(10, TimeUnit.MINUTES);
            for (ColumnSample sample : samples) {
                long key = packBlock(sample.worldX, sample.worldZ);
                ownedColumns.add(key);
                heights.put(key, sample.height);
                temperatures.put(key, sample.temperature);
                biomesAt.put(key, sample.biomeId);
                progress.onBlockScanned();
            }

            // Explicitly yield one tick between batches to reduce main-thread stalls.
            lastTick = waitNextTick(lastTick);
        }
    }

    private static ColumnSample readColumnSampleLoaded(ServerLevel level, BlockColumn col) {
        level.getChunk(col.chunkX, col.chunkZ, ChunkStatus.FULL, true);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.worldX, col.worldZ);
        BlockPos pos = new BlockPos(col.worldX, y, col.worldZ);
        var biomeHolder = level.getBiome(pos);
        float temp = biomeHolder.value().getBaseTemperature();
        String biomeId = biomeHolder.unwrapKey()
                .map(k -> k.location().toString())
                .orElse("unknown");
        return new ColumnSample(col.worldX, col.worldZ, y, temp, safeBiomeId(biomeId));
    }

    private static ColumnSample readColumnSampleFromGenerator(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            BlockColumn col
    ) {
        int y = generator.getBaseHeight(col.worldX, col.worldZ, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
        Holder<Biome> biomeHolder = generator.getBiomeSource()
                .getNoiseBiome(col.worldX >> 2, y >> 2, col.worldZ >> 2, randomState.sampler());
        float temp = biomeHolder.value().getBaseTemperature();
        String biomeId = biomeHolder.unwrapKey()
                .map(k -> k.location().toString())
                .orElse("unknown");
        return new ColumnSample(col.worldX, col.worldZ, y, temp, safeBiomeId(biomeId));
    }

    private static long waitNextTick(long lastTick) throws InterruptedException {
        long tick = lastTick;
        while (tick <= lastTick) {
            Thread.sleep(2L);
            tick = ServerTickTracker.currentTick();
        }
        return tick;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    private static String safeBiomeId(String biomeId) {
        if (biomeId == null || biomeId.isBlank()) return "unknown";
        return biomeId;
    }

    private static int samplesPerAxis(int step) {
        return ((16 - 1) / step) + 1;
    }

    private static long chunkDistanceSqToCapital(long chunkKey, int capitalX, int capitalZ) {
        int cx = ChunkPos.getX(chunkKey);
        int cz = ChunkPos.getZ(chunkKey);
        int centerX = (cx << 4) + 8;
        int centerZ = (cz << 4) + 8;
        long dx = (long) centerX - capitalX;
        long dz = (long) centerZ - capitalZ;
        return dx * dx + dz * dz;
    }

    private static String borderSecurity(double avgSlope) {
        if (avgSlope >= 5.0) return "HIGH";
        if (avgSlope >= 2.5) return "MEDIUM";
        return "LOW";
    }

    private static String hinterlandDepth(int maxBorderDist) {
        if (maxBorderDist >= 60) return "DEEP";
        if (maxBorderDist >= 25) return "MEDIUM";
        return "SHALLOW";
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static final class Analysis {
        final JsonObject summary;
        final byte[] datBytes;
        final long processedBlockCount;

        Analysis(JsonObject summary, byte[] datBytes, long processedBlockCount) {
            this.summary = summary;
            this.datBytes = datBytes;
            this.processedBlockCount = processedBlockCount;
        }
    }

    private static final class BlockColumn {
        final int worldX;
        final int worldZ;
        final int chunkX;
        final int chunkZ;

        BlockColumn(int worldX, int worldZ, int chunkX, int chunkZ) {
            this.worldX = worldX;
            this.worldZ = worldZ;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    private static final class ColumnSample {
        final int worldX;
        final int worldZ;
        final int height;
        final float temperature;
        final String biomeId;

        ColumnSample(int worldX, int worldZ, int height, float temperature, String biomeId) {
            this.worldX = worldX;
            this.worldZ = worldZ;
            this.height = height;
            this.temperature = temperature;
            this.biomeId = biomeId;
        }
    }


    private static final class ProgressTracker {
        private final StageContext ctx;
        private final String stageId;
        private final MinecraftServer server;
        private final long totalBlocks;
        private long scannedBlocks = 0L;
        private int nextProgressPct = 25;
        private long lastHeartbeatNanos = 0L;
        private static final long HEARTBEAT_INTERVAL_NANOS = 30_000_000_000L;

        private ProgressTracker(StageContext ctx, String stageId, long totalBlocks) {
            this.ctx = ctx;
            this.stageId = stageId;
            this.server = ctx != null ? ctx.server : null;
            this.totalBlocks = Math.max(1L, totalBlocks);
        }

        synchronized void start() {
            String message = "T4 scan started. total_blocks=" + totalBlocks + ", progress=0%";
            sendProgress(server, message);
            syncStatus(message);
            lastHeartbeatNanos = System.nanoTime();
        }

        synchronized void onBlockScanned() {
            scannedBlocks++;
            maybeHeartbeat();
            while (nextProgressPct <= 100 && scannedBlocks * 100 >= totalBlocks * (long) nextProgressPct) {
                String message = "T4 scan progress " + nextProgressPct + "% (" + scannedBlocks + "/" + totalBlocks + " blocks)";
                sendProgress(server, message);
                syncStatus(message);
                nextProgressPct += 25;
            }
        }

        synchronized void onBlocksScanned(long count) {
            if (count <= 0) return;
            scannedBlocks += count;
            maybeHeartbeat();
            while (nextProgressPct <= 100 && scannedBlocks * 100 >= totalBlocks * (long) nextProgressPct) {
                String message = "T4 scan progress " + nextProgressPct + "% (" + scannedBlocks + "/" + totalBlocks + " blocks)";
                sendProgress(server, message);
                syncStatus(message);
                nextProgressPct += 25;
            }
        }

        synchronized void finish() {
            if (nextProgressPct <= 100) {
                String message = "T4 scan progress 100% (" + scannedBlocks + "/" + totalBlocks + " blocks)";
                sendProgress(server, message);
                syncStatus(message);
            }
        }

        private void maybeHeartbeat() {
            long now = System.nanoTime();
            if (now - lastHeartbeatNanos < HEARTBEAT_INTERVAL_NANOS) return;
            int pct = (int) Math.min(99, (scannedBlocks * 100L) / totalBlocks);
            String message = "T4 scan heartbeat " + pct + "% (" + scannedBlocks + "/" + totalBlocks + " blocks)";
            sendProgress(server, message);
            syncStatus(message);
            lastHeartbeatNanos = now;
        }

        private void syncStatus(String message) {
            if (ctx == null || !(ctx.statusStore instanceof FileStageStatusStore store)) return;
            try {
                store.updateProgress(stageId, ctx, message, scannedBlocks, totalBlocks, "t4_legacy_scan");
            } catch (Exception ignored) {
            }
        }
    }

    private static final class CellRecord {
        final int worldX;
        final int worldZ;
        final int height;
        final float slope;
        final float temperature;
        final int distBorder;
        final int distCapital;
        final int strategic;

        CellRecord(int worldX, int worldZ, int height, float slope, float temperature, int distBorder, int distCapital, int strategic) {
            this.worldX = worldX;
            this.worldZ = worldZ;
            this.height = height;
            this.slope = slope;
            this.temperature = temperature;
            this.distBorder = distBorder;
            this.distCapital = distCapital;
            this.strategic = strategic;
        }
    }

    private static final class ChokePoint {
        final int x;
        final int z;
        final double slope;

        ChokePoint(int x, int z, double slope) {
            this.x = x;
            this.z = z;
            this.slope = slope;
        }
    }
}

