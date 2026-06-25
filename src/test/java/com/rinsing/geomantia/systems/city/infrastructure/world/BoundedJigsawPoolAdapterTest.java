package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedJigsawPoolAdapterTest {
    @Test
    void normalizesConnectorReadyPieceForSolverInput() {
        JsonObject raw = rawPiece("minecraft:village/plains/houses/plains_small_house_1", connector());

        JsonObject piece = BoundedJigsawPoolAdapter.normalizePiece(raw, true, 1,
                new BoundingBox(0, 64, 0, 15, 72, 15), "start_piece_1");

        assertEquals("start_piece_1", piece.get("pieceId").getAsString());
        assertEquals("connector_ready", piece.get("adapterStatus").getAsString());
        assertEquals("minecraft:village/plains/houses/plains_small_house_1",
                piece.get("templateId").getAsString());
        assertEquals(256, piece.get("visibleAreaCost").getAsInt());
        assertEquals(15, piece.getAsJsonObject("footprint").get("maxX").getAsInt());
        assertFalse(piece.has("adapterReasonCode"));
    }

    @Test
    void marksInspectablePieceWithoutConnectorsAsTerminal() {
        JsonObject raw = rawPiece("minecraft:village/plains/terminators/terminator_1");

        JsonObject piece = BoundedJigsawPoolAdapter.normalizePiece(raw, true, 0,
                new BoundingBox(16, 64, 0, 31, 70, 15), "terminal_piece_1");

        assertEquals("terminal_piece", piece.get("adapterStatus").getAsString());
        assertEquals(256, piece.get("visibleAreaCost").getAsInt());
    }

    @Test
    void marksUninspectableElementWithoutUsingDiagnosticStringAsTemplateId() {
        JsonObject raw = rawPiece("unresolved");
        raw.addProperty("element", "SinglePoolElement[diagnostic text]");

        JsonObject piece = BoundedJigsawPoolAdapter.normalizePiece(raw, false, 0,
                new BoundingBox(0, 64, 16, 15, 70, 31), "bad_piece_1");

        assertEquals("unsupported_pool_element", piece.get("adapterStatus").getAsString());
        assertEquals("UNSUPPORTED_POOL_ELEMENT", piece.get("adapterReasonCode").getAsString());
        assertEquals("unresolved", piece.get("templateId").getAsString());
    }

    @Test
    void populatesCandidatePoolsFromConnectorRefsWithDepthLimit() {
        JsonArray rootPieces = pieces(rawPiece("minecraft:village/plains/town_centers/plains_fountain_01",
                connector("minecraft:village/plains/streets"),
                connector("minecraft:village/plains/streets")));
        JsonObject candidatePools = new JsonObject();

        JsonObject report = BoundedJigsawPoolAdapter.populateCandidatePools(candidatePools, rootPieces,
                (poolId, depth) -> switch (poolId) {
                    case "minecraft:village/plains/streets" -> pieces(rawPiece("minecraft:village/plains/streets/corner_01",
                            connector("minecraft:village/plains/terminators")));
                    case "minecraft:village/plains/terminators" -> pieces(rawPiece("minecraft:village/plains/terminators/terminator_01"));
                    default -> null;
                }, 2, 8);

        assertEquals(2, candidatePools.entrySet().size());
        assertTrue(candidatePools.has("minecraft:village/plains/streets"));
        assertTrue(candidatePools.has("minecraft:village/plains/terminators"));
        JsonObject streetPiece = candidatePools.getAsJsonArray("minecraft:village/plains/streets")
                .get(0).getAsJsonObject();
        assertEquals("child_pool_prototype", streetPiece.get("adapterScope").getAsString());
        assertEquals("connector_alignment_pending", streetPiece.get("prototypePlacementStatus").getAsString());
        assertEquals(2, report.get("discoveredPoolCount").getAsInt());
        assertEquals(0, report.get("missingPoolCount").getAsInt());
    }

    @Test
    void reportsMissingConnectorPoolsWithoutCreatingCandidates() {
        JsonArray rootPieces = pieces(rawPiece("minecraft:village/plains/town_centers/plains_fountain_01",
                connector("minecraft:village/plains/missing_pool")));
        JsonObject candidatePools = new JsonObject();

        JsonObject report = BoundedJigsawPoolAdapter.populateCandidatePools(candidatePools, rootPieces,
                (poolId, depth) -> null, 2, 8);

        assertEquals(0, candidatePools.entrySet().size());
        assertEquals(0, report.get("discoveredPoolCount").getAsInt());
        assertEquals(1, report.get("missingPoolCount").getAsInt());
        assertEquals("BOUNDED_JIGSAW_POOL_MISSING", report.getAsJsonArray("missingPools")
                .get(0).getAsJsonObject()
                .get("status").getAsString());
    }

    private JsonObject rawPiece(String templateId, JsonObject... connectors) {
        JsonObject raw = new JsonObject();
        raw.addProperty("poolId", "minecraft:village/plains/houses");
        raw.addProperty("templateId", templateId);
        raw.addProperty("rotation", "NONE");
        raw.add("anchorBlock", block(0, 64, 0));
        JsonArray connectorRefs = new JsonArray();
        for (JsonObject connector : connectors) {
            connectorRefs.add(connector);
        }
        raw.add("connectorRefs", connectorRefs);
        raw.addProperty("connectorCount", connectorRefs.size());
        return raw;
    }

    private JsonArray pieces(JsonObject... pieces) {
        JsonArray array = new JsonArray();
        for (JsonObject piece : pieces) {
            array.add(piece);
        }
        return array;
    }

    private JsonObject connector() {
        return connector("minecraft:village/plains/streets");
    }

    private JsonObject connector(String pool) {
        JsonObject connector = new JsonObject();
        connector.addProperty("connectorId", "jigsaw_east_15_1_7");
        connector.addProperty("name", "minecraft:street");
        connector.addProperty("target", "minecraft:street");
        connector.addProperty("pool", pool);
        connector.add("worldBlock", block(15, 65, 7));
        connector.add("localBlock", block(15, 1, 7));
        connector.addProperty("front", "east");
        return connector;
    }

    private JsonObject block(int x, int y, int z) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        return obj;
    }
}
