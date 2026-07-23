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

    /** Resolves one placement against its complete footprint without writing any blocks. */
    public PlacementPreflight preflight(PrefabPlacement placement, PlacementWorld world) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(world, "world");
        if (placement.forcedFallbackReason() != null) {
            return PlacementPreflight.fallback(placement.forcedFallbackReason());
        }
        Rotation rotation = rotation(placement.rotationDegrees());
        Set<BlockPos> inspected = new HashSet<>();
        for (PlacementTarget target : targets(placement.templateNbt(), placement.anchor(), rotation,
                !placement.ignoreTemplateAir())) {
            BlockPos pos = target.worldPos();
            if (!inspected.add(pos)) continue;
            try {
                if (!world.ensureCanWrite(pos)) {
                    return PlacementPreflight.hardFailure("CITY_NBT_PREFAB_TARGET_NOT_WRITABLE");
                }
                if (!world.canReplace(target, placement.replacePolicy(), placement.groundPlaneLocalY())) {
                    return PlacementPreflight.fallback("CITY_NBT_PREFAB_REPLACE_POLICY_REJECTED");
                }
            } catch (RuntimeException ex) {
                return PlacementPreflight.hardFailure("CITY_NBT_PREFAB_TARGET_STATE_UNAVAILABLE");
            }
        }
        return PlacementPreflight.materialize();
    }

    /** Freezes owner targets and their original snapshots without writing any blocks. */
    public PreparedBatch prepare(BatchRequest request, PlacementWorld world) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(world, "world");
        List<PreparedPlacement> candidates = request.placements().stream()
                .map(placement -> prepare(placement, request.ownerBounds()))
                .toList();

        List<PreparedPlacement> prepared = new ArrayList<>();
        List<PlacementOutcome> outcomes = new ArrayList<>();
        Map<BlockPos, Object> snapshots = new LinkedHashMap<>();
        for (PreparedPlacement placement : candidates) {
            PlacementDecision frozenDecision = request.placementDecisions()
                    .get(placement.placement().placementKey());
            if (frozenDecision == PlacementDecision.FALLBACK) {
                outcomes.add(PlacementOutcome.skipped(placement.placement().placementKey(),
                        fallbackReason(placement.placement())));
                continue;
            }
            Map<BlockPos, Object> placementSnapshots = new LinkedHashMap<>();
            boolean contentRejected = false;
            for (PlacementTarget target : placement.ownerTargets()) {
                BlockPos pos = target.worldPos();
                if (!world.ensureCanWrite(pos)) {
                    return PreparedBatch.failed(request, prepared, snapshots,
                            outcomes, "CITY_NBT_PREFAB_TARGET_NOT_WRITABLE");
                }
                if (!world.canReplace(target, placement.placement().replacePolicy(),
                        placement.placement().groundPlaneLocalY())) {
                    if (frozenDecision == null
                            && request.contentRejectionPolicy() == ContentRejectionPolicy.SKIP_PLACEMENT) {
                        contentRejected = true;
                        break;
                    }
                    return PreparedBatch.failed(request, prepared, snapshots,
                            outcomes, "CITY_NBT_PREFAB_REPLACE_POLICY_REJECTED");
                }
                if (snapshots.containsKey(pos) || placementSnapshots.containsKey(pos)) continue;
                Object snapshot = world.snapshot(pos);
                if (snapshot == null) {
                    return PreparedBatch.failed(request, prepared, snapshots,
                            outcomes, "CITY_NBT_PREFAB_TARGET_SNAPSHOT_UNAVAILABLE");
                }
                placementSnapshots.put(pos.immutable(), snapshot);
            }
            if (contentRejected) {
                outcomes.add(PlacementOutcome.skipped(placement.placement().placementKey(),
                        "CITY_NBT_PREFAB_REPLACE_POLICY_REJECTED"));
                continue;
            }
            snapshots.putAll(placementSnapshots);
            prepared.add(placement);
            outcomes.add(PlacementOutcome.ready(placement.placement().placementKey()));
        }
        return PreparedBatch.ready(request, prepared, snapshots, outcomes);
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

    private static String fallbackReason(PrefabPlacement placement) {
        return placement.forcedFallbackReason() == null
                ? "CITY_NBT_PREFAB_REPLACE_POLICY_REJECTED" : placement.forcedFallbackReason();
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
        private final List<PlacementOutcome> outcomes;
        private final String reasonCode;

        private PreparedBatch(BatchRequest request,
                              List<PreparedPlacement> placements,
                              Map<BlockPos, Object> snapshots,
                              List<PlacementOutcome> outcomes,
                              String reasonCode) {
            this.request = request;
            this.placements = List.copyOf(placements);
            this.snapshots = Collections.unmodifiableMap(new LinkedHashMap<>(snapshots));
            this.outcomes = List.copyOf(outcomes);
            this.reasonCode = reasonCode;
        }

        private static PreparedBatch ready(BatchRequest request,
                                            List<PreparedPlacement> placements,
                                            Map<BlockPos, Object> snapshots,
                                            List<PlacementOutcome> outcomes) {
            return new PreparedBatch(request, placements, snapshots, outcomes,
                    "CITY_NBT_PREFAB_TARGETS_READY");
        }

        private static PreparedBatch failed(BatchRequest request,
                                             List<PreparedPlacement> placements,
                                             Map<BlockPos, Object> snapshots,
                                             List<PlacementOutcome> outcomes,
                                             String reasonCode) {
            return new PreparedBatch(request, placements, snapshots, outcomes, reasonCode);
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
            return request.placements().size();
        }

        public int readyPlacementCount() {
            return placements.size();
        }

        public int targetCount() {
            return snapshots.size();
        }

        public List<BlockPos> ownerTargetPositions() {
            return List.copyOf(snapshots.keySet());
        }

        public List<PlacementOutcome> outcomes() {
            return outcomes;
        }

        public List<SurfaceFallback> skippedSurfaceFallbacks() {
            return surfaceFallbacks(PlacementStatus.SKIPPED_CONTENT);
        }

        public List<SurfaceFallback> readySurfaceFallbacks() {
            return surfaceFallbacks(PlacementStatus.READY);
        }

        private List<SurfaceFallback> surfaceFallbacks(PlacementStatus status) {
            Set<String> matching = new HashSet<>();
            outcomes.stream().filter(outcome -> outcome.status() == status)
                    .map(PlacementOutcome::placementKey).forEach(matching::add);
            return request.placements().stream()
                    .filter(placement -> matching.contains(placement.placementKey()))
                    .map(PrefabPlacement::surfaceFallback)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    public record BatchRequest(String requestId,
                               BoundingBox ownerBounds,
                               List<PrefabPlacement> placements,
                               ContentRejectionPolicy contentRejectionPolicy,
                               Map<String, PlacementDecision> placementDecisions) {
        public BatchRequest(String requestId, BoundingBox ownerBounds, List<PrefabPlacement> placements) {
            this(requestId, ownerBounds, placements, ContentRejectionPolicy.FAIL_BATCH, Map.of());
        }

        public BatchRequest(String requestId,
                            BoundingBox ownerBounds,
                            List<PrefabPlacement> placements,
                            ContentRejectionPolicy contentRejectionPolicy) {
            this(requestId, ownerBounds, placements, contentRejectionPolicy, Map.of());
        }

        public BatchRequest {
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalArgumentException("CITY_NBT_PREFAB_REQUEST_ID_REQUIRED");
            }
            Objects.requireNonNull(ownerBounds, "ownerBounds");
            ownerBounds = new BoundingBox(ownerBounds.minX(), ownerBounds.minY(), ownerBounds.minZ(),
                    ownerBounds.maxX(), ownerBounds.maxY(), ownerBounds.maxZ());
            placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
            Objects.requireNonNull(contentRejectionPolicy, "contentRejectionPolicy");
            placementDecisions = Map.copyOf(Objects.requireNonNull(
                    placementDecisions, "placementDecisions"));
            Set<String> placementKeys = new HashSet<>();
            placements.forEach(placement -> placementKeys.add(placement.placementKey()));
            if (!placementKeys.containsAll(placementDecisions.keySet())) {
                throw new IllegalArgumentException("CITY_NBT_PREFAB_DECISION_PLACEMENT_UNKNOWN");
            }
        }

        @Override
        public BoundingBox ownerBounds() {
            return new BoundingBox(ownerBounds.minX(), ownerBounds.minY(), ownerBounds.minZ(),
                    ownerBounds.maxX(), ownerBounds.maxY(), ownerBounds.maxZ());
        }

        public BatchRequest withPlacementDecisions(Map<String, PlacementDecision> decisions) {
            return new BatchRequest(requestId, ownerBounds, placements, contentRejectionPolicy, decisions);
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
                                  int groundPlaneLocalY,
                                  SurfaceFallback surfaceFallback,
                                  String forcedFallbackReason) {
        public PrefabPlacement(String placementKey,
                               String contentRef,
                               String contentHash,
                               CompoundTag templateNbt,
                               BlockPos anchor,
                               int rotationDegrees,
                               boolean ignoreTemplateAir,
                               String replacePolicy,
                               int groundPlaneLocalY,
                               SurfaceFallback surfaceFallback) {
            this(placementKey, contentRef, contentHash, templateNbt, anchor, rotationDegrees,
                    ignoreTemplateAir, replacePolicy, groundPlaneLocalY, surfaceFallback, null);
        }

        public PrefabPlacement(String placementKey,
                               String contentRef,
                               String contentHash,
                               CompoundTag templateNbt,
                               BlockPos anchor,
                               int rotationDegrees,
                               boolean ignoreTemplateAir,
                               String replacePolicy,
                               int groundPlaneLocalY) {
            this(placementKey, contentRef, contentHash, templateNbt, anchor, rotationDegrees,
                    ignoreTemplateAir, replacePolicy, groundPlaneLocalY, null, null);
        }

        public PrefabPlacement(String placementKey,
                               String contentRef,
                               String contentHash,
                               CompoundTag templateNbt,
                               BlockPos anchor,
                               int rotationDegrees,
                               boolean ignoreTemplateAir) {
            this(placementKey, contentRef, contentHash, templateNbt, anchor, rotationDegrees,
                    ignoreTemplateAir, LEGACY_REPLACE_ANY, 0, null, null);
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
            if (forcedFallbackReason != null && forcedFallbackReason.isBlank()) {
                throw new IllegalArgumentException("CITY_NBT_PREFAB_FORCED_FALLBACK_REASON_INVALID");
            }
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

    public enum ContentRejectionPolicy {
        FAIL_BATCH,
        SKIP_PLACEMENT
    }

    public enum PlacementStatus {
        READY,
        SKIPPED_CONTENT
    }

    public enum PlacementDecision {
        MATERIALIZE,
        FALLBACK
    }

    public enum PreflightStatus {
        MATERIALIZE,
        FALLBACK,
        HARD_FAILURE
    }

    public record PlacementPreflight(PreflightStatus status, String reasonCode) {
        public PlacementPreflight {
            Objects.requireNonNull(status, "status");
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("CITY_NBT_PREFAB_PREFLIGHT_REASON_REQUIRED");
            }
        }

        private static PlacementPreflight materialize() {
            return new PlacementPreflight(PreflightStatus.MATERIALIZE,
                    "CITY_NBT_PREFAB_FULL_FOOTPRINT_READY");
        }

        private static PlacementPreflight fallback(String reasonCode) {
            return new PlacementPreflight(PreflightStatus.FALLBACK, reasonCode);
        }

        private static PlacementPreflight hardFailure(String reasonCode) {
            return new PlacementPreflight(PreflightStatus.HARD_FAILURE, reasonCode);
        }
    }

    public record PlacementOutcome(String placementKey, PlacementStatus status, String reasonCode) {
        public PlacementOutcome {
            if (placementKey == null || placementKey.isBlank()) {
                throw new IllegalArgumentException("CITY_NBT_PREFAB_PLACEMENT_KEY_REQUIRED");
            }
            Objects.requireNonNull(status, "status");
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("CITY_NBT_PREFAB_OUTCOME_REASON_REQUIRED");
            }
        }

        private static PlacementOutcome ready(String placementKey) {
            return new PlacementOutcome(placementKey, PlacementStatus.READY,
                    "CITY_NBT_PREFAB_PLACEMENT_READY");
        }

        private static PlacementOutcome skipped(String placementKey, String reasonCode) {
            return new PlacementOutcome(placementKey, PlacementStatus.SKIPPED_CONTENT, reasonCode);
        }
    }

    public record SurfaceFallback(String placementKey,
                                  String areaId,
                                  String blockId,
                                  int surfaceOffset,
                                  boolean requireReplaceableTarget,
                                  List<SurfaceFallbackCell> cells,
                                  List<SurfaceFallbackCell> footprintCells) {
        public SurfaceFallback(String placementKey,
                               String areaId,
                               String blockId,
                               int surfaceOffset,
                               boolean requireReplaceableTarget,
                               List<SurfaceFallbackCell> cells) {
            this(placementKey, areaId, blockId, surfaceOffset, requireReplaceableTarget,
                    cells, cells);
        }

        public SurfaceFallback {
            if (placementKey == null || placementKey.isBlank()
                    || areaId == null || areaId.isBlank()
                    || blockId == null || blockId.isBlank()) {
                throw new IllegalArgumentException("CITY_NBT_PREFAB_SURFACE_FALLBACK_INVALID");
            }
            cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
            footprintCells = List.copyOf(Objects.requireNonNull(footprintCells, "footprintCells"));
        }
    }

    public record SurfaceFallbackCell(int x, int z) {
    }

    private record PreparedPlacement(PrefabPlacement placement, Rotation rotation,
                                     List<PlacementTarget> ownerTargets) {
    }
}
