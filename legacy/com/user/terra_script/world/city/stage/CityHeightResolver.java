package com.user.terra_script.world.city.stage;

import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.domain.world.scan.ScanPixel;

public final class CityHeightResolver {
    private static final int INVALID_HEIGHT_THRESHOLD = -60;

    private CityHeightResolver() {}

    public static int resolveHeight(CityStage1BinaryIO.HeightData heightData, CityC2ScanBinaryIO.C2ScanData c2ScanData, int worldX, int worldZ) {
        int primary = heightFromStage1(heightData, worldX, worldZ);
        if (primary > INVALID_HEIGHT_THRESHOLD) {
            return primary;
        }
        int fallback = heightFromC2(c2ScanData, worldX, worldZ);
        if (fallback > INVALID_HEIGHT_THRESHOLD) {
            return fallback;
        }
        return primary;
    }

    private static int heightFromStage1(CityStage1BinaryIO.HeightData data, int worldX, int worldZ) {
        if (data == null || data.heightMap == null) return 0;
        int ix = worldX - data.originX;
        int iz = worldZ - data.originZ;
        if (ix < 0 || iz < 0 || ix >= data.width || iz >= data.height) return 0;
        return data.heightMap[ix][iz];
    }

    private static int heightFromC2(CityC2ScanBinaryIO.C2ScanData data, int worldX, int worldZ) {
        if (data == null || data.map == null || data.map.length == 0 || data.map[0] == null) return 0;
        int step = Math.max(1, data.step);
        int ix = (int) Math.round((worldX - data.originX) / (double) step);
        int iz = (int) Math.round((worldZ - data.originZ) / (double) step);
        if (ix < 0 || iz < 0 || ix >= data.map.length || iz >= data.map[0].length) return 0;
        ScanPixel pixel = data.map[ix][iz];
        return pixel != null ? pixel.height() : 0;
    }
}
