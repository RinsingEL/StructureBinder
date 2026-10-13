package com.rinsing.geomantia.adventure;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExplorationProfileTest {
    @Test void trialProfileKeepsStarterFreeOfMutantsAndBossPools() throws Exception {
        Path root = Path.of("addons/adventure/profiles/exploration-trial");
        Map<String,String> files = new HashMap<>();
        for (String name : List.of("distribution.json", "mob_pools.json", "structure_pools.json",
                "settlement_rules.json", "generation_control.json")) {
            files.put(name, Files.readString(root.resolve(name)));
        }
        var config = AdventureConfig.parse(files);
        assertEquals(List.of("land_common"), config.select("land", 1).mobs());
        assertEquals(List.of("land_light"), config.select("land", 1).structures());
        assertTrue(config.select("land", 2).mobs().contains("mutants_rare"));
        assertFalse(config.select("land", 2).structures().contains("land_boss_sites"));
        assertTrue(config.select("land", 3).structures().contains("land_boss_sites"));
        assertTrue(config.distribution().ocean().structures().contains("ocean_boss"));
        assertTrue(config.controls().structures().values().stream().noneMatch(Boolean::booleanValue));
        var bandit = "dungeons_arise:bandit_towers";
        for (int tier : List.of(1, 2)) {
            assertFalse(config.select("land", tier).structures().stream()
                    .flatMap(id -> config.structures().get(id).entries().stream())
                    .anyMatch(entry -> entry.structure().equals(bandit)));
        }
        assertTrue(config.select("land", 3).structures().stream()
                .flatMap(id -> config.structures().get(id).entries().stream())
                .anyMatch(entry -> entry.structure().equals(bandit)));
        assertEquals(6, config.structures().get("land_light").entries().size());
        var major = config.structures().get("land_encounters");
        assertEquals(60, major.spacingChunks());
        assertEquals(50, major.separationChunks());
        assertEquals(3, major.entries().stream()
                .filter(entry -> entry.structure().equals("dungeons_arise:illager_campsite"))
                .findFirst().orElseThrow().weight());
        assertFalse(config.select("nearSea", 1).structures().contains("wda_sea_encounters"));
        assertTrue(config.select("nearSea", 2).structures().contains("wda_sea_encounters"));
        assertFalse(config.select("nearSea", 2).structures().contains("wda_sea_rare"));
        assertTrue(config.select("nearSea", 3).structures().contains("wda_sea_rare"));
        assertTrue(config.structures().values().stream().flatMap(pool -> pool.entries().stream())
                .noneMatch(entry -> Set.of("dungeons_arise:aviary", "dungeons_arise:giant_mushroom",
                        "dungeons_arise:small_prairie_house").contains(entry.structure())));
    }
}
