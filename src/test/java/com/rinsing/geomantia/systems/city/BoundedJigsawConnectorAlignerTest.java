package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.BoundedJigsawConnectorAligner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedJigsawConnectorAlignerTest {
    @Test
    void alignsChildAnchorAndFootprintToParentFrontBlock() {
        JsonObject parent = connector("parent_east", "minecraft:street", "minecraft:street",
                "minecraft:village/plains/streets", 15, 64, 7, "east");
        JsonObject child = piece("child_street", 0, 0, 15, 15,
                connector("child_west", "minecraft:street", "minecraft:street",
                        "minecraft:village/plains/terminators", 0, 64, 7, "west"));

        JsonObject aligned = BoundedJigsawConnectorAligner.alignToParentConnector(parent, child);

        assertEquals("connector_aligned", aligned.get("prototypePlacementStatus").getAsString());
        assertEquals(16, aligned.getAsJsonObject("anchorBlock").get("x").getAsInt());
        assertEquals(16, aligned.getAsJsonObject("footprint").get("minX").getAsInt());
        assertEquals(31, aligned.getAsJsonObject("footprint").get("maxX").getAsInt());
        assertEquals(16, aligned.getAsJsonArray("connectorRefs")
                .get(0).getAsJsonObject()
                .getAsJsonObject("worldBlock")
                .get("x").getAsInt());
        assertTrue(aligned.getAsJsonArray("connectorRefs")
                .get(0).getAsJsonObject()
                .get("consumedByParent").getAsBoolean());
    }

    @Test
    void reportsFailureWhenNoCompatibleConnectorExists() {
        JsonObject parent = connector("parent_east", "minecraft:street", "minecraft:street",
                "minecraft:village/plains/streets", 15, 64, 7, "east");
        JsonObject child = piece("child_bad", 0, 0, 15, 15,
                connector("child_north", "minecraft:house", "minecraft:house",
                        "minecraft:village/plains/terminators", 0, 64, 7, "north"));

        JsonObject aligned = BoundedJigsawConnectorAligner.alignToParentConnector(parent, child);

        assertEquals("failed", aligned.get("alignmentStatus").getAsString());
        assertEquals("JIGSAW_CONNECTOR_ALIGNMENT_FAILED",
                aligned.get("alignmentReasonCode").getAsString());
    }

    private JsonObject piece(String pieceId, int minX, int minZ, int maxX, int maxZ, JsonObject... connectors) {
        JsonObject obj = new JsonObject();
        obj.addProperty("pieceId", pieceId);
        obj.addProperty("adapterScope", "child_pool_prototype");
        obj.addProperty("prototypePlacementStatus", "connector_alignment_pending");
        obj.add("anchorBlock", block(minX, 64, minZ));
        obj.add("footprint", bounds(minX, minZ, maxX, maxZ));
        JsonArray connectorRefs = new JsonArray();
        for (JsonObject connector : connectors) {
            connectorRefs.add(connector);
        }
        obj.add("connectorRefs", connectorRefs);
        return obj;
    }

    private JsonObject connector(String connectorId, String name, String target, String pool,
                                 int x, int y, int z, String front) {
        JsonObject connector = new JsonObject();
        connector.addProperty("connectorId", connectorId);
        connector.addProperty("name", name);
        connector.addProperty("target", target);
        connector.addProperty("pool", pool);
        connector.addProperty("front", front);
        connector.add("worldBlock", block(x, y, z));
        connector.add("localBlock", block(0, 0, 0));
        return connector;
    }

    private JsonObject block(int x, int y, int z) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        return obj;
    }

    private JsonObject bounds(int minX, int minZ, int maxX, int maxZ) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", minX);
        obj.addProperty("minZ", minZ);
        obj.addProperty("maxX", maxX);
        obj.addProperty("maxZ", maxZ);
        return obj;
    }
}
