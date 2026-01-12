package com.user.terra_script.util;

import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.data.ScanResultHolder.RegionCache;
import com.user.terra_script.scan.ScanPixel;
import com.user.terra_script.scan.ScanRegion;
import net.minecraft.nbt.*;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ScanDataIO {
    private static final String FILENAME = "terra_script_cache.dat";

    public static void saveAll() {
        var holder = ScanResultHolder.get();
        // 使用独立线程进行 IO 操作
        new Thread(() -> {
            try {
                CompoundTag root = new CompoundTag();

                // 1. 保存全局扫描像素数据 (Global Scan)
                if (holder.lastScanData != null) {
                    CompoundTag globalTag = new CompoundTag();
                    globalTag.putLong("seed", holder.seedUsed);
                    globalTag.putInt("radius", holder.scanRadiusChunks);
                    globalTag.putInt("step", holder.scanStep);
                    writePixelMatrix(globalTag, holder.lastScanData);
                    root.put("global", globalTag);
                }

                // 2. 保存宏观聚类分析结果 (Cluster Summaries)
                // 这里保存的是大陆的统计数据，而不是像素点，所以比较轻量
                if (holder.lastClusters != null && !holder.lastClusters.isEmpty()) {
                    root.put("clusters", saveClusters(holder.lastClusters));
                }
                // 保存海洋
                if (holder.lastOceanRegions != null && !holder.lastOceanRegions.isEmpty()) {
                    root.put("oceans", saveClusters(holder.lastOceanRegions));
                }

                // 3. 保存局部详细缓存 (Region Caches)
                if (!holder.regionCacheMap.isEmpty()) {
                    ListTag regionsList = new ListTag();
                    for (RegionCache cache : holder.regionCacheMap.values()) {
                        CompoundTag rTag = new CompoundTag();
                        rTag.putInt("id", cache.regionInfo.id);
                        rTag.putInt("minX", cache.minX);
                        rTag.putInt("minZ", cache.minZ);
                        rTag.putInt("w", cache.w);
                        rTag.putInt("h", cache.h);
                        rTag.putInt("step", cache.step);

                        writePixelMatrix(rTag, cache.detailData);

                        if (cache.slopeData != null)
                            rTag.putLongArray("slope", compressDoubleMatrix(cache.slopeData));
                        if (cache.roughnessData != null)
                            rTag.putLongArray("roughness", compressDoubleMatrix(cache.roughnessData));
                        if (cache.tpiData != null)
                            rTag.putLongArray("tpi", compressDoubleMatrix(cache.tpiData));

                        regionsList.add(rTag);
                    }
                    root.put("regions", regionsList);
                }

                File file = FMLPaths.GAMEDIR.get().resolve(FILENAME).toFile();
                NbtIo.writeCompressed(root, file);
                System.out.println("[DataIO] Saved cache: " + holder.regionCacheMap.size() + " regions, "
                        + (holder.lastClusters != null ? holder.lastClusters.size() : 0) + " clusters.");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "TerraScript-IO-Thread").start();
    }

    public static void loadInto(ScanResultHolder holder) {
        File file = FMLPaths.GAMEDIR.get().resolve(FILENAME).toFile();
        if (!file.exists()) return;

        try {
            System.out.println("[DataIO] Loading data from disk...");
            CompoundTag root = NbtIo.readCompressed(file);

            // 1. 恢复全局数据
            if (root.contains("global")) {
                CompoundTag globalTag = root.getCompound("global");
                holder.seedUsed = globalTag.getLong("seed");
                holder.scanRadiusChunks = globalTag.getInt("radius");
                holder.scanStep = globalTag.getInt("step");
                holder.lastScanData = readPixelMatrix(globalTag);
            }

            // 2. 恢复宏观聚类结果
            if (root.contains("clusters")) {
                holder.lastClusters = readClusters(root.getList("clusters", Tag.TAG_COMPOUND));
            }

            // 读取海洋
            if (root.contains("oceans")) {
                holder.lastOceanRegions = readClusters(root.getList("oceans", Tag.TAG_COMPOUND));
            }

            // 3. 恢复局部详细缓存
            if (root.contains("regions")) {
                ListTag list = root.getList("regions", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag rTag = list.getCompound(i);
                    int id = rTag.getInt("id");

                    ScanRegion dummyRegion = new ScanRegion(id);

                    int minX = rTag.getInt("minX");
                    int minZ = rTag.getInt("minZ");
                    int w = rTag.getInt("w");
                    int h = rTag.getInt("h");
                    int step = rTag.getInt("step");

                    RegionCache cache = new RegionCache(dummyRegion, minX, minZ, w, h, step);
                    cache.detailData = readPixelMatrix(rTag);

                    int cw = cache.detailData.length;
                    int ch = cache.detailData[0].length;

                    if (rTag.contains("slope"))
                        cache.slopeData = decompressDoubleMatrix(rTag.getLongArray("slope"), cw, ch);
                    if (rTag.contains("roughness"))
                        cache.roughnessData = decompressDoubleMatrix(rTag.getLongArray("roughness"), cw, ch);
                    if (rTag.contains("tpi"))
                        cache.tpiData = decompressDoubleMatrix(rTag.getLongArray("tpi"), cw, ch);

                    holder.regionCacheMap.put(id, cache);
                }
            }
            System.out.println("[DataIO] Load complete. Loaded " + (holder.lastClusters != null ? holder.lastClusters.size() : 0) + " clusters.");

        } catch (Exception e) {
            System.err.println("[DataIO] Failed to load cache: " + e.getMessage());
            e.printStackTrace();
            holder.clearAll();
        }
    }

    // --- 新增：聚类数据序列化逻辑 ---

    private static ListTag saveClusters(List<ScanRegion> regions) {
        ListTag list = new ListTag();
        for (ScanRegion r : regions) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("id", r.id);

            // 基础 Metrics
            tag.putLong("area", r.area);
            tag.putInt("cx", r.centerX);
            tag.putInt("cz", r.centerZ);
            tag.putInt("minX", r.minX); tag.putInt("maxX", r.maxX);
            tag.putInt("minZ", r.minZ); tag.putInt("maxZ", r.maxZ);

            // 地形
            tag.putDouble("avgH", r.avgHeight);
            tag.putDouble("maxR", r.maxRelief);
            tag.putDouble("rough", r.roughness);

            // 气候 (flatten 4x4 grid)
            tag.putDouble("avgT", r.avgTemp);
            tag.putFloat("minT", r.minTemp);
            tag.putFloat("maxT", r.maxTemp);
            if (r.climateGrid != null) {
                ListTag gridList = new ListTag();
                for (float[] row : r.climateGrid) {
                    for (float val : row) gridList.add(FloatTag.valueOf(val));
                }
                tag.put("climGrid", gridList);
            }

            // 生态 (Map -> List)
            ListTag ecoList = new ListTag();
            if (r.biomePercentages != null) {
                r.biomePercentages.forEach((biome, pct) -> {
                    CompoundTag bTag = new CompoundTag();
                    bTag.putString("id", biome);
                    bTag.putDouble("pct", pct);
                    ecoList.add(bTag);
                });
            }
            tag.put("biomes", ecoList);

            // Dominant List (可以直接从 Map 重建，但存一下方便)
            ListTag domList = new ListTag();
            if (r.dominantBiomes != null) {
                for (String b : r.dominantBiomes) domList.add(StringTag.valueOf(b));
            }
            tag.put("domBio", domList);

            // 稀有群系
            if (r.rareBiomes != null) {
                ListTag rareList = new ListTag();
                for (ScanRegion.RareBiomeLocation rare : r.rareBiomes) {
                    CompoundTag rt = new CompoundTag();
                    rt.putString("id", rare.id());
                    rt.putInt("x", rare.x());
                    rt.putInt("z", rare.z());
                    rareList.add(rt);
                }
                tag.put("rare", rareList);
            }

            list.add(tag);
        }
        return list;
    }

    private static List<ScanRegion> readClusters(ListTag list) {
        List<ScanRegion> regions = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            ScanRegion r = new ScanRegion(tag.getInt("id"));

            r.area = tag.getLong("area");
            r.centerX = tag.getInt("cx");
            r.centerZ = tag.getInt("cz");
            r.minX = tag.getInt("minX"); r.maxX = tag.getInt("maxX");
            r.minZ = tag.getInt("minZ"); r.maxZ = tag.getInt("maxZ");

            r.avgHeight = tag.getDouble("avgH");
            r.maxRelief = tag.getDouble("maxR");
            r.roughness = tag.getDouble("rough");

            r.avgTemp = tag.getDouble("avgT");
            r.minTemp = tag.getFloat("minT");
            r.maxTemp = tag.getFloat("maxT");

            if (tag.contains("climGrid")) {
                ListTag gridList = tag.getList("climGrid", Tag.TAG_FLOAT);
                if (gridList.size() == 16) {
                    r.climateGrid = new float[4][4];
                    for(int k=0; k<16; k++) {
                        r.climateGrid[k/4][k%4] = gridList.getFloat(k);
                    }
                }
            }

            if (tag.contains("biomes")) {
                ListTag ecoList = tag.getList("biomes", Tag.TAG_COMPOUND);
                for (int k = 0; k < ecoList.size(); k++) {
                    CompoundTag bTag = ecoList.getCompound(k);
                    r.biomePercentages.put(bTag.getString("id"), bTag.getDouble("pct"));
                }
            }

            if (tag.contains("domBio")) {
                ListTag domList = tag.getList("domBio", Tag.TAG_STRING);
                for (int k = 0; k < domList.size(); k++) {
                    r.dominantBiomes.add(domList.getString(k));
                }
            }

            if (tag.contains("rare")) {
                ListTag rareList = tag.getList("rare", Tag.TAG_COMPOUND);
                for (int k = 0; k < rareList.size(); k++) {
                    CompoundTag rt = rareList.getCompound(k);
                    r.rareBiomes.add(new ScanRegion.RareBiomeLocation(
                            rt.getString("id"), rt.getInt("x"), rt.getInt("z")
                    ));
                }
            }

            regions.add(r);
        }
        return regions;
    }

    // --- 通用辅助方法 (保持不变) ---

    private static void writePixelMatrix(CompoundTag tag, ScanPixel[][] map) {
        if (map == null) return;
        tag.putInt("width", map.length);
        tag.putInt("height", map[0].length);
        ListTag list = new ListTag();
        for (int i = 0; i < map.length; i++) {
            for (int j = 0; j < map[0].length; j++) {
                ScanPixel p = map[i][j];
                CompoundTag pTag = new CompoundTag();
                if (p != null) {
                    pTag.putInt("x", p.x());
                    pTag.putInt("z", p.z());
                    pTag.putInt("h", p.height());
                    pTag.putString("b", p.biomeId());
                    pTag.putBoolean("l", p.isLand());
                    pTag.putFloat("t", p.temperature());
                }
                list.add(pTag);
            }
        }
        tag.put("pixels", list);
    }

    private static ScanPixel[][] readPixelMatrix(CompoundTag tag) {
        int w = tag.getInt("width");
        int h = tag.getInt("height");
        ScanPixel[][] map = new ScanPixel[w][h];
        ListTag list = tag.getList("pixels", Tag.TAG_COMPOUND);
        if (list.size() != w * h) return map;
        for (int k = 0; k < list.size(); k++) {
            CompoundTag pTag = list.getCompound(k);
            if (pTag.contains("x")) {
                float temp = pTag.contains("t") ? pTag.getFloat("t") : 0.5f;
                ScanPixel p = new ScanPixel(pTag.getInt("x"), pTag.getInt("z"), pTag.getInt("h"), pTag.getString("b"), pTag.getBoolean("l"), temp);
                map[k / h][k % h] = p;
            }
        }
        return map;
    }

    private static long[] compressDoubleMatrix(double[][] matrix) {
        int w = matrix.length;
        int h = matrix[0].length;
        long[] result = new long[w * h];
        for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) result[i * h + j] = Double.doubleToLongBits(matrix[i][j]);
        return result;
    }

    private static double[][] decompressDoubleMatrix(long[] data, int w, int h) {
        if (data.length != w * h) return null;
        double[][] matrix = new double[w][h];
        for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) matrix[i][j] = Double.longBitsToDouble(data[i * h + j]);
        return matrix;
    }

    public static void exportRegionsToJSON(List<ScanRegion> regions) {
        if (regions == null || regions.isEmpty()) return;
        CompletableFuture.runAsync(() -> {
            try {
                com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                com.google.gson.JsonArray array = new com.google.gson.JsonArray();
                for (ScanRegion r : regions) {
                    com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
                    obj.addProperty("id", r.id);
                    // 【修复】根据 ID 判断类型
                    if (r.id >= 1000) {
                        obj.addProperty("type", "OCEAN");
                    } else {
                        obj.addProperty("type", r.area < 500 ? "ISLAND" : "CONTINENT");
                    }

                    // Metrics
                    com.google.gson.JsonObject metrics = new com.google.gson.JsonObject();
                    metrics.addProperty("area_pixels", r.area);
                    metrics.addProperty("avg_height", Math.round(r.avgHeight));
                    metrics.addProperty("max_relief", r.maxRelief);
                    metrics.addProperty("roughness", String.format("%.2f", r.roughness));
                    metrics.addProperty("center_x", r.centerX);
                    metrics.addProperty("center_z", r.centerZ);
                    obj.add("metrics", metrics);

                    // Climate
                    com.google.gson.JsonObject climate = new com.google.gson.JsonObject();
                    climate.addProperty("avg_temp", String.format("%.2f", r.avgTemp));
                    climate.addProperty("temp_range", String.format("[%.2f, %.2f]", r.minTemp, r.maxTemp));

                    com.google.gson.JsonArray grid = new com.google.gson.JsonArray();
                    if (r.climateGrid != null) {
                        for(int z=0; z<4; z++) {
                            com.google.gson.JsonArray row = new com.google.gson.JsonArray();
                            for(int x=0; x<4; x++) row.add(Float.parseFloat(String.format("%.1f", r.climateGrid[x][z])));
                            grid.add(row);
                        }
                    }
                    climate.add("temp_distribution_4x4", grid);
                    obj.add("climate", climate);

                    // Ecology
                    com.google.gson.JsonObject ecology = new com.google.gson.JsonObject();
                    com.google.gson.JsonArray dom = new com.google.gson.JsonArray();
                    if (r.biomePercentages != null) {
                        r.biomePercentages.forEach((id, pct) -> {
                            com.google.gson.JsonObject b = new com.google.gson.JsonObject();
                            b.addProperty("id", id);
                            b.addProperty("pct", pct);
                            dom.add(b);
                        });
                    }
                    ecology.add("dominant_biomes", dom);

                    if (r.rareBiomes != null && !r.rareBiomes.isEmpty()) {
                        com.google.gson.JsonArray rare = new com.google.gson.JsonArray();
                        for(var rb : r.rareBiomes) {
                            com.google.gson.JsonObject b = new com.google.gson.JsonObject();
                            b.addProperty("id", rb.id());
                            b.addProperty("pos", rb.x() + "," + rb.z());
                            rare.add(b);
                        }
                        ecology.add("rare_finds", rare);
                    }
                    obj.add("ecology", ecology);

                    array.add(obj);
                }
                File file = FMLPaths.CONFIGDIR.get().resolve("terra_script_regions_debug.json").toFile();
                java.nio.file.Files.writeString(file.toPath(), gson.toJson(array));
                System.out.println("[DataIO] Region debug data exported.");
            } catch (Exception e) { e.printStackTrace(); }
        });
    }
}