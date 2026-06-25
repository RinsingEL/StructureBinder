package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    private JsonObject connector() {
        JsonObject connector = new JsonObject();
        connector.addProperty("connectorId", "jigsaw_east_15_1_7");
        connector.addProperty("name", "minecraft:street");
        connector.addProperty("target", "minecraft:street");
        connector.addProperty("pool", "minecraft:village/plains/streets");
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
