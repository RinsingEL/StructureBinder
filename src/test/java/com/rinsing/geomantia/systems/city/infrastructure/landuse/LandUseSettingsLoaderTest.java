package com.rinsing.geomantia.systems.city.infrastructure.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LandUseSettingsLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void bootstrapInstallsDisabledDefaultOnlyWhenWholeDirectoryIsMissing() throws Exception {
        Path root = tempDir.resolve("city_land_use");
        LandUseDefaultConfigBootstrap.ensureInstalled(root);
        LandUseSettings settings = new LandUseSettingsLoader().load(root);

        assertFalse(settings.enabledInWorkflow());
        assertEquals("default_v0_1", settings.profileId());

        Files.writeString(root.resolve("settings.json"), "user-owned");
        LandUseDefaultConfigBootstrap.ensureInstalled(root);
        assertEquals("user-owned", Files.readString(root.resolve("settings.json")));
    }
}
