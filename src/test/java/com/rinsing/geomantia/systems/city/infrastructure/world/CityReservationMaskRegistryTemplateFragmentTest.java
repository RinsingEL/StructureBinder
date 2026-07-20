package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
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
                        planned, new BlockBounds(8, -4, 20, 14), "template:drift", new JsonArray(),
                        anchorOwner, 82, "", "TEMPLATE_CHUNK_WRITE_WAITING", "drift");

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
        CityReservationMaskRegistry.activate(maskPlan(), null, materializationPlan(footprint, rotation),
                "run_fragment_test", "seed_fragment_test", tempDir);
        return CityReservationMaskRegistry.plannedStructuresForChunk(new ChunkPos(0, 0)).get(0);
    }

    private static CityReservationMaskRegistry.TemplateFragmentRecordResult record(
            CityReservationMaskRegistry.PlannedStructure planned, ChunkPos owner, int datum) {
        return CityReservationMaskRegistry.recordTemplateWorldgenFragment(
                planned, planned.lockedActualFootprint(), "template:stable", new JsonArray(), owner,
                datum, "", "TEMPLATE_CHUNK_WRITE_WAITING", "fragment");
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

    private static JsonObject maskPlan() {
        JsonObject mask = new JsonObject();
        mask.addProperty("cityId", "city_fragment_test");
        mask.add("noVegetationMask", new JsonArray());
        mask.add("noVanillaStructureMask", new JsonArray());
        return mask;
    }

    private static JsonObject materializationPlan(
            BlockBounds footprint, CityTemplatePlacementGeometry.Rotation rotation) {
        JsonObject plan = new JsonObject();
        plan.addProperty("cityId", "city_fragment_test");
        JsonArray structures = new JsonArray();
        JsonObject structure = new JsonObject();
        structure.addProperty("status", "planned_worldgen");
        structure.addProperty("anchorId", "anchor_windmill");
        structure.addProperty("structureId", "geomantia:city/test/windmill");
        structure.add("anchorBlock", point(8, 8));
        structure.add("plannedFootprint", bounds(footprint));
        structure.add("reservedEnvelope", bounds(footprint));
        structure.add("lockedActualFootprint", bounds(footprint));
        structure.addProperty("templateRef", "geomantia:city/test/windmill");
        structure.addProperty("templateHash", "sha256:template");
        structure.addProperty("materializationSource", "structure_template_nbt");
        structure.addProperty("terrainPosePolicy", "direct_template");
        structure.addProperty("templateDatumPolicy", "worldgen_surface_motion_blocking_no_leaves");
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
