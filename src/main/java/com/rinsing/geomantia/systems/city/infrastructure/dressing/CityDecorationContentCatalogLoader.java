package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog.CatalogException;

public final class CityDecorationContentCatalogLoader {
    private static final String INDEX_FILE = "content_index.json";
    private static final String TEMPLATES_DIR = "templates";
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of("schemaVersion", "contents");
    private static final Set<String> LEGACY_CONTENT_FIELDS = Set.of(
            "contentId", "contentKind", "nbtFile", "allowedRotations", "supportMode", "placementMode", "replacePolicy",
            "maxFootprintHeightSpreadBlocks", "comfortMarginBlocks", "allowedSurfaceTags",
            "blockedSurfaceTags", "tags", "terrainDropFallbackContentRef");
    private static final Set<String> CONTENT_FIELDS = Set.of(
            "contentId", "contentKind", "nbtFile", "allowedRotations", "supportMode", "placementMode", "replacePolicy",
            "groundPlaneLocalY", "embedDepthBlocks", "clearanceMode",
            "maxFootprintHeightSpreadBlocks", "comfortMarginBlocks", "allowedSurfaceTags",
            "blockedSurfaceTags", "tags", "terrainDropFallbackContentRef");
    private static final Set<String> FORBIDDEN_DERIVED_FIELDS = Set.of(
            "size", "widthBlocks", "heightBlocks", "depthBlocks", "bodyEnvelope", "comfortEnvelope");
    private static final List<Integer> DEFAULT_ROTATIONS = List.of(0);
    private static final List<String> DEFAULT_BLOCKED_SURFACE_TAGS = List.of("water", "lava");

    public CityDecorationContentCatalog load(Path catalogRoot) {
        if (catalogRoot == null) {
            throw fail("CITY_DECORATION_CATALOG_ROOT_MISSING", "catalogRoot is required.");
        }
        Path root = realDirectory(catalogRoot);
        JsonObject index = readIndex(root.resolve(INDEX_FILE));
        rejectUnknownFields(index, TOP_LEVEL_FIELDS, "content index");
        String schemaVersion = requiredString(index, "schemaVersion");
        boolean legacySchema = CityDecorationContentCatalog.LEGACY_SCHEMA.equals(schemaVersion);
        if (!CityDecorationContentCatalog.SCHEMA.equals(schemaVersion) && !legacySchema) {
            throw fail("CITY_DECORATION_CONTENT_INDEX_SCHEMA_UNSUPPORTED",
                    "Expected " + CityDecorationContentCatalog.SCHEMA + " but found " + schemaVersion + ".");
        }
        JsonArray entries = requiredArray(index, "contents");
        if (entries.isEmpty()) {
            throw fail("CITY_DECORATION_CONTENT_INDEX_EMPTY", "contents must contain at least one prefab.");
        }

        Path templatesRoot = root.resolve(TEMPLATES_DIR).normalize();
        Map<String, CityDecorationContentCatalog.Content> contents = new LinkedHashMap<>();
        for (int indexPosition = 0; indexPosition < entries.size(); indexPosition++) {
            JsonElement element = entries.get(indexPosition);
            if (!element.isJsonObject()) {
                throw fail("CITY_DECORATION_CONTENT_ENTRY_INVALID",
                        "contents[" + indexPosition + "] must be an object.");
            }
            ParsedContent parsed = parseContent(element.getAsJsonObject(), root, templatesRoot, indexPosition,
                    schemaVersion, legacySchema);
            if (contents.containsKey(parsed.contentId())) {
                throw fail("CITY_DECORATION_CONTENT_ID_DUPLICATE",
                        "Duplicate contentId: " + parsed.contentId());
            }
            contents.put(parsed.contentId(), parsed.asContent());
        }
        validateTerrainDropFallbacks(contents);
        return new CityDecorationContentCatalog(root, schemaVersion, catalogHash(schemaVersion, contents), contents);
    }

