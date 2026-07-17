package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Optional;

/** One fixed template piece. Its persisted bounding box is the Beardifier footprint. */
public final class CityTemplateTerrainStructurePiece extends StructurePiece {
    private static final String TAG_TEMPLATE_REF = "TemplateRef";
    private static final String TAG_TEMPLATE_HASH = "TemplateHash";
    private static final String TAG_ANCHOR_ID = "AnchorId";
    private static final String TAG_ANCHOR_X = "AnchorX";
    private static final String TAG_ANCHOR_Z = "AnchorZ";
    private static final String TAG_DATUM_Y = "DatumY";
    private static final String TAG_ROTATION = "Rotation";
    private static final String TAG_MIRROR = "Mirror";
    private static final String TAG_WIDTH = "TemplateWidth";
    private static final String TAG_HEIGHT = "TemplateHeight";
    private static final String TAG_DEPTH = "TemplateDepth";

    private final ResourceLocation templateRef;
    private final String templateHash;
    private final String anchorId;
    private final BlockPoint anchor;
    private final int datumY;
    private final CityTemplatePlacementGeometry.Rotation cityRotation;
    private final CityTemplatePlacementGeometry.Mirror cityMirror;
    private final CityTemplatePlacementGeometry.Size sourceSize;

    public CityTemplateTerrainStructurePiece(ResourceLocation templateRef,
                                             String templateHash,
                                             String anchorId,
                                             BlockPoint anchor,
                                             int datumY,
                                             CityTemplatePlacementGeometry.Rotation cityRotation,
                                             CityTemplatePlacementGeometry.Mirror cityMirror,
                                             CityTemplatePlacementGeometry.Size sourceSize) {
        super(CityTemplateTerrainStructureRegistries.CITY_TEMPLATE_TERRAIN_PIECE.get(), 0,
                boundingBox(anchor, datumY, cityRotation, cityMirror, sourceSize));
        this.templateRef = requireTemplateRef(templateRef);
        this.templateHash = requireText(templateHash, TAG_TEMPLATE_HASH);
        this.anchorId = requireText(anchorId, TAG_ANCHOR_ID);
        this.anchor = anchor;
        this.datumY = datumY;
        this.cityRotation = cityRotation;
        this.cityMirror = cityMirror;
        this.sourceSize = sourceSize;
    }

    public CityTemplateTerrainStructurePiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(CityTemplateTerrainStructureRegistries.CITY_TEMPLATE_TERRAIN_PIECE.get(), tag);
        this.templateRef = requireTemplateRef(ResourceLocation.tryParse(tag.getString(TAG_TEMPLATE_REF)));
        this.templateHash = requireText(tag.getString(TAG_TEMPLATE_HASH), TAG_TEMPLATE_HASH);
        this.anchorId = requireText(tag.getString(TAG_ANCHOR_ID), TAG_ANCHOR_ID);
        this.anchor = new BlockPoint(tag.getInt(TAG_ANCHOR_X), tag.getInt(TAG_ANCHOR_Z));
        this.datumY = tag.getInt(TAG_DATUM_Y);
        this.cityRotation = enumValue(CityTemplatePlacementGeometry.Rotation.class, tag.getString(TAG_ROTATION));
        this.cityMirror = enumValue(CityTemplatePlacementGeometry.Mirror.class, tag.getString(TAG_MIRROR));
        this.sourceSize = new CityTemplatePlacementGeometry.Size(tag.getInt(TAG_WIDTH), tag.getInt(TAG_HEIGHT),
                tag.getInt(TAG_DEPTH));
        BoundingBox expected = boundingBox(anchor, datumY, cityRotation, cityMirror, sourceSize);
        if (!expected.equals(this.boundingBox)) {
            throw new IllegalArgumentException("CITY_TEMPLATE_TERRAIN_PIECE_BOUNDS_DRIFT");
        }
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putString(TAG_TEMPLATE_REF, templateRef.toString());
        tag.putString(TAG_TEMPLATE_HASH, templateHash);
        tag.putString(TAG_ANCHOR_ID, anchorId);
        tag.putInt(TAG_ANCHOR_X, anchor.x());
        tag.putInt(TAG_ANCHOR_Z, anchor.z());
        tag.putInt(TAG_DATUM_Y, datumY);
        tag.putString(TAG_ROTATION, cityRotation.name());
        tag.putString(TAG_MIRROR, cityMirror.name());
        tag.putInt(TAG_WIDTH, sourceSize.width());
        tag.putInt(TAG_HEIGHT, sourceSize.height());
        tag.putInt(TAG_DEPTH, sourceSize.depth());
    }

    @Override
    public void postProcess(WorldGenLevel level,
                            StructureManager structureManager,
                            ChunkGenerator generator,
                            RandomSource random,
                            BoundingBox chunkBox,
                            ChunkPos chunkPos,
                            BlockPos pivot) {
        Optional<CityReservationMaskRegistry.PlannedStructure> planned =
                CityReservationMaskRegistry.findTemplatePlacement(anchorId, templateRef.toString(), templateHash);
        if (planned.isEmpty()) {
            return;
        }
        CityReservationMaskRegistry.PlannedStructure item = planned.get();
        BlockBounds footprint = footprint();
        if (!item.lockedActualFootprint().equals(footprint)) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "TEMPLATE_TERRAIN_START_FOOTPRINT_MISMATCH",
                    "Persisted template piece footprint differs from D6 locked footprint.");
            return;
        }
        try {
            StructureTemplateManager manager = level.getLevel().getStructureManager();
            MinecraftCityTemplateReader.ReadResult read = new MinecraftCityTemplateReader(manager).read(templateRef);
            if (!read.success() || read.template().isEmpty()) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                        read.failureCode().name(), read.failureDetail());
                return;
            }
            if (!templateHash.equals(read.contentHash()) || !matches(read.size(), sourceSize)) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                        "TEMPLATE_TERRAIN_START_TEMPLATE_DRIFT",
                        "Persisted piece identity differs from the current template NBT.");
                return;
            }

            MinecraftCityTemplateWorldgenPlacer.RuntimeTransform transform =
                    MinecraftCityTemplateWorldgenPlacer.deriveRuntimeTransform(
                            sourceSize, anchor, datumY, cityRotation, cityMirror);
            CityTemplatePlacementGeometry geometry = CityTemplatePlacementGeometry.of(
                    sourceSize, cityRotation, cityMirror, java.util.List.of());
            MinecraftCityTemplateWorldgenPlacer.RuntimeTransformValidation validation =
                    transform.validateAgainst(geometry);
            if (!validation.valid() || !transform.transformedFootprint().equals(footprint)) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                        validation.reasonCode(), validation.message());
                return;
            }

            BoundingBox placementBox = new BoundingBox(chunkBox.minX(), datumY, chunkBox.minZ(),
                    chunkBox.maxX(), datumY + sourceSize.height() - 1, chunkBox.maxZ());
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setMirror(transform.minecraftMirror())
                    .setRotation(transform.minecraftRotation())
                    .setRotationPivot(transform.rotationPivot())
                    .setBoundingBox(placementBox)
                    .setIgnoreEntities(true)
                    .setKeepLiquids(false);
            boolean written = read.template().orElseThrow().placeInWorld(level, transform.placementOrigin(),
                    transform.placementOrigin(), settings, random, 2);
            if (!written) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                        "TEMPLATE_TERRAIN_START_WRITE_FAILED",
                        "StructureTemplate.placeInWorld returned false.");
                return;
            }
            CityReservationMaskRegistry.TemplateFragmentRecordResult result =
                    CityReservationMaskRegistry.recordTemplateWorldgenFragment(item, footprint,
                            signature(), pieceBox(), chunkPos, datumY, "beard_thin",
                            "TEMPLATE_TERRAIN_START_PIECE_PLACED",
                            "Fixed template piece placed through StructureStart before biome features.");
            if (!result.recorded()) {
                CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos, result.reasonCode(),
                        "Template piece was written but its ledger fragment was not accepted.");
            }
        } catch (RuntimeException ex) {
            CityReservationMaskRegistry.recordWorldgenFailure(item, chunkPos,
                    "TEMPLATE_TERRAIN_START_PLACEMENT_FAILED", message(ex));
        }
    }

    public BlockBounds footprint() {
        return new BlockBounds(boundingBox.minX(), boundingBox.minZ(), boundingBox.maxX(), boundingBox.maxZ());
    }

    private JsonArray pieceBox() {
        JsonArray boxes = new JsonArray();
        com.google.gson.JsonObject box = new com.google.gson.JsonObject();
        box.addProperty("pieceIndex", 0);
        box.addProperty("type", "geomantia:city_template_terrain_piece");
        box.addProperty("minX", boundingBox.minX());
        box.addProperty("minY", boundingBox.minY());
        box.addProperty("minZ", boundingBox.minZ());
        box.addProperty("maxX", boundingBox.maxX());
        box.addProperty("maxY", boundingBox.maxY());
        box.addProperty("maxZ", boundingBox.maxZ());
        boxes.add(box);
        return boxes;
    }

    private String signature() {
        return "template_start:" + anchorId + "#" + templateHash + "#" + cityRotation + "#" + cityMirror;
    }

    private static BoundingBox boundingBox(BlockPoint anchor,
                                           int datumY,
                                           CityTemplatePlacementGeometry.Rotation rotation,
                                           CityTemplatePlacementGeometry.Mirror mirror,
                                           CityTemplatePlacementGeometry.Size size) {
        CityTemplatePlacementGeometry geometry = CityTemplatePlacementGeometry.of(size, rotation, mirror,
                java.util.List.of());
        BlockBounds footprint = geometry.worldBounds(anchor);
        return new BoundingBox(footprint.minX(), datumY, footprint.minZ(), footprint.maxX(),
                datumY + geometry.transformedSize().height() - 1, footprint.maxZ());
    }

    private static boolean matches(Vec3i size, CityTemplatePlacementGeometry.Size expected) {
        return size != null && size.getX() == expected.width() && size.getY() == expected.height()
                && size.getZ() == expected.depth();
    }

    private static ResourceLocation requireTemplateRef(ResourceLocation value) {
        if (value == null) {
            throw new IllegalArgumentException("CITY_TEMPLATE_TERRAIN_PIECE_TEMPLATE_REF_INVALID");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CITY_TEMPLATE_TERRAIN_PIECE_" + field + "_INVALID");
        }
        return value.trim();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("CITY_TEMPLATE_TERRAIN_PIECE_ENUM_INVALID", ex);
        }
    }

    private static String message(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
