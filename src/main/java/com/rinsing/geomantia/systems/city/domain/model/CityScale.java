package com.rinsing.geomantia.systems.city.domain.model;

import java.util.Locale;

public enum CityScale {
    HAMLET("hamlet"),
    VILLAGE("village"),
    TOWN("town"),
    CITY("city");

    private final String contractName;

    CityScale(String contractName) {
        this.contractName = contractName;
    }

    public String contractName() {
        return contractName;
    }

    public static CityScale fromContractName(String name) {
        if (name == null) return null;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (CityScale s : values()) {
            if (s.contractName.equals(normalized)) return s;
        }
        // T4 legacy scale labels that map to existing enum values
        return switch (normalized) {
            case "capital", "large_city" -> CITY;
            case "town" -> TOWN;
            default -> null;
        };
    }
}
