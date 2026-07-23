package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.PlaceCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Optional;

public final class MinecraftCityStructureMaterializationBackend implements CityStructureMaterializationPlanner.PlacementBackend {
    private final MinecraftServer server;
    private final ServerLevel level;
    private final boolean executeWorldMutation;

    public MinecraftCityStructureMaterializationBackend(MinecraftServer server, ServerLevel level,
                                                        boolean executeWorldMutation) {
        this.server = server;
        this.level = level;
        this.executeWorldMutation = executeWorldMutation;
    }

    @Override
    public CityStructureMaterializationPlanner.PlacementResult plan(
            CityStructureMaterializationPlanner.StructureTask task) {
        GeneratedStart generated = generateStart(task);
        if (!generated.success()) {
            return generated.toResult(false);
        }
        return CityStructureMaterializationPlanner.PlacementResult.success(false,
                "Registry dry-run generated StructureStart without world mutation.",
                generated.footprint(), generated.signature(), generated.pieceBoxes());
    }

    @Override
    public CityStructureMaterializationPlanner.PlacementResult place(
            CityStructureMaterializationPlanner.StructureTask task) {
        GeneratedStart generated = generateStart(task);
        if (!generated.success()) {
            return generated.toResult(false);
        }
        if (!executeWorldMutation) {
            return CityStructureMaterializationPlanner.PlacementResult.success(false,
                    "Dry-run true-run recheck generated StructureStart without world mutation.",
                    generated.footprint(), generated.signature(), generated.pieceBoxes());
        }
        ChunkRange chunks = ChunkRange.from(generated.footprint());
        String missing = missingChunks(chunks);
        if (!missing.isBlank()) {
            return CityStructureMaterializationPlanner.PlacementResult.waiting("STRUCTURE_CHUNK_NOT_LOADED",
                    "Waiting for loaded chunks before structure placement: requiredChunks="
                            + chunks + ", missingChunks=" + missing);
        }
        try {
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            ChunkRange.rangeClosed(chunks).forEach(chunk -> generated.start().placeInChunk(
                    level,
                    level.structureManager(),
                    generator,
                    level.getRandom(),
                    new BoundingBox(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(),
                            chunk.getMaxBlockX(), level.getMaxBuildHeight(), chunk.getMaxBlockZ()),
                    chunk));
            return CityStructureMaterializationPlanner.PlacementResult.success(true,
                    "Placed configured structure with selected in-memory StructureStart.",
                    generated.footprint(), generated.signature(), generated.pieceBoxes());
        } catch (RuntimeException ex) {
            return CityStructureMaterializationPlanner.PlacementResult.failed(
                    "STRUCTURE_COMMAND_FAILED", ex.getMessage());
        }
    }

    private GeneratedStart generateStart(CityStructureMaterializationPlanner.StructureTask task) {
        if (server == null || level == null) {
            return GeneratedStart.failed("CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Minecraft server and ServerLevel are required.");
        }
        ResourceLocation id = ResourceLocation.tryParse(task.structureId());
        if (id == null) {
            return GeneratedStart.failed("CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Invalid configured structure id: " + task.structureId());
        }
        Optional<Holder.Reference<Structure>> holder = server.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .getHolder(ResourceKey.create(Registries.STRUCTURE, id));
        if (holder.isEmpty()) {
            return GeneratedStart.failed("CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Configured structure registry does not contain: " + task.structureId());
        }
        BlockPos anchor = new BlockPos(task.anchorBlock().x(), level.getMinBuildHeight(), task.anchorBlock().z());
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        StructureStart start = holder.get().value().generate(
                server.registryAccess(),
                generator,
                generator.getBiomeSource(),
                level.getChunkSource().randomState(),
                level.getStructureManager(),
                level.getSeed(),
                new ChunkPos(anchor),
                0,
                level,
                biome -> true);
        if (!start.isValid()) {
            return GeneratedStart.failed("CONFIGURED_STRUCTURE_START_INVALID",
                    "Configured structure generated invalid StructureStart: " + task.structureId());
        }
        BlockBounds footprint = footprint(start.getBoundingBox());
        String signature = signature(task, start);
        if (!task.expectedStartSignature().isBlank() && !task.expectedStartSignature().equals(signature)) {
            return GeneratedStart.failed("START_SIGNATURE_MISMATCH",
                    "Generated StructureStart differs from selected dry-run plan.");
        }
        return GeneratedStart.ok(holder.get(), anchor, start, footprint, signature, pieces(start));
    }

