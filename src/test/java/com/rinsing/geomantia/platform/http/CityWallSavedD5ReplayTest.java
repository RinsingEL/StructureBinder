package com.rinsing.geomantia.platform.http;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.CityWallPlanner;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in official D5 replay. All writes are confined to a temporary copy. */
class CityWallSavedD5ReplayTest {
    @TempDir Path root;

    @Test
    @EnabledIfEnvironmentVariable(named="GEOMANTIA_WALL_REPLAY_RUN", matches=".+")
    void savedCityCompilesThroughOfficialD5WithOutwardStraightWallTowers() throws Exception {
        Path source = Path.of(System.getenv("GEOMANTIA_WALL_REPLAY_RUN"));
        String run = source.getFileName().toString();
        String city = System.getenv("GEOMANTIA_WALL_REPLAY_CITY");
        Path target = root.resolve(run);
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path copy = target.resolve(source.relativize(file));
                Files.createDirectories(copy.getParent()); Files.copy(file,copy);
            }
        }
        Path cityDir=target.resolve("city_test_runs/"+city+"/steps");
        Path input=cityDir.resolve("d4/structure_anchor_map.json");
        String original=Files.readString(input);
        JsonObject response=CityPlanningEndpointHandler.handlePlanD5(root,run,city,24,4);
        assertTrue(response.get("ok").getAsBoolean(),response.toString());
        assertEquals(original,Files.readString(input));
        JsonObject reservation=JsonParser.parseString(Files.readString(cityDir.resolve("d5/wall_reservation_plan.json"))).getAsJsonObject();
        JsonObject plan=new CityWallPlanner().plan(reservation,CityWallPlanner.Options.defaults());
        for (JsonElement value:plan.getAsJsonArray("wallNodes")) {
            JsonObject node=value.getAsJsonObject();
            assertEquals("LONG_WALL_SUPPORT",node.get("reasonCode").getAsString());
            assertTrue(java.util.Set.of("NORTH","EAST","SOUTH","WEST").contains(node.get("facing").getAsString()));
        }
        System.out.println("WALL_D5_REPLAY ok=true anchors="+JsonParser.parseString(original).getAsJsonObject().getAsJsonArray("anchors").size()
                +" gates="+reservation.getAsJsonArray("gateSlots").size()+" towers="+plan.getAsJsonArray("wallNodes").size());
        Path evidence=Path.of("build/sandspring-wall");Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("d5-response.json"),response.toString());
        Files.writeString(evidence.resolve("wall-reservation.json"),reservation.toString());
        Files.writeString(evidence.resolve("wall-plan.json"),plan.toString());
    }
}
