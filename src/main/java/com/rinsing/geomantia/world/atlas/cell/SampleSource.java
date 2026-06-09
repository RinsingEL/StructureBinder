package com.rinsing.geomantia.world.atlas.cell;

import java.util.Locale;

public enum SampleSource {
    PRIOR("prior"),
    OBSERVED_LOADED("observedLoaded"),
    VERIFIED_SURFACE("verifiedSurface");

    private final String contractName;

    SampleSource(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }

    public static SampleSource fromContractName(String value) {
        if (value == null) {
            return PRIOR;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SampleSource source : values()) {
            if (source.contractName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return source;
            }
        }
        return PRIOR;
    }
}
