package com.user.terra_script.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.util.DBSCAN.Point;
import java.util.*;

public class AsciiMapGenerator {

    /**
     * 将一个簇转化为包含 ASCII 图和精确坐标映射的 JSON
     */
    public static JsonObject generate(int displayId, List<Point> points) {
        if (points.isEmpty()) return new JsonObject();

        // 1. 计算统计数据和极值
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        long sumX = 0, sumZ = 0;

        Point north = points.get(0), south = points.get(0), east = points.get(0), west = points.get(0);

        for (Point p : points) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.z < minZ) minZ = p.z;
            if (p.z > maxZ) maxZ = p.z;
            sumX += p.x;
            sumZ += p.z;

            if (p.z < north.z) north = p;
            if (p.z > south.z) south = p;
            if (p.x > east.x) east = p;
            if (p.x < west.x) west = p;
        }

        int width = maxX - minX + 1;
        int height = maxZ - minZ + 1;

        // 2. 动态计算缩放比例 (限制 ASCII 最大为 32 字符)
        int maxDim = Math.max(width, height);
        int scale = Math.max(1, (int) Math.ceil(maxDim / 32.0));

        int gridW = (int) Math.ceil((double) width / scale);
        int gridH = (int) Math.ceil((double) height / scale);

        // 3. 填充字符阵列
        char[][] canvas = new char[gridH][gridW];
        for (char[] row : canvas) Arrays.fill(row, '.');

        for (Point p : points) {
            int ix = (p.x - minX) / scale;
            int iz = (p.z - minZ) / scale;
            if (ix >= 0 && ix < gridW && iz >= 0 && iz < gridH) {
                canvas[iz][ix] = '#';
            }
        }

        // 4. 构建 JSON 响应
        JsonObject root = new JsonObject();
        root.addProperty("cluster_id", displayId);

        // 坐标转换矩阵
        JsonObject transform = new JsonObject();
        transform.addProperty("origin_x", minX);
        transform.addProperty("origin_z", minZ);
        transform.addProperty("scale", scale);
        transform.addProperty("formula", "RealCoord = Origin + (Index * Scale)");
        root.add("transform", transform);

        // 关键坐标点 (直接供 AI 引用)
        JsonObject kp = new JsonObject();
        kp.add("center", createCoord(sumX / points.size(), sumZ / points.size()));
        kp.add("north_tip", createCoord(north.x, north.z));
        kp.add("south_tip", createCoord(south.x, south.z));
        kp.add("east_tip", createCoord(east.x, east.z));
        kp.add("west_tip", createCoord(west.x, west.z));

        // 随机采样 3 个有效点
        JsonArray samples = new JsonArray();
        Collections.shuffle(points);
        points.stream().limit(3).forEach(p -> samples.add(createCoord(p.x, p.z)));
        kp.add("valid_samples", samples);

        root.add("key_points", kp);

        // ASCII 字符图
        JsonArray mapArray = new JsonArray();
        for (char[] row : canvas) mapArray.add(new String(row));
        root.add("ascii_map", mapArray);

        return root;
    }

    private static JsonObject createCoord(long x, long z) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", x);
        obj.addProperty("z", z);
        return obj;
    }
}