package com.user.terra_script.world.city;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.config.ForbiddenZoneConfig;
import com.user.terra_script.domain.world.scan.ScanPixel;
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

    // 全局城市占领�?(防止新城市覆盖旧城市)
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
     * 创建并生成一个城�?
     */
    public CityInstance createCity(CityConfig config) {
        String uid = "city_" + config.centerX + "_" + config.centerZ;
        config.cityInstanceId = uid;

        CityInstance oldCity = null;
        if (cities.containsKey(uid)) {
            oldCity = cities.get(uid);
            for (Long chunkKey : oldCity.claimedChunks.keySet()) {
                globalCityChunkMap.remove(chunkKey);
            }
            System.out.println("[CityManager] Overwriting existing city: " + uid);
        }

        CityInstance city = new CityInstance(uid, config);
        if (city.config.targetChunkCount <= 0) {
            city.config.targetChunkCount = estimateTargetChunks(city.config.territoryId);
        }
        try {
            // 执行扩张算法
            expandCity(city);
            validateCityWithinSovereignty(city);
        } catch (Exception e) {
            // Restore old city occupancy when overwrite fails.
            if (oldCity != null) {
                cities.put(oldCity.id, oldCity);
                for (Long chunkKey : oldCity.claimedChunks.keySet()) {
                    globalCityChunkMap.put(chunkKey, oldCity.id);
                }
            }
            throw e;
        }

        // 注册或修�?
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
        CityConfig.LayerLayout layout = city.getLayerLayout();

        // 1. 准备数据
        int startChunkX = city.config.centerX >> 4;
        int startChunkZ = city.config.centerZ >> 4;
        long centerKey = ChunkPos.asLong(startChunkX, startChunkZ);
        if (!TerritoryManager.isChunkWithinSovereignty(centerKey, city.config.territoryId)) {
            throw new IllegalArgumentException("City center chunk is outside territory sovereignty: " + city.config.territoryId);
        }

        // 优先队列 [cost, chunkX, chunkZ]
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        Map<Long, Double> costMap = new HashMap<>();

        // 初始�?
        pq.add(new double[]{0.0, startChunkX, startChunkZ});
        costMap.put(centerKey, 0.0);

        // 辅助：获取该国度的领土范�?(用于边界检�?
        // 只有属于�?territoryId 的区块才能扩�?
        // 这一步需要在 TerritoryManager 里加一�?helper method: isChunkOwnedBy(long chunkKey, String territoryId)

        int targetSize = city.config.targetChunkCount;
        int currentSize = 0;

        int[][] dirs = {{0,1}, {0,-1}, {1,0}, {-1,0}}; // 4方向，城市可以方正一点，或者用8方向更圆�?

        while (!pq.isEmpty() && currentSize < targetSize) {
            double[] curr = pq.poll();
            double currentCost = curr[0];
            int cx = (int) curr[1];
            int cz = (int) curr[2];
            long key = ChunkPos.asLong(cx, cz);

            // 如果已经被更低成本的处理过，跳过
            if (costMap.containsKey(key) && costMap.get(key) < currentCost) continue;
            if (!TerritoryManager.isChunkWithinSovereignty(key, city.config.territoryId)) continue;

            // 真正接纳这个区块
            if (!city.claimedChunks.containsKey(key)) {
                // 默认先设为最外层，后面统一分层
                int lastIndex = layout.layers.size() - 1;
                String layerType = layout.layerAt(lastIndex).type;
                city.claimedChunks.put(key, new CityInstance.LayerAssignment(lastIndex, layerType));
                globalCityChunkMap.put(key, city.id);
                currentSize++;
            }

            // 扩散
            for (int[] d : dirs) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                long nKey = ChunkPos.asLong(nx, nz);

                // 检�?: 是否已被任何城市占领
                if (globalCityChunkMap.containsKey(nKey)) continue;

                // 检�?: 是否在国境线�?(重要!)
                if (!TerritoryManager.isChunkWithinSovereignty(nKey, city.config.territoryId)) continue;

                // 计算代价
                double moveCost = calculateCellCost(nx, nz, city.config, holder);
                double newCost = currentCost + moveCost;

                if (!costMap.containsKey(nKey) || newCost < costMap.get(nKey)) {
                    costMap.put(nKey, newCost);
                    pq.add(new double[]{newCost, nx, nz});
                }
            }
        }

        // 2. 后处理：按层权重分配占比（成本低的块优先分给内层）
        assignLayersByWeight(city, layout, costMap);

        computeBorderChunks(city);
        System.out.println("City " + city.id + " generated. Size: " + currentSize + " chunks.");
    }

    private void validateCityWithinSovereignty(CityInstance city) {
        if (city == null || city.claimedChunks == null || city.claimedChunks.isEmpty()) return;
        for (Long chunkKey : city.claimedChunks.keySet()) {
            if (chunkKey == null) continue;
            if (!TerritoryManager.isChunkWithinSovereignty(chunkKey, city.config.territoryId)) {
                throw new IllegalStateException("City expansion overflowed sovereignty boundary: " + city.id);
            }
        }
    }

    private void assignLayersByWeight(CityInstance city, CityConfig.LayerLayout layout, Map<Long, Double> costMap) {
        if (city.claimedChunks.isEmpty() || layout == null || layout.layers == null || layout.layers.isEmpty()) return;

        List<Long> ordered = new ArrayList<>(city.claimedChunks.keySet());
        ordered.sort(Comparator.comparingDouble(k -> costMap.getOrDefault(k, Double.MAX_VALUE)));

        int layerCount = layout.layers.size();
        int total = ordered.size();
        int[] weights = new int[layerCount];
        int weightSum = 0;
        for (int i = 0; i < layerCount; i++) {
            int w = Math.max(1, layout.layerAt(i).weight);
            weights[i] = w;
            weightSum += w;
        }
        if (weightSum <= 0) weightSum = layerCount;

        int[] quotas = new int[layerCount];
        double[] fractional = new double[layerCount];
        int assigned = 0;
        for (int i = 0; i < layerCount; i++) {
            double exact = (total * (double) weights[i]) / weightSum;
            int base = (int) Math.floor(exact);
            quotas[i] = base;
            fractional[i] = exact - base;
            assigned += base;
        }

        int remain = total - assigned;
        while (remain > 0) {
            int best = 0;
            double bestFrac = -1;
            for (int i = 0; i < layerCount; i++) {
                if (fractional[i] > bestFrac) {
                    bestFrac = fractional[i];
                    best = i;
                }
            }
            quotas[best]++;
            fractional[best] = -1;
            remain--;
        }

        int cursor = 0;
        for (int i = 0; i < layerCount; i++) {
            CityConfig.LayerConfig layer = layout.layerAt(i);
            int take = quotas[i];
            for (int k = 0; k < take && cursor < ordered.size(); k++, cursor++) {
                long key = ordered.get(cursor);
                city.claimedChunks.put(key, new CityInstance.LayerAssignment(i, layer.type));
            }
        }

        int fallbackIndex = layerCount - 1;
        String fallbackType = layout.layerAt(fallbackIndex).type;
        while (cursor < ordered.size()) {
            long key = ordered.get(cursor++);
            city.claimedChunks.put(key, new CityInstance.LayerAssignment(fallbackIndex, fallbackType));
        }
    }

    private int estimateTargetChunks(String territoryId) {
        try {
            if (territoryId == null || territoryId.isBlank()) return 240;
            var results = TerritoryManager.getAllResults();
            if (results == null) return 240;
            for (var r : results) {
                if (r == null || r.config == null || r.stats == null) continue;
                if (!territoryId.equals(r.config.id)) continue;
                long area = Math.max(0L, r.stats.area_pixels);
                int estimated = (int) Math.round(area * 0.08);
                return Math.max(80, Math.min(520, estimated));
            }
        } catch (Exception ignored) {
        }
        return 240;
    }

    /**
     * 计算单步扩张代价
     */
    private double calculateCellCost(int chunkX, int chunkZ, CityConfig config, ScanResultHolder holder) {
        // 基础代价
        double cost = 1.0;

        // 1. 获取地形数据 (取区块中心点采样)
        // 转换�?Global Scan 坐标�?(假设 holder.lastScanData 是以 (0,0) 为中�?
        int step = holder.scanStep;
        if (step <= 0) step = 1;

        int radiusBlocks = holder.scanRadiusChunks * 16;
        int globalMinX = -radiusBlocks;
        int globalMinZ = -radiusBlocks;

        // 区块中心的世界坐�?
        int worldX = (chunkX * 16) + 8;
        int worldZ = (chunkZ * 16) + 8;

        // 映射�?scanData 数组索引
        int gx = (worldX - globalMinX) / step;
        int gz = (worldZ - globalMinZ) / step;

        ScanPixel p = null;
        if (holder.lastScanData != null && gx >= 0 && gx < holder.lastScanData.length && gz >= 0 && gz < holder.lastScanData[0].length) {
            p = holder.lastScanData[gx][gz];
        }

        // 如果数据缺失，给一个中等惩罚，防止报错
        if (p == null) return 5.0;

        // 2. 偏好偏移 (Bias) - 引导城市向特定方向生�?
        int dx = worldX - config.centerX;
        int dz = worldZ - config.centerZ;

        // 简单的线性势�?
        if (config.bias == CityConfig.ExpansionBias.NORTH && dz > 0) cost += 1.5; // 往南走更贵
        if (config.bias == CityConfig.ExpansionBias.SOUTH && dz < 0) cost += 1.5;
        if (config.bias == CityConfig.ExpansionBias.EAST && dx < 0) cost += 1.5;
        if (config.bias == CityConfig.ExpansionBias.WEST && dx > 0) cost += 1.5;

        // 3. 地形代价
        // 这里只是粗略判断，因�?p 是单点采样�?
        // 理想情况应该�?Chunk �?16x16 的平均斜率，但那样太慢�?
        // 我们可以�?holder.lastScanData[gx][gz] 周围点的差值来估算宏观斜率�?

        // 4. 水域代价
        if (!p.isLand()) {
            if (config.bias == CityConfig.ExpansionBias.COASTAL) {
                cost += 0.5; // 沿海城市下水容易�?
            } else {
                cost += 10.0; // 内陆城市极难下水
            }
        } else {
            // 如果是陆地，但倾向于内�?(INLAND)，则离水越近代价越高�?
            // 这需要距离场计算，暂时忽略�?
        }

        return cost;
    }

    
    private void computeBorderChunks(CityInstance city) {
        city.borderChunks.clear();
        if (city.claimedChunks == null || city.claimedChunks.isEmpty()) return;

        CityConfig.LayerLayout layout = city.getLayerLayout();
        boolean useWallLayers = hasWallLayers(layout);

        for (long key : city.claimedChunks.keySet()) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);

            if (useWallLayers) {
                CityInstance.LayerAssignment assignment = city.claimedChunks.get(key);
                if (!isWallLayer(layout, assignment)) continue;
                if (isLayerBoundary(city, assignment.layerIndex, cx, cz)) {
                    city.borderChunks.add(key);
                }
            } else {
                if (!city.claimedChunks.containsKey(ChunkPos.asLong(cx + 1, cz)) ||
                        !city.claimedChunks.containsKey(ChunkPos.asLong(cx - 1, cz)) ||
                        !city.claimedChunks.containsKey(ChunkPos.asLong(cx, cz + 1)) ||
                        !city.claimedChunks.containsKey(ChunkPos.asLong(cx, cz - 1))) {
                    city.borderChunks.add(key);
                }
            }
        }
    }

    private boolean hasWallLayers(CityConfig.LayerLayout layout) {
        if (layout == null || layout.layers == null) return false;
        for (CityConfig.LayerConfig layer : layout.layers) {
            if (layer == null) continue;
            if (layer.wallLayer || layer.wall != null) return true;
        }
        return false;
    }

    private boolean isWallLayer(CityConfig.LayerLayout layout, CityInstance.LayerAssignment assignment) {
        if (layout == null || assignment == null) return false;
        CityConfig.LayerConfig layer = layout.layerAt(assignment.layerIndex);
        return layer.wallLayer || layer.wall != null;
    }

    private boolean isLayerBoundary(CityInstance city, int layerIndex, int cx, int cz) {
        long northKey = ChunkPos.asLong(cx, cz - 1);
        long southKey = ChunkPos.asLong(cx, cz + 1);
        long westKey = ChunkPos.asLong(cx - 1, cz);
        long eastKey = ChunkPos.asLong(cx + 1, cz);

        return isDifferentLayer(city, layerIndex, northKey)
                || isDifferentLayer(city, layerIndex, southKey)
                || isDifferentLayer(city, layerIndex, westKey)
                || isDifferentLayer(city, layerIndex, eastKey);
    }

    private boolean isDifferentLayer(CityInstance city, int layerIndex, long neighborKey) {
        CityInstance.LayerAssignment neighbor = city.claimedChunks.get(neighborKey);
        if (neighbor == null) return true;
        return neighbor.layerIndex != layerIndex;
    }
    
    public Set<Long> getForbiddenBlocksFromConfig(String cityId) {
        CityInstance city = cities.get(cityId);
        if (city == null) return Collections.emptySet();

        var zones = ForbiddenZoneConfig.load();
        var forbiddenChunks = ForbiddenZoneConfig.toChunkKeys(zones);
        if (forbiddenChunks.isEmpty()) return Collections.emptySet();

        Set<Long> blocks = new HashSet<>();
        for (long chunkKey : forbiddenChunks) {
            if (!city.claimedChunks.containsKey(chunkKey)) continue;
            int cx = ChunkPos.getX(chunkKey);
            int cz = ChunkPos.getZ(chunkKey);
            int baseX = cx * 16;
            int baseZ = cz * 16;
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    long blockKey = packBlock(baseX + dx, baseZ + dz);
                    blocks.add(blockKey);
                }
            }
        }
        return blocks;
    }

    private long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
    private void saveToFile() {
        new Thread(() -> {
            try {
                JsonArray arr = new JsonArray();
                for (CityInstance city : cities.values()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", city.id);

                    if (city.config != null) {
                        obj.addProperty("territory", city.config.territoryId);
                        obj.addProperty("continent_id", city.config.continentId);
                        obj.addProperty("center_x", city.config.centerX);
                        obj.addProperty("center_z", city.config.centerZ);
                        obj.addProperty("target_size", city.config.targetChunkCount);
                        if (city.config.bias != null) obj.addProperty("bias", city.config.bias.name());
                        if (city.config.ecology != null) obj.addProperty("ecology", city.config.ecology.name());
                        if (city.config.density != null) obj.addProperty("density", city.config.density);
                    }

                    CityConfig.LayerLayout layout = city.getLayerLayout();
                    obj.addProperty("layer_count", layout.layers.size());
                    JsonArray thresholdArr = new JsonArray();
                    for (double t : layout.thresholds) thresholdArr.add(t);
                    obj.add("layer_thresholds", thresholdArr);
                    JsonArray layerArr = new JsonArray();
                    for (CityConfig.LayerConfig layer : layout.layers) {
                        JsonObject layerObj = new JsonObject();
                        layerObj.addProperty("name", layer.name);
                        layerObj.addProperty("type", layer.type);
                        layerObj.addProperty("density", layer.density);
                        layerObj.addProperty("weight", layer.weight);
                        layerObj.addProperty("is_wall", layer.isWall);
                        if (layer.ecology != null) layerObj.addProperty("ecology", layer.ecology.name());
                        layerObj.addProperty("wall_layer", layer.wallLayer);
                        if (layer.wall != null) {
                            JsonObject wallObj = new JsonObject();
                            wallObj.addProperty("type", layer.wall.type);
                            wallObj.addProperty("thickness_blocks", layer.wall.thicknessBlocks);
                            if (layer.wall.gateCount != null) {
                                JsonArray gateArr = new JsonArray();
                                for (int g : layer.wall.gateCount) gateArr.add(g);
                                wallObj.add("gate_count", gateArr);
                            }
                            layerObj.add("wall", wallObj);
                        }
                        layerArr.add(layerObj);
                    }
                    obj.add("layers", layerArr);

                    JsonArray chunks = new JsonArray();
                    city.claimedChunks.forEach((key, type) -> {
                        JsonObject c = new JsonObject();
                        c.addProperty("x", ChunkPos.getX(key));
                        c.addProperty("z", ChunkPos.getZ(key));
                        String layerType = type != null ? CityConfig.normalizeLayerType(type.layerType) : null;
                        if (layerType == null || layerType.isBlank()) layerType = "BUFFER";
                        int layerIndex = type != null ? type.layerIndex : (layout.layers.size() - 1);
                        c.addProperty("t", layerType != null && !layerType.isBlank() ? layerType.substring(0, 1) : "B");
                        c.addProperty("type", layerType);
                        c.addProperty("layer", layerIndex);
                        chunks.add(c);
                    });
                    obj.add("chunks", chunks);

                    // 2. 【新增】导�?Districts (撒点数据)
                    if (city.districts != null) {
                        JsonArray dists = new JsonArray();
                        for (var d : city.districts) {
                            JsonObject dObj = new JsonObject();
                            dObj.addProperty("id", d.id);
                            // 保留 2 位小数即�?
                            dObj.addProperty("x", Math.round(d.centerX * 100) / 100.0);
                            dObj.addProperty("z", Math.round(d.centerZ * 100) / 100.0);
                            dObj.addProperty("type", d.zoneType);
                            dObj.addProperty("layer", d.layerIndex);
                            if (d.density != null) dObj.addProperty("density", d.density);
                            // 环境属�?
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
            if (json == null || json.isBlank()) return; // 空文件保�?

            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr == null) return;

            cities.clear();
            globalCityChunkMap.clear();

            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();

                CityConfig cfg = new CityConfig();
                // 必须字段，如果没有则跳过该条目或赋默认�?
                if (!obj.has("id")) continue;
                cfg.cityInstanceId = obj.get("id").getAsString();

                // 可选字段，带默认�?
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

                if (obj.has("density") && !obj.get("density").isJsonNull()) {
                    cfg.density = obj.get("density").getAsString();
                }

                if (obj.has("layer_count")) cfg.layerCount = obj.get("layer_count").getAsInt();
                if (obj.has("layer_thresholds")) {
                    cfg.layerThresholds = new ArrayList<>();
                    for (JsonElement elThreshold : obj.getAsJsonArray("layer_thresholds")) {
                        cfg.layerThresholds.add(elThreshold.getAsDouble());
                    }
                }
                if (obj.has("layers")) {
                    cfg.layers = GSON.fromJson(obj.get("layers"), new TypeToken<List<CityConfig.LayerConfig>>(){}.getType());
                }

                CityInstance city = new CityInstance(cfg.cityInstanceId, cfg);
                CityConfig.LayerLayout layout = city.getLayerLayout();

                // 恢复区块
                if (obj.has("chunks")) {
                    for (JsonElement cEl : obj.getAsJsonArray("chunks")) {
                        JsonObject c = cEl.getAsJsonObject();
                        int x = c.get("x").getAsInt();
                        int z = c.get("z").getAsInt();
                        String typeStr = null;
                        if (c.has("type") && !c.get("type").isJsonNull()) {
                            typeStr = c.get("type").getAsString();
                        } else if (c.has("t")) {
                            String shortType = c.get("t").getAsString();
                            if ("C".equalsIgnoreCase(shortType)) typeStr = "CORE";
                            else if ("U".equalsIgnoreCase(shortType)) typeStr = "URBAN";
                            else if ("R".equalsIgnoreCase(shortType)) typeStr = "RING";
                            else typeStr = "BUFFER";
                        }
                        String layerType = CityConfig.normalizeLayerType(typeStr);
                        if (layerType == null || layerType.isBlank()) layerType = "BUFFER";
                        int layerIndex = c.has("layer") ? c.get("layer").getAsInt() : -1;
                        if (layerIndex < 0) {
                            layerIndex = layout.findLayerIndexByType(layerType);
                            if (layerIndex < 0) layerIndex = layout.layers.size() - 1;
                        }

                        long key = ChunkPos.asLong(x, z);
                        city.claimedChunks.put(key, new CityInstance.LayerAssignment(layerIndex, layerType));
                        globalCityChunkMap.put(key, city.id);
                    }
                }

                // 恢复区划 (如果之前存了的话，没存就重新�?
                if (obj.has("districts")) {
                    // TODO: 解析 district 数据
                    // 为了简化，这里可以不解析，而是调用 VoronoiComputer.computeDistricts(city) 重新生成
                    // 只要种子随机数是一样的，结果就是一样的
                    city.districts = VoronoiComputer.computeDistricts(city);
                } else {
                    // 兼容旧数�?
                    city.districts = VoronoiComputer.computeDistricts(city);
                }

                cities.put(city.id, city);
            }
            System.out.println("[CityManager] Loaded " + cities.size() + " cities from disk.");

        } catch (Exception e) {
            e.printStackTrace();
            // 如果文件损坏，可以选择删除它，或者只是报�?
            System.err.println("[CityManager] Failed to load cities: " + e.getMessage());
        }
    }

    /**
     * 确保该城市的道路数据已生�?(懒加�?
     */
    public void ensureRoadsGenerated(String cityId) {
        CityInstance city = cities.get(cityId);
        if (city == null || city.isRoadsGenerated) return;

        synchronized (city) { // 防止多线程重复计�?
            if (city.isRoadsGenerated) return;

            System.out.println("[CityManager] Lazy-generating roads for " + cityId + "...");
            long start = System.currentTimeMillis();

            if (city.districts == null || city.districts.isEmpty()) {
                // 如果区划也没生成，先生成区划
                city.districts = VoronoiComputer.computeDistricts(city);
            }

            if (!city.districts.isEmpty()) {
                VoronoiComputer.RoadPlan plan = VoronoiComputer.computeSmoothRoadPlan(city.districts);
                city.roadBlocks = plan.blocks;
                city.roadHeights = plan.heights;
                city.roadSlabBlocks = plan.slabBlocks;
            }

            city.isRoadsGenerated = true;
            System.out.println("[CityManager] Roads ready for " + cityId + ". took " + (System.currentTimeMillis() - start) + "ms. Blocks: " + (city.roadBlocks != null ? city.roadBlocks.size() : 0));
        }
    }
}







