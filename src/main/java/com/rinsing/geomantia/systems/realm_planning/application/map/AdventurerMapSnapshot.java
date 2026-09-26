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
        List<CityNode> cityNodes,
        int initialCenterX,
        int initialCenterZ,
        SurveyBounds surveyBounds
) {
    public record SurveyBounds(int minX, int minZ, int maxX, int maxZ) {
        public static SurveyBounds empty() { return new SurveyBounds(0,0,0,0); }
        public boolean available() { return maxX > minX && maxZ > minZ; }
        public double centerX() { return (minX + (double) maxX) / 2; }
        public double centerZ() { return (minZ + (double) maxZ) / 2; }
        public double overviewRadius() { return Math.max(1024, Math.max(maxX-(double)minX,maxZ-(double)minZ)*0.55+256); }
        public double minimumZoom() { return available() ? Math.min(4, Math.max(1.0/128, 4096/overviewRadius())) : 1.0/128; }
    }
    public AdventurerMapSnapshot(String runId,String wStatus,String wPhase,double progress,
            String tStage,String tStatus,String realmId,String realmName,String cityId,String cityStatus,
            int completed,int remaining,int radius,CoarseMap map,List<CityNode> nodes,int centerX,int centerZ) {
        this(runId,wStatus,wPhase,progress,tStage,tStatus,realmId,realmName,cityId,cityStatus,
                completed,remaining,radius,map,nodes,centerX,centerZ,SurveyBounds.empty());
    }
    public AdventurerMapSnapshot withSurveyBounds(SurveyBounds bounds) {
        return new AdventurerMapSnapshot(runId,wStatus,wPhase,wProgressPercent,tStage,tStatus,
                currentRealmId,currentRealmName,currentCityId,cityStatus,completedCityCount,remainingCityCount,
                initialActivityRadiusBlocks,coarseMap,cityNodes,initialCenterX,initialCenterZ,bounds);
    }
    public AdventurerMapSnapshot(String runId,String wStatus,String wPhase,double progress,
            String tStage,String tStatus,String realmId,String realmName,String cityId,String cityStatus,
            int completed,int remaining,int radius,CoarseMap map,List<CityNode> nodes) {
        this(runId,wStatus,wPhase,progress,tStage,tStatus,realmId,realmName,cityId,cityStatus,
                completed,remaining,radius,map,nodes,0,0);
    }
    public AdventurerMapSnapshot withInitialArea(com.rinsing.geomantia.systems.realm_planning.application.access.InitialExplorationArea area) {
        return new AdventurerMapSnapshot(runId,wStatus,wPhase,wProgressPercent,tStage,tStatus,
                currentRealmId,currentRealmName,currentCityId,cityStatus,completedCityCount,remainingCityCount,
                area.radius(),coarseMap,cityNodes,area.centerX(),area.centerZ(),surveyBounds);
    }
    public AdventurerMapSnapshot {
        surveyBounds = surveyBounds == null ? SurveyBounds.empty() : surveyBounds;
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

    /** Remove hidden data before transmitting an ordinary player's snapshot. */
    public AdventurerMapSnapshot forViewer(boolean debug) {
        if(debug) return this;
        CoarseMap m=coarseMap;
        byte[] terrain=m.terrainCodes().clone(), codes=m.realmCodes().clone();
        var ids=new java.util.ArrayList<String>(); var names=new java.util.ArrayList<String>();
        var mapping=new java.util.HashMap<Integer,Integer>();
        for(int i=0;i<codes.length;i++) {
            if(m.revealedCodes()[i]==0) { terrain[i]=0; codes[i]=0; continue; }
            int old=Byte.toUnsignedInt(codes[i]);
            if(old==0 || old>m.realmIds().size()) continue;
            if(!mapping.containsKey(old)) { ids.add(m.realmIds().get(old-1)); names.add(m.realmNames().get(old-1)); mapping.put(old,ids.size()); }
            codes[i]=(byte)(int)mapping.get(old);
        }
        var visible=new CoarseMap(m.dimensionId(),m.minBlockX(),m.minBlockZ(),m.cellSizeBlocks(),m.width(),m.height(),terrain,codes,m.revealedCodes(),ids,names);
        return new AdventurerMapSnapshot("",wStatus,wPhase,wProgressPercent,"","","","","", "",0,0,initialActivityRadiusBlocks,
                visible,cityNodes.stream().filter(n->m.revealedAt(n.blockX(),n.blockZ())).toList(),initialCenterX,initialCenterZ,surveyBounds);
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
                            List<String> realmIds, List<String> realmNames) {
        public CoarseMap(String dimensionId,int minBlockX,int minBlockZ,int cellSizeBlocks,int width,int height,
                         byte[] terrain,byte[] realms,byte[] revealed,List<String> ids) {
            this(dimensionId,minBlockX,minBlockZ,cellSizeBlocks,width,height,terrain,realms,revealed,ids,ids);
        }
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
            realmNames = realmNames == null ? realmIds : List.copyOf(realmNames);
            if(realmNames.size()!=realmIds.size()) throw new IllegalArgumentException("MAP_REALM_NAMES_MISMATCH");
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

        /** Network decoding creates new arrays even when the visible raster did not change. */
        public boolean sameRaster(CoarseMap other) {
            return other != null && dimensionId.equals(other.dimensionId)
                    && minBlockX == other.minBlockX && minBlockZ == other.minBlockZ
                    && cellSizeBlocks == other.cellSizeBlocks && width == other.width && height == other.height
                    && realmIds.equals(other.realmIds)
                    && java.util.Arrays.equals(terrainCodes, other.terrainCodes)
                    && java.util.Arrays.equals(realmCodes, other.realmCodes)
                    && java.util.Arrays.equals(revealedCodes, other.revealedCodes);
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