    private ParsedContent parseContent(JsonObject entry, Path root, Path templatesRoot, int indexPosition,
                                       String schemaVersion, boolean legacySchema) {
        rejectDerivedFields(entry, indexPosition);
        rejectUnknownFields(entry, legacySchema ? LEGACY_CONTENT_FIELDS : CONTENT_FIELDS,
                "contents[" + indexPosition + "]");
        String contentId = requiredString(entry, "contentId");
        ResourceLocation parsedId = ResourceLocation.tryParse(contentId);
        if (parsedId == null || !parsedId.toString().equals(contentId)) {
            throw fail("CITY_DECORATION_CONTENT_ID_INVALID", "Invalid contentId: " + contentId);
        }
        String contentKind = requiredString(entry, "contentKind");
        if (!"prefab".equals(contentKind)) {
            throw fail("CITY_DECORATION_CONTENT_KIND_UNSUPPORTED",
                    "Only contentKind=prefab is supported in v0.2: " + contentId);
        }

        String nbtFile = requiredString(entry, "nbtFile");
        Path nbtPath = resolveNbtPath(root, templatesRoot, nbtFile, contentId);
        List<Integer> allowedRotations = rotations(entry, contentId);
        String supportMode = optionalString(entry, "supportMode", "full_footprint");
        if (!"full_footprint".equals(supportMode)) {
            throw fail("CITY_DECORATION_SUPPORT_MODE_UNSUPPORTED",
                    "Only supportMode=full_footprint is supported in v0.2: " + contentId);
        }
        String placementMode = optionalString(entry, "placementMode", "above_surface");
        if (!"above_surface".equals(placementMode) && !"replace_surface".equals(placementMode)
                && (legacySchema || !"embed_surface".equals(placementMode))) {
            throw fail("CITY_DECORATION_PLACEMENT_MODE_UNSUPPORTED",
                    "placementMode must be above_surface, replace_surface or embed_surface: " + contentId);
        }
        String replacePolicy = optionalString(entry, "replacePolicy", "replaceable_only");
        boolean validReplacePair = "above_surface".equals(placementMode) && "replaceable_only".equals(replacePolicy)
                || ("replace_surface".equals(placementMode) || "embed_surface".equals(placementMode))
                && "surface_replaceable".equals(replacePolicy);
        if (!validReplacePair) {
            throw fail("CITY_DECORATION_PLACEMENT_REPLACE_POLICY_MISMATCH",
                    "placementMode=" + placementMode + " cannot use replacePolicy=" + replacePolicy + ": " + contentId);
        }
        int maxHeightSpread = optionalNonNegativeInt(entry, "maxFootprintHeightSpreadBlocks", 1, contentId);
        int comfortMargin = optionalNonNegativeInt(entry, "comfortMarginBlocks", 1, contentId);
        List<String> allowedSurfaceTags = strings(entry, "allowedSurfaceTags", List.of(), contentId);
        List<String> blockedSurfaceTags = strings(entry, "blockedSurfaceTags",
                DEFAULT_BLOCKED_SURFACE_TAGS, contentId);
        List<String> tags = strings(entry, "tags", List.of(), contentId);
        String terrainDropFallbackContentRef = optionalNullableString(entry, "terrainDropFallbackContentRef");

        CompoundTag template = readTemplate(nbtPath, contentId);
        CityDecorationContentCatalog.Size size = validateTemplate(template, contentId);
        int groundPlaneLocalY = legacySchema ? 0 : requiredNonNegativeInt(entry, "groundPlaneLocalY", contentId);
        int embedDepthBlocks = legacySchema ? 0 : requiredNonNegativeInt(entry, "embedDepthBlocks", contentId);
        String clearanceMode = legacySchema ? "preserve" : requiredString(entry, "clearanceMode");
        if (groundPlaneLocalY >= size.heightBlocks()) {
            throw fail("CITY_DECORATION_GROUND_PLANE_INVALID",
                    "groundPlaneLocalY must be inside the template height: " + contentId);
        }
        if (!"preserve".equals(clearanceMode) && !"clear_template_air".equals(clearanceMode)) {
            throw fail("CITY_DECORATION_CLEARANCE_MODE_UNSUPPORTED",
                    "clearanceMode must be preserve or clear_template_air: " + contentId);
        }
        if ("embed_surface".equals(placementMode) ? embedDepthBlocks <= 0 : embedDepthBlocks != 0) {
            throw fail("CITY_DECORATION_EMBED_DEPTH_INVALID",
                    "embed_surface requires positive embedDepthBlocks and other modes require zero: " + contentId);
        }
        String normalizedNbtFile = Path.of(nbtFile).normalize().toString().replace('\\', '/');
        String contentHash = contentHash(schemaVersion, contentId, contentKind, normalizedNbtFile, allowedRotations,
                supportMode, placementMode, replacePolicy, groundPlaneLocalY, embedDepthBlocks, clearanceMode,
                maxHeightSpread, comfortMargin, allowedSurfaceTags, blockedSurfaceTags, tags,
                terrainDropFallbackContentRef, template);
        return new ParsedContent(contentId, contentKind, normalizedNbtFile, nbtPath, allowedRotations,
                supportMode, placementMode, replacePolicy, groundPlaneLocalY, embedDepthBlocks, clearanceMode,
                maxHeightSpread, comfortMargin, allowedSurfaceTags, blockedSurfaceTags, tags,
                terrainDropFallbackContentRef, size, contentHash, template);
    }

