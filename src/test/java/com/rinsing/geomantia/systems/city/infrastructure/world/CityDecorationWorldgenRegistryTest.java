package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramCodec;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationChunkCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalogLoader;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationPlantCatalogTestFixture;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationStyleProfileCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationStyleProfileCatalogLoader;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationTerrainRunCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationWorldgenRegistryTest {
    @AfterEach
    void resetRegistry() {
        CityDecorationWorldgenRegistry.resetForTests();
    }

    @Test
    void layeredFragmentCommitsOuterLedgerOnlyAfterRequiredPlantSucceeds(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = CityDecorationPlantCatalogTestFixture.create(catalogRoot);
        CityDecorationWorldgenRegistry.setCatalogReaderForTests(ignored -> catalog);
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                layeredPlan(catalog), serverRoot, catalogRoot);
        FakePlacementWorld world = new FakePlacementWorld(true);
        world.failPlant = true;

        CityDecorationWorldgenRegistry.ApplySummary partial = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, world);

        assertEquals(0, partial.appliedFragmentCount());
        assertEquals(1, partial.failedFragmentCount());
        assertEquals(0, appliedCount());
        assertEquals(List.of("base", "plant"), world.placementOrder);
        JsonObject partialOutcome = CityDecorationWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("fragmentOutcomes").get(0).getAsJsonObject();
        assertEquals("failed", partialOutcome.get("status").getAsString());
        assertEquals(List.of("applied", "failed"), partialOutcome.getAsJsonArray("layers").asList().stream()
                .map(layer -> layer.getAsJsonObject().get("status").getAsString()).toList());

        world.failPlant = false;
        CityDecorationWorldgenRegistry.ApplySummary completed = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, world);

        assertEquals(1, completed.appliedFragmentCount());
        assertEquals(1, appliedCount());
        assertEquals(1, world.placeCalls, "successful base layer must not be placed again on retry");
        assertEquals(2, world.plantPlaceCalls);
        JsonObject applied = CityDecorationWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedFragments").get(0).getAsJsonObject();
        assertEquals(List.of("already_satisfied", "applied"), applied.getAsJsonArray("layers").asList().stream()
                .map(layer -> layer.getAsJsonObject().get("status").getAsString()).toList());
    }

    @Test
    void routesMultipleCitiesByDimensionAndPersistsSuppression(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(catalog, "city_a", "a", 1L, 4, 4), serverRoot, catalogRoot);
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(catalog, "city_b", "b", 2L, 8, 4), serverRoot, catalogRoot);
        CityDecorationWorldgenRegistry.activate("minecraft:the_nether",
                plan(catalog, "city_c", "c", 3L, 4, 4), serverRoot, catalogRoot);
        FakePlacementWorld world = new FakePlacementWorld(true);

        CityDecorationWorldgenRegistry.ApplySummary overworld = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, world);
        CityDecorationWorldgenRegistry.ApplySummary nether = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:the_nether", 0, 0, FlatTerrain.INSTANCE, world);

        assertEquals(2, overworld.activePlanCount());
        assertEquals(2, overworld.appliedFragmentCount());
        assertEquals(1, nether.activePlanCount());
        assertEquals(1, nether.appliedFragmentCount());
        assertEquals(3, world.placeCalls);
        assertTrue(CityDecorationWorldgenRegistry.suppressesVegetation(
                "minecraft:overworld", "minecraft:oak_tree", 4, 4));
        assertFalse(CityDecorationWorldgenRegistry.suppressesVegetation(
                "minecraft:overworld", "minecraft:ore_diamond", 4, 4));
        assertTrue(CityDecorationWorldgenRegistry.suppressesVegetation(
                "minecraft:the_nether", "minecraft:crimson_fungus_patch", 4, 4));

        JsonObject active = JsonParser.parseString(Files.readString(
                CityDecorationWorldgenRegistry.activePlansPath(serverRoot))).getAsJsonObject();
        assertEquals(CityDecorationWorldgenRegistry.ACTIVE_SCHEMA, active.get("schema").getAsString());
        assertEquals(3, active.getAsJsonArray("plans").size());
    }

    @Test
    void recordsOnlySuccessRetriesFailureAndAllowsChangedProgramHash(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(catalog, "city_a", "program", 10L, 4, 4), serverRoot, catalogRoot);
        FakePlacementWorld world = new FakePlacementWorld(false);

        CityDecorationWorldgenRegistry.ApplySummary failed = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, world);
        assertEquals(1, failed.failedFragmentCount());
        assertEquals(0, appliedCount());
        assertTrue(CityDecorationWorldgenRegistry.suppressesVegetation(
                "minecraft:overworld", "minecraft:oak_tree", 4, 4));

        world.succeed = true;
        CityDecorationWorldgenRegistry.ApplySummary applied = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, world);
        assertEquals(1, applied.appliedFragmentCount());
        assertEquals(1, appliedCount());
        JsonObject ledgerEntry = CityDecorationWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedFragments").get(0).getAsJsonObject();
        assertEquals("minecraft:overworld", ledgerEntry.get("dimensionId").getAsString());
        assertEquals("city_a", ledgerEntry.get("cityId").getAsString());
        assertTrue(ledgerEntry.get("programHash").getAsString().startsWith("sha256:"));
        assertEquals(catalog.catalogHash(), ledgerEntry.get("catalogHash").getAsString());

        CityDecorationWorldgenRegistry.ApplySummary reentered = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, world);
        assertEquals(1, reentered.alreadyAppliedFragmentCount());
        assertEquals(2, world.placeCalls, "failed and successful attempts only; committed fragment is not placed again");

        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(catalog, "city_a", "program", 11L, 4, 4), serverRoot, catalogRoot);
        CityDecorationWorldgenRegistry.ApplySummary changed = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, world);
        assertEquals(1, changed.appliedFragmentCount());
        assertEquals(2, appliedCount());

        CityDecorationWorldgenRegistry.resetForTests();
        CityDecorationWorldgenRegistry.load(serverRoot, catalogRoot);
        CityDecorationWorldgenRegistry.ApplySummary afterReload = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, world);
        assertEquals(1, afterReload.alreadyAppliedFragmentCount());
        assertEquals(2, appliedCount());
    }

    @Test
    void replacesTheSameCityWithAReplannedCatalogHash(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog original = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(original, "city_a", "field", 1L, 4, 4), serverRoot, catalogRoot);
        CityDecorationContentCatalog replanned = catalog(catalogRoot, "minecraft:dirt");

        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(replanned, "city_a", "field", 2L, 4, 4), serverRoot, catalogRoot);

        JsonObject active = JsonParser.parseString(Files.readString(
                CityDecorationWorldgenRegistry.activePlansPath(serverRoot))).getAsJsonObject();
        assertEquals(1, active.getAsJsonArray("plans").size());
        assertEquals(replanned.catalogHash(), active.getAsJsonArray("plans").get(0).getAsJsonObject()
                .get("catalogHash").getAsString());
    }

    @Test
    void writesOnlyTriggeredOwnerChunkAndDefersItsUnavailableFragment(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                twoChunkPlan(catalog, "city_a", "field", 15, 0), serverRoot, catalogRoot);
        FakePlacementWorld world = new FakePlacementWorld(true).denyAtOrAfterX(16);

        CityDecorationWorldgenRegistry.ApplySummary firstOwner = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, world);

        assertEquals(1, firstOwner.appliedFragmentCount());
        assertEquals(1, world.placeCalls);
        assertEquals(List.of(15), world.placementOrigins.stream().map(BlockPos::getX).toList());
        assertEquals(1, appliedCount());
        assertTrue(CityDecorationWorldgenRegistry.suppressesVegetation(
                "minecraft:overworld", "minecraft:oak_tree", 15, 0));
        assertFalse(CityDecorationWorldgenRegistry.suppressesVegetation(
                "minecraft:overworld", "minecraft:oak_tree", 16, 0));

        CityDecorationWorldgenRegistry.ApplySummary unavailableOwner = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 1, 0, FlatTerrain.INSTANCE, world);

        assertEquals(0, unavailableOwner.appliedFragmentCount());
        assertEquals(1, world.placeCalls, "a callback must never write another owner chunk");
        assertEquals(1, appliedCount());
        assertTrue(CityDecorationWorldgenRegistry.suppressesVegetation(
                "minecraft:overworld", "minecraft:oak_tree", 16, 0));
        JsonObject deferredOutcome = CityDecorationWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("fragmentOutcomes").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(outcome -> outcome.get("chunkX").getAsInt() == 1)
                .findFirst().orElseThrow();
        assertEquals("deferred", deferredOutcome.get("status").getAsString());
        assertEquals("CITY_DECORATION_TARGET_NOT_WRITABLE",
                deferredOutcome.get("reasonCode").getAsString());

        world.denyAtOrAfterX(Integer.MAX_VALUE);
        CityDecorationWorldgenRegistry.ApplySummary secondOwner = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 1, 0, FlatTerrain.INSTANCE, world);

        assertEquals(1, secondOwner.appliedFragmentCount());
        assertEquals(2, world.placeCalls);
        assertEquals(2, appliedCount());
        assertEquals(List.of(15, 16), world.placementOrigins.stream().map(BlockPos::getX).toList());
    }

    @Test
    void featureInvocationGuardRunsOnlyTheFirstOutermostOwnerCallbackAndResets() {
        Object generationScope = new Object();

        assertTrue(CityDecorationWorldgenRegistry.enterFeatureOwnerForTests(
                generationScope, "minecraft:overworld", 7, 9));
        assertFalse(CityDecorationWorldgenRegistry.enterFeatureOwnerForTests(
                generationScope, "minecraft:overworld", 8, 9),
                "a nested ConfiguredFeature must not start another decoration projection");
        CityDecorationWorldgenRegistry.exitFeatureOriginForTests();
        CityDecorationWorldgenRegistry.exitFeatureOriginForTests();

        assertFalse(CityDecorationWorldgenRegistry.enterFeatureOwnerForTests(
                generationScope, "minecraft:overworld", 7, 9),
                "later top-level features in the same generation scope reuse the first owner result");
        CityDecorationWorldgenRegistry.exitFeatureOriginForTests();
        assertTrue(CityDecorationWorldgenRegistry.enterFeatureOwnerForTests(
                generationScope, "minecraft:overworld", 8, 9),
                "a different owner chunk remains independently eligible");
        CityDecorationWorldgenRegistry.exitFeatureOriginForTests();
        assertTrue(CityDecorationWorldgenRegistry.enterFeatureOwnerForTests(
                new Object(), "minecraft:overworld", 7, 9),
                "a different WorldGenRegion generation scope is independent");
        CityDecorationWorldgenRegistry.exitFeatureOriginForTests();

        CityDecorationWorldgenRegistry.resetForTests();
        assertTrue(CityDecorationWorldgenRegistry.enterFeatureOwnerForTests(
                generationScope, "minecraft:overworld", 7, 9),
                "registry reset must not retain a stale generation guard");
        CityDecorationWorldgenRegistry.exitFeatureOriginForTests();
    }

    @Test
    void featureInvocationGuardAtomicallyClaimsOneOwnerAcrossThreads() throws Exception {
        Object generationScope = new Object();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> claim = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                boolean claimed = CityDecorationWorldgenRegistry.enterFeatureOwnerForTests(
                        generationScope, "minecraft:overworld", 7, 9);
                CityDecorationWorldgenRegistry.exitFeatureOriginForTests();
                return claimed;
            };
            Future<Boolean> first = executor.submit(claim);
            Future<Boolean> second = executor.submit(claim);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int claims = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, claims, "concurrent callbacks must not duplicate an owner projection");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void defersSuccessfulOwnerLedgerPersistenceUntilControlledFlush(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                twoChunkPlan(catalog, "city_a", "field", 4, 4), serverRoot, catalogRoot);
        AtomicInteger ledgerWrites = new AtomicInteger();
        CityDecorationWorldgenRegistry.setLedgerPersistenceWriterForTests((path, object, reasonCode) -> {
            ledgerWrites.incrementAndGet();
            try {
                Files.writeString(path, CityJson.GSON.toJson(object));
            } catch (Exception ex) {
                throw new IllegalStateException(reasonCode, ex);
            }
        });

        CityDecorationWorldgenRegistry.ApplySummary applied = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, new FakePlacementWorld(true));

        assertEquals(2, applied.appliedFragmentCount());
        assertEquals(0, ledgerWrites.get(), "worldgen worker must not serialize the growing ledger");
        CityDecorationWorldgenRegistry.flushPendingLedgerNow();
        assertEquals(1, ledgerWrites.get(), "the controlled flush must persist one complete snapshot");
        JsonObject persisted = JsonParser.parseString(Files.readString(
                CityDecorationWorldgenRegistry.worldgenLedgerPath(serverRoot))).getAsJsonObject();
        assertEquals(2, persisted.getAsJsonArray("appliedFragments").size());
    }

    @Test
    void ledgerPersistenceFailureDoesNotEscapeWorldgenAndFlushesLater(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                twoChunkPlan(catalog, "city_a", "field", 4, 4), serverRoot, catalogRoot);
        CityDecorationWorldgenRegistry.setLedgerPersistenceWriterForTests((path, object, reasonCode) -> {
            throw new IllegalStateException(reasonCode + ": simulated lock");
        });
        FakePlacementWorld world = new FakePlacementWorld(true);

        CityDecorationWorldgenRegistry.ApplySummary applied = assertDoesNotThrow(() ->
                CityDecorationWorldgenRegistry.applyForChunk(
                        "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, world));

        assertEquals(2, applied.appliedFragmentCount());
        assertEquals(2, CityDecorationWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedFragments").size());
        assertEquals(2, world.placeCalls);
        CityDecorationWorldgenRegistry.ApplySummary reentered = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0, FlatTerrain.INSTANCE, world);
        assertEquals(2, reentered.alreadyAppliedFragmentCount());
        assertEquals(2, world.placeCalls, "in-memory ledger must keep placed blocks idempotent");

        CityDecorationWorldgenRegistry.resetLedgerPersistenceWriterForTests();
        JsonObject flushed = CityDecorationWorldgenRegistry.ledgerSnapshot();
        assertEquals(2, flushed.getAsJsonArray("appliedFragments").size());
        JsonObject persisted = JsonParser.parseString(Files.readString(
                CityDecorationWorldgenRegistry.worldgenLedgerPath(serverRoot))).getAsJsonObject();
        assertEquals(2, persisted.getAsJsonArray("appliedFragments").size());
    }

    @Test
    void concurrentOwnersPreserveBothLedgerEntries(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                twoChunkPlan(catalog, "city_a", "field", 15, 4), serverRoot, catalogRoot);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CityDecorationWorldgenRegistry.ApplySummary> west = executor.submit(
                    concurrentApply(0, ready, start));
            Future<CityDecorationWorldgenRegistry.ApplySummary> east = executor.submit(
                    concurrentApply(1, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            assertEquals(1, west.get(5, TimeUnit.SECONDS).appliedFragmentCount());
            assertEquals(1, east.get(5, TimeUnit.SECONDS).appliedFragmentCount());
        } finally {
            executor.shutdownNow();
        }
        CityDecorationWorldgenRegistry.flushPendingLedgerNow();
        JsonObject persisted = JsonParser.parseString(Files.readString(
                CityDecorationWorldgenRegistry.worldgenLedgerPath(serverRoot))).getAsJsonObject();
        assertEquals(2, persisted.getAsJsonArray("appliedFragments").size());
    }

    @Test
    void loadDiscardsCatalogChangedPlanAndPreservesLedger(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(catalog, "city_a", "program", 20L, 4, 4), serverRoot, catalogRoot);

        catalog(catalogRoot, "minecraft:cobblestone");
        CityDecorationWorldgenRegistry.resetForTests();
        CityDecorationWorldgenRegistry.load(serverRoot, catalogRoot);

        assertEquals(0, CityDecorationWorldgenRegistry.activeSummary()
                .get("activePlanCount").getAsInt());
        JsonObject active = JsonParser.parseString(Files.readString(
                CityDecorationWorldgenRegistry.activePlansPath(serverRoot))).getAsJsonObject();
        assertEquals(0, active.getAsJsonArray("plans").size());
        assertTrue(Files.isRegularFile(CityDecorationWorldgenRegistry.worldgenLedgerPath(serverRoot)));
    }

    @Test
    void loadRejectsStyleProfileChangedAfterActivation(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(catalog, "city_a", "program", 20L, 4, 4), serverRoot, catalogRoot);

        writeTestStyle(catalogRoot, 2.0);
        CityDecorationWorldgenRegistry.resetForTests();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CityDecorationWorldgenRegistry.load(serverRoot, catalogRoot))
                .getMessage().contains("STYLE_PROFILE_HASH_MISMATCH"));
    }

    @Test
    void activationPreflightRejectsOtherCityCatalogMismatchWithoutMutatingState(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog original = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(original, "city_a", "a", 21L, 4, 4), serverRoot, catalogRoot);
        CityDecorationContentCatalog changed = catalog(catalogRoot, "minecraft:cobblestone");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityDecorationWorldgenRegistry.preflightActivate("minecraft:overworld",
                        plan(changed, "city_b", "b", 22L, 8, 4), serverRoot, catalogRoot));

        assertTrue(failure.getMessage().contains("CATALOG_HASH_MISMATCH"));
        JsonObject summary = CityDecorationWorldgenRegistry.activeSummary();
        assertEquals(1, summary.get("activePlanCount").getAsInt());
        assertEquals("city_a", summary.getAsJsonArray("activeKeys").get(0).getAsJsonObject()
                .get("cityId").getAsString());
    }

    @Test
    void deactivateRemovesTargetWithoutRequiringItsOldCatalog(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(catalog, "city_a", "program", 30L, 4, 4), serverRoot, catalogRoot);
        Files.delete(catalogRoot.resolve("content_index.json"));

        JsonObject summary = CityDecorationWorldgenRegistry.deactivate(
                "minecraft:overworld", "city_a", serverRoot, null);

        assertEquals(0, summary.get("activePlanCount").getAsInt());
        JsonObject persisted = JsonParser.parseString(Files.readString(
                CityDecorationWorldgenRegistry.activePlansPath(serverRoot))).getAsJsonObject();
        assertEquals(0, persisted.getAsJsonArray("plans").size());
    }

    @Test
    void deactivateOneCityRetainsOtherCities(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(catalog, "city_a", "a", 31L, 4, 4), serverRoot, catalogRoot);
        CityDecorationWorldgenRegistry.activate("minecraft:overworld",
                plan(catalog, "city_b", "b", 32L, 8, 4), serverRoot, catalogRoot);

        JsonObject summary = CityDecorationWorldgenRegistry.deactivate(
                "minecraft:overworld", "city_a", serverRoot, null);

        assertEquals(1, summary.get("activePlanCount").getAsInt());
        JsonObject remaining = summary.getAsJsonArray("activeKeys").get(0).getAsJsonObject();
        assertEquals("city_b", remaining.get("cityId").getAsString());
        JsonObject persisted = JsonParser.parseString(Files.readString(
                CityDecorationWorldgenRegistry.activePlansPath(serverRoot))).getAsJsonObject();
        assertEquals(1, persisted.getAsJsonArray("plans").size());
        assertEquals("city_b", persisted.getAsJsonArray("plans").get(0).getAsJsonObject()
                .get("cityId").getAsString());
    }

    @Test
    void persistsAndFiltersImmutableFoundationSnapshotsByDimensionAndChunk(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CompiledDecorationProgramPlan plan = foundationContinuousPlan(
                twoChunkPlan(catalog, "city_a", "program", 15, 4));
        CityDecorationTerrainRunCompiler.FrozenPlan frozen = new CityDecorationTerrainRunCompiler().compile(
                plan, catalog, (x, z) -> new CityDecorationTerrainRunCompiler.TerrainSample(
                        x == 15 ? 68 : 70, false, true));
        CityDecorationTerrainRunCompiler.FoundationSegment segment = frozen.foundationSegments().get(0);

        CityDecorationWorldgenRegistry.activate("minecraft:overworld", plan, frozen, serverRoot, catalogRoot);

        List<CityDecorationTerrainRunCompiler.FoundationSegment> west =
                CityDecorationWorldgenRegistry.foundationSegmentsForChunk(
                        "minecraft:overworld", new ChunkPos(0, 0));
        assertEquals(List.of(segment), west);
        assertEquals(List.of(segment), CityDecorationWorldgenRegistry.foundationSegmentsForChunk(
                "minecraft:overworld", new ChunkPos(1, 0)));
        assertTrue(CityDecorationWorldgenRegistry.foundationSegmentsForChunk(
                "minecraft:overworld", new ChunkPos(3, 0)).isEmpty());
        assertTrue(CityDecorationWorldgenRegistry.foundationSegmentsForChunk(
                "minecraft:the_nether", new ChunkPos(0, 0)).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> west.add(segment));

        CityDecorationWorldgenRegistry.ApplySummary summary = CityDecorationWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                (x, z) -> new CityDecorationChunkCompiler.TerrainSample(
                        x == 15 ? 68 : 70, Set.of("minecraft:grass_block"), false),
                new FakePlacementWorld(true));
        assertEquals(1, summary.skippedFragmentCount());
        JsonObject outcome = CityDecorationWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("fragmentOutcomes").get(0).getAsJsonObject();
        assertEquals("skipped", outcome.get("status").getAsString());
        assertEquals("CITY_DECORATION_FOUNDATION_NOT_MATERIALIZED",
                outcome.get("reasonCode").getAsString());
        assertTrue(outcome.get("foundationPlanned").getAsBoolean());
        assertFalse(outcome.get("foundationMaterialized").getAsBoolean());
        assertFalse(outcome.get("foundationApplied").getAsBoolean());

        CityDecorationWorldgenRegistry.resetForTests();
        CityDecorationWorldgenRegistry.load(serverRoot, catalogRoot);
        assertEquals(List.of(segment), CityDecorationWorldgenRegistry.foundationSegmentsForChunk(
                "minecraft:overworld", new ChunkPos(0, 0)));
        JsonObject persisted = JsonParser.parseString(Files.readString(
                CityDecorationWorldgenRegistry.activePlansPath(serverRoot))).getAsJsonObject();
        assertEquals(1, persisted.getAsJsonArray("plans").get(0).getAsJsonObject()
                .getAsJsonObject("frozenTerrainPlan").getAsJsonArray("foundationSegments").size());
    }

    @Test
    void continuousPlansCannotBypassFrozenTerrainActivation(@TempDir Path temp) throws Exception {
        Path catalogRoot = temp.resolve("catalog");
        Path serverRoot = temp.resolve("server");
        CityDecorationContentCatalog catalog = catalog(catalogRoot, "minecraft:stone");
        CompiledDecorationProgramPlan base = plan(catalog, "city_a", "channel", 41L, 4, 4);
        CompiledDecorationProgramPlan continuousPlan = continuousPlan(base);

        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                CityDecorationWorldgenRegistry.activate("minecraft:overworld", continuousPlan,
                        serverRoot, catalogRoot)).getMessage().contains("FROZEN_TERRAIN_PLAN_REQUIRED"));
        CityDecorationTerrainRunCompiler.FrozenPlan empty =
                new CityDecorationTerrainRunCompiler.FrozenPlan(CityDecorationTerrainRunCompiler.SCHEMA,
                        continuousPlan.cityId(), continuousPlan.catalogHash(), List.of(), List.of());
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                CityDecorationWorldgenRegistry.activate("minecraft:overworld", continuousPlan, empty,
                        serverRoot, catalogRoot)).getMessage().contains("FROZEN_TERRAIN_SLOT_MISSING"));
    }

    @Test
    void loadingObsoleteActivePlanSchemaIsQuarantined(@TempDir Path temp)
            throws Exception {
        Path serverRoot = temp.resolve("server");
        JsonObject active = new JsonObject();
        active.addProperty("schema", "obsolete_city_active_decoration_program_plans");
        active.add("plans", new JsonArray());
        Path activePath = CityDecorationWorldgenRegistry.activePlansPath(serverRoot);
        Files.createDirectories(activePath.getParent());
        Files.writeString(activePath, CityJson.GSON.toJson(active));

        CityDecorationWorldgenRegistry.load(serverRoot, temp.resolve("catalog"));
        JsonObject current = JsonParser.parseString(Files.readString(activePath)).getAsJsonObject();
        assertEquals(CityDecorationWorldgenRegistry.ACTIVE_SCHEMA, current.get("schema").getAsString());
        assertEquals(0, current.getAsJsonArray("plans").size());
        try (var files = Files.list(activePath.getParent())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().contains(".obsolete-")));
        }
    }

    private static int appliedCount() {
        return CityDecorationWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedFragments").size();
    }

    private static Callable<CityDecorationWorldgenRegistry.ApplySummary> concurrentApply(int chunkX,
                                                                                           CountDownLatch ready,
                                                                                           CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return CityDecorationWorldgenRegistry.applyForChunk("minecraft:overworld", chunkX, 0,
                    FlatTerrain.INSTANCE, new FakePlacementWorld(true));
        };
    }

    private static CompiledDecorationProgramPlan plan(CityDecorationContentCatalog catalog,
                                                       String cityId,
                                                       String programId,
                                                       long seed,
                                                       int x,
                                                       int z) {
        CompiledDecorationProgram program = new CompiledDecorationProgram(
                CompiledDecorationProgram.SCHEMA, programId, 1, seed,
                new CompiledDecorationProgram.TargetMask(programId + "_mask",
                        List.of(new BlockBounds(x, z, x, z))),
                new CompiledDecorationProgram.CoordinateFrame(BlockPoint.ORIGIN,
                        new CompiledDecorationProgram.Vector2(1, 0),
                        new CompiledDecorationProgram.Vector2(0, 1)),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.GridRepeatPattern("item", 1, 1, 0, 0),
                new CompiledDecorationProgram.ContentPalette(List.of(
                        new CompiledDecorationProgram.PaletteSlot("item", CompiledDecorationProgram.Phase.MAJOR,
                                List.of(new CompiledDecorationProgram.ContentEntry("city:prefab/test", 1.0)), true))),
                new CompiledDecorationProgram.TerrainPolicy(1, false,
                        CompiledDecorationProgram.InvalidTerrainAction.SKIP),
                new CompiledDecorationProgram.ConflictPolicy(CompiledDecorationProgram.ConflictAction.SKIP, 0));
        CityDecorationStyleProfileCatalog.StyleProfile profile = new CityDecorationStyleProfileCatalogLoader()
                .load(catalog.catalogRoot(), catalog).requireProfile("test_style");
        return new CompiledDecorationProgramPlan(CompiledDecorationProgramPlan.SCHEMA,
                cityId, catalog.catalogHash(), profile.styleProfileId(), profile.styleProfileHash(),
                List.of(), List.of(program));
    }

    private static CompiledDecorationProgramPlan layeredPlan(CityDecorationContentCatalog catalog) {
        CompiledDecorationProgram program = new CompiledDecorationProgram(
                CompiledDecorationProgram.SCHEMA, "layered_field", 1, 78L,
                new CompiledDecorationProgram.TargetMask("layered_mask",
                        List.of(new BlockBounds(4, 4, 4, 4))),
                new CompiledDecorationProgram.CoordinateFrame(BlockPoint.ORIGIN,
                        new CompiledDecorationProgram.Vector2(1, 0),
                        new CompiledDecorationProgram.Vector2(0, 1)),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.GridRepeatPattern("item", 1, 1, 0, 0),
                new CompiledDecorationProgram.ContentPalette(List.of(
                        new CompiledDecorationProgram.PaletteSlot("item", List.of(
                                new CompiledDecorationProgram.ContentLayer("base",
                                        CompiledDecorationProgram.Phase.SURFACE,
                                        List.of(new CompiledDecorationProgram.ContentEntry(
                                                "city:prefab/farmland", 1.0)), true, null),
                                new CompiledDecorationProgram.ContentLayer("plant",
                                        CompiledDecorationProgram.Phase.MINOR,
                                        List.of(new CompiledDecorationProgram.ContentEntry(
                                                "city:plant/wheat", 1.0)), true, "base"))))),
                new CompiledDecorationProgram.TerrainPolicy(1, false,
                        CompiledDecorationProgram.InvalidTerrainAction.SKIP),
                new CompiledDecorationProgram.ConflictPolicy(
                        CompiledDecorationProgram.ConflictAction.SKIP, 0));
        CityDecorationStyleProfileCatalog.StyleProfile profile = new CityDecorationStyleProfileCatalogLoader()
                .load(catalog.catalogRoot(), catalog).requireProfile("test_style");
        return new CompiledDecorationProgramPlan(CompiledDecorationProgramPlan.SCHEMA,
                "layered_city", catalog.catalogHash(), profile.styleProfileId(), profile.styleProfileHash(),
                List.of(), List.of(program));
    }

    private static CompiledDecorationProgramPlan twoChunkPlan(CityDecorationContentCatalog catalog,
                                                               String cityId,
                                                               String programId,
                                                               int minX,
                                                               int z) {
        CompiledDecorationProgram program = new CompiledDecorationProgram(
                CompiledDecorationProgram.SCHEMA, programId, 1, 77L,
                new CompiledDecorationProgram.TargetMask(programId + "_mask",
                        List.of(new BlockBounds(minX, z, minX + 1, z))),
                new CompiledDecorationProgram.CoordinateFrame(BlockPoint.ORIGIN,
                        new CompiledDecorationProgram.Vector2(1, 0),
                        new CompiledDecorationProgram.Vector2(0, 1)),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.GridRepeatPattern("item", 1, 1, 0, 0),
                new CompiledDecorationProgram.ContentPalette(List.of(
                        new CompiledDecorationProgram.PaletteSlot("item", CompiledDecorationProgram.Phase.MAJOR,
                                List.of(new CompiledDecorationProgram.ContentEntry("city:prefab/test", 1.0)), true))),
                new CompiledDecorationProgram.TerrainPolicy(1, false,
                        CompiledDecorationProgram.InvalidTerrainAction.SKIP),
                new CompiledDecorationProgram.ConflictPolicy(CompiledDecorationProgram.ConflictAction.SKIP, 0));
        CityDecorationStyleProfileCatalog.StyleProfile profile = new CityDecorationStyleProfileCatalogLoader()
                .load(catalog.catalogRoot(), catalog).requireProfile("test_style");
        return new CompiledDecorationProgramPlan(CompiledDecorationProgramPlan.SCHEMA,
                cityId, catalog.catalogHash(), profile.styleProfileId(), profile.styleProfileHash(),
                List.of(), List.of(program));
    }

    private static CompiledDecorationProgramPlan continuousPlan(CompiledDecorationProgramPlan base) {
        CompiledDecorationProgram source = base.programs().get(0);
        CompiledDecorationProgram continuous = new CompiledDecorationProgram(source.schema(),
                source.programId(), source.priority(), source.seed(), source.targetMask(), source.coordinateFrame(),
                source.shape(), new CompiledDecorationProgram.CrossSectionRepeatPattern(
                CompiledDecorationProgram.Axis.V, 0,
                List.of(new CompiledDecorationProgram.CrossSectionBand("item", 1))),
                source.contentPalette(), source.terrainPolicy(), source.conflictPolicy());
        return new CompiledDecorationProgramPlan(base.schema(), base.cityId(), base.catalogHash(),
                base.styleProfileId(), base.styleProfileHash(), base.hardObstacles(), List.of(continuous));
    }

    private static CompiledDecorationProgramPlan foundationContinuousPlan(CompiledDecorationProgramPlan base) {
        CompiledDecorationProgram source = base.programs().get(0);
        CompiledDecorationProgram continuous = new CompiledDecorationProgram(source.schema(),
                source.programId(), source.priority(), source.seed(), source.targetMask(), source.coordinateFrame(),
                source.shape(), new CompiledDecorationProgram.ParallelRowsPattern(
                CompiledDecorationProgram.Axis.U, "item", 1, 1, 0), source.contentPalette(),
                new CompiledDecorationProgram.TerrainPolicy(20, false,
                        CompiledDecorationProgram.InvalidTerrainAction.CLIP, 100, 8,
                        CompiledDecorationProgram.FoundationMode.FILL_ONLY, 4, 2),
                source.conflictPolicy());
        return new CompiledDecorationProgramPlan(base.schema(), base.cityId(), base.catalogHash(),
                base.styleProfileId(), base.styleProfileHash(), base.hardObstacles(), List.of(continuous));
    }

    private static CityDecorationContentCatalog catalog(Path root, String blockName) throws Exception {
        Files.createDirectories(root.resolve("templates"));
        CompoundTag template = new CompoundTag();
        template.put("size", ints(1, 1, 1));
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockName);
        ListTag palette = new ListTag();
        palette.add(state);
        template.put("palette", palette);
        CompoundTag block = new CompoundTag();
        block.put("pos", ints(0, 0, 0));
        block.putInt("state", 0);
        ListTag blocks = new ListTag();
        blocks.add(block);
        template.put("blocks", blocks);
        template.put("entities", new ListTag());
        NbtIo.writeCompressed(template, root.resolve("templates/test.nbt").toFile());

        JsonObject index = new JsonObject();
        index.addProperty("schema", CityDecorationContentCatalog.SCHEMA);
        JsonObject content = new JsonObject();
        content.addProperty("contentId", "city:prefab/test");
        content.addProperty("contentKind", "prefab");
        content.addProperty("nbtFile", "templates/test.nbt");
        content.addProperty("comfortMarginBlocks", 0);
        content.addProperty("groundPlaneLocalY", 0);
        content.addProperty("embedDepthBlocks", 0);
        content.addProperty("clearanceMode", "preserve");
        JsonArray contents = new JsonArray();
        contents.add(content);
        index.add("contents", contents);
        Files.writeString(root.resolve("content_index.json"), CityJson.GSON.toJson(index));
        writeTestStyle(root, 1.0);
        return new CityDecorationContentCatalogLoader().load(root);
    }

    private static void writeTestStyle(Path root, double weight) throws Exception {
        Path styles = root.resolve("styles");
        Files.createDirectories(styles);
        Files.writeString(styles.resolve("test_style.json"), """
                {
                  "schema": "city_decoration_style_profile",
                  "styleProfileId": "test_style",
                  "mappings": [
                    {
                      "semanticRef": "test_marker",
                      "variants": [
                        {"contentRef": "city:prefab/test", "weight": %s}
                      ]
                    }
                  ]
                }
                """.formatted(weight));
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }

    private enum FlatTerrain implements CityDecorationChunkCompiler.TerrainView {
        INSTANCE;

        @Override
        public CityDecorationChunkCompiler.TerrainSample sample(int worldX, int worldZ) {
            return new CityDecorationChunkCompiler.TerrainSample(64, Set.of("grass"), false);
        }
    }

    private static final class FakePlacementWorld implements CityDecorationNbtPlacer.PlacementWorld {
        private boolean succeed;
        private int placeCalls;
        private int deniedAtOrAfterX = Integer.MAX_VALUE;
        private final List<BlockPos> placementOrigins = new java.util.ArrayList<>();
        private final List<String> placementOrder = new java.util.ArrayList<>();
        private boolean failPlant;
        private int plantPlaceCalls;

        private FakePlacementWorld(boolean succeed) {
            this.succeed = succeed;
        }

        private FakePlacementWorld denyAtOrAfterX(int worldX) {
            deniedAtOrAfterX = worldX;
            return this;
        }

        @Override
        public boolean ensureCanWrite(BlockPos pos) {
            return pos.getX() < deniedAtOrAfterX;
        }

        @Override
        public CityDecorationNbtPlacer.ExistingTarget inspect(BlockPos pos) {
            return new CityDecorationNbtPlacer.ExistingTarget(true, true);
        }

        @Override
        public Object snapshot(BlockPos pos) {
            return inspect(pos);
        }

        @Override
        public boolean restore(BlockPos pos, Object snapshot) {
            return true;
        }

        @Override
        public boolean placeTemplate(CompoundTag templateNbt, BlockPos origin, Rotation rotation, long seed,
                                     boolean ignoreTemplateAir, BoundingBox ownerBounds) {
            placeCalls++;
            placementOrigins.add(origin);
            placementOrder.add("base");
            return succeed;
        }

        @Override
        public CityDecorationNbtPlacer.PlantTarget inspectPlant(CompoundTag blockStateNbt, BlockPos pos,
                                                                Rotation rotation) {
            return new CityDecorationNbtPlacer.PlantTarget(true, false, placeCalls > 0);
        }

        @Override
        public boolean placePlant(CompoundTag blockStateNbt, BlockPos pos, Rotation rotation) {
            plantPlaceCalls++;
            placementOrder.add("plant");
            return !failPlant;
        }
    }
}
