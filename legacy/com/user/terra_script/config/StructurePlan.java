package com.user.terra_script.config;

import net.minecraft.world.level.ChunkPos;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StructurePlan {
    private static final StructurePlan INSTANCE = new StructurePlan();

    // 使用 ConcurrentHashMap 防止并发修改异常
    // Key: ChunkPos.asLong (区块坐标) -> Value: Structure ID (如 "minecraft:igloo/top")
    private final Map<Long, String> plannedStructures = new ConcurrentHashMap<>();

    public static StructurePlan get() { return INSTANCE; }

    public void addStructure(int chunkX, int chunkZ, String structureId) {
        plannedStructures.put(ChunkPos.asLong(chunkX, chunkZ), structureId);
    }

    public String getStructureAt(int chunkX, int chunkZ) {
        return plannedStructures.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public void removeStructure(int chunkX, int chunkZ) {
        plannedStructures.remove(ChunkPos.asLong(chunkX, chunkZ));
    }

    public void clear() {
        plannedStructures.clear();
    }

    public Map<Long, String> getAllPlans() {
        return new HashMap<>(plannedStructures);
    }
}