package com.user.terra_script.territory.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TerritoryBlueprint {
    public String territory_id;
    public String name;
    public int target_continent_id;
    public Narrative narrative;
    public ExpansionPolicy expansion_policy;
    public List<ContinentPlan> continents;

    public List<ContinentPlan> normalizedContinents() {
        List<ContinentPlan> normalized = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        if (continents != null) {
            for (ContinentPlan raw : continents) {
                if (raw == null) continue;
                int continentId = raw.continent_id;
                if (continentId <= 0) continue;
                if (Boolean.FALSE.equals(raw.enabled)) continue;
                if (!seen.add(continentId)) continue;
                normalized.add(raw.normalized());
            }
        }
        if (!normalized.isEmpty()) {
            return normalized;
        }
        if (target_continent_id > 0) {
            ContinentPlan legacy = new ContinentPlan();
            legacy.continent_id = target_continent_id;
            legacy.enabled = true;
            legacy.site_preferences = new SitePreferences();
            normalized.add(legacy);
        }
        return normalized;
    }

    public ContinentPlan findContinent(int continentId) {
        for (ContinentPlan plan : normalizedContinents()) {
            if (plan.continent_id == continentId) return plan;
        }
        return null;
    }

    public ExpansionPolicy normalizedExpansionPolicy() {
        ExpansionPolicy policy = expansion_policy != null ? expansion_policy : new ExpansionPolicy();
        if (policy.base_power <= 0) policy.base_power = 100;
        if (policy.land_power <= 0) {
            policy.land_power = Math.max(1, (int) Math.round(policy.base_power * 0.35));
        }
        if (policy.costs == null) policy.costs = new Costs();
        if (policy.costs.base_move <= 0) policy.costs.base_move = 1.0;
        if (policy.costs.slope_penalty <= 0) policy.costs.slope_penalty = 2.0;
        if (policy.costs.water_penalty <= 0) policy.costs.water_penalty = 5.0;
        if (policy.costs.forest_penalty <= 0) policy.costs.forest_penalty = 1.5;
        if (policy.costs.preferred_biomes == null) policy.costs.preferred_biomes = new ArrayList<>();
        if (policy.costs.avoid_biomes == null) policy.costs.avoid_biomes = new ArrayList<>();
        return policy;
    }

    public String instanceId(int continentId) {
        return instanceId(territory_id, continentId);
    }

    public static String instanceId(String territoryId, int continentId) {
        String base = territoryId == null || territoryId.isBlank() ? "unknown" : territoryId.trim();
        return base + "@c" + continentId;
    }

    public static class Narrative {
        public String theme;
        public String description;
        public String ruler;
        public String color;
    }

    public static class ExpansionPolicy {
        public int base_power;
        public int land_power;
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

    public static class ContinentPlan {
        public int continent_id;
        public Boolean enabled;
        public SitePreferences site_preferences;

        public ContinentPlan normalized() {
            ContinentPlan copy = new ContinentPlan();
            copy.continent_id = continent_id;
            copy.enabled = enabled == null ? Boolean.TRUE : enabled;
            copy.site_preferences = site_preferences != null ? site_preferences.normalized() : new SitePreferences();
            return copy;
        }
    }

    public static class SitePreferences {
        public Double min_slope;
        public Double max_slope;
        public Double min_tpi;
        public Double max_tpi;
        public Integer top_k;
        public List<String> preferred_biomes;
        public List<String> avoid_biomes;
        public List<String> preferred_landforms;
        public List<String> avoid_landforms;
        public List<String> avoid_conflict_with;

        public SitePreferences normalized() {
            SitePreferences copy = new SitePreferences();
            copy.min_slope = min_slope;
            copy.max_slope = max_slope;
            copy.min_tpi = min_tpi;
            copy.max_tpi = max_tpi;
            copy.top_k = top_k != null && top_k > 0 ? top_k : 5;
            copy.preferred_biomes = preferred_biomes != null ? new ArrayList<>(preferred_biomes) : new ArrayList<>();
            copy.avoid_biomes = avoid_biomes != null ? new ArrayList<>(avoid_biomes) : new ArrayList<>();
            copy.preferred_landforms = preferred_landforms != null ? new ArrayList<>(preferred_landforms) : new ArrayList<>();
            copy.avoid_landforms = avoid_landforms != null ? new ArrayList<>(avoid_landforms) : new ArrayList<>();
            copy.avoid_conflict_with = avoid_conflict_with != null ? new ArrayList<>(avoid_conflict_with) : new ArrayList<>();
            return copy;
        }
    }
}
