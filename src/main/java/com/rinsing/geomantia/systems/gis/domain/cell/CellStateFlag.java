package com.rinsing.geomantia.systems.gis.domain.cell;

public enum CellStateFlag {
    SAMPLED("sampled"),
    OBSERVED("observed"),
    VERIFIED_SURFACE("verifiedSurface"),
    METRICS_READY_SMALL("metricsReadySmall"),
    METRICS_READY_LARGE("metricsReadyLarge"),
    LANDFORM_READY("landformReady"),
    PATCH_READY("patchReady"),
    EDGE_DIRTY("edgeDirty"),
    FAILED("failed");

    private final String contractName;

    CellStateFlag(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }
}
