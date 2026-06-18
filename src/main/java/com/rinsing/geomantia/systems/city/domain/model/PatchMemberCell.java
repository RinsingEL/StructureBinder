package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonObject;

public record PatchMemberCell(
        int cellX,
        int cellZ,
        int blockMinX,
        int blockMinZ) {

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("cellX", cellX);
        obj.addProperty("cellZ", cellZ);
        obj.addProperty("blockMinX", blockMinX);
        obj.addProperty("blockMinZ", blockMinZ);
        return obj;
    }
}
