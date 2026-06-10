package com.rinsing.geomantia.systems.gis.preview;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class AtlasJson {
    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private AtlasJson() {
    }
}
