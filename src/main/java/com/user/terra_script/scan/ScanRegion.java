package com.user.terra_script.scan;

import com.user.terra_script.util.BiomeLibrary;

import java.util.*;
import java.util.stream.Collectors;

public class ScanRegion {
    public int id;
    public List<ScanPixel> pixels = new ArrayList<>();

    // --- 1. 基础几何特征 ---
    public int centerX, centerZ;
    public long area; // 像素数量
    public int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
    public int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

    // --- 2. 地形特征 ---
    public double avgHeight;
    public int minHeight = Integer.MAX_VALUE;
    public int maxHeight = Integer.MIN_VALUE;
    public double maxRelief;      // 高差 (Max - Min)
    public double roughness;      // 崎岖度 (标准差)

    // --- 3. 气候特征 (新增) ---
    public double avgTemp;
    public float minTemp = Float.MAX_VALUE;
    public float maxTemp = -Float.MAX_VALUE;
    public float[][] climateGrid; // 4x4 宏观气候分布图 (用于判断方位气候)

    // --- 4. 生态特征 (新增) ---
    // 存储群系ID及其占比 (例如 "minecraft:plains" -> 0.45)
    public Map<String, Double> biomePercentages = new LinkedHashMap<>();
    public List<String> dominantBiomes = new ArrayList<>(); // 前 5 名
    public List<RareBiomeLocation> rareBiomes = new ArrayList<>(); // 稀有群系坐标

    public record RareBiomeLocation(String id, int x, int z) {}

    public ScanRegion(int id) { this.id = id; }

    public void addPixel(ScanPixel p) {
        pixels.add(p);
        updateBounds(p);
    }

    public void merge(ScanRegion other) {
        this.pixels.addAll(other.pixels);
        this.minX = Math.min(this.minX, other.minX);
        this.maxX = Math.max(this.maxX, other.maxX);
        this.minZ = Math.min(this.minZ, other.minZ);
        this.maxZ = Math.max(this.maxZ, other.maxZ);
    }

    private void updateBounds(ScanPixel p) {
        if (p.x() < minX) minX = p.x();
        if (p.x() > maxX) maxX = p.x();
        if (p.z() < minZ) minZ = p.z();
        if (p.z() > maxZ) maxZ = p.z();
    }

    /**
     * 计算到另一个区域的最短距离 (优化版)
     */
    public double distanceTo(ScanRegion other) {
        // 1. 包围盒快速排斥 (AABB Check)
        // 如果两个包围盒在 X 轴或 Z 轴上的间距非常大，直接返回估算距离，不再遍历像素
        // 这里的 threshold 是为了防止误判，给一个稍微宽一点的缓冲区
        int fastThreshold = 200;

        int dx = 0;
        if (this.maxX < other.minX) dx = other.minX - this.maxX;
        else if (other.maxX < this.minX) dx = this.minX - other.maxX;

        int dz = 0;
        if (this.maxZ < other.minZ) dz = other.minZ - this.maxZ;
        else if (other.maxZ < this.minZ) dz = this.minZ - other.maxZ;

        // 如果包围盒距离已经很大，直接返回包围盒距离 (性能提升 O(N^2) -> O(1))
        if (dx > fastThreshold || dz > fastThreshold) {
            return Math.sqrt(dx * dx + dz * dz);
        }

        // 2. 如果包围盒挨得很近，再进行精确的像素级计算
        double minDistanceSq = Double.MAX_VALUE;
        for (ScanPixel p1 : this.pixels) {
            for (ScanPixel p2 : other.pixels) {
                double dSq = Math.pow(p1.x() - p2.x(), 2) + Math.pow(p1.z() - p2.z(), 2);
                if (dSq < minDistanceSq) minDistanceSq = dSq;
            }
        }
        return Math.sqrt(minDistanceSq);
    }

    /**
     * 计算所有统计特征 (Finish)
     * 在聚类完成后调用一次
     */
    public void finish(ScanPixel[][] map) {
        if (pixels.isEmpty()) return;

        long sumX = 0, sumZ = 0;
        long sumH = 0;
        double sumT = 0;

        Map<String, Integer> biomeCounts = new HashMap<>();

        // --- 第一次遍历：基础数据 ---
        for (ScanPixel p : pixels) {
            sumX += p.x();
            sumZ += p.z();
            sumH += p.height();
            sumT += p.temperature();

            if (p.height() < minHeight) minHeight = p.height();
            if (p.height() > maxHeight) maxHeight = p.height();

            if (p.temperature() < minTemp) minTemp = p.temperature();
            if (p.temperature() > maxTemp) maxTemp = p.temperature();

            // 统计群系
            biomeCounts.put(p.biomeId(), biomeCounts.getOrDefault(p.biomeId(), 0) + 1);

            // 记录稀有群系 (简单全量记录，导出时可截断)
            if (BiomeLibrary.isRare(p.biomeId())) {
                rareBiomes.add(new RareBiomeLocation(p.biomeId(), p.x(), p.z()));
            }
        }

        this.area = pixels.size();
        this.centerX = (int) (sumX / area);
        this.centerZ = (int) (sumZ / area);
        this.avgHeight = (double) sumH / area;
        this.avgTemp = sumT / area;
        this.maxRelief = maxHeight - minHeight;

        // --- 第二次遍历：标准差 (崎岖度) ---
        double varianceSum = 0;
        for (ScanPixel p : pixels) {
            varianceSum += Math.pow(p.height() - avgHeight, 2);
        }
        this.roughness = Math.sqrt(varianceSum / area);

        // --- 计算 4x4 气候网格 ---
        calculateClimateGrid();

        // --- 计算主导群系 ---
        calculateDominantBiomes(biomeCounts);

        // --- 稀有群系采样 (防止过多) ---
        if (rareBiomes.size() > 10) {
            List<RareBiomeLocation> sampled = new ArrayList<>();
            int step = rareBiomes.size() / 10;
            for(int i=0; i<rareBiomes.size(); i+=step) {
                sampled.add(rareBiomes.get(i));
                if(sampled.size() >= 10) break;
            }
            this.rareBiomes = sampled;
        }
    }

    private void calculateClimateGrid() {
        this.climateGrid = new float[4][4];
        int[][] counts = new int[4][4];
        int width = Math.max(1, maxX - minX);
        int height = Math.max(1, maxZ - minZ);

        for (ScanPixel p : pixels) {
            // 归一化位置 0.0 ~ 1.0
            double nx = (double)(p.x() - minX) / width;
            double nz = (double)(p.z() - minZ) / height;

            int gx = (int)(nx * 4);
            int gz = (int)(nz * 4);
            gx = Math.min(3, Math.max(0, gx));
            gz = Math.min(3, Math.max(0, gz));

            climateGrid[gx][gz] += p.temperature();
            counts[gx][gz]++;
        }

        for(int i=0; i<4; i++) {
            for(int j=0; j<4; j++) {
                if(counts[i][j] > 0) climateGrid[i][j] /= counts[i][j];
            }
        }
    }

    private void calculateDominantBiomes(Map<String, Integer> counts) {
        // 排序
        this.dominantBiomes = counts.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 计算百分比
        for (String id : dominantBiomes) {
            double pct = (double)counts.get(id) / area;
            // 保留3位小数
            biomePercentages.put(id, (double)Math.round(pct * 1000) / 1000);
        }
    }
}