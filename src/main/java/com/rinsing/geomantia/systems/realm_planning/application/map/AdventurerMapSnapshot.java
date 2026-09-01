package com.rinsing.geomantia.systems.realm_planning.application.map;

import java.util.List;

public record AdventurerMapSnapshot(
        String runId,
        String wStatus,
        String wPhase,
        double wProgressPercent,
        String tStage,
        String tStatus,
        String currentRealmId,
        String currentRealmName,
        String currentCityId,
        String cityStatus,
        int completedCityCount,
        int remainingCityCount,
        int initialActivityRadiusBlocks,
        List<CityNode> cityNodes
) {
    public AdventurerMapSnapshot {
        runId = safe(runId);
        wStatus = safe(wStatus);
        wPhase = safe(wPhase);
        wProgressPercent = Math.max(0.0D, Math.min(100.0D, wProgressPercent));
        tStage = safe(tStage);
        tStatus = safe(tStatus);
        currentRealmId = safe(currentRealmId);
        currentRealmName = safe(currentRealmName);
        currentCityId = safe(currentCityId);
        cityStatus = safe(cityStatus);
        completedCityCount = Math.max(0, completedCityCount);
        remainingCityCount = Math.max(0, remainingCityCount);
        initialActivityRadiusBlocks = Math.max(0, initialActivityRadiusBlocks);
        cityNodes = cityNodes == null ? List.of() : List.copyOf(cityNodes);
    }

    public static AdventurerMapSnapshot empty() {
        return new AdventurerMapSnapshot("", "not_started", "", 0.0D,
                "", "not_started", "", "", "", "not_started", 0, 0, 2048, List.of());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record CityNode(String citySeedId, String realmId, String role, int blockX, int blockZ,
                           String status, boolean current) {
        public CityNode {
            citySeedId = safe(citySeedId);
            realmId = safe(realmId);
            role = safe(role);
            status = safe(status);
        }
    }
}
