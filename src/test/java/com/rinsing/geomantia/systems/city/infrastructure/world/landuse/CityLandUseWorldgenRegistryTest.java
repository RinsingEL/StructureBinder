package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfaceRunCompiler;
import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.TestDecorationCatalogs;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityNbtPrefabBatchPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertFalse(world.restores.isEmpty());
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

    @Test
    void surfacePrintActivationPersistsV02AndStrictlyReloadsCatalog(@TempDir Path temp) throws Exception {
        Path server = temp.resolve("server");
        CityDecorationContentCatalog catalog = TestDecorationCatalogs.loadManagedDefault(
                temp.resolve("city_decoration"));
        LandUseAreaPlan areaPlan = CityLandUseChunkCompilerTest.plan("city_surface");
        CityLandUseSurfacePrintPlan surfacePlan = surfacePlan(areaPlan, catalog.catalogHash(),
                areaPlan.planHash());

        JsonObject summary = CityLandUseWorldgenRegistry.activate(
                "minecraft:overworld", areaPlan, surfacePlan, catalog, server);

        assertEquals(1, summary.get("surfacePrintPlanCount").getAsInt());
        assertEquals(0, summary.get("legacyPalettePlanCount").getAsInt());

        JsonObject root = JsonParser.parseString(Files.readString(
                CityLandUseWorldgenRegistry.activePlansPath(server))).getAsJsonObject();
        JsonObject entry = root.getAsJsonArray("plans").get(0).getAsJsonObject();
        assertEquals("city_active_land_use_area_plans.v0.2", root.get("schemaVersion").getAsString());
        assertEquals("surface_print_plan", entry.get("surfaceMode").getAsString());
        assertEquals(surfacePlan.planHash(), entry.get("surfacePrintPlanHash").getAsString());
        assertEquals(surfacePlan.catalogHash(), entry.get("catalogHash").getAsString());
        assertEquals(catalog.catalogRoot().toAbsolutePath().normalize().toString(),
                entry.get("catalogRoot").getAsString());
        assertEquals(new CityLandUseSurfacePrintPlanCodec().toJson(surfacePlan),
                entry.getAsJsonObject("surfacePrintPlan"));

        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.setCatalogReaderForTests(ignored -> catalog);
        CityLandUseWorldgenRegistry.load(server);
        assertTrue(CityLandUseWorldgenRegistry.suppressesVegetation(
                "minecraft:overworld", 0, 0));
    }

    @Test
    void loadingSurfacePrintActivationRejectsCatalogDrift(@TempDir Path temp) throws Exception {
        Path server = temp.resolve("server");
        Path catalogRoot = temp.resolve("city_decoration");
        CityDecorationContentCatalog catalog = TestDecorationCatalogs.loadManagedDefault(catalogRoot);
        LandUseAreaPlan areaPlan = CityLandUseChunkCompilerTest.plan("city_surface");
        CityLandUseSurfacePrintPlan surfacePlan = surfacePlan(areaPlan, catalog.catalogHash(),
                areaPlan.planHash());
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", areaPlan, surfacePlan, catalog, server);

        JsonObject index = JsonParser.parseString(Files.readString(
                catalogRoot.resolve("content_index.json"))).getAsJsonObject();
        index.getAsJsonArray("contents").get(0).getAsJsonObject()
                .getAsJsonArray("tags").add("catalog_drift");
        Files.writeString(catalogRoot.resolve("content_index.json"), index.toString());
        CityDecorationContentCatalog drifted = TestDecorationCatalogs.loadManagedDefault(catalogRoot);
        assertNotEquals(catalog.catalogHash(), drifted.catalogHash());

        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.setCatalogReaderForTests(ignored -> drifted);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityLandUseWorldgenRegistry.load(server));
        assertTrue(failure.getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_CATALOG_HASH_MISMATCH"));
    }

    @Test
    void activationAndPreflightRejectSurfaceSourceMismatch(@TempDir Path temp) throws Exception {
        Path server = temp.resolve("server");
        CityDecorationContentCatalog catalog = TestDecorationCatalogs.loadManagedDefault(
                temp.resolve("city_decoration"));
        LandUseAreaPlan areaPlan = CityLandUseChunkCompilerTest.plan("city_surface");
        CityLandUseSurfacePrintPlan wrongSource = surfacePlan(areaPlan, catalog.catalogHash(),
                "wrong-area-plan-hash");

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CityLandUseWorldgenRegistry.activate(
                        "minecraft:overworld", areaPlan, wrongSource, catalog, server))
                .getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_SOURCE_HASH_MISMATCH"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CityLandUseWorldgenRegistry.preflightActivate(
                        "minecraft:overworld", areaPlan, wrongSource, catalog, server))
                .getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_SOURCE_HASH_MISMATCH"));
    }

    @Test
    void loadsV01AsLegacyAndRewritesExplicitLegacyMode(@TempDir Path temp) throws Exception {
        Path server = temp.resolve("server");
        LandUseAreaPlan plan = CityLandUseChunkCompilerTest.plan("city_legacy");
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", plan, server);
        Path activePath = CityLandUseWorldgenRegistry.activePlansPath(server);
        JsonObject legacy = JsonParser.parseString(Files.readString(activePath)).getAsJsonObject();
        legacy.addProperty("schemaVersion", CityLandUseWorldgenRegistry.LEGACY_ACTIVE_SCHEMA);
        legacy.getAsJsonArray("plans").get(0).getAsJsonObject().remove("surfaceMode");
        Files.writeString(activePath, legacy.toString());

        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.load(server);
        JsonObject summary = CityLandUseWorldgenRegistry.activate(
                "minecraft:the_nether", plan, server);

        assertEquals(2, summary.get("activePlanCount").getAsInt());
        JsonObject rewritten = JsonParser.parseString(Files.readString(activePath)).getAsJsonObject();
        assertEquals(CityLandUseWorldgenRegistry.ACTIVE_SCHEMA,
                rewritten.get("schemaVersion").getAsString());
        assertTrue(rewritten.getAsJsonArray("plans").asList().stream()
                .allMatch(value -> "legacy_palette".equals(value.getAsJsonObject()
                        .get("surfaceMode").getAsString())));
        assertTrue(rewritten.getAsJsonArray("plans").asList().stream()
                .noneMatch(value -> value.getAsJsonObject().has("surfacePrintPlan")));
    }

    @Test
    void appliesSurfaceOwnerAsUnifiedBlockAndPrefabTransactionAndRecordsPhaseCounts(
            @TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());
        FakeWorld blockWorld = new FakeWorld();
        FakePrefabWorld prefabWorld = new FakePrefabWorld();

        CityLandUseWorldgenRegistry.ApplySummary summary = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                blockWorld, prefabWorld, -64, 319);

        assertEquals(1, summary.appliedOwnerCount());
        assertTrue(blockWorld.writes.size() > 0);
        assertEquals(1, prefabWorld.placeCalls);
        assertEquals(66, prefabWorld.anchors.get(0).getY());
        JsonObject ledger = CityLandUseWorldgenRegistry.ledgerSnapshot();
        assertEquals(CityLandUseWorldgenRegistry.LEDGER_SCHEMA,
                ledger.get("schemaVersion").getAsString());
        JsonObject owner = ledger.getAsJsonArray("appliedOwners").get(0).getAsJsonObject();
        assertEquals("surface_print_plan", owner.get("surfaceMode").getAsString());
        assertEquals(activation.surfacePlan().planHash(),
                owner.get("surfacePrintPlanHash").getAsString());
        assertTrue(owner.get("preparedBaseOperationCount").getAsInt() > 0);
        assertEquals(owner.get("preparedBaseOperationCount").getAsInt(),
                owner.get("appliedBaseOperationCount").getAsInt());
        assertTrue(owner.get("preparedCropOperationCount").getAsInt() > 0);
        assertEquals(owner.get("preparedCropOperationCount").getAsInt(),
                owner.get("appliedCropOperationCount").getAsInt());
        assertTrue(owner.get("preparedBoundaryOperationCount").getAsInt() > 0);
        assertEquals(owner.get("preparedBoundaryOperationCount").getAsInt(),
                owner.get("appliedBoundaryOperationCount").getAsInt());
        assertEquals(1, owner.get("preparedPrefabPlacementCount").getAsInt());
        assertEquals(1, owner.get("appliedPrefabPlacementCount").getAsInt());
        assertEquals("MATERIALIZE", ledger.getAsJsonArray("placementDecisions").get(0)
                .getAsJsonObject().get("status").getAsString());
    }

    @Test
    void freezesCrossOwnerFallbackBeforeFirstWriteAndReusesItAfterRestart(
            @TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());
        FakeWorld westBlocks = new FakeWorld();
        FakePrefabWorld rejectingWorld = new FakePrefabWorld();
        rejectingWorld.rejectReplacePolicy = true;

        CityLandUseWorldgenRegistry.ApplySummary west = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                westBlocks, rejectingWorld, -64, 319);

        assertEquals(1, west.appliedOwnerCount());
        assertEquals(0, rejectingWorld.placeCalls);
        assertTrue(westBlocks.writes.stream().anyMatch(value -> value.contains("minecraft:wheat")));
        JsonObject firstLedger = CityLandUseWorldgenRegistry.ledgerSnapshot();
        assertEquals(1, firstLedger.getAsJsonArray("placementDecisions").size());
        JsonObject decision = firstLedger.getAsJsonArray("placementDecisions").get(0).getAsJsonObject();
        assertEquals("FALLBACK", decision.get("status").getAsString());
        assertEquals("CITY_NBT_PREFAB_REPLACE_POLICY_REJECTED",
                decision.get("reasonCode").getAsString());
        assertTrue(decision.has("contentHash"));
        assertTrue(decision.has("resolvedTargetY"));

        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.setCatalogReaderForTests(ignored -> activation.catalog());
        CityLandUseWorldgenRegistry.load(activation.server());
        FakeWorld eastBlocks = new FakeWorld();
        FakePrefabWorld nowReplaceable = new FakePrefabWorld();

        CityLandUseWorldgenRegistry.ApplySummary east = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 1, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                eastBlocks, nowReplaceable, -64, 319);

        assertEquals(1, east.appliedOwnerCount());
        assertEquals(0, nowReplaceable.placeCalls,
                "the second owner must consume the frozen fallback instead of re-deciding");
        assertTrue(eastBlocks.writes.stream().anyMatch(value -> value.contains("minecraft:wheat")));
        assertTrue(eastBlocks.writes.contains("16,65,0=minecraft:oak_fence"),
                "fallback must restore a boundary previously covered by the prefab footprint");
        JsonObject reloaded = CityLandUseWorldgenRegistry.ledgerSnapshot();
        assertEquals(1, reloaded.getAsJsonArray("placementDecisions").size());
        assertEquals(2, reloaded.getAsJsonArray("appliedOwners").size());
        assertTrue(reloaded.getAsJsonArray("appliedOwners").asList().stream()
                .allMatch(value -> "skipped_content".equals(value.getAsJsonObject()
                        .getAsJsonArray("prefabPlacementOutcomes").get(0)
                        .getAsJsonObject().get("status").getAsString())));
    }

    @Test
    void blocksFirstOwnerWriteWhenPlacementDecisionCannotBePersisted(
            @TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());
        JsonObject ledger = CityLandUseWorldgenRegistry.ledgerSnapshot();
        ledger.getAsJsonArray("placementDatums").add(datum(activation.surfacePlan().planHash(),
                "area_plaza/surface/0_0/farm/surface/place", 66));
        Files.writeString(CityLandUseWorldgenRegistry.worldgenLedgerPath(activation.server()),
                ledger.toString());
        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.setCatalogReaderForTests(ignored -> activation.catalog());
        CityLandUseWorldgenRegistry.load(activation.server());
        CityLandUseWorldgenRegistry.setLedgerPersistenceWriterForTests((path, value, reason) -> {
            throw new IllegalStateException("disk unavailable");
        });
        FakeWorld blocks = new FakeWorld();
        FakePrefabWorld prefabs = new FakePrefabWorld();

        CityLandUseWorldgenRegistry.ApplySummary summary = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                blocks, prefabs, -64, 319);

        assertEquals(1, summary.failedOwnerCount());
        assertTrue(blocks.writes.isEmpty());
        assertEquals(0, prefabs.placeCalls);
        assertTrue(CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("placementDecisions").isEmpty());
    }

    @Test
    void frozenMaterializeTreatsLaterOwnerStateDriftAsHardFailure(
            @TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());
        CityLandUseWorldgenRegistry.applyForChunk("minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                new FakeWorld(), new FakePrefabWorld(), -64, 319);
        FakeWorld eastBlocks = new FakeWorld();
        FakePrefabWorld changed = new FakePrefabWorld();
        changed.rejectReplacePolicy = true;

        CityLandUseWorldgenRegistry.ApplySummary east = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 1, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                eastBlocks, changed, -64, 319);

        assertEquals(1, east.failedOwnerCount());
        assertTrue(eastBlocks.writes.isEmpty());
        assertEquals("MATERIALIZE", CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("placementDecisions").get(0).getAsJsonObject()
                .get("status").getAsString());
    }

    @Test
    void migratesV2PartiallyAppliedPlacementToMaterializeBeforeRemainingOwnerRuns(
            @TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());
        CityLandUseWorldgenRegistry.applyForChunk("minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                new FakeWorld(), new FakePrefabWorld(), -64, 319);
        JsonObject legacyV2 = CityLandUseWorldgenRegistry.ledgerSnapshot();
        legacyV2.addProperty("schemaVersion", CityLandUseWorldgenRegistry.V2_LEDGER_SCHEMA);
        legacyV2.remove("placementDecisions");
        Files.writeString(CityLandUseWorldgenRegistry.worldgenLedgerPath(activation.server()),
                legacyV2.toString());

        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.setCatalogReaderForTests(ignored -> activation.catalog());
        CityLandUseWorldgenRegistry.load(activation.server());

        JsonObject migrated = CityLandUseWorldgenRegistry.ledgerSnapshot();
        assertEquals(CityLandUseWorldgenRegistry.LEDGER_SCHEMA,
                migrated.get("schemaVersion").getAsString());
        assertEquals(1, migrated.getAsJsonArray("placementDecisions").size());
        JsonObject decision = migrated.getAsJsonArray("placementDecisions").get(0).getAsJsonObject();
        assertEquals("MATERIALIZE", decision.get("status").getAsString());
        assertEquals("CITY_LAND_USE_PLACEMENT_MATERIALIZE_INFERRED_FROM_V2_APPLIED_OWNER",
                decision.get("reasonCode").getAsString());
        JsonObject persisted = JsonParser.parseString(Files.readString(
                CityLandUseWorldgenRegistry.worldgenLedgerPath(activation.server()))).getAsJsonObject();
        assertEquals(CityLandUseWorldgenRegistry.LEDGER_SCHEMA,
                persisted.get("schemaVersion").getAsString());

        FakeWorld eastBlocks = new FakeWorld();
        FakePrefabWorld changed = new FakePrefabWorld();
        changed.rejectReplacePolicy = true;
        CityLandUseWorldgenRegistry.ApplySummary east = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 1, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                eastBlocks, changed, -64, 319);

        assertEquals(1, east.failedOwnerCount());
        assertTrue(eastBlocks.writes.isEmpty(),
                "legacy half-materialized placement must not be reclassified to fallback");
    }

    @Test
    void surfaceOwnerIsIdempotentAfterRegistryRestart(@TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());
        CityLandUseWorldgenRegistry.applyForChunk("minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                new FakeWorld(), new FakePrefabWorld(), -64, 319);

        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.setCatalogReaderForTests(ignored -> activation.catalog());
        CityLandUseWorldgenRegistry.load(activation.server());
        FakeWorld blockWorld = new FakeWorld();
        FakePrefabWorld prefabWorld = new FakePrefabWorld();
        CityLandUseWorldgenRegistry.ApplySummary repeated = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                blockWorld, prefabWorld, -64, 319);

        assertEquals(1, repeated.alreadyAppliedOwnerCount());
        assertTrue(blockWorld.writes.isEmpty());
        assertEquals(0, prefabWorld.placeCalls);
        assertEquals(1, CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").size());
    }

    @Test
    void crossOwnerPrefabReusesPersistedLiveDatumAfterRestart(@TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());
        FakeWorld westWorld = new FakeWorld();
        westWorld.surfaceY = 64;
        FakePrefabWorld westPrefab = new FakePrefabWorld();
        CityLandUseWorldgenRegistry.applyForChunk("minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                westWorld, westPrefab, -64, 319);
        assertEquals(66, westPrefab.anchors.get(0).getY());
        assertEquals(1, CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("placementDatums").size());

        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.setCatalogReaderForTests(ignored -> activation.catalog());
        CityLandUseWorldgenRegistry.load(activation.server());
        FakeWorld eastWorld = new FakeWorld();
        eastWorld.surfaceY = 100;
        FakePrefabWorld eastPrefab = new FakePrefabWorld();
        CityLandUseWorldgenRegistry.ApplySummary east = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 1, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                eastWorld, eastPrefab, -64, 319);

        assertEquals(1, east.appliedOwnerCount());
        assertEquals(66, eastPrefab.anchors.get(0).getY());
        JsonObject datum = CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("placementDatums").get(0).getAsJsonObject();
        assertEquals(64, datum.get("liveSurfaceY").getAsInt());
        assertEquals(70, datum.get("coarseSurfaceY").getAsInt());
        assertEquals(72, datum.get("coarseTargetY").getAsInt());
        assertEquals(66, datum.get("resolvedTargetY").getAsInt());
    }

    @Test
    void oldApplyOverloadRejectsSurfaceActivePlan(@TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityLandUseWorldgenRegistry.applyForChunk("minecraft:overworld", 0, 0,
                        CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                        new FakeWorld()));

        assertTrue(failure.getMessage().contains("CITY_LAND_USE_SURFACE_PREFAB_WORLD_REQUIRED"));
        assertTrue(CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").isEmpty());
    }

    @Test
    void prefabFailureRollsBackBlocksAndDoesNotCommitOwnerLedger(@TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());
        FakeWorld blockWorld = new FakeWorld();
        FakePrefabWorld prefabWorld = new FakePrefabWorld();
        prefabWorld.failPlacement = true;

        CityLandUseWorldgenRegistry.ApplySummary failed = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                blockWorld, prefabWorld, -64, 319);

        assertEquals(1, failed.failedOwnerCount());
        assertEquals(1, prefabWorld.placeCalls);
        assertFalse(blockWorld.restores.isEmpty());
        assertTrue(CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").isEmpty());
    }

    @Test
    void foundationQueryIsPartitionedImmutableDeduplicatedAndEmptyWithoutActivePlan(
            @TempDir Path temp) throws Exception {
        assertTrue(CityLandUseWorldgenRegistry.foundationSegmentsForChunk(
                "minecraft:overworld", new ChunkPos(0, 0)).isEmpty());
        CityContinuousTerrainRunPlanner.FoundationSegment segment =
                new CityContinuousTerrainRunPlanner.FoundationSegment(
                        "farm/surface/run", 14, 0, 70, 18, 0, 70, 0, 4, 0);
        SurfaceActivation activation = surfaceActivation(temp, List.of(segment, segment));
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());

        List<CityContinuousTerrainRunPlanner.FoundationSegment> west =
                CityLandUseWorldgenRegistry.foundationSegmentsForChunk(
                        "minecraft:overworld", new ChunkPos(0, 0));
        assertEquals(List.of(segment), west);
        assertEquals(List.of(segment), CityLandUseWorldgenRegistry.foundationSegmentsForChunk(
                "minecraft:overworld", new ChunkPos(1, 0)));
        assertTrue(CityLandUseWorldgenRegistry.foundationSegmentsForChunk(
                "minecraft:overworld", new ChunkPos(2, 0)).isEmpty());
        assertTrue(CityLandUseWorldgenRegistry.foundationSegmentsForChunk(
                "minecraft:the_nether", new ChunkPos(0, 0)).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> west.add(segment));

        CityLandUseWorldgenRegistry.deactivate(
                "minecraft:overworld", activation.areaPlan().cityId(), activation.server());
        assertTrue(CityLandUseWorldgenRegistry.foundationSegmentsForChunk(
                "minecraft:overworld", new ChunkPos(0, 0)).isEmpty());
    }

    @Test
    void ineligibleSurfaceOwnerDoesNotResolveDatumOrTouchWorld(@TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());
        FakeWorld blockWorld = new FakeWorld();
        FakePrefabWorld prefabWorld = new FakePrefabWorld();

        CityLandUseWorldgenRegistry.ApplySummary result = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.ALREADY_GENERATED,
                blockWorld, prefabWorld, -64, 319);

        assertEquals(1, result.ineligibleOwnerCount());
        assertEquals(0, blockWorld.sampleCount);
        assertTrue(blockWorld.writes.isEmpty());
        assertEquals(0, prefabWorld.placeCalls);
        assertTrue(CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("placementDatums").isEmpty());
    }

    @Test
    void placementDatumLookupUsesLoadedIndexWithLargeHistory(@TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());
        JsonObject ledger = new JsonObject();
        ledger.addProperty("schemaVersion", CityLandUseWorldgenRegistry.LEDGER_SCHEMA);
        ledger.add("appliedOwners", new com.google.gson.JsonArray());
        com.google.gson.JsonArray datums = new com.google.gson.JsonArray();
        for (int index = 0; index < 2_000; index++) {
            datums.add(datum("other-plan", "other-placement-" + index, 50));
        }
        datums.add(datum(activation.surfacePlan().planHash(),
                "area_plaza/surface/0_0/farm/surface/place", 66));
        ledger.add("placementDatums", datums);
        Files.writeString(CityLandUseWorldgenRegistry.worldgenLedgerPath(activation.server()), ledger.toString());

        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.setCatalogReaderForTests(ignored -> activation.catalog());
        CityLandUseWorldgenRegistry.load(activation.server());
        assertEquals(2_001, CityLandUseWorldgenRegistry.placementDatumIndexSizeForTests());
        CityLandUseWorldgenRegistry.applyForChunk("minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                new FakeWorld(), new FakePrefabWorld(), -64, 319);

        assertEquals(1, CityLandUseWorldgenRegistry.placementDatumLookupCountForTests());
        assertEquals(2_001, CityLandUseWorldgenRegistry.placementDatumIndexSizeForTests());
    }

    @Test
    void liveDatumSamplingDoesNotHoldRegistryMonitor(@TempDir Path temp) throws Exception {
        SurfaceActivation activation = surfaceActivation(temp);
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", activation.areaPlan(),
                activation.surfacePlan(), activation.catalog(), activation.server());
        FakeWorld blockWorld = new FakeWorld();
        AtomicBoolean checked = new AtomicBoolean();
        blockWorld.onSample = () -> {
            if (!checked.compareAndSet(false, true)) return;
            try {
                CompletableFuture.supplyAsync(CityLandUseWorldgenRegistry::placementDatumIndexSizeForTests)
                        .get(2, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError("live terrain sampling ran while holding the registry monitor", failure);
            }
        };

        CityLandUseWorldgenRegistry.applyForChunk("minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                blockWorld, new FakePrefabWorld(), -64, 319);

        assertTrue(checked.get());
    }

    private static JsonObject datum(String planHash, String placementId, int resolvedTargetY) {
        JsonObject datum = new JsonObject();
        datum.addProperty("surfacePrintPlanHash", planHash);
        datum.addProperty("placementId", placementId);
        datum.addProperty("resolvedTargetY", resolvedTargetY);
        return datum;
    }

    private static CityLandUseSurfacePrintPlan surfacePlan(LandUseAreaPlan areaPlan,
                                                            String catalogHash,
                                                            String sourceHash) {
        LandUseAreaPlan.Area source = areaPlan.areas().get(0);
        CityLandUseSurfacePrintPlan.AreaPrint printArea = new CityLandUseSurfacePrintPlan.AreaPrint(
                "area_plaza/surface/0_0", source.areaId(), source.sourceGroupIds(),
                new LandUseSurfaceSettings(true, true, "minecraft:stone_bricks", "", "PAVE"),
                source.memberSpans(), List.of(), new com.rinsing.geomantia.systems.city.domain.model.BlockPoint(0, 0),
                CityLandUseSurfaceRunCompiler.WorldAxis.X,
                new CityLandUseSurfacePrintPlan.UniformRecipe("minecraft:stone_bricks"));
        CityLandUseSurfacePrintPlan raw = new CityLandUseSurfacePrintPlan(
                CityLandUseSurfacePrintPlan.CURRENT_SCHEMA_VERSION, areaPlan.cityId(), sourceHash,
                catalogHash, "", List.of(printArea));
        return new CityLandUseSurfacePrintPlanCodec().withComputedHash(raw);
    }

    private static SurfaceActivation surfaceActivation(Path temp) throws Exception {
        return surfaceActivation(temp, List.of());
    }

    private static SurfaceActivation surfaceActivation(
            Path temp,
            List<CityContinuousTerrainRunPlanner.FoundationSegment> foundationSegments) throws Exception {
        CityDecorationContentCatalog catalog = TestDecorationCatalogs.loadManagedDefault(
                temp.resolve("city_decoration"));
        LandUseAreaPlan areaPlan = CityLandUseChunkCompilerTest.plan("city_surface_apply",
                SurfacePolicy.CULTIVATE);
        LandUseAreaPlan.Area source = areaPlan.areas().get(0);
        CityDecorationContentCatalog.Content straightContent = catalog.requireContent(
                CityLandUseSurfaceRunCompiler.STRAIGHT_CONTENT_REF);
        CityDecorationContentCatalog.Content endCapContent = catalog.requireContent(
                CityLandUseSurfaceRunCompiler.END_CAP_CONTENT_REF);
        CityLandUseSurfaceRunCompiler.PrefabSpec straight = prefabSpec(straightContent);
        CityLandUseSurfaceRunCompiler.PrefabSpec endCap = prefabSpec(endCapContent);
        String runId = "farm/surface/run";
        BlockBounds footprint = new BlockBounds(15, 0, 17, 0);
        CityLandUseSurfacePrintPlan.SurfacePlacement placement =
                new CityLandUseSurfacePrintPlan.SurfacePlacement(
                        "farm/surface/place", runId, 0, new BlockPoint(16, 0), new BlockPoint(15, 0),
                        0, footprint, 70, 72, false,
                        CityContinuousTerrainRunPlanner.TerrainClass.SAFE,
                        CityContinuousTerrainRunPlanner.Decision.PLACE,
                        straight.contentRef(), straight.contentHash(), straight.contentRef(),
                        straight.contentHash(), "CITY_LAND_USE_SURFACE_RUN_POINT_SAFE");
        CityLandUseSurfacePrintPlan.SurfaceRun run = new CityLandUseSurfacePrintPlan.SurfaceRun(
                runId, CityLandUseSurfaceRunCompiler.WorldAxis.Z, 15, List.of(placement),
                null, "", foundationSegments);
        CityContinuousTerrainRunPlanner.FoundationMode foundationMode = foundationSegments.isEmpty()
                ? CityContinuousTerrainRunPlanner.FoundationMode.NONE
                : CityContinuousTerrainRunPlanner.FoundationMode.FILL_ONLY;
        CityLandUseSurfacePrintPlan.CultivateLinedRecipe recipe =
                new CityLandUseSurfacePrintPlan.CultivateLinedRecipe(
                        "minecraft:farmland", "minecraft:wheat", 13, 5, 3, 5, 5,
                        straight, endCap, new CityLandUseSurfaceRunCompiler.TerrainPolicy(
                        1, false, 2, 8, foundationMode,
                        foundationSegments.isEmpty() ? 0 : 4, 0),
                        List.of(run), foundationSegments);
        CityLandUseSurfacePrintPlan.AreaPrint printArea = new CityLandUseSurfacePrintPlan.AreaPrint(
                "area_plaza/surface/0_0", source.areaId(), source.sourceGroupIds(),
                LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE), source.memberSpans(), List.of(),
                BlockPoint.ORIGIN, CityLandUseSurfaceRunCompiler.WorldAxis.Z, recipe);
        CityLandUseSurfacePrintPlan raw = new CityLandUseSurfacePrintPlan(
                CityLandUseSurfacePrintPlan.CURRENT_SCHEMA_VERSION, areaPlan.cityId(), areaPlan.planHash(),
                catalog.catalogHash(), "", List.of(printArea));
        CityLandUseSurfacePrintPlan surfacePlan = new CityLandUseSurfacePrintPlanCodec().withComputedHash(raw);
        return new SurfaceActivation(temp.resolve("server"), areaPlan, surfacePlan, catalog);
    }

    private static CityLandUseSurfaceRunCompiler.PrefabSpec prefabSpec(
            CityDecorationContentCatalog.Content content) {
        return new CityLandUseSurfaceRunCompiler.PrefabSpec(content.contentId(), content.contentHash(),
                content.size().widthBlocks(), content.size().heightBlocks(), content.size().depthBlocks());
    }

    private record SurfaceActivation(Path server,
                                     LandUseAreaPlan areaPlan,
                                     CityLandUseSurfacePrintPlan surfacePlan,
                                     CityDecorationContentCatalog catalog) {
    }

    private static final class FakeWorld implements CityLandUseChunkExecutor.ExecutionWorld {
        private final List<String> writes = new ArrayList<>();
        private final List<String> restores = new ArrayList<>();
        private int failWriteIndex = -1;
        private int surfaceY = 64;
        private int sampleCount;
        private Runnable onSample;

        @Override
        public CityLandUseChunkExecutor.ColumnSample sampleColumn(int worldX, int worldZ) {
            sampleCount++;
            if (onSample != null) onSample.run();
            return new CityLandUseChunkExecutor.ColumnSample(surfaceY, "minecraft:grass_block", true);
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

    private static final class FakePrefabWorld implements CityNbtPrefabBatchPlacer.PlacementWorld {
        private int placeCalls;
        private int restoreCalls;
        private boolean failPlacement;
        private boolean rejectReplacePolicy;
        private final List<BlockPos> anchors = new ArrayList<>();

        @Override
        public boolean ensureCanWrite(BlockPos pos) {
            return true;
        }

        @Override
        public boolean canReplace(CityNbtPrefabBatchPlacer.PlacementTarget target,
                                  String replacePolicy,
                                  int groundPlaneLocalY) {
            return !rejectReplacePolicy;
        }

        @Override
        public Object snapshot(BlockPos pos) {
            return "old:" + pos.toShortString();
        }

        @Override
        public boolean restore(BlockPos pos, Object snapshot) {
            restoreCalls++;
            return true;
        }

        @Override
        public boolean placeTemplate(CompoundTag templateNbt,
                                     BlockPos anchor,
                                     Rotation rotation,
                                     long seed,
                                     boolean ignoreTemplateAir,
                                     BoundingBox ownerBounds) {
            placeCalls++;
            anchors.add(anchor.immutable());
            return !failPlacement;
        }
    }
}
