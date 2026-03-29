package com.user.terra_script.runtime.log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.runtime.context.RuntimeLogContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class RuntimeLogManager {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private RuntimeLogManager() {}

    public static void append(MinecraftServer server, RuntimeLogEntry entry, RuntimeLogContext context) {
        if (server == null || entry == null || context == null) return;
        try {
            Path path = logPath(server, context.domain);
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    GSON.toJson(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
        }
    }

    private static Path logPath(MinecraftServer server, String domain) {
        String safeDomain = domain == null || domain.isBlank()
                ? "runtime"
                : domain.replaceAll("[^A-Za-z0-9_.-]", "_");
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("terra_script")
                .resolve("runtime_logs")
                .resolve(safeDomain + ".jsonl");
    }
}
