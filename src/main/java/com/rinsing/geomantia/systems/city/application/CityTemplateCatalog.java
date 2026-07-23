package com.rinsing.geomantia.systems.city.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CityTemplateCatalog {
    public static final String SCHEMA = "city_template_catalog.v0.1";

    private final List<Template> templates;
    private final Map<String, Template> templatesByKey;

    CityTemplateCatalog(List<Template> templates) {
        this.templates = List.copyOf(Objects.requireNonNull(templates, "templates"));
        Map<String, Template> byKey = new LinkedHashMap<>();
        for (Template template : this.templates) {
            String key = key(template.templateId(), template.variantId());
            if (byKey.put(key, template) != null) {
                throw new CatalogException("CITY_TEMPLATE_CATALOG_DUPLICATE_VARIANT",
                        "Duplicate templateId/variantId: " + key);
            }
        }
        this.templatesByKey = Collections.unmodifiableMap(byKey);
    }

    public List<Template> templates() {
        return templates;
    }

    public String schemaVersion() {
        return SCHEMA;
    }

    public Template requireTemplate(String templateId, String variantId) {
        Template template = templatesByKey.get(key(templateId, variantId));
        if (template == null) {
            throw new CatalogException("CITY_TEMPLATE_CATALOG_TEMPLATE_UNKNOWN",
                    "Unknown template variant: " + templateId + " / " + variantId);
        }
        return template;
    }

    public List<Template> variants(String buildingSemantic, String style) {
        return templates.stream()
                .filter(template -> template.buildingSemantic().equals(buildingSemantic)
                        && template.style().equals(style))
                .sorted(Comparator.comparing(Template::variantId)
                        .thenComparing(Template::templateId)
                        .thenComparing(Template::contentHash))
                .toList();
    }

    public Template selectVariant(String buildingSemantic, String style, long seed) {
        List<Template> candidates = variants(buildingSemantic, style);
        if (candidates.isEmpty()) {
            throw new CatalogException("CITY_TEMPLATE_CATALOG_VARIANT_NOT_FOUND",
                    "No template variant for semantic/style: " + buildingSemantic + " / " + style);
        }
        long value = deterministicValue(seed, buildingSemantic, style);
        int index = (int) Long.remainderUnsigned(value, candidates.size());
        return candidates.get(index);
    }

    public Template chooseVariant(String buildingSemantic, String style, long seed) {
        return selectVariant(buildingSemantic, style, seed);
    }

    private static long deterministicValue(long seed, String buildingSemantic, String style) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(seed).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(buildingSemantic.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(style.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = digest.digest();
            long value = 0L;
            for (int i = 0; i < Long.BYTES; i++) {
                value = (value << 8) | (bytes[i] & 0xffL);
            }
            return value;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for template selection.", ex);
        }
    }

    private static String key(String templateId, String variantId) {
        return templateId + "\u0000" + variantId;
    }

    public record Template(String buildingSemantic,
                            String style,
                            String templateId,
                            String nbtFile,
                            String contentHash,
                            String variantId,
                            CityTemplatePlacementGeometry.Size rawSize,
                            List<CityTemplatePlacementGeometry.Rotation> allowedRotations,
                            List<CityTemplatePlacementGeometry.Mirror> allowedMirrors,
                            List<CityTemplatePlacementGeometry.RoadEntrance> roadEntrances,
                            String terrainPosePolicy,
                            String supportPolicy,
                            int clearanceBlocks) {
        public Template {
            buildingSemantic = required(buildingSemantic, "buildingSemantic");
            style = required(style, "style");
            templateId = required(templateId, "templateId");
            nbtFile = required(nbtFile, "nbtFile");
            contentHash = required(contentHash, "contentHash");
            variantId = required(variantId, "variantId");
            Objects.requireNonNull(rawSize, "rawSize");
            allowedRotations = List.copyOf(Objects.requireNonNull(allowedRotations, "allowedRotations"));
            allowedMirrors = List.copyOf(Objects.requireNonNull(allowedMirrors, "allowedMirrors"));
            roadEntrances = List.copyOf(Objects.requireNonNull(roadEntrances, "roadEntrances"));
            terrainPosePolicy = CityTemplateTerrainPosePolicy.freezeForTemplate(
                    templateId, nbtFile, required(terrainPosePolicy, "terrainPosePolicy"));
            supportPolicy = required(supportPolicy, "supportPolicy");
            if (allowedRotations.isEmpty() || allowedMirrors.isEmpty()) {
                throw new CatalogException("CITY_TEMPLATE_CATALOG_TRANSFORM_LIST_EMPTY",
                        "allowedRotations and allowedMirrors must not be empty.");
            }
            if (clearanceBlocks < 0) {
                throw new CatalogException("CITY_TEMPLATE_CATALOG_CLEARANCE_INVALID",
                        "clearanceBlocks must be non-negative.");
            }
        }

        public int width() {
            return rawSize.width();
        }

        public int height() {
            return rawSize.height();
        }

        public int depth() {
            return rawSize.depth();
        }

        public String templateRef() {
            return nbtFile;
        }

        public String variant() {
            return variantId;
        }

        public CityTemplatePlacementGeometry geometry(CityTemplatePlacementGeometry.Rotation rotation,
                                                       CityTemplatePlacementGeometry.Mirror mirror) {
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(mirror, "mirror");
            if (!allowedRotations.contains(rotation)) {
                throw new CatalogException("CITY_TEMPLATE_CATALOG_ROTATION_NOT_ALLOWED",
                        "Rotation is not allowed for " + templateId + ": " + rotation);
            }
            if (!allowedMirrors.contains(mirror)) {
                throw new CatalogException("CITY_TEMPLATE_CATALOG_MIRROR_NOT_ALLOWED",
                        "Mirror is not allowed for " + templateId + ": " + mirror);
            }
            return CityTemplatePlacementGeometry.of(rawSize, rotation, mirror, roadEntrances);
        }

        public CityTemplatePlacementGeometry placementGeometry(CityTemplatePlacementGeometry.Rotation rotation,
                                                               CityTemplatePlacementGeometry.Mirror mirror) {
            return geometry(rotation, mirror);
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new CatalogException("CITY_TEMPLATE_CATALOG_FIELD_MISSING", field + " must not be blank.");
            }
            return value.trim();
        }
    }

    public static final class CatalogException extends IllegalArgumentException {
        private final String reasonCode;

        public CatalogException(String reasonCode, String message) {
            super(message);
            this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        }

        public CatalogException(String reasonCode, String message, Throwable cause) {
            super(message, cause);
            this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        }

        public String reasonCode() {
            return reasonCode;
        }
    }
}
