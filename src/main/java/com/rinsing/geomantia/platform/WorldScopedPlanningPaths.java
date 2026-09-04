package com.rinsing.geomantia.platform;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Objects;

/** Resolves world-derived planning artifacts below the current save root. */
public final class WorldScopedPlanningPaths {
    private WorldScopedPlanningPaths() {
    }

    public static Path realmDebugRoot(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return realmDebugRoot(server.getWorldPath(LevelResource.ROOT));
    }

    public static Path realmDebugRoot(Path worldRoot) {
        return normalize(worldRoot).resolve("realm_debug");
    }

    public static Path gisDebugRoot(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return gisDebugRoot(server.getWorldPath(LevelResource.ROOT));
    }

    public static Path gisDebugRoot(Path worldRoot) {
        return normalize(worldRoot).resolve("gis_debug");
    }

    private static Path normalize(Path worldRoot) {
        return Objects.requireNonNull(worldRoot, "worldRoot").toAbsolutePath().normalize();
    }
}
