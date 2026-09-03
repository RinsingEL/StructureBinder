package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityTemplateCatalog;
import com.rinsing.geomantia.systems.city.application.CityTemplateCatalogLoader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Installs a reviewed, pre-converted City template content pack into one world. */
public final class CityTemplateContentPackInstaller {
    public static final String SCHEMA = "city_template_content_pack.v0.1";
    public static final String MANIFEST_FILE = "city_template_content_pack.json";
    public static final String PAYLOAD_DIRECTORY = "city_template_content_pack";
    public static final String STATE_FILE = "geomantia_city_template_content_pack.json";
    private static final Pattern RESOURCE_PART = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern RESOURCE_PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public InstallReport install(Path bundleDirectory, Path worldRoot) throws IOException {
        Path bundle = bundleDirectory.toAbsolutePath().normalize();
        Path manifestPath = bundle.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath)) {
            return new InstallReport(false, "", 0, 0, 0, 0, "manifest_not_configured");
        }
        Path catalogPath = bundle.resolve(CityTemplateCatalogLoader.DEFAULT_FILE_NAME);
        if (!Files.isRegularFile(catalogPath)) {
            throw failure("CITY_TEMPLATE_CONTENT_CATALOG_MISSING", catalogPath.toString());
        }
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        byte[] catalogBytes = Files.readAllBytes(catalogPath);
        JsonObject manifest = parseObject(manifestBytes, manifestPath);
        rejectUnknown(manifest, Set.of("schema", "packId", "catalogSha256", "templates"), "manifest");
        requireEqual(SCHEMA, string(manifest, "schema"), "CITY_TEMPLATE_CONTENT_SCHEMA_UNSUPPORTED");
        String packId = string(manifest, "packId");
        String expectedCatalogHash = string(manifest, "catalogSha256");
        String actualCatalogHash = sha256(catalogBytes);
        requireEqual(expectedCatalogHash, actualCatalogHash, "CITY_TEMPLATE_CONTENT_CATALOG_HASH_MISMATCH");

        CityTemplateCatalog catalog = new CityTemplateCatalogLoader().load(new String(catalogBytes,
                java.nio.charset.StandardCharsets.UTF_8));
        Map<String, SourceEntry> entries = parseEntries(manifest);
        Set<String> catalogRefs = new HashSet<>();
        for (CityTemplateCatalog.Template template : catalog.templates()) {
            catalogRefs.add(template.templateRef());
        }
        if (!catalogRefs.equals(entries.keySet())) {
            Set<String> missing = new HashSet<>(catalogRefs);
            missing.removeAll(entries.keySet());
            Set<String> extra = new HashSet<>(entries.keySet());
            extra.removeAll(catalogRefs);
            throw failure("CITY_TEMPLATE_CONTENT_CATALOG_COVERAGE_MISMATCH",
                    "missing=" + sorted(missing) + ", extra=" + sorted(extra));
        }

        Path payloadRoot = bundle.resolve(PAYLOAD_DIRECTORY).normalize();
        List<PreparedCopy> prepared = new ArrayList<>();
        int unchanged = 0;
        int repaired = 0;
        Path normalizedWorld = worldRoot.toAbsolutePath().normalize();
        for (SourceEntry entry : entries.values()) {
            Path source = resolveInside(payloadRoot, entry.sourceFile(),
                    "CITY_TEMPLATE_CONTENT_SOURCE_PATH_INVALID");
            if (!Files.isRegularFile(source)) {
                throw failure("CITY_TEMPLATE_CONTENT_SOURCE_MISSING", entry.templateRef() + ": " + source);
            }
            String sourceHash = sha256(Files.readAllBytes(source));
            requireEqual(entry.sourceSha256(), sourceHash, "CITY_TEMPLATE_CONTENT_SOURCE_HASH_MISMATCH");
            Path target = targetPath(normalizedWorld, entry.templateRef());
            if (Files.isRegularFile(target)) {
                String targetHash = sha256(Files.readAllBytes(target));
                if (sourceHash.equals(targetHash)) {
                    unchanged++;
                    continue;
                }
                repaired++;
            }
            prepared.add(new PreparedCopy(source, target));
        }

        for (PreparedCopy copy : prepared) {
            atomicCopy(copy.source(), copy.target());
        }
        String manifestHash = sha256(manifestBytes);
        writeState(normalizedWorld.resolve(STATE_FILE), packId, manifestHash, actualCatalogHash,
                entries.size(), prepared.size(), unchanged, repaired);
        return new InstallReport(true, packId, entries.size(), prepared.size(), unchanged, repaired, "installed");
    }

    private static Map<String, SourceEntry> parseEntries(JsonObject manifest) throws IOException {
        JsonArray templates = array(manifest, "templates");
        if (templates.isEmpty()) {
            throw failure("CITY_TEMPLATE_CONTENT_EMPTY", "templates must not be empty");
        }
        Map<String, SourceEntry> result = new LinkedHashMap<>();
        for (int index = 0; index < templates.size(); index++) {
            JsonElement element = templates.get(index);
            if (!element.isJsonObject()) {
                throw failure("CITY_TEMPLATE_CONTENT_ENTRY_INVALID", "templates[" + index + "]");
            }
            JsonObject value = element.getAsJsonObject();
            rejectUnknown(value, Set.of("templateRef", "sourceFile", "sourceSha256", "sourceModId",
                    "sourceIdentity", "converterId"), "templates[" + index + "]");
            SourceEntry entry = new SourceEntry(string(value, "templateRef"), string(value, "sourceFile"),
                    string(value, "sourceSha256"));
            validateTemplateRef(entry.templateRef());
            if (result.put(entry.templateRef(), entry) != null) {
                throw failure("CITY_TEMPLATE_CONTENT_DUPLICATE_REF", entry.templateRef());
            }
        }
        return result;
    }

    private static Path targetPath(Path worldRoot, String templateRef) throws IOException {
        int separator = templateRef.indexOf(':');
        String namespace = templateRef.substring(0, separator);
        String path = templateRef.substring(separator + 1);
        Path root = worldRoot.resolve("generated").resolve(namespace).resolve("structures").normalize();
        return resolveInside(root, path + ".nbt", "CITY_TEMPLATE_CONTENT_TARGET_PATH_INVALID");
    }

    private static void validateTemplateRef(String value) throws IOException {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1
                || value.indexOf(':', separator + 1) >= 0
                || !RESOURCE_PART.matcher(value.substring(0, separator)).matches()
                || !RESOURCE_PATH.matcher(value.substring(separator + 1)).matches()
                || value.substring(separator + 1).contains("..")) {
            throw failure("CITY_TEMPLATE_CONTENT_TEMPLATE_REF_INVALID", value);
        }
    }

    private static Path resolveInside(Path root, String relative, String code) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path relativePath;
        try {
            relativePath = Path.of(relative.replace('/', java.io.File.separatorChar));
        } catch (RuntimeException ex) {
            throw failure(code, relative);
        }
        if (relativePath.isAbsolute()) {
            throw failure(code, relative);
        }
        Path resolved = normalizedRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw failure(code, relative);
        }
        return resolved;
    }

    private static JsonObject parseObject(byte[] bytes, Path source) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalStateException("root is not an object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException ex) {
            throw failure("CITY_TEMPLATE_CONTENT_MANIFEST_INVALID", source + ": " + ex.getMessage());
        }
    }

    private static String string(JsonObject source, String key) throws IOException {
        if (!source.has(key) || !source.get(key).isJsonPrimitive()
                || !source.getAsJsonPrimitive(key).isString()
                || source.get(key).getAsString().isBlank()) {
            throw failure("CITY_TEMPLATE_CONTENT_FIELD_MISSING", key);
        }
        return source.get(key).getAsString().trim();
    }

    private static JsonArray array(JsonObject source, String key) throws IOException {
        if (!source.has(key) || !source.get(key).isJsonArray()) {
            throw failure("CITY_TEMPLATE_CONTENT_FIELD_MISSING", key);
        }
        return source.getAsJsonArray(key);
    }

    private static void rejectUnknown(JsonObject source, Set<String> allowed, String owner) throws IOException {
        for (String key : source.keySet()) {
            if (!allowed.contains(key)) {
                throw failure("CITY_TEMPLATE_CONTENT_FIELD_UNKNOWN", owner + "." + key);
            }
        }
    }

    private static void requireEqual(String expected, String actual, String code) throws IOException {
        if (!expected.equals(actual)) {
            throw failure(code, "expected=" + expected + ", actual=" + actual);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static void atomicCopy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".city_template_content_", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeState(Path target, String packId, String manifestHash, String catalogHash,
                                   int templateCount, int installedCount, int unchangedCount,
                                   int repairedCount) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("schema", "city_template_content_pack_install_state.v0.1");
        state.addProperty("packId", packId);
        state.addProperty("manifestSha256", manifestHash);
        state.addProperty("catalogSha256", catalogHash);
        state.addProperty("templateCount", templateCount);
        state.addProperty("installedCount", installedCount);
        state.addProperty("unchangedCount", unchangedCount);
        state.addProperty("repairedCount", repairedCount);
        state.addProperty("installedAt", Instant.now().toString());
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".city_template_content_state_", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(state) + System.lineSeparator());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static IOException failure(String code, String detail) {
        return new IOException(code + ": " + detail);
    }

    private record SourceEntry(String templateRef, String sourceFile, String sourceSha256) {
    }

    private record PreparedCopy(Path source, Path target) {
    }

    public record InstallReport(boolean configured, String packId, int templateCount, int installedCount,
                                int unchangedCount, int repairedCount, String status) {
    }
}
