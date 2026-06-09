package com.rinsing.geomantia.world.atlas.landform;

public enum PatchFlag {
    FRAGMENT("fragment"),
    EDGE_DIRTY("edgeDirty"),
    CROSS_REGION_CANDIDATE("crossRegionCandidate");

    private final String contractName;

    PatchFlag(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }
}
