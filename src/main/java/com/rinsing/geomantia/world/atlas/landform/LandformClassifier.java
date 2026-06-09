package com.rinsing.geomantia.world.atlas.landform;

import com.rinsing.geomantia.world.atlas.GisClassifierConfig;
import com.rinsing.geomantia.world.atlas.cell.AtlasCell;
import com.rinsing.geomantia.world.atlas.cell.CellStateFlag;
import com.rinsing.geomantia.world.atlas.cell.LandformType;
import com.rinsing.geomantia.world.atlas.region.AtlasRegion;

public final class LandformClassifier {
    private final GisClassifierConfig config;

    public LandformClassifier(GisClassifierConfig config) {
        this.config = config;
    }

    public void classify(AtlasRegion region) {
        for (AtlasCell cell : region.cells()) {
            if (!cell.hasFlag(CellStateFlag.SAMPLED)
                    || !cell.hasFlag(CellStateFlag.METRICS_READY_SMALL)
                    || !cell.hasFlag(CellStateFlag.METRICS_READY_LARGE)) {
                continue;
            }
            cell.setLandformType(classify(cell));
        }
    }

    public LandformType classify(AtlasCell cell) {
        if (cell.isWater() || cell.waterDepth() >= config.deepWaterMinDepth()) {
            return LandformType.WATER;
        }
        if (cell.waterDistance() <= config.shoreMaxWaterDistanceCells()
                && cell.slope() <= config.shoreMaxSlope()) {
            return LandformType.SHORE;
        }
        if (cell.slope() >= config.cliffMinSlope()
                || (cell.localRelief() >= config.plainMaxRelief() * 3.0 && cell.roughness() >= config.roughTerrainMin())) {
            return LandformType.CLIFF;
        }
        if (cell.tpiLarge() >= config.ridgeTpiLargeMin() && cell.slope() > config.plainMaxSlope()) {
            return LandformType.RIDGE;
        }
        if (cell.tpiLarge() <= config.valleyTpiLargeMax()
                && (cell.slope() > config.plainMaxSlope() || cell.localRelief() > config.plainMaxRelief())) {
            return LandformType.VALLEY;
        }
        if (cell.tpiLarge() <= config.basinTpiLargeMax() && cell.localRelief() <= config.plainMaxRelief() * 2.0) {
            return LandformType.BASIN;
        }
        if (cell.tpiLarge() >= config.terraceTpiLargeMin()
                && cell.slope() <= config.plainMaxSlope()
                && cell.localRelief() <= config.plainMaxRelief()) {
            return LandformType.TERRACE;
        }
        if (cell.slope() <= config.plainMaxSlope()
                && cell.localRelief() <= config.plainMaxRelief()
                && Math.abs(cell.tpiSmall()) <= config.tpiNeutralAbsMax()) {
            return LandformType.PLAIN;
        }
        if (cell.slope() <= config.slopeMaxSlope()) {
            return LandformType.SLOPE;
        }
        return LandformType.UNKNOWN;
    }
}
