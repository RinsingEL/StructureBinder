package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityDesignFailureFeedbackTest {
    @Test void reportsFinalFlowerShopFailureNotEarlierRecoveredSlots() {
        JsonObject blueprint = json("{groups:[{groupId:'civic'},{groupId:'market'}]}");
        JsonObject trace = json("""
                {selections:[
                  {groupId:'civic',structureRef:'guard',status:'skipped_illegal_slot',attempts:[]},
                  {groupId:'civic',structureRef:'guard',status:'committed',attempts:[]},
                  {groupId:'market',structureRef:'flower',status:'skipped_illegal_slot',attempts:[{
                    filterReasonCounts:{OCCUPIED_ENVELOPE_OVERLAP:1},
                    failedAttemptPositions:[{anchorBlock:{x:2200,z:5064},reasonCode:'OCCUPIED_ENVELOPE_OVERLAP'}],
                    hardBlocks:['D4_ARRAY_COUNT_UNSATISFIED']}]},
                  {groupId:'market',structureRef:'flower',status:'no_legal_candidate',attempts:[{
                    filterReasonCounts:{D4_ARRAY_COUNT_UNSATISFIED:1},failedAttemptPositions:[],hardBlocks:[]}]}]}
                """);
        var result = CityDesignFailureFeedback.summarize(blueprint, trace, "CITY_BLUEPRINT_REQUIRED_STRUCTURE_NO_LEGAL_PLACEMENT");
        assertEquals(1, result.getAsJsonArray("failures").size());
        var failure = result.getAsJsonArray("failures").get(0).getAsJsonObject();
        assertEquals("$.groups[1]", failure.get("fieldPath").getAsString());
        assertEquals("/groups/1", failure.get("jsonPointer").getAsString());
        assertEquals("flower", failure.get("structureRef").getAsString());
        assertEquals(2, failure.get("attemptCount").getAsInt());
        assertEquals(1, failure.getAsJsonObject("filterReasonCounts").get("OCCUPIED_ENVELOPE_OVERLAP").getAsInt());
        assertTrue(failure.toString().contains("2200"));
        assertFalse(result.get("capacityInsufficiencyProven").getAsBoolean());
        assertTrue(result.getAsJsonArray("parameterAdjustments").isEmpty());
    }

    @Test void unknownFailureDoesNotInventGroupOrAdjustment() {
        var result = CityDesignFailureFeedback.summarize(json("{groups:[]}"), json("{}"), "UNKNOWN");
        assertTrue(result.getAsJsonArray("failures").isEmpty());
        assertTrue(result.getAsJsonArray("parameterAdjustments").isEmpty());
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
