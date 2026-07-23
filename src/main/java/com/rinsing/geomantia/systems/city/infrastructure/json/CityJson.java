package com.rinsing.geomantia.systems.city.infrastructure.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class CityJson {
    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private CityJson() {
    }
}
