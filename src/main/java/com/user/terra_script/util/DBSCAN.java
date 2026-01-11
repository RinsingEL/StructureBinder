package com.user.terra_script.util;

import com.user.terra_script.scan.ScanPixel;
import java.util.*;

public class DBSCAN {

    // 内部包装类，记录点的位置和状态
    public static class Point {
        public final int x, z;
        public final ScanPixel data;
        public int clusterId = -1; // -1: 未分类, 0: 噪声, >0: 簇ID

        public Point(ScanPixel pixel) {
            this.x = pixel.x();
            this.z = pixel.z();
            this.data = pixel;
        }
    }

    /**
     * @param candidates 符合初步筛选条件的点列表
     * @param epsilon 邻域半径 (格)
     * @param minPts 最小点数
     * @return 聚类后的结果列表 (每个子列表代表一个地块)
     */
    public static List<List<Point>> cluster(List<ScanPixel> candidates, double epsilon, int minPts) {
        if (candidates.isEmpty()) return Collections.emptyList();

        // 1. 初始化点
        List<Point> points = new ArrayList<>();
        for (ScanPixel p : candidates) points.add(new Point(p));

        double epsSq = epsilon * epsilon;
        int currentClusterId = 0;

        // 2. 遍历所有点
        for (Point p : points) {
            if (p.clusterId != -1) continue; // 已处理

            List<Point> neighbors = getNeighbors(p, points, epsSq);

            if (neighbors.size() < minPts) {
                p.clusterId = 0; // 标记为噪声
                continue;
            }

            // 3. 发现核心点，开始创建新簇
            currentClusterId++;
            p.clusterId = currentClusterId;

            // 使用队列进行广度优先搜索扩展
            Queue<Point> queue = new LinkedList<>(neighbors);
            while (!queue.isEmpty()) {
                Point neighbor = queue.poll();

                if (neighbor.clusterId == 0) neighbor.clusterId = currentClusterId; // 噪点变边界点
                if (neighbor.clusterId != -1) continue; // 已处理

                neighbor.clusterId = currentClusterId;
                List<Point> nextNeighbors = getNeighbors(neighbor, points, epsSq);
                if (nextNeighbors.size() >= minPts) {
                    queue.addAll(nextNeighbors);
                }
            }
        }

        // 4. 按 ID 聚合结果
        Map<Integer, List<Point>> clusterMap = new HashMap<>();
        for (Point p : points) {
            if (p.clusterId > 0) {
                clusterMap.computeIfAbsent(p.clusterId, k -> new ArrayList<>()).add(p);
            }
        }

        return new ArrayList<>(clusterMap.values());
    }

    private static List<Point> getNeighbors(Point center, List<Point> all, double epsSq) {
        List<Point> neighbors = new ArrayList<>();
        for (Point p : all) {
            double dx = p.x - center.x;
            double dz = p.z - center.z;
            if (dx * dx + dz * dz <= epsSq) {
                neighbors.add(p);
            }
        }
        return neighbors;
    }
}