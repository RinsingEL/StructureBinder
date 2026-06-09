package com.rinsing.geomantia.world.atlas.refresh;

public enum RefreshStatus {
    QUEUED("queued"),
    SAMPLING("sampling"),
    METRICS("metrics"),
    CLASSIFYING("classifying"),
    PATCHING("patching"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String contractName;

    RefreshStatus(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }
}
