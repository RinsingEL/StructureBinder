package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftCityTemplateWorldgenPlacerTest {
    private static final ChunkPos CHUNK_ZERO = new ChunkPos(0, 0);
    private static final CityTemplatePlacementGeometry.Rotation NONE = CityTemplatePlacementGeometry.Rotation.NONE;
    private static final CityTemplatePlacementGeometry.Mirror NO_MIRROR = CityTemplatePlacementGeometry.Mirror.NONE;

    @Test
    void hashDriftIsRejectedBeforeWriterAccess() {
        FakeSource source = new FakeSource();
        source.put("city:house", new Vec3i(8, 5, 8), "sha256:actual");
        FakeWriter writer = new FakeWriter(CHUNK_ZERO);

        MinecraftCityTemplateWorldgenPlacer.PlacementResult result = placer(source).place(
                request("city:house", "sha256:planned", new BlockPoint(2, 2), CHUNK_ZERO), writer);

        assertFalse(result.success());
        assertEquals("TEMPLATE_HASH_MISMATCH", result.reasonCode());
        assertEquals(0, writer.writes);
    }

    @Test
    void missingTemplateUsesStableNotFoundCode() {
        FakeWriter writer = new FakeWriter(CHUNK_ZERO);

        MinecraftCityTemplateWorldgenPlacer.PlacementResult result = placer(new FakeSource()).place(
                request("city:missing", "sha256:planned", new BlockPoint(2, 2), CHUNK_ZERO), writer);

        assertFalse(result.success());
        assertEquals("TEMPLATE_NOT_FOUND", result.reasonCode());
        assertEquals(0, writer.writes);
    }

    @Test
    void crossChunkTemplateWritesOnlyOwnerFragmentAndWaitsForOtherChunks() {
        FakeSource source = new FakeSource();
        source.put("city:long_house", new Vec3i(12, 6, 4), "sha256:house");
        FakeWriter writer = new FakeWriter(CHUNK_ZERO);

        MinecraftCityTemplateWorldgenPlacer.PlacementResult result = placer(source).place(
                request("city:long_house", "sha256:house", new BlockPoint(8, 3), CHUNK_ZERO), writer);

        assertFalse(result.success());
        assertTrue(result.waiting());
        assertTrue(result.worldMutationApplied());
        assertEquals("TEMPLATE_CHUNK_WRITE_WAITING", result.reasonCode());
        assertEquals(new BlockBounds(8, 3, 15, 6), writer.fragments.get(0).ownerFragment());
        assertEquals(new BlockBounds(8, 3, 19, 6), writer.fragments.get(0).templateFootprint());
        assertEquals("structure_template_nbt", result.materializationSource());
    }

    @Test
    void placementKeyMakesRepeatedOwnerCallbackIdempotent() {
        FakeSource source = new FakeSource();
        source.put("city:small_house", new Vec3i(4, 5, 4), "sha256:house");
        FakeWriter writer = new FakeWriter(CHUNK_ZERO);
        MinecraftCityTemplateWorldgenPlacer placer = placer(source);
        MinecraftCityTemplateWorldgenPlacer.PlacementRequest request = request(
                "city:small_house", "sha256:house", new BlockPoint(2, 2), CHUNK_ZERO);

        MinecraftCityTemplateWorldgenPlacer.PlacementResult first = placer.place(request, writer);
        MinecraftCityTemplateWorldgenPlacer.PlacementResult second = placer.place(request, writer);

        assertEquals("TEMPLATE_PLACED", first.reasonCode());
        assertEquals("TEMPLATE_ALREADY_PLACED", second.reasonCode());
        assertEquals(first.placementKey(), second.placementKey());
        assertEquals(1, writer.writes);
    }

    @Test
    void rotationUsesTransformedTemplateSizeForFragmentBounds() {
        FakeSource source = new FakeSource();
        source.put("city:hall", new Vec3i(5, 7, 9), "sha256:hall");
        FakeWriter writer = new FakeWriter(CHUNK_ZERO);

        MinecraftCityTemplateWorldgenPlacer.PlacementResult result = placer(source).place(
                new MinecraftCityTemplateWorldgenPlacer.PlacementRequest("city:hall", "sha256:hall",
                        new BlockPoint(1, 1), CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90, NO_MIRROR,
                        64, CHUNK_ZERO), writer);

        assertEquals(new BlockBounds(1, 1, 9, 5), result.templateFootprint());
        assertEquals("structure_template_nbt", writer.fragments.get(0).materializationSource());
    }

    @Test
    void runtimeAdapterMatchesCityGeometryForEverySourceCoordinateAndTransform() {
        CityTemplatePlacementGeometry.Size size = new CityTemplatePlacementGeometry.Size(5, 7, 9);
        BlockPoint anchor = new BlockPoint(21, -11);
        for (CityTemplatePlacementGeometry.Rotation rotation
                : CityTemplatePlacementGeometry.Rotation.values()) {
            for (CityTemplatePlacementGeometry.Mirror mirror
                    : CityTemplatePlacementGeometry.Mirror.values()) {
                String context = rotation + " / " + mirror;
                MinecraftCityTemplateWorldgenPlacer.RuntimeTransform runtime =
                        MinecraftCityTemplateWorldgenPlacer.deriveRuntimeTransform(
                                size, anchor, 64, rotation, mirror);
                CityTemplatePlacementGeometry geometry = CityTemplatePlacementGeometry.of(
                        size, rotation, mirror, List.of());

                assertTrue(runtime.validateAgainst(geometry).valid(), context);
                assertEquals(geometry.worldBounds(anchor), runtime.transformedFootprint(), context);
                assertEquals(net.minecraft.core.BlockPos.ZERO, runtime.rotationPivot(), context);
                for (int x = 0; x < size.width(); x++) {
                    for (int z = 0; z < size.depth(); z++) {
                        BlockPoint local = new BlockPoint(x, z);
                        BlockPoint expected = geometry.worldPosition(anchor, local);
                        net.minecraft.core.BlockPos actual = runtime.worldPosition(local);
                        assertEquals(expected.x(), actual.getX(), context + " local=" + local);
                        assertEquals(expected.z(), actual.getZ(), context + " local=" + local);
                    }
                }
            }
        }
    }

    @Test
    void clockwiseAndCounterclockwiseUseNormalizedPlacementOriginInsteadOfPivotOffset() {
        CityTemplatePlacementGeometry.Size size = new CityTemplatePlacementGeometry.Size(19, 8, 13);
        BlockPoint anchor = new BlockPoint(660210, 659551);

        MinecraftCityTemplateWorldgenPlacer.RuntimeTransform clockwise =
                MinecraftCityTemplateWorldgenPlacer.deriveRuntimeTransform(size, anchor, 107,
                        CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90, NO_MIRROR);
        MinecraftCityTemplateWorldgenPlacer.RuntimeTransform counterclockwise =
                MinecraftCityTemplateWorldgenPlacer.deriveRuntimeTransform(size, anchor, 107,
                        CityTemplatePlacementGeometry.Rotation.COUNTERCLOCKWISE_90, NO_MIRROR);

        assertEquals(new net.minecraft.core.BlockPos(660222, 107, 659551), clockwise.placementOrigin());
        assertEquals(new net.minecraft.core.BlockPos(660210, 107, 659569),
                counterclockwise.placementOrigin());
        assertEquals(net.minecraft.core.BlockPos.ZERO, clockwise.rotationPivot());
        assertEquals(net.minecraft.core.BlockPos.ZERO, counterclockwise.rotationPivot());
        assertEquals(new BlockBounds(660210, 659551, 660222, 659569),
                clockwise.transformedFootprint());
        assertEquals(clockwise.transformedFootprint(), counterclockwise.transformedFootprint());
    }

    @Test
    void lockedFootprintDriftFailsBeforeWriterAccess() {
        FakeSource source = new FakeSource();
        source.put("city:butcher", new Vec3i(19, 8, 13), "sha256:butcher");
        ChunkPos owner = new ChunkPos(0, 0);
        FakeWriter writer = new FakeWriter(owner);

        MinecraftCityTemplateWorldgenPlacer.PlacementResult result = placer(source).place(
                new MinecraftCityTemplateWorldgenPlacer.PlacementRequest(
                        "city:butcher", "sha256:butcher", new BlockPoint(0, 0),
                        CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90, NO_MIRROR, 64, owner,
                        new BlockBounds(0, -12, 12, 6)), writer);

        assertFalse(result.success());
        assertEquals("TEMPLATE_LOCKED_FOOTPRINT_MISMATCH", result.reasonCode());
        assertEquals(0, writer.writes);
    }

    @Test
    void rotatedNonSquareTemplateAcrossOwnersCoversEveryCoordinateOnlyInItsOwner() {
        FakeSource source = new FakeSource();
        source.put("city:butcher", new Vec3i(19, 8, 13), "sha256:butcher");
        MinecraftCityTemplateWorldgenPlacer placer = placer(source);
        BlockPoint anchor = new BlockPoint(8, 8);
        BlockBounds footprint = new BlockBounds(8, 8, 20, 26);
        List<ChunkPos> owners = List.of(new ChunkPos(0, 0), new ChunkPos(0, 1),
                new ChunkPos(1, 0), new ChunkPos(1, 1));
        Map<ChunkPos, MinecraftCityTemplateWorldgenPlacer.TemplateFragment> fragments = new HashMap<>();

        for (ChunkPos owner : owners) {
            FakeWriter writer = new FakeWriter(owner);
            MinecraftCityTemplateWorldgenPlacer.PlacementResult result = placer.place(
                    new MinecraftCityTemplateWorldgenPlacer.PlacementRequest(
                            "city:butcher", "sha256:butcher", anchor,
                            CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90, NO_MIRROR,
                            64, owner, footprint), writer);
            assertTrue(result.waiting());
            assertTrue(result.worldMutationApplied());
            assertEquals(1, writer.writes);
            MinecraftCityTemplateWorldgenPlacer.TemplateFragment fragment = writer.fragments.get(0);
            fragments.put(owner, fragment);
            assertEquals(intersection(footprint, owner), fragment.ownerFragment());
        }

        Set<BlockPoint> covered = new HashSet<>();
        MinecraftCityTemplateWorldgenPlacer.RuntimeTransform runtime = fragments.get(owners.get(0))
                .runtimeTransform();
        for (int x = 0; x < 19; x++) {
            for (int z = 0; z < 13; z++) {
                net.minecraft.core.BlockPos world = runtime.worldPosition(new BlockPoint(x, z));
                ChunkPos owner = new ChunkPos(Math.floorDiv(world.getX(), 16), Math.floorDiv(world.getZ(), 16));
                assertTrue(fragments.containsKey(owner));
                assertTrue(fragments.get(owner).ownerFragment().contains(world.getX(), world.getZ()));
                covered.add(new BlockPoint(world.getX(), world.getZ()));
            }
        }
        assertEquals(19 * 13, covered.size());
    }

    @Test
    void mismatchedRuntimeAndPlanningTransformIsExplicitlyRejected() {
        CityTemplatePlacementGeometry.Size size = new CityTemplatePlacementGeometry.Size(19, 8, 13);
        BlockPoint anchor = new BlockPoint(0, 0);
        MinecraftCityTemplateWorldgenPlacer.RuntimeTransform runtime =
                MinecraftCityTemplateWorldgenPlacer.deriveRuntimeTransform(size, anchor, 64,
                        CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90, NO_MIRROR);
        CityTemplatePlacementGeometry wrongGeometry = CityTemplatePlacementGeometry.of(size,
                CityTemplatePlacementGeometry.Rotation.COUNTERCLOCKWISE_90, NO_MIRROR, List.of());

        MinecraftCityTemplateWorldgenPlacer.RuntimeTransformValidation validation =
                runtime.validateAgainst(wrongGeometry);

        assertFalse(validation.valid());
        assertEquals("TEMPLATE_RUNTIME_TRANSFORM_MISMATCH", validation.reasonCode());
    }

    private static BlockBounds intersection(BlockBounds footprint, ChunkPos owner) {
        return new BlockBounds(Math.max(footprint.minX(), owner.getMinBlockX()),
                Math.max(footprint.minZ(), owner.getMinBlockZ()),
                Math.min(footprint.maxX(), owner.getMaxBlockX()),
                Math.min(footprint.maxZ(), owner.getMaxBlockZ()));
    }

    private static MinecraftCityTemplateWorldgenPlacer placer(FakeSource source) {
        return new MinecraftCityTemplateWorldgenPlacer(source);
    }

    private static MinecraftCityTemplateWorldgenPlacer.PlacementRequest request(
            String templateRef, String hash, BlockPoint anchor, ChunkPos ownerChunk) {
        return new MinecraftCityTemplateWorldgenPlacer.PlacementRequest(templateRef, hash, anchor,
                NONE, NO_MIRROR, 64, ownerChunk);
    }

    private static final class FakeSource implements MinecraftCityTemplateWorldgenPlacer.TemplateSource {
        private final Map<ResourceLocation, MinecraftCityTemplateReader.TemplateSnapshot> templates = new HashMap<>();

        private void put(String ref, Vec3i size, String hash) {
            templates.put(ResourceLocation.parse(ref),
                    MinecraftCityTemplateReader.TemplateSnapshot.metadata(size, hash, "fake-nbt"));
        }

        @Override
        public Optional<MinecraftCityTemplateReader.TemplateSnapshot> load(ResourceLocation templateRef) {
            return Optional.ofNullable(templates.get(templateRef));
        }
    }

    private static final class FakeWriter implements MinecraftCityTemplateWorldgenPlacer.WorldWriter {
        private final ChunkPos ownerChunk;
        private final Set<String> placed = new HashSet<>();
        private final java.util.ArrayList<MinecraftCityTemplateWorldgenPlacer.TemplateFragment> fragments =
                new java.util.ArrayList<>();
        private int writes;

        private FakeWriter(ChunkPos ownerChunk) {
            this.ownerChunk = ownerChunk;
        }

        @Override
        public ChunkPos ownerChunk() {
            return ownerChunk;
        }

        @Override
        public boolean isPlaced(String placementKey) {
            return placed.contains(placementKey);
        }

        @Override
        public MinecraftCityTemplateWorldgenPlacer.WriteReport write(
                MinecraftCityTemplateWorldgenPlacer.TemplateFragment fragment) {
            if (!placed.add(fragment.placementKey())) {
                return MinecraftCityTemplateWorldgenPlacer.WriteReport.alreadyPlaced();
            }
            fragments.add(fragment);
            writes++;
            return MinecraftCityTemplateWorldgenPlacer.WriteReport.written();
        }
    }
}
