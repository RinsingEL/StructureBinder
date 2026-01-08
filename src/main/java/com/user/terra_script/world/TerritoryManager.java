package com.user.terra_script.world;

import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.data.ScanResultHolder.RegionCache;
import com.user.terra_script.scan.ScanPixel;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TerritoryManager {

    public static class TerritoryConfig {
        public String id;
        public String name;
        public int regionId; // 【新增】必须知道这个国家属于哪个大陆
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

    public static class TerritoryResult {
        public TerritoryConfig config;
        public Set<Long> claimedChunks = new HashSet<>();
        public Set<Long> wildChunks = new HashSet<>();
        public TerritoryResult(TerritoryConfig config) { this.config = config; }
    }

    private static final List<TerritoryConfig> registeredFactions = new ArrayList<>();
    private static final Map<String, TerritoryResult> results = new ConcurrentHashMap<>();

    public static Collection<TerritoryResult> getAllResults() { return results.values(); }

    public static void clear() {
        registeredFactions.clear();
        results.clear();
    }

    // 【新增参数】regionId
    public static void createTerritory(String id, String name, int regionId, int startX, int startZ, int maxPower, double mCost, double wCost, int color) {
        registeredFactions.removeIf(c -> c.id.equals(id));
        registeredFactions.add(new TerritoryConfig(id, name, regionId, startX, startZ, maxPower, mCost, wCost, color));
        recalculateAll();
    }

    // 提供给 GUI 打开时刷新用
    public static void refresh() {
        recalculateAll();
    }

    private static void recalculateAll() {
        results.clear();
        var holder = ScanResultHolder.get();

        // 按 RegionID 分组计算，防止不同大陆的坐标混淆
        // Map<RegionID, List<Config>>
        Map<Integer, List<TerritoryConfig>> factionsByRegion = new HashMap<>();
        for (TerritoryConfig cfg : registeredFactions) {
            factionsByRegion.computeIfAbsent(cfg.regionId, k -> new ArrayList<>()).add(cfg);
        }

        // 遍历每个有国家的大陆进行计算
        for (Map.Entry<Integer, List<TerritoryConfig>> entry : factionsByRegion.entrySet()) {
            int rId = entry.getKey();
            List<TerritoryConfig> regionFactions = entry.getValue();

            // 精准获取该大陆的缓存
            RegionCache cache = holder.regionCacheMap.get(rId);
            if (cache == null || cache.detailData == null) {
                System.out.println("[Territory] Skipped region " + rId + " (Not cached).");
                continue;
            }

            computeRegion(cache, regionFactions, holder);
        }
    }

    private static void computeRegion(RegionCache cache, List<TerritoryConfig> factionList, ScanResultHolder holder) {
        ScanPixel[][] map = cache.detailData;
        double[][] slopeMap = cache.slopeData; // 可能为 null，需检查
        int w = map.length;
        int h = map[0].length;

        // 初始化结果对象
        for (TerritoryConfig cfg : factionList) {
            results.put(cfg.id, new TerritoryResult(cfg));
        }

        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        double[][] distMap = new double[w][h];
        for (double[] row : distMap) Arrays.fill(row, Double.MAX_VALUE);
        String[][] ownershipMap = new String[w][h];

        // 1. 初始化种子点
        for (TerritoryConfig cfg : factionList) {
            int gx = (cfg.capitalX - cache.minX) / cache.step;
            int gz = (cfg.capitalZ - cache.minZ) / cache.step;

            if (gx >= 0 && gx < w && gz >= 0 && gz < h) {
                pq.add(new double[]{0.0, gx, gz, factionList.indexOf(cfg)}); // 存 index 而不是 ID 以便快速查找
                distMap[gx][gz] = 0.0;
                ownershipMap[gx][gz] = cfg.id;
                results.get(cfg.id).claimedChunks.add(toChunkKey(gx, gz, cache));
            }
        }

        int[][] dirs = {{0,1}, {0,-1}, {1,0}, {-1,0}};

        // 2. 扩张
        while (!pq.isEmpty()) {
            double[] current = pq.poll();
            double cost = current[0];
            int cx = (int) current[1];
            int cz = (int) current[2];
            int fIdx = (int) current[3];
            TerritoryConfig currentFaction = factionList.get(fIdx);

            if (cost >= currentFaction.maxPower) continue;
            if (distMap[cx][cz] < cost) continue;

            for (int[] dir : dirs) {
                int nx = cx + dir[0];
                int nz = cz + dir[1];
                if (nx < 0 || nx >= w || nz < 0 || nz >= h) continue;

                ScanPixel neighbor = map[nx][nz];
                if (neighbor == null) continue;

                double moveCost = 1.0;
                // 安全读取斜率
                double slope = (slopeMap != null) ? slopeMap[nx][nz] : 0.5;
                if (slope > 1.0) moveCost += (slope * currentFaction.mountainCost);
                if (!neighbor.isLand()) moveCost *= currentFaction.waterCost;

                double newCost = cost + moveCost;

                if (distMap[nx][nz] == Double.MAX_VALUE && newCost <= currentFaction.maxPower) {
                    distMap[nx][nz] = newCost;
                    ownershipMap[nx][nz] = currentFaction.id;
                    results.get(currentFaction.id).claimedChunks.add(toChunkKey(nx, nz, cache));
                    pq.add(new double[]{newCost, nx, nz, fIdx});
                }
            }
        }

        // 3. 填补空洞 (传入当前处理的 region factions 来判定)
        fillEnclaves(ownershipMap, w, h, cache);
    }

    private static void fillEnclaves(String[][] ownershipMap, int w, int h, RegionCache cache) {
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
                                } else {
                                    neighborFactions.add(owner);
                                }
                            } else {
                                touchesEdge = true;
                            }
                        }
                    }

                    if (!touchesEdge && neighborFactions.size() == 1) {
                        String sovereignId = neighborFactions.iterator().next();
                        TerritoryResult res = results.get(sovereignId);
                        if (res != null) {
                            for (int[] p : holePixels) {
                                res.wildChunks.add(toChunkKey(p[0], p[1], cache));
                                // ownershipMap[p[0]][p[1]] = sovereignId; // 视觉上不需要更新map，只需加到set里渲染
                            }
                        }
                    }
                }
            }
        }
    }

    private static long toChunkKey(int gridX, int gridZ, RegionCache cache) {
        int worldX = cache.minX + gridX * cache.step;
        int worldZ = cache.minZ + gridZ * cache.step;
        return ChunkPos.asLong(worldX >> 4, worldZ >> 4);
    }
}