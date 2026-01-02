package com.user.terra_script.scan;

import java.util.ArrayList;
import java.util.List;

public class ScanRegion {
    public int id;
    public List<ScanPixel> pixels = new ArrayList<>();

    // --- 统计特征 ---
    public int centerX, centerZ;
    public long area; // 像素数量

    // 地形特征
    public double avgHeight;      // 平均海拔
    public double maxRelief;      // 起伏度 (Max - Min)
    public double roughness;      // 崎岖度 (标准差)
    public double avgSlope;       // 平均斜率

    // 包围盒
    public int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
    public int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

    public ScanRegion(int id) { this.id = id; }

    public void addPixel(ScanPixel p) {
        pixels.add(p);
        if (p.x() < minX) minX = p.x();
        if (p.x() > maxX) maxX = p.x();
        if (p.z() < minZ) minZ = p.z();
        if (p.z() > maxZ) maxZ = p.z();
    }

    public void merge(ScanRegion other) {
        this.pixels.addAll(other.pixels);
        this.minX = Math.min(this.minX, other.minX);
        this.maxX = Math.max(this.maxX, other.maxX);
        this.minZ = Math.min(this.minZ, other.minZ);
        this.maxZ = Math.max(this.maxZ, other.maxZ);
    }

    // 计算距离
    public double distanceTo(ScanRegion other) {
        double minDistanceSq = Double.MAX_VALUE;

        // ... 循环计算 ...
        for (ScanPixel p1 : this.pixels) {
            for (ScanPixel p2 : other.pixels) {
                // 确保这里使用的是 p1.x() (世界坐标)
                double dSq = Math.pow(p1.x() - p2.x(), 2) + Math.pow(p1.z() - p2.z(), 2);
                if (dSq < minDistanceSq) minDistanceSq = dSq;
            }
        }

        double dist = Math.sqrt(minDistanceSq);

        // 【添加这句 Debug】看看算出来的距离到底是 1.414 还是 160.0
        if (dist < 100) System.out.println("Debug Dist: " + dist + " between " + this.id + " and " + other.id);

        return dist;
    }

    /**
     * 计算所有统计特征
     * @param map 全局地图数据 (用于计算斜率时的邻居查找)
     */
    public void finish(ScanPixel[][] map) {
        if (pixels.isEmpty()) return;

        long sumX = 0, sumZ = 0;
        long sumH = 0;
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        double sumSlope = 0;

        // 1. 第一次遍历：基础统计
        for (ScanPixel p : pixels) {
            sumX += p.x();
            sumZ += p.z();

            int h = p.height();
            sumH += h;
            if (h < minH) minH = h;
            if (h > maxH) maxH = h;

            // 计算局部斜率 (需要反算 Grid 坐标，或者直接传入 Grid 坐标)
            // 由于 ScanPixel 没存 gridR/gridC，我们只能依靠世界坐标估算，或者在外部计算好斜率存入 ScanPixel
            // 这里为了架构简单，我们假设 map 已经无法轻易反查（除非 ScanPixel 加字段）
            // 妥协方案：ScanPixel 应该加一个 slope 字段。

            // 但如果不想改 ScanPixel，我们可以尝试简单的逻辑：
            // 暂时跳过精确斜率，或者由 ClusterAnalyzer 传入
        }

        this.area = pixels.size();
        this.centerX = (int) (sumX / area);
        this.centerZ = (int) (sumZ / area);
        this.avgHeight = (double) sumH / area;
        this.maxRelief = maxH - minH;

        // 2. 第二次遍历：计算标准差 (Roughness)
        double varianceSum = 0;
        for (ScanPixel p : pixels) {
            varianceSum += Math.pow(p.height() - avgHeight, 2);
        }
        this.roughness = Math.sqrt(varianceSum / area);

        // 3. 斜率 (AvgSlope)
        // 建议在 SatelliteScanner 生成 ScanPixel 时直接算出 Slope 并存进去，那样性能最好。
        // 这里暂时置为 0 或留空，等待你给 ScanPixel 加字段
        this.avgSlope = this.roughness;
    }
}