package com.user.terra_script.util;

import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.scan.ScanPixel;
import com.user.terra_script.scan.ScanRegion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public class ScanDataIO {
    private static final String FILENAME = "terra_script_cache.dat";

    // 保存方法 (保持不变，因为调用它时 holder 肯定已经初始化好了)
    public static void saveAll() {
        var holder = ScanResultHolder.get();
        if (holder.lastScanData == null) return;
        // ... (后面的保存逻辑完全保持不变) ...
        CompletableFuture.runAsync(() -> {
            try {
                CompoundTag root = new CompoundTag();
                CompoundTag globalTag = new CompoundTag();
                globalTag.putLong("seed", holder.seedUsed);
                globalTag.putInt("radius", holder.scanRadiusChunks);
                globalTag.putInt("step", holder.scanStep);
                writePixelMatrix(globalTag, holder.lastScanData);
                root.put("global", globalTag);

                if (holder.lastEditedRegion != null && holder.lastRegionDetailData != null) {
                    CompoundTag regionTag = new CompoundTag();
                    regionTag.putInt("id", holder.lastEditedRegion.id);
                    regionTag.putInt("minX", holder.lastRegionMinX);
                    regionTag.putInt("minZ", holder.lastRegionMinZ);
                    regionTag.putInt("w", holder.lastRegionW);
                    regionTag.putInt("h", holder.lastRegionH);
                    regionTag.putInt("step", holder.lastRegionStep);
                    writePixelMatrix(regionTag, holder.lastRegionDetailData);
                    if (holder.lastRegionSlopeData != null)
                        regionTag.putLongArray("slope", compressDoubleMatrix(holder.lastRegionSlopeData));
                    if (holder.lastRegionRoughnessData != null)
                        regionTag.putLongArray("roughness", compressDoubleMatrix(holder.lastRegionRoughnessData));
                    if (holder.lastRegionTpiData != null)
                        regionTag.putLongArray("tpi", compressDoubleMatrix(holder.lastRegionTpiData));
                    root.put("local", regionTag);
                }
                File file = FMLPaths.GAMEDIR.get().resolve(FILENAME).toFile();
                NbtIo.writeCompressed(root, file);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // 【关键修复】加载方法：接收 holder 实例作为参数
    public static void loadInto(ScanResultHolder holder) {
        File file = FMLPaths.GAMEDIR.get().resolve(FILENAME).toFile();
        if (!file.exists()) return;

        try {
            System.out.println("[DataIO] Loading data from disk...");
            CompoundTag root = NbtIo.readCompressed(file);

            // 注意：这里不再调用 ScanResultHolder.get()，而是使用传入的 holder

            // 1. 恢复全局数据
            if (root.contains("global")) {
                CompoundTag globalTag = root.getCompound("global");
                holder.seedUsed = globalTag.getLong("seed");
                holder.scanRadiusChunks = globalTag.getInt("radius");
                holder.scanStep = globalTag.getInt("step");
                holder.lastScanData = readPixelMatrix(globalTag);
            }

            // 2. 恢复局部数据
            if (root.contains("local")) {
                CompoundTag regionTag = root.getCompound("local");

                int rId = regionTag.getInt("id");
                holder.lastEditedRegion = new ScanRegion(rId);

                holder.lastRegionMinX = regionTag.getInt("minX");
                holder.lastRegionMinZ = regionTag.getInt("minZ");
                holder.lastRegionW = regionTag.getInt("w");
                holder.lastRegionH = regionTag.getInt("h");
                holder.lastRegionStep = regionTag.getInt("step");

                holder.lastRegionDetailData = readPixelMatrix(regionTag);

                int w = holder.lastRegionDetailData.length;
                int h = holder.lastRegionDetailData[0].length;

                if (regionTag.contains("slope"))
                    holder.lastRegionSlopeData = decompressDoubleMatrix(regionTag.getLongArray("slope"), w, h);
                if (regionTag.contains("roughness"))
                    holder.lastRegionRoughnessData = decompressDoubleMatrix(regionTag.getLongArray("roughness"), w, h);
                if (regionTag.contains("tpi"))
                    holder.lastRegionTpiData = decompressDoubleMatrix(regionTag.getLongArray("tpi"), w, h);
            }
            System.out.println("[DataIO] Load complete.");

        } catch (Exception e) {
            System.err.println("[DataIO] Failed to load cache: " + e.getMessage());
            // 失败时清空，使用传入的 holder
            holder.clearAll();
        }
    }

    // --- 辅助方法 (writePixelMatrix, readPixelMatrix, compress, decompress) 保持不变 ---
    // 为了节省篇幅，这里不重复粘贴，请保留你之前版本中的这几个私有方法
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
                ScanPixel p = new ScanPixel(pTag.getInt("x"), pTag.getInt("z"), pTag.getInt("h"), pTag.getString("b"), pTag.getBoolean("l"));
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
}