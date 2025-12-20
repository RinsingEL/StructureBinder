package com.user.terra_script.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class ClusterAnalyzer {

    public enum TargetType {
        CONTINENT("Continents"),
        OCEAN("Oceans"),
        MOUNTAIN("Mountains"); // 新增类型

        public final String name;
        TargetType(String name) { this.name = name; }
    }

    private record Coord(int r, int c) {}

    // 可以在这里加更多参数，为了简单，我们先把山脉阈值写死或通过重载传入
    // 如果想要 GUI 可配，可以把 slopeThreshold 也传进来
    public static List<ScanRegion> analyze(ScanPixel[][] map, TargetType type, int minPixelSize) {
        if (map == null || map.length == 0) return new ArrayList<>();

        int rows = map.length;
        int cols = map[0].length;
        boolean[][] visited = new boolean[rows][cols];
        List<ScanRegion> regions = new ArrayList<>();
        int currentId = 0;

        Stack<Coord> stack = new Stack<>();

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (visited[r][c]) continue;

                ScanPixel p = map[r][c];
                if (p == null) continue;

                boolean matches = false;

                // --- 聚类判别逻辑 ---
                switch (type) {
                    case CONTINENT -> matches = p.isLand();
                    case OCEAN -> matches = !p.isLand();
                    case MOUNTAIN -> {
                        // 山脉定义：高度 > 80 且 比较陡峭
                        // 注意：ScanData 是网格采样的，相邻像素的距离是 step
                        // 这里计算的是“网格梯度”，依然能很好地反应地形起伏
                        double slope = getLocalSlope(map, r, c);
                        matches = p.height() > 80 && slope > 3.0; // 阈值可微调
                    }
                }

                if (matches) {
                    ScanRegion region = new ScanRegion(++currentId);

                    stack.push(new Coord(r, c));
                    visited[r][c] = true;
                    region.addPixel(p);

                    while (!stack.isEmpty()) {
                        Coord current = stack.pop();
                        int cr = current.r;
                        int cc = current.c;
                        // 检查四邻域
                        checkNeighbor(map, visited, stack, cr + 1, cc, region, type);
                        checkNeighbor(map, visited, stack, cr - 1, cc, region, type);
                        checkNeighbor(map, visited, stack, cr, cc + 1, region, type);
                        checkNeighbor(map, visited, stack, cr, cc - 1, region, type);
                    }

                    if (region.pixels.size() >= minPixelSize) {
                        region.finish();
                        regions.add(region);
                    }
                }
            }
        }
        return regions;
    }

    private static void checkNeighbor(ScanPixel[][] map, boolean[][] visited, Stack<Coord> stack, int r, int c, ScanRegion region, TargetType type) {
        if (r < 0 || r >= map.length || c < 0 || c >= map[0].length) return;
        if (visited[r][c]) return;

        ScanPixel p = map[r][c];
        if (p == null) return;

        boolean matches = false;
        switch (type) {
            case CONTINENT -> matches = p.isLand();
            case OCEAN -> matches = !p.isLand();
            case MOUNTAIN -> {
                double slope = getLocalSlope(map, r, c);
                matches = p.height() > 200 && slope > 3.0;
            }
        }

        if (!matches) return;

        visited[r][c] = true;
        region.addPixel(p);
        stack.push(new Coord(r, c));
    }

    // 计算局部坡度（最大高度差）
    private static double getLocalSlope(ScanPixel[][] map, int r, int c) {
        ScanPixel center = map[r][c];
        double maxDiff = 0;

        // 简单采样右边和下边，计算最大差值
        if (r + 1 < map.length && map[r+1][c] != null) {
            maxDiff = Math.max(maxDiff, Math.abs(center.height() - map[r+1][c].height()));
        }
        if (c + 1 < map[0].length && map[r][c+1] != null) {
            maxDiff = Math.max(maxDiff, Math.abs(center.height() - map[r][c+1].height()));
        }
        return maxDiff;
    }
}