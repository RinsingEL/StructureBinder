package com.user.terra_script.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("removal")
public class StructureDiscovery {

    public record StructureInfo(ResourceLocation id, Vec3i size) {
        @Override
        public String toString() {
            return id.toString();
        }
    }

    /**
     * 扫描游戏中所有已加载的 NBT 结构文件
     */
    public static List<StructureInfo> scanAllStructures(ServerLevel level) {
        List<StructureInfo> result = new ArrayList<>();

        System.out.println("[StructureDiscovery] Starting structure scan...");

        ResourceManager resourceManager = level.getServer().getResourceManager();
        StructureTemplateManager templateManager = level.getStructureManager();

        // 列出 "structures" 目录下的所有 .nbt 文件
        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> resources =
                resourceManager.listResources("structures", path -> path.getPath().endsWith(".nbt"));

        for (ResourceLocation fileLoc : resources.keySet()) {
            try {
                String namespace = fileLoc.getNamespace();
                String path = fileLoc.getPath();

                if (path.startsWith("structures/") && path.endsWith(".nbt")) {
                    String structPath = path.substring("structures/".length(), path.length() - ".nbt".length());
                    ResourceLocation structId = new ResourceLocation(namespace, structPath);

                    Optional<StructureTemplate> templateOp = templateManager.get(structId);
                    if (templateOp.isPresent()) {
                        StructureTemplate t = templateOp.get();
                        result.add(new StructureInfo(structId, t.getSize()));
                    }
                }
            } catch (Exception e) {
                // 忽略
            }
        }

        List<StructureInfo> sortedList = result.stream()
                .sorted(Comparator.comparing(s -> s.id.toString()))
                .collect(Collectors.toList());

        return sortedList;
    }

    /**
     * 将结构列表导出为 JSON 文件到 config 目录
     */
    public static void exportToConfig(List<StructureInfo> list, String filename) {
        try {
            // 1. 构建 JSON 数据
            JsonArray array = new JsonArray();
            for (StructureInfo info : list) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", info.id.toString());
                obj.addProperty("size_x", info.size.getX());
                obj.addProperty("size_y", info.size.getY());
                obj.addProperty("size_z", info.size.getZ());
                // 简单的分类标签 (给 AI 参考用)
                obj.addProperty("type", guessCategory(info.id.getPath()));
                array.add(obj);
            }

            // 2. 格式化输出
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String jsonOutput = gson.toJson(array);

            // 3. 写入文件
            Path path = FMLPaths.CONFIGDIR.get().resolve(filename);
            Files.writeString(path, jsonOutput);

            System.out.println("[StructureDiscovery] Exported " + list.size() + " structures to " + path.toAbsolutePath());

        } catch (IOException e) {
            System.err.println("[StructureDiscovery] Failed to export structure list: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 简单的路径关键词猜测分类
    private static String guessCategory(String path) {
        path = path.toLowerCase();
        if (path.contains("house") || path.contains("home")) return "residential";
        if (path.contains("street") || path.contains("road")) return "road";
        if (path.contains("center") || path.contains("meet")) return "center";
        if (path.contains("field") || path.contains("farm")) return "farm";
        if (path.contains("tower") || path.contains("watch")) return "military";
        if (path.contains("decor")) return "decoration";
        return "structure";
    }
}