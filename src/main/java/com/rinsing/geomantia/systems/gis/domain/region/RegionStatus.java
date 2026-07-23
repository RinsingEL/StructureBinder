package com.rinsing.geomantia.systems.gis.domain.region;

import java.util.Locale;

public enum RegionStatus {
    EMPTY("empty"),
    SAMPLED("sampled"),
    METRICS_PARTIAL("metricsPartial"),
    READY("ready"),
    DIRTY("dirty");

    private final String contractName;

    RegionStatus(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }

    public static RegionStatus fromContractName(String value) {
        if (value == null) {
            return EMPTY;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (RegionStatus status : values()) {
            if (status.contractName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return status;
            }
        }
        return EMPTY;
    }
}