    private static void validateTerrainDropFallbacks(Map<String, CityDecorationContentCatalog.Content> contents) {
        for (CityDecorationContentCatalog.Content content : contents.values()) {
            String fallbackRef = content.terrainDropFallbackContentRef();
            if (fallbackRef == null) {
                continue;
            }
            CityDecorationContentCatalog.Content fallback = contents.get(fallbackRef);
            if (fallback == null) {
                throw fail("CITY_DECORATION_TERRAIN_FALLBACK_UNKNOWN",
                        "terrainDropFallbackContentRef is not present in the catalog: " + fallbackRef);
            }
            if (content.size().widthBlocks() != 1 || content.size().depthBlocks() != 1
                    || fallback.size().widthBlocks() != 1 || fallback.size().depthBlocks() != 1) {
                throw fail("CITY_DECORATION_TERRAIN_FALLBACK_TILE_REQUIRED",
                        "terrainDropFallbackContentRef requires one-by-one source and fallback tiles: "
                                + content.contentId());
            }
            if (!content.placementMode().equals(fallback.placementMode())
                    || !content.replacePolicy().equals(fallback.replacePolicy())) {
                throw fail("CITY_DECORATION_TERRAIN_FALLBACK_PLACEMENT_MISMATCH",
                        "terrainDropFallbackContentRef must use the same placement mode and replace policy: "
                                + content.contentId());
            }
        }
    }

