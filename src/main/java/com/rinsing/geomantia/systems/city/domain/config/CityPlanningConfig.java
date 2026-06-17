package com.rinsing.geomantia.systems.city.domain.config;

import com.rinsing.geomantia.systems.city.domain.model.CityScale;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record CityPlanningConfig(
        Map<CityScale, ScaleRadius> scaleRadii,
        AreaThresholds areaThresholds,
        Map<LandformType, String> landformDisplayNames,
        double territoryBorderRatio,
        Map<String, String> entryDirectionNames,
        int cityCellStepMin) {

    public CityPlanningConfig {
        Objects.requireNonNull(scaleRadii, "scaleRadii");
        Objects.requireNonNull(areaThresholds, "areaThresholds");
        Objects.requireNonNull(landformDisplayNames, "landformDisplayNames");
        Objects.requireNonNull(entryDirectionNames, "entryDirectionNames");
        if (territoryBorderRatio < 0 || territoryBorderRatio > 1) {
            throw new IllegalArgumentException("territoryBorderRatio must be in [0,1]");
        }
        if (cityCellStepMin <= 0) {
            throw new IllegalArgumentException("cityCellStepMin must be positive");
        }
    }

    public record ScaleRadius(int minRadiusBlocks, int maxRadiusBlocks, int minCellStep, int maxCellStep) {
        public ScaleRadius {
            if (minRadiusBlocks <= 0) throw new IllegalArgumentException("minRadiusBlocks must be positive");
            if (maxRadiusBlocks < minRadiusBlocks)
                throw new IllegalArgumentException("maxRadiusBlocks must be >= minRadiusBlocks");
            if (minCellStep <= 0) throw new IllegalArgumentException("minCellStep must be positive");
            if (maxCellStep < minCellStep)
                throw new IllegalArgumentException("maxCellStep must be >= minCellStep");
        }

        public int clampCellStep(int preferred) {
            return Math.max(minCellStep, Math.min(maxCellStep, preferred));
        }

        public int clampRadius(int preferred) {
            return Math.max(minRadiusBlocks, Math.min(maxRadiusBlocks, preferred));
        }
    }

    public record AreaThresholds(int tinyMax, int smallMax, int mediumMax) {
        public AreaThresholds {
            if (tinyMax <= 0) throw new IllegalArgumentException("tinyMax must be positive");
            if (smallMax < tinyMax) throw new IllegalArgumentException("smallMax must be >= tinyMax");
            if (mediumMax < smallMax) throw new IllegalArgumentException("mediumMax must be >= smallMax");
        }
    }

    public ScaleRadius radiusFor(CityScale scale) {
        ScaleRadius r = scaleRadii.get(scale);
        if (r == null) {
            throw new IllegalArgumentException("No radius config for scale: " + scale);
        }
        return r;
    }

    public static CityPlanningConfig defaults() {
        return new CityPlanningConfig(
                defaultScaleRadii(),
                defaultAreaThresholds(),
                defaultLandformDisplayNames(),
                0.10,
                defaultEntryDirectionNames(),
                4);
    }

    public CityPlanningConfig withScaleRadii(Map<CityScale, ScaleRadius> scaleRadii) {
        return new CityPlanningConfig(scaleRadii, areaThresholds, landformDisplayNames,
                territoryBorderRatio, entryDirectionNames, cityCellStepMin);
    }

    public CityPlanningConfig withAreaThresholds(AreaThresholds areaThresholds) {
        return new CityPlanningConfig(scaleRadii, areaThresholds, landformDisplayNames,
                territoryBorderRatio, entryDirectionNames, cityCellStepMin);
    }

    public CityPlanningConfig withLandformDisplayNames(Map<LandformType, String> names) {
        return new CityPlanningConfig(scaleRadii, areaThresholds, names,
                territoryBorderRatio, entryDirectionNames, cityCellStepMin);
    }

    public CityPlanningConfig withTerritoryBorderRatio(double ratio) {
        return new CityPlanningConfig(scaleRadii, areaThresholds, landformDisplayNames,
                ratio, entryDirectionNames, cityCellStepMin);
    }

    public CityPlanningConfig withCityCellStepMin(int step) {
        return new CityPlanningConfig(scaleRadii, areaThresholds, landformDisplayNames,
                territoryBorderRatio, entryDirectionNames, step);
    }

    private static Map<CityScale, ScaleRadius> defaultScaleRadii() {
        Map<CityScale, ScaleRadius> map = new EnumMap<>(CityScale.class);
        map.put(CityScale.HAMLET, new ScaleRadius(160, 240, 8, 16));
        map.put(CityScale.VILLAGE, new ScaleRadius(256, 384, 16, 16));
        map.put(CityScale.TOWN, new ScaleRadius(512, 640, 16, 24));
        map.put(CityScale.CITY, new ScaleRadius(768, Integer.MAX_VALUE, 24, 32));
        return map;
    }

    private static AreaThresholds defaultAreaThresholds() {
        return new AreaThresholds(3, 12, 48);
    }

    private static Map<LandformType, String> defaultLandformDisplayNames() {
        Map<LandformType, String> map = new LinkedHashMap<>();
        map.put(LandformType.PLAIN, "平原");
        map.put(LandformType.SHORE, "海岸");
        map.put(LandformType.WATER, "水域");
        map.put(LandformType.TERRACE, "台地");
        map.put(LandformType.SLOPE, "坡地");
        map.put(LandformType.CLIFF, "崖壁");
        map.put(LandformType.RIDGE, "山脊");
        map.put(LandformType.VALLEY, "山谷");
        map.put(LandformType.BASIN, "盆地");
        map.put(LandformType.UNKNOWN, "未知");
        return map;
    }

    private static Map<String, String> defaultEntryDirectionNames() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("N", "北");
        map.put("S", "南");
        map.put("E", "东");
        map.put("W", "西");
        map.put("NE", "东北");
        map.put("NW", "西北");
        map.put("SE", "东南");
        map.put("SW", "西南");
        return map;
    }
}
