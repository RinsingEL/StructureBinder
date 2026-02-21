package com.user.terra_script.util;

import com.user.terra_script.domain.world.scan.ScanPixel;

public class TerrainFeatureComputer {

    // 斜率计算 (移除 parallel)
    public static double[][] computeSlope(ScanPixel[][] map, int step, boolean ignoreOcean) {
        int w = map.length;
        int h = map[0].length;
        double[][] slopeMap = new double[w][h];
        double run = Math.max(1, step);

        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                ScanPixel p = map[i][j];
                // 边界与海洋检查
                if (p == null || (ignoreOcean && !p.isLand()) || i >= w - 1 || j >= h - 1) {
                    slopeMap[i][j] = 0;
                    continue;
                }

                ScanPixel px = map[i + 1][j];
                ScanPixel pz = map[i][j + 1];
                if (px == null || pz == null) continue;

                double dx = Math.abs(p.height() - px.height()) / run;
                double dz = Math.abs(p.height() - pz.height()) / run;
                slopeMap[i][j] = Math.sqrt(dx * dx + dz * dz);
            }
        }
        return slopeMap;
    }

    // 崎岖度计算 (移除 parallel)
    public static double[][] computeRoughness(ScanPixel[][] map, int step, int kernelRadius, boolean ignoreOcean) {
        int w = map.length;
        int h = map[0].length;
        double[][] roughMap = new double[w][h];
        double run = Math.max(1, step);

        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                ScanPixel p = map[i][j];
                if (p == null || (ignoreOcean && !p.isLand())) {
                    roughMap[i][j] = 0;
                    continue;
                }
                roughMap[i][j] = Math.sqrt(getLocalVariance(map, i, j, kernelRadius, w, h)) / run;
            }
        }
        return roughMap;
    }

    // TPI 计算 (移除 parallel)
    public static double[][] computeTPI(ScanPixel[][] map, int kernelRadius, boolean ignoreOcean) {
        int w = map.length;
        int h = map[0].length;
        double[][] tpiMap = new double[w][h];

        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                ScanPixel p = map[i][j];
                if (p == null || (ignoreOcean && !p.isLand())) {
                    tpiMap[i][j] = 0;
                    continue;
                }
                double localMean = getLocalMean(map, i, j, kernelRadius, w, h, ignoreOcean);
                tpiMap[i][j] = p.height() - localMean;
            }
        }
        return tpiMap;
    }

    // 辅助方法
    private static double getLocalVariance(ScanPixel[][] map, int cx, int cy, int r, int w, int h) {
        int count = 0;
        double sum = 0;
        for (int i = cx - r; i <= cx + r; i++) {
            for (int j = cy - r; j <= cy + r; j++) {
                if (i >= 0 && i < w && j >= 0 && j < h && map[i][j] != null) {
                    sum += map[i][j].height();
                    count++;
                }
            }
        }
        if (count == 0) return 0;
        double avg = sum / count;
        double sumSqDiff = 0;
        for (int i = cx - r; i <= cx + r; i++) {
            for (int j = cy - r; j <= cy + r; j++) {
                if (i >= 0 && i < w && j >= 0 && j < h && map[i][j] != null) {
                    double diff = map[i][j].height() - avg;
                    sumSqDiff += diff * diff;
                }
            }
        }
        return sumSqDiff / count;
    }

    private static double getLocalMean(ScanPixel[][] map, int cx, int cy, int r, int w, int h, boolean ignoreOcean) {
        int count = 0;
        double sum = 0;
        for (int i = cx - r; i <= cx + r; i++) {
            for (int j = cy - r; j <= cy + r; j++) {
                if (i >= 0 && i < w && j >= 0 && j < h) {
                    ScanPixel p = map[i][j];
                    if (p != null) {
                        if (ignoreOcean && !p.isLand()) continue;
                        sum += p.height();
                        count++;
                    }
                }
            }
        }
        return (count == 0) ? 0 : sum / count;
    }
}
