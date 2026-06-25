package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityStructureD7Executor;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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

    @Override
    public CityStructureD7Executor.PlacementResult placeBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
        if (server == null || level == null) {
            return CityStructureD7Executor.PlacementResult.failed(
                    "CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Minecraft server and ServerLevel are required for bounded jigsaw materialization.");
        }
        ResourceLocation id = ResourceLocation.tryParse(request.structureId());
        if (id == null) {
            return CityStructureD7Executor.PlacementResult.failed("CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Invalid configured structure id: " + request.structureId());
        }
        Optional<Holder.Reference<Structure>> holder = server.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .getHolder(ResourceKey.create(Registries.STRUCTURE, id));
        if (holder.isEmpty()) {
            return CityStructureD7Executor.PlacementResult.failed("CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Configured structure registry does not contain: " + request.structureId());
        }
        if (!(holder.get().value() instanceof JigsawStructure jigsaw)) {
            return CityStructureD7Executor.PlacementResult.failed("BOUNDED_JIGSAW_UNSUPPORTED",
                    "Configured structure is not a vanilla-like JigsawStructure: " + request.structureId());
        }
        Holder<StructureTemplatePool> startPool = startPool(jigsaw);
        if (startPool == null || startPool.value() == null) {
            return CityStructureD7Executor.PlacementResult.failed("BOUNDED_JIGSAW_POOL_MISSING",
                    "Could not resolve JigsawStructure start pool: " + request.structureId());
        }
        Rotation rotation = rotation(request.rotation());
        BlockPos anchor = surfaceAnchor(request.anchorBlock().x(), request.anchorBlock().z());
        ChunkRange requiredChunks = ChunkRange.from(request.requiredLoadBounds() == null
                ? request.footprint()
                : request.requiredLoadBounds());
        String missingChunks = missingChunks(requiredChunks);
        if (!missingChunks.isBlank()) {
            return CityStructureD7Executor.PlacementResult.waiting("STRUCTURE_CHUNK_NOT_LOADED",
                    "Waiting for loaded chunks before bounded jigsaw materialization: requiredChunks="
                            + requiredChunks + ", missingChunks=" + missingChunks);
        }

        List<StructurePoolElement> elements = startPool.value()
                .getShuffledTemplates(RandomSource.create(request.structureId().hashCode() * 31L
                        + request.anchorBlock().x() * 17L + request.anchorBlock().z()));
        if (elements.isEmpty()) {
            return CityStructureD7Executor.PlacementResult.failed("BOUNDED_JIGSAW_POOL_EMPTY",
                    "Start pool has no elements: " + poolName(startPool));
        }
        JsonObject trace = baseBoundedTrace(request, holder.get(), startPool);
        int pieceIndex = 0;
        for (StructurePoolElement element : elements) {
            if (element instanceof EmptyPoolElement) {
                continue;
            }
            pieceIndex++;
            BoundingBox box = element.getBoundingBox(level.getStructureManager(), anchor, rotation);
            BlockBounds footprint = new BlockBounds(box.minX(), box.minZ(), box.maxX(), box.maxZ());
            BoundedJigsawTemplateInspector.PieceInspection inspection = BoundedJigsawTemplateInspector.inspect(
                    level.getStructureManager(), element, poolName(startPool), anchor, rotation, box, pieceIndex,
                    request.structureId().hashCode() * 31L + pieceIndex);
            JsonObject piece = inspection.pieceJson();
            if (!BoundedJigsawTemplateInspector.canInspectTemplate(element)) {
                addRejectedPiece(trace, piece, "UNSUPPORTED_POOL_ELEMENT");
                continue;
            }
            ChunkRange pieceChunks = ChunkRange.from(footprint);
            String missingPieceChunks = missingChunks(pieceChunks);
            if (!missingPieceChunks.isBlank()) {
                return CityStructureD7Executor.PlacementResult.waiting("STRUCTURE_CHUNK_NOT_LOADED",
                        "Waiting for loaded chunks before bounded jigsaw piece placement: requiredChunks="
                                + pieceChunks + ", missingChunks=" + missingPieceChunks,
                        trace);
            }
            String reason = boundedPieceFailure(request.constraintField(), footprint, request.targetAreaBlocks());
            piece.add("footprint", boundsJson(footprint));
            piece.addProperty("visibleAreaCost", footprint.widthBlocks() * footprint.heightBlocks());
            if (!reason.isBlank()) {
                addRejectedPiece(trace, piece, reason);
                continue;
            }
            addAcceptedPiece(trace, piece, footprint);
            if (!executeCommands) {
                return CityStructureD7Executor.PlacementResult.dryRunAccepted(
                        "Dry-run accepted bounded jigsaw start piece: " + request.structureId(),
                        trace, footprint, footprint);
            }
            boolean placed = element.place(level.getStructureManager(), level, level.structureManager(),
                    level.getChunkSource().getGenerator(), anchor, anchor, rotation, box,
                    RandomSource.create(request.structureId().hashCode()), false);
            if (!placed) {
                incrementTraceFailure(trace, "BOUNDED_JIGSAW_PIECE_PLACE_FAILED");
                return CityStructureD7Executor.PlacementResult.failed("BOUNDED_JIGSAW_PIECE_PLACE_FAILED",
                        "Accepted jigsaw piece returned false from place(): " + element,
                        trace, footprint, footprint);
            }
            return CityStructureD7Executor.PlacementResult.placed(
                    "Placed bounded jigsaw start piece for " + request.structureId(),
                trace, footprint, footprint);
        }
        incrementTraceFailure(trace, "JIGSAW_NO_ACCEPTED_PIECE");
        return CityStructureD7Executor.PlacementResult.failed("JIGSAW_NO_ACCEPTED_PIECE",
                "No start pool element passed CityConstraintField for " + request.structureId(),
                trace);
    }

    @SuppressWarnings("unchecked")
    private Holder<StructureTemplatePool> startPool(JigsawStructure jigsaw) {
        try {
            Field field = JigsawStructure.class.getDeclaredField("startPool");
            field.setAccessible(true);
            Object value = field.get(jigsaw);
            if (value instanceof Holder<?> holder && holder.value() instanceof StructureTemplatePool) {
                return (Holder<StructureTemplatePool>) holder;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private BlockPos surfaceAnchor(int x, int z) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int y = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, surfaceY));
        return new BlockPos(x, y, z);
    }

    private Rotation rotation(String value) {
        try {
            return Rotation.valueOf(value == null || value.isBlank() ? "NONE" : value);
        } catch (IllegalArgumentException ex) {
            return Rotation.NONE;
        }
    }

    private JsonObject baseBoundedTrace(CityStructureD7Executor.PlacementRequest request,
                                        Holder.Reference<Structure> structure,
                                        Holder<StructureTemplatePool> pool) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", "city_bounded_jigsaw_trace.v0.1");
        trace.addProperty("capability", "bounded_jigsaw_supported");
        trace.addProperty("sourceStructureId", request.structureId());
        trace.addProperty("structureRegistryKey", structure.key().location().toString());
        trace.addProperty("startPool", poolName(pool));
        trace.addProperty("targetAreaBlocks", request.targetAreaBlocks());
        trace.add("acceptedPieces", new JsonArray());
        trace.add("rejectedPieces", new JsonArray());
        trace.add("stoppedBranches", new JsonArray());
        trace.addProperty("fallbackUsed", false);
        trace.add("failureSummary", new JsonObject());
        trace.add("metrics", boundedMetrics(0, 0, 0, 0));
        trace.add("plan", boundedPlan(request));
        return trace;
    }

    private String poolName(Holder<StructureTemplatePool> pool) {
        return pool.unwrapKey().map(key -> key.location().toString()).orElse("inline");
    }

    private JsonObject boundedPlan(CityStructureD7Executor.PlacementRequest request) {
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_bounded_jigsaw_plan.v0.1");
        plan.addProperty("planId", "bounded_" + safeId(request.structureId()) + "_"
                + request.anchorBlock().x() + "_" + request.anchorBlock().z() + "_"
                + safeId(request.rotation()));
        plan.addProperty("jobId", "job_" + safeId(request.structureId()) + "_"
                + request.anchorBlock().x() + "_" + request.anchorBlock().z());
        plan.addProperty("sourceStructureId", request.structureId());
        plan.add("pieces", new JsonArray());
        plan.add("stoppedBranches", new JsonArray());
        BlockBounds initialBounds = request.requiredLoadBounds() == null ? request.footprint() : request.requiredLoadBounds();
        if (initialBounds != null) {
            plan.add("estimatedFootprint", boundsJson(initialBounds));
            plan.add("requiredChunkRange", chunkRangeJson(ChunkRange.from(initialBounds)));
        }
        plan.addProperty("visibleAreaCost", 0);
        JsonObject quality = new JsonObject();
        quality.addProperty("acceptedPieceCount", 0);
        quality.addProperty("stoppedBranchCount", 0);
        quality.addProperty("startPieceOnly", true);
        quality.add("warnings", new JsonArray());
        plan.add("quality", quality);
        return plan;
    }

    private void addAcceptedPiece(JsonObject trace, JsonObject piece, BlockBounds footprint) {
        piece.addProperty("validatorResult", "passed");
        trace.getAsJsonArray("acceptedPieces").add(piece.deepCopy());
        JsonObject plan = trace.getAsJsonObject("plan");
        plan.getAsJsonArray("pieces").add(piece.deepCopy());
        plan.add("estimatedFootprint", boundsJson(footprint));
        plan.add("requiredChunkRange", chunkRangeJson(ChunkRange.from(footprint)));
        plan.addProperty("visibleAreaCost", footprint.widthBlocks() * footprint.heightBlocks());
        updateBoundedMetrics(trace);
    }

    private void addRejectedPiece(JsonObject trace, JsonObject piece, String reasonCode) {
        piece.addProperty("validatorResult", "failed");
        piece.addProperty("reasonCode", reasonCode);
        trace.getAsJsonArray("rejectedPieces").add(piece.deepCopy());
        JsonObject stopped = new JsonObject();
        String pieceId = piece.has("pieceId") ? piece.get("pieceId").getAsString() : "unknown_piece";
        stopped.addProperty("branchId", "branch_" + pieceId);
        stopped.addProperty("pieceId", pieceId);
        stopped.addProperty("action", "stop_branch");
        stopped.addProperty("reasonCode", reasonCode);
        stopped.addProperty("endcapAttempted", false);
        stopped.addProperty("endcapStatus", "not_implemented_start_piece_slice");
        if (piece.has("footprint") && piece.get("footprint").isJsonObject()) {
            stopped.add("footprint", piece.getAsJsonObject("footprint").deepCopy());
        }
        trace.getAsJsonArray("stoppedBranches").add(stopped.deepCopy());
        trace.getAsJsonObject("plan").getAsJsonArray("stoppedBranches").add(stopped.deepCopy());
        JsonObject summary = trace.getAsJsonObject("failureSummary");
        incrementTraceFailure(trace, reasonCode);
        updateBoundedMetrics(trace);
    }

    private void incrementTraceFailure(JsonObject trace, String reasonCode) {
        JsonObject summary = trace.getAsJsonObject("failureSummary");
        summary.addProperty(reasonCode, intValue(summary, reasonCode, 0) + 1);
    }

    private void updateBoundedMetrics(JsonObject trace) {
        int accepted = trace.getAsJsonArray("acceptedPieces").size();
        int rejected = trace.getAsJsonArray("rejectedPieces").size();
        int stopped = trace.getAsJsonArray("stoppedBranches").size();
        int visibleArea = 0;
        for (JsonElement elem : trace.getAsJsonArray("acceptedPieces")) {
            if (elem.isJsonObject()) {
                visibleArea += intValue(elem.getAsJsonObject(), "visibleAreaCost", 0);
            }
        }
        trace.add("metrics", boundedMetrics(accepted, rejected, stopped, visibleArea));
        JsonObject quality = trace.getAsJsonObject("plan").getAsJsonObject("quality");
        quality.addProperty("acceptedPieceCount", accepted);
        quality.addProperty("stoppedBranchCount", stopped);
    }

    private JsonObject boundedMetrics(int acceptedPieces, int rejectedPieces, int stoppedBranches, int visibleAreaCost) {
        JsonObject metrics = new JsonObject();
        metrics.addProperty("acceptedPieceCount", acceptedPieces);
        metrics.addProperty("rejectedPieceCount", rejectedPieces);
        metrics.addProperty("stoppedBranchCount", stoppedBranches);
        metrics.addProperty("visibleAreaCost", visibleAreaCost);
        return metrics;
    }

    private String boundedPieceFailure(JsonObject constraintField, BlockBounds footprint, int targetAreaBlocks) {
        if (constraintField == null || !constraintField.has("allowedArea")) {
            return "CITY_CONSTRAINT_FIELD_MISSING";
        }
        if (!covers(constraintField, footprint)) {
            return "JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA";
        }
        if (targetAreaBlocks > 0 && footprint.widthBlocks() * footprint.heightBlocks() > targetAreaBlocks) {
            return "JIGSAW_AREA_BUDGET_REACHED";
        }
        if (constraintField.has("occupiedFootprints") && constraintField.get("occupiedFootprints").isJsonArray()) {
            for (JsonElement elem : constraintField.getAsJsonArray("occupiedFootprints")) {
                if (elem.isJsonObject() && elem.getAsJsonObject().has("footprint")
                        && overlaps(footprint, bounds(elem.getAsJsonObject().getAsJsonObject("footprint")))) {
                    return "JIGSAW_PIECE_RESERVED_CONFLICT";
                }
            }
        }
        return "";
    }

    private boolean covers(JsonObject constraintField, BlockBounds footprint) {
        JsonArray cells = constraintField.has("buildableCells") && constraintField.get("buildableCells").isJsonArray()
                ? constraintField.getAsJsonArray("buildableCells")
                : new JsonArray();
        if (cells.isEmpty()) {
            return contains(bounds(constraintField.getAsJsonObject("allowedArea")), footprint);
        }
        int originX = intValue(constraintField, "originBlockX", 0);
        int originZ = intValue(constraintField, "originBlockZ", 0);
        int cellStep = Math.max(1, intValue(constraintField, "cellStepBlocks", 16));
        int minCellX = Math.floorDiv(footprint.minX() - originX, cellStep);
        int maxCellX = Math.floorDiv(footprint.maxX() - originX, cellStep);
        int minCellZ = Math.floorDiv(footprint.minZ() - originZ, cellStep);
        int maxCellZ = Math.floorDiv(footprint.maxZ() - originZ, cellStep);
        List<Long> allowed = new ArrayList<>();
        for (JsonElement elem : cells) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject cell = elem.getAsJsonObject();
            int x = Math.floorDiv(intValue(cell, "blockMinX", 0) - originX, cellStep);
            int z = Math.floorDiv(intValue(cell, "blockMinZ", 0) - originZ, cellStep);
            allowed.add((((long) x) << 32) ^ (z & 0xffffffffL));
        }
        for (int x = minCellX; x <= maxCellX; x++) {
            for (int z = minCellZ; z <= maxCellZ; z++) {
                long key = (((long) x) << 32) ^ (z & 0xffffffffL);
                if (!allowed.contains(key)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static JsonObject chunkRangeJson(ChunkRange range) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minChunkX", range.minX());
        obj.addProperty("minChunkZ", range.minZ());
        obj.addProperty("maxChunkX", range.maxX());
        obj.addProperty("maxChunkZ", range.maxZ());
        return obj;
    }

    private static boolean contains(BlockBounds container, BlockBounds child) {
        return child.minX() >= container.minX() && child.maxX() <= container.maxX()
                && child.minZ() >= container.minZ() && child.maxZ() <= container.maxZ();
    }

    private static boolean overlaps(BlockBounds left, BlockBounds right) {
        return left.minX() <= right.maxX() && left.maxX() >= right.minX()
                && left.minZ() <= right.maxZ() && left.maxZ() >= right.minZ();
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsInt();
    }

    private static String safeId(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^A-Za-z0-9_]+", "_");
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
