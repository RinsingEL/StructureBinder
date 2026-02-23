package com.user.terra_script.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.data.ScanResultHolder.RegionCache;
import com.user.terra_script.domain.world.scan.ScanPixel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TerritoryManager {

    // --- 基础配置类 ---
    public static class TerritoryConfig {
        public String id;
        public String name;
        public int regionId;
        public int capitalX, capitalZ;
        public int maxPower;
        public double mountainCost;
        public double waterCost;
        public int color;

        public TerritoryConfig(String id, String name, int regionId, int x, int z, int power, double mCost, double wCost, int color) {
            this.id = id; this.name = name; this.regionId = regionId;
            this.capitalX = x; this.capitalZ = z;
            this.maxPower = power; this.mountainCost = mCost; this.waterCost = wCost; this.color = color;
        }
    }

    // --- 结果容器类 ---
    public static class TerritoryResult {
        public TerritoryConfig config;
        public Set<Long> claimedChunks = new HashSet<>();
        public Set<Long> wildChunks = new HashSet<>();
        public TerritoryStats stats; // 详细统计数据
        public TerritoryResult(TerritoryConfig config) { this.config = config; }
    }

    // --- 【新增】详细统计信息类 ---
    // 这个类的字段直接对应 JSON 里的内容
    public static class TerritoryStats {
        public long area_pixels = 0;
        public int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        public int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        // 统计 Map
        public Map<Integer, Integer> rawContinentCounts = new HashMap<>(); // 原始计数
        public Map<String, Integer> rawBiomeCounts = new HashMap<>();      // 原始计数
        public Set<String> neighborIds = new HashSet<>();                  // 邻居 ID 集合

        // 最终输出给 API 的百分比数据 (在分析结束后计算)
        public Map<Integer, Double> continent_distribution = new HashMap<>();
        public Map<String, Double> biome_composition = new HashMap<>();
    }

    private static class RegionTerrainAggregate {
        public final double[][] slopeAvg;
        public final double[][] landRatio;
        public final boolean[][] hasData;

        public RegionTerrainAggregate(double[][] slopeAvg, double[][] landRatio, boolean[][] hasData) {
            this.slopeAvg = slopeAvg;
            this.landRatio = landRatio;
            this.hasData = hasData;
        }
    }

    private static final List<TerritoryConfig> registeredFactions = new ArrayList<>();
    private static final Map<String, TerritoryResult> results = new ConcurrentHashMap<>();

    // 全局像素归属图 (用于快速判定接壤)
    public static String[][] globalOwnershipMap = null;
    private static final int HIGH_RES_EXPANSION_STEP = 16;
    private static int expansionStepBlocks = 1;
    private static int expansionMinX = 0;
    private static int expansionMinZ = 0;
    private static ScanPixel[][] expansionScanMap = null;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("terra_script_territories.json").toFile();

    public static Collection<TerritoryResult> getAllResults() { return results.values(); }
    public static int getExpansionStepBlocks() { return Math.max(1, expansionStepBlocks); }
    public static int getExpansionMinX() { return expansionMinX; }
    public static int getExpansionMinZ() { return expansionMinZ; }
    public static ScanPixel[][] getExpansionScanMap() { return expansionScanMap; }
    public static List<TerritoryConfig> getRegisteredFactions() { return new ArrayList<>(registeredFactions); }
    public static TerritoryConfig getTerritoryConfig(String id) {
        if (id == null) return null;
        for (TerritoryConfig cfg : registeredFactions) {
            if (id.equals(cfg.id)) return cfg;
        }
        return null;
    }

    private static class ExpansionGrid {
        public final ScanPixel[][] map;
        public final int step;
        public final int minX;
        public final int minZ;
        public final int coarseStep;

        public ExpansionGrid(ScanPixel[][] map, int step, int minX, int minZ, int coarseStep) {
            this.map = map;
            this.step = step;
            this.minX = minX;
            this.minZ = minZ;
            this.coarseStep = coarseStep;
        }
    }

    // --- 核心操作方法 ---

    public static void createTerritory(String id, String name, int regionId, int startX, int startZ, int maxPower, double mCost, double wCost, int color) {
        registeredFactions.removeIf(c -> c.id.equals(id));
        registeredFactions.add(new TerritoryConfig(id, name, regionId, startX, startZ, maxPower, mCost, wCost, color));
        save();
    }

    public static void ensureLoaded() {
        if (registeredFactions.isEmpty() && CONFIG_FILE.exists()) load();
    }

    public static void refresh() {
        runExpansion();
    }

    public static void runExpansion() {
        ensureLoaded();
        recalculateAll();
    }

    public static void clear() {
        registeredFactions.clear();
        results.clear();
        globalOwnershipMap = null;
        expansionScanMap = null;
        expansionStepBlocks = 1;
        expansionMinX = 0;
        expansionMinZ = 0;
        save();
    }

    // --- 计算逻辑主入口 ---
    private static void recalculateAll() {
        results.clear();
        var holder = ScanResultHolder.get();
        if (holder.lastScanData != null) {
            computeGlobal(holder);
            analyzeTerritories(holder); // 计算完立刻分析
        } else {
            System.out.println("[Territory] No global scan data found.");
        }
    }

    // --- 领土扩张算法 (Dijkstra) ---
    private static void computeGlobal(ScanResultHolder holder) {
        ExpansionGrid grid = buildExpansionGrid(holder);
        ScanPixel[][] map = grid.map;
        int[][] clusterMap = holder.lastClusterMap;
        int w = map.length;
        int h = map[0].length;
        int step = grid.step;
        int globalMinX = grid.minX;
        int globalMinZ = grid.minZ;
        int coarseStep = grid.coarseStep;

        expansionScanMap = map;
        expansionStepBlocks = step;
        expansionMinX = globalMinX;
        expansionMinZ = globalMinZ;

        globalOwnershipMap = new String[w][h];

        for (TerritoryConfig cfg : registeredFactions) {
            results.put(cfg.id, new TerritoryResult(cfg));
        }

        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        double[][] distMap = new double[w][h];
        for (double[] row : distMap) Arrays.fill(row, Double.MAX_VALUE);
        Map<Integer, RegionTerrainAggregate> regionAggregates =
                buildRegionTerrainAggregates(holder, w, h, globalMinX, globalMinZ, step);

        // 1. 种子点
        for (TerritoryConfig cfg : registeredFactions) {
            int seedX = (cfg.capitalX - globalMinX) / step;
            int seedZ = (cfg.capitalZ - globalMinZ) / step;
            int[] snapped = snapSeedToRegion(
                    seedX, seedZ, cfg.regionId, map, clusterMap,
                    globalMinX, globalMinZ, step, coarseStep);
            int gx = snapped[0];
            int gz = snapped[1];

            if (gx < 0 || gx >= w || gz < 0 || gz >= h) continue;
            if (!isCellAllowedForRegion(
                    gx, gz, cfg.regionId, map, clusterMap,
                    globalMinX, globalMinZ, step, coarseStep)) continue;

            pq.add(new double[]{0.0, gx, gz, registeredFactions.indexOf(cfg)});
            distMap[gx][gz] = 0.0;
            globalOwnershipMap[gx][gz] = cfg.id;
            claimArea(results.get(cfg.id).claimedChunks, gx, gz, globalMinX, globalMinZ, step);
        }

        int[][] dirs = {{0,1}, {0,-1}, {1,0}, {-1,0}, {1,1}, {1,-1}, {-1,1}, {-1,-1}};
        double[] dirCosts = {1.0, 1.0, 1.0, 1.0, 1.414, 1.414, 1.414, 1.414};

        while (!pq.isEmpty()) {
            double[] current = pq.poll();
            double cost = current[0];
            int cx = (int) current[1];
            int cz = (int) current[2];
            int fIdx = (int) current[3];
            TerritoryConfig currentFaction = registeredFactions.get(fIdx);
            RegionTerrainAggregate terrainAgg = regionAggregates.get(currentFaction.regionId);

            if (cost >= currentFaction.maxPower) continue;
            if (cost > distMap[cx][cz]) continue;
            ScanPixel center = map[cx][cz];
            if (center == null) continue;

            for (int k = 0; k < 8; k++) {
                int nx = cx + dirs[k][0];
                int nz = cz + dirs[k][1];
                double distFactor = dirCosts[k];

                if (nx < 0 || nx >= w || nz < 0 || nz >= h) continue;
                ScanPixel neighbor = map[nx][nz];
                if (neighbor == null) continue;
                if (!isCellAllowedForRegion(
                        nx, nz, currentFaction.regionId, map, clusterMap,
                        globalMinX, globalMinZ, step, coarseStep)) continue;

                double slope;
                double landRatio;
                if (terrainAgg != null && terrainAgg.hasData[nx][nz]) {
                    slope = terrainAgg.slopeAvg[nx][nz];
                    landRatio = terrainAgg.landRatio[nx][nz];
                } else {
                    double hDiff = Math.abs(neighbor.height() - center.height());
                    slope = hDiff / 16.0;
                    landRatio = neighbor.isLand() ? 1.0 : 0.0;
                }

                double moveCost = 1.0 + (slope * currentFaction.mountainCost);
                double waterFactor = 1.0 + (1.0 - landRatio) * (currentFaction.waterCost - 1.0);
                moveCost *= waterFactor;

                double newCost = cost + (moveCost * distFactor);

                if (distMap[nx][nz] == Double.MAX_VALUE && newCost <= currentFaction.maxPower) {
                    distMap[nx][nz] = newCost;
                    globalOwnershipMap[nx][nz] = currentFaction.id;
                    claimArea(results.get(currentFaction.id).claimedChunks, nx, nz, globalMinX, globalMinZ, step);
                    pq.add(new double[]{newCost, nx, nz, fIdx});
                }
            }
        }

        fillEnclaves(globalOwnershipMap, w, h, globalMinX, globalMinZ, step);
    }

    private static boolean isCellAllowedForRegion(
            int gx, int gz, int regionId, ScanPixel[][] map, int[][] clusterMap,
            int globalMinX, int globalMinZ, int step, int clusterStep) {
        if (gx < 0 || gz < 0 || gx >= map.length || gz >= map[0].length) return false;
        ScanPixel p = map[gx][gz];
        if (p == null || !p.isLand()) return false;
        int worldX = globalMinX + gx * step + step / 2;
        int worldZ = globalMinZ + gz * step + step / 2;
        int clusterId = readClusterAtWorld(worldX, worldZ, clusterMap, globalMinX, globalMinZ, clusterStep);
        return clusterId == regionId;
    }

    private static int[] snapSeedToRegion(
            int gx, int gz, int regionId, ScanPixel[][] map, int[][] clusterMap,
            int globalMinX, int globalMinZ, int step, int clusterStep) {
        int w = map.length;
        int h = map[0].length;
        int clampedX = Math.max(0, Math.min(w - 1, gx));
        int clampedZ = Math.max(0, Math.min(h - 1, gz));
        if (isCellAllowedForRegion(
                clampedX, clampedZ, regionId, map, clusterMap,
                globalMinX, globalMinZ, step, clusterStep)) {
            return new int[]{clampedX, clampedZ};
        }

        int bestX = clampedX;
        int bestZ = clampedZ;
        int bestDistSq = Integer.MAX_VALUE;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                if (!isCellAllowedForRegion(
                        x, z, regionId, map, clusterMap,
                        globalMinX, globalMinZ, step, clusterStep)) continue;
                int dx = x - clampedX;
                int dz = z - clampedZ;
                int distSq = dx * dx + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        return new int[]{bestX, bestZ};
    }

    private static ExpansionGrid buildExpansionGrid(ScanResultHolder holder) {
        ScanPixel[][] coarseMap = holder.lastScanData;
        int coarseStep = Math.max(1, holder.scanStep);
        int targetStep = selectExpansionStep(holder, coarseStep);
        int radiusBlocks = holder.scanRadiusChunks * 16;
        int globalMinX = -radiusBlocks;
        int globalMinZ = -radiusBlocks;
        int span = radiusBlocks * 2;
        int w = Math.max(1, span / targetStep);
        int h = Math.max(1, span / targetStep);

        ScanPixel[][] map = new ScanPixel[w][h];
        paintCoarseFallback(coarseMap, map, globalMinX, globalMinZ, coarseStep, targetStep);
        paintDetailCaches(holder, map, globalMinX, globalMinZ, targetStep);
        return new ExpansionGrid(map, targetStep, globalMinX, globalMinZ, coarseStep);
    }

    private static int selectExpansionStep(ScanResultHolder holder, int coarseStep) {
        if (holder.regionCacheMap == null || holder.regionCacheMap.isEmpty()) return coarseStep;
        for (RegionCache cache : holder.regionCacheMap.values()) {
            if (cache == null || cache.detailData == null) continue;
            int s = Math.max(1, cache.step);
            if (s <= HIGH_RES_EXPANSION_STEP) return HIGH_RES_EXPANSION_STEP;
        }
        return coarseStep;
    }

    private static void paintCoarseFallback(
            ScanPixel[][] coarseMap,
            ScanPixel[][] out,
            int globalMinX,
            int globalMinZ,
            int coarseStep,
            int targetStep) {
        if (coarseMap == null || coarseMap.length == 0 || coarseMap[0] == null) return;
        int factor = Math.max(1, coarseStep / targetStep);
        for (int i = 0; i < coarseMap.length; i++) {
            for (int j = 0; j < coarseMap[0].length; j++) {
                ScanPixel p = coarseMap[i][j];
                if (p == null) continue;
                int gx = (p.x() - globalMinX) / targetStep;
                int gz = (p.z() - globalMinZ) / targetStep;
                paintCell(out, gx, gz, factor, p);
            }
        }
    }

    private static void paintDetailCaches(
            ScanResultHolder holder,
            ScanPixel[][] out,
            int globalMinX,
            int globalMinZ,
            int targetStep) {
        if (holder.regionCacheMap == null || holder.regionCacheMap.isEmpty()) return;
        for (RegionCache cache : holder.regionCacheMap.values()) {
            if (cache == null || cache.detailData == null) continue;
            int cacheStep = Math.max(1, cache.step);
            int factor = (cacheStep % targetStep == 0) ? Math.max(1, cacheStep / targetStep) : 1;
            ScanPixel[][] data = cache.detailData;
            for (int i = 0; i < data.length; i++) {
                for (int j = 0; j < data[0].length; j++) {
                    ScanPixel p = data[i][j];
                    if (p == null) continue;
                    int gx = (p.x() - globalMinX) / targetStep;
                    int gz = (p.z() - globalMinZ) / targetStep;
                    paintCell(out, gx, gz, factor, p);
                }
            }
        }
    }

    private static void paintCell(ScanPixel[][] out, int gx, int gz, int factor, ScanPixel value) {
        if (value == null || factor <= 0) return;
        for (int dx = 0; dx < factor; dx++) {
            for (int dz = 0; dz < factor; dz++) {
                int x = gx + dx;
                int z = gz + dz;
                if (x < 0 || z < 0 || x >= out.length || z >= out[0].length) continue;
                out[x][z] = value;
            }
        }
    }

    private static int readClusterAtWorld(
            int worldX, int worldZ,
            int[][] clusterMap,
            int globalMinX, int globalMinZ,
            int clusterStep) {
        if (clusterMap == null || clusterMap.length == 0 || clusterMap[0] == null) return -1;
        int step = Math.max(1, clusterStep);
        int gx = (worldX - globalMinX) / step;
        int gz = (worldZ - globalMinZ) / step;
        if (gx < 0 || gz < 0 || gx >= clusterMap.length || gz >= clusterMap[0].length) return -1;
        return clusterMap[gx][gz];
    }

    private static Map<Integer, RegionTerrainAggregate> buildRegionTerrainAggregates(
            ScanResultHolder holder, int w, int h, int globalMinX, int globalMinZ, int step) {
        if (holder.regionCacheMap.isEmpty()) return Collections.emptyMap();
        Map<Integer, RegionTerrainAggregate> aggregates = new HashMap<>();
        for (Map.Entry<Integer, RegionCache> entry : holder.regionCacheMap.entrySet()) {
            RegionCache cache = entry.getValue();
            if (cache == null || cache.detailData == null) continue;
            ScanPixel[][] data = cache.detailData;
            int localW = data.length;
            int localH = data[0].length;
            double[][] slopeSum = new double[w][h];
            int[][] landCount = new int[w][h];
            int[][] sampleCount = new int[w][h];
            for (int i = 0; i < localW; i++) {
                for (int j = 0; j < localH; j++) {
                    ScanPixel p = data[i][j];
                    if (p == null) continue;
                    int gx = (p.x() - globalMinX) / step;
                    int gz = (p.z() - globalMinZ) / step;
                    if (gx < 0 || gx >= w || gz < 0 || gz >= h) continue;

                    sampleCount[gx][gz]++;
                    if (p.isLand()) landCount[gx][gz]++;

                    double slope = 0.0;
                    if (p.isLand()) {
                        double raw;
                        if (cache.slopeData != null
                                && i < cache.slopeData.length && j < cache.slopeData[0].length) {
                            raw = cache.slopeData[i][j];
                        } else if (i + 1 < localW && j + 1 < localH) {
                            ScanPixel px = data[i + 1][j];
                            ScanPixel pz = data[i][j + 1];
                            if (px != null && pz != null) {
                                double dx = Math.abs(p.height() - px.height());
                                double dz = Math.abs(p.height() - pz.height());
                                raw = Math.sqrt(dx * dx + dz * dz);
                            } else {
                                raw = 0.0;
                            }
                        } else {
                            raw = 0.0;
                        }
                        slope = raw;
                    }
                    slopeSum[gx][gz] += slope;
                }
            }

            double[][] slopeAvg = new double[w][h];
            double[][] landRatio = new double[w][h];
            boolean[][] hasData = new boolean[w][h];

            for (int x = 0; x < w; x++) {
                for (int z = 0; z < h; z++) {
                    int count = sampleCount[x][z];
                    if (count == 0) continue;
                    hasData[x][z] = true;
                    slopeAvg[x][z] = slopeSum[x][z] / count;
                    landRatio[x][z] = (double) landCount[x][z] / count;
                }
            }

            aggregates.put(entry.getKey(), new RegionTerrainAggregate(slopeAvg, landRatio, hasData));
        }
        return aggregates;
    }

    // --- 【分析统计逻辑】 ---
    private static void analyzeTerritories(ScanResultHolder holder) {
        ScanPixel[][] map = expansionScanMap != null ? expansionScanMap : holder.lastScanData;
        int[][] clusterMap = holder.lastClusterMap;
        if (globalOwnershipMap == null || map == null) return;

        int step = Math.max(1, expansionStepBlocks);
        int globalMinX = expansionMinX;
        int globalMinZ = expansionMinZ;
        int clusterStep = Math.max(1, holder.scanStep);
        int w = Math.min(map.length, globalOwnershipMap.length);
        int h = Math.min(map[0].length, globalOwnershipMap[0].length);

        // 初始化统计对象
        Map<String, TerritoryStats> tempStats = new HashMap<>();
        for (String id : results.keySet()) tempStats.put(id, new TerritoryStats());

        // 1. 遍历全图，收集原始数据
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                String owner = globalOwnershipMap[i][j];
                if (owner == null) continue;

                ScanPixel p = map[i][j];
                if (p == null) continue;

                TerritoryStats stat = tempStats.get(owner);
                if (stat == null) continue;

                // 面积
                stat.area_pixels++;

                // 边界框
                int wx = globalMinX + i * step;
                int wz = globalMinZ + j * step;
                if (wx < stat.minX) stat.minX = wx;
                if (wx > stat.maxX) stat.maxX = wx;
                if (wz < stat.minZ) stat.minZ = wz;
                if (wz > stat.maxZ) stat.maxZ = wz;

                // 群系
                stat.rawBiomeCounts.merge(p.biomeId(), 1, Integer::sum);

                // 大陆
                int regionId = readClusterAtWorld(
                        wx + step / 2, wz + step / 2, clusterMap,
                        globalMinX, globalMinZ, clusterStep);
                if (regionId > 0) {
                    stat.rawContinentCounts.merge(regionId, 1, Integer::sum);
                }

                // 邻国判定 (检查上下左右)
                checkNeighbor(i + 1, j, owner, w, h, stat);
                checkNeighbor(i - 1, j, owner, w, h, stat);
                checkNeighbor(i, j + 1, owner, w, h, stat);
                checkNeighbor(i, j - 1, owner, w, h, stat);
            }
        }

        // 2. 汇总百分比
        for (String id : results.keySet()) {
            TerritoryResult res = results.get(id);
            TerritoryStats stat = tempStats.get(id);

            // 计算群系 %
            long total = Math.max(1, stat.area_pixels);
            stat.rawBiomeCounts.forEach((bId, count) -> {
                double pct = (double) count / total;
                if (pct > 0.05) stat.biome_composition.put(bId, (double)Math.round(pct * 1000) / 1000.0);
            });

            // 计算大陆 %
            stat.rawContinentCounts.forEach((rId, count) -> {
                double pct = (double) count / total;
                stat.continent_distribution.put(rId, (double)Math.round(pct * 1000) / 1000.0);
            });

            res.stats = stat;
        }
        System.out.println("[Territory] Analysis complete.");
    }

    private static void checkNeighbor(int x, int z, String myOwner, int w, int h, TerritoryStats stat) {
        if (x < 0 || x >= w || z < 0 || z >= h) return;
        String neighbor = globalOwnershipMap[x][z];
        if (neighbor != null && !neighbor.equals(myOwner)) {
            stat.neighborIds.add(neighbor);
        }
    }

    // --- 辅助方法 ---
    private static void claimArea(Set<Long> chunks, int gx, int gz, int minX, int minZ, int step) {
        int startWorldX = minX + gx * step;
        int startWorldZ = minZ + gz * step;
        int cX1 = startWorldX >> 4;
        int cZ1 = startWorldZ >> 4;
        int cX2 = (startWorldX + step - 1) >> 4;
        int cZ2 = (startWorldZ + step - 1) >> 4;
        for (int x = cX1; x <= cX2; x++) for (int z = cZ1; z <= cZ2; z++) chunks.add(ChunkPos.asLong(x, z));
    }

    private static void fillEnclaves(String[][] ownershipMap, int w, int h, int minX, int minZ, int step) {
        boolean[][] visited = new boolean[w][h];
        int[][] dirs = {{0,1}, {0,-1}, {1,0}, {-1,0}};
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                if (ownershipMap[i][j] == null && !visited[i][j]) {
                    List<int[]> holePixels = new ArrayList<>();
                    Set<String> neighborFactions = new HashSet<>();
                    Queue<int[]> queue = new LinkedList<>();
                    queue.add(new int[]{i, j});
                    visited[i][j] = true;
                    holePixels.add(new int[]{i, j});
                    boolean touchesEdge = false;
                    while(!queue.isEmpty()) {
                        int[] curr = queue.poll();
                        for (int[] d : dirs) {
                            int nx = curr[0] + d[0];
                            int ny = curr[1] + d[1];
                            if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                                String owner = ownershipMap[nx][ny];
                                if (owner == null) {
                                    if (!visited[nx][ny]) {
                                        visited[nx][ny] = true;
                                        int[] next = new int[]{nx, ny};
                                        holePixels.add(next);
                                        queue.add(next);
                                    }
                                } else neighborFactions.add(owner);
                            } else touchesEdge = true;
                        }
                    }
                    if (!touchesEdge && neighborFactions.size() == 1) {
                        String sovereignId = neighborFactions.iterator().next();
                        TerritoryResult res = results.get(sovereignId);
                        if (res != null) {
                            for (int[] p : holePixels) claimArea(res.wildChunks, p[0], p[1], minX, minZ, step);
                        }
                    }
                }
            }
        }
    }

    /**
     * 快速检查某区块是否属于指定领土
     * @param chunkKey ChunkPos.asLong()
     * @param territoryId 目标领土 ID
     */
    public static boolean isChunkOwnedBy(long chunkKey, String territoryId) {
        if (territoryId == null) return false;

        TerritoryResult res = results.get(territoryId);
        if (res == null) return false;

        // 检查核心领土
        if (res.claimedChunks.contains(chunkKey)) return true;

        // 检查荒野领土 (根据策划决定：城市能不能建在荒野/飞地？通常是可以的)
        if (res.wildChunks.contains(chunkKey)) return true;

        return false;
    }

    // JSON IO
    private static void save() { try { Files.writeString(CONFIG_FILE.toPath(), GSON.toJson(registeredFactions)); } catch (Exception e) { e.printStackTrace(); } }
    private static void load() { if (!CONFIG_FILE.exists()) return; try { List<TerritoryConfig> l = GSON.fromJson(Files.readString(CONFIG_FILE.toPath()), new TypeToken<List<TerritoryConfig>>(){}.getType()); registeredFactions.clear(); if(l!=null) registeredFactions.addAll(l); } catch (Exception e) { e.printStackTrace(); } }
}

