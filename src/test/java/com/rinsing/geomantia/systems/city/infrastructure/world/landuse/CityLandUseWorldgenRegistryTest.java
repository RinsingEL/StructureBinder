package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseWorldgenRegistryTest {
    @TempDir
    Path serverRoot;

    @BeforeEach
    void setUp() {
        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.setSurfaceBlockExistsForTests(ignored -> true);
    }

    @AfterEach
    void tearDown() {
        CityLandUseWorldgenRegistry.resetForTests();
    }

    @Test
    void currentPlanPersistsReloadsAndRemainsOwnerIdempotent() throws IOException {
        LandUseAreaPlan areaPlan = areaPlan("city_reload");
        CityLandUseSurfacePrintPlan surfacePlan =
                CityLandUseChunkCompilerTest.uniformPlan(areaPlan);
        CityLandUseWorldgenRegistry.activate(
                "minecraft:overworld", areaPlan, surfacePlan, serverRoot);
        FakeWorld world = new FakeWorld();

        CityLandUseWorldgenRegistry.ApplySummary first = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES, world);
        CityLandUseWorldgenRegistry.ApplySummary repeated = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES, world);

        assertEquals(1, first.appliedOwnerCount());
        assertEquals(1, repeated.alreadyAppliedOwnerCount());
        JsonObject active = JsonParser.parseString(Files.readString(
                CityLandUseWorldgenRegistry.activePlansPath(serverRoot))).getAsJsonObject();
        JsonObject entry = active.getAsJsonArray("plans").get(0).getAsJsonObject();
        assertEquals(CityLandUseWorldgenRegistry.ACTIVE_SCHEMA,
                active.get("schema").getAsString());
        assertFalse(entry.has("catalogHash"));
        assertFalse(entry.has("catalogRoot"));
        assertFalse(entry.has("surfaceMode"));
        CityLandUseWorldgenRegistry.flushPendingLedgerNow();
        JsonObject persistedLedger = JsonParser.parseString(Files.readString(
                CityLandUseWorldgenRegistry.worldgenLedgerPath(serverRoot))).getAsJsonObject();
        assertFalse(persistedLedger.has("placementDatums"));
        assertFalse(persistedLedger.has("placementDecisions"));

        CityLandUseWorldgenRegistry.resetForTests();
        CityLandUseWorldgenRegistry.setSurfaceBlockExistsForTests(ignored -> true);
        CityLandUseWorldgenRegistry.load(serverRoot);
        CityLandUseWorldgenRegistry.ApplySummary reloaded = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES,
                new FakeWorld());

        assertEquals(1, reloaded.alreadyAppliedOwnerCount());
        assertEquals(1, CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").size());
        assertTrue(CityLandUseWorldgenRegistry.ledgerSnapshot().getAsJsonArray("appliedOwners")
                .get(0).getAsJsonObject().has("foundationDiagnostics"));
    }

    @Test
    void worldgenDefersLedgerSerializationUntilControlledFlush() throws IOException {
        LandUseAreaPlan areaPlan = areaPlan("city_deferred_ledger");
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", areaPlan,
                CityLandUseChunkCompilerTest.uniformPlan(areaPlan), serverRoot);
        AtomicInteger writes = new AtomicInteger();
        CityLandUseWorldgenRegistry.setLedgerPersistenceWriterForTests((path, object, reasonCode) -> {
            writes.incrementAndGet();
            try {
                Files.writeString(path, object.toString());
            } catch (IOException ex) {
                throw new IllegalStateException(reasonCode, ex);
            }
        });

        CityLandUseWorldgenRegistry.ApplySummary applied = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES, new FakeWorld());

        assertEquals(1, applied.appliedOwnerCount());
        assertEquals(0, writes.get(), "worldgen worker must not serialize the growing ledger");
        CityLandUseWorldgenRegistry.flushPendingLedgerNow();
        assertEquals(1, writes.get());
    }

    @Test
    void failedOwnerRollsBackAndIsNotRecorded() {
        LandUseAreaPlan areaPlan = areaPlan("city_rollback");
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", areaPlan,
                CityLandUseChunkCompilerTest.uniformPlan(areaPlan), serverRoot);
        FakeWorld world = new FakeWorld();
        world.mutateThenFailWriteIndex = 2;

        CityLandUseWorldgenRegistry.ApplySummary result = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES, world);

        assertEquals(1, result.failedOwnerCount());
        assertEquals(1, result.failures().size());
        assertEquals("CITY_LAND_USE_BLOCK_WRITE_FAILED", result.failures().get(0).reasonCode());
        assertTrue(result.failures().get(0).rollbackComplete());
        assertFalse(world.restores.isEmpty());
        assertEquals(0, CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").size());
    }

    @Test
    void ineligibleOwnerDoesNotWriteOrEnterLedger() {
        LandUseAreaPlan areaPlan = areaPlan("city_ineligible");
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", areaPlan,
                CityLandUseChunkCompilerTest.uniformPlan(areaPlan), serverRoot);
        FakeWorld world = new FakeWorld();

        CityLandUseWorldgenRegistry.ApplySummary result = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.ALREADY_GENERATED, world);

        assertEquals(1, result.ineligibleOwnerCount());
        assertTrue(world.writes.isEmpty());
        assertEquals(0, CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").size());
    }

    @Test
    void activationRejectsUnknownSurfaceBlockBeforePersisting() {
        LandUseAreaPlan areaPlan = areaPlan("city_unknown_block");
        LandUseAreaPlan.Area source = areaPlan.areas().get(0);
        LandUseSurfaceSettings settings = new LandUseSurfaceSettings(
                true, true, "example:missing_block", "", "PAVE");
        CityLandUseSurfacePrintPlan surfacePlan = CityLandUseChunkCompilerTest.hashed(areaPlan,
                List.of(new CityLandUseSurfacePrintPlan.AreaPrint(
                        "area/surface", source.areaId(), source.sourceGroupIds(), settings,
                        source.memberSpans(), List.of(),
                        new CityLandUseSurfacePrintPlan.UniformRecipe(settings.surfaceBlockId()))));
        CityLandUseWorldgenRegistry.setSurfaceBlockExistsForTests(
                key -> !key.toString().equals("example:missing_block"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityLandUseWorldgenRegistry.activate(
                        "minecraft:overworld", areaPlan, surfacePlan, serverRoot));

        assertTrue(failure.getMessage().contains("CITY_LAND_USE_SURFACE_BLOCK_UNKNOWN"));
        assertFalse(Files.exists(CityLandUseWorldgenRegistry.activePlansPath(serverRoot)));
    }

    @Test
    void loadQuarantinesObsoleteActiveAndLedgerSchemasWithoutBlockingServerStart() throws IOException {
        Path activePath = CityLandUseWorldgenRegistry.activePlansPath(serverRoot);
        Files.createDirectories(activePath.getParent());
        Files.writeString(activePath,
                "{\"schema\":\"obsolete_city_active_land_use_area_plans\",\"plans\":[]}");

        CityLandUseWorldgenRegistry.load(serverRoot);
        assertTrue(Files.exists(activePath));
        JsonObject currentActive = JsonParser.parseString(Files.readString(activePath)).getAsJsonObject();
        assertEquals(CityLandUseWorldgenRegistry.ACTIVE_SCHEMA,
                currentActive.get("schema").getAsString());
        assertEquals(0, currentActive.getAsJsonArray("plans").size());

        Files.writeString(CityLandUseWorldgenRegistry.worldgenLedgerPath(serverRoot),
                "{\"schema\":\"obsolete_city_land_use_worldgen_ledger\",\"appliedOwners\":[]}");
        CityLandUseWorldgenRegistry.load(serverRoot);
        assertEquals(0, CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").size());
        try (var files = Files.list(activePath.getParent())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().contains(".obsolete-")));
        }
    }

    @Test
    void activeClearAreaSuppressesVegetationOnlyInsideItsMask() {
        LandUseAreaPlan areaPlan = areaPlan("city_vegetation");
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", areaPlan,
                CityLandUseChunkCompilerTest.uniformPlan(areaPlan), serverRoot);

        assertTrue(CityLandUseWorldgenRegistry.suppressesVegetation(
                "minecraft:overworld", 1, 0));
        assertFalse(CityLandUseWorldgenRegistry.suppressesVegetation(
                "minecraft:overworld", 20, 0));
        assertTrue(CityLandUseWorldgenRegistry.vegetationLike("configured_tree_oak"));
        assertFalse(CityLandUseWorldgenRegistry.vegetationLike("ore_diamond"));
    }

    @Test
    void controlledD7BackfillAppliesAFormerlyMissedOwnerButRemainsIdempotent() {
        LandUseAreaPlan areaPlan = areaPlan("city_controlled_backfill");
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", areaPlan,
                CityLandUseChunkCompilerTest.uniformPlan(areaPlan), serverRoot);
        FakeWorld world = new FakeWorld();

        CityLandUseWorldgenRegistry.ApplySummary first = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.CONTROLLED_D7_BACKFILL, world);
        CityLandUseWorldgenRegistry.ApplySummary repeated = CityLandUseWorldgenRegistry.applyForChunk(
                "minecraft:overworld", 0, 0,
                CityLandUseChunkExecutor.GenerationEligibility.CONTROLLED_D7_BACKFILL, world);

        assertEquals(1, first.appliedOwnerCount());
        assertEquals(0, first.failedOwnerCount());
        assertEquals(1, repeated.alreadyAppliedOwnerCount());
        assertEquals(1, CityLandUseWorldgenRegistry.ledgerSnapshot()
                .getAsJsonArray("appliedOwners").size());
    }

    @Test
    void activeFoundationResolvesStructureDatumFromSurroundingPlatform() {
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        for (int z = 0; z <= 15; z++) spans.add(new LandUseAreaPlan.ScanlineSpan(z, 0, 15));
        BlockBounds footprint = new BlockBounds(4, 4, 5, 5);
        LandUseAreaPlan.Area area = new LandUseAreaPlan.Area("foundation", "foundation", "urban",
                List.of("city::foundation"), List.of("house"), List.of(new BlockPoint(0, 0)),
                spans, List.of(footprint), List.of(), List.of(), 1.0, SurfacePolicy.PAVE,
                VegetationPolicy.CLEAR, BoundaryPolicy.OPEN);
        LandUseAreaPlan plan = new LandUseAreaPlanCodec().withComputedHash(new LandUseAreaPlan(
                LandUseAreaPlan.SCHEMA, "land_use_rules", "city_foundation_datum", "",
                new BlockBounds(0, 0, 15, 15), List.of(area), List.of(), List.of(), List.of()));
        CityLandUseWorldgenRegistry.activate("minecraft:overworld", plan,
                CityLandUseChunkCompilerTest.uniformPlan(plan), serverRoot);

        int datum = CityLandUseWorldgenRegistry.resolveStructureFoundationDatum(
                plan.cityId(), footprint,
                (x, z) -> new CityLandUseChunkExecutor.ColumnSample(
                        64, "minecraft:grass_block", true)).orElseThrow();

        assertEquals(65, datum);
    }

    private static LandUseAreaPlan areaPlan(String cityId) {
        return CityLandUseChunkCompilerTest.areaPlan(cityId, SurfacePolicy.PAVE,
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 3)));
    }

    private static final class FakeWorld implements CityLandUseChunkExecutor.ExecutionWorld {
        private final Map<String, Boolean> replaceable = new HashMap<>();
        private final List<String> writes = new ArrayList<>();
        private final List<String> restores = new ArrayList<>();
        private int writeCount;
        private int mutateThenFailWriteIndex = -1;

        @Override
        public CityLandUseChunkExecutor.ColumnSample sampleColumn(int worldX, int worldZ) {
            return new CityLandUseChunkExecutor.ColumnSample(64, "minecraft:dirt", true);
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
            return new CityLandUseChunkExecutor.TargetState("old",
                    replaceable.getOrDefault(worldX + "," + y + "," + worldZ, true));
        }

        @Override
        public boolean setBlock(int worldX, int y, int worldZ, String blockId) {
            writeCount++;
            writes.add(worldX + "," + y + "," + worldZ + "=" + blockId);
            return writeCount != mutateThenFailWriteIndex;
        }

        @Override
        public boolean restoreBlock(int worldX, int y, int worldZ, Object snapshot) {
            restores.add(worldX + "," + y + "," + worldZ + "=" + snapshot);
            return true;
        }
    }
}
