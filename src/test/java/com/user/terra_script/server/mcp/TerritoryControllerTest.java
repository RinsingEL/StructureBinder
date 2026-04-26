package com.user.terra_script.server.mcp;

import com.user.terra_script.world.TerritoryManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerritoryControllerTest {
    @Test
    void t3RunContinentTreatsZeroAreaAsConflict() {
        TerritoryManager.TerritoryResult han = result("han@c7", "han", 7, 0, 0);
        TerritoryManager.TerritoryResult qin = result("qin@c7", "qin", 7, 0, 0);

        TerritoryController.T3RunContinentResponse response =
                TerritoryController.buildT3RunContinentResponse(7, List.of(qin, han));

        assertEquals(409, response.statusCode);
        assertEquals(7, response.body.get("continent_id").getAsInt());
        assertEquals(2, response.body.get("exported_count").getAsInt());
        assertEquals(0, response.body.get("claimed_total").getAsLong());
        assertEquals(0, response.body.get("wild_total").getAsLong());
        assertEquals("T3 produced zero claimed chunks for continent 7", response.body.get("error").getAsString());
        assertTrue(response.body.get("hint").getAsString().contains("W3-W4 scan cache"));
        assertEquals(2, response.body.getAsJsonArray("territories").size());
        assertEquals("han", response.body.getAsJsonArray("territories")
                .get(0).getAsJsonObject().get("territory_id").getAsString());
        assertEquals(0, response.body.getAsJsonArray("territories")
                .get(0).getAsJsonObject().get("claimed_chunks").getAsInt());
        assertEquals(0, response.body.getAsJsonArray("territories")
                .get(0).getAsJsonObject().get("wild_chunks").getAsInt());
    }

    @Test
    void t3RunContinentReportsAreaSummaryOnSuccess() {
        TerritoryManager.TerritoryResult han = result("han@c7", "han", 7, 2, 1);
        TerritoryManager.TerritoryResult qin = result("qin@c7", "qin", 7, 3, 0);

        TerritoryController.T3RunContinentResponse response =
                TerritoryController.buildT3RunContinentResponse(7, List.of(han, qin));

        assertEquals(200, response.statusCode);
        assertEquals(2, response.body.get("exported_count").getAsInt());
        assertEquals(5, response.body.get("claimed_total").getAsLong());
        assertEquals(1, response.body.get("wild_total").getAsLong());
        assertEquals(2, response.body.getAsJsonArray("territories").size());
    }

    @Test
    void t4WindowSelectionReanchorsWhenDefaultCenterHasNoCoverage() {
        List<TerritoryController.CellRecord> records = List.of(
                new TerritoryController.CellRecord(3248, -3506, 70, 0.0f, 1.0f, 0, 0, 100),
                new TerritoryController.CellRecord(3250, -3506, 70, 0.0f, 1.0f, 0, 0, 100),
                new TerritoryController.CellRecord(3200, -3522, 70, 0.0f, 1.0f, 0, 0, 100)
        );

        TerritoryController.WindowSelection selection =
                TerritoryController.selectWindow(records, 3120, -3200, 256, true);

        assertTrue(selection.reanchored);
        assertEquals(3248, selection.centerX);
        assertEquals(-3506, selection.centerZ);
        assertFalse(selection.records.isEmpty());
    }

    @Test
    void t4WindowSelectionKeepsExplicitCenterEvenWhenEmpty() {
        List<TerritoryController.CellRecord> records = List.of(
                new TerritoryController.CellRecord(3248, -3506, 70, 0.0f, 1.0f, 0, 0, 100)
        );

        TerritoryController.WindowSelection selection =
                TerritoryController.selectWindow(records, 3120, -3200, 256, false);

        assertFalse(selection.reanchored);
        assertEquals(3120, selection.centerX);
        assertEquals(-3200, selection.centerZ);
        assertTrue(selection.records.isEmpty());
    }

    private static TerritoryManager.TerritoryResult result(
            String instanceId,
            String territoryId,
            int continentId,
            int claimedCount,
            int wildCount
    ) {
        TerritoryManager.TerritoryConfig config = new TerritoryManager.TerritoryConfig(
                instanceId,
                territoryId,
                territoryId,
                continentId,
                0,
                0,
                0,
                0,
                100,
                35,
                0,
                2.0,
                5.0,
                0xFF0000
        );
        TerritoryManager.TerritoryResult result = new TerritoryManager.TerritoryResult(config);
        for (int i = 0; i < claimedCount; i++) {
            result.claimedChunks.add((long) i);
        }
        for (int i = 0; i < wildCount; i++) {
            result.wildChunks.add(10_000L + i);
        }
        return result;
    }
}
