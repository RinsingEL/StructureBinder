package com.rinsing.geomantia.systems.city.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves every artifact produced by one City test under a single stable package root. */
public final class CityTestRunLayout {
    public static final String D3 = "d3";
    public static final String BLUEPRINT = "blueprint";
    public static final String D4 = "d4";
    public static final String D4_CANDIDATES = "d4_candidates";
    public static final String D4_ARRAY_CANDIDATES = "d4_array_candidates";
    public static final String D4_STRUCTURE_CLUSTER_GROUPS = "d4_structure_cluster_groups";
    public static final String D4_CANDIDATE_SESSION = "d4_candidate_session";
    public static final String D4_ARRAY_LAYOUT = "d4_array_layout";
    public static final String D4_DESIGN_LOOP = "d4_design_loop";
    public static final String D4_STAGED = "d4_staged";
    public static final String D5 = "d5";
    public static final String LAND_USE = "land_use";
    public static final String DECORATION = "decoration";
    public static final String D6 = "d6";
    public static final String D7 = "d7";
    public static final String WALLS = "walls";
    public static final String WORKFLOW = "workflow";

    private static final Map<String, String> LEGACY_PREFIXES = legacyPrefixes();

    private final Path runDirectory;
    private final String safeCityId;
    private final Path packageDirectory;
    private final boolean legacy;

    private CityTestRunLayout(Path runDirectory, String cityId) {
        this.runDirectory = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
        this.safeCityId = safe(Objects.requireNonNull(cityId, "cityId"));
        this.packageDirectory = this.runDirectory.resolve("city_test_runs").resolve(safeCityId);
        this.legacy = !Files.exists(packageDirectory) && hasLegacyArtifacts();
    }

    public static CityTestRunLayout open(Path runDirectory, String cityId) {
        return new CityTestRunLayout(runDirectory, cityId);
    }

    public Path packageDirectory() {
        return packageDirectory;
    }

    public Path manifestPath() {
        return legacy
                ? stepDirectory(WORKFLOW).resolve("city_workflow_report.json")
                : packageDirectory.resolve("test_run_manifest.json");
    }

    public Path stepDirectory(String stage) {
        String legacyPrefix = LEGACY_PREFIXES.get(stage);
        if (legacyPrefix == null) {
            throw new IllegalArgumentException("Unknown City test stage: " + stage);
        }
        return legacy
                ? runDirectory.resolve(legacyPrefix + safeCityId)
                : packageDirectory.resolve("steps").resolve(stage);
    }

    public Path legacyDressingDirectory() {
        return runDirectory.resolve("city_dressing_" + safeCityId);
    }

    public boolean legacy() {
        return legacy;
    }

    public String testRunId(String runId, String cityId) {
        return Objects.requireNonNull(runId, "runId") + "::" + Objects.requireNonNull(cityId, "cityId");
    }

    private boolean hasLegacyArtifacts() {
        for (String prefix : LEGACY_PREFIXES.values()) {
            if (Files.exists(runDirectory.resolve(prefix + safeCityId))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> legacyPrefixes() {
        Map<String, String> prefixes = new LinkedHashMap<>();
        prefixes.put(D3, "city_d3_");
        prefixes.put(BLUEPRINT, "city_blueprint_");
        prefixes.put(D4, "city_d4_");
        prefixes.put(D4_CANDIDATES, "city_d4_candidates_");
        prefixes.put(D4_ARRAY_CANDIDATES, "city_d4_array_candidates_");
        prefixes.put(D4_STRUCTURE_CLUSTER_GROUPS, "city_d4_structure_cluster_groups_");
        prefixes.put(D4_CANDIDATE_SESSION, "city_d4_candidate_session_");
        prefixes.put(D4_ARRAY_LAYOUT, "city_d4_array_layout_");
        prefixes.put(D4_DESIGN_LOOP, "city_d4_design_loop_");
        prefixes.put(D4_STAGED, "city_d4_staged_");
        prefixes.put(D5, "city_d5_");
        prefixes.put(LAND_USE, "city_land_use_");
        prefixes.put(DECORATION, "city_decoration_");
        prefixes.put(D6, "city_d6_");
        prefixes.put(D7, "city_d7_");
        prefixes.put(WALLS, "city_walls_");
        prefixes.put(WORKFLOW, "city_workflow_");
        return Map.copyOf(prefixes);
    }

    private static String safe(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
