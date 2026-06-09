package com.user.terra_script.world.city.stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class CityGroupPathUtil {
    private CityGroupPathUtil() {}

    public static Path resolveGroupDir(Path cityDir, String groupId) throws Exception {
        Path dir = cityDir.resolve("groups").resolve(safeGroupId(groupId));
        Files.createDirectories(dir);
        return dir;
    }

    public static String safeGroupId(String groupId) {
        if (groupId == null || groupId.isBlank()) return "group_unknown";
        String normalized = groupId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
        return normalized.isBlank() ? "group_unknown" : normalized;
    }

    public static String relativeGroupPath(String cityId, String groupId, String fileName) {
        return "cities/" + cityId + "/groups/" + safeGroupId(groupId) + "/" + fileName;
    }
}
