package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityTemplateCatalogLoader;
import com.rinsing.geomantia.systems.city.application.CityBlueprintReferenceCatalog;
import com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Resolves the installed TerraSense/template bundle without exposing filesystem choices to the model. */
public final class ManagedCityPlanningSources {
    private static final long MAX_JSON_BYTES = 16L * 1024 * 1024;
    private static final String PROFILE_SOURCE = "TerraSenseStructureProfileSource.official.json";
    private static final String BINDER_PROFILE_SOURCE = "TerraSenseStructureProfileSource.binder.json";
    private static final String ENTRANCE_CATALOG = "StructureEntrances.approved.json";
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
        if (Files.isRegularFile(directory.resolve(BINDER_PROFILE_SOURCE)) && Files.isRegularFile(directory.resolve(PROFILE_SOURCE))) {
            throw new IOException("PROVIDER_MANAGED_CITY_PROFILE_SOURCE_AMBIGUOUS: keep exactly one source descriptor in the bundle.");
        }
        JsonObject profile = readObject(directory.resolve(Files.isRegularFile(directory.resolve(BINDER_PROFILE_SOURCE))
                ? BINDER_PROFILE_SOURCE : PROFILE_SOURCE));
        Path profiles = directory.resolve("StructureProfile.jsonl").toAbsolutePath().normalize();
        Path vocabulary = directory.resolve("StructureVocabulary.snapshot.json").toAbsolutePath().normalize();
        if (Files.isRegularFile(profiles)) profile.addProperty("profilePath", profiles.toString());
        if (Files.isRegularFile(vocabulary)) {
            profile.addProperty("vocabularySnapshotPath", vocabulary.toString());
        }
        JsonObject templateSource = new JsonObject();
        if (!Files.isRegularFile(directory.resolve(ENTRANCE_CATALOG))) {
            throw new IOException("PLANNING_ENTRANCE_REVIEW_REQUIRED: missing " + ENTRANCE_CATALOG
                    + "; legacy door positions are not author-approved road ports.");
        }
        profile.addProperty("entranceCatalogPath", directory.resolve(ENTRANCE_CATALOG).toAbsolutePath().normalize().toString());
        JsonObject effectiveTemplates = ApprovedEntranceCatalog.apply(readObject(directory.resolve(TEMPLATE_CATALOG)),
                readObject(directory.resolve(ENTRANCE_CATALOG)));
        templateSource.add("catalog", effectiveTemplates);
        JsonObject references = readObject(directory.resolve(REFERENCE_CATALOG));
        // Validate author-owned semantics and every reference before a model sees this bundle.
        var templates = new CityTemplateCatalogLoader().load(effectiveTemplates);
        var referenceCatalog = CityBlueprintReferenceCatalog.parse(references, templates);
        var authored = CityStructureProfileCatalog.importCatalog(directory, profile);
        JsonObject brief = authoringBrief(authored, referenceCatalog.structureRefs());
        return new ResolvedSources(profile, templateSource, references, directory, brief);
    }

    static JsonObject authoringBrief(CityStructureProfileCatalog.ImportedCatalog authored, java.util.Set<String> refs) {
        JsonArray choices = new JsonArray();
        var byId = authored.byId();
        for (String ref : refs.stream().sorted().toList()) {
            var entry = byId.get(ref);
            if (entry == null || !"approved".equalsIgnoreCase(entry.reviewState())
                    || entry.functionTerms().isEmpty() || entry.styleTerms().isEmpty()) {
                throw new IllegalArgumentException("PLANNING_AUTHOR_ANNOTATION_REQUIRED: " + ref
                        + " requires author-approved functionTerms and styleTerms before planning.");
            }
            choices.add(entry.asSemanticJson());
        }
        JsonObject brief = new JsonObject();
        brief.addProperty("semanticAuthority", "pack_author_approved_annotations");
        brief.addProperty("instruction", "Use these authored functions and styles to compose civilizations. Never infer or relabel a structure's function/style from its name or appearance.");
        brief.add("structures", choices);
        return brief;
    }

    public void bindDesignRequest(JsonObject request) throws IOException {
        for (String key : List.of("terrasenseProfileSource", "templateCatalogSource", "blueprintReferenceCatalog")) {
            if (request.has(key)) {
                throw new IllegalArgumentException("PLANNING_SOURCE_HOST_OWNED: " + key
                        + " is configured by the pack author; submit only runId and citySeedId.");
            }
        }
        resolve().applyTo(request);
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
            List<Path> candidates = stream.filter(Files::isDirectory)
                    .filter(ManagedCityPlanningSources::isComplete)
                    .sorted().toList();
            if (candidates.size() > 1) {
                throw new IOException("PROVIDER_MANAGED_CITY_SOURCES_AMBIGUOUS: pack author must set "
                        + "geomantia.providerPlanningSourceDir; candidates="
                        + candidates.stream().map(path -> path.getFileName().toString()).toList());
            }
            return candidates.isEmpty() ? null : candidates.get(0);
        }
    }

    private static boolean isComplete(Path directory) {
        return (Files.isRegularFile(directory.resolve(PROFILE_SOURCE)) || Files.isRegularFile(directory.resolve(BINDER_PROFILE_SOURCE)))
                && Files.isRegularFile(directory.resolve(TEMPLATE_CATALOG))
                && Files.isRegularFile(directory.resolve(REFERENCE_CATALOG));
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
                                  JsonObject blueprintReferenceCatalog, Path directory, JsonObject authoringBrief) {
        public void applyTo(JsonObject arguments) {
            arguments.add("terrasenseProfileSource", terrasenseProfileSource.deepCopy());
            arguments.add("templateCatalogSource", templateCatalogSource.deepCopy());
            arguments.add("blueprintReferenceCatalog", blueprintReferenceCatalog.deepCopy());
        }
    }
}
