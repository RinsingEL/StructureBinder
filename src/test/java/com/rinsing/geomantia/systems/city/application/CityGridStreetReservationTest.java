package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityGridStreetReservationTest {
    private final CityBlueprintGroupLayoutPlanner layout = new CityBlueprintGroupLayoutPlanner();

    @Test
    void mixedSizeCandidateMustPreserveTheContinuousRowStreetBeforeCommit() {
        var parameters = layout.parameters("GRID", CityBlueprint.DensityClass.BALANCED);
        var windmill = new CityGridStreetReservation.Placement(
                0, 0, new BlockBounds(0, 0, 38, 32));

        String rejected = CityGridStreetReservation.rejectionReason(List.of(windmill),
                new CityGridStreetReservation.Placement(
                        1, 0, new BlockBounds(39, 0, 50, 10)), parameters);
        String accepted = CityGridStreetReservation.rejectionReason(List.of(windmill),
                new CityGridStreetReservation.Placement(
                        1, 0, new BlockBounds(46, 0, 57, 10)), parameters);

        assertTrue(rejected.startsWith("GRID_ROW_STREET_CLEARANCE_RESERVED:"));
        assertEquals("", accepted);
    }

    @Test
    void laterBuildingInTheSameRowCannotConsumeAnAlreadyReservedStreet() {
        var parameters = layout.parameters("GRID", CityBlueprint.DensityClass.BALANCED);
        var committed = List.of(
                new CityGridStreetReservation.Placement(0, 0,
                        new BlockBounds(0, 0, 20, 10)),
                new CityGridStreetReservation.Placement(1, 0,
                        new BlockBounds(30, 0, 40, 10)));

        String rejected = CityGridStreetReservation.rejectionReason(committed,
                new CityGridStreetReservation.Placement(
                        0, 1, new BlockBounds(0, 20, 25, 30)), parameters);

        assertTrue(rejected.startsWith("GRID_ROW_STREET_CLEARANCE_RESERVED:"));
    }

    @Test
    void missingTerrainSlotMayLeaveAWholeRowEmptyWithoutInventingAReservation() {
        var parameters = layout.parameters("GRID", CityBlueprint.DensityClass.BALANCED);
        var committed = List.of(new CityGridStreetReservation.Placement(
                0, 0, new BlockBounds(0, 0, 20, 10)));

        String accepted = CityGridStreetReservation.rejectionReason(committed,
                new CityGridStreetReservation.Placement(
                        2, 0, new BlockBounds(60, 0, 70, 10)), parameters);

        assertEquals("", accepted);
    }
}
