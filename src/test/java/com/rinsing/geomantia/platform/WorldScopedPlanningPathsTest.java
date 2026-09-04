package com.rinsing.geomantia.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WorldScopedPlanningPathsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void differentWorldRootsCannotSharePlanningArtifacts() {
        Path firstWorld = temporaryDirectory.resolve("saves").resolve("first_world");
        Path secondWorld = temporaryDirectory.resolve("saves").resolve("second_world");

        Path firstRealmRoot = WorldScopedPlanningPaths.realmDebugRoot(firstWorld);
        Path secondRealmRoot = WorldScopedPlanningPaths.realmDebugRoot(secondWorld);

        assertEquals(firstWorld.toAbsolutePath().normalize().resolve("realm_debug"), firstRealmRoot);
        assertEquals(secondWorld.toAbsolutePath().normalize().resolve("realm_debug"), secondRealmRoot);
        assertNotEquals(firstRealmRoot, secondRealmRoot);
        assertNotEquals(WorldScopedPlanningPaths.gisDebugRoot(firstWorld),
                WorldScopedPlanningPaths.gisDebugRoot(secondWorld));
    }
}
