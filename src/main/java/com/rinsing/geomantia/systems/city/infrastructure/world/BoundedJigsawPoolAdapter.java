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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class BoundedJigsawPoolAdapter {
    private static final String CHILD_POOL_DISCOVERY = "child_pool_discovery";
    private static final String ALIGNMENT_PENDING = "connector_alignment_pending";

    private BoundedJigsawPoolAdapter() {
    }

    static JsonObject solverInput(CityStructureD7Executor.PlacementRequest request,
                                  Holder<StructureTemplatePool> startPool,
                                  StructureTemplateManager templateManager,
                                  BlockPos anchor,
                                  Rotation rotation,
                                  long seed) {
        return solverInput(request, startPool, templateManager, anchor, rotation, seed, poolId -> null);
    }

    static JsonObject solverInput(CityStructureD7Executor.PlacementRequest request,
                                  Holder<StructureTemplatePool> startPool,
                                  StructureTemplateManager templateManager,
                                  BlockPos anchor,
                                  Rotation rotation,
                                  long seed,
                                  PoolLookup poolLookup) {
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
        JsonArray startPieces = inspectPool(startPool, templateManager, anchor, rotation, seed, "start_piece");
        input.add("startPieces", startPieces);
        JsonObject candidatePools = new JsonObject();
        JsonObject report = populateCandidatePools(candidatePools, startPieces, (poolId, depth) -> {
            Holder<StructureTemplatePool> pool = poolLookup == null ? null : poolLookup.find(poolId);
            if (pool == null || pool.value() == null) {
                return null;
            }
            return inspectPoolRotations(pool, templateManager, anchor, seed + depth * 997L,
                    "pool_" + safeId(poolId) + "_piece");
        }, 2, 12);
        input.add("candidatePools", candidatePools);
        input.add("poolAdapterReport", report);
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

    static JsonArray inspectPoolRotations(Holder<StructureTemplatePool> pool,
                                          StructureTemplateManager templateManager,
                                          BlockPos anchor,
                                          long seed,
                                          String piecePrefix) {
        JsonArray pieces = new JsonArray();
        if (pool == null || pool.value() == null) {
            return pieces;
        }
        int rotationIndex = 0;
        for (Rotation rotation : Rotation.values()) {
            JsonArray rotated = inspectPool(pool, templateManager, anchor, rotation,
                    seed + rotationIndex * 131L, piecePrefix + "_" + rotation.name().toLowerCase());
            for (int i = 0; i < rotated.size(); i++) {
                if (rotated.get(i).isJsonObject()) {
                    pieces.add(rotated.get(i).getAsJsonObject());
                }
            }
            rotationIndex++;
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

    static JsonObject populateCandidatePools(JsonObject candidatePools,
                                             JsonArray rootPieces,
                                             CandidatePoolSource poolSource,
                                             int maxDepth,
                                             int maxPools) {
        JsonObject pools = candidatePools == null ? new JsonObject() : candidatePools;
        JsonObject report = adapterReport(maxDepth, maxPools);
        Deque<PoolRequest> queue = new ArrayDeque<>();
        enqueueConnectorPools(queue, rootPieces, 1, "startPieces");
        Set<String> visited = new LinkedHashSet<>();
        int discovered = 0;
        while (!queue.isEmpty()) {
            PoolRequest request = queue.removeFirst();
            if (request.poolId().isBlank() || !visited.add(request.poolId())) {
                continue;
            }
            if (request.depth() > maxDepth) {
                report.getAsJsonArray("warnings").add("max_depth_reached:" + request.poolId());
                continue;
            }
            if (discovered >= maxPools) {
                report.getAsJsonArray("warnings").add("max_pool_count_reached:" + maxPools);
                break;
            }
            JsonArray pieces = poolSource == null ? null : poolSource.pieces(request.poolId(), request.depth());
            if (pieces == null) {
                report.getAsJsonArray("missingPools").add(poolReport(request.poolId(), request.depth(),
                        "BOUNDED_JIGSAW_POOL_MISSING", 0, request.source()));
                continue;
            }
            JsonArray normalized = markChildPoolPrototypes(pieces, request.poolId(), request.depth());
            pools.add(request.poolId(), normalized);
            report.getAsJsonArray("discoveredPools").add(poolReport(request.poolId(), request.depth(),
                    "discovered", normalized.size(), request.source()));
            discovered++;
            enqueueConnectorPools(queue, normalized, request.depth() + 1, request.poolId());
        }
        report.addProperty("discoveredPoolCount", discovered);
        report.addProperty("missingPoolCount", report.getAsJsonArray("missingPools").size());
        return report;
    }

    static String poolName(Holder<StructureTemplatePool> pool) {
        return pool == null ? "unknown"
                : pool.unwrapKey().map(key -> key.location().toString()).orElse("inline");
    }

    private static JsonArray markChildPoolPrototypes(JsonArray pieces, String poolId, int depth) {
        JsonArray normalized = new JsonArray();
        if (pieces == null) {
            return normalized;
        }
        for (int i = 0; i < pieces.size(); i++) {
            if (!pieces.get(i).isJsonObject()) {
                continue;
            }
            JsonObject piece = pieces.get(i).getAsJsonObject().deepCopy();
            piece.addProperty("adapterScope", "child_pool_prototype");
            piece.addProperty("sourcePoolId", poolId);
            piece.addProperty("prototypeDepth", depth);
            piece.addProperty("prototypePlacementStatus", ALIGNMENT_PENDING);
            JsonArray warnings = piece.has("adapterWarnings") && piece.get("adapterWarnings").isJsonArray()
                    ? piece.getAsJsonArray("adapterWarnings")
                    : new JsonArray();
            warnings.add("connector_alignment_pending");
            piece.add("adapterWarnings", warnings);
            normalized.add(piece);
        }
        return normalized;
    }

    private static void enqueueConnectorPools(Deque<PoolRequest> queue, JsonArray pieces, int depth, String source) {
        if (pieces == null) {
            return;
        }
        for (int i = 0; i < pieces.size(); i++) {
            if (!pieces.get(i).isJsonObject()) {
                continue;
            }
            JsonObject piece = pieces.get(i).getAsJsonObject();
            JsonArray connectors = piece.has("connectorRefs") && piece.get("connectorRefs").isJsonArray()
                    ? piece.getAsJsonArray("connectorRefs")
                    : new JsonArray();
            for (int j = 0; j < connectors.size(); j++) {
                if (!connectors.get(j).isJsonObject()) {
                    continue;
                }
                JsonObject connector = connectors.get(j).getAsJsonObject();
                String poolId = stringValue(connector, "pool", "");
                if (!poolId.isBlank()) {
                    queue.addLast(new PoolRequest(poolId, depth, source));
                }
            }
        }
    }

    private static JsonObject adapterReport(int maxDepth, int maxPools) {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", "city_bounded_jigsaw_pool_adapter_report.v0.1");
        report.addProperty("status", CHILD_POOL_DISCOVERY);
        report.addProperty("maxDepth", maxDepth);
        report.addProperty("maxPools", maxPools);
        report.addProperty("discoveredPoolCount", 0);
        report.addProperty("missingPoolCount", 0);
        report.add("discoveredPools", new JsonArray());
        report.add("missingPools", new JsonArray());
        report.add("warnings", new JsonArray());
        return report;
    }

    private static JsonObject poolReport(String poolId, int depth, String status, int pieceCount, String source) {
        JsonObject obj = new JsonObject();
        obj.addProperty("poolId", poolId);
        obj.addProperty("depth", depth);
        obj.addProperty("status", status);
        obj.addProperty("pieceCount", pieceCount);
        obj.addProperty("source", source);
        return obj;
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }

    private static String safeId(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^A-Za-z0-9_]+", "_");
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

    interface PoolLookup {
        Holder<StructureTemplatePool> find(String poolId);
    }

    interface CandidatePoolSource {
        JsonArray pieces(String poolId, int depth);
    }

    private record PoolRequest(String poolId, int depth, String source) {
    }
}
