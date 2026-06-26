package com.rinsing.geomantia.systems.city;

import com.rinsing.geomantia.systems.city.application.CityTerrainProbe;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CityTerrainProbeTest {
    @Test
    void acceptsFlatDrySupportedFootprint() {
        CityTerrainProbe.Result result = CityTerrainProbe.evaluate("probe_1", "piece_1",
                new BlockBounds(0, 0, 15, 15),
                List.of(
                        new CityTerrainProbe.Sample(0, 0, 64, false, true),
                        new CityTerrainProbe.Sample(15, 0, 65, false, true),
                        new CityTerrainProbe.Sample(0, 15, 64, false, true),
                        new CityTerrainProbe.Sample(15, 15, 65, false, true)));

        assertTrue(result.accepted());
        assertEquals(1, result.probe().get("heightDelta").getAsInt());
        assertEquals("", CityTerrainProbe.ruleResult(result).get("reasonCode").getAsString());
    }

    @Test
    void rejectsUnevenTerrain() {
        CityTerrainProbe.Result result = CityTerrainProbe.evaluate("probe_1", "piece_1",
                new BlockBounds(0, 0, 15, 15),
                List.of(
                        new CityTerrainProbe.Sample(0, 0, 64, false, true),
                        new CityTerrainProbe.Sample(15, 0, 74, false, true)),
                5, 0.75, false);

        assertFalse(result.accepted());
        assertEquals("JIGSAW_RULE_TERRAIN_TOO_UNEVEN", result.reasonCode());
    }

    @Test
    void rejectsFluidOverlapForOrdinaryStructure() {
        CityTerrainProbe.Result result = CityTerrainProbe.evaluate("probe_1", "piece_1",
                new BlockBounds(0, 0, 15, 15),
                List.of(
                        new CityTerrainProbe.Sample(0, 0, 64, true, true),
                        new CityTerrainProbe.Sample(15, 0, 64, false, true)),
                5, 0.75, false);

        assertFalse(result.accepted());
        assertEquals("JIGSAW_RULE_FLUID_OVERLAP", result.reasonCode());
    }

    @Test
    void waitingChunksProducesStructuredProbe() {
        CityTerrainProbe.Result result = CityTerrainProbe.waitingForChunks("probe_1", "piece_1",
                new BlockBounds(0, 0, 15, 15), "0,0;0,1");

        assertFalse(result.accepted());
        assertEquals("JIGSAW_RULE_CHUNK_WAITING", result.reasonCode());
        assertEquals("waiting_chunks", result.probe().get("chunkCoverage").getAsString());
        assertEquals("0,0;0,1", result.probe().get("missingChunks").getAsString());
    }
}