    private String missingChunks(ChunkRange chunks) {
        StringBuilder missing = new StringBuilder();
        int count = 0;
        for (int x = chunks.minX(); x <= chunks.maxX(); x++) {
            for (int z = chunks.minZ(); z <= chunks.maxZ(); z++) {
                if (!level.isLoaded(new ChunkPos(x, z).getWorldPosition())) {
                    if (count > 0) {
                        missing.append(";");
                    }
                    missing.append(x).append(",").append(z);
                    count++;
                    if (count >= 16) {
                        return missing.append(";...").toString();
                    }
                }
            }
        }
        return missing.toString();
    }

    private static String signature(CityStructureMaterializationPlanner.StructureTask task, StructureStart start) {
        return task.structureId() + "@" + task.anchorBlock().x() + "," + task.anchorBlock().z()
                + "#" + start.getPieces().size() + "#" + footprint(start.getBoundingBox());
    }

    private static JsonArray pieces(StructureStart start) {
        JsonArray array = new JsonArray();
        int index = 0;
        for (StructurePiece piece : start.getPieces()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("pieceIndex", index++);
            obj.add("box", boundsJson(footprint(piece.getBoundingBox())));
            obj.addProperty("type", piece.getType().toString());
            array.add(obj);
        }
        return array;
    }

    private static BlockBounds footprint(BoundingBox box) {
        return new BlockBounds(box.minX(), box.minZ(), box.maxX(), box.maxZ());
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private record GeneratedStart(boolean success, boolean waiting, String reasonCode, String message,
                                  Holder.Reference<Structure> holder, BlockPos anchor, StructureStart start,
                                  BlockBounds footprint, String signature, JsonArray pieceBoxes) {
        static GeneratedStart ok(Holder.Reference<Structure> holder, BlockPos anchor, StructureStart start,
                                 BlockBounds footprint, String signature, JsonArray pieceBoxes) {
            return new GeneratedStart(true, false, "", "", holder, anchor, start, footprint, signature, pieceBoxes);
        }

        static GeneratedStart failed(String reasonCode, String message) {
            return new GeneratedStart(false, false, reasonCode, message, null, null, null, null, "", new JsonArray());
        }

        static GeneratedStart waiting(String reasonCode, String message) {
            return new GeneratedStart(false, true, reasonCode, message, null, null, null, null, "", new JsonArray());
        }

        CityStructureMaterializationPlanner.PlacementResult toResult(boolean applied) {
            return waiting
                    ? CityStructureMaterializationPlanner.PlacementResult.waiting(reasonCode, message)
                    : CityStructureMaterializationPlanner.PlacementResult.failed(reasonCode, message);
        }
    }

    private record ChunkRange(int minX, int minZ, int maxX, int maxZ) {
        static ChunkRange from(BlockBounds bounds) {
            int minChunkX = SectionPos.blockToSectionCoord(bounds.minX());
            int minChunkZ = SectionPos.blockToSectionCoord(bounds.minZ());
            int maxChunkX = SectionPos.blockToSectionCoord(bounds.maxX());
            int maxChunkZ = SectionPos.blockToSectionCoord(bounds.maxZ());
            return new ChunkRange(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        }

        @Override
        public String toString() {
            return minX + "," + minZ + ".." + maxX + "," + maxZ;
        }

        static java.util.stream.Stream<ChunkPos> rangeClosed(ChunkRange range) {
            return ChunkPos.rangeClosed(new ChunkPos(range.minX(), range.minZ()),
                    new ChunkPos(range.maxX(), range.maxZ()));
        }
    }
}
