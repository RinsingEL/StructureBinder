package com.user.terra_script.config;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ForbiddenZoneConfig {
    private static final String FILE_NAME = "terra_script_forbidden_zones.json";
    private static final Gson GSON = new Gson();

    public static class ForbiddenZone {
        public String id;
        public String reason;
        public int chunk_x;
        public int chunk_z;
    }

    public static List<ForbiddenZone> load() {
        File file = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME).toFile();
        if (!file.exists()) return Collections.emptyList();
        try {
            String json = Files.readString(file.toPath());
            List<ForbiddenZone> zones = GSON.fromJson(json, new TypeToken<List<ForbiddenZone>>(){}.getType());
            return zones != null ? zones : Collections.emptyList();
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    public static Set<Long> toChunkKeys(List<ForbiddenZone> zones) {
        if (zones == null || zones.isEmpty()) return Collections.emptySet();
        Set<Long> keys = new HashSet<>();
        for (ForbiddenZone z : zones) {
            keys.add(ChunkPos.asLong(z.chunk_x, z.chunk_z));
        }
        return keys;
    }

    public static Map<Long, String> toChunkReasonMap(List<ForbiddenZone> zones) {
        if (zones == null || zones.isEmpty()) return Collections.emptyMap();
        Map<Long, String> map = new HashMap<>();
        for (ForbiddenZone z : zones) {
            map.put(ChunkPos.asLong(z.chunk_x, z.chunk_z), z.reason);
        }
        return map;
    }
}
