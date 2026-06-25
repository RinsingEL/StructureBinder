package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityConstraintField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CityConstraintFieldTest {
    @Test
    void acceptsFootprintInsideBuildableCells() {
        CityConstraintField.ValidationResult result = CityConstraintField.fromJson(field())
                .validatePiece(new BlockBounds(0, 0, 15, 15), 512);

        assertTrue(result.passed());
        assertEquals("", result.reasonCode());
    }

    @Test
    void rejectsReservedCellsBeforeBuildableMiss() {
        JsonObject field = field();
        field.add("reservedCells", cells(new int[][]{{16, 0}}));

        CityConstraintField.ValidationResult result = CityConstraintField.fromJson(field)
                .validatePiece(new BlockBounds(16, 0, 31, 15), 512);

        assertEquals("JIGSAW_PIECE_RESERVED_CONFLICT", result.reasonCode());
    }

    @Test
    void rejectsFootprintOutsideBuildableCells() {
        JsonObject field = field();
        field.add("buildableCells", cells(new int[][]{{0, 0}}));

        CityConstraintField.ValidationResult result = CityConstraintField.fromJson(field)
                .validatePiece(new BlockBounds(16, 0, 31, 15), 512);

        assertEquals("JIGSAW_BRANCH_OUT_OF_ALLOWED_AREA", result.reasonCode());
    }

    @Test
    void rejectsOccupiedFootprintOverlap() {
        JsonObject field = field();
        JsonArray occupied = new JsonArray();
        JsonObject item = new JsonObject();
        item.add("footprint", bounds(8, 8, 20, 20));
        occupied.add(item);
        field.add("occupiedFootprints", occupied);

        CityConstraintField.ValidationResult result = CityConstraintField.fromJson(field)
                .validatePiece(new BlockBounds(0, 0, 15, 15), 512);

        assertEquals("JIGSAW_PIECE_RESERVED_CONFLICT", result.reasonCode());
    }

    @Test
    void rejectsAreaBudgetOverflow() {
        CityConstraintField.ValidationResult result = CityConstraintField.fromJson(field())
                .validatePiece(new BlockBounds(0, 0, 31, 31), 128);

        assertEquals("JIGSAW_AREA_BUDGET_REACHED", result.reasonCode());
    }

    @Test
    void rejectsMissingConstraintField() {
        CityConstraintField.ValidationResult result = CityConstraintField.fromJson(new JsonObject())
                .validatePiece(new BlockBounds(0, 0, 15, 15), 512);

        assertEquals("CITY_CONSTRAINT_FIELD_MISSING", result.reasonCode());
    }

    private JsonObject field() {
        JsonObject field = new JsonObject();
        field.addProperty("schemaVersion", "city_constraint_field.v0.1");
        field.addProperty("zonePatchId", "zone_core");
        field.add("allowedArea", bounds(0, 0, 31, 31));
        field.addProperty("originBlockX", 0);
        field.addProperty("originBlockZ", 0);
        field.addProperty("cellStepBlocks", 16);
        field.addProperty("cellsX", 2);
        field.addProperty("cellsZ", 2);
        field.add("buildableCells", cells(new int[][]{{0, 0}, {16, 0}, {0, 16}, {16, 16}}));
        field.add("reservedCells", new JsonArray());
        field.add("occupiedFootprints", new JsonArray());
        return field;
    }

    private JsonArray cells(int[][] blockMinPairs) {
        JsonArray array = new JsonArray();
        for (int[] pair : blockMinPairs) {
            JsonObject cell = new JsonObject();
            cell.addProperty("blockMinX", pair[0]);
            cell.addProperty("blockMinZ", pair[1]);
            array.add(cell);
        }
        return array;
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
