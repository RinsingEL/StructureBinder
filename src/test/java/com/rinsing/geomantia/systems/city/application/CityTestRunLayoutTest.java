package com.rinsing.geomantia.systems.city.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityTestRunLayoutTest {
    @TempDir
    Path tempDir;

    @Test
    void freshCityUsesOnePackagedTestRun() {
        CityTestRunLayout layout = CityTestRunLayout.open(tempDir, "capital:test");

        assertFalse(layout.legacy());
        assertEquals(tempDir.resolve("city_test_runs/capital_test"), layout.packageDirectory());
        assertEquals(tempDir.resolve("city_test_runs/capital_test/steps/d3"),
                layout.stepDirectory(CityTestRunLayout.D3));
        assertEquals(tempDir.resolve("city_test_runs/capital_test/steps/land_use"),
                layout.stepDirectory(CityTestRunLayout.LAND_USE));
        assertEquals(tempDir.resolve("city_test_runs/capital_test/test_run_manifest.json"),
                layout.manifestPath());
        assertEquals("realm_run::capital:test", layout.testRunId("realm_run", "capital:test"));
    }

    @Test
    void existingLegacyStageLocksWholeCityToLegacyLayout() throws IOException {
        Files.createDirectories(tempDir.resolve("city_d3_capital"));

        CityTestRunLayout layout = CityTestRunLayout.open(tempDir, "capital");

        assertTrue(layout.legacy());
        assertEquals(tempDir.resolve("city_d3_capital"), layout.stepDirectory(CityTestRunLayout.D3));
        assertEquals(tempDir.resolve("city_d4_capital"), layout.stepDirectory(CityTestRunLayout.D4));
        assertEquals(tempDir.resolve("city_workflow_capital/city_workflow_report.json"),
                layout.manifestPath());
    }

    @Test
    void packageRootWinsWhenLegacyDirectoriesAlsoExist() throws IOException {
        Files.createDirectories(tempDir.resolve("city_d3_capital"));
        Files.createDirectories(tempDir.resolve("city_test_runs/capital"));

        CityTestRunLayout layout = CityTestRunLayout.open(tempDir, "capital");

        assertFalse(layout.legacy());
        assertEquals(tempDir.resolve("city_test_runs/capital/steps/d4"),
                layout.stepDirectory(CityTestRunLayout.D4));
    }
}
