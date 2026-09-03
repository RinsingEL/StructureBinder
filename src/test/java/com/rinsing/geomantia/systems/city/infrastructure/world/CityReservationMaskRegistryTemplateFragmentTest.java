package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityReservationMaskRegistryTemplateFragmentTest {
    private static final BlockBounds THREE_BY_THREE_FOOTPRINT = new BlockBounds(8, 8, 39, 39);

    @TempDir
    Path tempDir;

    @Test
    void nonAnchorOwnersCanArriveFirstThenAllReuseOneFrozenDatum() throws Exception {
        CityReservationMaskRegistry.PlannedStructure planned = activate(THREE_BY_THREE_FOOTPRINT);
        List<ChunkPos> owners = owners(THREE_BY_THREE_FOOTPRINT);
        ChunkPos anchorOwner = new ChunkPos(0, 0);

        for (ChunkPos owner : owners) {
            if (owner.equals(anchorOwner)) {
                continue;
            }
            CityReservationMaskRegistry.TemplateDatumPreparation waiting =
                    CityReservationMaskRegistry.prepareTemplateOwner(
                            planned, owner, OptionalInt.empty(), -64);
            assertEquals(CityReservationMaskRegistry.TemplateDatumPreparationStatus.WAITING, waiting.status());
            assertTrue(CityReservationMaskRegistry.pendingTemplateStructuresForChunk(owner).contains(planned));
        }

        JsonObject beforeAnchor = CityReservationMaskRegistry.worldgenLedgerSnapshot();
        assertEquals(8, beforeAnchor.getAsJsonArray("templatePendingFragments").size());
        assertEquals(0, beforeAnchor.getAsJsonArray("templateDatums").size());
        assertEquals(0, beforeAnchor.getAsJsonArray("templateFragments").size());
        assertEquals(0, beforeAnchor.getAsJsonArray("placedStructures").size());

        CityReservationMaskRegistry.TemplateDatumPreparation anchorReady =
                CityReservationMaskRegistry.prepareTemplateOwner(
                        planned, anchorOwner, OptionalInt.of(105), -64);
        assertTrue(anchorReady.ready());
        assertEquals(105, anchorReady.templateDatumY().orElseThrow());

        CityReservationMaskRegistry.TemplateFragmentRecordResult first = record(planned, anchorOwner, 105);
        assertTrue(first.recorded());
        assertFalse(first.templateCompleted());

        for (ChunkPos owner : owners) {
            if (owner.equals(anchorOwner)) {
                continue;
            }
            CityReservationMaskRegistry.TemplateDatumPreparation ready =
                    CityReservationMaskRegistry.prepareTemplateOwner(
                            planned, owner, OptionalInt.empty(), -64);
            assertTrue(ready.ready());
            assertEquals(105, ready.templateDatumY().orElseThrow());
            CityReservationMaskRegistry.TemplateFragmentRecordResult result = record(planned, owner, 105);
            if (!owner.equals(owners.get(owners.size() - 1))) {
                assertFalse(result.templateCompleted());
                assertEquals(0, CityReservationMaskRegistry.worldgenLedgerSnapshot()
                        .getAsJsonArray("placedStructures").size());
            }
        }

        JsonObject completed = CityReservationMaskRegistry.worldgenLedgerSnapshot();
        assertEquals(1, completed.getAsJsonArray("templateDatums").size());
        assertEquals(9, completed.getAsJsonArray("templatePendingFragments").size());
        assertEquals(9, completed.getAsJsonArray("templateFragments").size());
        assertEquals(1, completed.getAsJsonArray("placedStructures").size());
        assertEquals(105, completed.getAsJsonArray("placedStructures").get(0).getAsJsonObject()
                .get("templateDatumY").getAsInt());
        assertTrue(completed.getAsJsonArray("templateFragments").asList().stream()
                .allMatch(element -> element.getAsJsonObject().get("templateDatumY").getAsInt() == 105));
    }

    @Test
    void conflicting109And110DatumCannotRecordASecondOwner() throws Exception {
        CityReservationMaskRegistry.PlannedStructure planned = activate(new BlockBounds(8, 8, 23, 23));
        ChunkPos anchorOwner = new ChunkPos(0, 0);
        ChunkPos secondOwner = new ChunkPos(1, 0);

        assertTrue(CityReservationMaskRegistry.prepareTemplateOwner(
                planned, anchorOwner, OptionalInt.of(109), -64).ready());
        assertTrue(record(planned, anchorOwner, 109).recorded());
        assertTrue(CityReservationMaskRegistry.prepareTemplateOwner(
                planned, secondOwner, OptionalInt.empty(), -64).ready());

        CityReservationMaskRegistry.TemplateFragmentRecordResult conflict = record(planned, secondOwner, 110);

        assertFalse(conflict.recorded());
        assertEquals("TEMPLATE_DATUM_CONFLICT", conflict.reasonCode());
        assertEquals(1, CityReservationMaskRegistry.worldgenLedgerSnapshot()
                .getAsJsonArray("templateFragments").size());
        assertEquals(0, CityReservationMaskRegistry.worldgenLedgerSnapshot()
                .getAsJsonArray("placedStructures").size());
    }

    @Test
    void terrainStartFreezesOneDatumAndPreauthorizesEveryOwnerBeforePiecePlacement() throws Exception {
        BlockBounds footprint = new BlockBounds(8, 8, 39, 39);
        CityReservationMaskRegistry.PlannedStructure planned = activate(footprint);
        List<ChunkPos> owners = owners(footprint);

        CityReservationMaskRegistry.TemplateDatumPreparation preparation =
                CityReservationMaskRegistry.prepareTemplateTerrainStart(planned, 96);

        assertTrue(preparation.ready());
        assertEquals(96, preparation.templateDatumY().orElseThrow());
        assertEquals(96, CityReservationMaskRegistry.resolvedTemplateDatum(planned).orElseThrow());
        assertTrue(owners.stream().allMatch(owner ->
                CityReservationMaskRegistry.hasTemplatePendingProof(planned, owner)));

        for (int index = 0; index < owners.size(); index++) {
            CityReservationMaskRegistry.TemplateFragmentRecordResult result =
                    record(planned, owners.get(index), 96);
            assertTrue(result.recorded());
            assertEquals(index == owners.size() - 1, result.templateCompleted());
        }
        assertEquals(1, CityReservationMaskRegistry.worldgenLedgerSnapshot()
                .getAsJsonArray("placedStructures").size());
    }

    @Test
    void reloadKeepsPendingDatumAndFragmentTransactionIdempotent() throws Exception {
        BlockBounds footprint = new BlockBounds(8, 8, 23, 23);
        CityReservationMaskRegistry.PlannedStructure planned = activate(footprint);
        ChunkPos anchorOwner = new ChunkPos(0, 0);

        assertTrue(CityReservationMaskRegistry.prepareTemplateOwner(
                planned, anchorOwner, OptionalInt.of(78), -64).ready());
        assertTrue(record(planned, anchorOwner, 78).recorded());
        CityReservationMaskRegistry.load(tempDir);

        CityReservationMaskRegistry.PlannedStructure reloaded = CityReservationMaskRegistry
                .plannedStructuresForChunk(new ChunkPos(1, 0)).get(0);
        assertEquals(78, CityReservationMaskRegistry.resolvedTemplateDatum(reloaded).orElseThrow());
        assertEquals(1, CityReservationMaskRegistry.worldgenLedgerSnapshot()
                .getAsJsonArray("templateFragments").size());

        for (ChunkPos owner : owners(footprint)) {
            if (owner.equals(anchorOwner)) {
                continue;
            }
            assertTrue(CityReservationMaskRegistry.prepareTemplateOwner(
                    reloaded, owner, OptionalInt.empty(), -64).ready());
            record(reloaded, owner, 78);
        }
        record(reloaded, anchorOwner, 78);

        JsonObject ledger = CityReservationMaskRegistry.worldgenLedgerSnapshot();
        assertEquals(4, ledger.getAsJsonArray("templateFragments").size());
        assertEquals(1, ledger.getAsJsonArray("placedStructures").size());
        assertEquals(4, ledger.getAsJsonArray("placedStructures").get(0).getAsJsonObject()
                .get("templateFragmentCount").getAsInt());
    }

    @Test
    void fragmentLedgerWritesAreBatchedButForcedFlushRemainsRestartSafe() throws Exception {
        CityReservationMaskRegistry.PlannedStructure planned = activate(new BlockBounds(8, 8, 23, 23));
        ChunkPos owner = new ChunkPos(0, 0);
        assertTrue(CityReservationMaskRegistry.prepareTemplateTerrainStart(planned, 88).ready());

        JsonObject durableBefore = com.google.gson.JsonParser.parseString(Files.readString(
                CityReservationMaskRegistry.worldgenLedgerPath(tempDir))).getAsJsonObject();
        assertEquals(0, durableBefore.getAsJsonArray("templateFragments").size());

        assertTrue(record(planned, owner, 88).recorded());
        assertEquals(1, CityReservationMaskRegistry.worldgenLedgerSnapshot()
                .getAsJsonArray("templateFragments").size());
        JsonObject stillBatched = com.google.gson.JsonParser.parseString(Files.readString(
                CityReservationMaskRegistry.worldgenLedgerPath(tempDir))).getAsJsonObject();
        assertEquals(0, stillBatched.getAsJsonArray("templateFragments").size());

        CityReservationMaskRegistry.flushPendingWorldgenLedgerNow();
        JsonObject durableAfter = com.google.gson.JsonParser.parseString(Files.readString(
                CityReservationMaskRegistry.worldgenLedgerPath(tempDir))).getAsJsonObject();
        assertEquals(1, durableAfter.getAsJsonArray("templateFragments").size());
    }

    @Test
    void activatingSecondCityKeepsBothTemplateRegistriesAndMasksAcrossReload() throws Exception {
        BlockBounds firstFootprint = new BlockBounds(8, 8, 15, 15);
        BlockBounds secondFootprint = new BlockBounds(40, 8, 47, 15);
        activate("city_first", "seed_first", firstFootprint);
        activate("city_second", "seed_second", secondFootprint);

        assertEquals(2, CityReservationMaskRegistry.activePlannedStructureCount());
        assertTrue(CityReservationMaskRegistry.hasActivePlannedStructuresFor(
                "run_fragment_test", "seed_first", "city_first"));
        assertTrue(CityReservationMaskRegistry.hasActivePlannedStructuresFor(
                "run_fragment_test", "seed_second", "city_second"));
        assertEquals("city_first", CityReservationMaskRegistry.plannedStructuresForChunk(new ChunkPos(0, 0))
                .get(0).cityId());
        assertEquals("city_second", CityReservationMaskRegistry.plannedStructuresForChunk(new ChunkPos(2, 0))
                .get(0).cityId());
        assertEquals(2, CityReservationMaskRegistry.activeSummary().get("activeCityCount").getAsInt());
        assertEquals(2, CityReservationMaskRegistry.activeSummary().get("noVegetationMaskCount").getAsInt());

        JsonObject persistedStructures = com.google.gson.JsonParser.parseString(Files.readString(
                CityReservationMaskRegistry.plannedRegistryPath(tempDir))).getAsJsonObject();
        assertEquals(CityReservationMaskRegistry.PLANNED_REGISTRIES_SCHEMA,
                persistedStructures.get("schema").getAsString());
        assertEquals(2, persistedStructures.getAsJsonArray("registries").size());

        CityReservationMaskRegistry.load(tempDir);

        assertEquals(2, CityReservationMaskRegistry.activePlannedStructureCount());
        assertEquals(2, CityReservationMaskRegistry.activeSummary().get("activeCityCount").getAsInt());
        assertEquals(2, CityReservationMaskRegistry.activeSummary().get("noVegetationMaskCount").getAsInt());
        assertEquals(new BlockPoint(8, 8), CityReservationMaskRegistry.findTemplatePlacement(
                "anchor_windmill", "geomantia:city/test/windmill", "sha256:template",
                new BlockPoint(8, 8)).orElseThrow().anchorBlock());
        assertEquals(new BlockPoint(40, 8), CityReservationMaskRegistry.findTemplatePlacement(
                "anchor_windmill", "geomantia:city/test/windmill", "sha256:template",
                new BlockPoint(40, 8)).orElseThrow().anchorBlock());
    }

    @Test
    void legacySingleCityFilesStillLoadIntoTheMultiCityRegistry() throws Exception {
        BlockBounds footprint = new BlockBounds(8, 8, 15, 15);
        activate("city_legacy", "seed_legacy", footprint);
        Path stateDir = tempDir.resolve("geomantia_city_masks");
        JsonObject plannedCollection = com.google.gson.JsonParser.parseString(Files.readString(
                CityReservationMaskRegistry.plannedRegistryPath(tempDir))).getAsJsonObject();
        JsonObject maskCollection = com.google.gson.JsonParser.parseString(Files.readString(
                stateDir.resolve("active_reservation_mask_plan.json"))).getAsJsonObject();
        Files.writeString(CityReservationMaskRegistry.plannedRegistryPath(tempDir),
                plannedCollection.getAsJsonArray("registries").get(0).toString());
        Files.writeString(stateDir.resolve("active_reservation_mask_plan.json"),
                maskCollection.getAsJsonArray("plans").get(0).toString());

        CityReservationMaskRegistry.load(tempDir);

        assertEquals(1, CityReservationMaskRegistry.activePlannedStructureCount());
        assertTrue(CityReservationMaskRegistry.hasActivePlannedStructuresFor(
                "run_fragment_test", "seed_legacy", "city_legacy"));
        assertEquals(1, CityReservationMaskRegistry.activeSummary().get("activeCityCount").getAsInt());
        assertEquals("city_legacy", CityReservationMaskRegistry.activeSummary().get("cityId").getAsString());
    }

    @Test
    void reactivatingOneCityReplacesOnlyThatCityRevision() throws Exception {
        activate("city_first", "seed_first", new BlockBounds(8, 8, 15, 15));
        activate("city_second", "seed_second", new BlockBounds(40, 8, 47, 15));
        activate("city_first", "seed_first", new BlockBounds(72, 8, 79, 15));

        assertEquals(2, CityReservationMaskRegistry.activePlannedStructureCount());
        assertTrue(CityReservationMaskRegistry.plannedStructuresForChunk(new ChunkPos(0, 0)).isEmpty());
        assertEquals("city_second", CityReservationMaskRegistry.plannedStructuresForChunk(new ChunkPos(2, 0))
                .get(0).cityId());
        assertEquals("city_first", CityReservationMaskRegistry.plannedStructuresForChunk(new ChunkPos(4, 0))
                .get(0).cityId());
    }

    @Test
    void persistenceFailureDoesNotAuthorizeWorldWriteOrKeepInMemoryDatum() throws Exception {
        CityReservationMaskRegistry.PlannedStructure planned = activate(new BlockBounds(8, 8, 15, 15));
        Path blocker = tempDir.resolve("not-a-server-directory");
        Files.writeString(blocker, "block directory creation");
        CityReservationMaskRegistry.load(blocker);

        CityReservationMaskRegistry.TemplateDatumPreparation failed =
                CityReservationMaskRegistry.prepareTemplateOwner(
                        planned, new ChunkPos(0, 0), OptionalInt.of(90), -64);

        assertEquals(CityReservationMaskRegistry.TemplateDatumPreparationStatus.PERSISTENCE_FAILED,
                failed.status());
        assertFalse(failed.ready());
        assertTrue(CityReservationMaskRegistry.resolvedTemplateDatum(planned).isEmpty());
        assertFalse(CityReservationMaskRegistry.hasTemplatePendingProof(planned, new ChunkPos(0, 0)));
        CityReservationMaskRegistry.load(tempDir);
    }

    @Test
    void rotatedNonSquareTemplateCompletesOnlyAfterAllFourOwners() throws Exception {
        BlockBounds footprint = new BlockBounds(8, 8, 20, 26);
        CityReservationMaskRegistry.PlannedStructure planned = activate(
                footprint, CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90);
        List<ChunkPos> owners = owners(footprint);
        ChunkPos anchorOwner = new ChunkPos(0, 0);

        assertTrue(CityReservationMaskRegistry.prepareTemplateOwner(
                planned, anchorOwner, OptionalInt.of(82), -64).ready());
        for (int index = 0; index < owners.size(); index++) {
            ChunkPos owner = owners.get(index);
            if (!owner.equals(anchorOwner)) {
                assertTrue(CityReservationMaskRegistry.prepareTemplateOwner(
                        planned, owner, OptionalInt.empty(), -64).ready());
            }
            CityReservationMaskRegistry.TemplateFragmentRecordResult result = record(planned, owner, 82);
            assertEquals(index == owners.size() - 1, result.templateCompleted());
            assertEquals(index == owners.size() - 1 ? 1 : 0,
                    CityReservationMaskRegistry.worldgenLedgerSnapshot()
                            .getAsJsonArray("placedStructures").size());
        }
    }

    @Test
    void transformedFootprintDriftCannotRecordFragmentOrCompletedLedger() throws Exception {
        BlockBounds footprint = new BlockBounds(8, 8, 20, 26);
        CityReservationMaskRegistry.PlannedStructure planned = activate(
                footprint, CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90);
        ChunkPos anchorOwner = new ChunkPos(0, 0);
        assertTrue(CityReservationMaskRegistry.prepareTemplateOwner(
                planned, anchorOwner, OptionalInt.of(82), -64).ready());

        CityReservationMaskRegistry.TemplateFragmentRecordResult result =
                CityReservationMaskRegistry.recordTemplateWorldgenFragment(
                        planned, new BlockBounds(8, -4, 20, 14), anchorOwner, 82,
                        "", "TEMPLATE_CHUNK_WRITE_WAITING", "drift");

        assertFalse(result.recorded());
        assertEquals("TEMPLATE_LOCKED_FOOTPRINT_MISMATCH", result.reasonCode());
        assertEquals(0, CityReservationMaskRegistry.worldgenLedgerSnapshot()
                .getAsJsonArray("templateFragments").size());
        assertEquals(0, CityReservationMaskRegistry.worldgenLedgerSnapshot()
                .getAsJsonArray("placedStructures").size());
    }

    private CityReservationMaskRegistry.PlannedStructure activate(BlockBounds footprint) throws Exception {
        return activate(footprint, CityTemplatePlacementGeometry.Rotation.NONE);
    }

    private CityReservationMaskRegistry.PlannedStructure activate(
            BlockBounds footprint, CityTemplatePlacementGeometry.Rotation rotation) throws Exception {
        CityReservationMaskRegistry.activate(maskPlan("city_fragment_test", footprint), null,
                materializationPlan("city_fragment_test", footprint, rotation),
                "run_fragment_test", "seed_fragment_test", tempDir);
        return CityReservationMaskRegistry.plannedStructuresForChunk(new ChunkPos(0, 0)).get(0);
    }

    private CityReservationMaskRegistry.PlannedStructure activate(
            String cityId, String citySeedId, BlockBounds footprint) throws Exception {
        CityReservationMaskRegistry.activate(maskPlan(cityId, footprint), null,
                materializationPlan(cityId, footprint, CityTemplatePlacementGeometry.Rotation.NONE),
                "run_fragment_test", citySeedId, tempDir);
        return CityReservationMaskRegistry.plannedStructuresForChunk(
                new ChunkPos(Math.floorDiv(footprint.minX(), 16), Math.floorDiv(footprint.minZ(), 16))).get(0);
    }

    private static CityReservationMaskRegistry.TemplateFragmentRecordResult record(
            CityReservationMaskRegistry.PlannedStructure planned, ChunkPos owner, int datum) {
        return CityReservationMaskRegistry.recordTemplateWorldgenFragment(
                planned, planned.lockedActualFootprint(), owner, datum,
                "", "TEMPLATE_CHUNK_WRITE_WAITING", "fragment");
    }

    private static List<ChunkPos> owners(BlockBounds footprint) {
        List<ChunkPos> owners = new ArrayList<>();
        for (int x = Math.floorDiv(footprint.minX(), 16); x <= Math.floorDiv(footprint.maxX(), 16); x++) {
            for (int z = Math.floorDiv(footprint.minZ(), 16); z <= Math.floorDiv(footprint.maxZ(), 16); z++) {
                owners.add(new ChunkPos(x, z));
            }
        }
        return owners;
    }

    private static JsonObject maskPlan(String cityId, BlockBounds footprint) {
        JsonObject mask = new JsonObject();
        mask.addProperty("cityId", cityId);
        JsonArray noVegetation = new JsonArray();
        noVegetation.add(bounds(footprint));
        mask.add("noVegetationMask", noVegetation);
        mask.add("noVanillaStructureMask", new JsonArray());
        return mask;
    }

    private static JsonObject materializationPlan(
            String cityId, BlockBounds footprint, CityTemplatePlacementGeometry.Rotation rotation) {
        JsonObject plan = new JsonObject();
        plan.addProperty("cityId", cityId);
        JsonArray structures = new JsonArray();
        JsonObject structure = new JsonObject();
        structure.addProperty("status", "planned_worldgen");
        structure.addProperty("anchorId", "anchor_windmill");
        structure.addProperty("templateId", "geomantia:city/test/windmill");
        structure.add("anchorBlock", point(footprint.minX(), footprint.minZ()));
        structure.add("plannedFootprint", bounds(footprint));
        structure.add("reservedEnvelope", bounds(footprint));
        structure.add("lockedActualFootprint", bounds(footprint));
        structure.addProperty("templateRef", "geomantia:city/test/windmill");
        structure.addProperty("templateHash", "sha256:template");
        structure.addProperty("materializationSource", "structure_template_nbt");
        structure.addProperty("terrainPosePolicy", "structure_start_beard_thin");
        structure.addProperty("templateDatumPolicy", "generator_base_height_motion_blocking_no_leaves");
        structure.addProperty("rotation", rotation.name());
        structure.addProperty("mirror", CityTemplatePlacementGeometry.Mirror.NONE.name());
        structures.add(structure);
        plan.add("plannedWorldgenStructures", structures);
        return plan;
    }

    private static JsonObject point(int x, int z) {
        JsonObject point = new JsonObject();
        point.addProperty("x", x);
        point.addProperty("z", z);
        return point;
    }

    private static JsonObject bounds(BlockBounds bounds) {
        JsonObject object = new JsonObject();
        object.addProperty("minX", bounds.minX());
        object.addProperty("minZ", bounds.minZ());
        object.addProperty("maxX", bounds.maxX());
        object.addProperty("maxZ", bounds.maxZ());
        return object;
    }
}
