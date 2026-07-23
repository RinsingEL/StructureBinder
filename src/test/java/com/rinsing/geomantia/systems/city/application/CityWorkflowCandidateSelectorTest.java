package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CityWorkflowCandidateSelectorTest {
    private final CityWorkflowCandidateSelector selector = new CityWorkflowCandidateSelector();

    @Test
    void choosesHighestScoredAnchorAndCarriesOwningSlot() {
        JsonObject candidateSet = JsonParser.parseString("""
                {
                  "currentSlotId": "fallback_slot",
                  "slotCandidates": [{
                    "slotId": "market_slot",
                    "candidates": [
                      {"candidateId":"low","scoreBreakdown":{"total":1.5}},
                      {"candidateId":"high","scoreBreakdown":{"total":8.0}}
                    ]
                  }]
                }
                """).getAsJsonObject();

        JsonObject chosen = selector.chooseAnchorCandidate(candidateSet);

        assertEquals("high", chosen.get("candidateId").getAsString());
        assertEquals("market_slot", chosen.get("slotId").getAsString());
    }

    @Test
    void choosesHighestScoredCompleteGroupForEachWorkflowMode() {
        JsonObject clusterSet = JsonParser.parseString("""
                {"groupCandidates":[
                  {"groupCandidateId":"first","scoreBreakdown":{"total":2}},
                  {"groupCandidateId":"best","scoreBreakdown":{"total":3}}
                ]}
                """).getAsJsonObject();
        JsonObject arraySet = JsonParser.parseString("""
                {"arrayCandidates":[
                  {"arrayCandidateId":"negative","scoreBreakdown":{"total":-1}},
                  {"arrayCandidateId":"default_score"}
                ]}
                """).getAsJsonObject();

        assertEquals("best", selector.chooseStructureClusterGroup(clusterSet)
                .get("groupCandidateId").getAsString());
        assertEquals("default_score", selector.chooseArrayCandidate(arraySet)
                .get("arrayCandidateId").getAsString());
    }

    @Test
    void preservesWorkflowErrorCodesWhenNoCandidateExists() {
        IllegalArgumentException anchorError = assertThrows(IllegalArgumentException.class,
                () -> selector.chooseAnchorCandidate(new JsonObject()));
        IllegalArgumentException groupError = assertThrows(IllegalArgumentException.class,
                () -> selector.chooseStructureClusterGroup(new JsonObject()));
        IllegalArgumentException arrayError = assertThrows(IllegalArgumentException.class,
                () -> selector.chooseArrayCandidate(new JsonObject()));

        assertEquals("WORKFLOW_NO_D4_CANDIDATE: current slot produced no candidate.", anchorError.getMessage());
        assertEquals("WORKFLOW_NO_D4_STRUCTURE_CLUSTER_GROUP: no complete group candidate.",
                groupError.getMessage());
        assertEquals("WORKFLOW_NO_D4_ARRAY_CANDIDATE: no complete array candidate.", arrayError.getMessage());
    }
}
