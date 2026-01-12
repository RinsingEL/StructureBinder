package com.user.terra_script.world.city;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.scan.ScanPixel;
import com.user.terra_script.world.TerritoryManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CityManager {
    private static final CityManager INSTANCE = new CityManager();
    public static CityManager get() { return INSTANCE; }

    // 存储所有生成的城市
    private final Map<String, CityInstance> cities = new ConcurrentHashMap<>();

    // 全局城市占领图 (防止新城市覆盖旧城市)
    // Key: ChunkPos.asLong, Value: CityInstanceID
    private final Map<Long, String> globalCityChunkMap = new ConcurrentHashMap<>();

    // JSON 工具
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File EXPORT_FILE = FMLPaths.GAMEDIR.get().resolve("terra_script_cities.json").toFile();

    public void clear() {
        cities.clear();
        globalCityChunkMap.clear();
        saveToFile(); // 清空时也同步文件
    }

    public Collection<CityInstance> getAllCities() { return cities.values(); }

    /**
     * 创建并生成一个城市
     */
    public CityInstance createCity(CityConfig config) {
        String uid = "city_" + config.centerX + "_" + config.centerZ;
        config.cityInstanceId = uid;

        CityInstance city = new CityInstance(uid, config);

        // 执行扩张算法
        expandCity(city);

        // 注册
        cities.put(uid, city);

        saveToFile();
        return city;
    }

    /**
     * 核心算法：基于权重的泛洪扩张
     */
    private void expandCity(CityInstance city) {
        var holder = ScanResultHolder.get();
        if (holder.lastScanData == null) return;

        // 1. 准备数据
        int startChunkX = city.config.centerX >> 4;
        int startChunkZ = city.config.centerZ >> 4;
        long centerKey = ChunkPos.asLong(startChunkX, startChunkZ);

        // 优先队列 [cost, chunkX, chunkZ]
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        Map<Long, Double> costMap = new HashMap<>();

        // 初始点
        pq.add(new double[]{0.0, startChunkX, startChunkZ});
        costMap.put(centerKey, 0.0);

        // 辅助：获取该国度的领土范围 (用于边界检查)
        // 只有属于该 territoryId 的区块才能扩张
        // 这一步需要在 TerritoryManager 里加一个 helper method: isChunkOwnedBy(long chunkKey, String territoryId)

        int targetSize = city.config.targetChunkCount;
        int currentSize = 0;
        double maxCostReached = 0.0;

        int[][] dirs = {{0,1}, {0,-1}, {1,0}, {-1,0}}; // 4方向，城市可以方正一点，或者用8方向更圆润

        while (!pq.isEmpty() && currentSize < targetSize) {
            double[] curr = pq.poll();
            double currentCost = curr[0];
            int cx = (int) curr[1];
            int cz = (int) curr[2];
            long key = ChunkPos.asLong(cx, cz);

            // 如果已经被更低成本的处理过，跳过
            if (costMap.containsKey(key) && costMap.get(key) < currentCost) continue;

            // 真正接纳这个区块
            if (!city.claimedChunks.containsKey(key)) {
                // 默认先设为 BUFFER，后面统一分层
                city.claimedChunks.put(key, CityInstance.CityZoneType.BUFFER);
                globalCityChunkMap.put(key, city.id);
                currentSize++;
                maxCostReached = Math.max(maxCostReached, currentCost);
            }

            // 扩散
            for (int[] d : dirs) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                long nKey = ChunkPos.asLong(nx, nz);

                // 检查1: 是否已被任何城市占领
                if (globalCityChunkMap.containsKey(nKey)) continue;

                // 检查2: 是否在国境线内 (重要!)
                // if (!TerritoryManager.isOwnedBy(nKey, city.config.territoryId)) continue;
                // 这里暂时注释，需要在 TerritoryManager 实现对应接口

                // 计算代价
                double moveCost = calculateCellCost(nx, nz, city.config, holder);
                double newCost = currentCost + moveCost;

                if (!costMap.containsKey(nKey) || newCost < costMap.get(nKey)) {
                    costMap.put(nKey, newCost);
                    pq.add(new double[]{newCost, nx, nz});
                }
            }
        }

        // 2. 后处理：根据 Cost 分层 (Core / Urban / Buffer)
        for (Map.Entry<Long, CityInstance.CityZoneType> entry : city.claimedChunks.entrySet()) {
            long key = entry.getKey();
            double cost = costMap.getOrDefault(key, maxCostReached);
            double ratio = (maxCostReached > 0) ? (cost / maxCostReached) : 0;

            if (ratio <= CityInstance.CityZoneType.CORE.threshold) {
                entry.setValue(CityInstance.CityZoneType.CORE);
            } else if (ratio <= CityInstance.CityZoneType.URBAN.threshold) {
                entry.setValue(CityInstance.CityZoneType.URBAN);
            } else {
                entry.setValue(CityInstance.CityZoneType.BUFFER);
                city.borderChunks.add(key); // 简单的边界识别，后续可用 Alpha Shape 优化
            }
        }

        System.out.println("City " + city.id + " generated. Size: " + currentSize + " chunks.");
    }

    /**
     * 计算单步扩张代价
     */
    private double calculateCellCost(int chunkX, int chunkZ, CityConfig config, ScanResultHolder holder) {
        // 基础代价
        double cost = 1.0;

        // 1. 获取地形数据 (取区块中心点采样)
        // 转换到 Global Scan 坐标系 (假设 holder.lastScanData 是以 (0,0) 为中心)
        int step = holder.scanStep;
        if (step <= 0) step = 1;

        int radiusBlocks = holder.scanRadiusChunks * 16;
        int globalMinX = -radiusBlocks;
        int globalMinZ = -radiusBlocks;

        // 区块中心的世界坐标
        int worldX = (chunkX * 16) + 8;
        int worldZ = (chunkZ * 16) + 8;

        // 映射到 scanData 数组索引
        int gx = (worldX - globalMinX) / step;
        int gz = (worldZ - globalMinZ) / step;

        ScanPixel p = null;
        if (holder.lastScanData != null && gx >= 0 && gx < holder.lastScanData.length && gz >= 0 && gz < holder.lastScanData[0].length) {
            p = holder.lastScanData[gx][gz];
        }

        // 如果数据缺失，给一个中等惩罚，防止报错
        if (p == null) return 5.0;

        // 2. 偏好偏移 (Bias) - 引导城市向特定方向生长
        int dx = worldX - config.centerX;
        int dz = worldZ - config.centerZ;

        // 简单的线性势场
        if (config.bias == CityConfig.ExpansionBias.NORTH && dz > 0) cost += 1.5; // 往南走更贵
        if (config.bias == CityConfig.ExpansionBias.SOUTH && dz < 0) cost += 1.5;
        if (config.bias == CityConfig.ExpansionBias.EAST && dx < 0) cost += 1.5;
        if (config.bias == CityConfig.ExpansionBias.WEST && dx > 0) cost += 1.5;

        // 3. 地形代价
        // 这里只是粗略判断，因为 p 是单点采样。
        // 理想情况应该取 Chunk 内 16x16 的平均斜率，但那样太慢。
        // 我们可以用 holder.lastScanData[gx][gz] 周围点的差值来估算宏观斜率。

        // 4. 水域代价
        if (!p.isLand()) {
            if (config.bias == CityConfig.ExpansionBias.COASTAL) {
                cost += 0.5; // 沿海城市下水容易点
            } else {
                cost += 10.0; // 内陆城市极难下水
            }
        } else {
            // 如果是陆地，但倾向于内陆 (INLAND)，则离水越近代价越高？
            // 这需要距离场计算，暂时忽略。
        }

        return cost;
    }

    private void saveToFile() {
        // 使用新线程，避免阻塞主逻辑
        new Thread(() -> {
            try {
                JsonArray arr = new JsonArray();
                for (CityInstance city : cities.values()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", city.id);
                    obj.addProperty("territory", city.config.territoryId);
                    obj.addProperty("center_x", city.config.centerX);
                    obj.addProperty("center_z", city.config.centerZ);
                    obj.addProperty("bias", city.config.bias.name());
                    obj.addProperty("ecology", city.config.ecology.name());

                    // 导出区块列表
                    // 为了减小文件体积，我们可以只存 "x,z:type" 的紧凑字符串数组，或者依然用对象数组
                    JsonArray chunks = new JsonArray();
                    city.claimedChunks.forEach((key, type) -> {
                        JsonObject c = new JsonObject();
                        c.addProperty("x", ChunkPos.getX(key));
                        c.addProperty("z", ChunkPos.getZ(key));
                        // 简化类型名: CORE -> C, URBAN -> U, BUFFER -> B
                        String tShort = type.name().substring(0, 1);
                        c.addProperty("t", tShort);
                        chunks.add(c);
                    });
                    obj.add("chunks", chunks);

                    arr.add(obj);
                }

                Files.writeString(EXPORT_FILE.toPath(), GSON.toJson(arr));
                System.out.println("[CityManager] Auto-saved " + cities.size() + " cities to " + EXPORT_FILE.getName());

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "TerraScript-IO-City").start();
    }
}