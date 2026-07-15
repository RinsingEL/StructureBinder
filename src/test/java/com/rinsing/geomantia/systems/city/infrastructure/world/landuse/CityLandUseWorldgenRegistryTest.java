package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseWorldgenRegistryTest {
    @Test
    void vegetationClassificationDoesNotSuppressUnrelatedConfiguredFeatures() {
        assertTrue(CityLandUseWorldgenRegistry.vegetationLike("configured_tree_oak"));
        assertTrue(CityLandUseWorldgenRegistry.vegetationLike("flower_patch"));
        assertFalse(CityLandUseWorldgenRegistry.vegetationLike("ore_diamond"));
        assertFalse(CityLandUseWorldgenRegistry.vegetationLike("lake_lava"));
    }

    @AfterEach
    void resetRegistry() {
        CityLandUseWorldgenRegistry.resetForTests();
    }

    @Test
    void persistsDimensionCityPlansAndOwnerLedgerAndReloadsIdempotently(@TempDir Path temp) throws Exception {
        Path server = temp.resolve("server");
        LandUseAreaPlan plan = CityLandUseChunkCompilerTest.plan("city_a");
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", plan, server);
        FakeWorld world = new FakeWorld();

        CityLandUseWorldgenRegistry.ApplySummary first = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES, world);
        int writesAfterFirst = world.writes.size();
        CityLandUseWorldgenRegistry.ApplySummary repeated = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES, world);

        assertEquals(1, first.appliedOwnerCount());
        assertEquals(1, repeated.alreadyAppliedOwnerCount());
        assertTrue(writesAfterFirst > 0);
        assertEquals(writesAfterFirst, world.writes.size());
        JsonObject active = JsonParser.parseString(Files.readString(
                CityLandUseWorldgenRegistry.activePlansPath(server))).getAsJsonObject();
        assertEquals(CityLandUseWorldgenRegistry.ACTIVE_SCHEMA,
                active.get("schemaVersion").getAsString());
        assertEquals(plan.planHash(), active.getAsJsonArray("plans").get(0).getAsJsonObject()
                .get("planHash").getAsString());

        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.load(server);
        CityLandUseWorldgenRegistry.ApplySummary reloaded = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES, world);
        assertEquals(1, reloaded.alreadyAppliedOwnerCount());
        assertEquals(1, CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").size());
    }

    @Test
    void rejectsHashMismatchAndDoesNotBackfillOldChunk(@TempDir Path temp) {
        Path server = temp.resolve("server");
        LandUseAreaPlan valid = CityLandUseChunkCompilerTest.plan("city_a");
        assertThrows(IllegalArgumentException.class, () -> CityLandUseWorldgenRegistry.activate(
                "minecraft:overworld", valid.withPlanHash("changed"), server));

        CityLandUseWorldgenRegistry.activate("minecraft:overworld", valid, server);
        FakeWorld world = new FakeWorld();
        CityLandUseWorldgenRegistry.ApplySummary summary = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.ALREADY_GENERATED, world);

        assertEquals(1, summary.ineligibleOwnerCount());
        assertTrue(world.writes.isEmpty());
        assertEquals(0, CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").size());
    }

    @Test
    void failedOwnerRollsBackAndIsRetriedWithoutLedgerCommit(@TempDir Path temp) {
        Path server = temp.resolve("server");
        CityLandUseWorldgenRegistry.activate("minecraft:overworld",
                CityLandUseChunkCompilerTest.plan("city_a"), server);
        FakeWorld world = new FakeWorld();
        world.failWriteIndex = 2;

        CityLandUseWorldgenRegistry.ApplySummary failed = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES, world);

        assertEquals(1, failed.failedOwnerCount());
        assertEquals(1, world.restores.size());
        assertEquals(0, CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").size());

        world.failWriteIndex = -1;
        CityLandUseWorldgenRegistry.ApplySummary retried = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES, world);
        assertEquals(1, retried.appliedOwnerCount());
        assertEquals(1, CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").size());
    }

    @Test
    void clearVegetationHonorsFootprintCorridorAndGateExclusions(@TempDir Path temp) {
        CityLandUseWorldgenRegistry.activate("minecraft:overworld",
                CityLandUseChunkCompilerTest.plan("city_a"), temp.resolve("server"));

        assertTrue(CityLandUseWorldgenRegistry.suppressesVegetation("minecraft:overworld", 0, 0));
        assertFalse(CityLandUseWorldgenRegistry.suppressesVegetation("minecraft:overworld", 1, 0));
        assertFalse(CityLandUseWorldgenRegistry.suppressesVegetation("minecraft:overworld", 2, 0));
        assertFalse(CityLandUseWorldgenRegistry.suppressesVegetation("minecraft:overworld", 3, 0));
        assertFalse(CityLandUseWorldgenRegistry.suppressesVegetation("minecraft:the_nether", 0, 0));
    }

    private static final class FakeWorld implements CityLandUseChunkExecutor.ExecutionWorld {
        private final List<String> writes = new ArrayList<>();
        private final List<String> restores = new ArrayList<>();
        private int failWriteIndex = -1;

        @Override
        public CityLandUseChunkExecutor.ColumnSample sampleColumn(int worldX, int worldZ) {
            return new CityLandUseChunkExecutor.ColumnSample(64, "minecraft:grass_block", true);
        }

        @Override
        public boolean isKnownBlock(String blockId) {
            return true;
        }

        @Override
        public boolean ensureCanWrite(int worldX, int y, int worldZ) {
            return true;
        }

        @Override
        public CityLandUseChunkExecutor.TargetState inspect(int worldX, int y, int worldZ) {
            return new CityLandUseChunkExecutor.TargetState("old", true);
        }

        @Override
        public boolean setBlock(int worldX, int y, int worldZ, String blockId) {
            if (writes.size() + 1 == failWriteIndex) return false;
            writes.add(worldX + "," + y + "," + worldZ + "=" + blockId);
            return true;
        }

        @Override
        public boolean restoreBlock(int worldX, int y, int worldZ, Object snapshot) {
            restores.add(worldX + "," + y + "," + worldZ);
            return true;
        }
    }
}
