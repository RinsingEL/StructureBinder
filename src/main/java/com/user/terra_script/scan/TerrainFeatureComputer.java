package com.user.terra_script.scan;

import java.util.stream.IntStream;

public class TerrainFeatureComputer {
    public static double[][] computeSlope(ScanPixel[][] map, boolean ignoreOcean) {
        int w = map.length;
        int h = map[0].length;
        double[][] slopeMap = new double[w][h];

        // 并行计算
        IntStream.range(0, w).parallel().forEach(i -> {
            for (int j = 0; j < h; j++) {
                // 边界检查优化
                if (i >= w - 1 || j >= h - 1) {
                    slopeMap[i][j] = 0;
                    continue;
                }

                ScanPixel p = map[i][j];
                ScanPixel px = map[i + 1][j];
                ScanPixel pz = map[i][j + 1];

                if (p == null || px == null || pz == null) continue;

                if ((ignoreOcean && !p.isLand())) {
                    slopeMap[i][j] = 0;
                    continue;
                }

                double dx = Math.abs(p.height() - px.height());
                double dz = Math.abs(p.height() - pz.height());
                slopeMap[i][j] = Math.sqrt(dx * dx + dz * dz);
            }
        });
        return slopeMap;
    }

    public static double[][] computeRoughness(ScanPixel[][] map, int kernelRadius, boolean ignoreOcean) {
        int w = map.length;
        int h = map[0].length;
        double[][] roughMap = new double[w][h];

        IntStream.range(0, w).parallel().forEach(i -> {
            for (int j = 0; j < h; j++) {
                if ((ignoreOcean && !map[i][j].isLand())) {
                    roughMap[i][j] = 0;
                    continue;
                }

                roughMap[i][j] = Math.sqrt(getLocalVariance(map, i, j, kernelRadius, w, h));
            }
        });
        return roughMap;
    }

    // getLocalVariance 保持不变 (它是被内部调用的，不需要改并行)
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

    /**
     * 计算地形位置指数 (TPI)
     * TPI = 当前点高度 - 局部平均高度
     * 正值：山脊/山顶
     * 负值：山谷/盆地
     * 接近0：平原/恒定斜坡
     */
    public static double[][] computeTPI(ScanPixel[][] map, int kernelRadius, boolean ignoreOcean) {
        int w = map.length;
        int h = map[0].length;
        double[][] tpiMap = new double[w][h];

        // 并行计算
        java.util.stream.IntStream.range(0, w).parallel().forEach(i -> {
            for (int j = 0; j < h; j++) {
                ScanPixel p = map[i][j];

                // 海洋检查
                if (p == null || (ignoreOcean && !p.isLand())) {
                    tpiMap[i][j] = 0;
                    continue;
                }

                // 获取局部平均高度
                double localMean = getLocalMean(map, i, j, kernelRadius, w, h, ignoreOcean);
                tpiMap[i][j] = p.height() - localMean;
            }
        });
        return tpiMap;
    }

    // 辅助方法：计算局部平均值
    private static double getLocalMean(ScanPixel[][] map, int cx, int cy, int r, int w, int h, boolean ignoreOcean) {
        int count = 0;
        double sum = 0;

        for (int i = cx - r; i <= cx + r; i++) {
            for (int j = cy - r; j <= cy + r; j++) {
                // 边界检查
                if (i >= 0 && i < w && j >= 0 && j < h) {
                    ScanPixel p = map[i][j];
                    if (p != null) {
                        // 如果忽略海洋，且当前点是海，则不计入平均
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