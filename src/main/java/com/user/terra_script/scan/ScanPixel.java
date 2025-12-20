package com.user.terra_script.scan;

import java.util.ArrayList;
import java.util.List;

// 单个采样点的数据
public record ScanPixel(int x, int z, int height, String biomeId, boolean isLand) {
}