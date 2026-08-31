package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps AI-facing semantic decoration references to concrete content catalog entries. */
public final class CityDecorationStyleProfileCatalog {
    public static final String SCHEMA = "city_decoration_style_profile";

    private final Map<String, StyleProfile> profiles;

    CityDecorationStyleProfileCatalog(Map<String, StyleProfile> profiles) {
        this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    public Map<String, StyleProfile> profiles() {
        return profiles;
    }

    public StyleProfile requireProfile(String styleProfileId) {
        StyleProfile profile = profiles.get(styleProfileId);
        if (profile == null) {
            throw new CityDecorationContentCatalog.CatalogException("CITY_DECORATION_STYLE_PROFILE_UNKNOWN",
                    "Unknown City decoration style profile: " + styleProfileId);
        }
        return profile;
    }

    public record StyleProfile(String styleProfileId, String styleProfileHash,
                               Map<String, Mapping> mappings) {
        public StyleProfile {
            if (styleProfileId == null || !styleProfileId.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("CITY_DECORATION_STYLE_PROFILE_ID_INVALID");
            }
            if (styleProfileHash == null || styleProfileHash.isBlank()) {
                throw new IllegalArgumentException("CITY_DECORATION_STYLE_PROFILE_HASH_REQUIRED");
            }
            mappings = Collections.unmodifiableMap(new LinkedHashMap<>(mappings));
            if (mappings.isEmpty()) {
                throw new IllegalArgumentException("CITY_DECORATION_STYLE_PROFILE_MAPPINGS_REQUIRED");
            }
        }

        public Mapping requireMapping(String semanticRef) {
            Mapping mapping = mappings.get(semanticRef);
            if (mapping == null) {
                throw new CityDecorationContentCatalog.CatalogException("CITY_DECORATION_STYLE_SEMANTIC_REF_UNKNOWN",
                        "Style profile " + styleProfileId + " does not map semanticRef: " + semanticRef);
            }
            return mapping;
        }
    }

    public record Mapping(String semanticRef, List<Variant> variants) {
        public Mapping {
            if (semanticRef == null || !semanticRef.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("CITY_DECORATION_SEMANTIC_REF_INVALID");
            }
            variants = List.copyOf(variants);
            if (variants.isEmpty()) {
                throw new IllegalArgumentException("CITY_DECORATION_STYLE_VARIANTS_REQUIRED");
            }
            if (variants.stream().map(Variant::contentRef).distinct().count() != variants.size()) {
                throw new IllegalArgumentException("CITY_DECORATION_STYLE_VARIANT_CONTENT_DUPLICATE");
            }
        }
    }

    public record Variant(String contentRef, double weight) {
        public Variant {
            if (contentRef == null || contentRef.isBlank() || !Double.isFinite(weight) || weight <= 0.0D) {
                throw new IllegalArgumentException("CITY_DECORATION_STYLE_VARIANT_INVALID");
            }
        }
    }
}
