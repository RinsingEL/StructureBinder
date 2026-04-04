package com.user.terra_script.world.city;

import java.util.List;

// 读取方便
public class CityLayout {

    public String cityInstanceId;   // 对应 CityConfig.cityInstanceId
    public int continentId;
    public String territoryId;
    // CityLayout.java 增加
    public List<DistrictRenderData> districts;

    public static class DistrictRenderData {
        public int id;
        public double centerX, centerZ;
        public String type;
        public int layerIndex;
        public String density;
        public List<Point2D> polygon; // 多边形顶点序列 (有序)
    }

    public static class Point2D {
        public double x, z;
        public Point2D(double x, double z) { this.x = x; this.z = z; }
    }

    // 实际占用
    public List<CityChunk> chunks;

    // 方便渲染 / 计算
    public int centerChunkX;
    public int centerChunkZ;

    public static class CityChunk {
        public int x;     // chunk x
        public int z;     // chunk z
        public String zoneType;
        public int layerIndex;
    }


}
