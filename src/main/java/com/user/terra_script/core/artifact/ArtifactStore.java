package com.user.terra_script.core.artifact;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class ArtifactStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ROOT_DIR = "terra_script";

    public Path resolve(MinecraftServer server, String worldId, ArtifactKey key) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path base = worldRoot.resolve(ROOT_DIR).resolve(worldId);
        return base.resolve(key.relativePath);
    }

    public void writeJsonAtomic(Path p, Object obj) throws Exception {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(obj), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public void writeDatAtomic(Path p, byte[] bytes) throws Exception {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
