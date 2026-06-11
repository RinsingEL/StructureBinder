package com.rinsing.geomantia.systems.gis;

import com.rinsing.geomantia.systems.gis.adapter.snapshot.AtlasRegionSnapshotIo;
import com.rinsing.geomantia.systems.gis.domain.cell.CellStateFlag;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestCase;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GisBaselineRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void allBaselineCasesExportReportsPreviewsAndSnapshots() throws Exception {
        GisTestRunner runner = new GisTestRunner(GisSampleConfig.defaults(), GisClassifierConfig.defaults());

        for (GisTestCase testCase : GisTestCase.baselineCases()) {
            var report = runner.runCase(testCase, tempDir);
            Path runDir = tempDir.resolve(report.runId());

            assertTrue(report.passed(), () -> testCase.id() + " failures=" + report.failures());
            assertTrue(Files.exists(runDir.resolve("test_report.json")));
            assertTrue(Files.exists(runDir.resolve("progress.png")));
            assertTrue(Files.exists(runDir.resolve("progress_manifest.json")));
            assertTrue(Files.exists(runDir.resolve("preview").resolve("preview_manifest.json")));
            assertTrue(Files.exists(runDir.resolve("preview").resolve("elevation.png")));
            assertTrue(Files.exists(runDir.resolve("preview").resolve("slope.png")));
            assertTrue(Files.exists(runDir.resolve("preview").resolve("tpiSmall.png")));
            assertTrue(Files.exists(runDir.resolve("preview").resolve("tpiLarge.png")));
            assertTrue(Files.exists(runDir.resolve("preview").resolve("landform.png")));
            assertTrue(Files.exists(runDir.resolve("preview").resolve("patch.png")));
            assertTrue(Files.exists(runDir.resolve("preview").resolve("legend.png")));
            assertTrue(Files.exists(runDir.resolve("region_snapshot.json")));
            assertFalse(report.landformCounts().isEmpty());
            assertEquals(4, report.cellStepBlocks());
        }
    }

    @Test
    void regionSnapshotRoundTripsForReproduction() throws Exception {
        GisTestRunner runner = new GisTestRunner(GisSampleConfig.defaults(), GisClassifierConfig.defaults());
        var report = runner.runCase(GisTestCase.byId("water"), tempDir);
        Path snapshot = tempDir.resolve(report.runId()).resolve("region_snapshot.json");

        var restored = new AtlasRegionSnapshotIo().read(snapshot, GisSampleConfig.defaults());

        assertTrue(restored.patches().size() > 0);
        assertTrue(restored.cells().stream().anyMatch(cell -> cell.isWater()));
        assertTrue(restored.cells().stream().anyMatch(cell -> cell.landformType().contractName().equals("shore")));
        assertFalse(restored.cells().stream()
                .filter(cell -> cell.hasFlag(CellStateFlag.PATCH_READY))
                .anyMatch(cell -> cell.patchId().isBlank()));
        assertTrue(restored.cells().stream().anyMatch(cell -> !cell.hasFlag(CellStateFlag.SAMPLED)));
        assertFalse(restored.cells().stream()
                .filter(cell -> !cell.hasFlag(CellStateFlag.SAMPLED))
                .anyMatch(cell -> cell.hasFlag(CellStateFlag.LANDFORM_READY)
                        || cell.hasFlag(CellStateFlag.PATCH_READY)));
    }

    @Test
    void nonDefaultCellStepIsWrittenToReportsSnapshotsAndManifests() throws Exception {
        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(64);
        GisTestRunner runner = new GisTestRunner(sampleConfig, GisClassifierConfig.defaults());
        var report = runner.runCase(GisTestCase.byId("mixed"), tempDir);
        Path runDir = tempDir.resolve(report.runId());

        assertEquals(64, report.cellStepBlocks());
        assertTrue(Files.exists(runDir.resolve("test_report.json")));
        assertTrue(Files.exists(runDir.resolve("region_snapshot.json")));
        assertTrue(Files.exists(runDir.resolve("progress_manifest.json")));
        assertTrue(Files.exists(runDir.resolve("preview").resolve("preview_manifest.json")));
        assertJsonNumber(runDir.resolve("test_report.json"), "cellStepBlocks", 64);
        assertJsonNumber(runDir.resolve("region_snapshot.json"), "cellStepBlocks", 64);
        assertJsonNumber(runDir.resolve("progress_manifest.json"), "cellStepBlocks", 64);
        assertJsonNumber(runDir.resolve("preview").resolve("preview_manifest.json"), "cellStepBlocks", 64);
        String previewManifest = Files.readString(runDir.resolve("preview").resolve("preview_manifest.json"));
        assertTrue(previewManifest.contains("\"metricRadiiBlocks\""));
        assertTrue(previewManifest.contains("\"tpiLarge\": 768"));
    }

    private static void assertJsonNumber(Path path, String key, int expected) throws Exception {
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals(expected, json.get(key).getAsInt());
    }
}
