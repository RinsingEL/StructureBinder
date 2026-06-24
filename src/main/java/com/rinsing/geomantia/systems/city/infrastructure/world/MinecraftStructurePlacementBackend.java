package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityStructureD7Executor;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.PlaceCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class MinecraftStructurePlacementBackend implements CityStructureD7Executor.PlacementBackend {
    private final MinecraftServer server;
    private final ServerLevel level;
    private final boolean executeCommands;

    public MinecraftStructurePlacementBackend(MinecraftServer server, ServerLevel level, boolean executeCommands) {
        this.server = server;
        this.level = level;
        this.executeCommands = executeCommands;
    }

    @Override
    public CityStructureD7Executor.PlacementResult place(CityStructureD7Executor.PlacementRequest request) {
        if (server == null || level == null) {
            return CityStructureD7Executor.PlacementResult.failed(
                    "CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Minecraft server and ServerLevel are required for D7 structure placement.");
        }
        ResourceLocation id = ResourceLocation.tryParse(request.structureId());
        if (id == null) {
            return CityStructureD7Executor.PlacementResult.failed("CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Invalid configured structure id: " + request.structureId());
        }
        ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE, id);
        Optional<Holder.Reference<Structure>> holder = server.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .getHolder(key);
        if (holder.isEmpty()) {
            return CityStructureD7Executor.PlacementResult.failed("CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Configured structure registry does not contain: " + request.structureId());
        }
        if (!executeCommands) {
            return CityStructureD7Executor.PlacementResult.placed(
                    "Dry-run confirmed configured structure registry entry: " + request.structureId());
        }
        int x = request.anchorBlock().x();
        int z = request.anchorBlock().z();
        ChunkRange loadedChunks = preloadChunks(request.footprint());
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int y = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, surfaceY));
        BlockPos anchor = new BlockPos(x, y, z);
        String command = "place structure " + request.structureId() + " " + x + " " + y + " " + z;
        try {
            int result = PlaceCommand.placeStructure(
                    server.createCommandSourceStack()
                            .withLevel(level)
                            .withPosition(Vec3.atLowerCornerOf(anchor))
                            .withPermission(4),
                    holder.get(),
                    anchor);
            if (result <= 0) {
                return CityStructureD7Executor.PlacementResult.failed("CONFIGURED_STRUCTURE_START_INVALID",
                        "Command returned no success: /" + command + "; " + diagnostics(anchor, loadedChunks));
            }
            return CityStructureD7Executor.PlacementResult.placed("Executed /" + command + "; "
                    + diagnostics(anchor, loadedChunks));
        } catch (RuntimeException ex) {
            return CityStructureD7Executor.PlacementResult.failed("STRUCTURE_COMMAND_FAILED",
                    "Command failed: " + ex.getMessage() + "; " + diagnostics(anchor, loadedChunks));
        } catch (CommandSyntaxException ex) {
            return CityStructureD7Executor.PlacementResult.failed(reasonCode(ex),
                    "PlaceCommand failed: " + ex.getMessage() + "; /" + command + "; "
                            + diagnostics(anchor, loadedChunks));
        }
    }

    private ChunkRange preloadChunks(com.rinsing.geomantia.systems.city.domain.model.BlockBounds footprint) {
        if (footprint == null) {
            return ChunkRange.single(0, 0);
        }
        int marginChunks = 1;
        int minChunkX = new ChunkPos(new BlockPos(footprint.minX(), level.getMinBuildHeight(), footprint.minZ())).x
                - marginChunks;
        int minChunkZ = new ChunkPos(new BlockPos(footprint.minX(), level.getMinBuildHeight(), footprint.minZ())).z
                - marginChunks;
        int maxChunkX = new ChunkPos(new BlockPos(footprint.maxX(), level.getMinBuildHeight(), footprint.maxZ())).x
                + marginChunks;
        int maxChunkZ = new ChunkPos(new BlockPos(footprint.maxX(), level.getMinBuildHeight(), footprint.maxZ())).z
                + marginChunks;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
        return new ChunkRange(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    private String reasonCode(CommandSyntaxException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (message.contains("not loaded")) {
            return "STRUCTURE_CHUNK_NOT_LOADED";
        }
        return "CONFIGURED_STRUCTURE_START_INVALID";
    }

    private String diagnostics(BlockPos anchor, ChunkRange loadedChunks) {
        ResourceLocation biomeId = level.registryAccess()
                .registryOrThrow(Registries.BIOME)
                .getKey(level.getBiome(anchor).value());
        ChunkPos commandChunk = new ChunkPos(anchor);
        return "surfaceY=" + anchor.getY()
                + ", biome=" + (biomeId == null ? "unknown" : biomeId)
                + ", commandChunk=" + commandChunk.x + "," + commandChunk.z
                + ", loadedFootprintChunks=" + loadedChunks;
    }

    private record ChunkRange(int minX, int minZ, int maxX, int maxZ) {
        static ChunkRange single(int chunkX, int chunkZ) {
            return new ChunkRange(chunkX, chunkZ, chunkX, chunkZ);
        }

        @Override
        public String toString() {
            return minX + "," + minZ + ".." + maxX + "," + maxZ;
        }
    }
}
