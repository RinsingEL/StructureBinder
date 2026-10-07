package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.*;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.testsupport.SyntheticAtlasSampler;
import com.rinsing.geomantia.systems.gis.testsupport.SyntheticTerrainProfile;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FixedRealmTerritoryTest {
    @TempDir Path temp;
    private final PlanningAreaAccessConfig access = new PlanningAreaAccessConfig(false, 0, 0, Set.of("minecraft:overworld"));

    private RealmPlanningService world(String id) throws Exception {
        Path root = temp.resolve("debug");
        var terrain = new SyntheticTerrainProfile() {
            public double seaLevel() { return 62; }
            public double elevationAt(double x, double z) {
                return Math.abs(z) < 240 && (Math.abs(x + 512) < 240 || Math.abs(x - 512) < 240) ? 80 : 30;
            }
        };
        var survey = new WorldSurveyRunner(root, GisClassifierConfig.defaults()).run(
                new WorldSurveyRunner.Config(id, "minecraft:overworld", "enclosed_islands", 0,
                        0, 0, 1536, 128, 32, 8, SampleMode.PRIOR, WorldSurveyRunner.ResumePolicy.RESCAN),
                new SyntheticAtlasSampler(terrain));
        var service = new RealmPlanningService(root, access);
        service.runW(survey, null);
        return service;
    }

    private JsonArray profiles(RealmPlanningService service, String id) throws Exception {
        JsonArray profiles = service.prepareT1(id, null, 2, "", true).getAsJsonArray("realmProfiles");
        JsonObject world = JsonParser.parseString(Files.readString(temp.resolve("debug/" + id + "/world_patch_map.json"))).getAsJsonObject();
        List<String> continents = new ArrayList<>();
        for (var entry : world.getAsJsonArray("cells")) {
            var cell = entry.getAsJsonObject();
            if (cell.has("continentId") && !continents.contains(cell.get("continentId").getAsString()))
                continents.add(cell.get("continentId").getAsString());
        }
        assertEquals(2, continents.size());
        for (int i = 0; i < 2; i++) profiles.get(i).getAsJsonObject().addProperty("targetContinentId", continents.get(i));
        var fixed = profiles.get(0).getAsJsonObject();
        fixed.add("territoryPolicy", JsonParser.parseString("{mode:'fixed',nearshoreRadiusCells:2}"));
        // A fixed island must not be shrunk even by a zero area budget or no-crossing preference.
        var scale = fixed.getAsJsonObject("scalePlan");
        for (String key : List.of("minAreaRatio", "targetAreaRatio", "maxAreaRatio")) scale.addProperty(key, 0);
        fixed.getAsJsonObject("expansionStyle").addProperty("seaCrossingPolicy", "none");
        return profiles;
    }

    @Test void fixedIslandAndNearshoreSurviveBothModelsAndCheckpointRestore() throws Exception {
        String id = "fixed_both_models";
        var service = world(id);
        JsonArray profiles = profiles(service, id);
        service.prepareT1(id, profiles, 2, "", false);
        String fixed = profiles.get(0).getAsJsonObject().get("realmId").getAsString();
        String continent = profiles.get(0).getAsJsonObject().get("targetContinentId").getAsString();
        for (var entry : profiles) {
            String realm = entry.getAsJsonObject().get("realmId").getAsString();
            var point = service.suggestedPoint(id, realm);
            service.selectT2(id, realm, point.x(), point.z(), null, "test", "debug", false);
        }
        JsonObject map = JsonParser.parseString(Files.readString(temp.resolve("debug/" + id + "/world_patch_map.json"))).getAsJsonObject();
        Map<String, JsonObject> world = new HashMap<>();
        Set<String> expectedLand = new HashSet<>();
        for (var entry : map.getAsJsonArray("cells")) {
            var cell = entry.getAsJsonObject(); String key = cell.get("gridX") + "," + cell.get("gridZ");
            world.put(key, cell);
            if (cell.has("continentId") && continent.equals(cell.get("continentId").getAsString())) expectedLand.add(key);
        }
        JsonArray previous = null;
        for (String model : List.of("quota_frontier", "action_budget")) {
            var result = service.expandT3(id, "", true, "strict", model).getAsJsonObject("territoryMap");
            Set<String> land = new HashSet<>(); int marine = 0; JsonArray fixedCells = new JsonArray();
            for (var entry : result.getAsJsonArray("territoryCells")) {
                var cell = entry.getAsJsonObject(); if (!fixed.equals(cell.get("realmId").getAsString())) continue;
                fixedCells.add(cell);
                String key = cell.get("gridX") + "," + cell.get("gridZ");
                if ("nearshore".equals(cell.get("territoryType").getAsString())) {
                    marine++; assertEquals("water", world.get(key).get("landWater").getAsString());
                    int x = cell.get("gridX").getAsInt(), z = cell.get("gridZ").getAsInt();
                    assertTrue(expectedLand.stream().anyMatch(k -> {
                        String[] p = k.split(","); return Math.abs(x - Integer.parseInt(p[0])) + Math.abs(z - Integer.parseInt(p[1])) <= 2;
                    }));
                } else land.add(key);
            }
            assertEquals(expectedLand, land); assertTrue(marine > 0);
            for (var entry : result.getAsJsonArray("realmStats")) {
                var stat = entry.getAsJsonObject(); if (!fixed.equals(stat.get("realmId").getAsString())) continue;
                assertEquals(land.size(), stat.get("areaCells").getAsInt());
                assertEquals(marine, stat.get("nearshoreAreaCells").getAsInt());
            }
            if (previous != null) assertEquals(previous, fixedCells); previous = fixedCells;
        }
        service = new RealmPlanningService(temp.resolve("debug"), access);
        var restored = service.expandT3(id, "", true, "strict", "action_budget").getAsJsonObject("territoryMap");
        JsonArray fixedCells = new JsonArray();
        for (var entry : restored.getAsJsonArray("territoryCells"))
            if (fixed.equals(entry.getAsJsonObject().get("realmId").getAsString())) fixedCells.add(entry);
        assertEquals(previous, fixedCells);
        Path checkpoint = temp.resolve("debug/" + id + "/realm_territory_map.json");
        JsonObject tampered = JsonParser.parseString(Files.readString(checkpoint)).getAsJsonObject();
        JsonArray altered = new JsonArray();
        for (var entry : tampered.getAsJsonArray("territoryCells")) {
            var cell = entry.getAsJsonObject();
            if (!fixed.equals(cell.get("realmId").getAsString())) altered.add(cell);
        }
        tampered.add("territoryCells", altered); Files.writeString(checkpoint, tampered.toString());
        var corrupt = new RealmPlanningService(temp.resolve("debug"), access);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> corrupt.expandT3(id, "", true, "strict", "action_budget"))
                .getMessage().contains("fixed territory differs"));
    }

    @Test void overlappingFixedClaimsAreRejectedBeforeReplacingAcceptedProfiles() throws Exception {
        String id = "fixed_conflict"; var service = world(id); var profiles = profiles(service, id);
        Path file = temp.resolve("debug/" + id + "/realm_profiles.json"); String before = Files.readString(file);
        profiles.get(1).getAsJsonObject().addProperty("targetContinentId",
                profiles.get(0).getAsJsonObject().get("targetContinentId").getAsString());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.prepareT1(id, profiles, 2, "", false))
                .getMessage().contains("FIXED_TERRITORY_CONFLICT"));
        assertEquals(before, Files.readString(file));
    }
    @Test void nearshoreOverlapAndProtectedScopeRejectWithoutShrinking() throws Exception {
        String id = "fixed_nearshore_conflict"; var service = world(id); var profiles = profiles(service, id);
        Path file = temp.resolve("debug/" + id + "/realm_profiles.json"); String before = Files.readString(file);
        for (var entry : profiles) entry.getAsJsonObject().add("territoryPolicy",
                JsonParser.parseString("{mode:'fixed',nearshoreRadiusCells:3}"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.prepareT1(id, profiles, 2, "", false))
                .getMessage().contains("FIXED_TERRITORY_CONFLICT"));
        assertEquals(before, Files.readString(file));
        profiles.get(0).getAsJsonObject().getAsJsonObject("territoryPolicy").addProperty("nearshoreRadiusCells", 16);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.prepareT1(id, profiles, 2, "", false))
                .getMessage().contains("FIXED_TERRITORY_OUTSIDE_SURVEY"));
        assertEquals(before, Files.readString(file));
        profiles.get(0).getAsJsonObject().getAsJsonObject("territoryPolicy").addProperty("nearshoreRadiusCells", 2);
        profiles.get(1).getAsJsonObject().remove("territoryPolicy");
        var protectedAccess = new PlanningAreaAccessConfig(true, 128, 128, Set.of("minecraft:overworld"));
        var protectedService = new RealmPlanningService(temp.resolve("debug"), protectedAccess);
        var rejected = assertThrows(IllegalArgumentException.class, () -> protectedService.prepareT1(id, profiles, 2, "", false));
        assertTrue(rejected.getMessage().contains("FIXED_TERRITORY_RESERVED"), rejected.getMessage());
        assertEquals(before, Files.readString(file));
    }

}
