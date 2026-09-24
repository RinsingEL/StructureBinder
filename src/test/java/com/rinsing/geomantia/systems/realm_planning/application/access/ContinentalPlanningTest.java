package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContinentalPlanningTest {
    @TempDir Path root;
    private JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    @Test void completesRosterAcrossCountriesBeforeCitiesAndDoesNotFollowCountryAcrossContinents() throws Exception {
        Path run=Files.createDirectories(root.resolve("realm_debug/run"));
        Files.writeString(run.resolve("world_feature_grid.json"),GeographicRegionsTest.grid(0,110,0,2,(x,z)->x<5||x>100).toString());
        Files.writeString(run.resolve("realm_territory_map.json"),"""
                {"territoryCells":[
                {"gridX":0,"gridZ":0,"status":"owned","realmId":"a"},
                {"gridX":1,"gridZ":0,"status":"owned","realmId":"b"},
                {"gridX":105,"gridZ":0,"status":"owned","realmId":"a"},
                {"gridX":106,"gridZ":0,"status":"owned","realmId":"c"}]}
                """);
        var planning=ContinentalPlanning.load(run);
        assertTrue(planning.rank(0,0)<planning.rank(105*128,0));
        var seeds=json("""
                {"citySeeds":[{"citySeedId":"near","realmId":"a","anchorBlock":{"x":128,"z":0}},
                {"citySeedId":"far","realmId":"a","anchorBlock":{"x":13440,"z":0}}]}
                """);
        assertEquals("b",planning.missingRealm(Set.of("a"),seeds,null));
        assertEquals("",planning.missingRealm(Set.of("a","b"),seeds,null));
        var queue=json("""
                {"items":[{"citySeedId":"near","status":"waiting_for_generation"},
                {"citySeedId":"far","status":"pending"}]}
                """);
        assertEquals("c",planning.missingRealm(Set.of("a","b"),seeds,queue));
    }
}
