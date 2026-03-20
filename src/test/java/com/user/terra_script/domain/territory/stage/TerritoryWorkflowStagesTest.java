package com.user.terra_script.domain.territory.stage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerritoryWorkflowStagesTest {
    @Test
    void t3ReadinessPassesWhenAllPrerequisitesExist() {
        TerritoryStageOrchestrator.ReadinessReport report = TerritoryStageOrchestrator.evaluateReadiness(List.of(
                new TerritoryStageOrchestrator.GateRow(1, "han", true, true, true),
                new TerritoryStageOrchestrator.GateRow(1, "qin", true, true, true)
        ));

        assertTrue(report.ready);
        assertEquals("ready", report.message);
    }

    @Test
    void t3ReadinessReportsMissingStepsByContinent() {
        TerritoryStageOrchestrator.ReadinessReport report = TerritoryStageOrchestrator.evaluateReadiness(List.of(
                new TerritoryStageOrchestrator.GateRow(2, "han", false, false, false),
                new TerritoryStageOrchestrator.GateRow(2, "qin", true, false, true),
                new TerritoryStageOrchestrator.GateRow(5, "chu", true, true, false)
        ));

        assertFalse(report.ready);
        assertTrue(report.message.contains("region_id=2"));
        assertTrue(report.message.contains("han missing T1/T2/territory_config"));
        assertTrue(report.message.contains("qin missing T2"));
        assertTrue(report.message.contains("region_id=5"));
        assertTrue(report.message.contains("chu missing territory_config"));
    }

    @Test
    void t4RuntimeOptionsDefaultToScanOnly() {
        T4Stage.RuntimeOptions defaults = T4Stage.RuntimeOptions.defaults();
        T4Stage.RuntimeOptions autoTrigger = T4Stage.RuntimeOptions.autoTriggerDefaults();

        assertEquals(2, defaults.sampleStride);
        assertEquals(-1, defaults.maxChunksPerTerritory);
        assertFalse(defaults.loadedOnly);

        assertEquals(4, autoTrigger.sampleStride);
        assertEquals(256, autoTrigger.maxChunksPerTerritory);
        assertTrue(autoTrigger.loadedOnly);
    }
}
