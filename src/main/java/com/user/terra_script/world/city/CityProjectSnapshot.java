package com.user.terra_script.world.city;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CityProjectSnapshot {
    public int version = 1;
    public long createdAtEpochMs;
    public long seedUsed;
    public ScanMeta scan;
    public List<TerritorySnapshot> territories = new ArrayList<>();
    public List<CitySnapshot> cities = new ArrayList<>();

    public static class FreezeResult {
        public final boolean ok;
        public final String message;
        public FreezeResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    public static class ScanMeta {
        public String cacheFile;
        public long cacheSizeBytes;
        public long cacheLastModified;
        public int scanRadiusChunks;
        public int scanStep;
    }

    public static class TerritorySnapshot {
        public String id;
        public String name;
        public int regionId;
        public int capitalX, capitalZ;
        public int maxPower;
        public double mountainCost;
        public double waterCost;
        public int color;
        public TerritoryStatsSnapshot stats;
    }

    public static class TerritoryStatsSnapshot {
        public long areaPixels;
        public int minX, maxX, minZ, maxZ;
        public Map<Integer, Double> continentDistribution = new HashMap<>();
        public Map<String, Double> biomeComposition = new HashMap<>();
        public Set<String> neighbors = new HashSet<>();
    }

    public static class CitySnapshot {
        public String id;
        public CityConfigSnapshot config;
        public List<ClaimedChunk> claimedChunks = new ArrayList<>();
        public List<DistrictSnapshot> districts = new ArrayList<>();
    }

    public static class CityConfigSnapshot {
        public String territoryId;
        public int continentId;
        public int centerX;
        public int centerZ;
        public int targetChunkCount;
        public String bias;
        public String ecology;
        public String density;
    }

    public static class ClaimedChunk {
        public int x;
        public int z;
        public String zone;
    }

    public static class ChunkCoord {
        public int x;
        public int z;
    }

    public static class Point2D {
        public double x;
        public double z;
    }

    public static class DistrictSnapshot {
        public int id;
        public double centerX, centerZ;
        public String zoneType;
        public List<ChunkCoord> memberChunks = new ArrayList<>();
        public List<Point2D> polygon = new ArrayList<>();
    }
}
