package com.rinsing.geomantia.systems.city.domain.blueprint;

public final class CityBlueprintContractException extends IllegalArgumentException {
    private final CityBlueprintReasonCode reasonCode;
    private final String fieldPath;

    public CityBlueprintContractException(CityBlueprintReasonCode reasonCode, String fieldPath, String message) {
        super(message);
        this.reasonCode = reasonCode;
        this.fieldPath = fieldPath == null ? "" : fieldPath;
    }

    public CityBlueprintReasonCode reasonCode() {
        return reasonCode;
    }

    public String fieldPath() {
        return fieldPath;
    }
}
