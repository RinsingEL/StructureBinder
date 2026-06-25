package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityStructureD7Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;

final class BoundedJigsawPoolAdapter {
    private BoundedJigsawPoolAdapter() {
    }

    static JsonObject solverInput(CityStructureD7Executor.PlacementRequest request,
                                  Holder<StructureTemplatePool> startPool,
                                  StructureTemplateManager templateManager,
                                  BlockPos anchor,
                                  Rotation rotation,
                                  long seed) {
        JsonObject input = new JsonObject();
        input.addProperty("sourceStructureId", request.structureId());
        input.addProperty("seedKey", request.structureId() + ":" + request.anchorBlock().x() + ":"
                + request.anchorBlock().z() + ":" + request.rotation());
        input.addProperty("startPool", poolName(startPool));
        input.addProperty("targetAreaBlocks", request.targetAreaBlocks());
        input.addProperty("maxPieces", 4);
        input.addProperty("maxDepth", 2);
        input.add("constraintField", request.constraintField() == null ? new JsonObject()
                : request.constraintField().deepCopy());
        input.add("startPieces", inspectPool(startPool, templateManager, anchor, rotation, seed, "start_piece"));
        input.add("candidatePools", new JsonObject());
        return input;
    }

    static JsonArray inspectPool(Holder<StructureTemplatePool> pool,
                                 StructureTemplateManager templateManager,
                                 BlockPos anchor,
                                 Rotation rotation,
                                 long seed,
                                 String piecePrefix) {
        JsonArray pieces = new JsonArray();
        if (pool == null || pool.value() == null) {
            return pieces;
        }
        List<StructurePoolElement> elements = pool.value().getShuffledTemplates(RandomSource.create(seed));
        int pieceIndex = 0;
        for (StructurePoolElement element : elements) {
            if (element instanceof EmptyPoolElement) {
                continue;
            }
            pieceIndex++;
            JsonObject piece = inspectElement(templateManager, element, poolName(pool), anchor, rotation,
                    piecePrefix + "_" + pieceIndex, seed + pieceIndex);
            pieces.add(piece);
        }
        return pieces;
    }

    static JsonObject inspectElement(StructureTemplateManager templateManager,
                                     StructurePoolElement element,
                                     String poolId,
                                     BlockPos anchor,
                                     Rotation rotation,
                                     String pieceId,
                                     long seed) {
        BoundingBox box = null;
        try {
            box = element == null || templateManager == null || anchor == null ? null
                    : element.getBoundingBox(templateManager, anchor, rotation == null ? Rotation.NONE : rotation);
        } catch (RuntimeException ignored) {
        }
        BoundedJigsawTemplateInspector.PieceInspection inspection = BoundedJigsawTemplateInspector.inspect(
                templateManager, element, poolId, anchor, rotation, box, 1, seed);
        JsonObject piece = inspection.pieceJson();
        return normalizePiece(piece, BoundedJigsawTemplateInspector.canInspectTemplate(element),
                inspection.connectorCount(), box, pieceId);
    }

    static JsonObject normalizePiece(JsonObject rawPiece,
                                     boolean templateInspectable,
                                     int connectorCount,
                                     BoundingBox box,
                                     String pieceId) {
        JsonObject piece = rawPiece == null ? new JsonObject() : rawPiece.deepCopy();
        piece.addProperty("pieceId", pieceId == null || pieceId.isBlank() ? "piece_1" : pieceId);
        if (box != null) {
            piece.add("footprint", footprintJson(box));
            piece.addProperty("visibleAreaCost", area(box));
        }
        if (!templateInspectable) {
            piece.addProperty("adapterStatus", "unsupported_pool_element");
            piece.addProperty("adapterReasonCode", "UNSUPPORTED_POOL_ELEMENT");
        } else if (connectorCount == 0) {
            piece.addProperty("adapterStatus", "terminal_piece");
        } else {
            piece.addProperty("adapterStatus", "connector_ready");
        }
        return piece;
    }

    static String poolName(Holder<StructureTemplatePool> pool) {
        return pool == null ? "unknown"
                : pool.unwrapKey().map(key -> key.location().toString()).orElse("inline");
    }

    private static JsonObject footprintJson(BoundingBox box) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", box.minX());
        obj.addProperty("minZ", box.minZ());
        obj.addProperty("maxX", box.maxX());
        obj.addProperty("maxZ", box.maxZ());
        return obj;
    }

    private static int area(BoundingBox box) {
        return (box.maxX() - box.minX() + 1) * (box.maxZ() - box.minZ() + 1);
    }
}
