package com.rinsing.geomantia.systems.gis.application.refresh;

public enum RefreshPriority {
    LOW("low"),
    NORMAL("normal"),
    HIGH("high"),
    DEBUG("debug");

    private final String contractName;

    RefreshPriority(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }
}
