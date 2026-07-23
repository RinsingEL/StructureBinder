package com.rinsing.geomantia.systems.gis.testsupport;

import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;

public interface SyntheticTerrainProfile {
    double seaLevel();

    double elevationAt(double blockX, double blockZ);

    default boolean hasWaterAt(double blockX, double blockZ) {
        return true;
    }

    default SurfaceType surfaceTypeAt(double blockX, double blockZ, double elevation) {
        return elevation > seaLevel() + 55.0 ? SurfaceType.ROCK : SurfaceType.GRASS;
    }

    default String biomeAt(double blockX, double blockZ, double elevation, boolean water) {
        if (water) {
            return "minecraft:ocean";
        }
        return elevation > seaLevel() + 45.0 ? "minecraft:windswept_hills" : "minecraft:plains";
    }

    static SyntheticTerrainProfile plain() {
        return new SyntheticTerrainProfile() {
            @Override
            public double seaLevel() {
                return 62.0;
            }

            @Override
            public double elevationAt(double blockX, double blockZ) {
                return 70.0 + Math.sin(blockX / 70.0) * 0.8 + Math.cos(blockZ / 80.0) * 0.6;
            }
        };
    }

    static SyntheticTerrainProfile mountain() {
        return new SyntheticTerrainProfile() {
            @Override
            public double seaLevel() {
                return 40.0;
            }

            @Override
            public double elevationAt(double blockX, double blockZ) {
                double ridge = 60.0 * Math.exp(-Math.pow(blockX / 95.0, 2.0));
                double valley = -28.0 * Math.exp(-Math.pow((blockZ - 80.0) / 55.0, 2.0));
                double waves = Math.sin(blockZ / 24.0) * 6.0 + Math.cos(blockX / 18.0) * 3.0;
                return 78.0 + ridge + valley + waves;
            }
        };
    }

    static SyntheticTerrainProfile water() {
        return new SyntheticTerrainProfile() {
            @Override
            public double seaLevel() {
                return 62.0;
            }

            @Override
            public double elevationAt(double blockX, double blockZ) {
                double coast = blockX + Math.sin(blockZ / 40.0) * 35.0;
                if (coast < -30.0) {
                    return 48.0 + Math.sin(blockZ / 38.0) * 2.0;
                }
                if (coast < 34.0) {
                    return 58.0 + coast * 0.18 + Math.sin(blockZ / 28.0) * 1.6;
                }
                return 69.0 + Math.sin(blockX / 60.0) * 2.0 + Math.cos(blockZ / 50.0) * 1.2;
            }

            @Override
            public SurfaceType surfaceTypeAt(double blockX, double blockZ, double elevation) {
                double coast = blockX + Math.sin(blockZ / 40.0) * 35.0;
                return Math.abs(coast) < 55.0 ? SurfaceType.SAND : SurfaceType.GRASS;
            }
        };
    }

    static SyntheticTerrainProfile mixed() {
        return new SyntheticTerrainProfile() {
            @Override
            public double seaLevel() {
                return 62.0;
            }

            @Override
            public double elevationAt(double blockX, double blockZ) {
                double basin = -22.0 * Math.exp(-(Math.pow((blockX - 70.0) / 75.0, 2.0)
                        + Math.pow((blockZ + 20.0) / 75.0, 2.0)));
                double terrace = blockZ > 40.0 ? 18.0 : 0.0;
                double ridge = 38.0 * Math.exp(-Math.pow((blockX + 100.0) / 50.0, 2.0));
                double waterCut = blockX < -140.0 ? -26.0 : 0.0;
                return 72.0 + basin + terrace + ridge + waterCut
                        + Math.sin(blockX / 26.0) * 3.5 + Math.cos(blockZ / 31.0) * 3.0;
            }
        };
    }
}
