package com.rinsing.geomantia.systems.city.infrastructure.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Places concrete NBT prefabs as one owner-bounded, rollback-capable batch. */
public final class CityNbtPrefabBatchPlacer {
    public static final String LEGACY_REPLACE_ANY = "legacy_replace_any";

    public BatchResult place(BatchRequest request, PlacementWorld world) {
        return place(prepare(request, world), world);
    }

    /** Freezes owner targets and their original snapshots without writing any blocks. */
    public PreparedBatch prepare(BatchRequest request, PlacementWorld world) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(world, "world");
        List<PreparedPlacement> prepared = request.placements().stream()
                .map(placement -> prepare(placement, request.ownerBounds()))
                .toList();

        Map<BlockPos, Object> snapshots = new LinkedHashMap<>();
        for (PreparedPlacement placement : prepared) {
            for (PlacementTarget target : placement.ownerTargets()) {
                BlockPos pos = target.worldPos();
                if (!world.ensureCanWrite(pos)) {
                    return PreparedBatch.failed(request, prepared, snapshots,
                            "CITY_NBT_PREFAB_TARGET_NOT_WRITABLE");
                }
                if (!world.canReplace(target, placement.placement().replacePolicy(),
                        placement.placement().groundPlaneLocalY())) {
                    return PreparedBatch.failed(request, prepared, snapshots,
                            "CITY_NBT_PREFAB_REPLACE_POLICY_REJECTED");
                }
                if (snapshots.containsKey(pos)) continue;
                Object snapshot = world.snapshot(pos);
                if (snapshot == null) {
                    return PreparedBatch.failed(request, prepared, snapshots,
                            "CITY_NBT_PREFAB_TARGET_SNAPSHOT_UNAVAILABLE");
                }
                snapshots.put(pos.immutable(), snapshot);
            }
        }
        return PreparedBatch.ready(request, prepared, snapshots);
    }

    /** Applies a batch whose owner targets were already checked and snapshotted. */
    public BatchResult place(PreparedBatch batch, PlacementWorld world) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(world, "world");
        if (!batch.ready()) {
            return BatchResult.failed(batch.requestId(), batch.reasonCode(),
                    batch.placementCount(), batch.targetCount(), false, true);
        }

        List<BlockPos> rollbackOrder = new ArrayList<>();
        Set<BlockPos> rollbackSeen = new HashSet<>();
        int appliedPlacements = 0;
        for (PreparedPlacement placement : batch.placements) {
            if (placement.ownerTargets().isEmpty()) continue;
            for (PlacementTarget target : placement.ownerTargets()) {
                if (rollbackSeen.add(target.worldPos())) rollbackOrder.add(target.worldPos());
            }
            boolean placed;
            try {
                placed = world.placeTemplate(placement.placement().templateNbt(),
                        placement.placement().anchor(), placement.rotation(),
                        stableSeed(placement.placement().placementKey()),
                        placement.placement().ignoreTemplateAir(), batch.ownerBounds());
            } catch (RuntimeException ex) {
                boolean rollbackComplete = rollback(world, rollbackOrder, batch.snapshots);
                return BatchResult.failed(batch.requestId(), "CITY_NBT_PREFAB_PLACE_EXCEPTION",
                        batch.placementCount(), batch.targetCount(), true, rollbackComplete);
            }
            if (!placed) {
                boolean rollbackComplete = rollback(world, rollbackOrder, batch.snapshots);
                return BatchResult.failed(batch.requestId(), "CITY_NBT_PREFAB_PLACE_FAILED",
                        batch.placementCount(), batch.targetCount(), true, rollbackComplete);
            }
            appliedPlacements++;
        }
        return BatchResult.applied(batch.requestId(), appliedPlacements, batch.targetCount());
    }

    /** Restores every owner target captured by {@link #prepare(BatchRequest, PlacementWorld)}. */
    public boolean rollback(PreparedBatch batch, PlacementWorld world) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(world, "world");
        return rollback(world, batch.ownerTargetPositions(), batch.snapshots);
    }

    private static PreparedPlacement prepare(PrefabPlacement placement, BoundingBox ownerBounds) {
        Rotation rotation = rotation(placement.rotationDegrees());
        List<PlacementTarget> ownerTargets = targets(placement.templateNbt(), placement.anchor(), rotation,
                !placement.ignoreTemplateAir()).stream()
                .filter(target -> contains(ownerBounds, target.worldPos()))
                .toList();
        return new PreparedPlacement(placement, rotation, ownerTargets);
    }

    static List<PlacementTarget> targets(CompoundTag templateNbt, BlockPos anchor, Rotation rotation,
                                         boolean includeTemplateAir) {
        Objects.requireNonNull(templateNbt, "templateNbt");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(rotation, "rotation");
        ListTag blocks = templateNbt.getList("blocks", 10);
        ListTag palette = templateNbt.getList("palette", 10);
        List<PlacementTarget> targets = new ArrayList<>(blocks.size());
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag block = blocks.getCompound(index);
            int stateIndex = block.getInt("state");
            boolean templateAir = stateIndex >= 0 && stateIndex < palette.size()
                    && "minecraft:air".equals(palette.getCompound(stateIndex).getString("Name"));
            if (templateAir && !includeTemplateAir) continue;
            ListTag pos = block.getList("pos", 3);
            BlockPos local = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
            BlockPos transformed = StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO);
            targets.add(new PlacementTarget(local.getY(), transformed.offset(anchor), templateAir));
        }
        return List.copyOf(targets);
    }

    static Rotation rotation(int degrees) {
        return switch (Math.floorMod(degrees, 360)) {
            case 0 -> Rotation.NONE;
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException("CITY_NBT_PREFAB_ROTATION_UNSUPPORTED: " + degrees);
        };
    }

    static long stableSeed(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            long seed = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                seed = (seed << 8) | (hash[index] & 0xffL);
            }
            return seed;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    private static boolean rollback(PlacementWorld world, List<BlockPos> rollbackOrder,
                                    Map<BlockPos, Object> snapshots) {
        boolean complete = true;
        for (int index = rollbackOrder.size() - 1; index >= 0; index--) {
            BlockPos pos = rollbackOrder.get(index);
            try {
                complete &= world.restore(pos, snapshots.get(pos));
            } catch (RuntimeException ex) {
                complete = false;
            }
        }
        return complete;
    }

    private static boolean contains(BoundingBox bounds, BlockPos pos) {
        return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                && pos.getY() >= bounds.minY() && pos.getY() <= bounds.maxY()
                && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
    }

    public interface PlacementWorld {
        boolean ensureCanWrite(BlockPos pos);

        default boolean canReplace(PlacementTarget target,
                                   String replacePolicy,
                                   int groundPlaneLocalY) {
            return true;
        }

        Object snapshot(BlockPos pos);

        boolean restore(BlockPos pos, Object snapshot);

        boolean placeTemplate(CompoundTag templateNbt, BlockPos anchor, Rotation rotation, long seed,
                              boolean ignoreTemplateAir, BoundingBox ownerBounds);
    }

    public static final class PreparedBatch {
        private final BatchRequest request;
        private final List<PreparedPlacement> placements;
        private final Map<BlockPos, Object> snapshots;
        private final String reasonCode;

        private PreparedBatch(BatchRequest request,
                              List<PreparedPlacement> placements,
                              Map<BlockPos, Object> snapshots,
                              String reasonCode) {
            this.request = request;
            this.placements = List.copyOf(placements);
            this.snapshots = Collections.unmodifiableMap(new LinkedHashMap<>(snapshots));
            this.reasonCode = reasonCode;
        }

        private static PreparedBatch ready(BatchRequest request,
                                           List<PreparedPlacement> placements,
                                           Map<BlockPos, Object> snapshots) {
            return new PreparedBatch(request, placements, snapshots, "CITY_NBT_PREFAB_TARGETS_READY");
        }

        private static PreparedBatch failed(BatchRequest request,
                                            List<PreparedPlacement> placements,
                                            Map<BlockPos, Object> snapshots,
                                            String reasonCode) {
            return new PreparedBatch(request, placements, snapshots, reasonCode);
        }

        public boolean ready() {
            return "CITY_NBT_PREFAB_TARGETS_READY".equals(reasonCode);
        }

        public String reasonCode() {
            return reasonCode;
        }

        public String requestId() {
            return request.requestId();
        }

        public BoundingBox ownerBounds() {
            return request.ownerBounds();
        }

        public int placementCount() {
            return placements.size();
        }

        public int targetCount() {
            return snapshots.size();
        }

        public List<BlockPos> ownerTargetPositions() {
            return List.copyOf(snapshots.keySet());
        }
    }

    public record BatchRequest(String requestId, BoundingBox ownerBounds, List<PrefabPlacement> placements) {
        public BatchRequest {
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalArgumentException("CITY_NBT_PREFAB_REQUEST_ID_REQUIRED");
            }
            Objects.requireNonNull(ownerBounds, "ownerBounds");
            ownerBounds = new BoundingBox(ownerBounds.minX(), ownerBounds.minY(), ownerBounds.minZ(),
                    ownerBounds.maxX(), ownerBounds.maxY(), ownerBounds.maxZ());
            placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
        }

        @Override
        public BoundingBox ownerBounds() {
            return new BoundingBox(ownerBounds.minX(), ownerBounds.minY(), ownerBounds.minZ(),
                    ownerBounds.maxX(), ownerBounds.maxY(), ownerBounds.maxZ());
        }
    }

    public record PrefabPlacement(String placementKey,
                                  String contentRef,
                                  String contentHash,
                                  CompoundTag templateNbt,
                                  BlockPos anchor,
                                  int rotationDegrees,
                                  boolean ignoreTemplateAir,
                                  String replacePolicy,
                                  int groundPlaneLocalY) {
        public PrefabPlacement(String placementKey,
                               String contentRef,
                               String contentHash,
                               CompoundTag templateNbt,
                               BlockPos anchor,
                               int rotationDegrees,
                               boolean ignoreTemplateAir) {
            this(placementKey, contentRef, contentHash, templateNbt, anchor, rotationDegrees,
                    ignoreTemplateAir, LEGACY_REPLACE_ANY, 0);
        }

        public PrefabPlacement {
            if (placementKey == null || placementKey.isBlank()
                    || contentRef == null || contentRef.isBlank()
                    || contentHash == null || contentHash.isBlank()
                    || replacePolicy == null || replacePolicy.isBlank()) {
                throw new IllegalArgumentException("CITY_NBT_PREFAB_IDENTITY_REQUIRED");
            }
            if (!LEGACY_REPLACE_ANY.equals(replacePolicy)
                    && !"replaceable_only".equals(replacePolicy)
                    && !"surface_replaceable".equals(replacePolicy)) {
                throw new IllegalArgumentException("CITY_NBT_PREFAB_REPLACE_POLICY_UNSUPPORTED:"
                        + replacePolicy);
            }
            if (groundPlaneLocalY < 0) {
                throw new IllegalArgumentException("CITY_NBT_PREFAB_GROUND_PLANE_INVALID");
            }
            templateNbt = Objects.requireNonNull(templateNbt, "templateNbt").copy();
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
            rotation(rotationDegrees);
        }

        @Override
        public CompoundTag templateNbt() {
            return templateNbt.copy();
        }
    }

    public record BatchResult(boolean applied,
                              String reasonCode,
                              String requestId,
                              int placementCount,
                              int targetCount,
                              boolean rollbackAttempted,
                              boolean rollbackComplete) {
        private static BatchResult applied(String requestId, int placements, int targets) {
            return new BatchResult(true, "CITY_NBT_PREFAB_BATCH_APPLIED", requestId,
                    placements, targets, false, true);
        }

        private static BatchResult failed(String requestId, String reasonCode, int placements, int targets,
                                          boolean rollbackAttempted, boolean rollbackComplete) {
            return new BatchResult(false, reasonCode, requestId, placements, targets,
                    rollbackAttempted, rollbackComplete);
        }
    }

    public record PlacementTarget(int localY, BlockPos worldPos, boolean templateAir) {
        public PlacementTarget {
            worldPos = Objects.requireNonNull(worldPos, "worldPos").immutable();
        }
    }

    private record PreparedPlacement(PrefabPlacement placement, Rotation rotation,
                                     List<PlacementTarget> ownerTargets) {
    }
}
