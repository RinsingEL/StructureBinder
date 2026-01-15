package com.user.terra_script.world.city.district;

import net.minecraft.world.level.ChunkPos;
import java.util.*;

public class District {
    public int id;
    public double centerX, centerZ;
    public List<double[]> polygonVertices = new ArrayList<>(); // 多边形顶点
    public List<Long> memberChunks = new ArrayList<>();

    // 归属信息
    public String cityId;
    public String zoneType; // CORE, URBAN, BUFFER

    // AI 决策依据 (Field Data)
    public double avgSlope;
    public double waterDistance; // 0=临水, 1=远
    public double mineralDensity; // 模拟值

    // AI 决策结果
    public String assignedFunction; // e.g. "market", "slum"

    public District(int id, double x, double z) {
        this.id = id; this.centerX = x; this.centerZ = z;
    }
}