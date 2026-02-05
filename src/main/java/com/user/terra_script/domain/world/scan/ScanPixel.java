package com.user.terra_script.domain.world.scan;

// 单个采样点的数据
public record ScanPixel(int x, int z, int height, String biomeId, boolean isLand, float temperature) {
}
