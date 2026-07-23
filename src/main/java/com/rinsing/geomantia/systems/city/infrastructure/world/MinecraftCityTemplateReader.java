package com.rinsing.geomantia.systems.city.infrastructure.world;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads fixed City building templates without going through configured structures or Jigsaw pools.
 */
public final class MinecraftCityTemplateReader {
    public static final String MINECRAFT_TEMPLATE_MANAGER_SOURCE = "minecraft:structure_template_manager";

    private final TemplateSource source;

    public MinecraftCityTemplateReader(StructureTemplateManager templateManager) {
        this(new StructureTemplateManagerSource(templateManager));
    }

    public MinecraftCityTemplateReader(TemplateSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public ReadResult read(String templateRef) {
        if (templateRef == null || templateRef.isBlank()) {
            return ReadResult.failure(templateRef, FailureCode.TEMPLATE_REF_INVALID,
                    "templateRef must not be blank");
        }
        ResourceLocation resourceLocation = ResourceLocation.tryParse(templateRef);
        if (resourceLocation == null) {
            return ReadResult.failure(templateRef, FailureCode.TEMPLATE_REF_INVALID,
                    "templateRef is not a valid resource location");
        }
        return read(resourceLocation);
    }

    public ReadResult read(ResourceLocation templateRef) {
        if (templateRef == null) {
            return ReadResult.failure(null, FailureCode.TEMPLATE_REF_INVALID,
                    "templateRef must not be null");
        }

        String normalizedRef = templateRef.toString();
        TemplateSnapshot snapshot;
        try {
            Optional<TemplateSnapshot> loaded = source.load(templateRef);
            if (loaded == null || loaded.isEmpty()) {
                return ReadResult.failure(normalizedRef, FailureCode.TEMPLATE_NOT_FOUND,
                        "StructureTemplateManager did not provide a template");
            }
            snapshot = loaded.get();
        } catch (Exception ex) {
            return ReadResult.failure(normalizedRef, FailureCode.TEMPLATE_READ_FAILED,
                    message(ex));
        }

        if (snapshot == null || snapshot.size() == null
                || snapshot.contentHash() == null || snapshot.contentHash().isBlank()
                || snapshot.sourceId() == null || snapshot.sourceId().isBlank()) {
            return ReadResult.failure(normalizedRef, FailureCode.TEMPLATE_METADATA_INVALID,
                    "template source returned incomplete metadata");
        }

        Vec3i size = snapshot.template()
                .map(StructureTemplate::getSize)
                .orElse(snapshot.size());
        if (size == null || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return ReadResult.failure(normalizedRef, FailureCode.TEMPLATE_SIZE_NON_POSITIVE,
                    "StructureTemplate.getSize() must be positive on every axis", size,
                    snapshot.contentHash(), snapshot.sourceId());
        }

        return ReadResult.success(normalizedRef, size, snapshot.template(), snapshot.contentHash(),
                snapshot.sourceId());
    }

    /**
     * Provides the same source snapshot to the worldgen adapter without hiding its error handling.
     * The adapter converts source failures into placement reason codes.
     */
    public Optional<TemplateSnapshot> readSnapshot(ResourceLocation templateRef) throws Exception {
        if (templateRef == null) {
            return Optional.empty();
        }
        return source.load(templateRef);
    }

    private static String message(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    public enum FailureCode {
        NONE,
        TEMPLATE_REF_INVALID,
        TEMPLATE_NOT_FOUND,
        TEMPLATE_READ_FAILED,
        TEMPLATE_METADATA_INVALID,
        TEMPLATE_SIZE_NON_POSITIVE
    }

    @FunctionalInterface
    public interface TemplateSource {
        Optional<TemplateSnapshot> load(ResourceLocation templateRef) throws Exception;
    }

    /**
     * The parsed template is optional so pure contract tests can provide size and provenance without
     * constructing Minecraft's block registry. Runtime sources should provide it for the placer.
     */
    public record TemplateSnapshot(Optional<StructureTemplate> template,
                                   Vec3i size,
                                   String contentHash,
                                   String sourceId) {
        public TemplateSnapshot {
            template = template == null ? Optional.empty() : template;
            Objects.requireNonNull(size, "size");
        }

        public static TemplateSnapshot metadata(Vec3i size, String contentHash, String sourceId) {
            return new TemplateSnapshot(Optional.empty(), size, contentHash, sourceId);
        }

        public static TemplateSnapshot loaded(StructureTemplate template,
                                              String contentHash,
                                              String sourceId) {
            Objects.requireNonNull(template, "template");
            return new TemplateSnapshot(Optional.of(template), template.getSize(), contentHash, sourceId);
        }
    }

    public record ReadResult(String templateRef,
                             boolean readable,
                             FailureCode failureCode,
                             String failureDetail,
                             Vec3i size,
                             Optional<StructureTemplate> template,
                             String contentHash,
                             String sourceId) {
        public ReadResult {
            template = template == null ? Optional.empty() : template;
            Objects.requireNonNull(failureCode, "failureCode");
            if (readable && failureCode != FailureCode.NONE) {
                throw new IllegalArgumentException("readable result must use FailureCode.NONE");
            }
            if (!readable && failureCode == FailureCode.NONE) {
                throw new IllegalArgumentException("failed result must use a failure code");
            }
        }

        public boolean success() {
            return readable;
        }

        private static ReadResult success(String templateRef,
                                          Vec3i size,
                                          Optional<StructureTemplate> template,
                                          String contentHash,
                                          String sourceId) {
            return new ReadResult(templateRef, true, FailureCode.NONE, "", size, template,
                    contentHash, sourceId);
        }

        private static ReadResult failure(String templateRef, FailureCode failureCode, String detail) {
            return failure(templateRef, failureCode, detail, null, "", "");
        }

        private static ReadResult failure(String templateRef,
                                          FailureCode failureCode,
                                          String detail,
                                          Vec3i size,
                                          String contentHash,
                                          String sourceId) {
            return new ReadResult(templateRef, false, failureCode, detail, size, Optional.empty(),
                    contentHash, sourceId);
        }
    }

    private static final class StructureTemplateManagerSource implements TemplateSource {
        private final StructureTemplateManager templateManager;

        private StructureTemplateManagerSource(StructureTemplateManager templateManager) {
            this.templateManager = Objects.requireNonNull(templateManager, "templateManager");
        }

        @Override
        public Optional<TemplateSnapshot> load(ResourceLocation templateRef) throws IOException {
            Optional<StructureTemplate> template = templateManager.get(templateRef);
            if (template.isEmpty()) {
                return Optional.empty();
            }
            StructureTemplate value = template.get();
            String sourceId = MINECRAFT_TEMPLATE_MANAGER_SOURCE + ":" + templateRef;
            return Optional.of(TemplateSnapshot.loaded(value, contentHash(value), sourceId));
        }
    }

    private static String contentHash(StructureTemplate template) throws IOException {
        CompoundTag serialized = template.save(new CompoundTag());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(serialized, new DataOutputStream(bytes));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }
}
