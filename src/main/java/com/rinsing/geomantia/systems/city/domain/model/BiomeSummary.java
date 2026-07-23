package com.rinsing.geomantia.systems.city.domain.model;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

public record BiomeSummary(
        String dominantBiome,
        Map<String, Integer> biomeHistogram,
        boolean mixedBiome,
        int sampledCellCount) {

    public BiomeSummary {
        dominantBiome = normalize(dominantBiome);
        LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
        if (biomeHistogram != null) {
            biomeHistogram.entrySet().stream()
                    .filter(entry -> !normalize(entry.getKey()).isBlank())
                    .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                    .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                            .reversed()
                            .thenComparing(entry -> normalize(entry.getKey())))
                    .forEach(entry -> copy.put(normalize(entry.getKey()), entry.getValue()));
        }
        biomeHistogram = Collections.unmodifiableMap(copy);
        mixedBiome = biomeHistogram.size() > 1;
        sampledCellCount = Math.max(0, sampledCellCount);
        if (biomeHistogram.isEmpty()) {
            dominantBiome = "unknown";
            sampledCellCount = 0;
        }
    }

    public static BiomeSummary empty() {
        return new BiomeSummary("unknown", Map.of(), false, 0);
    }

    public static BiomeSummary fromHistogram(Map<String, Integer> histogram) {
        if (histogram == null || histogram.isEmpty()) {
            return empty();
        }
        LinkedHashMap<String, Integer> sorted = new LinkedHashMap<>();
        histogram.entrySet().stream()
                .filter(entry -> !normalize(entry.getKey()).isBlank())
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(entry -> normalize(entry.getKey())))
                .forEach(entry -> sorted.put(normalize(entry.getKey()), entry.getValue()));
        if (sorted.isEmpty()) {
            return empty();
        }
        int total = sorted.values().stream().mapToInt(Integer::intValue).sum();
        return new BiomeSummary(sorted.keySet().iterator().next(), sorted, sorted.size() > 1, total);
    }

    public boolean hasKnownBiome() {
        return !biomeHistogram.isEmpty() && !"unknown".equals(dominantBiome);
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
