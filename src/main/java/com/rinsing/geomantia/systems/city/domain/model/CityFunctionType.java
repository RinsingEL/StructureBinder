package com.rinsing.geomantia.systems.city.domain.model;

import java.util.Locale;

public enum CityFunctionType {
    CIVIC_CORE("civic_core"),
    RESIDENTIAL("residential"),
    PRODUCTION("production"),
    MARKET("market"),
    FARM_OR_PASTURE("farm_or_pasture"),
    DEFENSE("defense"),
    HARBOR_OR_WATERFRONT("harbor_or_waterfront"),
    SACRED_OR_CULTURAL("sacred_or_cultural");

    private final String contractName;

    CityFunctionType(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }

    public static CityFunctionType fromContractName(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (CityFunctionType type : values()) {
            if (type.contractName.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
