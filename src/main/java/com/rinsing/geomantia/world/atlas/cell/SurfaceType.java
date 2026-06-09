package com.rinsing.geomantia.world.atlas.cell;

import java.util.Locale;

public enum SurfaceType {
    WATER("water"),
    SAND("sand"),
    SNOW("snow"),
    ROCK("rock"),
    GRASS("grass"),
    DIRT("dirt"),
    UNKNOWN("unknown");

    private final String contractName;

    SurfaceType(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }

    public static SurfaceType fromContractName(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SurfaceType type : values()) {
            if (type.contractName.equals(normalized)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
