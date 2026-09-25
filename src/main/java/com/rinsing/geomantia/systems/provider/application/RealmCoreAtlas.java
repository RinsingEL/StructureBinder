package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/** Portable Studio evidence, copied inside the current run for both agent transports. */
final class RealmCoreAtlas {
    private RealmCoreAtlas() { }

    record Evidence(JsonObject brief, List<Path> images) { }

    static Evidence prepare(Path bundle, Path runDirectory, JsonObject fallback) throws IOException {
        Path manifest = bundle.resolve("realm_core_atlas.json");
        if (!Files.exists(manifest)) {
            JsonObject brief = fallback.deepCopy();
            brief.addProperty("visualEvidenceAvailable", false);
            brief.addProperty("visualEvidenceNote", "当前内容包未提供核心建筑图册；只能依据作者文字，不得声称已看过建筑外观。");
            return new Evidence(brief, List.of());
        }
        try {
            return checked(bundle, runDirectory, manifest);
        } catch (RuntimeException failure) {
            throw new IOException("REALM_CORE_ATLAS_INVALID: " + failure.getMessage(), failure);
        }
    }

    private static Evidence checked(Path bundle, Path runDirectory, Path manifest) throws IOException {
        if (Files.size(manifest) > 4L * 1024 * 1024) throw invalid("manifest too large");
        JsonObject atlas = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        if (!"realm_core_atlas.v1".equals(atlas.get("schema").getAsString())) throw invalid("schema");
        JsonObject sources = atlas.getAsJsonObject("sources");
        for (String name : List.of("template_catalog.json", "asset_names.json", "StructureProfile.jsonl")) {
            if (!sha(bundle.resolve(name)).equals(sources.get(name).getAsString())) throw invalid("stale " + name);
        }
        Set<String> expected = new HashSet<>();
        for (String line : Files.readAllLines(bundle.resolve("StructureProfile.jsonl"))) {
            if (line.isBlank()) continue;
            JsonObject profile = JsonParser.parseString(line).getAsJsonObject();
            for (var role : profile.getAsJsonArray("planningRoleTerms")) {
                if (Set.of("planning_role.key", "planning_role.anchor").contains(role.getAsString()))
                    expected.add(profile.get("structureId").getAsString());
            }
        }
        Set<String> actual = new HashSet<>();
        for (var core : atlas.getAsJsonArray("cores")) {
            if (!actual.add(core.getAsJsonObject().get("templateRef").getAsString())) throw invalid("duplicate core");
        }
        if (!actual.equals(expected)) throw invalid("core coverage");
        List<Path> originals = new ArrayList<>();
        Set<String> pictured = new HashSet<>();
        Path bundleReal = bundle.toRealPath();
        int pageNumber = 0;
        for (var element : atlas.getAsJsonArray("pages")) {
            JsonObject page = element.getAsJsonObject();
            if (page.get("page").getAsInt() != ++pageNumber) throw invalid("page order");
            Path relative = Path.of(page.get("file").getAsString());
            if (relative.isAbsolute()) throw invalid("absolute image path");
            Path image = bundle.resolve(relative).normalize().toRealPath();
            if (!image.startsWith(bundleReal) || Files.size(image) > 8L * 1024 * 1024
                    || !sha(image).equals(page.get("sha256").getAsString())) throw invalid("image identity");
            try (var input = ImageIO.createImageInputStream(image.toFile())) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid("unreadable image");
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    if (!"png".equalsIgnoreCase(reader.getFormatName()) || reader.getWidth(0) > 4096
                            || reader.getHeight(0) > 4096) throw invalid("image format/size");
                } finally { reader.dispose(); }
            }
            int slot = 0;
            for (var ref : page.getAsJsonArray("templateRefs")) {
                String id = ref.getAsString();
                if (!pictured.add(id)) throw invalid("duplicate image ref");
                final int number = pageNumber, position = ++slot;
                boolean matches = atlas.getAsJsonArray("cores").asList().stream().anyMatch(c -> {
                    var core = c.getAsJsonObject();
                    return id.equals(core.get("templateRef").getAsString())
                            && number == core.get("page").getAsInt() && position == core.get("slot").getAsInt();
                });
                if (!matches) throw invalid("image label mapping");
            }
            originals.add(image);
        }
        if (!pictured.equals(actual)) throw invalid("image coverage");
        Path target = runDirectory.resolve("core_atlas").resolve(sha(manifest));
        Files.createDirectories(target);
        // Validate the entire package before publishing any current-run evidence.
        List<Path> images = new ArrayList<>();
        for (int i = 0; i < originals.size(); i++) {
            Path destination = target.resolve(String.format("page-%03d.png", i + 1));
            Files.copy(originals.get(i), destination, StandardCopyOption.REPLACE_EXISTING);
            images.add(destination);
        }
        JsonObject brief = new JsonObject();
        brief.addProperty("visualEvidenceAvailable", true);
        brief.addProperty("semanticAuthority", "pack_author_approved_annotations");
        brief.addProperty("imageGuide", "地形图之后依次为核心图册第1页起；每页从左到右、从上到下对应slot。图中ID与cores对应；按图片判断外观，按作者functionTerms判断功能。尺寸为真实方块尺寸，缩略图缩放比例不统一。");
        brief.add("cores", atlas.get("cores").deepCopy());
        brief.add("supportSummary", atlas.get("supportSummary").deepCopy());
        return new Evidence(brief, List.copyOf(images));
    }

    private static IOException invalid(String reason) { return new IOException("REALM_CORE_ATLAS_INVALID: " + reason); }

    private static String sha(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
