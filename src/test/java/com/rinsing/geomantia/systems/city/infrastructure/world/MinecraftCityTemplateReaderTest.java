package com.rinsing.geomantia.systems.city.infrastructure.world;

import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftCityTemplateReaderTest {
    @Test
    void missingTemplateIsAStableStructuredFailure() {
        FakeSource source = new FakeSource();
        MinecraftCityTemplateReader.ReadResult result = new MinecraftCityTemplateReader(source)
                .read("city:missing_house");

        assertFalse(result.success());
        assertEquals(MinecraftCityTemplateReader.FailureCode.TEMPLATE_NOT_FOUND, result.failureCode());
        assertEquals("city:missing_house", result.templateRef());
        assertEquals(1, source.calls);
    }

    @Test
    void nonPositiveTemplateDimensionsAreRejectedBeforePlanning() {
        FakeSource source = new FakeSource();
        source.put("city:invalid", new Vec3i(10, 0, 4), "sha256:invalid", "fake-nbt");

        MinecraftCityTemplateReader.ReadResult result = new MinecraftCityTemplateReader(source)
                .read("city:invalid");

        assertFalse(result.success());
        assertEquals(MinecraftCityTemplateReader.FailureCode.TEMPLATE_SIZE_NON_POSITIVE,
                result.failureCode());
        assertEquals(new Vec3i(10, 0, 4), result.size());
        assertEquals("sha256:invalid", result.contentHash());
        assertEquals("fake-nbt", result.sourceId());
    }

    @Test
    void nonSquareTemplateExposesTheRealThreeAxisSize() {
        FakeSource source = new FakeSource();
        source.put("city:market", new Vec3i(10, 7, 23), "sha256:market", "fake-nbt");

        MinecraftCityTemplateReader.ReadResult result = new MinecraftCityTemplateReader(source)
                .read("city:market");

        assertTrue(result.success());
        assertEquals(new Vec3i(10, 7, 23), result.size());
        assertEquals("sha256:market", result.contentHash());
        assertEquals("fake-nbt", result.sourceId());
        assertTrue(result.template().isEmpty());
    }

    @Test
    void fakeSourceReadsWithoutStructureRegistryAndDoesNotInspectJigsawPools() {
        FakeSource source = new FakeSource();
        source.put("city:fixed_house", new Vec3i(9, 6, 14), "sha256:fixed", "config/structures/fixed_house.nbt");

        MinecraftCityTemplateReader.ReadResult result = new MinecraftCityTemplateReader(source)
                .read(ResourceLocation.parse("city:fixed_house"));

        assertTrue(result.success());
        assertEquals(new Vec3i(9, 6, 14), result.size());
        assertEquals(ResourceLocation.parse("city:fixed_house"), source.lastRef);
        assertEquals(1, source.calls);
        assertFalse(source.jigsawPoolWasRead);
    }

    @Test
    void invalidReferenceFailsBeforeSourceAccess() {
        FakeSource source = new FakeSource();

        MinecraftCityTemplateReader.ReadResult result = new MinecraftCityTemplateReader(source)
                .read("not a resource location");

        assertFalse(result.success());
        assertEquals(MinecraftCityTemplateReader.FailureCode.TEMPLATE_REF_INVALID, result.failureCode());
        assertEquals(0, source.calls);
    }

    private static final class FakeSource implements MinecraftCityTemplateReader.TemplateSource {
        private final Map<ResourceLocation, MinecraftCityTemplateReader.TemplateSnapshot> templates = new HashMap<>();
        private ResourceLocation lastRef;
        private int calls;
        private boolean jigsawPoolWasRead;

        private void put(String ref, Vec3i size, String contentHash, String sourceId) {
            templates.put(ResourceLocation.parse(ref),
                    MinecraftCityTemplateReader.TemplateSnapshot.metadata(size, contentHash, sourceId));
        }

        @Override
        public Optional<MinecraftCityTemplateReader.TemplateSnapshot> load(ResourceLocation templateRef) {
            calls++;
            lastRef = templateRef;
            return Optional.ofNullable(templates.get(templateRef));
        }
    }
}
