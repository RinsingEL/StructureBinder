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
        CoarseMap coarseMap,
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
        coarseMap = coarseMap == null ? CoarseMap.empty() : coarseMap;
        cityNodes = cityNodes == null ? List.of() : List.copyOf(cityNodes);
    }

    public static AdventurerMapSnapshot empty() {
        return new AdventurerMapSnapshot("", "not_started", "", 0.0D,
                "", "not_started", "", "", "", "not_started", 0, 0, 2048,
                CoarseMap.empty(), List.of());
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

    public record CoarseMap(String dimensionId, int minBlockX, int minBlockZ, int cellSizeBlocks,
                            int width, int height, byte[] terrainCodes, byte[] realmCodes, byte[] revealedCodes,
                            List<String> realmIds) {
        private static final int MAX_SIDE = 128;

        public CoarseMap {
            dimensionId = safe(dimensionId);
            cellSizeBlocks = Math.max(1, cellSizeBlocks);
            width = Math.max(0, Math.min(MAX_SIDE, width));
            height = Math.max(0, Math.min(MAX_SIDE, height));
            int expected = width * height;
            terrainCodes = terrainCodes == null ? new byte[expected] : terrainCodes.clone();
            realmCodes = realmCodes == null ? new byte[expected] : realmCodes.clone();
            revealedCodes = revealedCodes == null ? new byte[expected] : revealedCodes.clone();
            if (terrainCodes.length != expected || realmCodes.length != expected || revealedCodes.length != expected) {
                throw new IllegalArgumentException("ADVENTURER_MAP_RASTER_SIZE_MISMATCH");
            }
            realmIds = realmIds == null ? List.of() : List.copyOf(realmIds);
            if (realmIds.size() > 255) {
                throw new IllegalArgumentException("ADVENTURER_MAP_REALM_PALETTE_TOO_LARGE");
            }
        }

        public static CoarseMap empty() {
            return new CoarseMap("", 0, 0, 1, 0, 0, new byte[0], new byte[0], new byte[0], List.of());
        }

        public boolean available() {
            return width > 0 && height > 0;
        }

        public int maxBlockX() {
            return minBlockX + width * cellSizeBlocks;
        }

        public int maxBlockZ() {
            return minBlockZ + height * cellSizeBlocks;
        }

        public boolean revealedAt(double blockX, double blockZ) {
            int column = (int) Math.floor((blockX - minBlockX) / cellSizeBlocks);
            int row = (int) Math.floor((blockZ - minBlockZ) / cellSizeBlocks);
            if (column < 0 || column >= width || row < 0 || row >= height) return false;
            return revealedCodes[row * width + column] != 0;
        }
    }
}
