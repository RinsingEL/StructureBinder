package com.user.terra_script.world.city;

import net.minecraft.world.level.ChunkPos;
import java.util.*;

public class CityInstance {
    public String id;
    public CityConfig config;

    // 核心数据：该城市占领的区块集合
    // Key: ChunkPos.asLong
    // Value: CityZoneType (层级)
    public Map<Long, CityZoneType> claimedChunks = new HashMap<>();

    // 边界上的区块 (用于生成城墙)
    public Set<Long> borderChunks = new HashSet<>();

    // 城门位置 (BlockPos)
    public List<long[]> gatePositions = new ArrayList<>();

    // 内部多边形 (后续阶段使用)
    // public List<CityDistrict> districts = ...

    public CityInstance(String id, CityConfig config) {
        this.id = id;
        this.config = config;
    }

    public enum CityZoneType {
        CORE(0, 0.4f),    // 核心区 (0-40%)
        URBAN(1, 0.85f),  // 城区 (40-85%)
        BUFFER(2, 1.0f);  // 缓冲区 (85-100%)

        public final int level;
        public final float threshold; // 归一化代价阈值

        CityZoneType(int level, float threshold) {
            this.level = level;
            this.threshold = threshold;
        }
    }
}