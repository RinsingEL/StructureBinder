package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonObject;

public record BlockPoint(int x, int z) {
    public static final BlockPoint ORIGIN = new BlockPoint(0, 0);

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", x);
        obj.addProperty("z", z);
        return obj;
    }
}
