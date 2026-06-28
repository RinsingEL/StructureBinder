package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

public final class MinecraftCityWorldgenStatusInspector
        implements CityStructureMaterializationPlanner.ChunkStatusInspector {
    private final ServerLevel level;

    public MinecraftCityWorldgenStatusInspector(ServerLevel level) {
        this.level = level;
    }

    @Override
    public CityStructureMaterializationPlanner.ChunkStatusResult inspect(
            CityStructureMaterializationPlanner.StructureTask task) {
        if (level == null) {
            return CityStructureMaterializationPlanner.ChunkStatusResult.plannedWorldgen(
                    "No ServerLevel available; assuming target chunk has not been generated.");
        }
        int chunkX = Math.floorDiv(task.anchorBlock().x(), 16);
        int chunkZ = Math.floorDiv(task.anchorBlock().z(), 16);
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.EMPTY, false);
        if (chunk == null) {
            return CityStructureMaterializationPlanner.ChunkStatusResult.plannedWorldgen(
                    "Target chunk is not loaded/generated. Load from outside after city_execute_d5 activates registry.");
        }
        ChunkStatus status = chunk.getStatus();
        if (status.isOrAfter(ChunkStatus.FEATURES)) {
            return CityStructureMaterializationPlanner.ChunkStatusResult.alreadyGenerated(
                    "Target chunk is already at " + status + "; active path will not late paste structures.");
        }
        if (status.isOrAfter(ChunkStatus.STRUCTURE_STARTS)) {
            return CityStructureMaterializationPlanner.ChunkStatusResult.plannedWorldgen(
                    "Target chunk status is " + status + "; worldgen hook can still be observed before features.");
        }
        return CityStructureMaterializationPlanner.ChunkStatusResult.plannedWorldgen(
                "Target chunk status is " + status + "; waiting for worldgen structure stage.");
    }
}
