package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeProfiler;
import com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Optional;

public final class MinecraftCityStructureEnvelopeSampler implements CityStructureEnvelopeProfiler.StructureEnvelopeSampler {
    private static final int SAMPLE_GRID_WIDTH = 17;
    private static final int SAMPLE_CHUNK_SPACING = 23;

    private final MinecraftServer server;
    private final ServerLevel level;
    private final int centerChunkX;
    private final int centerChunkZ;

    public MinecraftCityStructureEnvelopeSampler(MinecraftServer server, ServerLevel level, int centerBlockX, int centerBlockZ) {
        this.server = server;
        this.level = level;
        this.centerChunkX = Math.floorDiv(centerBlockX, 16);
        this.centerChunkZ = Math.floorDiv(centerBlockZ, 16);
    }

    @Override
    public CityStructureEnvelopeProfiler.EnvelopeSample sample(CityStructureProfileCatalog.StructureProfile profile,
                                                               int sampleIndex) {
        if (server == null || level == null) {
            return CityStructureEnvelopeProfiler.EnvelopeSample.invalid(sampleIndex,
                    "CONFIGURED_STRUCTURE_REGISTRY_MISSING", "", "");
        }
        ResourceLocation id = ResourceLocation.tryParse(profile.structureId());
        if (id == null) {
            return CityStructureEnvelopeProfiler.EnvelopeSample.invalid(sampleIndex,
                    "CONFIGURED_STRUCTURE_REGISTRY_MISSING", "", "");
        }
        RegistryAccess registryAccess = server.registryAccess();
        Optional<Holder.Reference<Structure>> holder = registryAccess
                .registryOrThrow(Registries.STRUCTURE)
                .getHolder(ResourceKey.create(Registries.STRUCTURE, id));
        if (holder.isEmpty()) {
            return CityStructureEnvelopeProfiler.EnvelopeSample.invalid(sampleIndex,
                    "CONFIGURED_STRUCTURE_REGISTRY_MISSING", "", "");
        }

        ChunkPos chunk = sampleChunk(sampleIndex);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        StructureStart start = holder.get().value().generate(
                registryAccess,
                generator,
                generator.getBiomeSource(),
                level.getChunkSource().randomState(),
                level.getStructureManager(),
                level.getSeed(),
                chunk,
                0,
                level,
                biome -> true);
        if (!start.isValid()) {
            return CityStructureEnvelopeProfiler.EnvelopeSample.invalid(sampleIndex,
                    "CONFIGURED_STRUCTURE_START_INVALID", "", "");
        }
        BlockBounds local = localFootprint(start.getBoundingBox(), chunk);
        return CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex, local, start.getPieces().size(),
                profile.structureId() + "#" + profile.maxDistanceFromCenterBlocks(),
                "minecraft_runtime_registry");
    }

    private ChunkPos sampleChunk(int sampleIndex) {
        int xOffset = Math.floorMod(sampleIndex, SAMPLE_GRID_WIDTH) - SAMPLE_GRID_WIDTH / 2;
        int zOffset = Math.floorDiv(sampleIndex, SAMPLE_GRID_WIDTH) - SAMPLE_GRID_WIDTH / 2;
        return new ChunkPos(
                centerChunkX + xOffset * SAMPLE_CHUNK_SPACING,
                centerChunkZ + zOffset * SAMPLE_CHUNK_SPACING);
    }

    private static BlockBounds localFootprint(BoundingBox box, ChunkPos chunk) {
        BlockPos origin = chunk.getWorldPosition();
        return new BlockBounds(
                box.minX() - origin.getX(),
                box.minZ() - origin.getZ(),
                box.maxX() - origin.getX(),
                box.maxZ() - origin.getZ());
    }
}
