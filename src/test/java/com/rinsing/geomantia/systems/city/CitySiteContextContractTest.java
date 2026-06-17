package com.rinsing.geomantia.systems.city;

import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CitySiteContextContractTest {

    private final CityPlanningConfig config = CityPlanningConfig.defaults();
    private final CitySiteContextBuilder builder = new CitySiteContextBuilder(config);

    @Test
    void missingSchemaVersion_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new CitySiteContext(null, "city1", "realm1", "overworld",
                        "seed1", "cand1",
                        new BlockBounds(0, 0, 100, 100),
                        new PlanningGrid(0, 0, 16, 10, 10),
                        new BlockPoint(50, 50), "capital",
                        CityScale.VILLAGE, 256, List.of(),
                        TerritoryCheckResult.INSIDE));
    }

    @Test
    void missingCityId_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new CitySiteContext(CitySiteContext.CURRENT_SCHEMA_VERSION, null, "realm1",
                        "overworld", "seed1", "cand1",
                        new BlockBounds(0, 0, 100, 100),
                        new PlanningGrid(0, 0, 16, 10, 10),
                        new BlockPoint(50, 50), "capital",
                        CityScale.VILLAGE, 256, List.of(),
                        TerritoryCheckResult.INSIDE));
    }

    @Test
    void blankCityId_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new CitySiteContext(CitySiteContext.CURRENT_SCHEMA_VERSION, "  ", "realm1",
                        "overworld", "seed1", "cand1",
                        new BlockBounds(0, 0, 100, 100),
                        new PlanningGrid(0, 0, 16, 10, 10),
                        new BlockPoint(50, 50), "capital",
                        CityScale.VILLAGE, 256, List.of(),
                        TerritoryCheckResult.INSIDE));
    }

    @Test
    void missingGrid_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new CitySiteContext(CitySiteContext.CURRENT_SCHEMA_VERSION, "city1", "realm1",
                        "overworld", "seed1", "cand1",
                        new BlockBounds(0, 0, 100, 100),
                        null, new BlockPoint(50, 50), "capital",
                        CityScale.VILLAGE, 256, List.of(),
                        TerritoryCheckResult.INSIDE));
    }

    @Test
    void schemaVersionConstant_isCorrect() {
        assertEquals("city_site_context.v0.1", CitySiteContext.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void planningGrid_negativeCellStep_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new PlanningGrid(0, 0, 0, 10, 10));
    }

    @Test
    void planningGrid_zeroCells_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new PlanningGrid(0, 0, 16, 0, 10));
    }

    @Test
    void blockBounds_minGreaterThanMax_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new BlockBounds(5, 5, 3, 3));
    }

    @Test
    void validMinimalContext_producesJsonWithRequiredFields() {
        CitySiteContext ctx = builder.build(
                "city_test", "realm_salt", "overworld",
                "seed_1", "cand_1",
                0, 0, "capital", "village",
                64, 4, null);

        String json = ctx.asJson().toString();
        assertTrue(json.contains("city_site_context.v0.1"));
        assertTrue(json.contains("city_test"));
        assertTrue(json.contains("realm_salt"));
        assertTrue(json.contains("grid"));
        assertTrue(json.contains("bounds"));
        assertTrue(json.contains("anchorBlock"));
        assertTrue(json.contains("entryCandidates"));
        assertTrue(json.contains("territoryCheckResult"));
    }

    @Test
    void cityLandformReviewPackage_schemaVersion_isCorrect() {
        assertEquals("city_landform_review.v0.1", CityLandformReviewPackage.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void cityLandformReviewPackage_missingRequired_throws() {
        PlanningGrid grid = new PlanningGrid(0, 0, 16, 10, 10);
        TargetScale ts = new TargetScale(CityScale.VILLAGE, 256, 16);

        assertThrows(IllegalArgumentException.class, () ->
                new CityLandformReviewPackage(null, "city1", grid, ts, "",
                        List.of(), List.of(), List.of(), "", List.of()));

        assertThrows(IllegalArgumentException.class, () ->
                new CityLandformReviewPackage(CityLandformReviewPackage.CURRENT_SCHEMA_VERSION,
                        null, grid, ts, "", List.of(), List.of(), List.of(), "", List.of()));

        assertThrows(IllegalArgumentException.class, () ->
                new CityLandformReviewPackage(CityLandformReviewPackage.CURRENT_SCHEMA_VERSION,
                        "city1", null, ts, "", List.of(), List.of(), List.of(), "", List.of()));
    }

    @Test
    void blockBounds_overlaps_correctDetection() {
        BlockBounds a = new BlockBounds(0, 0, 100, 100);
        BlockBounds b = new BlockBounds(50, 50, 150, 150);
        BlockBounds c = new BlockBounds(200, 200, 300, 300);
        assertTrue(a.overlaps(b));
        assertFalse(a.overlaps(c));
    }
}
