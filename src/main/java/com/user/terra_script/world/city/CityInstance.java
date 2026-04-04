package com.user.terra_script.world.city;

import com.user.terra_script.world.city.district.District;
import java.util.*;

public class CityInstance {
    public String id;
    public CityConfig config;

    // 核心数据：该城市占领的区块集合
    // Key: ChunkPos.asLong
    // Value: LayerAssignment (层级)
    public Map<Long, LayerAssignment> claimedChunks = new HashMap<>();

    // 边界上的区块 (用于生成城墙)
    public Set<Long> borderChunks = new HashSet<>();

    // 城门位置 (BlockPos)
    public List<long[]> gatePositions = new ArrayList<>();

    // 内部多边形
    public List<District> districts = new ArrayList<>();

    public transient CityConfig.LayerLayout layerLayout;

    public CityInstance(String id, CityConfig config) {
        this.id = id;
        this.config = config;
    }

    public CityConfig.LayerLayout getLayerLayout() {
        if (layerLayout == null) {
            if (config == null) config = new CityConfig();
            layerLayout = config.resolveLayerLayout();
        }
        return layerLayout;
    }

    public static class LayerAssignment {
        public int layerIndex;
        public String layerType;

        public LayerAssignment(int layerIndex, String layerType) {
            this.layerIndex = layerIndex;
            this.layerType = layerType;
        }
    }
}
