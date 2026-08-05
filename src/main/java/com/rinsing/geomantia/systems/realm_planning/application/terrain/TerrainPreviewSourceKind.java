package com.rinsing.geomantia.systems.realm_planning.application.terrain;

public enum TerrainPreviewSourceKind {
    GENERATOR_NATIVE("generator_native"),
    MINECRAFT_BASE_HEIGHT("minecraft_base_height"),
    GIS_ATLAS_SAMPLER("gis_atlas_sampler");

    private final String contractName;

    TerrainPreviewSourceKind(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }
}
