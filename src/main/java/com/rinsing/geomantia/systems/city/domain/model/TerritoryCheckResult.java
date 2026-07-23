package com.rinsing.geomantia.systems.city.domain.model;

public enum TerritoryCheckResult {
    INSIDE("inside"),
    BORDER("border"),
    OUTSIDE("outside"),
    UNKNOWN("unknown");

    private final String contractName;

    TerritoryCheckResult(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }
}