    private static Path realDirectory(Path catalogRoot) {
        try {
            Path root = catalogRoot.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(root)) {
                throw fail("CITY_DECORATION_CATALOG_ROOT_INVALID", "Catalog root is not a directory: " + root);
            }
            return root;
        } catch (IOException ex) {
            throw fail("CITY_DECORATION_CATALOG_ROOT_INVALID",
                    "Catalog root is unavailable: " + catalogRoot, ex);
        }
    }

    private static JsonObject readIndex(Path indexPath) {
        if (!Files.isRegularFile(indexPath)) {
            throw fail("CITY_DECORATION_CONTENT_INDEX_MISSING", "Missing content index: " + indexPath);
        }
        try (var reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw fail("CITY_DECORATION_CONTENT_INDEX_INVALID", "Content index root must be an object.");
            }
            return parsed.getAsJsonObject();
        } catch (CatalogException ex) {
            throw ex;
        } catch (Exception ex) {
            throw fail("CITY_DECORATION_CONTENT_INDEX_INVALID", "Cannot parse content index: " + indexPath, ex);
        }
    }

    private static Path resolveNbtPath(Path root, Path templatesRoot, String nbtFile, String contentId) {
        final Path relative;
        try {
            relative = Path.of(nbtFile);
        } catch (InvalidPathException ex) {
            throw fail("CITY_DECORATION_NBT_PATH_INVALID", "Invalid nbtFile for " + contentId + ": " + nbtFile, ex);
        }
        if (relative.isAbsolute() || relative.getNameCount() < 2
                || !TEMPLATES_DIR.equals(relative.getName(0).toString())
                || !nbtFile.toLowerCase(java.util.Locale.ROOT).endsWith(".nbt")) {
            throw fail("CITY_DECORATION_NBT_PATH_INVALID",
                    "nbtFile must be a relative templates/*.nbt path for " + contentId + ": " + nbtFile);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(templatesRoot)) {
            throw fail("CITY_DECORATION_NBT_PATH_ESCAPE",
                    "nbtFile escapes the templates directory for " + contentId + ": " + nbtFile);
        }
        if (!Files.isRegularFile(resolved)) {
            throw fail("CITY_DECORATION_NBT_MISSING", "Missing prefab NBT for " + contentId + ": " + resolved);
        }
        try {
            Path realTemplates = templatesRoot.toRealPath();
            Path realNbt = resolved.toRealPath();
            if (!realTemplates.startsWith(root) || !realNbt.startsWith(root) || !realNbt.startsWith(realTemplates)) {
                throw fail("CITY_DECORATION_NBT_PATH_ESCAPE",
                        "nbtFile resolves outside the templates directory for " + contentId + ": " + nbtFile);
            }
            return realNbt;
        } catch (CatalogException ex) {
            throw ex;
        } catch (IOException ex) {
            throw fail("CITY_DECORATION_NBT_READ_FAILED", "Cannot resolve prefab NBT for " + contentId, ex);
        }
    }

    private static CompoundTag readTemplate(Path nbtPath, String contentId) {
        try {
            return NbtIo.readCompressed(nbtPath.toFile());
        } catch (Exception ex) {
            throw fail("CITY_DECORATION_NBT_READ_FAILED",
                    "Cannot read compressed prefab NBT for " + contentId + ": " + nbtPath, ex);
        }
    }

    private static CityDecorationContentCatalog.Size validateTemplate(CompoundTag root, String contentId) {
        ListTag size = requiredList(root, "size", Tag.TAG_INT, contentId);
        if (size.size() != 3) {
            throw fail("CITY_DECORATION_NBT_SIZE_INVALID", "size must contain exactly three integers: " + contentId);
        }
        int width = size.getInt(0);
        int height = size.getInt(1);
        int depth = size.getInt(2);
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw fail("CITY_DECORATION_NBT_SIZE_INVALID", "size values must be positive: " + contentId);
        }

        ListTag palette = requiredList(root, "palette", Tag.TAG_COMPOUND, contentId);
        if (palette.isEmpty()) {
            throw fail("CITY_DECORATION_NBT_PALETTE_INVALID", "palette must not be empty: " + contentId);
        }
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag state = palette.getCompound(i);
            if (!state.contains("Name", Tag.TAG_STRING) || state.getString("Name").isBlank()
                    || ResourceLocation.tryParse(state.getString("Name")) == null) {
                throw fail("CITY_DECORATION_NBT_PALETTE_INVALID",
                        "palette[" + i + "] has an invalid Name: " + contentId);
            }
            if (state.contains("Properties")) {
                Tag propertiesTag = state.get("Properties");
                if (!(propertiesTag instanceof CompoundTag properties)
                        || properties.getAllKeys().stream().anyMatch(key -> !properties.contains(key, Tag.TAG_STRING))) {
                    throw fail("CITY_DECORATION_NBT_PALETTE_INVALID",
                            "palette[" + i + "].Properties must contain string values: " + contentId);
                }
            }
        }

        ListTag blocks = requiredList(root, "blocks", Tag.TAG_COMPOUND, contentId);
        if (blocks.isEmpty()) {
            throw fail("CITY_DECORATION_NBT_BLOCKS_INVALID", "blocks must not be empty: " + contentId);
        }
        Set<String> occupied = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag pos = requiredList(block, "pos", Tag.TAG_INT, contentId + " blocks[" + i + "]");
            if (pos.size() != 3 || !block.contains("state", Tag.TAG_INT)) {
                throw fail("CITY_DECORATION_NBT_BLOCKS_INVALID",
                        "blocks[" + i + "] requires pos[3] and integer state: " + contentId);
            }
            int x = pos.getInt(0);
            int y = pos.getInt(1);
            int z = pos.getInt(2);
            int stateIndex = block.getInt("state");
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
                throw fail("CITY_DECORATION_NBT_BLOCK_OUT_OF_BOUNDS",
                        "blocks[" + i + "] is outside size: " + contentId);
            }
            if (stateIndex < 0 || stateIndex >= palette.size()) {
                throw fail("CITY_DECORATION_NBT_STATE_INVALID",
                        "blocks[" + i + "] references missing palette state: " + contentId);
            }
            if (!occupied.add(x + "," + y + "," + z)) {
                throw fail("CITY_DECORATION_NBT_BLOCK_DUPLICATE",
                        "Duplicate block position in prefab NBT: " + contentId + " at " + x + "," + y + "," + z);
            }
        }

        if (root.contains("entities")) {
            Tag entitiesTag = root.get("entities");
            if (!(entitiesTag instanceof ListTag entities) || !entities.isEmpty()) {
                throw fail("CITY_DECORATION_PREFAB_ENTITY_NBT_FORBIDDEN",
                        "Prefab entities must be absent or empty: " + contentId);
            }
        }
        return new CityDecorationContentCatalog.Size(width, height, depth);
    }

    private static ListTag requiredList(CompoundTag root, String key, byte elementType, String owner) {
        Tag tag = root.get(key);
        if (!(tag instanceof ListTag list) || list.getElementType() != elementType) {
            throw fail("CITY_DECORATION_NBT_STRUCTURE_INVALID",
                    owner + " requires " + key + " list with element type " + elementType + ".");
        }
        return list;
    }

    private static List<Integer> rotations(JsonObject entry, String contentId) {
        if (!entry.has("allowedRotations")) {
            return DEFAULT_ROTATIONS;
        }
        JsonArray array = requiredArray(entry, "allowedRotations");
        if (array.isEmpty()) {
            throw fail("CITY_DECORATION_ROTATIONS_INVALID", "allowedRotations must not be empty: " + contentId);
        }
        Set<Integer> unique = new HashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw fail("CITY_DECORATION_ROTATIONS_INVALID",
                        "allowedRotations must contain integer degrees: " + contentId);
            }
            final int rotation;
            try {
                rotation = exactInt(element);
            } catch (RuntimeException ex) {
                throw fail("CITY_DECORATION_ROTATIONS_INVALID", "Invalid rotation: " + contentId, ex);
            }
            if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
                throw fail("CITY_DECORATION_ROTATIONS_INVALID",
                        "Rotation must be one of 0, 90, 180, 270: " + contentId);
            }
            if (!unique.add(rotation)) {
                throw fail("CITY_DECORATION_ROTATIONS_INVALID", "Duplicate rotation " + rotation + ": " + contentId);
            }
        }
        return unique.stream().sorted().toList();
    }

    private static List<String> strings(JsonObject entry, String key, List<String> fallback, String contentId) {
        if (!entry.has(key)) {
            return fallback.stream().sorted().toList();
        }
        JsonArray array = requiredArray(entry, key);
        Set<String> unique = new HashSet<>();
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw fail("CITY_DECORATION_CONTENT_FIELD_INVALID",
                        key + " must contain strings: " + contentId);
            }
            String value = element.getAsString().trim();
            if (value.isEmpty() || !unique.add(value)) {
                throw fail("CITY_DECORATION_CONTENT_FIELD_INVALID",
                        key + " contains a blank or duplicate value: " + contentId);
            }
            values.add(value);
        }
        values.sort(Comparator.naturalOrder());
        return List.copyOf(values);
    }

    private static int optionalNonNegativeInt(JsonObject entry, String key, int fallback, String contentId) {
        if (!entry.has(key)) {
            return fallback;
        }
        try {
            int value = exactInt(entry.get(key));
            if (value < 0) {
                throw fail("CITY_DECORATION_CONTENT_FIELD_INVALID", key + " must be non-negative: " + contentId);
            }
            return value;
        } catch (CatalogException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw fail("CITY_DECORATION_CONTENT_FIELD_INVALID", key + " must be an integer: " + contentId, ex);
        }
    }

    private static String contentHash(String schemaVersion,
                                      String contentId,
                                      String contentKind,
                                      String nbtFile,
                                      List<Integer> rotations,
                                      String supportMode,
                                      String placementMode,
                                      String replacePolicy,
                                      int groundPlaneLocalY,
                                      int embedDepthBlocks,
                                      String clearanceMode,
                                      int maxHeightSpread,
                                      int comfortMargin,
                                      List<String> allowedSurfaceTags,
                                      List<String> blockedSurfaceTags,
                                      List<String> tags,
                                      String terrainDropFallbackContentRef,
                                      CompoundTag template) {
        return hash(out -> {
            writeString(out, schemaVersion);
            writeString(out, contentId);
            writeString(out, contentKind);
            writeString(out, nbtFile);
            writeInts(out, rotations);
            writeString(out, supportMode);
            writeString(out, placementMode);
            writeString(out, replacePolicy);
            if (CityDecorationContentCatalog.SCHEMA.equals(schemaVersion)) {
                out.writeInt(groundPlaneLocalY);
                out.writeInt(embedDepthBlocks);
                writeString(out, clearanceMode);
            }
            out.writeInt(maxHeightSpread);
            out.writeInt(comfortMargin);
            writeStrings(out, allowedSurfaceTags);
            writeStrings(out, blockedSurfaceTags);
            writeStrings(out, tags);
            writeNullableString(out, terrainDropFallbackContentRef);
            writeTag(out, template);
        });
    }

    private static int exactInt(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new NumberFormatException("Expected an integer JSON number.");
        }
        return element.getAsBigDecimal().intValueExact();
    }

    private static String catalogHash(String schemaVersion,
                                      Map<String, CityDecorationContentCatalog.Content> contents) {
        return hash(out -> {
            writeString(out, schemaVersion);
            List<CityDecorationContentCatalog.Content> sorted = contents.values().stream()
                    .sorted(Comparator.comparing(CityDecorationContentCatalog.Content::contentId))
                    .toList();
            out.writeInt(sorted.size());
            for (CityDecorationContentCatalog.Content content : sorted) {
                writeString(out, content.contentId());
                writeString(out, content.contentHash());
            }
        });
    }

    private static String hash(HashWriter writer) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream out = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                writer.write(out);
            }
            return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot compute City decoration catalog hash.", ex);
        }
    }

    private static void writeTag(DataOutputStream out, Tag tag) throws IOException {
        out.writeByte(tag.getId());
        switch (tag.getId()) {
            case Tag.TAG_END -> {
                if (!(tag instanceof EndTag)) {
                    throw new IOException("Invalid END tag implementation.");
                }
            }
            case Tag.TAG_BYTE -> out.writeByte(((ByteTag) tag).getAsByte());
            case Tag.TAG_SHORT -> out.writeShort(((ShortTag) tag).getAsShort());
            case Tag.TAG_INT -> out.writeInt(((IntTag) tag).getAsInt());
            case Tag.TAG_LONG -> out.writeLong(((LongTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> out.writeInt(Float.floatToIntBits(((FloatTag) tag).getAsFloat()));
            case Tag.TAG_DOUBLE -> out.writeLong(Double.doubleToLongBits(((DoubleTag) tag).getAsDouble()));
            case Tag.TAG_BYTE_ARRAY -> {
                byte[] values = ((ByteArrayTag) tag).getAsByteArray();
                out.writeInt(values.length);
                out.write(values);
            }
            case Tag.TAG_STRING -> writeString(out, ((StringTag) tag).getAsString());
            case Tag.TAG_LIST -> {
                ListTag list = (ListTag) tag;
                out.writeInt(list.size());
                for (Tag element : list) {
                    writeTag(out, element);
                }
            }
            case Tag.TAG_COMPOUND -> {
                CompoundTag compound = (CompoundTag) tag;
                List<String> keys = compound.getAllKeys().stream().sorted().toList();
                out.writeInt(keys.size());
                for (String key : keys) {
                    writeString(out, key);
                    writeTag(out, java.util.Objects.requireNonNull(compound.get(key)));
                }
            }
            case Tag.TAG_INT_ARRAY -> {
                int[] values = ((IntArrayTag) tag).getAsIntArray();
                out.writeInt(values.length);
                for (int value : values) {
                    out.writeInt(value);
                }
            }
            case Tag.TAG_LONG_ARRAY -> {
                long[] values = ((LongArrayTag) tag).getAsLongArray();
                out.writeInt(values.length);
                for (long value : values) {
                    out.writeLong(value);
                }
            }
            default -> throw new IOException("Unsupported NBT tag type: " + tag.getId());
        }
    }

    private static void writeInts(DataOutputStream out, List<Integer> values) throws IOException {
        out.writeInt(values.size());
        for (int value : values) {
            out.writeInt(value);
        }
    }

    private static void writeStrings(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) {
            writeString(out, value);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeNullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            writeString(out, value);
        }
    }

    private static void rejectUnknownFields(JsonObject object, Set<String> allowed, String owner) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw fail("CITY_DECORATION_CONTENT_FIELD_UNKNOWN", "Unknown field " + owner + "." + key);
            }
        }
    }

    private static void rejectDerivedFields(JsonObject entry, int indexPosition) {
        for (String key : FORBIDDEN_DERIVED_FIELDS) {
            if (entry.has(key)) {
                throw fail("CITY_DECORATION_CONTENT_DERIVED_FIELD_FORBIDDEN",
                        "contents[" + indexPosition + "]." + key + " must be derived from NBT.");
            }
        }
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString()
                || object.get(key).getAsString().isBlank()) {
            throw fail("CITY_DECORATION_CONTENT_FIELD_MISSING", "Required string field is missing: " + key);
        }
        return object.get(key).getAsString().trim();
    }

    private static int requiredNonNegativeInt(JsonObject object, String key, String contentId) {
        if (!object.has(key)) {
            throw fail("CITY_DECORATION_CONTENT_FIELD_MISSING", "Required integer field is missing: " + key);
        }
        try {
            int value = exactInt(object.get(key));
            if (value < 0) {
                throw fail("CITY_DECORATION_CONTENT_FIELD_INVALID",
                        key + " must be non-negative: " + contentId);
            }
            return value;
        } catch (CatalogException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw fail("CITY_DECORATION_CONTENT_FIELD_INVALID",
                    key + " must be an integer: " + contentId, ex);
        }
    }

    private static String optionalString(JsonObject object, String key, String fallback) {
        return object.has(key) ? requiredString(object, key) : fallback;
    }

    private static String optionalNullableString(JsonObject object, String key) {
        return object.has(key) ? requiredString(object, key) : null;
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw fail("CITY_DECORATION_CONTENT_FIELD_MISSING", "Required array field is missing: " + key);
        }
        return object.getAsJsonArray(key);
    }

    private static CatalogException fail(String reasonCode, String message) {
        return new CatalogException(reasonCode, message);
    }

    private static CatalogException fail(String reasonCode, String message, Throwable cause) {
        return new CatalogException(reasonCode, message, cause);
    }

    @FunctionalInterface
    private interface HashWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private record ParsedContent(String contentId,
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
                                 CityDecorationContentCatalog.Size size,
                                 String contentHash,
                                 CompoundTag template) {
        CityDecorationContentCatalog.Content asContent() {
            return new CityDecorationContentCatalog.Content(contentId, contentKind, nbtFile, nbtPath,
                    allowedRotations, supportMode, placementMode, replacePolicy, groundPlaneLocalY,
                    embedDepthBlocks, clearanceMode, maxFootprintHeightSpreadBlocks, comfortMarginBlocks,
                    allowedSurfaceTags, blockedSurfaceTags, tags, terrainDropFallbackContentRef, size,
                    contentHash, template);
        }
    }
}
