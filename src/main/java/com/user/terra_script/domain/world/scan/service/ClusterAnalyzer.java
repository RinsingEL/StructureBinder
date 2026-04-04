package com.user.terra_script.domain.world.scan.service;

import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.domain.world.scan.ScanRegion;

import java.util.*;

public class ClusterAnalyzer {

    public enum TargetType {
        CONTINENT("Continents"),
        OCEAN("Oceans"),
        MOUNTAIN("Mountains");

        public final String name;
        TargetType(String name) { this.name = name; }
    }

    private record Coord(int r, int c) {}

    /**
     * 执行聚类分析
     * @param map 全局像素数据
     * @param type 聚类目标类型
     * @param minPixelSize 最小像素数（过滤噪点）
     * @param mergeDistance 归并距离（单位：方块距离，0表示不归并）
     * @return 聚类区域列表
     */
    public static List<ScanRegion> analyze(ScanPixel[][] map, TargetType type, int minPixelSize, int mergeDistance) {
        if (map == null || map.length == 0) return new ArrayList<>();

        // 1. 第一阶段：原子化识别 (找出所有连通的小块)
        List<ScanRegion> atomicRegions = findAtomicRegions(map, type);

        // 2. 第二阶段：距离归并 (如果启用)
        List<ScanRegion> finalRegions;
        if (mergeDistance > 0) {
            finalRegions = mergeRegions(atomicRegions, mergeDistance, minPixelSize, map);
        } else {
            finalRegions = filterBySize(atomicRegions, minPixelSize, map);
        }

        return finalRegions;
    }

