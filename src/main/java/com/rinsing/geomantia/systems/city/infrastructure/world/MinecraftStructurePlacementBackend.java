package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.BoundedJigsawSolver;
import com.rinsing.geomantia.systems.city.application.CityStructureD7Executor;
import com.rinsing.geomantia.systems.city.application.CityTerrainProbe;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        CityStructureD7Executor.PlacementResult planned = planBoundedJigsaw(request);
        if (!planned.success()) {
            return planned;
        }
        return materializeBoundedJigsaw(request, planned.trace());
    }

    @Override
    public CityStructureD7Executor.PlacementResult planBoundedJigsaw(CityStructureD7Executor.PlacementRequest request) {
        if (server == null || level == null) {
            return CityStructureD7Executor.PlacementResult.failed(
                    "CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Minecraft server and ServerLevel are required for bounded jigsaw planning.");
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
                    "Waiting for loaded chunks before bounded jigsaw planning: requiredChunks="
                            + requiredChunks + ", missingChunks=" + missingChunks);
        }

        List<StructurePoolElement> elements = startPool.value()
                .getShuffledTemplates(RandomSource.create(request.structureId().hashCode() * 31L
                        + request.anchorBlock().x() * 17L + request.anchorBlock().z()));
        if (elements.isEmpty()) {
            return CityStructureD7Executor.PlacementResult.failed("BOUNDED_JIGSAW_POOL_EMPTY",
                    "Start pool has no elements: " + poolName(startPool));
        }
        JsonObject solverInput = BoundedJigsawPoolAdapter.solverInput(request, startPool, level.getStructureManager(),
                anchor, rotation, request.structureId().hashCode() * 31L
                        + request.anchorBlock().x() * 17L + request.anchorBlock().z(),
                this::templatePool);
        TerrainRuleEvaluator terrainRules = new TerrainRuleEvaluator(request.structureId());
        JsonObject trace = new BoundedJigsawSolver(terrainRules).solve(solverInput);
        trace.addProperty("structureRegistryKey", holder.get().key().location().toString());
        trace.addProperty("poolAdapterStatus", "child_pool_discovery");
        trace.addProperty("worldPasteMode", "dry_run_plan_only");
        trace.add("terrainProbeSummary", terrainRules.summary());
        if (solverInput.has("poolAdapterReport") && solverInput.get("poolAdapterReport").isJsonObject()) {
            trace.add("poolAdapterReport", solverInput.getAsJsonObject("poolAdapterReport").deepCopy());
        }
        if (terrainRules.hasWaiting()) {
            return CityStructureD7Executor.PlacementResult.waiting("STRUCTURE_CHUNK_NOT_LOADED",
                    "Waiting for loaded chunks before bounded jigsaw terrain probe: missingChunks="
                            + terrainRules.missingChunks(),
                    trace);
        }
        JsonArray acceptedPieces = acceptedPieces(trace);
        BlockBounds acceptedFootprint = unionFootprint(acceptedPieces);
        if (acceptedPieces.isEmpty() || acceptedFootprint == null) {
            incrementTraceFailure(trace, "JIGSAW_NO_ACCEPTED_PIECE");
            return CityStructureD7Executor.PlacementResult.failed("JIGSAW_NO_ACCEPTED_PIECE",
                    "Dry-run bounded jigsaw found no accepted piece for " + request.structureId(),
                    trace);
        }
        trace.getAsJsonObject("plan").add("requiredChunkRange", chunkRangeJson(ChunkRange.from(acceptedFootprint)));
        return CityStructureD7Executor.PlacementResult.dryRunAccepted(
                "Dry-run accepted bounded jigsaw solver plan: " + request.structureId(),
                trace, acceptedFootprint, acceptedFootprint);
    }

    @Override
    public CityStructureD7Executor.PlacementResult materializeBoundedJigsaw(
            CityStructureD7Executor.PlacementRequest request, JsonObject selectedPlanTrace) {
        if (selectedPlanTrace == null) {
            return CityStructureD7Executor.PlacementResult.failed("JIGSAW_NO_ACCEPTED_PIECE",
                    "Selected bounded jigsaw plan trace is missing.");
        }
        if (server == null || level == null) {
            return CityStructureD7Executor.PlacementResult.failed(
                    "CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Minecraft server and ServerLevel are required for bounded jigsaw materialization.",
                    selectedPlanTrace);
        }
        ResourceLocation id = ResourceLocation.tryParse(request.structureId());
        if (id == null) {
            return CityStructureD7Executor.PlacementResult.failed("CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Invalid configured structure id: " + request.structureId(), selectedPlanTrace);
        }
        Optional<Holder.Reference<Structure>> holder = server.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .getHolder(ResourceKey.create(Registries.STRUCTURE, id));
        if (holder.isEmpty()) {
            return CityStructureD7Executor.PlacementResult.failed("CONFIGURED_STRUCTURE_REGISTRY_MISSING",
                    "Configured structure registry does not contain: " + request.structureId(), selectedPlanTrace);
        }
        if (!(holder.get().value() instanceof JigsawStructure jigsaw)) {
            return CityStructureD7Executor.PlacementResult.failed("BOUNDED_JIGSAW_UNSUPPORTED",
                    "Configured structure is not a vanilla-like JigsawStructure: " + request.structureId(),
                    selectedPlanTrace);
        }
        Holder<StructureTemplatePool> startPool = startPool(jigsaw);
        if (startPool == null || startPool.value() == null) {
            return CityStructureD7Executor.PlacementResult.failed("BOUNDED_JIGSAW_POOL_MISSING",
                    "Could not resolve JigsawStructure start pool: " + request.structureId(), selectedPlanTrace);
        }
        JsonObject trace = selectedPlanTrace.deepCopy();
        trace.addProperty("structureRegistryKey", holder.get().key().location().toString());
        JsonArray acceptedPieces = acceptedPieces(trace);
        BlockBounds acceptedFootprint = unionFootprint(acceptedPieces);
        if (acceptedPieces.isEmpty() || acceptedFootprint == null) {
            incrementTraceFailure(trace, "JIGSAW_NO_ACCEPTED_PIECE");
            return CityStructureD7Executor.PlacementResult.failed("JIGSAW_NO_ACCEPTED_PIECE",
                    "Selected bounded jigsaw plan has no accepted piece for " + request.structureId(),
                    trace);
        }
        if (!executeCommands) {
            trace.addProperty("worldPasteMode", "dry_run_plan_only");
            return CityStructureD7Executor.PlacementResult.dryRunAccepted(
                    "Dry-run selected bounded jigsaw plan only: " + request.structureId(),
                    trace, acceptedFootprint, acceptedFootprint);
        }

        ChunkRange acceptedChunks = ChunkRange.from(acceptedFootprint);
        trace.getAsJsonObject("plan").add("requiredChunkRange", chunkRangeJson(acceptedChunks));
        String missingAcceptedChunks = missingChunks(acceptedChunks);
        if (!missingAcceptedChunks.isBlank()) {
            return CityStructureD7Executor.PlacementResult.waiting("STRUCTURE_CHUNK_NOT_LOADED",
                    "Waiting for loaded chunks before accepted bounded jigsaw piece placement: requiredChunks="
                            + acceptedChunks + ", missingChunks=" + missingAcceptedChunks,
                    trace);
        }

        List<RuntimePiece> runtimePieces = new ArrayList<>();
        for (JsonElement elem : acceptedPieces) {
            JsonObject piece = elem.getAsJsonObject();
            ResolveResult resolved = resolveRuntimePiece(piece, startPool);
            if (!resolved.success()) {
                incrementTraceFailure(trace, resolved.reasonCode());
                markPiecePasteStatus(trace, stringValue(piece, "pieceId", ""),
                        "failed", false, resolved.reasonCode());
                return CityStructureD7Executor.PlacementResult.failed(resolved.reasonCode(),
                        resolved.message(), trace, acceptedFootprint, acceptedFootprint);
            }
            markPieceResolved(trace, stringValue(piece, "pieceId", ""),
                    stringValue(resolved.piece().pieceJson(), "resolvedPoolId", ""),
                    stringValue(resolved.piece().pieceJson(), "resolvedTemplateId", ""));
            runtimePieces.add(resolved.piece());
        }

        int applied = 0;
        int clearedVegetation = 0;
        for (RuntimePiece runtime : runtimePieces) {
            JsonObject piece = runtime.pieceJson();
            String pieceId = stringValue(piece, "pieceId", "piece_" + applied);
            int clearedForPiece = clearSoftObstacles(runtime.footprint(), runtime.anchor().getY(),
                    runtime.box().maxY());
            clearedVegetation += clearedForPiece;
            markPieceVegetationCleared(trace, pieceId, clearedForPiece);
            boolean placed = runtime.element().place(level.getStructureManager(), level, level.structureManager(),
                    level.getChunkSource().getGenerator(), runtime.anchor(), runtime.anchor(), runtime.rotation(),
                    runtime.box(), RandomSource.create(request.structureId().hashCode() * 31L + pieceId.hashCode()),
                    false);
            if (!placed) {
                String reason = "BOUNDED_JIGSAW_TEMPLATE_PASTE_FAILED";
                if (applied > 0) {
                    incrementTraceFailure(trace, "BOUNDED_JIGSAW_PARTIAL_WORLD_MUTATION");
                }
                incrementTraceFailure(trace, reason);
                markPiecePasteStatus(trace, pieceId, "failed", false, reason);
                return CityStructureD7Executor.PlacementResult.failed(reason,
                        "Accepted jigsaw piece returned false from place(): pieceId=" + pieceId
                                + ", templateId=" + stringValue(piece, "templateId", ""),
                        trace, acceptedFootprint, acceptedFootprint);
            }
            applied++;
            markPiecePasteStatus(trace, pieceId, "applied", true, "");
        }
        JsonObject metrics = trace.getAsJsonObject("metrics");
        metrics.addProperty("worldPasteAppliedPieceCount", applied);
        metrics.addProperty("worldPasteMode", "accepted_piece_template_paste");
        metrics.addProperty("clearedVegetationBlocks", clearedVegetation);
        return CityStructureD7Executor.PlacementResult.placed(
                "Placed " + applied + " accepted bounded jigsaw pieces for " + request.structureId(),
                trace, acceptedFootprint, acceptedFootprint);
    }

    private int clearSoftObstacles(BlockBounds footprint, int minY, int maxY) {
        if (!executeCommands || footprint == null) {
            return 0;
        }
        int changed = 0;
        int fromY = Math.max(level.getMinBuildHeight(), minY);
        int toY = Math.min(level.getMaxBuildHeight() - 1, Math.max(maxY, minY + 12));
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                for (int y = toY; y >= fromY; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                    if (!softClearable(state)) {
                        continue;
                    }
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    changed++;
                }
            }
        }
        return changed;
    }

    private ResolveResult resolveRuntimePiece(JsonObject piece, Holder<StructureTemplatePool> startPool) {
        String pieceId = stringValue(piece, "pieceId", "");
        String poolId = stringValue(piece, "poolId", stringValue(piece, "sourcePoolId", ""));
        String templateId = stringValue(piece, "templateId", "");
        JsonObject anchorJson = objectValue(piece, "anchorBlock", null);
        JsonObject footprintJson = objectValue(piece, "footprint", null);
        if (poolId.isBlank() || templateId.isBlank() || "unresolved".equals(templateId)
                || anchorJson == null || footprintJson == null) {
            return ResolveResult.failed("BOUNDED_JIGSAW_PIECE_RESOLVE_FAILED",
                    "Accepted jigsaw piece is missing runtime source fields: pieceId=" + pieceId);
        }
        Holder<StructureTemplatePool> pool = poolName(startPool).equals(poolId) ? startPool : templatePool(poolId);
        if (pool == null || pool.value() == null) {
            return ResolveResult.failed("BOUNDED_JIGSAW_POOL_MISSING",
                    "Accepted jigsaw piece pool is missing: pieceId=" + pieceId + ", poolId=" + poolId);
        }
        BlockPos pieceAnchor = blockPos(anchorJson);
        Rotation pieceRotation = rotation(stringValue(piece, "rotation", "NONE"));
        BlockBounds expectedFootprint = bounds(footprintJson);
        List<RuntimePiece> matches = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        for (StructurePoolElement element : pool.value().getShuffledTemplates(
                RandomSource.create((poolId + ":" + templateId).hashCode()))) {
            if (element instanceof EmptyPoolElement || !BoundedJigsawTemplateInspector.canInspectTemplate(element)
                    || !templateMatches(element, templateId)) {
                continue;
            }
            BoundingBox box;
            try {
                box = element.getBoundingBox(level.getStructureManager(), pieceAnchor, pieceRotation);
            } catch (RuntimeException ignored) {
                continue;
            }
            BlockBounds actualFootprint = new BlockBounds(box.minX(), box.minZ(), box.maxX(), box.maxZ());
            if (!sameBounds(expectedFootprint, actualFootprint)) {
                continue;
            }
            String signature = templateSignature(element);
            if (signatures.add(signature)) {
                JsonObject runtimePiece = piece.deepCopy();
                runtimePiece.addProperty("resolvedPoolId", poolName(pool));
                runtimePiece.addProperty("resolvedTemplateId", templateId);
                matches.add(new RuntimePiece(element, runtimePiece, pieceAnchor, pieceRotation, box,
                        actualFootprint));
            }
        }
        if (matches.isEmpty()) {
            return ResolveResult.failed("BOUNDED_JIGSAW_PIECE_RESOLVE_FAILED",
                    "Accepted jigsaw piece could not be resolved: pieceId=" + pieceId
                            + ", poolId=" + poolId + ", templateId=" + templateId);
        }
        if (matches.size() > 1) {
            return ResolveResult.failed("BOUNDED_JIGSAW_PIECE_AMBIGUOUS",
                    "Accepted jigsaw piece resolved to multiple runtime elements: pieceId=" + pieceId
                            + ", poolId=" + poolId + ", templateId=" + templateId);
        }
        return ResolveResult.ok(matches.get(0));
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

    private Holder<StructureTemplatePool> templatePool(String poolId) {
        ResourceLocation id = ResourceLocation.tryParse(poolId);
        if (id == null) {
            return null;
        }
        return server.registryAccess()
                .registryOrThrow(Registries.TEMPLATE_POOL)
                .getHolder(ResourceKey.create(Registries.TEMPLATE_POOL, id))
                .map(holder -> (Holder<StructureTemplatePool>) holder)
                .orElse(null);
    }

    private JsonArray acceptedPieces(JsonObject trace) {
        JsonObject plan = objectValue(trace, "plan", null);
        if (plan != null && plan.has("pieces") && plan.get("pieces").isJsonArray()) {
            JsonArray pieces = new JsonArray();
            for (JsonElement elem : plan.getAsJsonArray("pieces")) {
                if (elem.isJsonObject() && acceptedPiece(elem.getAsJsonObject())) {
                    pieces.add(elem.getAsJsonObject().deepCopy());
                }
            }
            return pieces;
        }
        JsonArray pieces = new JsonArray();
        for (JsonElement elem : arrayValue(trace, "acceptedPieces", new JsonArray())) {
            if (elem.isJsonObject() && acceptedPiece(elem.getAsJsonObject())) {
                pieces.add(elem.getAsJsonObject().deepCopy());
            }
        }
        return pieces;
    }

    private boolean acceptedPiece(JsonObject piece) {
        return !"failed".equals(stringValue(piece, "validatorResult", "passed"))
                && !"failed".equals(stringValue(piece, "alignmentStatus", ""));
    }

    private BlockBounds unionFootprint(JsonArray pieces) {
        BlockBounds union = null;
        for (JsonElement elem : pieces) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject footprint = objectValue(elem.getAsJsonObject(), "footprint", null);
            if (footprint == null) {
                continue;
            }
            BlockBounds bounds = bounds(footprint);
            union = union == null ? bounds : union(union, bounds);
        }
        return union;
    }

    private BlockBounds union(BlockBounds left, BlockBounds right) {
        return new BlockBounds(
                Math.min(left.minX(), right.minX()),
                Math.min(left.minZ(), right.minZ()),
                Math.max(left.maxX(), right.maxX()),
                Math.max(left.maxZ(), right.maxZ()));
    }

    private void markPiecePasteStatus(JsonObject trace, String pieceId, String status,
                                      boolean worldMutationApplied, String reasonCode) {
        markPieceArray(trace.getAsJsonArray("acceptedPieces"), pieceId, status, worldMutationApplied, reasonCode);
        JsonObject plan = objectValue(trace, "plan", null);
        if (plan != null && plan.has("pieces") && plan.get("pieces").isJsonArray()) {
            markPieceArray(plan.getAsJsonArray("pieces"), pieceId, status, worldMutationApplied, reasonCode);
        }
    }

    private void markPieceVegetationCleared(JsonObject trace, String pieceId, int clearedBlocks) {
        markPieceVegetationArray(trace.getAsJsonArray("acceptedPieces"), pieceId, clearedBlocks);
        JsonObject plan = objectValue(trace, "plan", null);
        if (plan != null && plan.has("pieces") && plan.get("pieces").isJsonArray()) {
            markPieceVegetationArray(plan.getAsJsonArray("pieces"), pieceId, clearedBlocks);
        }
    }

    private void markPieceVegetationArray(JsonArray pieces, String pieceId, int clearedBlocks) {
        if (pieces == null) {
            return;
        }
        for (JsonElement elem : pieces) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject piece = elem.getAsJsonObject();
            if (!pieceId.equals(stringValue(piece, "pieceId", ""))) {
                continue;
            }
            piece.addProperty("clearedVegetationBlocks", clearedBlocks);
        }
    }

    private void markPieceArray(JsonArray pieces, String pieceId, String status,
                                boolean worldMutationApplied, String reasonCode) {
        if (pieces == null) {
            return;
        }
        for (JsonElement elem : pieces) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject piece = elem.getAsJsonObject();
            if (!pieceId.equals(stringValue(piece, "pieceId", ""))) {
                continue;
            }
            piece.addProperty("pasteStatus", status);
            piece.addProperty("worldMutationApplied", worldMutationApplied);
            if (reasonCode != null && !reasonCode.isBlank()) {
                piece.addProperty("pasteReasonCode", reasonCode);
            }
        }
    }

    private boolean templateMatches(StructurePoolElement element, String templateId) {
        for (ResourceLocation id : BoundedJigsawTemplateInspector.templateIds(element)) {
            if (id.toString().equals(templateId)) {
                return true;
            }
        }
        return false;
    }

    private String templateSignature(StructurePoolElement element) {
        StringBuilder signature = new StringBuilder(element.getType().toString())
                .append("|").append(String.valueOf(element));
        for (ResourceLocation id : BoundedJigsawTemplateInspector.templateIds(element)) {
            signature.append("|").append(id);
        }
        return signature.toString();
    }

    private void markPieceResolved(JsonObject trace, String pieceId, String resolvedPoolId,
                                   String resolvedTemplateId) {
        markPieceResolvedArray(trace.getAsJsonArray("acceptedPieces"), pieceId, resolvedPoolId, resolvedTemplateId);
        JsonObject plan = objectValue(trace, "plan", null);
        if (plan != null && plan.has("pieces") && plan.get("pieces").isJsonArray()) {
            markPieceResolvedArray(plan.getAsJsonArray("pieces"), pieceId, resolvedPoolId, resolvedTemplateId);
        }
    }

    private void markPieceResolvedArray(JsonArray pieces, String pieceId, String resolvedPoolId,
                                        String resolvedTemplateId) {
        if (pieces == null) {
            return;
        }
        for (JsonElement elem : pieces) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject piece = elem.getAsJsonObject();
            if (!pieceId.equals(stringValue(piece, "pieceId", ""))) {
                continue;
            }
            if (resolvedPoolId != null && !resolvedPoolId.isBlank()) {
                piece.addProperty("resolvedPoolId", resolvedPoolId);
            }
            if (resolvedTemplateId != null && !resolvedTemplateId.isBlank()) {
                piece.addProperty("resolvedTemplateId", resolvedTemplateId);
            }
        }
    }

    private static boolean sameBounds(BlockBounds left, BlockBounds right) {
        return left != null && right != null
                && left.minX() == right.minX()
                && left.minZ() == right.minZ()
                && left.maxX() == right.maxX()
                && left.maxZ() == right.maxZ();
    }

    private static BlockPos blockPos(JsonObject obj) {
        return new BlockPos(intValue(obj, "x", 0), intValue(obj, "y", 0), intValue(obj, "z", 0));
    }

    private Rotation rotation(String value) {
        try {
            return Rotation.valueOf(value == null || value.isBlank() ? "NONE" : value);
        } catch (IllegalArgumentException ex) {
            return Rotation.NONE;
        }
    }

    private String poolName(Holder<StructureTemplatePool> pool) {
        return pool.unwrapKey().map(key -> key.location().toString()).orElse("inline");
    }

    private void incrementTraceFailure(JsonObject trace, String reasonCode) {
        JsonObject summary = trace.getAsJsonObject("failureSummary");
        summary.addProperty(reasonCode, intValue(summary, reasonCode, 0) + 1);
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static JsonObject chunkRangeJson(ChunkRange range) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minChunkX", range.minX());
        obj.addProperty("minChunkZ", range.minZ());
        obj.addProperty("maxChunkX", range.maxX());
        obj.addProperty("maxChunkZ", range.maxZ());
        return obj;
    }

    private static JsonArray arrayValue(JsonObject obj, String key, JsonArray defaultValue) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : defaultValue;
    }

    private static JsonObject objectValue(JsonObject obj, String key, JsonObject defaultValue) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : defaultValue;
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsInt();
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

    private CityTerrainProbe.Result probeTerrain(String probeId, String candidateId, BlockBounds footprint) {
        ChunkRange chunks = ChunkRange.from(footprint);
        String missing = missingChunks(chunks);
        if (!missing.isBlank()) {
            return CityTerrainProbe.waitingForChunks(probeId, candidateId, footprint, missing);
        }
        List<CityTerrainProbe.Sample> samples = terrainSamples(footprint);
        return CityTerrainProbe.evaluate(probeId, candidateId, footprint, samples);
    }

    private List<CityTerrainProbe.Sample> terrainSamples(BlockBounds footprint) {
        List<CityTerrainProbe.Sample> samples = new ArrayList<>();
        if (footprint == null) {
            return samples;
        }
        int stepX = Math.max(1, Math.max(footprint.widthBlocks() / 3, 4));
        int stepZ = Math.max(1, Math.max(footprint.heightBlocks() / 3, 4));
        for (int x = footprint.minX(); x <= footprint.maxX(); x += stepX) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z += stepZ) {
                samples.add(terrainSample(x, z));
            }
        }
        samples.add(terrainSample(footprint.maxX(), footprint.minZ()));
        samples.add(terrainSample(footprint.minX(), footprint.maxZ()));
        samples.add(terrainSample(footprint.maxX(), footprint.maxZ()));
        samples.add(terrainSample(footprint.center().x(), footprint.center().z()));
        return samples;
    }

    private CityTerrainProbe.Sample terrainSample(int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        y = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, y));
        boolean fluid = false;
        boolean support = false;
        for (int scanY = y + 1; scanY >= Math.max(level.getMinBuildHeight(), y - 3); scanY--) {
            BlockPos pos = new BlockPos(x, scanY, z);
            if (level.getFluidState(pos).is(FluidTags.WATER)) {
                fluid = true;
            }
        }
        net.minecraft.world.level.block.state.BlockState supportState = level.getBlockState(new BlockPos(x, y, z));
        support = !supportState.isAir() && supportState.getFluidState().isEmpty() && !softClearable(supportState);
        return new CityTerrainProbe.Sample(x, z, y, fluid, support);
    }

    private boolean softClearable(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null || state.isAir() || protectedBlock(state)) {
            return false;
        }
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.WART_BLOCKS)
                || state.is(BlockTags.MUSHROOM_GROW_BLOCK)
                || state.canBeReplaced();
    }

    private boolean protectedBlock(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.BEDROCK)
                || state.is(Blocks.BARRIER)
                || state.is(Blocks.COMMAND_BLOCK)
                || state.is(Blocks.CHAIN_COMMAND_BLOCK)
                || state.is(Blocks.REPEATING_COMMAND_BLOCK)
                || state.is(Blocks.STRUCTURE_BLOCK)
                || state.is(Blocks.END_PORTAL_FRAME);
    }

    private final class TerrainRuleEvaluator implements BoundedJigsawSolver.PieceRuleEvaluator {
        private final String sourceStructureId;
        private final JsonArray probes = new JsonArray();
        private boolean waiting;
        private String missingChunks = "";

        private TerrainRuleEvaluator(String sourceStructureId) {
            this.sourceStructureId = sourceStructureId == null ? "" : sourceStructureId;
        }

        @Override
        public BoundedJigsawSolver.PieceDecision evaluate(JsonObject piece, BlockBounds footprint,
                                                          int visibleAreaCostSoFar, int targetAreaBlocks) {
            String pieceId = stringValue(piece, "pieceId", "piece");
            CityTerrainProbe.Result result = probeTerrain("terrain_" + safeProbeId(sourceStructureId)
                            + "_" + safeProbeId(pieceId),
                    pieceId, footprint);
            probes.add(result.probe().deepCopy());
            piece.add("terrainProbe", result.probe().deepCopy());
            JsonArray ruleResults = new JsonArray();
            ruleResults.add(CityTerrainProbe.ruleResult(result));
            if ("JIGSAW_RULE_CHUNK_WAITING".equals(result.reasonCode())) {
                waiting = true;
                String missing = stringValue(result.probe(), "missingChunks", "");
                if (!missing.isBlank()) {
                    missingChunks = missingChunks.isBlank() ? missing : missingChunks + ";" + missing;
                }
            }
            return result.accepted()
                    ? BoundedJigsawSolver.PieceDecision.accept(ruleResults)
                    : BoundedJigsawSolver.PieceDecision.reject(result.reasonCode(), ruleResults);
        }

        private boolean hasWaiting() {
            return waiting;
        }

        private String missingChunks() {
            return missingChunks;
        }

        private JsonObject summary() {
            JsonObject obj = new JsonObject();
            obj.addProperty("schemaVersion", "city_terrain_probe_summary.v0.1");
            obj.addProperty("waiting", waiting);
            obj.addProperty("missingChunks", missingChunks);
            obj.addProperty("probeCount", probes.size());
            obj.add("probes", probes.deepCopy());
            return obj;
        }
    }

    private static String safeProbeId(String raw) {
        return raw == null || raw.isBlank() ? "unknown" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
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

    private record RuntimePiece(StructurePoolElement element, JsonObject pieceJson, BlockPos anchor,
                                Rotation rotation, BoundingBox box, BlockBounds footprint) {
    }

    private record ResolveResult(boolean success, String reasonCode, String message, RuntimePiece piece) {
        static ResolveResult ok(RuntimePiece piece) {
            return new ResolveResult(true, "", "", piece);
        }

        static ResolveResult failed(String reasonCode, String message) {
            return new ResolveResult(false, reasonCode, message, null);
        }
    }
}
