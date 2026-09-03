package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CityTemplateContentPackInstallerTest {
    @TempDir
    Path temporary;

    @Test
    void installsIdempotentlyAndRepairsDrift() throws Exception {
        Path bundle = temporary.resolve("bundle");
        Path world = temporary.resolve("world");
        Files.createDirectories(bundle);
        byte[] nbt = "reviewed-nbt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path source = bundle.resolve("city_template_content_pack/geomantia/structures/city/test/house.nbt");
        Files.createDirectories(source.getParent());
        Files.write(source, nbt);
        String catalog = catalog("geomantia:city/test/house");
        Files.writeString(bundle.resolve("template_catalog.json"), catalog);
        Files.writeString(bundle.resolve("city_template_content_pack.json"),
                manifest("geomantia:city/test/house", sha256(nbt), sha256(catalog.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8))));

        CityTemplateContentPackInstaller installer = new CityTemplateContentPackInstaller();
        CityTemplateContentPackInstaller.InstallReport first = installer.install(bundle, world);
        Path target = world.resolve("generated/geomantia/structures/city/test/house.nbt");
        assertEquals(1, first.installedCount());
        assertEquals("reviewed-nbt", Files.readString(target));

        CityTemplateContentPackInstaller.InstallReport second = installer.install(bundle, world);
        assertEquals(0, second.installedCount());
        assertEquals(1, second.unchangedCount());

        Files.writeString(target, "drifted");
        CityTemplateContentPackInstaller.InstallReport repaired = installer.install(bundle, world);
        assertEquals(1, repaired.installedCount());
        assertEquals(1, repaired.repairedCount());
        assertEquals("reviewed-nbt", Files.readString(target));
        assertTrue(Files.isRegularFile(world.resolve(CityTemplateContentPackInstaller.STATE_FILE)));
    }

    @Test
    void rejectsManifestThatDoesNotCoverCatalog() throws Exception {
        Path bundle = temporary.resolve("bundle-mismatch");
        Files.createDirectories(bundle.resolve("city_template_content_pack/geomantia/structures/city/test"));
        byte[] nbt = "nbt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(bundle.resolve("city_template_content_pack/geomantia/structures/city/test/other.nbt"), nbt);
        String catalog = catalog("geomantia:city/test/house");
        Files.writeString(bundle.resolve("template_catalog.json"), catalog);
        Files.writeString(bundle.resolve("city_template_content_pack.json"),
                manifest("geomantia:city/test/other", sha256(nbt), sha256(catalog.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8))));

        Exception failure = assertThrows(Exception.class,
                () -> new CityTemplateContentPackInstaller().install(bundle, temporary.resolve("world")));
        assertTrue(failure.getMessage().startsWith("CITY_TEMPLATE_CONTENT_CATALOG_COVERAGE_MISMATCH"));
    }

    private static String catalog(String ref) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "city_template_catalog");
        JsonObject template = new JsonObject();
        template.addProperty("buildingSemantic", "house");
        template.addProperty("style", "test");
        template.addProperty("templateId", ref);
        template.addProperty("templateRef", ref);
        template.addProperty("contentHash", "sha256:runtime");
        template.addProperty("variant", "v1");
        JsonObject size = new JsonObject();
        size.addProperty("width", 1);
        size.addProperty("height", 1);
        size.addProperty("depth", 1);
        template.add("rawSize", size);
        JsonArray rotations = new JsonArray();
        rotations.add("NONE");
        template.add("allowedRotations", rotations);
        JsonArray mirrors = new JsonArray();
        mirrors.add("NONE");
        template.add("allowedMirrors", mirrors);
        template.add("roadEntrances", new JsonArray());
        template.addProperty("terrainPosePolicy", "structure_start_beard_thin");
        template.addProperty("supportPolicy", "full_footprint_support");
        template.addProperty("clearanceBlocks", 0);
        JsonArray templates = new JsonArray();
        templates.add(template);
        root.add("templates", templates);
        return root.toString();
    }

    private static String manifest(String ref, String sourceHash, String catalogHash) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", CityTemplateContentPackInstaller.SCHEMA);
        root.addProperty("packId", "test_pack");
        root.addProperty("catalogSha256", catalogHash);
        JsonObject template = new JsonObject();
        template.addProperty("templateRef", ref);
        template.addProperty("sourceFile", "geomantia/structures/city/test/"
                + (ref.endsWith("other") ? "other.nbt" : "house.nbt"));
        template.addProperty("sourceSha256", sourceHash);
        JsonArray templates = new JsonArray();
        templates.add(template);
        root.add("templates", templates);
        return root.toString();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
