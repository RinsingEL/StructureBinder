package com.rinsing.geomantia.systems.city.infrastructure.landuse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class LandUseDefaultConfigBootstrap {
    private static final Map<String, String> DEFAULT_FILES = Map.of(
            "settings.json", "/geomantia/default_config/city_land_use/settings.json",
            "profiles/default.json", "/geomantia/default_config/city_land_use/profiles/default.json");

    private LandUseDefaultConfigBootstrap() {
    }

    public static synchronized Path ensureInstalled(Path cityLandUseConfigRoot) throws IOException {
        if (cityLandUseConfigRoot == null) throw new IOException("LandUse config root is required");
        Path root = cityLandUseConfigRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        for (Map.Entry<String, String> entry : DEFAULT_FILES.entrySet()) {
            installIfMissing(root.resolve(entry.getKey()), entry.getValue());
        }
        return root;
    }

    private static void installIfMissing(Path target, String resource) throws IOException {
        if (Files.exists(target)) return;
        Path parent = target.getParent();
        if (parent == null) throw new IOException("LandUse default config has no parent: " + target);
        Files.createDirectories(parent);
        try (InputStream input = LandUseDefaultConfigBootstrap.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing packaged LandUse config resource: " + resource);
            try {
                Files.copy(input, target);
            } catch (FileAlreadyExistsException ignored) {
                // Another server startup installed the same bundled file first.
            }
        }
    }
}
