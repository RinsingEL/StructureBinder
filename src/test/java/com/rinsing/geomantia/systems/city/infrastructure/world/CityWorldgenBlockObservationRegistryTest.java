package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityWorldgenBlockObservationRegistryTest {
    @TempDir
    Path tempDirectory;

    @Test
    void buildsActualBlockStateEvidenceInsteadOfRepeatingAttemptCounts() {
        BlockPos crop = new BlockPos(1, 64, 1);
        BlockPos wrongAge = new BlockPos(2, 64, 1);
        BlockPos missing = new BlockPos(3, 64, 1);
        var wheatAgeZero = new CityWorldgenBlockObservationRegistry.ObservedState(
                "minecraft:wheat", Map.of("age", "0"), false);
        var wheatAgeSeven = new CityWorldgenBlockObservationRegistry.ObservedState(
                "minecraft:wheat", Map.of("age", "7"), false);
        CityWorldgenBlockObservationRegistry.ObservationSnapshot snapshot =
                new CityWorldgenBlockObservationRegistry.ObservationSnapshot(
                        tempDirectory,
                        "minecraft:overworld",
                        new ChunkPos(0, 0),
                        new ChunkPos(0, 0),
                        Map.of(
                                crop, new CityWorldgenBlockObservationRegistry.ExpectedWrite(
                                        "minecraft:wheat", wheatAgeZero, "land_use_direct"),
                                wrongAge, new CityWorldgenBlockObservationRegistry.ExpectedWrite(
                                        "minecraft:wheat", wheatAgeSeven, "land_use_direct"),
                                missing, new CityWorldgenBlockObservationRegistry.ExpectedWrite(
                                        "minecraft:wheat",
                                        new CityWorldgenBlockObservationRegistry.ObservedState(
                                                "minecraft:air", Map.of(), true),
                                        "land_use_direct")),
                        2);

        JsonObject observation = CityWorldgenBlockObservationRegistry.buildObservationFromStates(
                snapshot,
                CityWorldgenBlockObservationRegistry.POST_FEATURES,
                "test_callback",
                pos -> pos.equals(crop)
                        ? wheatAgeZero
                        : pos.equals(wrongAge)
                        ? wheatAgeZero
                        : new CityWorldgenBlockObservationRegistry.ObservedState(
                                "minecraft:air", Map.of(), true));

        assertEquals(3, observation.get("watchedBlockCount").getAsInt());
        assertEquals(2, observation.get("matchedExpectedBlockCount").getAsInt());
        assertEquals(1, observation.get("mismatchedExpectedBlockCount").getAsInt());
        assertEquals(1, observation.get("postWriteExpectedBlockMismatchCount").getAsInt());
        assertEquals(2, observation.get("matchedPostWriteStateCount").getAsInt());
        assertEquals(1, observation.get("changedSinceWriteCount").getAsInt());
        assertEquals(1, observation.get("actualAirBlockCount").getAsInt());
        assertEquals(2, observation.get("rolledBackWriteCount").getAsInt());
        JsonObject cropBlock = observation.getAsJsonArray("blocks").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(block -> block.get("x").getAsInt() == crop.getX())
                .findFirst()
                .orElseThrow();
        JsonObject actualCropState = cropBlock.getAsJsonObject("actualState");
        assertEquals("minecraft:wheat", actualCropState.get("blockId").getAsString());
        assertTrue(actualCropState.getAsJsonObject("properties").has("age"));
        JsonObject wrongAgeBlock = observation.getAsJsonArray("blocks").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(block -> block.get("x").getAsInt() == wrongAge.getX())
                .findFirst()
                .orElseThrow();
        assertTrue(wrongAgeBlock.get("matchesExpectedBlock").getAsBoolean());
        assertFalse(wrongAgeBlock.get("matchesPostWriteState").getAsBoolean());
        assertEquals("7", wrongAgeBlock.getAsJsonObject("postWriteState")
                .getAsJsonObject("properties").get("age").getAsString());
    }

    @Test
    void queriesRecentChunkObservationsByPhaseWithoutLoadingChunks() throws Exception {
        ChunkPos chunk = new ChunkPos(-2, 3);
        Path path = CityWorldgenBlockObservationRegistry.observationPath(
                tempDirectory, "minecraft:overworld", chunk);
        Files.createDirectories(path.getParent());
        JsonObject postFeatures = observation("post_features", 4);
        JsonObject chunkSave = observation("chunk_save", 3);
        Files.writeString(path,
                postFeatures + System.lineSeparator()
                        + "not-json" + System.lineSeparator()
                        + chunkSave + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        JsonObject response = CityWorldgenBlockObservationRegistry.query(
                tempDirectory, "minecraft:overworld", -2, 3, "chunk_save", 10, false);

        assertTrue(response.get("ok").getAsBoolean());
        assertEquals("OBSERVED", response.get("status").getAsString());
        assertEquals(1, response.get("observationCount").getAsInt());
        JsonObject returned = response.getAsJsonArray("observations").get(0).getAsJsonObject();
        assertEquals("chunk_save", returned.get("phase").getAsString());
        assertFalse(returned.has("blocks"));
        assertFalse(response.get("pendingSaveVerification").getAsBoolean());
    }

    @Test
    void rejectsInvalidQueryValuesAndIgnoresMalformedPhaseFields() throws Exception {
        ChunkPos chunk = new ChunkPos(1, 2);
        Path path = CityWorldgenBlockObservationRegistry.observationPath(
                tempDirectory, "minecraft:overworld", chunk);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"phase\":{},\"blocks\":[]}" + System.lineSeparator());

        JsonObject response = CityWorldgenBlockObservationRegistry.query(
                tempDirectory, "minecraft:overworld", 1, 2, "chunk_save", 10, true);

        assertEquals("NOT_OBSERVED", response.get("status").getAsString());
        assertThrows(IllegalArgumentException.class, () -> CityWorldgenBlockObservationRegistry.query(
                tempDirectory, "minecraft:overworld", 1, 2, "unknown", 10, true));
        assertThrows(IllegalArgumentException.class, () -> CityWorldgenBlockObservationRegistry.query(
                tempDirectory, "minecraft:overworld", 1, 2, null, 0, true));
        assertThrows(IllegalArgumentException.class, () -> CityWorldgenBlockObservationRegistry.query(
                tempDirectory, "not a dimension", 1, 2, null, 10, true));
    }

    @Test
    void mergesPendingSaveSnapshotsWithoutDroppingEarlierTouchedPositions() {
        BlockPos firstPos = new BlockPos(1, 64, 1);
        BlockPos secondPos = new BlockPos(2, 64, 1);
        var stone = new CityWorldgenBlockObservationRegistry.ObservedState(
                "minecraft:stone", Map.of(), false);
        var dirt = new CityWorldgenBlockObservationRegistry.ObservedState(
                "minecraft:dirt", Map.of(), false);
        var first = snapshot(Map.of(firstPos,
                new CityWorldgenBlockObservationRegistry.ExpectedWrite(
                        "minecraft:stone", stone, "first")), 1);
        var second = snapshot(Map.of(
                firstPos, new CityWorldgenBlockObservationRegistry.ExpectedWrite(
                        "minecraft:dirt", dirt, "second"),
                secondPos, new CityWorldgenBlockObservationRegistry.ExpectedWrite(
                        "minecraft:stone", stone, "second")), 2);

        var merged = CityWorldgenBlockObservationRegistry.mergeSnapshots(first, second);

        assertEquals(2, merged.expectedBlocks().size());
        assertEquals("minecraft:dirt", merged.expectedBlocks().get(firstPos).expectedBlockId());
        assertEquals(3, merged.rolledBackWriteCount());
    }

    @Test
    void rollbackTokenRemovesOnlyOwnedWriteAndPreservesEarlierAndLaterObservations() {
        BlockPos pos = new BlockPos(1, 64, 1);
        var stone = new CityWorldgenBlockObservationRegistry.ObservedState(
                "minecraft:stone", Map.of(), false);
        var dirt = new CityWorldgenBlockObservationRegistry.ObservedState(
                "minecraft:dirt", Map.of(), false);
        var grass = new CityWorldgenBlockObservationRegistry.ObservedState(
                "minecraft:grass_block", Map.of(), false);
        var capture = new CityWorldgenBlockObservationRegistry.Capture(
                tempDirectory, "minecraft:overworld", new ChunkPos(0, 0));
        capture.watch(pos, "minecraft:stone", stone, "earlier_write");
        var rolledBackToken = capture.newRollbackToken();
        var laterToken = capture.newRollbackToken();
        capture.watch(pos, "minecraft:dirt", dirt, "rolled_back_write", rolledBackToken);
        capture.watch(pos, "minecraft:grass_block", grass, "later_failed_write", laterToken);

        capture.rollback(rolledBackToken);

        var snapshots = capture.snapshotsByObservedChunk();
        assertEquals(1, snapshots.size());
        assertEquals("minecraft:grass_block", snapshots.get(0).expectedBlocks().get(pos).expectedBlockId());
        assertEquals(1, snapshots.get(0).rolledBackWriteCount());

        capture.rollback(laterToken);
        snapshots = capture.snapshotsByObservedChunk();
        assertEquals("minecraft:stone", snapshots.get(0).expectedBlocks().get(pos).expectedBlockId());
        assertEquals(2, snapshots.get(0).rolledBackWriteCount());
    }

    @Test
    void rollbackTokenRemovesOwnedWritesAcrossPositions() {
        BlockPos first = new BlockPos(1, 64, 1);
        BlockPos second = new BlockPos(2, 64, 1);
        BlockPos retained = new BlockPos(3, 64, 1);
        var stone = new CityWorldgenBlockObservationRegistry.ObservedState(
                "minecraft:stone", Map.of(), false);
        var capture = new CityWorldgenBlockObservationRegistry.Capture(
                tempDirectory, "minecraft:overworld", new ChunkPos(0, 0));
        var rollbackToken = capture.newRollbackToken();
        capture.watch(first, "minecraft:stone", stone, "center", rollbackToken);
        capture.watch(second, "minecraft:stone", stone, "neighbor", rollbackToken);
        capture.watch(retained, "minecraft:stone", stone, "other_mutation");

        capture.rollback(rollbackToken);

        var snapshots = capture.snapshotsByObservedChunk();
        assertEquals(1, snapshots.size());
        assertEquals(Map.of(retained, new CityWorldgenBlockObservationRegistry.ExpectedWrite(
                "minecraft:stone", stone, "other_mutation")), snapshots.get(0).expectedBlocks());
        assertEquals(2, snapshots.get(0).rolledBackWriteCount());
    }

    @Test
    void failedBoundaryFinalizeLeavesNoFinalizeObservationEvidence() {
        var capture = new CityWorldgenBlockObservationRegistry.Capture(
                tempDirectory, "minecraft:overworld", new ChunkPos(0, 0));
        BlockPos rawBoundary = new BlockPos(15, 65, 0);
        BlockPos reconciledNeighbor = rawBoundary.east();
        var fence = new CityWorldgenBlockObservationRegistry.ObservedState(
                "minecraft:oak_fence", Map.of("east", "true", "west", "true"), false);
        capture.watch(rawBoundary, "minecraft:oak_fence", fence, "land_use_direct");

        var finalizeRollbackToken = capture.newRollbackToken();
        capture.watch(rawBoundary, "minecraft:oak_fence", fence,
                "land_use_neighbor_reconcile", finalizeRollbackToken);
        capture.watch(reconciledNeighbor, "minecraft:oak_fence", fence,
                "land_use_neighbor_reconcile", finalizeRollbackToken);
        capture.rollback(finalizeRollbackToken);

        var snapshots = capture.snapshotsByObservedChunk();
        assertEquals(1, snapshots.size());
        assertEquals(Map.of(rawBoundary,
                        new CityWorldgenBlockObservationRegistry.ExpectedWrite(
                                "minecraft:oak_fence", fence, "land_use_direct")),
                snapshots.get(0).expectedBlocks());
        assertEquals(2, snapshots.get(0).rolledBackWriteCount());
    }

    @Test
    void rollbackTokenFromAnotherCaptureCannotDeleteCurrentWrites() {
        BlockPos pos = new BlockPos(1, 64, 1);
        var stone = new CityWorldgenBlockObservationRegistry.ObservedState(
                "minecraft:stone", Map.of(), false);
        var firstCapture = new CityWorldgenBlockObservationRegistry.Capture(
                tempDirectory, "minecraft:overworld", new ChunkPos(0, 0));
        var secondCapture = new CityWorldgenBlockObservationRegistry.Capture(
                tempDirectory, "minecraft:overworld", new ChunkPos(0, 0));
        var foreignToken = firstCapture.newRollbackToken();
        secondCapture.watch(pos, "minecraft:stone", stone, "current_capture", foreignToken);

        secondCapture.rollback(foreignToken);

        var snapshots = secondCapture.snapshotsByObservedChunk();
        assertEquals(1, snapshots.size());
        assertEquals("minecraft:stone", snapshots.get(0).expectedBlocks().get(pos).expectedBlockId());
        assertEquals(0, snapshots.get(0).rolledBackWriteCount());
    }

    @Test
    void dimensionDirectoryEncodingDoesNotCollapseDistinctResourceLocations() {
        Path nested = CityWorldgenBlockObservationRegistry.observationPath(
                tempDirectory, "example:a/b", new ChunkPos(0, 0));
        Path underscored = CityWorldgenBlockObservationRegistry.observationPath(
                tempDirectory, "example:a_b", new ChunkPos(0, 0));

        assertNotEquals(nested, underscored);
    }

    @Test
    void transformsTemplateTargetsAndExcludesIgnoredAirAndOutsideOwnerBounds() {
        CompoundTag template = new CompoundTag();
        ListTag palette = new ListTag();
        palette.add(blockState("minecraft:oak_stairs", Map.of("facing", "north")));
        palette.add(blockState("minecraft:air"));
        template.put("palette", palette);
        ListTag blocks = new ListTag();
        blocks.add(templateBlock(0, 1, 0, 2));
        blocks.add(templateBlock(1, 2, 0, 2));
        blocks.add(templateBlock(0, 20, 0, 20));
        template.put("blocks", blocks);

        var targets = CityWorldgenBlockObservationRegistry.templateExpectedBlocks(
                template,
                new BlockPos(10, 64, 10),
                Mirror.NONE,
                Rotation.CLOCKWISE_90,
                BlockPos.ZERO,
                true,
                new BoundingBox(0, 0, 0, 15, 100, 15));

        assertEquals(1, targets.size());
        assertEquals(new BlockPos(8, 64, 11), targets.get(0).worldPos());
        assertEquals("minecraft:oak_stairs", targets.get(0).blockId());
    }

    private static JsonObject observation(String phase, int matched) {
        JsonObject observation = new JsonObject();
        observation.addProperty("schema", CityWorldgenBlockObservationRegistry.SCHEMA);
        observation.addProperty("phase", phase);
        observation.addProperty("matchedExpectedBlockCount", matched);
        observation.add("blocks", new com.google.gson.JsonArray());
        return observation;
    }

    private static CompoundTag blockState(String blockId) {
        return blockState(blockId, Map.of());
    }

    private static CompoundTag blockState(String blockId, Map<String, String> properties) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        if (!properties.isEmpty()) {
            CompoundTag encodedProperties = new CompoundTag();
            properties.forEach(encodedProperties::putString);
            state.put("Properties", encodedProperties);
        }
        return state;
    }

    private CityWorldgenBlockObservationRegistry.ObservationSnapshot snapshot(
            Map<BlockPos, CityWorldgenBlockObservationRegistry.ExpectedWrite> expected,
            int rolledBackWriteCount) {
        return new CityWorldgenBlockObservationRegistry.ObservationSnapshot(
                tempDirectory, "minecraft:overworld", new ChunkPos(0, 0), new ChunkPos(0, 0),
                expected, rolledBackWriteCount);
    }

    private static CompoundTag templateBlock(int state, int x, int y, int z) {
        CompoundTag block = new CompoundTag();
        block.putInt("state", state);
        ListTag coordinates = new ListTag();
        coordinates.add(IntTag.valueOf(x));
        coordinates.add(IntTag.valueOf(y));
        coordinates.add(IntTag.valueOf(z));
        block.put("pos", coordinates);
        return block;
    }
}
