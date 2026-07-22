package com.rinsing.geomantia.systems.city.infrastructure.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityNbtPrefabBatchPlacerTest {
    @Test
    void transformsTargetsForAllFourRotations() {
        CompoundTag template = template(List.of(
                new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(0, 0, 2)));
        Map<Integer, Set<BlockPos>> expected = Map.of(
                0, Set.of(new BlockPos(10, 64, 10), new BlockPos(11, 64, 10), new BlockPos(10, 64, 12)),
                90, Set.of(new BlockPos(10, 64, 10), new BlockPos(10, 64, 11), new BlockPos(8, 64, 10)),
                180, Set.of(new BlockPos(10, 64, 10), new BlockPos(9, 64, 10), new BlockPos(10, 64, 8)),
                270, Set.of(new BlockPos(10, 64, 10), new BlockPos(10, 64, 9), new BlockPos(12, 64, 10)));

        for (int degrees : List.of(0, 90, 180, 270)) {
            FakeWorld world = new FakeWorld();
            CityNbtPrefabBatchPlacer.BatchResult result = new CityNbtPrefabBatchPlacer().place(
                    request("rotation-" + degrees, owner(-32, 32), placement("p", template,
                            new BlockPos(10, 64, 10), degrees)), world);

            assertTrue(result.applied(), "rotation=" + degrees);
            assertEquals(expected.get(degrees), new HashSet<>(world.writes), "rotation=" + degrees);
            assertEquals(CityNbtPrefabBatchPlacer.rotation(degrees), world.rotations.get(0));
        }
    }

    @Test
    void clipsTheSamePrefabIntoSeparateOwnersAtX15And16() {
        CompoundTag template = template(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)));
        CityNbtPrefabBatchPlacer.PrefabPlacement placement = policyPlacement(
                "cross-owner", template, new BlockPos(15, 64, 8));

        FakeWorld left = new FakeWorld();
        CityNbtPrefabBatchPlacer.BatchResult leftResult = new CityNbtPrefabBatchPlacer().place(
                request("left", new BoundingBox(0, 0, 0, 15, 255, 15), placement), left);
        FakeWorld right = new FakeWorld();
        CityNbtPrefabBatchPlacer.BatchResult rightResult = new CityNbtPrefabBatchPlacer().place(
                request("right", new BoundingBox(16, 0, 0, 31, 255, 15), placement), right);

        assertTrue(leftResult.applied());
        assertEquals(List.of(new BlockPos(15, 64, 8)), left.writes);
        assertEquals(List.of(new BlockPos(15, 64, 8)), left.snapshots);
        assertTrue(rightResult.applied());
        assertEquals(List.of(new BlockPos(16, 64, 8)), right.writes);
        assertEquals(List.of(new BlockPos(16, 64, 8)), right.snapshots);
    }

    @Test
    void rollsBackEveryPotentiallyWrittenPositionInReverseOrder() {
        CompoundTag oneBlock = template(List.of(BlockPos.ZERO));
        FakeWorld world = new FakeWorld();
        BlockPos first = new BlockPos(4, 64, 4);
        BlockPos second = new BlockPos(5, 64, 4);
        world.states.put(first, "original-first");
        world.states.put(second, "original-second");
        world.failOnPlacementCall = 2;
        CityNbtPrefabBatchPlacer.BatchRequest request = new CityNbtPrefabBatchPlacer.BatchRequest(
                "rollback", new BoundingBox(0, 0, 0, 15, 255, 15), List.of(
                placement("first", oneBlock, first, 0),
                placement("second", oneBlock, second, 0)));

        CityNbtPrefabBatchPlacer.BatchResult result = new CityNbtPrefabBatchPlacer().place(request, world);

        assertFalse(result.applied());
        assertEquals("CITY_NBT_PREFAB_PLACE_FAILED", result.reasonCode());
        assertTrue(result.rollbackAttempted());
        assertTrue(result.rollbackComplete());
        assertEquals("original-first", world.states.get(first));
        assertEquals("original-second", world.states.get(second));
        assertEquals(List.of(second, first), world.restores);
        assertEquals(List.of("snapshot:4", "snapshot:5", "place:1", "place:2", "restore:5", "restore:4"),
                world.events);
    }

    @Test
    void preparedBatchCanApplyWithoutResnapshotAndRollbackAfterLaterStage() {
        CompoundTag oneBlock = template(List.of(BlockPos.ZERO));
        BlockPos target = new BlockPos(6, 64, 6);
        FakeWorld world = new FakeWorld();
        world.states.put(target, "original");
        CityNbtPrefabBatchPlacer placer = new CityNbtPrefabBatchPlacer();

        CityNbtPrefabBatchPlacer.PreparedBatch prepared = placer.prepare(
                request("prepared", new BoundingBox(0, 0, 0, 15, 255, 15),
                        placement("prepared", oneBlock, target, 0)), world);
        int snapshotsBeforeWrite = world.snapshots.size();
        CityNbtPrefabBatchPlacer.BatchResult result = placer.place(prepared, world);

        assertTrue(prepared.ready());
        assertEquals(List.of(target), prepared.ownerTargetPositions());
        assertTrue(result.applied());
        assertEquals(snapshotsBeforeWrite, world.snapshots.size());
        assertTrue(placer.rollback(prepared, world));
        assertEquals("original", world.states.get(target));
    }

    @Test
    void rejectsNonSurfaceReplaceableGroundTargetBeforeWriting() {
        CompoundTag twoHigh = twoHighTemplate();
        BlockPos anchor = new BlockPos(6, 64, 6);
        FakeWorld world = new FakeWorld();
        world.nonSurfaceReplaceable.add(anchor);

        CityNbtPrefabBatchPlacer.BatchResult result = new CityNbtPrefabBatchPlacer().place(
                request("surface-policy", owner(0, 15), policyPlacement("surface-policy", twoHigh, anchor)),
                world);

        assertFalse(result.applied());
        assertEquals("CITY_NBT_PREFAB_REPLACE_POLICY_REJECTED", result.reasonCode());
        assertTrue(world.writes.isEmpty());
        assertTrue(world.snapshots.isEmpty());
    }

    @Test
    void rejectsNonReplaceableUpperTargetBeforeWriting() {
        CompoundTag twoHigh = twoHighTemplate();
        BlockPos anchor = new BlockPos(6, 64, 6);
        FakeWorld world = new FakeWorld();
        world.nonReplaceable.add(anchor.above());

        CityNbtPrefabBatchPlacer.BatchResult result = new CityNbtPrefabBatchPlacer().place(
                request("upper-policy", owner(0, 15), policyPlacement("upper-policy", twoHigh, anchor)),
                world);

        assertFalse(result.applied());
        assertEquals("CITY_NBT_PREFAB_REPLACE_POLICY_REJECTED", result.reasonCode());
        assertTrue(world.writes.isEmpty());
        assertEquals(List.of(anchor), world.snapshots);
    }

    @Test
    void frozenFallbackSkipsOnlyThatPlacementAndKeepsOtherPlacementReady() {
        CompoundTag template = template(List.of(BlockPos.ZERO));
        CityNbtPrefabBatchPlacer.PrefabPlacement fallback = placement(
                "fallback", template, new BlockPos(4, 64, 4), 0);
        CityNbtPrefabBatchPlacer.PrefabPlacement materialize = placement(
                "materialize", template, new BlockPos(5, 64, 4), 0);
        CityNbtPrefabBatchPlacer.BatchRequest request = new CityNbtPrefabBatchPlacer.BatchRequest(
                "decided", owner(0, 15), List.of(fallback, materialize),
                CityNbtPrefabBatchPlacer.ContentRejectionPolicy.FAIL_BATCH,
                Map.of("fallback", CityNbtPrefabBatchPlacer.PlacementDecision.FALLBACK,
                        "materialize", CityNbtPrefabBatchPlacer.PlacementDecision.MATERIALIZE));
        FakeWorld world = new FakeWorld();
        CityNbtPrefabBatchPlacer placer = new CityNbtPrefabBatchPlacer();

        CityNbtPrefabBatchPlacer.PreparedBatch prepared = placer.prepare(request, world);
        CityNbtPrefabBatchPlacer.BatchResult result = placer.place(prepared, world);

        assertTrue(prepared.ready());
        assertEquals(2, prepared.placementCount());
        assertEquals(1, prepared.readyPlacementCount());
        assertEquals(CityNbtPrefabBatchPlacer.PlacementStatus.SKIPPED_CONTENT,
                prepared.outcomes().get(0).status());
        assertTrue(result.applied());
        assertEquals(1, result.placementCount());
        assertEquals(List.of(new BlockPos(5, 64, 4)), world.writes);
    }

    @Test
    void completeFootprintPreflightDoesNotCaptureOwnerSnapshots() {
        CompoundTag template = template(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0)));
        CityNbtPrefabBatchPlacer.PrefabPlacement placement = policyPlacement(
                "cross-owner", template, new BlockPos(15, 64, 8));
        FakeWorld world = new FakeWorld();
        world.nonSurfaceReplaceable.add(new BlockPos(16, 64, 8));

        CityNbtPrefabBatchPlacer.PlacementPreflight preflight =
                new CityNbtPrefabBatchPlacer().preflight(placement, world);

        assertEquals(CityNbtPrefabBatchPlacer.PreflightStatus.FALLBACK, preflight.status());
        assertTrue(world.snapshots.isEmpty());
    }

    private static CityNbtPrefabBatchPlacer.BatchRequest request(
            String requestId, BoundingBox owner, CityNbtPrefabBatchPlacer.PrefabPlacement placement) {
        return new CityNbtPrefabBatchPlacer.BatchRequest(requestId, owner, List.of(placement));
    }

    private static BoundingBox owner(int min, int max) {
        return new BoundingBox(min, 0, min, max, 255, max);
    }

    private static CityNbtPrefabBatchPlacer.PrefabPlacement placement(
            String key, CompoundTag template, BlockPos anchor, int rotation) {
        return new CityNbtPrefabBatchPlacer.PrefabPlacement(
                key, "geomantia:test/" + key, "sha256:" + key, template, anchor, rotation, true);
    }

    private static CityNbtPrefabBatchPlacer.PrefabPlacement policyPlacement(
            String key, CompoundTag template, BlockPos anchor) {
        return new CityNbtPrefabBatchPlacer.PrefabPlacement(
                key, "geomantia:test/" + key, "sha256:" + key, template, anchor, 0, false,
                "surface_replaceable", 0);
    }

    private static CompoundTag template(List<BlockPos> positions) {
        CompoundTag root = new CompoundTag();
        root.put("size", ints(3, 1, 3));
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:stone");
        ListTag palette = new ListTag();
        palette.add(state);
        root.put("palette", palette);
        ListTag blocks = new ListTag();
        for (BlockPos pos : positions) {
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(pos.getX(), pos.getY(), pos.getZ()));
            block.putInt("state", 0);
            blocks.add(block);
        }
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return root;
    }

    private static CompoundTag twoHighTemplate() {
        CompoundTag template = template(List.of(BlockPos.ZERO, new BlockPos(0, 1, 0)));
        template.put("size", ints(1, 2, 1));
        return template;
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag result = new ListTag();
        result.add(IntTag.valueOf(x));
        result.add(IntTag.valueOf(y));
        result.add(IntTag.valueOf(z));
        return result;
    }

    private static final class FakeWorld implements CityNbtPrefabBatchPlacer.PlacementWorld {
        private final Map<BlockPos, String> states = new HashMap<>();
        private final List<BlockPos> snapshots = new ArrayList<>();
        private final List<BlockPos> restores = new ArrayList<>();
        private final List<BlockPos> writes = new ArrayList<>();
        private final List<Rotation> rotations = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final Set<BlockPos> nonReplaceable = new HashSet<>();
        private final Set<BlockPos> nonSurfaceReplaceable = new HashSet<>();
        private int placementCalls;
        private int failOnPlacementCall = -1;

        @Override
        public boolean ensureCanWrite(BlockPos pos) {
            return true;
        }

        @Override
        public boolean canReplace(CityNbtPrefabBatchPlacer.PlacementTarget target,
                                  String replacePolicy,
                                  int groundPlaneLocalY) {
            if (CityNbtPrefabBatchPlacer.LEGACY_REPLACE_ANY.equals(replacePolicy)) return true;
            boolean replaceable = !nonReplaceable.contains(target.worldPos());
            boolean surfaceReplaceable = !nonSurfaceReplaceable.contains(target.worldPos());
            return switch (replacePolicy) {
                case "replaceable_only" -> replaceable;
                case "surface_replaceable" -> target.templateAir()
                        ? replaceable || surfaceReplaceable
                        : target.localY() <= groundPlaneLocalY ? surfaceReplaceable : replaceable;
                default -> false;
            };
        }

        @Override
        public Object snapshot(BlockPos pos) {
            snapshots.add(pos);
            events.add("snapshot:" + pos.getX());
            return states.getOrDefault(pos, "original-air");
        }

        @Override
        public boolean restore(BlockPos pos, Object snapshot) {
            restores.add(pos);
            events.add("restore:" + pos.getX());
            states.put(pos, (String) snapshot);
            return true;
        }

        @Override
        public boolean placeTemplate(CompoundTag templateNbt, BlockPos anchor, Rotation rotation, long seed,
                                     boolean ignoreTemplateAir, BoundingBox ownerBounds) {
            placementCalls++;
            events.add("place:" + placementCalls);
            rotations.add(rotation);
            for (CityNbtPrefabBatchPlacer.PlacementTarget target : CityNbtPrefabBatchPlacer.targets(
                    templateNbt, anchor, rotation, !ignoreTemplateAir)) {
                BlockPos pos = target.worldPos();
                if (inside(ownerBounds, pos)) {
                    writes.add(pos);
                    states.put(pos, "placed-" + placementCalls);
                }
            }
            return placementCalls != failOnPlacementCall;
        }

        private static boolean inside(BoundingBox bounds, BlockPos pos) {
            return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                    && pos.getY() >= bounds.minY() && pos.getY() <= bounds.maxY()
                    && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
        }
    }
}
