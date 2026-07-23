package com.user.terra_script.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CityGenerationConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("terra_script_city_generation.json");
    private static volatile Root cached;

    private CityGenerationConfig() {}

    public static Root get() {
        Root current = cached;
        return current != null ? current : load();
    }

    public static synchronized Root load() {
        try {
            ensureExists();
            Root root = GSON.fromJson(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8), Root.class);
            if (root == null) root = defaultConfig();
            normalize(root);
            cached = root;
            return root;
        } catch (Exception e) {
            Root fallback = defaultConfig();
            cached = fallback;
            return fallback;
        }
    }

    public static boolean resolveStrictTagSource(Boolean requestOverride) {
        if (requestOverride != null) return requestOverride;
        return get().strict_tag_source_default;
    }

    private static void ensureExists() throws Exception {
        if (Files.exists(CONFIG_PATH)) return;
        Files.createDirectories(CONFIG_PATH.getParent());
        Files.writeString(CONFIG_PATH, GSON.toJson(defaultConfig()), StandardCharsets.UTF_8);
    }

    private static Root defaultConfig() {
        Root root = new Root();
        root.strict_tag_source_default = true;
        return root;
    }

    private static void normalize(Root root) {
        if (root == null) return;
    }

    public static final class Root {
        public boolean strict_tag_source_default = true;
    }
}
