package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.BoundedJigsawSolver;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedJigsawSolverTest {
    @Test
    void expandsConnectorIntoDeterministicMultiPiecePlan() {
        JsonObject input = baseInput(1024);
        input.add("startPieces", pieces(piece("start_piece_1", "minecraft:start_house",
                "minecraft:village/plains/town_centers", 0, 0, 15, 15,
                connector("door_east", "minecraft:street", "minecraft:street",
                        "minecraft:village/plains/streets"))));
        JsonObject pools = new JsonObject();
        JsonObject street = piece("street_piece_1", "minecraft:street_1",
                "minecraft:village/plains/streets", 16, 0, 31, 15,
                connector("street_tail", "minecraft:street", "minecraft:street",
                        "minecraft:village/plains/terminators"));
        street.addProperty("attachTarget", "minecraft:street");
        pools.add("minecraft:village/plains/streets", pieces(street));
        input.add("candidatePools", pools);

        JsonObject first = new BoundedJigsawSolver().solve(input);
        JsonObject second = new BoundedJigsawSolver().solve(input);

        assertEquals(CityJson.GSON.toJson(first), CityJson.GSON.toJson(second));
        assertEquals(2, first.getAsJsonArray("acceptedPieces").size());
        assertEquals(2, first.getAsJsonObject("plan").getAsJsonArray("pieces").size());
        assertFalse(first.getAsJsonObject("plan").getAsJsonObject("quality")
                .get("startPieceOnly").getAsBoolean());
        assertEquals(1, first.getAsJsonObject("plan").getAsJsonObject("requiredChunkRange")
                .get("maxChunkX").getAsInt());
    }

    @Test
    void stopsBranchWhenNextPieceLeavesAllowedArea() {
        JsonObject input = baseInput(1024);
        input.add("startPieces", pieces(piece("start_piece_1", "minecraft:start_house",
                "minecraft:village/plains/town_centers", 0, 0, 15, 15,
                connector("door_east", "minecraft:street", "minecraft:street",
                        "minecraft:village/plains/streets"))));
        JsonObject pools = new JsonObject();
        JsonObject outside = piece("street_piece_outside", "minecraft:street_1",
                "minecraft:village/plains/streets", 64, 0, 79, 15);
        outside.addProperty("attachTarget", "minecraft:street");
        pools.add("minecraft:village/plains/streets", pieces(outside));
        input.add("candidatePools", pools);

        JsonObject trace = new BoundedJigsawSolver().solve(input);

        assertEquals(1, trace.getAsJsonArray("acceptedPieces").size());
        assertEquals(1, trace.getAsJsonArray("rejectedPieces").size());
        assertTrue(trace.getAsJsonArray("rejectedPieces").toString()
                .contains("JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA"));
        assertTrue(trace.getAsJsonArray("stoppedBranches").toString()
                .contains("JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA"));
        assertEquals(1, trace.getAsJsonObject("plan").getAsJsonArray("pieces").size());
        JsonObject rejected = trace.getAsJsonArray("rejectedPieces").get(0).getAsJsonObject();
        assertEquals("reject", rejected.getAsJsonObject("ruleDecision").get("decision").getAsString());
        assertTrue(rejected.getAsJsonArray("ruleResults").toString().contains("zone_allowed_area"));
    }

    @Test
    void stopsBranchWhenAreaBudgetWouldOverflow() {
        JsonObject input = baseInput(300);
        input.add("startPieces", pieces(piece("start_piece_1", "minecraft:start_house",
                "minecraft:village/plains/town_centers", 0, 0, 15, 15,
                connector("door_east", "minecraft:street", "minecraft:street",
                        "minecraft:village/plains/streets"))));
        JsonObject pools = new JsonObject();
        JsonObject street = piece("street_piece_1", "minecraft:street_1",
                "minecraft:village/plains/streets", 16, 0, 31, 15);
        street.addProperty("attachTarget", "minecraft:street");
        pools.add("minecraft:village/plains/streets", pieces(street));
        input.add("candidatePools", pools);

        JsonObject trace = new BoundedJigsawSolver().solve(input);

        assertEquals(1, trace.getAsJsonArray("acceptedPieces").size());
        assertTrue(trace.getAsJsonArray("rejectedPieces").toString()
                .contains("JIGSAW_AREA_BUDGET_REACHED"));
        assertTrue(trace.getAsJsonArray("stoppedBranches").toString()
                .contains("JIGSAW_AREA_BUDGET_REACHED"));
        assertEquals(256, trace.getAsJsonObject("metrics").get("visibleAreaCost").getAsInt());
    }

    @Test
    void alignsDiscoveredChildPoolPrototypeBeforeValidation() {
        JsonObject input = baseInput(1024);
        input.add("startPieces", pieces(piece("start_piece_1", "minecraft:start_house",
                "minecraft:village/plains/town_centers", 0, 0, 15, 15,
                connector("door_east", "minecraft:street", "minecraft:street",
                        "minecraft:village/plains/streets"))));
        JsonObject pools = new JsonObject();
        JsonObject street = piece("street_piece_1", "minecraft:street_1",
                "minecraft:village/plains/streets", 0, 0, 15, 15,
                connector("street_west", "minecraft:street", "minecraft:street",
                        "minecraft:village/plains/terminators", 0, 64, 0, "west"));
        street.addProperty("adapterScope", "child_pool_prototype");
        street.addProperty("prototypePlacementStatus", "connector_alignment_pending");
        pools.add("minecraft:village/plains/streets", pieces(street));
        input.add("candidatePools", pools);

        JsonObject trace = new BoundedJigsawSolver().solve(input);

        assertEquals(2, trace.getAsJsonArray("acceptedPieces").size());
        JsonObject child = trace.getAsJsonArray("acceptedPieces").get(1).getAsJsonObject();
        assertEquals("connector_aligned", child.get("prototypePlacementStatus").getAsString());
        assertEquals(16, child.getAsJsonObject("footprint").get("minX").getAsInt());
        assertTrue(child.getAsJsonArray("connectorRefs").get(0).getAsJsonObject()
                .get("consumedByParent").getAsBoolean());
        assertFalse(trace.getAsJsonObject("plan").getAsJsonObject("quality")
                .get("startPieceOnly").getAsBoolean());
    }

    @Test
    void reportsAlignmentFailureForIncompatibleChildPoolPrototype() {
        JsonObject input = baseInput(1024);
        input.add("startPieces", pieces(piece("start_piece_1", "minecraft:start_house",
                "minecraft:village/plains/town_centers", 0, 0, 15, 15,
                connector("door_east", "minecraft:street", "minecraft:street",
                        "minecraft:village/plains/streets"))));
        JsonObject pools = new JsonObject();
        JsonObject street = piece("street_piece_bad", "minecraft:street_1",
                "minecraft:village/plains/streets", 0, 0, 15, 15,
                connector("street_north", "minecraft:house", "minecraft:house",
                        "minecraft:village/plains/terminators", 0, 64, 0, "north"));
        street.addProperty("adapterScope", "child_pool_prototype");
        street.addProperty("prototypePlacementStatus", "connector_alignment_pending");
        pools.add("minecraft:village/plains/streets", pieces(street));
        input.add("candidatePools", pools);

        JsonObject trace = new BoundedJigsawSolver().solve(input);

        assertEquals(1, trace.getAsJsonArray("acceptedPieces").size());
        assertEquals(1, trace.getAsJsonArray("rejectedPieces").size());
        assertTrue(trace.getAsJsonArray("rejectedPieces").toString()
                .contains("JIGSAW_CONNECTOR_ALIGNMENT_FAILED"));
        assertTrue(trace.getAsJsonArray("stoppedBranches").toString()
                .contains("JIGSAW_CONNECTOR_ALIGNMENT_FAILED"));
    }

    @Test
    void reportsStructuredFailureWhenNoPieceCanBeAccepted() {
        JsonObject input = baseInput(1024);
        input.add("startPieces", pieces(piece("start_piece_outside", "minecraft:start_house",
                "minecraft:village/plains/town_centers", 64, 0, 79, 15)));
        input.add("candidatePools", new JsonObject());

        JsonObject trace = new BoundedJigsawSolver().solve(input);

        assertEquals(0, trace.getAsJsonArray("acceptedPieces").size());
        assertTrue(trace.getAsJsonObject("failureSummary").has("JIGSAW_NO_ACCEPTED_PIECE"));
        assertTrue(trace.getAsJsonArray("rejectedPieces").toString()
                .contains("JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA"));
        assertTrue(trace.getAsJsonArray("stoppedBranches").toString()
                .contains("JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA"));
    }

    @Test
    void runtimeRuleRejectsChildPieceBeforeItCanBeAccepted() {
        JsonObject input = baseInput(1024);
        input.add("startPieces", pieces(piece("start_piece_1", "minecraft:start_house",
                "minecraft:village/plains/town_centers", 0, 0, 15, 15,
                connector("door_east", "minecraft:street", "minecraft:street",
                        "minecraft:village/plains/streets"))));
        JsonObject pools = new JsonObject();
        JsonObject street = piece("street_piece_1", "minecraft:street_1",
                "minecraft:village/plains/streets", 16, 0, 31, 15);
        street.addProperty("attachTarget", "minecraft:street");
        pools.add("minecraft:village/plains/streets", pieces(street));
        input.add("candidatePools", pools);

        JsonObject trace = new BoundedJigsawSolver((piece, footprint, visibleAreaCostSoFar, targetAreaBlocks) -> {
            JsonArray rules = new JsonArray();
            JsonObject rule = new JsonObject();
            rule.addProperty("ruleId", "terrain_probe");
            rule.addProperty("status", "failed");
            rule.addProperty("reasonCode", "JIGSAW_RULE_TERRAIN_TOO_UNEVEN");
            rules.add(rule);
            if ("street_piece_1".equals(piece.get("pieceId").getAsString())) {
                return BoundedJigsawSolver.PieceDecision.reject("JIGSAW_RULE_TERRAIN_TOO_UNEVEN", rules);
            }
            return BoundedJigsawSolver.PieceDecision.accept(new JsonArray());
        }).solve(input);

        assertEquals(1, trace.getAsJsonArray("acceptedPieces").size());
        assertEquals("start_piece_1", trace.getAsJsonArray("acceptedPieces")
                .get(0).getAsJsonObject().get("pieceId").getAsString());
        assertTrue(trace.getAsJsonArray("rejectedPieces").toString()
                .contains("JIGSAW_RULE_TERRAIN_TOO_UNEVEN"));
        assertTrue(trace.getAsJsonArray("rejectedPieces").toString().contains("terrain_probe"));
    }

    @Test
    void rejectsChildPieceOverlappingAcceptedPiece() {
        JsonObject input = baseInput(1024);
        input.add("startPieces", pieces(piece("start_piece_1", "minecraft:start_house",
                "minecraft:village/plains/town_centers", 0, 0, 15, 15,
                connector("door_east", "minecraft:street", "minecraft:street",
                        "minecraft:village/plains/streets"))));
        JsonObject pools = new JsonObject();
        JsonObject overlapping = piece("street_piece_overlap", "minecraft:street_1",
                "minecraft:village/plains/streets", 8, 0, 23, 15);
        overlapping.addProperty("attachTarget", "minecraft:street");
        pools.add("minecraft:village/plains/streets", pieces(overlapping));
        input.add("candidatePools", pools);

        JsonObject trace = new BoundedJigsawSolver().solve(input);

        assertEquals(1, trace.getAsJsonArray("acceptedPieces").size());
        assertEquals("start_piece_1", trace.getAsJsonArray("acceptedPieces")
                .get(0).getAsJsonObject().get("pieceId").getAsString());
        JsonObject rejected = trace.getAsJsonArray("rejectedPieces").get(0).getAsJsonObject();
        assertEquals("JIGSAW_PIECE_RESERVED_CONFLICT", rejected.get("reasonCode").getAsString());
        assertTrue(rejected.getAsJsonArray("ruleResults").toString().contains("runtime_occupied"));
    }

    private JsonObject baseInput(int targetAreaBlocks) {
        JsonObject input = new JsonObject();
        input.addProperty("sourceStructureId", "minecraft:village_plains");
        input.addProperty("seedKey", "city_test:bounded");
        input.addProperty("startPool", "minecraft:village/plains/town_centers");
        input.addProperty("targetAreaBlocks", targetAreaBlocks);
        input.addProperty("maxPieces", 2);
        input.addProperty("maxDepth", 2);
        input.add("constraintField", constraintField());
        return input;
    }

    private JsonObject constraintField() {
        JsonObject field = new JsonObject();
        field.addProperty("schemaVersion", "city_constraint_field.v0.1");
        field.addProperty("zonePatchId", "core");
        field.add("allowedArea", bounds(0, 0, 31, 31));
        field.addProperty("originBlockX", 0);
        field.addProperty("originBlockZ", 0);
        field.addProperty("cellStepBlocks", 16);
        field.addProperty("cellsX", 2);
        field.addProperty("cellsZ", 2);
        JsonArray cells = new JsonArray();
        cells.add(cell(0, 0));
        cells.add(cell(16, 0));
        cells.add(cell(0, 16));
        cells.add(cell(16, 16));
        field.add("buildableCells", cells);
        field.add("reservedCells", new JsonArray());
        field.add("occupiedFootprints", new JsonArray());
        return field;
    }

    private JsonObject piece(String pieceId, String templateId, String poolId,
                             int minX, int minZ, int maxX, int maxZ, JsonObject... connectors) {
        JsonObject piece = new JsonObject();
        piece.addProperty("pieceId", pieceId);
        piece.addProperty("templateId", templateId);
        piece.addProperty("poolId", poolId);
        piece.add("anchorBlock", block(minX, 64, minZ));
        piece.addProperty("rotation", "NONE");
        piece.add("footprint", bounds(minX, minZ, maxX, maxZ));
        piece.addProperty("visibleAreaCost", (maxX - minX + 1) * (maxZ - minZ + 1));
        JsonArray connectorArray = new JsonArray();
        for (JsonObject connector : connectors) {
            connectorArray.add(connector);
        }
        piece.add("connectorRefs", connectorArray);
        return piece;
    }

    private JsonObject connector(String connectorId, String name, String target, String pool) {
        return connector(connectorId, name, target, pool, 15, 64, 0, "east");
    }

    private JsonObject connector(String connectorId, String name, String target, String pool,
                                 int worldX, int worldY, int worldZ, String front) {
        JsonObject connector = new JsonObject();
        connector.addProperty("connectorId", connectorId);
        connector.addProperty("name", name);
        connector.addProperty("target", target);
        connector.addProperty("pool", pool);
        connector.add("worldBlock", block(worldX, worldY, worldZ));
        connector.add("localBlock", block(0, 0, 0));
        connector.addProperty("front", front);
        return connector;
    }

    private JsonArray pieces(JsonObject... pieces) {
        JsonArray array = new JsonArray();
        for (JsonObject piece : pieces) {
            array.add(piece);
        }
        return array;
    }

    private JsonObject cell(int blockMinX, int blockMinZ) {
        JsonObject obj = new JsonObject();
        obj.addProperty("blockMinX", blockMinX);
        obj.addProperty("blockMinZ", blockMinZ);
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

    private JsonObject block(int x, int y, int z) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        return obj;
    }
}
