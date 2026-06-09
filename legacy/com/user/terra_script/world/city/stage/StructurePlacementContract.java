package com.user.terra_script.world.city.stage;

import com.google.gson.Gson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StructurePlacementContract {
    private static final Gson GSON = new Gson();
    private static final Object CACHE_LOCK = new Object();
    private static volatile long cachedCatalogTimestamp = Long.MIN_VALUE;
    private static volatile Map<String, CityC35CatalogIO.CatalogStructure> cachedById = Collections.emptyMap();

    private StructurePlacementContract() {
    }

    public static int resolveSurfaceAlignedOriginY(String templateId, int surfaceY) {
        return resolveSurfaceAlignedOriginY(surfaceY, originOffsetY(templateId));
    }

    static int resolveSurfaceAlignedOriginY(int surfaceY, int originOffsetY) {
        return surfaceY + originOffsetY;
    }

    public static int originOffsetY(String templateId) {
        CityC35CatalogIO.CatalogStructure meta = findStructure(templateId);
        if (meta == null || meta.placement == null || meta.placement.origin_offset == null) {
            return 0;
        }
        return meta.placement.origin_offset.y;
    }

    public static CityC35CatalogIO.CatalogStructure findStructure(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return null;
        }
        return catalogIndex().get(templateId);
    }

    private static Map<String, CityC35CatalogIO.CatalogStructure> catalogIndex() {
        Path path = safeCatalogPath();
        long timestamp = lastModified(path);
        Map<String, CityC35CatalogIO.CatalogStructure> snapshot = cachedById;
        if (timestamp == cachedCatalogTimestamp) {
            return snapshot;
        }
        synchronized (CACHE_LOCK) {
            if (timestamp == cachedCatalogTimestamp) {
                return cachedById;
            }
            cachedById = loadCatalogIndex();
            cachedCatalogTimestamp = timestamp;
            return cachedById;
        }
    }

    private static Map<String, CityC35CatalogIO.CatalogStructure> loadCatalogIndex() {
        try {
            if (safeCatalogPath() == null) {
                return Collections.emptyMap();
            }
            CityC35CatalogIO.StructureCatalog catalog = CityC35CatalogIO.loadCatalog(GSON, CityC35CatalogIO.StructureCatalog.class);
            if (catalog == null || catalog.structures == null || catalog.structures.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, CityC35CatalogIO.CatalogStructure> index = new LinkedHashMap<>();
            for (CityC35CatalogIO.CatalogStructure meta : catalog.structures) {
                if (meta == null || meta.structure_id == null || meta.structure_id.isBlank()) {
                    continue;
                }
                index.putIfAbsent(meta.structure_id, meta);
            }
            return Collections.unmodifiableMap(index);
        } catch (Exception e) {
            System.err.println("[StructurePlacementContract] Failed to load placement contract catalog: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static Path safeCatalogPath() {
        try {
            return CityC35CatalogIO.catalogPath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long lastModified(Path path) {
        try {
            return path != null && Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : Long.MIN_VALUE;
        } catch (Exception ignored) {
            return Long.MIN_VALUE;
        }
    }
}
