package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** Resolves the installed TerraSense/template bundle without exposing filesystem choices to the model. */
public final class ManagedCityPlanningSources {
    private static final long MAX_JSON_BYTES = 16L * 1024 * 1024;
    private static final String PROFILE_SOURCE = "TerraSenseStructureProfileSource.official.json";
    private static final String TEMPLATE_CATALOG = "template_catalog.json";
    private static final String REFERENCE_CATALOG = "blueprint_reference_catalog.json";
    private final Path serverDirectory;

    public ManagedCityPlanningSources(Path serverDirectory) {
        this.serverDirectory = serverDirectory.toAbsolutePath().normalize();
    }

    public ResolvedSources resolve() throws IOException {
        Path directory = configuredDirectory().orElse(null);
        if (directory == null) directory = discoverDirectory();
        if (directory == null) throw new IOException("PROVIDER_MANAGED_CITY_SOURCES_NOT_FOUND");
        JsonObject profile = readObject(directory.resolve(PROFILE_SOURCE));
        Path profiles = directory.resolve("StructureProfile.jsonl").toAbsolutePath().normalize();
        Path vocabulary = directory.resolve("StructureVocabulary.snapshot.json").toAbsolutePath().normalize();
        if (Files.isRegularFile(profiles)) profile.addProperty("profilePath", profiles.toString());
        if (Files.isRegularFile(vocabulary)) {
            profile.addProperty("vocabularySnapshotPath", vocabulary.toString());
        }
        JsonObject templateSource = new JsonObject();
        templateSource.addProperty("catalogPath", directory.resolve(TEMPLATE_CATALOG)
                .toAbsolutePath().normalize().toString());
        JsonObject references = readObject(directory.resolve(REFERENCE_CATALOG));
        return new ResolvedSources(profile, templateSource, references, directory);
    }

    private Optional<Path> configuredDirectory() {
        String configured = System.getProperty("geomantia.providerPlanningSourceDir", "").trim();
        if (configured.isBlank()) return Optional.empty();
        Path value = Path.of(configured);
        if (!value.isAbsolute()) value = serverDirectory.resolve(value);
        return Optional.of(value.toAbsolutePath().normalize());
    }

    private Path discoverDirectory() throws IOException {
        Path root = serverDirectory.resolve("config").resolve("structureTemplate").resolve("terrasense");
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .filter(ManagedCityPlanningSources::isComplete)
                    .max(Comparator.comparing(ManagedCityPlanningSources::lastModified))
                    .orElse(null);
        }
    }

    private static boolean isComplete(Path directory) {
        return Files.isRegularFile(directory.resolve(PROFILE_SOURCE))
                && Files.isRegularFile(directory.resolve(TEMPLATE_CATALOG))
                && Files.isRegularFile(directory.resolve(REFERENCE_CATALOG));
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static JsonObject readObject(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_JSON_BYTES) {
            throw new IOException("PROVIDER_MANAGED_CITY_SOURCE_INVALID: " + path.getFileName());
        }
        JsonElement value = JsonParser.parseString(Files.readString(path));
        if (!value.isJsonObject()) {
            throw new IOException("PROVIDER_MANAGED_CITY_SOURCE_INVALID: " + path.getFileName());
        }
        return value.getAsJsonObject();
    }

    public record ResolvedSources(JsonObject terrasenseProfileSource, JsonObject templateCatalogSource,
                                  JsonObject blueprintReferenceCatalog, Path directory) {
        public void applyTo(JsonObject arguments) {
            arguments.add("terrasenseProfileSource", terrasenseProfileSource.deepCopy());
            arguments.add("templateCatalogSource", templateCatalogSource.deepCopy());
            arguments.add("blueprintReferenceCatalog", blueprintReferenceCatalog.deepCopy());
        }
    }
}