    // 泛洪填充逻辑
    private static List<ScanRegion> findAtomicRegions(ScanPixel[][] map, TargetType type) {
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
                if (checkType(p, type, map, r, c)) {
                    ScanRegion region = new ScanRegion(++currentId);

                    stack.push(new Coord(r, c));
                    visited[r][c] = true;
                    region.addPixel(p);

                    while (!stack.isEmpty()) {
                        Coord curr = stack.pop();
                        checkNeighbor(map, visited, stack, curr.r + 1, curr.c, region, type);
                        checkNeighbor(map, visited, stack, curr.r - 1, curr.c, region, type);
                        checkNeighbor(map, visited, stack, curr.r, curr.c + 1, region, type);
                        checkNeighbor(map, visited, stack, curr.r, curr.c - 1, region, type);
                    }
                    regions.add(region);
                }
            }
        }
        return regions;
    }

    // 归并逻辑
    private static List<ScanRegion> mergeRegions(List<ScanRegion> atoms, int distThreshold, int minFinalSize, ScanPixel[][] map) {
        int n = atoms.size();
        if (n == 0) return new ArrayList<>();

        // 并查集初始化
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        // 两两比较距离
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                ScanRegion r1 = atoms.get(i);
                ScanRegion r2 = atoms.get(j);

                // 包围盒快速排斥
                if (!boundingBoxOverlap(r1, r2, distThreshold)) continue;

                // 精确距离检查
                if (r1.distanceTo(r2) <= distThreshold) {
                    union(parent, i, j);
                }
            }
        }

        // 聚合
        Map<Integer, ScanRegion> mergedMap = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            ScanRegion atom = atoms.get(i);

            if (!mergedMap.containsKey(root)) {
                // 必须创建新对象或克隆，这里简化直接使用第一个原子作为容器
                // 注意：如果 ScanRegion 是引用类型，这里需要小心
                // 这里的逻辑是：把所有子节点像素加到根节点上
                mergedMap.put(root, atom);
            } else {
                ScanRegion target = mergedMap.get(root);
                if (target != atom) {
                    target.merge(atom);
                }
            }
        }

        // 过滤并计算特征
        List<ScanRegion> result = new ArrayList<>();
        for (ScanRegion r : mergedMap.values()) {
            if (r.pixels.size() >= minFinalSize) {
                r.finish(map); // 计算统计特征 (海拔、粗糙度等)
                result.add(r);
            }
        }
        return result;
    }

    /**
     * 【新算法】基于距离场划分海洋
     * 将所有海洋像素分配给最近的大陆
     * @param map 全局地图
     * @param landRegions 已识别出的陆地列表
     * @return 一个新的二维数组，存储每个像素归属的 Region ID (无论是陆地还是海洋)
     */
    public static int[][] expandOceans(ScanPixel[][] map, List<ScanRegion> landRegions) {
        int w = map.length;
        int h = map[0].length;

        // 1. 初始化 ID 图 (0=未分配, >0=RegionID)
        int[][] idMap = new int[w][h];
        // 距离图 (用于记录到最近大陆的距离)
        double[][] distMap = new double[w][h];
        for(double[] row : distMap) Arrays.fill(row, Double.MAX_VALUE);

        // 优先队列: [distance, x, z, regionId]
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));

        // 2. 将所有陆地像素作为种子点加入队列
        for (ScanRegion r : landRegions) {
            for (ScanPixel p : r.pixels) {
                // 需要反算网格坐标 (这里假设 ScanPixel 存的是世界坐标，需要知道 offset)
                // 为了简化，我们假设 analyze 方法里已经把像素和网格对应好了，或者直接遍历 map
            }
        }

        // 更简单的做法：遍历全图，如果是陆地且属于某个Region，就作为种子
        // 为了高效，我们需要快速知道每个像素属于哪个 Region
        // 我们可以利用 ClusterAnalyzer 刚刚生成的 landRegions
        Map<ScanPixel, Integer> pixelToRegion = new HashMap<>();
        for (ScanRegion r : landRegions) for (ScanPixel p : r.pixels) pixelToRegion.put(p, r.id);

        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                ScanPixel p = map[i][j];
                if (p != null && pixelToRegion.containsKey(p)) {
                    int rid = pixelToRegion.get(p);
                    idMap[i][j] = rid;
                    distMap[i][j] = 0;
                    pq.add(new double[]{0, i, j, rid});
                }
            }
        }

        // 3. 多源 Dijkstra 泛洪 (填满海洋)
        int[][] dirs = {{0,1}, {0,-1}, {1,0}, {-1,0}};

        while (!pq.isEmpty()) {
            double[] curr = pq.poll();
            double d = curr[0];
            int cx = (int)curr[1];
            int cz = (int)curr[2];
            int rid = (int)curr[3];

            if (d > distMap[cx][cz]) continue;

            for (int[] dir : dirs) {
                int nx = cx + dir[0];
                int nz = cz + dir[1];

                if (nx >= 0 && nx < w && nz >= 0 && nz < h) {
                    // 如果是已经有主的陆地，跳过
                    // 如果是海洋(或未分配区域)，且找到了更近的路，更新
                    if (map[nx][nz] != null && !map[nx][nz].isLand()) {
                        double newDist = d + 1.0; // 简单距离，也可以加权
                        if (newDist < distMap[nx][nz]) {
                            distMap[nx][nz] = newDist;
                            idMap[nx][nz] = rid; // 标记这片海属于 rid
                            pq.add(new double[]{newDist, nx, nz, rid});
                        }
                    }
                }
            }
        }

        return idMap;
    }

    private static List<ScanRegion> filterBySize(List<ScanRegion> input, int minSize, ScanPixel[][] map) {
        List<ScanRegion> res = new ArrayList<>();
        for (ScanRegion r : input) {
            if (r.pixels.size() >= minSize) {
                r.finish(map);
                res.add(r);
            }
        }
        return res;
    }

    // --- 辅助方法 ---

    private static boolean checkType(ScanPixel p, TargetType type, ScanPixel[][] map, int r, int c) {
        if (p == null) return false;
        switch (type) {
            case CONTINENT: return p.isLand();
            case OCEAN: return !p.isLand();
            case MOUNTAIN:
                // 山脉判定：高度 > 80 且 局部斜率 > 2.0
                return p.height() > 80 && getLocalSlope(map, r, c) > 2.0;
        }
        return false;
    }

    private static void checkNeighbor(ScanPixel[][] map, boolean[][] visited, Stack<Coord> stack, int r, int c, ScanRegion region, TargetType type) {
        if (r < 0 || r >= map.length || c < 0 || c >= map[0].length) return;
        if (visited[r][c]) return;

        ScanPixel p = map[r][c];
        if (checkType(p, type, map, r, c)) {
            visited[r][c] = true;
            region.addPixel(p);
            stack.push(new Coord(r, c));
        }
    }

    private static int find(int[] parent, int i) {
        if (parent[i] == i) return i;
        return parent[i] = find(parent, parent[i]);
    }

    private static void union(int[] parent, int i, int j) {
        int rootI = find(parent, i);
        int rootJ = find(parent, j);
        if (rootI != rootJ) parent[rootI] = rootJ;
    }

    private static boolean boundingBoxOverlap(ScanRegion r1, ScanRegion r2, int dist) {
        if (r1.maxX + dist < r2.minX) return false;
        if (r1.minX - dist > r2.maxX) return false;
        if (r1.maxZ + dist < r2.minZ) return false;
        if (r1.minZ - dist > r2.maxZ) return false;
        return true;
    }

    private static double getLocalSlope(ScanPixel[][] map, int r, int c) {
        ScanPixel center = map[r][c];
        double maxDiff = 0;
        int[][] offsets = {{0,1}, {1,0}, {0,-1}, {-1,0}};
        for(int[] off : offsets) {
            int nr = r + off[0];
            int nc = c + off[1];
            if (nr >= 0 && nr < map.length && nc >= 0 && nc < map[0].length && map[nr][nc] != null) {
                maxDiff = Math.max(maxDiff, Math.abs(center.height() - map[nr][nc].height()));
            }
        }
        return maxDiff;
    }
}
