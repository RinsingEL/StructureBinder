package com.user.terra_script.world.city.stage.c8;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.Pools;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.block.Blocks;

import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.CityHeightResolver;
import com.user.terra_script.world.city.stage.CityC35CatalogIO;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class CityVanillaJigsawAdapterService {
    private static final Gson GSON = new GsonBuilder().create();
    // Current solver only handles one child-expansion step per request.
    // In vanilla JigsawPlacement semantics that means depth must be 1:
    // depth=0 still returns a stub, but the builder stays empty.
    private static final int JIGSAW_SINGLE_CHILD_DEPTH = 1;
    private static final int MAX_RADIUS = 128;

    private CityVanillaJigsawAdapterService() {}

    public static SolveResult solve(
            ServerLevel level,
            String cityId,
            CityC8Stages.AreaGeometry geometry,
            List<CityC8Stages.PlacementNode> existingPlacements,
            CityC8Stages.PlacementNode parentPlacement,
            String parentConnectorId,
            String selectedTemplateId,
            String selectedConnectorDir,
            Integer selectedRotation
    ) {
        return solve(
                level,
                cityId,
                geometry,
                existingPlacements,
                parentPlacement,
                parentConnectorId,
                selectedTemplateId,
                selectedConnectorDir,
                selectedRotation,
                null,
                null
        );
    }

    public static SolveResult solve(
            ServerLevel level,
            String cityId,
            CityC8Stages.AreaGeometry geometry,
            List<CityC8Stages.PlacementNode> existingPlacements,
            CityC8Stages.PlacementNode parentPlacement,
            String parentConnectorId,
            String selectedTemplateId,
            String selectedConnectorDir,
            Integer selectedRotation,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData
    ) {
        SolveResult result = new SolveResult();
        result.debug = new DebugDetails();
        result.selected_template_id = selectedTemplateId;
        result.selected_connector_dir = selectedConnectorDir;
        result.selected_rotation = selectedRotation;
        result.debug.selected_template_id = selectedTemplateId;
        result.debug.requested_parent_connector_id = parentConnectorId;
        result.debug.selected_connector_dir = selectedConnectorDir;
        result.debug.selected_rotation = selectedRotation;
        if (selectedRotation != null) {
            result.warnings.add("selected_rotation_ignored_by_vanilla_jigsaw");
        }
        if (level == null) {
            result.reject_reason = "missing_server_level";
            return result;
        }
        if (geometry == null || !geometry.valid) {
            result.reject_reason = "missing_area_geometry";
            return result;
        }
        if (parentPlacement == null || parentPlacement.template_id == null || parentPlacement.template_id.isBlank()) {
            result.reject_reason = "missing_parent_placement";
            return result;
        }
        if (selectedTemplateId == null || selectedTemplateId.isBlank()) {
            result.reject_reason = "missing_selected_template";
            return result;
        }
        if (parentConnectorId == null || parentConnectorId.isBlank()) {
            result.reject_reason = "missing_parent_connector_id";
            return result;
        }
        if (isSyntheticConnectorId(parentConnectorId)) {
            result.reject_reason = "parent_connector_must_be_real_jigsaw_id";
            return result;
        }

        Map<String, CityC35CatalogIO.CatalogStructure> metaById = loadCatalog();
        if (metaById.isEmpty()) {
            result.reject_reason = "missing_catalog_meta";
            return result;
        }
        CityC35CatalogIO.CatalogStructure parentMeta = metaById.get(parentPlacement.template_id);
        CityC35CatalogIO.CatalogStructure childMeta = metaById.get(selectedTemplateId);
        if (childMeta == null) {
            result.reject_reason = "missing_selected_template_meta";
            return result;
        }

        StructureTemplateManager templateManager = level.getStructureManager();
        Rotation parentRotation = toRotation(parentPlacement.rotation);
        List<RuntimeParentConnector> runtimeParentConnectors = resolveRuntimeParentConnectors(templateManager, parentPlacement, parentRotation);
        for (RuntimeParentConnector connector : runtimeParentConnectors) {
            if (connector != null && connector.candidate != null) {
                result.runtime_parent_connectors.add(connector.candidate);
            }
        }
        ParentConnectorContext parentContext = findRuntimeParentConnector(runtimeParentConnectors, parentConnectorId);
        if (parentContext != null) {
            result.warnings.add("parent_connector_resolved_from_runtime_template");
        }
        if (parentContext == null && parentMeta == null) {
            appendParentConnectorDebug(result, runtimeParentConnectors, null, null, null, parentRotation);
            result.reject_reason = "missing_parent_catalog_meta";
            return result;
        }
        CityC35CatalogIO.ConnectorSpec parentConnector = null;
        if (parentContext == null) {
            parentConnector = findConnector(parentMeta, parentConnectorId);
            if (parentConnector == null) {
                appendParentConnectorDebug(result, runtimeParentConnectors, null, null, null, parentRotation);
                result.reject_reason = "parent_connector_not_found_in_catalog";
                return result;
            }
            applyExpectedParentConnectorDebug(result, parentPlacement, parentMeta, parentConnector, parentRotation);
        }
        if (parentContext == null) {
            parentContext = resolveParentConnectorContext(parentPlacement, parentMeta, parentConnector, parentRotation, parentConnectorId, runtimeParentConnectors);
        }
        if (parentContext == null) {
            appendParentConnectorDebug(result, runtimeParentConnectors, parentPlacement, parentMeta, parentConnector, parentRotation);
            result.reject_reason = "parent_connector_not_found_in_template";
            return result;
        }
        ResourceLocation target = parentContext.target();
        if (target == null) {
            result.reject_reason = "parent_connector_target_missing";
            return result;
        }
        applyResolvedParentConnectorDebug(result, parentContext);
        Direction resolvedFront = parentContext.blockInfo() != null ? JigsawBlock.getFrontFacing(parentContext.blockInfo().state()) : null;
        if (isVerticalFront(resolvedFront)) {
            return CityVerticalJigsawPlaceholderService.pending(
                    result,
                    "当前 solver 仅支持水平 jigsaw，垂直 jigsaw 将由独立求解器处理。"
            );
        }

        Holder<StructureTemplatePool> poolHolder = buildSingleTemplatePool(level, selectedTemplateId);
        if (poolHolder == null) {
            result.reject_reason = "temporary_template_pool_unavailable";
            return result;
        }
        long stableSeed = stableSeed(level.getSeed(), cityId, parentPlacement.node_id, parentConnectorId, selectedTemplateId);
        result.debug.stable_seed = stableSeed;
        result.debug.pool_template_id = selectedTemplateId;
        result.debug.generation_depth = JIGSAW_SINGLE_CHILD_DEPTH;
        result.debug.generation_max_radius = MAX_RADIUS;
        result.debug.parent_target = target.toString();
        result.debug.start_pos_x = parentContext.startPos() != null ? parentContext.startPos().getX() : null;
        result.debug.start_pos_y = parentContext.startPos() != null ? parentContext.startPos().getY() : null;
        result.debug.start_pos_z = parentContext.startPos() != null ? parentContext.startPos().getZ() : null;
        List<ManualChildConnectorCandidate> manualCandidates = analyzeManualChildConnectors(
                templateManager,
                geometry,
                heightData,
                c2ScanData,
                existingPlacements,
                childMeta,
                selectedTemplateId,
                parentContext
        );
        if (manualCandidates != null && !manualCandidates.isEmpty()) {
            result.debug.manual_child_connector_candidates.addAll(manualCandidates);
        }

        VanillaGenerationAttempt generation = generateSingleChildPiece(level, stableSeed, parentContext.startPos(), poolHolder, target);
        applyVanillaGenerationDiagnostics(result.debug, generation != null ? generation.diagnostics() : null);
        result.debug.vanilla_stub_generated = generation != null && generation.stubGenerated();
        SolveArtifact artifact = generation != null ? generation.artifact() : null;
        if (artifact == null || artifact.piece == null) {
            result.debug.piece_generated = false;
            result.debug.manual_attach_summary = summarizeManualCandidates(
                    result.debug.manual_child_connector_candidates,
                    false,
                    Boolean.TRUE.equals(result.debug.vanilla_stub_generated),
                    generation != null ? generation.diagnostics() : null
            );
            result.debug.first_blocker_stage = result.debug.manual_attach_summary != null
                    ? result.debug.manual_attach_summary.first_blocker_stage
                    : null;
            result.reject_reason = "no_valid_jigsaw_solution";
            return result;
        }
        result.debug.piece_generated = true;
        result.debug.manual_attach_summary = summarizeManualCandidates(
                result.debug.manual_child_connector_candidates,
                true,
                Boolean.TRUE.equals(result.debug.vanilla_stub_generated),
                generation != null ? generation.diagnostics() : null
        );
        result.debug.first_blocker_stage = result.debug.manual_attach_summary != null
                ? result.debug.manual_attach_summary.first_blocker_stage
                : null;
        result.debug.generated_origin_x = artifact.piece.getPosition().getX();
        result.debug.generated_origin_y = artifact.piece.getPosition().getY();
        result.debug.generated_origin_z = artifact.piece.getPosition().getZ();
        result.debug.generated_bounds = ResolvedBounds.fromBoundingBox(artifact.bounds);
        if (!insideArea(geometry, artifact.bounds)) {
            result.reject_reason = "out_of_area";
            return result;
        }

        StructureTemplate.StructureBlockInfo childConnectorInfo = findAttachedChildConnector(templateManager, childMeta.structure_id, artifact, parentContext);
        String childConnectorId = mapConnectorId(childMeta, artifact.piece.getPosition(), artifact.piece.getRotation(), childConnectorInfo);
        if (childConnectorId != null) {
            result.debug.child_connector_source = "catalog_projection";
        }
        if (childConnectorId == null && childConnectorInfo != null) {
            RuntimeConnectorCandidate runtimeChild = runtimeConnectorCandidate(artifact.piece.getPosition(), artifact.piece.getRotation(), childConnectorInfo);
            childConnectorId = runtimeChild != null ? runtimeChild.id : null;
            if (childConnectorId != null) {
                result.warnings.add("child_connector_resolved_from_runtime_template");
                result.debug.child_connector_source = "runtime_template";
            }
        }

        CityC8Stages.PlacementNode placement = toPlacementNode(
                parentPlacement,
                childMeta,
                artifact,
                parentContext,
                childConnectorId,
                childConnectorInfo,
                nextBuildOrder(existingPlacements)
        );
        result.ok = true;
        result.placement = placement;
        result.resolved_origin_x = placement.x;
        result.resolved_origin_z = placement.z;
        result.resolved_rotation = placement.rotation;
        result.incoming_parent_connector_id = parentConnectorId;
        result.incoming_child_connector_id = childConnectorId;
        result.resolved_bounds = ResolvedBounds.fromBoundingBox(artifact.bounds);
        result.descriptor = VanillaPlacementDescriptor.fromArtifact(artifact);
        return result;
    }

    private static Map<String, CityC35CatalogIO.CatalogStructure> loadCatalog() {
        Map<String, CityC35CatalogIO.CatalogStructure> index = new LinkedHashMap<>();
        try {
            CityC35CatalogIO.StructureCatalog catalog = CityC35CatalogIO.loadCatalog(GSON, CityC35CatalogIO.StructureCatalog.class);
            if (catalog == null || catalog.structures == null) return index;
            for (CityC35CatalogIO.CatalogStructure meta : catalog.structures) {
                if (meta != null && meta.structure_id != null && !meta.structure_id.isBlank()) {
                    index.put(meta.structure_id, meta);
                }
            }
        } catch (Exception ignored) {
        }
        return index;
    }

    private static CityC35CatalogIO.ConnectorSpec findConnector(CityC35CatalogIO.CatalogStructure meta, String connectorId) {
        if (meta == null || connectorId == null || connectorId.isBlank() || meta.connectors == null) return null;
        for (CityC35CatalogIO.ConnectorSpec connector : meta.connectors) {
            if (connector != null && connectorId.equals(connector.id)) return connector;
        }
        return null;
    }

    private static ParentConnectorContext resolveParentConnectorContext(
            CityC8Stages.PlacementNode parentPlacement,
            CityC35CatalogIO.CatalogStructure parentMeta,
            CityC35CatalogIO.ConnectorSpec parentConnector,
            Rotation parentRotation,
            String parentConnectorId,
            List<RuntimeParentConnector> runtimeParentConnectors
    ) {
        CityC35CatalogIO.Vec3i originOffset = parentMeta.placement != null && parentMeta.placement.origin_offset != null
                ? parentMeta.placement.origin_offset
                : new CityC35CatalogIO.Vec3i();
        int[] rotated = rotateLocal(parentConnector.local_pos.x - originOffset.x, parentConnector.local_pos.z - originOffset.z, parentRotation);
        int expectedX = parentPlacement.x + rotated[0];
        int expectedY = parentPlacement.y + (parentConnector.local_pos.y - originOffset.y);
        int expectedZ = parentPlacement.z + rotated[1];
        Direction expectedFacing = rotateFacing(parseDirection(parentConnector.facing), parentRotation);
        for (RuntimeParentConnector runtimeConnector : runtimeParentConnectors) {
            if (runtimeConnector == null || runtimeConnector.blockInfo == null) continue;
            StructureTemplate.StructureBlockInfo blockInfo = runtimeConnector.blockInfo;
            if (blockInfo == null || blockInfo.nbt() == null) continue;
            Direction facing = JigsawBlock.getFrontFacing(blockInfo.state());
            if (blockInfo.pos().getX() != expectedX || blockInfo.pos().getY() != expectedY || blockInfo.pos().getZ() != expectedZ) continue;
            if (expectedFacing != null && facing != expectedFacing) continue;
            ResourceLocation target = ResourceLocation.tryParse(blockInfo.nbt().getString("target"));
            BlockPos startPos = blockInfo.pos().relative(facing);
            return new ParentConnectorContext(parentConnectorId, blockInfo, startPos, target, "catalog_projection");
        }
        return null;
    }

    private static void appendParentConnectorDebug(
            SolveResult result,
            List<RuntimeParentConnector> runtimeParentConnectors,
            CityC8Stages.PlacementNode parentPlacement,
            CityC35CatalogIO.CatalogStructure parentMeta,
            CityC35CatalogIO.ConnectorSpec parentConnector,
            Rotation parentRotation
    ) {
        appendRuntimeParentConnectorDebug(result, runtimeParentConnectors);
        if (result == null || parentPlacement == null || parentMeta == null || parentConnector == null) return;
        CityC35CatalogIO.Vec3i originOffset = parentMeta.placement != null && parentMeta.placement.origin_offset != null
                ? parentMeta.placement.origin_offset
                : new CityC35CatalogIO.Vec3i();
        int[] rotated = rotateLocal(parentConnector.local_pos.x - originOffset.x, parentConnector.local_pos.z - originOffset.z, parentRotation);
        int expectedX = parentPlacement.x + rotated[0];
        int expectedY = parentPlacement.y + (parentConnector.local_pos.y - originOffset.y);
        int expectedZ = parentPlacement.z + rotated[1];
        Direction expectedFacing = rotateFacing(parseDirection(parentConnector.facing), parentRotation);
        result.warnings.add("parent_connector_expected="
                + expectedX + "," + expectedY + "," + expectedZ
                + ",facing=" + (expectedFacing != null ? expectedFacing.getSerializedName() : "unknown"));
    }

    private static void appendRuntimeParentConnectorDebug(
            SolveResult result,
            List<RuntimeParentConnector> runtimeParentConnectors
    ) {
        if (result == null) return;
        if (runtimeParentConnectors == null || runtimeParentConnectors.isEmpty()) {
            result.warnings.add("parent_template_jigsaws=empty");
            return;
        }
        int count = 0;
        for (RuntimeParentConnector runtimeConnector : runtimeParentConnectors) {
            if (runtimeConnector == null || runtimeConnector.candidate == null) continue;
            RuntimeConnectorCandidate candidate = runtimeConnector.candidate;
            result.warnings.add("parent_template_jigsaw[" + count + "]="
                    + candidate.world_x + "," + candidate.world_y + "," + candidate.world_z
                    + ",id=" + candidate.id
                    + ",front=" + candidate.front
                    + ",name=" + safe(candidate.name)
                    + ",target=" + safe(candidate.target));
            count++;
            if (count >= 6) break;
        }
    }

    private static List<RuntimeParentConnector> resolveRuntimeParentConnectors(
            StructureTemplateManager templateManager,
            CityC8Stages.PlacementNode parentPlacement,
            Rotation parentRotation
    ) {
        List<RuntimeParentConnector> runtimeConnectors = new ArrayList<>();
        if (templateManager == null || parentPlacement == null) return runtimeConnectors;
        List<StructureTemplate.StructureBlockInfo> jigsaws = jigsawBlocks(
                templateManager,
                parentPlacement.template_id,
                new BlockPos(parentPlacement.x, parentPlacement.y, parentPlacement.z),
                parentRotation
        );
        for (StructureTemplate.StructureBlockInfo info : jigsaws) {
            RuntimeConnectorCandidate candidate = runtimeConnectorCandidate(
                    new BlockPos(parentPlacement.x, parentPlacement.y, parentPlacement.z),
                    parentRotation,
                    info
            );
            if (candidate == null || candidate.id == null || candidate.id.isBlank()) continue;
            ResourceLocation target = info != null && info.nbt() != null
                    ? ResourceLocation.tryParse(info.nbt().getString("target"))
                    : null;
            BlockPos startPos = info != null
                    ? info.pos().relative(JigsawBlock.getFrontFacing(info.state()))
                    : null;
            runtimeConnectors.add(new RuntimeParentConnector(candidate, info, startPos, target));
        }
        return runtimeConnectors;
    }

    public static List<RuntimeConnectorCandidate> scanRuntimeConnectors(
            ServerLevel level,
            CityC8Stages.PlacementNode placement
    ) {
        List<RuntimeConnectorCandidate> out = new ArrayList<>();
        if (level == null || placement == null) return out;
        Rotation rotation = toRotation(placement.rotation);
        List<RuntimeParentConnector> connectors = resolveRuntimeParentConnectors(level.getStructureManager(), placement, rotation);
        for (RuntimeParentConnector connector : connectors) {
            if (connector != null && connector.candidate != null) out.add(connector.candidate);
        }
        return out;
    }

    public static boolean isHorizontalFront(String front) {
        String normalized = front == null ? "" : front.trim().toLowerCase(Locale.ROOT);
        return "north".equals(normalized) || "south".equals(normalized) || "east".equals(normalized) || "west".equals(normalized);
    }

    public static boolean isVerticalFront(String front) {
        String normalized = front == null ? "" : front.trim().toLowerCase(Locale.ROOT);
        return "up".equals(normalized) || "down".equals(normalized);
    }

    public static boolean isVerticalFront(Direction direction) {
        return direction == Direction.UP || direction == Direction.DOWN;
    }

    private static ParentConnectorContext findRuntimeParentConnector(
            List<RuntimeParentConnector> runtimeParentConnectors,
            String parentConnectorId
    ) {
        if (runtimeParentConnectors == null || runtimeParentConnectors.isEmpty() || parentConnectorId == null || parentConnectorId.isBlank()) {
            return null;
        }
        for (RuntimeParentConnector runtimeConnector : runtimeParentConnectors) {
            if (runtimeConnector == null || runtimeConnector.candidate == null) continue;
            if (!parentConnectorId.equals(runtimeConnector.candidate.id)) continue;
            return new ParentConnectorContext(
                    runtimeConnector.candidate.id,
                    runtimeConnector.blockInfo,
                    runtimeConnector.startPos,
                    runtimeConnector.target,
                    "runtime_template"
            );
        }
        return null;
    }

    private static List<StructureTemplate.StructureBlockInfo> jigsawBlocks(
            StructureTemplateManager templateManager,
            String templateId,
            BlockPos origin,
            Rotation rotation
    ) {
        if (templateManager == null || templateId == null || templateId.isBlank() || origin == null) {
            return List.of();
        }
        Optional<StructureTemplate> templateOp = templateManager.get(new ResourceLocation(templateId));
        if (templateOp.isEmpty()) {
            return List.of();
        }
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setMirror(Mirror.NONE)
                .setRotation(rotation != null ? rotation : Rotation.NONE);
        return templateOp.get().filterBlocks(origin, settings, Blocks.JIGSAW);
    }

    private static Holder<StructureTemplatePool> buildSingleTemplatePool(ServerLevel level, String templateId) {
        if (level == null || templateId == null || templateId.isBlank()) return null;
        Registry<StructureTemplatePool> registry = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        Holder<StructureTemplatePool> emptyHolder = registry.getHolderOrThrow(Pools.EMPTY);
        StructurePoolElement element = StructurePoolElement.single(templateId).apply(StructureTemplatePool.Projection.RIGID);
        StructureTemplatePool pool = new StructureTemplatePool(emptyHolder, List.of(Pair.of(element, 1)));
        return Holder.direct(pool);
    }

    private static VanillaGenerationAttempt generateSingleChildPiece(
            ServerLevel level,
            long stableSeed,
            BlockPos startPos,
            Holder<StructureTemplatePool> poolHolder,
            ResourceLocation targetName
    ) {
        VanillaGenerationDiagnostics diagnostics = new VanillaGenerationDiagnostics();
        if (level == null || startPos == null || poolHolder == null || targetName == null) {
            diagnostics.extraction_stage = "missing_generation_inputs";
            diagnostics.summary_zh = "调用 vanilla jigsaw 前缺少必要输入，本次没有进入 GenerationStub 构造。";
            diagnostics.piece_count = 0;
            diagnostics.pool_element_piece_count = 0;
            return new VanillaGenerationAttempt(false, null, diagnostics);
        }
        ChunkPos chunkPos = new ChunkPos(startPos);
        WorldgenRandom random = randomFromSeed(stableSeed, chunkPos);
        Structure.GenerationContext context = new Structure.GenerationContext(
                level.registryAccess(),
                level.getChunkSource().getGenerator(),
                level.getChunkSource().getGenerator().getBiomeSource(),
                level.getChunkSource().randomState(),
                level.getStructureManager(),
                random,
                stableSeed,
                chunkPos,
                level,
                biome -> true
        );
        Optional<Structure.GenerationStub> stub = JigsawPlacement.addPieces(
                context,
                poolHolder,
                Optional.of(targetName),
                JIGSAW_SINGLE_CHILD_DEPTH,
                startPos,
                false,
                Optional.empty(),
                MAX_RADIUS
        );
        if (stub.isEmpty()) {
            diagnostics.extraction_stage = "stub_empty";
            diagnostics.summary_zh = "JigsawPlacement.addPieces(...) 没有返回 GenerationStub。";
            diagnostics.piece_count = 0;
            diagnostics.pool_element_piece_count = 0;
            return new VanillaGenerationAttempt(false, null, diagnostics);
        }
        PiecesContainer piecesContainer = stub.get().getPiecesBuilder().build();
        LinkedHashSet<String> pieceTypes = new LinkedHashSet<>();
        int pieceIndex = 0;
        int poolElementPieceCount = 0;
        PoolElementStructurePiece selectedPoolPiece = null;
        Integer selectedPoolPieceIndex = null;
        for (StructurePiece piece : piecesContainer.pieces()) {
            VanillaPieceDebugItem item = describeVanillaPiece(pieceIndex, piece);
            diagnostics.piece_items.add(item);
            if (item != null && item.piece_type != null && !item.piece_type.isBlank()) {
                pieceTypes.add(item.piece_type);
            }
            if (piece instanceof PoolElementStructurePiece poolPiece) {
                poolElementPieceCount++;
                if (selectedPoolPiece == null) {
                    selectedPoolPiece = poolPiece;
                    selectedPoolPieceIndex = pieceIndex;
                }
            }
            pieceIndex++;
        }
        diagnostics.piece_count = pieceIndex;
        diagnostics.pool_element_piece_count = poolElementPieceCount;
        diagnostics.piece_types.addAll(pieceTypes);
        diagnostics.selected_piece_index = selectedPoolPieceIndex;
        if (selectedPoolPiece != null) {
            diagnostics.extraction_stage = "pool_element_piece_selected";
            diagnostics.summary_zh = "vanilla builder 共返回 " + pieceIndex
                    + " 个 StructurePiece，其中 PoolElementStructurePiece " + poolElementPieceCount
                    + " 个，当前已选中索引 " + selectedPoolPieceIndex + " 的 piece 作为 child。";
            return new VanillaGenerationAttempt(true, new SolveArtifact(selectedPoolPiece, selectedPoolPiece.getBoundingBox(), startPos), diagnostics);
        }
        if (pieceIndex <= 0) {
            diagnostics.extraction_stage = "builder_empty";
            diagnostics.summary_zh = "JigsawPlacement.addPieces(...) 已返回 stub，但 piecesBuilder.build() 为空，没有任何 StructurePiece。";
            return new VanillaGenerationAttempt(true, null, diagnostics);
        }
        diagnostics.extraction_stage = "no_pool_element_piece";
        diagnostics.summary_zh = "vanilla builder 共返回 " + pieceIndex
                + " 个 StructurePiece，但没有任何 PoolElementStructurePiece。"
                + (pieceTypes.isEmpty() ? "" : " 当前实际类型: " + String.join(", ", pieceTypes) + "。");
        return new VanillaGenerationAttempt(true, null, diagnostics);
    }

    private static List<ManualChildConnectorCandidate> analyzeManualChildConnectors(
            StructureTemplateManager templateManager,
            CityC8Stages.AreaGeometry geometry,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            List<CityC8Stages.PlacementNode> existingPlacements,
            CityC35CatalogIO.CatalogStructure childMeta,
            String childTemplateId,
            ParentConnectorContext parentContext
    ) {
        List<ManualChildConnectorCandidate> out = new ArrayList<>();
        if (templateManager == null || childTemplateId == null || childTemplateId.isBlank() || parentContext == null || parentContext.startPos() == null) {
            return out;
        }
        for (Integer degrees : candidateRotations(childMeta)) {
            Rotation rotation = toRotation(degrees != null ? degrees : 0);
            for (StructureTemplate.StructureBlockInfo childInfo : jigsawBlocks(templateManager, childTemplateId, BlockPos.ZERO, rotation)) {
                if (childInfo == null) continue;
                RuntimeConnectorCandidate runtime = runtimeConnectorCandidate(BlockPos.ZERO, rotation, childInfo);
                BlockPos candidateOrigin = candidateOrigin(parentContext.startPos(), childInfo);
                BoundingBox bounds = candidateBounds(templateManager, childTemplateId, candidateOrigin, rotation);

                ManualChildConnectorCandidate candidate = new ManualChildConnectorCandidate();
                candidate.rotation = degrees != null ? degrees : 0;
                if (runtime != null) {
                    candidate.id = runtime.id;
                    candidate.local_x = runtime.local_x;
                    candidate.local_y = runtime.local_y;
                    candidate.local_z = runtime.local_z;
                    candidate.front = runtime.front;
                    candidate.name = runtime.name;
                    candidate.target = runtime.target;
                }
                candidate.world_x = candidateOrigin.getX() + childInfo.pos().getX();
                candidate.world_y = candidateOrigin.getY() + childInfo.pos().getY();
                candidate.world_z = candidateOrigin.getZ() + childInfo.pos().getZ();
                candidate.matches_parent_target = parentContext.target() != null
                        && childInfo.nbt() != null
                        && parentContext.target().toString().equals(childInfo.nbt().getString("name"));
                candidate.can_attach_to_parent = candidate.matches_parent_target
                        && parentContext.blockInfo() != null
                        && JigsawBlock.canAttach(parentContext.blockInfo(), childInfo);
                candidate.candidate_origin_x = candidateOrigin.getX();
                candidate.candidate_origin_y = candidateOrigin.getY();
                candidate.candidate_origin_z = candidateOrigin.getZ();
                candidate.candidate_bounds = ResolvedBounds.fromBoundingBox(bounds);
                candidate.inside_area = bounds != null && insideArea(geometry, bounds);
                candidate.footprint_collision = bounds != null && intersectsExisting(existingPlacements, bounds);
                candidate.terrain_rejected = bounds != null && terrainRejected(heightData, c2ScanData, childMeta, candidateOrigin, rotation, bounds);
                candidate.viable = Boolean.TRUE.equals(candidate.matches_parent_target)
                        && Boolean.TRUE.equals(candidate.can_attach_to_parent)
                        && Boolean.TRUE.equals(candidate.inside_area)
                        && !Boolean.TRUE.equals(candidate.footprint_collision)
                        && !Boolean.TRUE.equals(candidate.terrain_rejected);
                candidate.reject_stage = resolveManualRejectStage(candidate);
                out.add(candidate);
            }
        }
        return out;
    }

    static ManualAttachSummary summarizeManualCandidates(
            List<ManualChildConnectorCandidate> candidates,
            boolean pieceGenerated,
            boolean vanillaStubGenerated,
            VanillaGenerationDiagnostics diagnostics
    ) {
        ManualAttachSummary summary = new ManualAttachSummary();
        summary.truth_source = "runtime_template";
        summary.total_candidate_count = candidates != null ? candidates.size() : 0;
        if (candidates == null || candidates.isEmpty()) {
            summary.first_blocker_stage = pieceGenerated ? "generated" : "target/name";
            String baseSummary = pieceGenerated
                    ? "vanilla 已成功生成 child piece，但当前没有可回放的手工 child 候选记录。"
                    : "child 模板中没有扫到任何 runtime jigsaw 候选。";
            summary.summary_zh = combineZhSummary(baseSummary, diagnostics != null ? diagnostics.summary_zh : null);
            return summary;
        }

        int targetMatches = 0;
        int attachable = 0;
        int areaPass = 0;
        int collisionFree = 0;
        int terrainPass = 0;
        int viable = 0;
        boolean terrainBlocked = false;

        for (ManualChildConnectorCandidate candidate : candidates) {
            if (candidate == null) continue;
            if (Boolean.TRUE.equals(candidate.matches_parent_target)) targetMatches++;
            if (Boolean.TRUE.equals(candidate.matches_parent_target) && Boolean.TRUE.equals(candidate.can_attach_to_parent)) attachable++;
            if (Boolean.TRUE.equals(candidate.matches_parent_target)
                    && Boolean.TRUE.equals(candidate.can_attach_to_parent)
                    && Boolean.TRUE.equals(candidate.inside_area)) {
                areaPass++;
            }
            if (Boolean.TRUE.equals(candidate.matches_parent_target)
                    && Boolean.TRUE.equals(candidate.can_attach_to_parent)
                    && Boolean.TRUE.equals(candidate.inside_area)
                    && !Boolean.TRUE.equals(candidate.footprint_collision)) {
                collisionFree++;
            }
            if (Boolean.TRUE.equals(candidate.matches_parent_target)
                    && Boolean.TRUE.equals(candidate.can_attach_to_parent)
                    && Boolean.TRUE.equals(candidate.inside_area)
                    && !Boolean.TRUE.equals(candidate.footprint_collision)
                    && !Boolean.TRUE.equals(candidate.terrain_rejected)) {
                terrainPass++;
            }
            if (Boolean.TRUE.equals(candidate.viable)) viable++;
            if (Boolean.TRUE.equals(candidate.matches_parent_target)
                    && Boolean.TRUE.equals(candidate.can_attach_to_parent)
                    && Boolean.TRUE.equals(candidate.inside_area)
                    && !Boolean.TRUE.equals(candidate.footprint_collision)
                    && Boolean.TRUE.equals(candidate.terrain_rejected)) {
                terrainBlocked = true;
            }
        }

        summary.target_name_match_count = targetMatches;
        summary.attachable_count = attachable;
        summary.area_pass_count = areaPass;
        summary.collision_free_count = collisionFree;
        summary.terrain_pass_count = terrainPass;
        summary.viable_count = viable;

        if (pieceGenerated) {
            summary.first_blocker_stage = "generated";
            String baseSummary = viable > 0
                    ? "手工分析存在可行候选，vanilla 也成功生成了 child piece。"
                    : "vanilla 已成功生成 child piece，但手工候选分析未找到完整可行候选，请继续核对生成与诊断语义。";
            summary.summary_zh = combineZhSummary(baseSummary, diagnostics != null ? diagnostics.summary_zh : null);
            return summary;
        }
        if (targetMatches <= 0) {
            summary.first_blocker_stage = "target/name";
            summary.summary_zh = "child 模板中没有任何 jigsaw 的 name 命中 parent target。";
            return summary;
        }
        if (attachable <= 0) {
            summary.first_blocker_stage = "attach";
            summary.summary_zh = "存在 name 命中的 child jigsaw，但都未通过 attach 判定。";
            return summary;
        }
        if (viable <= 0) {
            if (terrainBlocked) {
                summary.first_blocker_stage = "terrain";
                summary.summary_zh = "存在 attach 成立且矩形可落位的候选，但都被地形约束拦下。";
            } else {
                summary.first_blocker_stage = "bounds/area";
                summary.summary_zh = "存在 attach 成立的候选，但候选矩形全部越界或与已有结构发生碰撞。";
            }
            return summary;
        }
        summary.first_blocker_stage = "vanilla_empty_stub";
        if (vanillaStubGenerated && diagnostics != null && diagnostics.summary_zh != null && !diagnostics.summary_zh.isBlank()) {
            summary.summary_zh = "手工分析存在可行候选，但 vanilla stub 未形成可用 child piece。"
                    + " 当前提取诊断："
                    + diagnostics.summary_zh;
        } else {
            summary.summary_zh = vanillaStubGenerated
                    ? "手工分析存在可行候选，但 vanilla stub 未形成可用 child piece，需要继续核对 piece 提取语义。"
                    : "手工分析存在可行候选，但 vanilla 仍返回空 stub，需要继续核对 startPos / depth / projection 语义。";
        }
        return summary;
    }

    private static VanillaPieceDebugItem describeVanillaPiece(int index, StructurePiece piece) {
        VanillaPieceDebugItem item = new VanillaPieceDebugItem();
        item.index = index;
        if (piece == null) {
            item.piece_type = "null";
            item.pool_element_piece = false;
            return item;
        }
        String simpleName = piece.getClass().getSimpleName();
        item.piece_type = (simpleName == null || simpleName.isBlank()) ? piece.getClass().getName() : simpleName;
        item.pool_element_piece = piece instanceof PoolElementStructurePiece;
        item.bounds = ResolvedBounds.fromBoundingBox(piece.getBoundingBox());
        if (piece instanceof PoolElementStructurePiece poolPiece) {
            item.origin_x = poolPiece.getPosition().getX();
            item.origin_y = poolPiece.getPosition().getY();
            item.origin_z = poolPiece.getPosition().getZ();
            item.rotation = toDegrees(poolPiece.getRotation());
        }
        return item;
    }

    private static void applyVanillaGenerationDiagnostics(DebugDetails debug, VanillaGenerationDiagnostics diagnostics) {
        if (debug == null || diagnostics == null) return;
        debug.vanilla_piece_count = diagnostics.piece_count;
        debug.vanilla_pool_element_piece_count = diagnostics.pool_element_piece_count;
        debug.vanilla_piece_extraction_stage = diagnostics.extraction_stage;
        debug.vanilla_piece_debug_summary_zh = diagnostics.summary_zh;
        debug.selected_vanilla_piece_index = diagnostics.selected_piece_index;
        if (diagnostics.piece_types != null && !diagnostics.piece_types.isEmpty()) {
            debug.vanilla_piece_types.addAll(diagnostics.piece_types);
        }
        if (diagnostics.piece_items != null && !diagnostics.piece_items.isEmpty()) {
            debug.vanilla_piece_items.addAll(diagnostics.piece_items);
        }
    }

    private static String combineZhSummary(String primary, String detail) {
        if (primary == null || primary.isBlank()) return detail;
        if (detail == null || detail.isBlank()) return primary;
        if (primary.contains(detail)) return primary;
        return primary + " 当前 vanilla builder 诊断：" + detail;
    }

    private static String resolveManualRejectStage(ManualChildConnectorCandidate candidate) {
        if (candidate == null) return "target/name";
        if (!Boolean.TRUE.equals(candidate.matches_parent_target)) return "target/name";
        if (!Boolean.TRUE.equals(candidate.can_attach_to_parent)) return "attach";
        if (!Boolean.TRUE.equals(candidate.inside_area)) return "bounds/area";
        if (Boolean.TRUE.equals(candidate.footprint_collision)) return "footprint_collision";
        if (Boolean.TRUE.equals(candidate.terrain_rejected)) return "terrain";
        return Boolean.TRUE.equals(candidate.viable) ? "viable" : "attach";
    }

    private static List<Integer> candidateRotations(CityC35CatalogIO.CatalogStructure meta) {
        LinkedHashSet<Integer> rotations = new LinkedHashSet<>();
        if (meta != null && meta.constraints != null && meta.constraints.allowed_rotations != null && !meta.constraints.allowed_rotations.isEmpty()) {
            for (Integer rotation : meta.constraints.allowed_rotations) {
                if (rotation != null) rotations.add(normalizeRotation(rotation));
            }
        }
        if (rotations.isEmpty() && meta != null && meta.orientation != null && meta.orientation.rotations != null && !meta.orientation.rotations.isEmpty()) {
            for (Integer rotation : meta.orientation.rotations) {
                if (rotation != null) rotations.add(normalizeRotation(rotation));
            }
        }
        if (rotations.isEmpty()) {
            rotations.add(0);
            rotations.add(90);
            rotations.add(180);
            rotations.add(270);
        }
        return new ArrayList<>(rotations);
    }

    private static int normalizeRotation(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return normalized - (normalized % 90);
    }

    private static BlockPos candidateOrigin(BlockPos startPos, StructureTemplate.StructureBlockInfo childInfo) {
        if (startPos == null || childInfo == null) return BlockPos.ZERO;
        return startPos.offset(-childInfo.pos().getX(), -childInfo.pos().getY(), -childInfo.pos().getZ());
    }

    private static BoundingBox candidateBounds(
            StructureTemplateManager templateManager,
            String templateId,
            BlockPos origin,
            Rotation rotation
    ) {
        if (templateManager == null || templateId == null || templateId.isBlank() || origin == null) return null;
        Optional<StructureTemplate> templateOp = templateManager.get(new ResourceLocation(templateId));
        if (templateOp.isEmpty()) return null;
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setMirror(Mirror.NONE)
                .setRotation(rotation != null ? rotation : Rotation.NONE);
        return templateOp.get().getBoundingBox(settings, origin);
    }

    private static boolean intersectsExisting(List<CityC8Stages.PlacementNode> existingPlacements, BoundingBox bounds) {
        if (bounds == null || existingPlacements == null || existingPlacements.isEmpty()) return false;
        for (CityC8Stages.PlacementNode existing : existingPlacements) {
            if (existing == null
                    || existing.footprint_min_x == null || existing.footprint_min_z == null
                    || existing.footprint_max_x == null || existing.footprint_max_z == null) {
                continue;
            }
            boolean separated = bounds.maxX() < existing.footprint_min_x
                    || bounds.minX() > existing.footprint_max_x
                    || bounds.maxZ() < existing.footprint_min_z
                    || bounds.minZ() > existing.footprint_max_z;
            if (!separated) return true;
        }
        return false;
    }

    private static boolean terrainRejected(
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC35CatalogIO.CatalogStructure childMeta,
            BlockPos origin,
            Rotation rotation,
            BoundingBox bounds
    ) {
        if (heightData == null || childMeta == null || bounds == null) return false;
        if (isTemplateTerrainProbeHardCheckDisabled()) return false;
        List<ProbeWorldPoint> probes = resolveProbePoints(childMeta, origin, rotation, bounds);
        if (probes.isEmpty()) return false;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (ProbeWorldPoint probe : probes) {
            int h = CityHeightResolver.resolveHeight(heightData, c2ScanData, probe.x, probe.z);
            min = Math.min(min, h);
            max = Math.max(max, h);
            if (childMeta.constraints != null && childMeta.constraints.avoid_water && !isLand(c2ScanData, probe.x, probe.z)) {
                return true;
            }
        }
        if (childMeta.constraints != null && childMeta.constraints.max_height_delta > 0) {
            if ((max - min) > childMeta.constraints.max_height_delta) return true;
        }
        if (childMeta.constraints != null && childMeta.constraints.max_slope > 0) {
            double span = Math.max(1.0, Math.hypot(
                    Math.max(1, bounds.maxX() - bounds.minX() + 1),
                    Math.max(1, bounds.maxZ() - bounds.minZ() + 1)
            ));
            double approxSlope = (max - min) / span;
            if (approxSlope > childMeta.constraints.max_slope) return true;
        }
        return false;
    }

    private static List<ProbeWorldPoint> resolveProbePoints(
            CityC35CatalogIO.CatalogStructure childMeta,
            BlockPos origin,
            Rotation rotation,
            BoundingBox bounds
    ) {
        List<ProbeWorldPoint> out = new ArrayList<>();
        if (childMeta != null
                && childMeta.placement != null
                && childMeta.placement.terrain_probe_points != null
                && !childMeta.placement.terrain_probe_points.isEmpty()) {
            CityC35CatalogIO.Vec3i originOffset = childMeta.placement.origin_offset != null
                    ? childMeta.placement.origin_offset
                    : new CityC35CatalogIO.Vec3i();
            for (CityC35CatalogIO.ProbePoint probe : childMeta.placement.terrain_probe_points) {
                if (probe == null) continue;
                int[] rotated = rotateLocal(probe.x - originOffset.x, probe.z - originOffset.z, rotation);
                out.add(new ProbeWorldPoint(origin.getX() + rotated[0], origin.getZ() + rotated[1]));
            }
            return out;
        }
        // TEMP-C35-TERRAIN-PROBE-HARD-CHECK-DISABLED:
        // Generic bounds-corner fallback probing is intentionally disabled for now.
        // Current city building relies on foundation / terrain adaptation to own
        // ground fitting, so templates without explicit probe points should not be
        // hard-rejected here by a coarse generic sampler.
        return out;
    }

    private static boolean isTemplateTerrainProbeHardCheckDisabled() {
        // TEMP-C35-TERRAIN-PROBE-HARD-CHECK-DISABLED:
        // Template terrain probe checks are temporarily bypassed in the solver.
        // We already model foundation / terrain adaptation above this layer, so
        // probe-derived max_height_delta / max_slope / avoid_water should not
        // hard-reject candidate child placements at this stage.
        return true;
    }

    private static boolean isLand(CityC2ScanBinaryIO.C2ScanData data, int worldX, int worldZ) {
        if (data == null || data.map == null || data.map.length == 0 || data.map[0] == null) return true;
        int step = Math.max(1, data.step);
        int ix = (int) Math.round((worldX - data.originX) / (double) step);
        int iz = (int) Math.round((worldZ - data.originZ) / (double) step);
        if (ix < 0 || iz < 0 || ix >= data.map.length || iz >= data.map[0].length) return true;
        return data.map[ix][iz] == null || data.map[ix][iz].isLand();
    }

    private static StructureTemplate.StructureBlockInfo findAttachedChildConnector(
            StructureTemplateManager templateManager,
            String childTemplateId,
            SolveArtifact artifact,
            ParentConnectorContext parentContext
    ) {
        if (templateManager == null || artifact == null || artifact.piece == null || parentContext == null || parentContext.blockInfo == null) return null;
        List<StructureTemplate.StructureBlockInfo> childJigsaws = jigsawBlocks(
                templateManager,
                childTemplateId,
                artifact.piece.getPosition(),
                artifact.piece.getRotation()
        );
        for (StructureTemplate.StructureBlockInfo childInfo : childJigsaws) {
            if (childInfo != null && JigsawBlock.canAttach(parentContext.blockInfo, childInfo)) {
                return childInfo;
            }
        }
        return null;
    }

    private static boolean insideArea(CityC8Stages.AreaGeometry geometry, BoundingBox bounds) {
        if (geometry == null || bounds == null) return false;
        return CityC8Stages.containsFootprint(geometry, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
    }

    private static CityC8Stages.PlacementNode toPlacementNode(
            CityC8Stages.PlacementNode parentPlacement,
            CityC35CatalogIO.CatalogStructure childMeta,
            SolveArtifact artifact,
            ParentConnectorContext parentContext,
            String childConnectorId,
            StructureTemplate.StructureBlockInfo childConnectorInfo,
            int buildOrder
    ) {
        CityC8Stages.PlacementNode placement = new CityC8Stages.PlacementNode();
        placement.node_id = buildNodeId(parentPlacement, childConnectorId);
        placement.template_id = childMeta.structure_id;
        placement.role = childMeta.piece_role;
        placement.x = artifact.piece.getPosition().getX();
        placement.y = artifact.piece.getPosition().getY();
        placement.z = artifact.piece.getPosition().getZ();
        placement.rotation = toDegrees(artifact.piece.getRotation());
        placement.level = parentPlacement != null ? parentPlacement.level + 1 : 1;
        placement.parent_node_id = parentPlacement != null ? parentPlacement.node_id : null;
        placement.placement_reason = "vanilla_jigsaw_adapter";
        placement.build_order = buildOrder;
        placement.incoming_parent_connector_id = parentContext != null ? parentContext.connectorId : null;
        placement.incoming_child_connector_id = childConnectorId;
        if (parentContext != null && parentContext.blockInfo != null) {
            placement.incoming_parent_connector_x = parentContext.blockInfo.pos().getX();
            placement.incoming_parent_connector_z = parentContext.blockInfo.pos().getZ();
            placement.incoming_connector_dir = JigsawBlock.getFrontFacing(parentContext.blockInfo.state()).getSerializedName();
        }
        if (childConnectorInfo != null) {
            placement.incoming_child_connector_x = childConnectorInfo.pos().getX();
            placement.incoming_child_connector_z = childConnectorInfo.pos().getZ();
        }
        placement.footprint_min_x = artifact.bounds.minX();
        placement.footprint_min_z = artifact.bounds.minZ();
        placement.footprint_max_x = artifact.bounds.maxX();
        placement.footprint_max_z = artifact.bounds.maxZ();
        placement.outgoing_connector_ids.addAll(resolveRemainingConnectorIds(childMeta, artifact, childConnectorId));
        return placement;
    }

    private static List<String> resolveRemainingConnectorIds(
            CityC35CatalogIO.CatalogStructure childMeta,
            SolveArtifact artifact,
            String attachedConnectorId
    ) {
        List<String> out = new ArrayList<>();
        if (childMeta == null || childMeta.connectors == null) return out;
        for (CityC35CatalogIO.ConnectorSpec connector : childMeta.connectors) {
            if (connector == null || connector.id == null || connector.id.isBlank()) continue;
            if (Objects.equals(connector.id, attachedConnectorId)) continue;
            out.add(connector.id);
        }
        return out;
    }

    private static String mapConnectorId(
            CityC35CatalogIO.CatalogStructure meta,
            BlockPos origin,
            Rotation rotation,
            StructureTemplate.StructureBlockInfo info
    ) {
        if (meta == null || origin == null || info == null || meta.connectors == null) return null;
        CityC35CatalogIO.Vec3i originOffset = meta.placement != null && meta.placement.origin_offset != null
                ? meta.placement.origin_offset
                : new CityC35CatalogIO.Vec3i();
        Direction actualFacing = JigsawBlock.getFrontFacing(info.state());
        for (CityC35CatalogIO.ConnectorSpec connector : meta.connectors) {
            if (connector == null) continue;
            int[] rotated = rotateLocal(connector.local_pos.x - originOffset.x, connector.local_pos.z - originOffset.z, rotation);
            int worldX = origin.getX() + rotated[0];
            int worldY = origin.getY() + (connector.local_pos.y - originOffset.y);
            int worldZ = origin.getZ() + rotated[1];
            Direction worldFacing = rotateFacing(parseDirection(connector.facing), rotation);
            if (worldX == info.pos().getX() && worldY == info.pos().getY() && worldZ == info.pos().getZ() && worldFacing == actualFacing) {
                return connector.id;
            }
        }
        return null;
    }

    private static RuntimeConnectorCandidate runtimeConnectorCandidate(
            BlockPos origin,
            Rotation rotation,
            StructureTemplate.StructureBlockInfo info
    ) {
        if (origin == null || info == null) return null;
        RuntimeConnectorCandidate candidate = new RuntimeConnectorCandidate();
        int deltaX = info.pos().getX() - origin.getX();
        int deltaZ = info.pos().getZ() - origin.getZ();
        int[] local = unrotateLocal(deltaX, deltaZ, rotation);
        Direction actualFacing = JigsawBlock.getFrontFacing(info.state());
        candidate.id = runtimeConnectorId(actualFacing, local[0], info.pos().getY() - origin.getY(), local[1]);
        candidate.local_x = local[0];
        candidate.local_y = info.pos().getY() - origin.getY();
        candidate.local_z = local[1];
        candidate.world_x = info.pos().getX();
        candidate.world_y = info.pos().getY();
        candidate.world_z = info.pos().getZ();
        candidate.front = actualFacing != null ? actualFacing.getSerializedName() : null;
        candidate.name = info.nbt() != null ? info.nbt().getString("name") : null;
        candidate.target = info.nbt() != null ? info.nbt().getString("target") : null;
        return candidate;
    }

    private static int nextBuildOrder(List<CityC8Stages.PlacementNode> placements) {
        int next = 0;
        if (placements == null) return next;
        for (CityC8Stages.PlacementNode placement : placements) {
            if (placement == null || placement.build_order == null) continue;
            next = Math.max(next, placement.build_order + 1);
        }
        return next;
    }

    private static String buildNodeId(CityC8Stages.PlacementNode parentPlacement, String childConnectorId) {
        String parent = parentPlacement != null && parentPlacement.node_id != null ? parentPlacement.node_id : "parent";
        String connector = childConnectorId != null ? childConnectorId : "child";
        return sanitizeNodeId("vjigsaw_" + parent + "_" + connector);
    }

    private static String sanitizeNodeId(String raw) {
        return raw == null ? "vjigsaw_child" : raw.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static long stableSeed(long worldSeed, String cityId, String parentNodeId, String parentConnectorId, String selectedTemplateId) {
        String raw = safe(cityId) + "|" + safe(parentNodeId) + "|" + safe(parentConnectorId) + "|" + safe(selectedTemplateId);
        long hash = 1125899906842597L;
        for (int i = 0; i < raw.length(); i++) {
            hash = 31L * hash + raw.charAt(i);
        }
        return worldSeed ^ hash;
    }

    private static WorldgenRandom randomFromSeed(long seed, ChunkPos chunkPos) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        int x = chunkPos != null ? chunkPos.x : 0;
        int z = chunkPos != null ? chunkPos.z : 0;
        random.setLargeFeatureSeed(seed, x, z);
        return random;
    }

    static boolean isSyntheticConnectorId(String connectorId) {
        return connectorId != null && connectorId.toLowerCase(Locale.ROOT).startsWith("next_");
    }

    static String runtimeConnectorId(Direction front, int localX, int localY, int localZ) {
        String frontName = front != null ? front.getSerializedName() : "unknown";
        return "jigsaw_" + frontName + "_" + localX + "_" + localY + "_" + localZ;
    }

    private static void applyExpectedParentConnectorDebug(
            SolveResult result,
            CityC8Stages.PlacementNode parentPlacement,
            CityC35CatalogIO.CatalogStructure parentMeta,
            CityC35CatalogIO.ConnectorSpec parentConnector,
            Rotation parentRotation
    ) {
        if (result == null || result.debug == null || parentPlacement == null || parentMeta == null || parentConnector == null) return;
        CityC35CatalogIO.Vec3i originOffset = parentMeta.placement != null && parentMeta.placement.origin_offset != null
                ? parentMeta.placement.origin_offset
                : new CityC35CatalogIO.Vec3i();
        int[] rotated = rotateLocal(parentConnector.local_pos.x - originOffset.x, parentConnector.local_pos.z - originOffset.z, parentRotation);
        result.debug.expected_parent_connector_x = parentPlacement.x + rotated[0];
        result.debug.expected_parent_connector_y = parentPlacement.y + (parentConnector.local_pos.y - originOffset.y);
        result.debug.expected_parent_connector_z = parentPlacement.z + rotated[1];
        Direction expectedFacing = rotateFacing(parseDirection(parentConnector.facing), parentRotation);
        result.debug.expected_parent_connector_front = expectedFacing != null ? expectedFacing.getSerializedName() : null;
    }

    private static void applyResolvedParentConnectorDebug(
            SolveResult result,
            ParentConnectorContext parentContext
    ) {
        if (result == null || result.debug == null || parentContext == null) return;
        result.debug.parent_connector_source = parentContext.source();
        if (parentContext.blockInfo() != null) {
            result.debug.resolved_parent_connector_x = parentContext.blockInfo().pos().getX();
            result.debug.resolved_parent_connector_y = parentContext.blockInfo().pos().getY();
            result.debug.resolved_parent_connector_z = parentContext.blockInfo().pos().getZ();
            Direction front = JigsawBlock.getFrontFacing(parentContext.blockInfo().state());
            result.debug.resolved_parent_connector_front = front != null ? front.getSerializedName() : null;
        }
    }

    private static Direction parseDirection(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    private static Direction rotateFacing(Direction direction, Rotation rotation) {
        if (direction == null || rotation == null) return direction;
        return switch (rotation) {
            case CLOCKWISE_90 -> direction.getClockWise();
            case CLOCKWISE_180 -> direction.getOpposite();
            case COUNTERCLOCKWISE_90 -> direction.getCounterClockWise();
            default -> direction;
        };
    }

    private static int[] rotateLocal(int localX, int localZ, Rotation rotation) {
        if (rotation == null) return new int[]{localX, localZ};
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{-localZ, localX};
            case CLOCKWISE_180 -> new int[]{-localX, -localZ};
            case COUNTERCLOCKWISE_90 -> new int[]{localZ, -localX};
            default -> new int[]{localX, localZ};
        };
    }

    static int[] unrotateLocal(int rotatedX, int rotatedZ, Rotation rotation) {
        if (rotation == null) return new int[]{rotatedX, rotatedZ};
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{rotatedZ, -rotatedX};
            case CLOCKWISE_180 -> new int[]{-rotatedX, -rotatedZ};
            case COUNTERCLOCKWISE_90 -> new int[]{-rotatedZ, rotatedX};
            default -> new int[]{rotatedX, rotatedZ};
        };
    }

    private static Rotation toRotation(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return switch (normalized) {
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static int toDegrees(Rotation rotation) {
        if (rotation == null) return 0;
        return switch (rotation) {
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
            default -> 0;
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record ParentConnectorContext(
            String connectorId,
            StructureTemplate.StructureBlockInfo blockInfo,
            BlockPos startPos,
            ResourceLocation target,
            String source
    ) {}

    private static final class RuntimeParentConnector {
        final RuntimeConnectorCandidate candidate;
        final StructureTemplate.StructureBlockInfo blockInfo;
        final BlockPos startPos;
        final ResourceLocation target;

        private RuntimeParentConnector(
                RuntimeConnectorCandidate candidate,
                StructureTemplate.StructureBlockInfo blockInfo,
                BlockPos startPos,
                ResourceLocation target
        ) {
            this.candidate = candidate;
            this.blockInfo = blockInfo;
            this.startPos = startPos;
            this.target = target;
        }
    }

    private record SolveArtifact(
            PoolElementStructurePiece piece,
            BoundingBox bounds,
            BlockPos startPos
    ) {}

    private record VanillaGenerationAttempt(
            boolean stubGenerated,
            SolveArtifact artifact,
            VanillaGenerationDiagnostics diagnostics
    ) {}

    private static final class VanillaGenerationDiagnostics {
        Integer piece_count;
        Integer pool_element_piece_count;
        Integer selected_piece_index;
        String extraction_stage;
        String summary_zh;
        final List<String> piece_types = new ArrayList<>();
        final List<VanillaPieceDebugItem> piece_items = new ArrayList<>();
    }

    private record ProbeWorldPoint(
            int x,
            int z
    ) {}

    public static final class VanillaPlacementDescriptor {
        public transient BlockPos start_pos;
        public transient StructureInjector.PlacementBounds bounds;
        public transient PiecePlacer piece_placer;

        private static VanillaPlacementDescriptor fromArtifact(SolveArtifact artifact) {
            VanillaPlacementDescriptor descriptor = new VanillaPlacementDescriptor();
            if (artifact == null) return descriptor;
            descriptor.start_pos = artifact.startPos;
            descriptor.bounds = artifact.bounds != null
                    ? StructureInjector.PlacementBounds.of(
                    artifact.bounds.minX(),
                    artifact.bounds.minY(),
                    artifact.bounds.minZ(),
                    artifact.bounds.maxX() + 1,
                    artifact.bounds.maxY() + 1,
                    artifact.bounds.maxZ() + 1
            )
                    : null;
            descriptor.piece_placer = (world, startPos, keepJigsaws) -> {
                if (artifact.piece == null || world == null || world.level() == null) return false;
                BoundingBox bounds = BoundingBox.infinite();
                artifact.piece.place(
                        world.level(),
                        world.level().structureManager(),
                        world.level().getChunkSource().getGenerator(),
                        world.level().getRandom(),
                        bounds,
                        startPos,
                        keepJigsaws
                );
                return true;
            };
            return descriptor;
        }
    }

    @FunctionalInterface
    public interface PiecePlacer {
        boolean place(com.user.terra_script.world.city.execution.BuildWorldAccess world, BlockPos startPos, boolean keepJigsaws);
    }

    public static final class SolveResult {
        public boolean ok;
        public String selected_template_id;
        public String selected_connector_dir;
        public Integer selected_rotation;
        public Integer resolved_origin_x;
        public Integer resolved_origin_z;
        public Integer resolved_rotation;
        public String incoming_parent_connector_id;
        public String incoming_child_connector_id;
        public ResolvedBounds resolved_bounds;
        public String reject_reason;
        public CityC8Stages.PlacementNode placement;
        public List<String> warnings = new ArrayList<>();
        public List<RuntimeConnectorCandidate> runtime_parent_connectors = new ArrayList<>();
        public DebugDetails debug;
        public transient VanillaPlacementDescriptor descriptor;
    }

    public static final class DebugDetails {
        public String requested_parent_connector_id;
        public String selected_template_id;
        public String selected_connector_dir;
        public Integer selected_rotation;
        public String parent_connector_source;
        public String parent_target;
        public Integer start_pos_x;
        public Integer start_pos_y;
        public Integer start_pos_z;
        public Integer expected_parent_connector_x;
        public Integer expected_parent_connector_y;
        public Integer expected_parent_connector_z;
        public String expected_parent_connector_front;
        public Integer resolved_parent_connector_x;
        public Integer resolved_parent_connector_y;
        public Integer resolved_parent_connector_z;
        public String resolved_parent_connector_front;
        public Long stable_seed;
        public String pool_template_id;
        public Integer generation_depth;
        public Integer generation_max_radius;
        public Boolean vanilla_stub_generated;
        public Boolean piece_generated;
        public Integer vanilla_piece_count;
        public Integer vanilla_pool_element_piece_count;
        public Integer selected_vanilla_piece_index;
        public String vanilla_piece_extraction_stage;
        public String vanilla_piece_debug_summary_zh;
        public List<String> vanilla_piece_types = new ArrayList<>();
        public List<VanillaPieceDebugItem> vanilla_piece_items = new ArrayList<>();
        public List<ManualChildConnectorCandidate> manual_child_connector_candidates = new ArrayList<>();
        public ManualAttachSummary manual_attach_summary;
        public String first_blocker_stage;
        public Integer generated_origin_x;
        public Integer generated_origin_y;
        public Integer generated_origin_z;
        public ResolvedBounds generated_bounds;
        public String child_connector_source;
    }

    public static final class RuntimeConnectorCandidate {
        public String id;
        public Integer local_x;
        public Integer local_y;
        public Integer local_z;
        public Integer world_x;
        public Integer world_y;
        public Integer world_z;
        public String front;
        public String name;
        public String target;
    }

    public static final class VanillaPieceDebugItem {
        public Integer index;
        public String piece_type;
        public Boolean pool_element_piece;
        public Integer origin_x;
        public Integer origin_y;
        public Integer origin_z;
        public Integer rotation;
        public ResolvedBounds bounds;
    }

    public static final class ManualChildConnectorCandidate {
        public Integer rotation;
        public String id;
        public Integer local_x;
        public Integer local_y;
        public Integer local_z;
        public Integer world_x;
        public Integer world_y;
        public Integer world_z;
        public String front;
        public String name;
        public String target;
        public Boolean matches_parent_target;
        public Boolean can_attach_to_parent;
        public Integer candidate_origin_x;
        public Integer candidate_origin_y;
        public Integer candidate_origin_z;
        public ResolvedBounds candidate_bounds;
        public Boolean inside_area;
        public Boolean footprint_collision;
        public Boolean terrain_rejected;
        public String reject_stage;
        public Boolean viable;
    }

    public static final class ManualAttachSummary {
        public String truth_source;
        public Integer total_candidate_count;
        public Integer target_name_match_count;
        public Integer attachable_count;
        public Integer area_pass_count;
        public Integer collision_free_count;
        public Integer terrain_pass_count;
        public Integer viable_count;
        public String first_blocker_stage;
        public String summary_zh;
    }

    public static final class ResolvedBounds {
        public Integer min_x;
        public Integer min_y;
        public Integer min_z;
        public Integer max_x;
        public Integer max_y;
        public Integer max_z;

        private static ResolvedBounds fromBoundingBox(BoundingBox bounds) {
            ResolvedBounds out = new ResolvedBounds();
            if (bounds == null) return out;
            out.min_x = bounds.minX();
            out.min_y = bounds.minY();
            out.min_z = bounds.minZ();
            out.max_x = bounds.maxX();
            out.max_y = bounds.maxY();
            out.max_z = bounds.maxZ();
            return out;
        }
    }
}
