package com.user.terra_script.core.artifact;

public enum ArtifactKey {
    W3_CONTINENT_META_JSON("world/W3/ContinentMeta.json", ArtifactType.JSON),
    W3_OCEAN_META_JSON("world/W3/OceanMeta.json", ArtifactType.JSON),
    W4_TERRAIN_FACTS_DAT("world/W4/TerrainFacts.dat", ArtifactType.DAT),
    W4_TERRAIN_SUMMARY_JSON("world/W4/TerrainSummary.json", ArtifactType.JSON);

    public final String relativePath;
    public final ArtifactType type;

    ArtifactKey(String relativePath, ArtifactType type) {
        this.relativePath = relativePath;
        this.type = type;
    }
}
