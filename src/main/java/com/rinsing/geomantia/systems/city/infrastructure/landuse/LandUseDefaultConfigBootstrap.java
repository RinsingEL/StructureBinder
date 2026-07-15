package com.rinsing.geomantia.systems.city.infrastructure.landuse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class LandUseDefaultConfigBootstrap {
    private static final String RESOURCE = "/geomantia/default_config/city_land_use/settings.json";

    private LandUseDefaultConfigBootstrap() {
    }

    public static synchronized Path ensureInstalled(Path cityLandUseConfigRoot) throws IOException {
        if (cityLandUseConfigRoot == null) throw new IOException("LandUse config root is required");
        Path root = cityLandUseConfigRoot.toAbsolutePath().normalize();
        if (Files.exists(root)) return root;
        Path parent = root.getParent();
        if (parent == null) throw new IOException("LandUse config root has no parent: " + root);
        Files.createDirectories(parent);
        Path staging = parent.resolve("." + root.getFileName() + ".bootstrap");
        if (Files.exists(staging)) throw new IOException("LandUse bootstrap staging exists: " + staging);
        Files.createDirectories(staging);
        try (InputStream input = LandUseDefaultConfigBootstrap.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing packaged LandUse settings resource");
            Files.copy(input, staging.resolve("settings.json"));
        }
        try {
            return Files.move(staging, root, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            try {
                return Files.move(staging, root);
            } catch (FileAlreadyExistsException raced) {
                return root;
            }
        } catch (FileAlreadyExistsException raced) {
            return root;
        }
    }
}
