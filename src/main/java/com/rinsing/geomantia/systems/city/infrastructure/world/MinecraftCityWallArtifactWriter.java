package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityWallTemplateCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import java.io.IOException;
import java.nio.file.*;

public final class MinecraftCityWallArtifactWriter {
    public Path writeArtifacts(JsonObject plan, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("city_wall_plan.json");
        Files.writeString(path, CityJson.GSON.toJson(plan));
        if (plan.has("wallModuleSnapshot")) {
            var modules = CityWallModuleConfig.loadCurrent();
            modules.requireMatches(plan);
            Path templates = directory.resolve("city_wall_templates");
            Files.createDirectories(templates);
            Files.writeString(templates.resolve("wall_template_library.json"), CityJson.GSON.toJson(plan.get("templateLibrary")));
            Files.writeString(templates.resolve("modules.json"), CityJson.GSON.toJson(modules.configuration()));
            Files.writeString(templates.resolve("module_snapshot.json"), CityJson.GSON.toJson(modules.snapshot()));
            writeModule(templates, modules.configuration().getAsJsonObject("modules").get("guardTower").getAsString(), modules.towerBytes());
            writeModule(templates, modules.configuration().getAsJsonObject("modules").get("straightWall").getAsString(), modules.wallBytes());
        } else writeTemplates(directory.resolve("city_wall_templates"));
        return path;
    }

    private static void writeModule(Path directory, String relative, byte[] bytes) throws IOException {
        Path target = directory.resolve(relative).normalize();
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    public void writeTemplates(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("wall_template_library.json"), CityJson.GSON.toJson(CityWallTemplateCatalog.libraryJson()));
        for (String file : new String[]{"guard_tower.nbt", "wall_straight.nbt", "manifest.json"}) {
            try (var input = getClass().getResourceAsStream("/data/geomantia/structures/city_walls/" + file)) {
                if (input == null) throw new IOException("CITY_WALL_MODULE_MISSING:" + file);
                Files.copy(input, directory.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
