package com.rinsing.geomantia.systems.city.domain.landuse.rules;

import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class LandUseRuleCatalog {
    public static final String RULE_VERSION = "city_land_use_rules.v0.2";
    public static final String LEGACY_RULE_VERSION = "city_land_use_rules.v0.1";
    public static final int LEGACY_NEARBY_MERGE_MAX_BRIDGE_BLOCKS = 24;

    private final Map<String, LandUseRule> rules;

    public LandUseRuleCatalog(List<LandUseRule> rules) {
        Map<String, LandUseRule> values = new LinkedHashMap<>();
        for (LandUseRule rule : rules) {
            if (values.put(rule.ruleRef(), rule) != null) {
                throw new IllegalArgumentException("Duplicate LandUse ruleRef: " + rule.ruleRef());
            }
        }
        this.rules = Map.copyOf(values);
    }

    public Optional<LandUseRule> byRef(String ruleRef) {
        return Optional.ofNullable(rules.get(ruleRef));
    }

    public Optional<LandUseRule> resolveSemantic(List<String> terms) {
        List<SemanticMatch> matches = new java.util.ArrayList<>();
        for (String raw : terms == null ? List.<String>of() : terms) {
            String input = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
            for (LandUseRule rule : rules()) {
                for (String rawToken : rule.semanticTerms()) {
                    String token = rawToken.toLowerCase(Locale.ROOT);
                    if (!token.isBlank() && input.contains(token)) {
                        matches.add(new SemanticMatch(rule, token, input));
                    }
                }
            }
        }
        return matches.stream().sorted(java.util.Comparator
                        .comparingInt((SemanticMatch match) -> match.token().length()).reversed()
                        .thenComparing(match -> match.rule().ruleRef())
                        .thenComparing(SemanticMatch::token)
                        .thenComparing(SemanticMatch::input))
                .map(SemanticMatch::rule)
                .findFirst();
    }

    public List<LandUseRule> rules() {
        return rules.values().stream().sorted((a, b) -> a.ruleRef().compareTo(b.ruleRef())).toList();
    }

    public String profileHash() {
        StringBuilder canonical = new StringBuilder(RULE_VERSION);
        for (LandUseRule rule : rules()) {
            canonical.append('|').append(rule.ruleRef()).append('|').append(rule.landUseType())
                    .append('|').append(String.join(",", rule.semanticTerms().stream().sorted().toList()))
                    .append('|').append(rule.footprintMultiplier()).append('|').append(rule.extraAreaBlocks())
                    .append('|').append(rule.minAreaBlocks()).append('|').append(rule.maxAreaBlocks())
                    .append('|').append(rule.actionBudget()).append('|').append(rule.baseStepCost())
                    .append('|').append(rule.slopeCost()).append('|').append(rule.reliefCost())
                    .append('|').append(rule.waterCost()).append('|').append(rule.forestAffinity())
                    .append('|').append(rule.competitionWeight()).append('|').append(rule.mergeSameType())
                    .append('|').append(rule.nearbyMergeMaxBridgeBlocks())
                    .append('|').append(rule.surfacePolicy()).append('|').append(rule.vegetationPolicy())
                    .append('|').append(rule.boundaryPolicy()).append('|').append(rule.decorationPolicy());
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public static LandUseRuleCatalog defaults() {
        return new LandUseRuleCatalog(List.of(
                rule("agriculture", List.of("agriculture", "farm", "farmland", "farmstead", "barn", "crop", "农", "田"),
                        6.0, 192, 192, 4096, 520, 1.0, 0.7, 0.8, 5.0, 0,
                        32, SurfacePolicy.CULTIVATE, VegetationPolicy.CLEAR, BoundaryPolicy.FENCE),
                rule("plaza", List.of("plaza", "square", "market_center", "fountain", "courtyard", "广场", "喷泉", "庭院"),
                        2.2, 96, 96, 2048, 360, 1.0, 1.2, 1.3, 8.0, 0,
                        16, SurfacePolicy.PAVE, VegetationPolicy.CLEAR, BoundaryPolicy.OPEN),
                rule("residential", List.of("residential", "residence", "house", "housing", "home", "住宅", "民居"),
                        1.8, 48, 64, 2048, 330, 1.0, 1.1, 1.0, 7.0, 0,
                        20, SurfacePolicy.PRESERVE, VegetationPolicy.SELECTIVE_CLEAR, BoundaryPolicy.HEDGE),
                rule("commercial", List.of("commercial", "market", "shop", "stall", "storage", "warehouse", "商业", "商铺", "市场"),
                        1.8, 72, 72, 2048, 340, 1.0, 1.0, 1.1, 7.0, 0,
                        24, SurfacePolicy.PAVE, VegetationPolicy.CLEAR, BoundaryPolicy.OPEN),
                rule("industry", List.of("industry", "industrial", "production", "workshop", "smith", "forge",
                                "mine", "mining", "quarry", "ore", "coal", "矿业", "矿井", "采矿", "工坊",
                                "冶炼", "function.矿业"),
                        3.0, 128, 128, 3072, 420, 1.0, 0.8, 0.8, 7.0, 0,
                        24, SurfacePolicy.PAVE, VegetationPolicy.CLEAR, BoundaryPolicy.OPEN),
                rule("forestry", List.of("forestry", "forest", "lumber", "woodland", "林场", "森林"),
                        5.0, 256, 256, 4096, 540, 1.0, 0.8, 0.7, 6.0, -1.1,
                        32, SurfacePolicy.PRESERVE, VegetationPolicy.PRESERVE, BoundaryPolicy.FENCE),
                rule("pond", List.of("pond", "fish", "fishery", "fish_pond", "鱼塘", "池塘"),
                        3.0, 128, 128, 3072, 440, 1.0, 1.4, 1.0, -0.7, 0,
                        0, SurfacePolicy.WATER_ADAPTIVE, VegetationPolicy.SELECTIVE_CLEAR, BoundaryPolicy.SHORELINE),
                rule("civic", List.of("civic", "landmark", "public_core", "church", "temple", "hall", "school",
                                "watchtower", "fort", "governance", "administrative", "公共", "教堂", "市政"),
                        1.5, 80, 80, 1536, 300, 1.0, 1.2, 1.2, 8.0, 0,
                        16, SurfacePolicy.PAVE, VegetationPolicy.CLEAR, BoundaryPolicy.LOW_WALL),
                rule("general_settlement", List.of("general_settlement", "settlement", "generic", "village", "filler",
                                "聚落", "通用"),
                        1.5, 48, 48, 1536, 300, 1.0, 1.2, 1.1, 7.0, 0,
                        20, SurfacePolicy.PRESERVE, VegetationPolicy.SELECTIVE_CLEAR, BoundaryPolicy.OPEN)
        ));
    }

    private static LandUseRule rule(String ref, List<String> terms, double multiplier, int extra, int min, int max,
                                    double budget, double base, double slope, double relief, double water,
                                    double forestAffinity, int nearbyMergeMaxBridgeBlocks, SurfacePolicy surface, VegetationPolicy vegetation,
                                    BoundaryPolicy boundary) {
        return new LandUseRule(ref, ref, terms, multiplier, extra, min, max, budget, base, slope, relief, water,
                forestAffinity, 1.0, true, nearbyMergeMaxBridgeBlocks, surface, vegetation, boundary, ref);
    }

    private record SemanticMatch(LandUseRule rule, String token, String input) {
    }
}
