package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationChunkCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalogLoader;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationStyleProfileCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationStyleProfileCatalogLoader;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Rotation;
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
        assertEquals(CityDecorationWorldgenRegistry.ACTIVE_SCHEMA, active.get("schemaVersion").getAsString());
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
    void batchesSuccessfulOwnerFragmentsIntoOneLedgerPersistence(@TempDir Path temp) throws Exception {
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
        assertEquals(1, ledgerWrites.get(), "one owner callback must persist its complete batch once");
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
        index.addProperty("schemaVersion", CityDecorationContentCatalog.SCHEMA);
        JsonObject content = new JsonObject();
        content.addProperty("contentId", "city:prefab/test");
        content.addProperty("contentKind", "prefab");
        content.addProperty("nbtFile", "templates/test.nbt");
        content.addProperty("comfortMarginBlocks", 0);
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
                  "schemaVersion": "city_decoration_style_profile.v0.1",
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
        public boolean placeTemplate(CompoundTag templateNbt, BlockPos origin, Rotation rotation, long seed) {
            placeCalls++;
            placementOrigins.add(origin);
            return succeed;
        }
    }
}
