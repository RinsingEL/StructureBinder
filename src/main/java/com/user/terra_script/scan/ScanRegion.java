package com.user.terra_script.scan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScanRegion {
    public int id;
    public List<ScanPixel> pixels = new ArrayList<>();

    // 统计数据
    public int centerX, centerZ;
    public long approxArea;
    public String mainBiome;

    public ScanRegion(int id) {
        this.id = id;
    }

    // 修复：添加 addPixel 方法
    public void addPixel(ScanPixel p) {
        pixels.add(p);
    }

    // 修复：添加 finish 方法，用于计算统计数据
    public void finish() {
        if (pixels.isEmpty()) return;

        long sumX = 0, sumZ = 0;
        Map<String, Integer> biomeCounts = new HashMap<>();

        for (ScanPixel p : pixels) {
            sumX += p.x();
            sumZ += p.z();

            // 统计群系出现次数
            biomeCounts.put(p.biomeId(), biomeCounts.getOrDefault(p.biomeId(), 0) + 1);
        }

        this.centerX = (int) (sumX / pixels.size());
        this.centerZ = (int) (sumZ / pixels.size());
        this.approxArea = pixels.size();

        // 找出出现次数最多的群系
        String mostFrequent = "unknown";
        int maxCount = -1;
        for (Map.Entry<String, Integer> entry : biomeCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                mostFrequent = entry.getKey();
            }
        }
        this.mainBiome = mostFrequent;
    }
}