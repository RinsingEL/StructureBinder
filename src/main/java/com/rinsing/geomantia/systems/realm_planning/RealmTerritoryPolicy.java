package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonObject;

/** Fixed ownership is a boundary contract, not a cheaper expansion cost. */
public record RealmTerritoryPolicy(String mode, int nearshoreRadiusCells) {
    public static final RealmTerritoryPolicy EXPANDING = new RealmTerritoryPolicy("expanding", 0);

    public RealmTerritoryPolicy {
        if (!"expanding".equals(mode) && !"fixed".equals(mode))
            throw new IllegalArgumentException("REALM_TERRITORY_POLICY: mode must be expanding or fixed.");
        if (nearshoreRadiusCells < 0 || nearshoreRadiusCells > 16
                || "expanding".equals(mode) && nearshoreRadiusCells != 0)
            throw new IllegalArgumentException("REALM_TERRITORY_POLICY: radius must be 0..16; expanding requires 0.");
    }

    public boolean fixed() { return "fixed".equals(mode); }

    public static RealmTerritoryPolicy fromJson(JsonObject profile) {
        if (!profile.has("territoryPolicy")) return EXPANDING;
        try {
            JsonObject value = profile.getAsJsonObject("territoryPolicy");
            return new RealmTerritoryPolicy(value.get("mode").getAsString(),
                    value.has("nearshoreRadiusCells") ? value.get("nearshoreRadiusCells").getAsBigDecimal().intValueExact() : 0);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("REALM_TERRITORY_POLICY: invalid mode or integer radius.", invalid);
        }
    }

    public JsonObject asJson() {
        JsonObject result = new JsonObject();
        result.addProperty("mode", mode);
        result.addProperty("nearshoreRadiusCells", nearshoreRadiusCells);
        return result;
    }
}
