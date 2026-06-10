package com.rinsing.geomantia.systems.gis.application.refresh;

import java.util.Locale;

public enum SampleMode {
    PRIOR("prior"),
    OBSERVED_IF_LOADED("observedIfLoaded"),
    VERIFY_SURFACE("verifySurface");

    private final String contractName;

    SampleMode(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }

    public static SampleMode fromContractName(String value) {
        if (value == null) {
            return PRIOR;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SampleMode mode : values()) {
            if (mode.contractName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return mode;
            }
        }
        return PRIOR;
    }
}
