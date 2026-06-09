package com.rinsing.geomantia.world.atlas.cell;

import java.util.Locale;

public enum LandformType {
    WATER("water"),
    SHORE("shore"),
    PLAIN("plain"),
    TERRACE("terrace"),
    SLOPE("slope"),
    CLIFF("cliff"),
    RIDGE("ridge"),
    VALLEY("valley"),
    BASIN("basin"),
    UNKNOWN("unknown");

    private final String contractName;

    LandformType(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }

    public static LandformType fromContractName(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (LandformType type : values()) {
            if (type.contractName.equals(normalized)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
