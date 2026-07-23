package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class CityWallTemplateCatalog {
    private CityWallTemplateCatalog() {
    }

    public static JsonObject libraryJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_wall_template_library.v0.1");
        obj.addProperty("templateSource", "GPT城墙设计.txt");
        JsonArray templates = new JsonArray();
        templates.add(template("wall_straight_15", 15, 5, 12,
                "stone_wall_tower_segment_15x5x12 straight wall portion"));
        templates.add(template("wall_tower_small", 5, 5, 12,
                "square stone buttress tower"));
        templates.add(template("watchtower_5x5", 5, 5, 12,
                "usable 5x5 watchtower with hollow interior"));
        templates.add(template("beacon_5x5", 5, 5, 16,
                "usable 5x5 beacon tower node with hollow center and straight climb access"));
        templates.add(template("wall_gap_gate_7", 7, 5, 1,
                "temporary empty gate gap"));
        templates.add(template("gatehouse_9", 9, 7, 9,
                "independent stone and timber gatehouse with full road opening"));
        templates.add(template("gatehouse_13", 13, 7, 9,
                "wide independent stone and timber gatehouse with full road opening"));
        templates.add(template("natural_water_boundary", 15, 1, 1,
                "natural water boundary marker; no continuous wall"));
        templates.add(template("natural_cliff_boundary", 15, 1, 1,
                "natural cliff boundary marker; no continuous wall"));
        obj.add("templates", templates);
        return obj;
    }

    private static JsonObject template(String id, int width, int depth, int height, String note) {
        JsonObject obj = new JsonObject();
        obj.addProperty("templateId", id);
        obj.addProperty("widthBlocks", width);
        obj.addProperty("depthBlocks", depth);
        obj.addProperty("heightBlocks", height);
        obj.addProperty("format", "minecraft_structure_template_nbt");
        obj.addProperty("note", note);
        JsonObject palette = new JsonObject();
        palette.addProperty("base", "minecraft:deepslate_bricks");
        palette.addProperty("body", "minecraft:stone_bricks");
        palette.addProperty("weathered", "minecraft:mossy_stone_bricks");
        palette.addProperty("cracked", "minecraft:cracked_stone_bricks");
        palette.addProperty("cobble", "minecraft:cobblestone");
        palette.addProperty("battlement", "minecraft:stone_brick_wall");
        palette.addProperty("walkway", "minecraft:stone_brick_slab");
        palette.addProperty("climbAccess", "minecraft:ladder");
        palette.addProperty("gateOpeningFence", "minecraft:oak_fence");
        obj.add("palette", palette);
        return obj;
    }
}
