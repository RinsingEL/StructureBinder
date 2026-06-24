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
            return CityStructureD7Executor.PlacementResult.dryRunAccepted(
                    "Dry-run confirmed configured structure registry entry: " + request.structureId());
        }
        int x = request.anchorBlock().x();
        int z = request.anchorBlock().z();
        ChunkRange requiredChunks = ChunkRange.from(request.requiredLoadBounds() == null
                ? request.footprint()
                : request.requiredLoadBounds());
        String missingChunks = missingChunks(requiredChunks);
        if (!missingChunks.isBlank()) {
            return CityStructureD7Executor.PlacementResult.waiting("STRUCTURE_CHUNK_NOT_LOADED",
                    "Waiting for loaded chunks before structure placement: requiredChunks=" + requiredChunks
                            + ", missingChunks=" + missingChunks);
        }
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
                        "Command returned no success: /" + command + "; " + diagnostics(anchor, requiredChunks));
            }
            return CityStructureD7Executor.PlacementResult.placed("Executed /" + command + "; "
                    + diagnostics(anchor, requiredChunks));
        } catch (RuntimeException ex) {
            return CityStructureD7Executor.PlacementResult.failed("STRUCTURE_COMMAND_FAILED",
                    "Command failed: " + ex.getMessage() + "; " + diagnostics(anchor, requiredChunks));
        } catch (CommandSyntaxException ex) {
            return CityStructureD7Executor.PlacementResult.failed(reasonCode(ex),
                    "PlaceCommand failed: " + ex.getMessage() + "; /" + command + "; "
                            + diagnostics(anchor, requiredChunks));
        }
    }

    private String missingChunks(ChunkRange requiredChunks) {
        StringBuilder missing = new StringBuilder();
        int count = 0;
        for (int chunkX = requiredChunks.minX(); chunkX <= requiredChunks.maxX(); chunkX++) {
            for (int chunkZ = requiredChunks.minZ(); chunkZ <= requiredChunks.maxZ(); chunkZ++) {
                if (!level.isLoaded(new ChunkPos(chunkX, chunkZ).getWorldPosition())) {
                    if (count > 0) {
                        missing.append(";");
                    }
                    missing.append(chunkX).append(",").append(chunkZ);
                    count++;
                    if (count >= 16) {
                        missing.append(";...");
                        return missing.toString();
                    }
                }
            }
        }
        return missing.toString();
    }

    private String reasonCode(CommandSyntaxException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (message.contains("not loaded") || message.contains("尚未被加载") || message.contains("未被加载")) {
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
                + ", requiredChunks=" + loadedChunks;
    }

    private record ChunkRange(int minX, int minZ, int maxX, int maxZ) {
        static ChunkRange from(com.rinsing.geomantia.systems.city.domain.model.BlockBounds footprint) {
            if (footprint == null) {
                return new ChunkRange(0, 0, 0, 0);
            }
            int minChunkX = new ChunkPos(new BlockPos(footprint.minX(), 0, footprint.minZ())).x;
            int minChunkZ = new ChunkPos(new BlockPos(footprint.minX(), 0, footprint.minZ())).z;
            int maxChunkX = new ChunkPos(new BlockPos(footprint.maxX(), 0, footprint.maxZ())).x;
            int maxChunkZ = new ChunkPos(new BlockPos(footprint.maxX(), 0, footprint.maxZ())).z;
            return new ChunkRange(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        }

        @Override
        public String toString() {
            return minX + "," + minZ + ".." + maxX + "," + maxZ;
        }
    }
}
