package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

/** Server-start settings shared by building foundations and outdoor decks. */
public record CityFoundationSupportSettings(int maximumSolidFillHeight, int pierSpacingBlocks) {
    private static volatile CityFoundationSupportSettings current = defaults();
    public CityFoundationSupportSettings {
        if (maximumSolidFillHeight < 0 || maximumSolidFillHeight > 128)
            throw new IllegalArgumentException("CITY_FOUNDATION_FILL_HEIGHT_OUT_OF_RANGE: 0..128");
        if (pierSpacingBlocks < 2 || pierSpacingBlocks > 16)
            throw new IllegalArgumentException("CITY_FOUNDATION_PIER_SPACING_OUT_OF_RANGE: 2..16");
    }
    public static CityFoundationSupportSettings defaults() { return new CityFoundationSupportSettings(16, 4); }
    public static CityFoundationSupportSettings current() { return current; }
    public boolean requiresDeck(int groundY, int targetY) { return (long) targetY - groundY > maximumSolidFillHeight; }
    public boolean pierAt(int x, int z) { return Math.floorMod(x, pierSpacingBlocks) == 0 && Math.floorMod(z, pierSpacingBlocks) == 0; }
    public static synchronized void load(Path configRoot) throws IOException {
        current = defaults();
        Files.createDirectories(configRoot);
        Path file = configRoot.resolve("foundation_support.json");
        if (!Files.exists(file)) Files.writeString(file, "{\n  \"maximumSolidFillHeight\": 16,\n  \"pierSpacingBlocks\": 4\n}\n");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            current = new CityFoundationSupportSettings(json.get("maximumSolidFillHeight").getAsInt(),
                    json.get("pierSpacingBlocks").getAsInt());
        } catch (RuntimeException e) { throw new IOException("CITY_FOUNDATION_SUPPORT_CONFIG_INVALID", e); }
    }
}
