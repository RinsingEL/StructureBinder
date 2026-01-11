package com.user.terra_script.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.data.ScanResultHolder.RegionCache;
import com.user.terra_script.scan.ScanPixel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TerritoryManager {

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

    public static class TerritoryResult {
        public TerritoryConfig config;
        public Set<Long> claimedChunks = new HashSet<>();
        public Set<Long> wildChunks = new HashSet<>();
        public TerritoryResult(TerritoryConfig config) { this.config = config; }
    }

    private static final List<TerritoryConfig> registeredFactions = new ArrayList<>();
    private static final Map<String, TerritoryResult> results = new ConcurrentHashMap<>();

    // JSON 持久化相关
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("terra_script_territories.json").toFile();

    public static Collection<TerritoryResult> getAllResults() { return results.values(); }

    public static void clear() {
        registeredFactions.clear();
        results.clear();
        save(); // 清空也要保存
    }

    public static void createTerritory(String id, String name, int regionId, int startX, int startZ, int maxPower, double mCost, double wCost, int color) {
        registeredFactions.removeIf(c -> c.id.equals(id));
        registeredFactions.add(new TerritoryConfig(id, name, regionId, startX, startZ, maxPower, mCost, wCost, color));

        save(); // 保存到磁盘
        recalculateAll();
    }

    public static void refresh() {
        // 刷新时先尝试加载（防止重启游戏后数据丢失）
        if (registeredFactions.isEmpty() && CONFIG_FILE.exists()) {
            load();
        }
        recalculateAll();
    }

    // --- 持久化方法 ---
    private static void save() {
        try {
            String json = GSON.toJson(registeredFactions);
            Files.writeString(CONFIG_FILE.toPath(), json);
            System.out.println("[Territory] Configs saved to " + CONFIG_FILE.getName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void load() {
        if (!CONFIG_FILE.exists()) return;
        try {
            String json = Files.readString(CONFIG_FILE.toPath());
            List<TerritoryConfig> loaded = GSON.fromJson(json, new TypeToken<List<TerritoryConfig>>(){}.getType());
            registeredFactions.clear();
            if (loaded != null) registeredFactions.addAll(loaded);
            System.out.println("[Territory] Loaded " + registeredFactions.size() + " factions from disk.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- 计算逻辑 (复用您之前的代码，只需注意要适配全球计算逻辑) ---
    // 为了防止混淆，我这里贴上【全球版】的 recalculateAll 代码

    private static void recalculateAll() {
        results.clear();
        var holder = ScanResultHolder.get();

        // 优先检查全球数据
        if (holder.lastScanData != null) {
            computeGlobal(holder);
        } else {
            System.out.println("[Territory] No global scan data found. Waiting for scan...");
        }
    }

    private static void computeGlobal(ScanResultHolder holder) {
        ScanPixel[][] map = holder.lastScanData;
        int w = map.length;
        int h = map[0].length;
        int step = holder.scanStep;

        int radiusBlocks = holder.scanRadiusChunks * 16;
        int globalMinX = -radiusBlocks;
        int globalMinZ = -radiusBlocks;

        for (TerritoryConfig cfg : registeredFactions) {
            results.put(cfg.id, new TerritoryResult(cfg));
        }

        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        double[][] distMap = new double[w][h];
        for (double[] row : distMap) Arrays.fill(row, Double.MAX_VALUE);
        String[][] ownershipMap = new String[w][h];

        // 1. 初始化
        for (TerritoryConfig cfg : registeredFactions) {
            int gx = (cfg.capitalX - globalMinX) / step;
            int gz = (cfg.capitalZ - globalMinZ) / step;

            if (gx >= 0 && gx < w && gz >= 0 && gz < h) {
                pq.add(new double[]{0.0, gx, gz, registeredFactions.indexOf(cfg)});
                distMap[gx][gz] = 0.0;
                ownershipMap[gx][gz] = cfg.id;
                claimArea(results.get(cfg.id).claimedChunks, gx, gz, globalMinX, globalMinZ, step);
            }
        }

        int[][] dirs = {{0,1}, {0,-1}, {1,0}, {-1,0}, {1,1}, {1,-1}, {-1,1}, {-1,-1}};
        double[] dirCosts = {1.0, 1.0, 1.0, 1.0, 1.414, 1.414, 1.414, 1.414};

        // 2. 扩张
        while (!pq.isEmpty()) {
            double[] current = pq.poll();
            double cost = current[0];
            int cx = (int) current[1];
            int cz = (int) current[2];
            int fIdx = (int) current[3];
            TerritoryConfig currentFaction = registeredFactions.get(fIdx);

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

                double moveCost = 1.0;
                double hDiff = Math.abs(neighbor.height() - center.height());
                double slope = hDiff / (double)step;

                if (slope > 0.5) moveCost += (slope * 2.0 * currentFaction.mountainCost);
                if (!neighbor.isLand()) moveCost *= currentFaction.waterCost;

                double newCost = cost + (moveCost * distFactor);

                if (distMap[nx][nz] == Double.MAX_VALUE && newCost <= currentFaction.maxPower) {
                    distMap[nx][nz] = newCost;
                    ownershipMap[nx][nz] = currentFaction.id;
                    claimArea(results.get(currentFaction.id).claimedChunks, nx, nz, globalMinX, globalMinZ, step);
                    pq.add(new double[]{newCost, nx, nz, fIdx});
                }
            }
        }

        // 3. 填补
        fillEnclaves(ownershipMap, w, h, globalMinX, globalMinZ, step);
    }

    // claimArea 和 fillEnclaves 辅助方法保持不变 (请使用上一轮修复后的版本)
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
}