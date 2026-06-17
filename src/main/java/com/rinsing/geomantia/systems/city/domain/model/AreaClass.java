package com.rinsing.geomantia.systems.city.domain.model;

public enum AreaClass {
    TINY("tiny"),
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large");

    private final String contractName;

    AreaClass(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }
}
