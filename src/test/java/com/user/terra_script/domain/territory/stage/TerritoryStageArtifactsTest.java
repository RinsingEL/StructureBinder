package com.user.terra_script.domain.territory.stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerritoryStageArtifactsTest {
    @TempDir
    Path tempDir;

    @Test
    void writeAndReadT1StatusRoundTrips() throws Exception {
        Path path = tempDir.resolve("T1_Status.json");
        TerritoryStageArtifacts.T1Status status = new TerritoryStageArtifacts.T1Status();
        status.territoryId = "han";
        status.territoryName = "Han";
        status.territoryInstanceId = "han@c7";
        status.selectedContinentId = 7;
        status.clusterSelected = true;
        status.selectedClusterId = 3;
        status.selectedClusterLabel = "A";
        status.selectionSource = "auto_top_ranked";

        TerritoryStageArtifacts.writeJson(path, status);
        TerritoryStageArtifacts.T1Status restored = TerritoryStageArtifacts
                .readJson(path, TerritoryStageArtifacts.T1Status.class)
                .orElseThrow();

        assertEquals("han", restored.territoryId);
        assertEquals("han@c7", restored.territoryInstanceId);
        assertEquals(7, restored.selectedContinentId);
        assertTrue(restored.clusterSelected);
        assertEquals(3, restored.selectedClusterId);
        assertEquals("A", restored.selectedClusterLabel);
    }
}
