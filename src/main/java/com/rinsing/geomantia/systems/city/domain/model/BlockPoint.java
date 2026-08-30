package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonObject;

public record BlockPoint(int x, int z) {
    public static final BlockPoint ORIGIN = new BlockPoint(0, 0);

    @Override
    public int hashCode() {
        long value = ((long) x << 32) ^ (z & 0xffff_ffffL);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) (value ^ value >>> 32);
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", x);
        obj.addProperty("z", z);
        return obj;
    }
}
