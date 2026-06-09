package com.rinsing.geomantia.world.atlas;

public record GisClassifierConfig(
        double plainMaxSlope,
        double slopeMaxSlope,
        double cliffMinSlope,
        double plainMaxRelief,
        double roughTerrainMin,
        double ridgeTpiLargeMin,
        double valleyTpiLargeMax,
        double terraceTpiLargeMin,
        double basinTpiLargeMax,
        double tpiNeutralAbsMax,
        int shoreMaxWaterDistanceCells,
        double deepWaterMinDepth,
        double shallowWaterMaxDepth,
        double shoreMaxSlope,
        int fragmentMaxPatchCells
) {
    public static GisClassifierConfig defaults() {
        return new GisClassifierConfig(
                2.25,
                7.5,
                14.0,
                5.0,
                5.5,
                8.0,
                -8.0,
                4.0,
                -10.0,
                3.5,
                3,
                4.0,
                2.0,
                5.0,
                8
        );
    }
}
