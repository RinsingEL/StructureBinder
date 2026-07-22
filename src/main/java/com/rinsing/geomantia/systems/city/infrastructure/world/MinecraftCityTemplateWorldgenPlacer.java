package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places a fixed City template directly through StructureTemplate.
 *
 * <p>This adapter intentionally has no dependency on configured structures. The writer receives
 * only the fragment belonging to its owner chunk; a later worldgen callback may handle another
 * owner chunk with the same plan.</p>
 */
public final class MinecraftCityTemplateWorldgenPlacer {
    public static final String MATERIALIZATION_SOURCE = "structure_template_nbt";

    private final TemplateSource source;
    private final Set<String> placedKeys = ConcurrentHashMap.newKeySet();

    public MinecraftCityTemplateWorldgenPlacer(TemplateSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public MinecraftCityTemplateWorldgenPlacer(MinecraftCityTemplateReader reader) {
        this(Objects.requireNonNull(reader, "reader")::readSnapshot);
    }

    public MinecraftCityTemplateWorldgenPlacer(StructureTemplateManager templateManager) {
        this(new MinecraftCityTemplateReader(Objects.requireNonNull(templateManager, "templateManager"))
                ::readSnapshot);
    }

    /** Places only the fragment for {@link WorldWriter#ownerChunk()}. */
    public PlacementResult place(PlacementRequest request, WorldWriter writer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(writer, "writer");

        if (!request.ownerChunk().equals(writer.ownerChunk())) {
            return PlacementResult.waiting(request.placementKey(),
                    "The writer is not the owner chunk for this placement.", null, null);
        }

        ResourceLocation templateRef = ResourceLocation.tryParse(request.templateRef());
        if (templateRef == null) {
            return PlacementResult.failed(request.placementKey(), "TEMPLATE_REF_INVALID",
                    "templateRef is not a valid resource location", null, null);
        }

        MinecraftCityTemplateReader.TemplateSnapshot snapshot;
        try {
            Optional<MinecraftCityTemplateReader.TemplateSnapshot> loaded = source.load(templateRef);
            if (loaded == null || loaded.isEmpty()) {
                return PlacementResult.failed(request.placementKey(), "TEMPLATE_NOT_FOUND",
                        "Template source did not provide " + templateRef, null, null);
            }
            snapshot = loaded.get();
        } catch (Exception ex) {
            return PlacementResult.failed(request.placementKey(), "TEMPLATE_READ_FAILED",
                    message(ex), null, null);
        }

        if (snapshot == null || snapshot.size() == null || snapshot.contentHash() == null
                || snapshot.contentHash().isBlank()) {
            return PlacementResult.failed(request.placementKey(), "TEMPLATE_METADATA_INVALID",
                    "Template source returned incomplete metadata", null, null);
        }
        Vec3i size = snapshot.template().map(StructureTemplate::getSize).orElse(snapshot.size());
        if (size == null || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return PlacementResult.failed(request.placementKey(), "TEMPLATE_SIZE_NON_POSITIVE",
                    "StructureTemplate.getSize() must be positive on every axis", null, null);
        }
        if (!request.templateHash().equals(snapshot.contentHash())) {
            return PlacementResult.failed(request.placementKey(), "TEMPLATE_HASH_MISMATCH",
                    "Expected template hash " + request.templateHash() + " but found "
                            + snapshot.contentHash(), null, null);
        }

        CityTemplatePlacementGeometry.Size sourceSize = new CityTemplatePlacementGeometry.Size(
                size.getX(), size.getY(), size.getZ());
        CityTemplatePlacementGeometry geometry = CityTemplatePlacementGeometry.of(
                sourceSize,
                request.rotation(), request.mirror(), List.of());
        BlockBounds footprint = geometry.worldBounds(request.anchor());
        if (request.lockedFootprint() != null && !request.lockedFootprint().equals(footprint)) {
            return PlacementResult.failed(request.placementKey(), "TEMPLATE_LOCKED_FOOTPRINT_MISMATCH",
                    "D6 locked footprint " + request.lockedFootprint()
                            + " differs from template identity footprint " + footprint + ".",
                    footprint, null);
        }
        RuntimeTransform runtimeTransform = deriveRuntimeTransform(
                sourceSize, request.anchor(), request.datum(), request.rotation(), request.mirror());
        RuntimeTransformValidation transformValidation = runtimeTransform.validateAgainst(geometry);
        if (!transformValidation.valid()) {
            return PlacementResult.failed(request.placementKey(), transformValidation.reasonCode(),
                    transformValidation.message(), footprint, null);
        }
        BlockBounds ownerBounds = chunkBounds(writer.ownerChunk());
        BlockBounds fragmentBounds = intersection(footprint, ownerBounds);
        if (fragmentBounds == null) {
            return PlacementResult.waiting(request.placementKey(),
                    "The template does not intersect this owner chunk.", footprint, null);
        }

        if (placedKeys.contains(request.placementKey()) || writer.isPlaced(request.placementKey())) {
            return PlacementResult.alreadyPlaced(request.placementKey(), footprint, fragmentBounds);
        }

        TemplateFragment fragment = new TemplateFragment(request.placementKey(), request.templateRef(),
                request.templateHash(), snapshot.template(), request.anchor(), request.datum(),
                request.rotation(), request.mirror(), request.ownerChunk(), geometry, footprint,
                fragmentBounds, runtimeTransform, MATERIALIZATION_SOURCE);
        WriteReport report = writer.write(fragment);
        if (report == null) {
            return PlacementResult.failed(request.placementKey(), "TEMPLATE_CHUNK_WRITE_FAILED",
                    "WorldWriter returned no report", footprint, fragmentBounds);
        }
        if (report.status() == WriteStatus.ALREADY_PLACED) {
            placedKeys.add(request.placementKey());
            return PlacementResult.alreadyPlaced(request.placementKey(), footprint, fragmentBounds);
        }
        if (report.status() != WriteStatus.WRITTEN) {
            return PlacementResult.failed(request.placementKey(), report.reasonCode(),
                    report.detail(), footprint, fragmentBounds);
        }
        placedKeys.add(request.placementKey());

        if (spansMultipleChunks(footprint)) {
            return PlacementResult.waitingAfterWrite(request.placementKey(),
                    "Owner chunk fragment written; remaining template chunks wait for their owner callbacks.",
                    footprint, fragmentBounds);
        }
        return PlacementResult.placed(request.placementKey(), footprint, fragmentBounds);
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static BlockBounds chunkBounds(ChunkPos chunk) {
        return new BlockBounds(chunk.getMinBlockX(), chunk.getMinBlockZ(),
                chunk.getMaxBlockX(), chunk.getMaxBlockZ());
    }

    private static BlockBounds intersection(BlockBounds first, BlockBounds second) {
        int minX = Math.max(first.minX(), second.minX());
        int minZ = Math.max(first.minZ(), second.minZ());
        int maxX = Math.min(first.maxX(), second.maxX());
        int maxZ = Math.min(first.maxZ(), second.maxZ());
        return minX <= maxX && minZ <= maxZ ? new BlockBounds(minX, minZ, maxX, maxZ) : null;
    }

    private static boolean spansMultipleChunks(BlockBounds bounds) {
        return Math.floorDiv(bounds.minX(), 16) != Math.floorDiv(bounds.maxX(), 16)
                || Math.floorDiv(bounds.minZ(), 16) != Math.floorDiv(bounds.maxZ(), 16);
    }

    public static RuntimeTransform deriveRuntimeTransform(CityTemplatePlacementGeometry.Size sourceSize,
                                                          BlockPoint anchor,
                                                          int datum,
                                                          CityTemplatePlacementGeometry.Rotation rotation,
                                                          CityTemplatePlacementGeometry.Mirror mirror) {
        Objects.requireNonNull(sourceSize, "sourceSize");
        Objects.requireNonNull(anchor, "anchor");
        Mirror minecraftMirror = minecraftMirror(Objects.requireNonNull(mirror, "mirror"));
        Rotation minecraftRotation = minecraftRotation(Objects.requireNonNull(rotation, "rotation"));
        BlockPos anchorOrigin = new BlockPos(anchor.x(), datum, anchor.z());
        BlockPos placementOrigin = StructureTemplate.getZeroPositionWithTransform(
                anchorOrigin, minecraftMirror, minecraftRotation, sourceSize.width(), sourceSize.depth());
        BlockPos pivot = BlockPos.ZERO;

        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int x : new int[]{0, sourceSize.width() - 1}) {
            for (int z : new int[]{0, sourceSize.depth() - 1}) {
                BlockPos transformed = StructureTemplate.transform(
                        new BlockPos(x, 0, z), minecraftMirror, minecraftRotation, pivot)
                        .offset(placementOrigin);
                minX = Math.min(minX, transformed.getX());
                minZ = Math.min(minZ, transformed.getZ());
                maxX = Math.max(maxX, transformed.getX());
                maxZ = Math.max(maxZ, transformed.getZ());
            }
        }
        return new RuntimeTransform(sourceSize, anchor, datum, rotation, mirror, minecraftMirror,
                minecraftRotation, placementOrigin, pivot, new BlockBounds(minX, minZ, maxX, maxZ));
    }

    private static Mirror minecraftMirror(CityTemplatePlacementGeometry.Mirror mirror) {
        return switch (mirror) {
            case NONE -> Mirror.NONE;
            case LEFT_RIGHT -> Mirror.LEFT_RIGHT;
            case FRONT_BACK -> Mirror.FRONT_BACK;
        };
    }

    private static Rotation minecraftRotation(CityTemplatePlacementGeometry.Rotation rotation) {
        return switch (rotation) {
            case NONE -> Rotation.NONE;
            case CLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            case CLOCKWISE_180 -> Rotation.CLOCKWISE_180;
            case COUNTERCLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    @FunctionalInterface
    public interface TemplateSource extends MinecraftCityTemplateReader.TemplateSource {
    }

    public interface WorldWriter {
        ChunkPos ownerChunk();

        default boolean isPlaced(String placementKey) {
            return false;
        }

        WriteReport write(TemplateFragment fragment);
    }

    public record PlacementRequest(String templateRef, String templateHash, BlockPoint anchor,
                                   CityTemplatePlacementGeometry.Rotation rotation,
                                   CityTemplatePlacementGeometry.Mirror mirror, int datum,
                                   ChunkPos ownerChunk, BlockBounds lockedFootprint) {
        public PlacementRequest(String templateRef, String templateHash, BlockPoint anchor,
                                CityTemplatePlacementGeometry.Rotation rotation,
                                CityTemplatePlacementGeometry.Mirror mirror, int datum,
                                ChunkPos ownerChunk) {
            this(templateRef, templateHash, anchor, rotation, mirror, datum, ownerChunk, null);
        }

        public PlacementRequest {
            templateRef = requireText(templateRef, "templateRef");
            templateHash = requireText(templateHash, "templateHash");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(mirror, "mirror");
            Objects.requireNonNull(ownerChunk, "ownerChunk");
        }

        public String placementKey() {
            return templateRef + "|" + templateHash + "|" + anchor.x() + ":" + anchor.z()
                    + "|" + rotation + "|" + mirror + "|" + datum + "|"
                    + ownerChunk.x + ":" + ownerChunk.z;
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank.");
            }
            return value.trim();
        }
    }

    public record TemplateFragment(String placementKey, String templateRef, String templateHash,
                                   Optional<StructureTemplate> template, BlockPoint anchor, int datum,
                                   CityTemplatePlacementGeometry.Rotation rotation,
                                   CityTemplatePlacementGeometry.Mirror mirror, ChunkPos ownerChunk,
                                   CityTemplatePlacementGeometry geometry, BlockBounds templateFootprint,
                                   BlockBounds ownerFragment, RuntimeTransform runtimeTransform,
                                   String materializationSource) {
        public TemplateFragment {
            template = template == null ? Optional.empty() : template;
            Objects.requireNonNull(placementKey, "placementKey");
            Objects.requireNonNull(templateRef, "templateRef");
            Objects.requireNonNull(templateHash, "templateHash");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(mirror, "mirror");
            Objects.requireNonNull(ownerChunk, "ownerChunk");
            Objects.requireNonNull(geometry, "geometry");
            Objects.requireNonNull(templateFootprint, "templateFootprint");
            Objects.requireNonNull(ownerFragment, "ownerFragment");
            Objects.requireNonNull(runtimeTransform, "runtimeTransform");
            Objects.requireNonNull(materializationSource, "materializationSource");
        }

        public BlockPos origin() {
            return new BlockPos(anchor.x(), datum, anchor.z());
        }

        public int height() {
            return geometry.transformedSize().height();
        }
    }

    public enum WriteStatus {
        WRITTEN,
        ALREADY_PLACED,
        FAILED
    }

    public record WriteReport(WriteStatus status, String reasonCode, String detail) {
        public WriteReport {
            Objects.requireNonNull(status, "status");
            reasonCode = reasonCode == null || reasonCode.isBlank()
                    ? "TEMPLATE_CHUNK_WRITE_FAILED" : reasonCode;
            detail = detail == null ? "" : detail;
        }

        public static WriteReport written() {
            return new WriteReport(WriteStatus.WRITTEN, "TEMPLATE_FRAGMENT_WRITTEN", "");
        }

        public static WriteReport alreadyPlaced() {
            return new WriteReport(WriteStatus.ALREADY_PLACED, "TEMPLATE_ALREADY_PLACED", "");
        }

        public static WriteReport failed(String detail) {
            return failed("TEMPLATE_CHUNK_WRITE_FAILED", detail);
        }

        public static WriteReport failed(String reasonCode, String detail) {
            return new WriteReport(WriteStatus.FAILED, reasonCode, detail);
        }
    }

    public record RuntimeTransform(CityTemplatePlacementGeometry.Size sourceSize,
                                   BlockPoint anchor,
                                   int datum,
                                   CityTemplatePlacementGeometry.Rotation rotation,
                                   CityTemplatePlacementGeometry.Mirror mirror,
                                   Mirror minecraftMirror,
                                   Rotation minecraftRotation,
                                   BlockPos placementOrigin,
                                   BlockPos rotationPivot,
                                   BlockBounds transformedFootprint) {
        public RuntimeTransform {
            Objects.requireNonNull(sourceSize, "sourceSize");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(mirror, "mirror");
            Objects.requireNonNull(minecraftMirror, "minecraftMirror");
            Objects.requireNonNull(minecraftRotation, "minecraftRotation");
            Objects.requireNonNull(placementOrigin, "placementOrigin");
            Objects.requireNonNull(rotationPivot, "rotationPivot");
            Objects.requireNonNull(transformedFootprint, "transformedFootprint");
        }

        public BlockPos worldPosition(BlockPoint localPosition) {
            Objects.requireNonNull(localPosition, "localPosition");
            if (localPosition.x() < 0 || localPosition.x() >= sourceSize.width()
                    || localPosition.z() < 0 || localPosition.z() >= sourceSize.depth()) {
                throw new IllegalArgumentException("Local point is outside the source template dimensions.");
            }
            return StructureTemplate.transform(new BlockPos(localPosition.x(), 0, localPosition.z()),
                    minecraftMirror, minecraftRotation, rotationPivot).offset(placementOrigin);
        }

        public RuntimeTransformValidation validateAgainst(CityTemplatePlacementGeometry geometry) {
            Objects.requireNonNull(geometry, "geometry");
            BlockBounds plannedFootprint = geometry.worldBounds(anchor);
            if (!plannedFootprint.equals(transformedFootprint)) {
                return RuntimeTransformValidation.invalid("TEMPLATE_RUNTIME_TRANSFORM_MISMATCH",
                        "Minecraft runtime footprint " + transformedFootprint
                                + " differs from City planned footprint " + plannedFootprint + ".");
            }
            Set<Long> occupied = new HashSet<>(sourceSize.width() * sourceSize.depth());
            for (int x = 0; x < sourceSize.width(); x++) {
                for (int z = 0; z < sourceSize.depth(); z++) {
                    BlockPoint local = new BlockPoint(x, z);
                    BlockPoint planned = geometry.worldPosition(anchor, local);
                    BlockPos runtime = worldPosition(local);
                    if (planned.x() != runtime.getX() || planned.z() != runtime.getZ()) {
                        return RuntimeTransformValidation.invalid("TEMPLATE_RUNTIME_TRANSFORM_MISMATCH",
                                "Local point " + local + " maps to City " + planned
                                        + " but Minecraft runtime " + runtime.getX() + "," + runtime.getZ() + ".");
                    }
                    occupied.add(ChunkPos.asLong(runtime.getX(), runtime.getZ()));
                }
            }
            if (occupied.size() != sourceSize.width() * sourceSize.depth()) {
                return RuntimeTransformValidation.invalid("TEMPLATE_RUNTIME_TRANSFORM_MISMATCH",
                        "Minecraft runtime transform maps multiple source coordinates to one world coordinate.");
            }
            return RuntimeTransformValidation.success();
        }
    }

    public record RuntimeTransformValidation(boolean valid, String reasonCode, String message) {
        private static RuntimeTransformValidation success() {
            return new RuntimeTransformValidation(true, "TEMPLATE_RUNTIME_TRANSFORM_VALID", "");
        }

        private static RuntimeTransformValidation invalid(String reasonCode, String message) {
            return new RuntimeTransformValidation(false, reasonCode, message);
        }
    }

    public record PlacementResult(boolean success, boolean waiting, boolean worldMutationApplied,
                                  String reasonCode, String message, String placementKey,
                                  BlockBounds templateFootprint, BlockBounds ownerFragment,
                                  String materializationSource) {
        private static PlacementResult placed(String key, BlockBounds footprint, BlockBounds fragment) {
            return new PlacementResult(true, false, true, "TEMPLATE_PLACED",
                    "Template fragment written.", key, footprint, fragment, MATERIALIZATION_SOURCE);
        }

        private static PlacementResult waiting(String key, String message, BlockBounds footprint,
                                              BlockBounds fragment) {
            return new PlacementResult(false, true, false, "TEMPLATE_CHUNK_WRITE_WAITING",
                    message, key, footprint, fragment, MATERIALIZATION_SOURCE);
        }

        private static PlacementResult waitingAfterWrite(String key, String message,
                                                         BlockBounds footprint, BlockBounds fragment) {
            return new PlacementResult(false, true, true, "TEMPLATE_CHUNK_WRITE_WAITING",
                    message, key, footprint, fragment, MATERIALIZATION_SOURCE);
        }

        private static PlacementResult alreadyPlaced(String key, BlockBounds footprint,
                                                      BlockBounds fragment) {
            return new PlacementResult(true, false, false, "TEMPLATE_ALREADY_PLACED",
                    "Placement key was already materialized.", key, footprint, fragment,
                    MATERIALIZATION_SOURCE);
        }

        private static PlacementResult failed(String key, String reasonCode, String message,
                                              BlockBounds footprint, BlockBounds fragment) {
            return new PlacementResult(false, false, false, reasonCode, message, key,
                    footprint, fragment, MATERIALIZATION_SOURCE);
        }
    }

    /** Forge/Minecraft writer for a worldgen callback; tests can inject a pure fake writer instead. */
    public static final class WorldGenLevelWriter implements WorldWriter {
        private final WorldGenLevel level;
        private final ChunkPos ownerChunk;
        private final Set<String> placedKeys = ConcurrentHashMap.newKeySet();

        public WorldGenLevelWriter(WorldGenLevel level, ChunkPos ownerChunk) {
            this.level = Objects.requireNonNull(level, "level");
            this.ownerChunk = Objects.requireNonNull(ownerChunk, "ownerChunk");
        }

        public WorldGenLevelWriter(WorldGenRegion region) {
            this(region, region.getCenter());
        }

        @Override
        public ChunkPos ownerChunk() {
            return ownerChunk;
        }

        @Override
        public boolean isPlaced(String placementKey) {
            return placedKeys.contains(placementKey);
        }

        @Override
        public WriteReport write(TemplateFragment fragment) {
            if (!placedKeys.add(fragment.placementKey())) {
                return WriteReport.alreadyPlaced();
            }
            StructureTemplate template = fragment.template().orElse(null);
            if (template == null) {
                placedKeys.remove(fragment.placementKey());
                return WriteReport.failed("Runtime template value is unavailable.");
            }

            RuntimeTransform runtimeTransform = fragment.runtimeTransform();
            RuntimeTransformValidation validation = runtimeTransform.validateAgainst(fragment.geometry());
            BlockBounds runtimeOwnerFragment = intersection(
                    runtimeTransform.transformedFootprint(), chunkBounds(fragment.ownerChunk()));
            if (!validation.valid() || !fragment.templateFootprint().equals(runtimeTransform.transformedFootprint())
                    || !fragment.ownerFragment().equals(runtimeOwnerFragment)) {
                placedKeys.remove(fragment.placementKey());
                return WriteReport.failed("TEMPLATE_RUNTIME_TRANSFORM_MISMATCH",
                        validation.valid() ? "Runtime owner fragment differs from planned owner fragment."
                                : validation.message());
            }
            BoundingBox ownerBox = new BoundingBox(fragment.ownerFragment().minX(), fragment.datum(),
                    fragment.ownerFragment().minZ(), fragment.ownerFragment().maxX(),
                    fragment.datum() + fragment.height() - 1, fragment.ownerFragment().maxZ());
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setMirror(runtimeTransform.minecraftMirror())
                    .setRotation(runtimeTransform.minecraftRotation())
                    .setRotationPivot(runtimeTransform.rotationPivot())
                    .setBoundingBox(ownerBox)
                    .setIgnoreEntities(true)
                    .setKeepLiquids(false);
            boolean written = template.placeInWorld(level, runtimeTransform.placementOrigin(),
                    runtimeTransform.placementOrigin(), settings,
                    RandomSource.create(stableSeed(fragment.placementKey())), 2);
            if (!written) {
                placedKeys.remove(fragment.placementKey());
                return WriteReport.failed("StructureTemplate.placeInWorld returned false.");
            }
            CityWorldgenBlockObservationRegistry.watchStructureTemplate(template, fragment.templateHash(),
                    runtimeTransform.placementOrigin(), runtimeTransform.minecraftMirror(),
                    runtimeTransform.minecraftRotation(), runtimeTransform.rotationPivot(), ownerBox,
                    level::getBlockState, "city_structure_template");
            return WriteReport.written();
        }

        private static long stableSeed(String key) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(key.getBytes(StandardCharsets.UTF_8));
                long seed = 0L;
                for (int index = 0; index < Long.BYTES; index++) {
                    seed = (seed << 8) | (digest[index] & 0xffL);
                }
                return seed;
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
            }
        }
    }
}
