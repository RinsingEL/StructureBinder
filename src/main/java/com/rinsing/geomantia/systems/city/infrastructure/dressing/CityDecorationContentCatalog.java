package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import net.minecraft.nbt.CompoundTag;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CityDecorationContentCatalog {
    public static final String SCHEMA = "city_decoration_content_index.v0.3";
    public static final String LEGACY_SCHEMA = "city_decoration_content_index.v0.2";

    private final Path catalogRoot;
    private final String schemaVersion;
    private final String catalogHash;
    private final Map<String, Content> contents;

    CityDecorationContentCatalog(Path catalogRoot, String schemaVersion, String catalogHash,
                                 Map<String, Content> contents) {
        this.catalogRoot = Objects.requireNonNull(catalogRoot, "catalogRoot");
        this.schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        this.catalogHash = Objects.requireNonNull(catalogHash, "catalogHash");
        this.contents = Collections.unmodifiableMap(new LinkedHashMap<>(contents));
    }

    public Path catalogRoot() {
        return catalogRoot;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public boolean legacySchema() {
        return LEGACY_SCHEMA.equals(schemaVersion);
    }

    public String catalogHash() {
        return catalogHash;
    }

    public Map<String, Content> contents() {
        return contents;
    }

    public Content requireContent(String contentId) {
        Content content = contents.get(contentId);
        if (content == null) {
            throw new CatalogException("CITY_DECORATION_CONTENT_UNKNOWN",
                    "Unknown City decoration contentId: " + contentId);
        }
        return content;
    }

    public static final class Content {
        private final String contentId;
        private final String contentKind;
        private final String nbtFile;
        private final Path nbtPath;
        private final List<Integer> allowedRotations;
        private final String supportMode;
        private final String placementMode;
        private final String replacePolicy;
        private final int groundPlaneLocalY;
        private final int embedDepthBlocks;
        private final String clearanceMode;
        private final int maxFootprintHeightSpreadBlocks;
        private final int comfortMarginBlocks;
        private final List<String> allowedSurfaceTags;
        private final List<String> blockedSurfaceTags;
        private final List<String> tags;
        private final String terrainDropFallbackContentRef;
        private final Size size;
        private final Envelope bodyEnvelope;
        private final Envelope comfortEnvelope;
        private final String contentHash;
        private final CompoundTag template;

        Content(String contentId,
                String contentKind,
                String nbtFile,
                Path nbtPath,
                List<Integer> allowedRotations,
                String supportMode,
                String placementMode,
                String replacePolicy,
                int groundPlaneLocalY,
                int embedDepthBlocks,
                String clearanceMode,
                int maxFootprintHeightSpreadBlocks,
                int comfortMarginBlocks,
                List<String> allowedSurfaceTags,
                List<String> blockedSurfaceTags,
                List<String> tags,
                String terrainDropFallbackContentRef,
                Size size,
                String contentHash,
                CompoundTag template) {
            this.contentId = contentId;
            this.contentKind = contentKind;
            this.nbtFile = nbtFile;
            this.nbtPath = nbtPath;
            this.allowedRotations = List.copyOf(allowedRotations);
            this.supportMode = supportMode;
            this.placementMode = placementMode;
            this.replacePolicy = replacePolicy;
            this.groundPlaneLocalY = groundPlaneLocalY;
            this.embedDepthBlocks = embedDepthBlocks;
            this.clearanceMode = clearanceMode;
            this.maxFootprintHeightSpreadBlocks = maxFootprintHeightSpreadBlocks;
            this.comfortMarginBlocks = comfortMarginBlocks;
            this.allowedSurfaceTags = List.copyOf(allowedSurfaceTags);
            this.blockedSurfaceTags = List.copyOf(blockedSurfaceTags);
            this.tags = List.copyOf(tags);
            this.terrainDropFallbackContentRef = terrainDropFallbackContentRef;
            this.size = size;
            this.bodyEnvelope = new Envelope(0, 0, 0,
                    size.widthBlocks() - 1, size.heightBlocks() - 1, size.depthBlocks() - 1);
            this.comfortEnvelope = new Envelope(-comfortMarginBlocks, 0, -comfortMarginBlocks,
                    size.widthBlocks() - 1 + comfortMarginBlocks,
                    size.heightBlocks() - 1,
                    size.depthBlocks() - 1 + comfortMarginBlocks);
            this.contentHash = contentHash;
            this.template = template.copy();
        }

        public String contentId() {
            return contentId;
        }

        public String contentKind() {
            return contentKind;
        }

        public String nbtFile() {
            return nbtFile;
        }

        public Path nbtPath() {
            return nbtPath;
        }

        public List<Integer> allowedRotations() {
            return allowedRotations;
        }

        public String supportMode() {
            return supportMode;
        }

        public String placementMode() {
            return placementMode;
        }

        public String replacePolicy() {
            return replacePolicy;
        }

        public int groundPlaneLocalY() {
            return groundPlaneLocalY;
        }

        public int embedDepthBlocks() {
            return embedDepthBlocks;
        }

        public String clearanceMode() {
            return clearanceMode;
        }

        public int maxFootprintHeightSpreadBlocks() {
            return maxFootprintHeightSpreadBlocks;
        }

        public int comfortMarginBlocks() {
            return comfortMarginBlocks;
        }

        public List<String> allowedSurfaceTags() {
            return allowedSurfaceTags;
        }

        public List<String> blockedSurfaceTags() {
            return blockedSurfaceTags;
        }

        public List<String> tags() {
            return tags;
        }

        public String terrainDropFallbackContentRef() {
            return terrainDropFallbackContentRef;
        }

        public Size size() {
            return size;
        }

        public Envelope bodyEnvelope() {
            return bodyEnvelope;
        }

        public Envelope comfortEnvelope() {
            return comfortEnvelope;
        }

        public String contentHash() {
            return contentHash;
        }

        public CompoundTag template() {
            return template.copy();
        }
    }

    public record Size(int widthBlocks, int heightBlocks, int depthBlocks) {
        public Size {
            if (widthBlocks <= 0 || heightBlocks <= 0 || depthBlocks <= 0) {
                throw new IllegalArgumentException("City decoration content size must be positive.");
            }
        }
    }

    public record Envelope(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    public static final class CatalogException extends IllegalArgumentException {
        private final String reasonCode;

        public CatalogException(String reasonCode, String message) {
            super(reasonCode + ": " + message);
            this.reasonCode = reasonCode;
        }

        public CatalogException(String reasonCode, String message, Throwable cause) {
            super(reasonCode + ": " + message, cause);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }
}
