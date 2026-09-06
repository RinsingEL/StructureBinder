package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CityTrafficConnectivityTest {
    @Test void generatedRoadsConnectARealmWithoutBuildingGrowth() {
        JsonObject plan = JsonParser.parseString("""
            {"connections":[
            {"sourceGroupId":"civic","targetGroupId":"farm","streetBandIds":["a"]},
            {"sourceGroupId":"civic","targetGroupId":"market","streetBandIds":["b"]},
            {"sourceGroupId":"civic","targetGroupId":"homes","streetBandIds":["c"]},
            {"sourceGroupId":"market","targetGroupId":"defense","streetBandIds":["d"]}]}
            """).getAsJsonObject();
        Set<String> groups = Set.of("civic", "farm", "market", "homes", "defense");
        assertTrue(new CityTrafficConnectivity(plan).connected(groups));
        plan.getAsJsonArray("connections").remove(3);
        assertFalse(new CityTrafficConnectivity(plan).connected(groups));
    }

    @Test void intendedOrSkippedRoadsAreNotConnectivityEvidence() {
        JsonObject plan = JsonParser.parseString("{connections:[{sourceGroupId:'a',targetGroupId:'b',streetBandIds:[]}],skippedConnections:[{sourceGroupId:'a',targetGroupId:'b'}]}").getAsJsonObject();
        assertFalse(new CityTrafficConnectivity(plan).connected(Set.of("a", "b")));
    }

    @Test void onlyActuallyPlannedBridgeBandsCount() {
        JsonObject plan = JsonParser.parseString("{bridgeConnections:[{fromGroupId:'a',toGroupId:'b',status:'PLANNED_BY_CITY',streetBandIds:['deck']}]}").getAsJsonObject();
        assertTrue(new CityTrafficConnectivity(plan).connected(Set.of("a", "b")));
        plan.getAsJsonArray("bridgeConnections").get(0).getAsJsonObject().addProperty("status", "DELEGATED");
        assertFalse(new CityTrafficConnectivity(plan).connected(Set.of("a", "b")));
    }

    @Test void finalFailureRoutingRequiresExplicitDesignEvidence() {
        String code = "CITY_BLUEPRINT_COMPILED_ANCHOR_FINALIZATION_FAILED";
        JsonObject evidence = JsonParser.parseString("{qualityReport:{hardBlocks:['compilationAcceptance: CITY_MAIN_ROAD_CONNECTION_REQUIRED trafficGroups=2']}}").getAsJsonObject();
        assertFalse(CityBlueprintFailureRouting.isProgramFailure(code, evidence));
        evidence.getAsJsonObject("qualityReport").getAsJsonArray("hardBlocks").add("STRUCTURE_RELATION_GRAPH_DISCONNECTED trafficGroups=2");
        assertFalse(CityBlueprintFailureRouting.isProgramFailure(code, evidence));
        evidence.getAsJsonObject("qualityReport").getAsJsonArray("hardBlocks").add("COLLISION");
        assertTrue(CityBlueprintFailureRouting.isProgramFailure(code, evidence));
        assertTrue(CityBlueprintFailureRouting.isProgramFailure(code, new JsonObject()));
    }
}
