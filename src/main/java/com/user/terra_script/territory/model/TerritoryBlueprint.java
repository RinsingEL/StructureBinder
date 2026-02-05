package com.user.terra_script.territory.model;

import java.util.List;

public class TerritoryBlueprint {
    public String territory_id;
    public String name;
    public int target_continent_id;
    public Narrative narrative;
    public ExpansionPolicy expansion_policy;

    public static class Narrative {
        public String theme;
        public String description;
        public String ruler;
        public String color;
    }

    public static class ExpansionPolicy {
        public int base_power;
        public Costs costs;
    }

    public static class Costs {
        public double base_move;
        public double slope_penalty;
        public double water_penalty;
        public double forest_penalty;
        public List<String> preferred_biomes;
        public List<String> avoid_biomes;
    }
}
