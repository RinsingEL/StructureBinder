package com.user.terra_script.world.city;

import com.google.gson.*;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.scan.ScanPixel;
import com.user.terra_script.util.VoronoiComputer;
import com.user.terra_script.world.TerritoryManager;
import com.user.terra_script.world.city.district.District;
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

    public void reload() {
        loadFromFile();
    }

    public String getCityIdAt(long chunkKey) {
        return globalCityChunkMap.get(chunkKey);
    }

    public CityInstance getCity(String id) {
        return cities.get(id);
    }

    /**
     * 创建并生成一个城市
     */
    public CityInstance createCity(CityConfig config) {
        String uid = "city_" + config.centerX + "_" + config.centerZ;
        config.cityInstanceId = uid;

        if (cities.containsKey(uid)) {
            CityInstance oldCity = cities.get(uid);
            for (Long chunkKey : oldCity.claimedChunks.keySet()) {
                globalCityChunkMap.remove(chunkKey);
            }
            System.out.println("[CityManager] Overwriting existing city: " + uid);
        }

        CityInstance city = new CityInstance(uid, config);
        // 执行扩张算法
        expandCity(city);

        // 注册或修改
        cities.put(uid, city);
        city.districts = VoronoiComputer.computeDistricts(city);

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
        new Thread(() -> {
            try {
                JsonArray arr = new JsonArray();
                for (CityInstance city : cities.values()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", city.id);

                    JsonArray chunks = new JsonArray();
                    city.claimedChunks.forEach((key, type) -> {
                        JsonObject c = new JsonObject();
                        c.addProperty("x", ChunkPos.getX(key));
                        c.addProperty("z", ChunkPos.getZ(key));
                        String tShort = type.name().substring(0, 1);
                        c.addProperty("t", tShort);
                        chunks.add(c);
                    });
                    obj.add("chunks", chunks);

                    // 2. 【新增】导出 Districts (撒点数据)
                    if (city.districts != null) {
                        JsonArray dists = new JsonArray();
                        for (var d : city.districts) {
                            JsonObject dObj = new JsonObject();
                            dObj.addProperty("id", d.id);
                            // 保留 2 位小数即可
                            dObj.addProperty("x", Math.round(d.centerX * 100) / 100.0);
                            dObj.addProperty("z", Math.round(d.centerZ * 100) / 100.0);
                            dObj.addProperty("type", d.zoneType);
                            // 环境属性
                            dObj.addProperty("water_dist", d.waterDistance);
                            dObj.addProperty("slope", d.avgSlope);

                            JsonArray members = new JsonArray();
                            for(long k : d.memberChunks) {
                                JsonObject c = new JsonObject();
                                c.addProperty("x", ChunkPos.getX(k));
                                c.addProperty("z", ChunkPos.getZ(k));
                                members.add(c);
                            }
                            dObj.add("blocks", members);
                            dists.add(dObj);
                        }
                        obj.add("districts", dists);
                    }

                    arr.add(obj);
                }

                Files.writeString(EXPORT_FILE.toPath(), GSON.toJson(arr));
                System.out.println("[CityManager] Auto-saved " + cities.size() + " cities with districts.");

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "TerraScript-IO-City").start();
    }

    private void loadFromFile() {
        if (!EXPORT_FILE.exists()) return;
        try {
            String json = Files.readString(EXPORT_FILE.toPath());
            if (json == null || json.isBlank()) return; // 空文件保护

            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr == null) return;

            cities.clear();
            globalCityChunkMap.clear();

            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();

                CityConfig cfg = new CityConfig();
                // 必须字段，如果没有则跳过该条目或赋默认值
                if (!obj.has("id")) continue;
                cfg.cityInstanceId = obj.get("id").getAsString();

                // 可选字段，带默认值
                cfg.territoryId = obj.has("territory") && !obj.get("territory").isJsonNull() ? obj.get("territory").getAsString() : "unknown";
                cfg.continentId = obj.has("continent_id") ? obj.get("continent_id").getAsInt() : 0;
                cfg.centerX = obj.has("center_x") ? obj.get("center_x").getAsInt() : 0;
                cfg.centerZ = obj.has("center_z") ? obj.get("center_z").getAsInt() : 0;
                cfg.targetChunkCount = obj.has("target_size") ? obj.get("target_size").getAsInt() : 100;

                try {
                    if (obj.has("bias")) cfg.bias = CityConfig.ExpansionBias.valueOf(obj.get("bias").getAsString());
                } catch (Exception e) { cfg.bias = CityConfig.ExpansionBias.BALANCED; }

                try {
                    if (obj.has("ecology")) cfg.ecology = CityConfig.EcologyPolicy.valueOf(obj.get("ecology").getAsString());
                } catch (Exception e) { cfg.ecology = CityConfig.EcologyPolicy.ADAPTIVE; }

                CityInstance city = new CityInstance(cfg.cityInstanceId, cfg);

                // 恢复区块
                if (obj.has("chunks")) {
                    for (JsonElement cEl : obj.getAsJsonArray("chunks")) {
                        JsonObject c = cEl.getAsJsonObject();
                        int x = c.get("x").getAsInt();
                        int z = c.get("z").getAsInt();
                        String typeStr = c.has("t") ? c.get("t").getAsString() : "B";

                        CityInstance.CityZoneType type = CityInstance.CityZoneType.BUFFER;
                        if (typeStr.equals("C") || typeStr.equals("CORE")) type = CityInstance.CityZoneType.CORE;
                        else if (typeStr.equals("U") || typeStr.equals("URBAN")) type = CityInstance.CityZoneType.URBAN;

                        long key = ChunkPos.asLong(x, z);
                        city.claimedChunks.put(key, type);
                        globalCityChunkMap.put(key, city.id);
                    }
                }

                // 恢复区划 (如果之前存了的话，没存就重新算)
                if (obj.has("districts")) {
                    // TODO: 解析 district 数据
                    // 为了简化，这里可以不解析，而是调用 VoronoiComputer.computeDistricts(city) 重新生成
                    // 只要种子随机数是一样的，结果就是一样的
                    city.districts = VoronoiComputer.computeDistricts(city);
                } else {
                    // 兼容旧数据
                    city.districts = VoronoiComputer.computeDistricts(city);
                }

                cities.put(city.id, city);
            }
            System.out.println("[CityManager] Loaded " + cities.size() + " cities from disk.");

        } catch (Exception e) {
            e.printStackTrace();
            // 如果文件损坏，可以选择删除它，或者只是报错
            System.err.println("[CityManager] Failed to load cities: " + e.getMessage());
        }
    }

    /**
     * 确保该城市的道路数据已生成 (懒加载)
     */
    public void ensureRoadsGenerated(String cityId) {
        CityInstance city = cities.get(cityId);
        if (city == null || city.isRoadsGenerated) return;

        synchronized (city) { // 防止多线程重复计算
            if (city.isRoadsGenerated) return;

            System.out.println("[CityManager] Lazy-generating roads for " + cityId + "...");
            long start = System.currentTimeMillis();

            if (city.districts == null || city.districts.isEmpty()) {
                // 如果区划也没生成，先生成区划
                city.districts = VoronoiComputer.computeDistricts(city);
            }

            if (!city.districts.isEmpty()) {
                var blockOwner = VoronoiComputer.buildBlockOwnership(city.districts);
                var rawRoads = VoronoiComputer.computeDistrictBoundaries(blockOwner);
                city.roadBlocks = VoronoiComputer.expandBoundary(rawRoads, 1);
            }

            city.isRoadsGenerated = true;
            System.out.println("[CityManager] Roads ready for " + cityId + ". took " + (System.currentTimeMillis() - start) + "ms. Blocks: " + (city.roadBlocks != null ? city.roadBlocks.size() : 0));
        }
    }
}