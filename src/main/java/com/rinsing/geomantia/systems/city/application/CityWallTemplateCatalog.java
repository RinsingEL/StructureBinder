package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class CityWallTemplateCatalog {
    private CityWallTemplateCatalog() {}
    public static JsonObject libraryJson() {
        JsonObject library = new JsonObject();
        library.addProperty("schema", "city_wall_template_library");
        library.addProperty("templateSource", "ac3 城墙守卫塔.litematic");
        library.addProperty("moduleSet", "guard_tower");
        library.addProperty("walkwayFloorY", 9);
        library.addProperty("passageHeadroom", 2);
        JsonArray templates = new JsonArray();
        templates.add(template("guard_tower",7,10,15));
        templates.add(template("wall_straight",16,5,12));
        library.add("templates",templates);
        return library;
    }
    private static JsonObject template(String id,int width,int depth,int height) {
        JsonObject result=new JsonObject();
        result.addProperty("templateId",id);
        result.addProperty("widthBlocks",width);
        result.addProperty("depthBlocks",depth);
        result.addProperty("heightBlocks",height);
        result.addProperty("format","minecraft_structure_template_nbt");
        return result;
    }
}
