package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class BoundedJigsawTemplateInspector {
    private BoundedJigsawTemplateInspector() {
    }

    static PieceInspection inspect(StructureTemplateManager manager,
                                   StructurePoolElement element,
                                   String poolId,
                                   BlockPos anchor,
                                   Rotation rotation,
                                   BoundingBox box,
                                   int pieceIndex,
                                   long seed) {
        JsonObject piece = new JsonObject();
        piece.addProperty("pieceId", "start_piece_" + pieceIndex);
        piece.addProperty("poolId", poolId == null || poolId.isBlank() ? "unknown" : poolId);
        piece.addProperty("elementType", element == null ? "unknown" : element.getType().toString());
        piece.addProperty("element", String.valueOf(element));
        piece.add("anchorBlock", blockPosJson(anchor));
        piece.addProperty("rotation", (rotation == null ? Rotation.NONE : rotation).name());
        JsonArray warnings = new JsonArray();
        List<ResourceLocation> templateIds = templateIds(element);
        if (templateIds.isEmpty()) {
            warnings.add("template_id_unresolved");
            piece.addProperty("templateId", "unresolved");
        } else {
            piece.addProperty("templateId", templateIds.get(0).toString());
            JsonArray ids = new JsonArray();
            templateIds.forEach(id -> ids.add(id.toString()));
            piece.add("templateIds", ids);
        }
        JsonArray connectors = connectors(manager, element, anchor, rotation, seed, warnings);
        piece.add("connectorRefs", connectors);
        piece.addProperty("connectorCount", connectors.size());
        if (box != null) {
            piece.add("templateFootprint", boundsJson(box));
        }
        if (!warnings.isEmpty()) {
            piece.add("inspectionWarnings", warnings);
        }
        return new PieceInspection(piece, connectors.size(), warnings);
    }

    static boolean canInspectTemplate(StructurePoolElement element) {
        return element != null && !(element instanceof EmptyPoolElement) && !templateIds(element).isEmpty();
    }

    private static JsonArray connectors(StructureTemplateManager manager,
                                        StructurePoolElement element,
                                        BlockPos anchor,
                                        Rotation rotation,
                                        long seed,
                                        JsonArray warnings) {
        JsonArray out = new JsonArray();
        if (manager == null || element == null || anchor == null) {
            warnings.add("connector_scan_missing_context");
            return out;
        }
        List<StructureTemplate.StructureBlockInfo> blocks;
        try {
            blocks = element.getShuffledJigsawBlocks(manager, anchor, rotation == null ? Rotation.NONE : rotation,
                    RandomSource.create(seed));
        } catch (RuntimeException ex) {
            warnings.add("connector_scan_failed:" + ex.getClass().getSimpleName());
            return out;
        }
        return connectorsFromBlocks(blocks, anchor);
    }

    static JsonArray connectorsFromBlocks(List<StructureTemplate.StructureBlockInfo> blocks, BlockPos anchor) {
        JsonArray out = new JsonArray();
        if (blocks == null || anchor == null) {
            return out;
        }
        int index = 0;
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            if (info == null || info.nbt() == null) {
                continue;
            }
            Direction front = JigsawBlock.getFrontFacing(info.state());
            out.add(connectorFromNbt(front, info.pos(), anchor, info.nbt(), index++));
        }
        return out;
    }

    static JsonObject connectorFromNbt(Direction front, BlockPos worldPos, BlockPos anchor, CompoundTag nbt, int index) {
        Direction actualFront = front == null ? Direction.NORTH : front;
        JsonObject connector = new JsonObject();
        connector.addProperty("connectorId", connectorId(actualFront, worldPos, anchor));
        connector.add("worldBlock", blockPosJson(worldPos));
        connector.add("localBlock", blockPosJson(worldPos.subtract(anchor)));
        connector.addProperty("front", actualFront.getSerializedName());
        connector.addProperty("name", nbt == null ? "" : nbt.getString("name"));
        connector.addProperty("target", nbt == null ? "" : nbt.getString("target"));
        connector.addProperty("pool", nbt == null ? "" : nbt.getString("pool"));
        connector.addProperty("finalState", nbt == null ? "" : nbt.getString("final_state"));
        connector.addProperty("joint", nbt == null ? "" : nbt.getString("joint"));
        connector.addProperty("index", index);
        return connector;
    }

    private static String connectorId(Direction front, BlockPos worldPos, BlockPos anchor) {
        BlockPos local = worldPos.subtract(anchor);
        return "jigsaw_" + front.getSerializedName() + "_" + local.getX() + "_" + local.getY() + "_" + local.getZ();
    }

    static List<ResourceLocation> templateIds(Object element) {
        List<ResourceLocation> ids = new ArrayList<>();
        collectTemplateIds(element, ids);
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static void collectTemplateIds(Object element, List<ResourceLocation> ids) {
        if (element == null) {
            return;
        }
        try {
            Field templateField = findField(element.getClass(), "template");
            if (templateField != null) {
                Object value = templateField.get(element);
                if (value instanceof Either<?, ?> either) {
                    Optional<?> left = either.left();
                    if (left.isPresent() && left.get() instanceof ResourceLocation location) {
                        ids.add(location);
                    }
                }
            }
            Field childrenField = findField(element.getClass(), "elements");
            if (childrenField != null) {
                Object value = childrenField.get(element);
                if (value instanceof List<?> list) {
                    for (Object child : list) {
                        collectTemplateIds(child, ids);
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws ReflectiveOperationException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static JsonObject blockPosJson(BlockPos pos) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", pos == null ? 0 : pos.getX());
        obj.addProperty("y", pos == null ? 0 : pos.getY());
        obj.addProperty("z", pos == null ? 0 : pos.getZ());
        return obj;
    }

    private static JsonObject boundsJson(BoundingBox box) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", box.minX());
        obj.addProperty("minY", box.minY());
        obj.addProperty("minZ", box.minZ());
        obj.addProperty("maxX", box.maxX());
        obj.addProperty("maxY", box.maxY());
        obj.addProperty("maxZ", box.maxZ());
        return obj;
    }

    record PieceInspection(JsonObject pieceJson, int connectorCount, JsonArray warnings) {
    }
}
