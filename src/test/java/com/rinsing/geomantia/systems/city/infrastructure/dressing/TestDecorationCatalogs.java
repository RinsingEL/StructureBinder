package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import java.io.IOException;
import java.nio.file.Path;

public final class TestDecorationCatalogs {
    private TestDecorationCatalogs() {
    }

    public static CityDecorationContentCatalog loadManagedDefault(Path root) throws IOException {
        Path installed = CityDecorationDefaultCatalogBootstrap.ensureInstalled(root);
        return new CityDecorationContentCatalogLoader(state -> {
            // Planning tests only require frozen metadata; registry validation is covered separately.
        }).load(installed);
    }
}
