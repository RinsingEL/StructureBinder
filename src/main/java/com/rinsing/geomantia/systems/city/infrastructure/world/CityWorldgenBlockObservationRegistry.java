package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.GeomantiaMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/** Records actual block states at Minecraft lifecycle boundaries after City worldgen writes. */
@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class CityWorldgenBlockObservationRegistry {
    public static final String SCHEMA_VERSION = "city_worldgen_block_observation.v0.1";
    public static final String POST_FEATURES = "post_features";
    public static final String CHUNK_SAVE = "chunk_save";
    private static final String ROOT_DIRECTORY = "geomantia_city_masks/worldgen_block_observations";
    private static final int MAX_FLUSH_PER_TICK = 2;
    private static final Set<String> QUERYABLE_PHASES = Set.of(POST_FEATURES, "post_retry_tick", CHUNK_SAVE);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Object FILE_LOCK = new Object();
    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();
    private static final Map<PendingKey, ObservationSnapshot> PENDING_SAVE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<CapturedObservation> PENDING_PERSISTENCE =
            new ConcurrentLinkedQueue<>();
    private static final Map<String, List<LocalExpectedBlock>> TEMPLATE_BLOCK_CACHE = new ConcurrentHashMap<>();

    private CityWorldgenBlockObservationRegistry() {
    }

    public static void begin(WorldGenLevel level, ChunkAccess ownerChunk) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(ownerChunk, "ownerChunk");
        Capture abandoned = CURRENT.get();
        if (abandoned != null) {
            LOGGER.warn("Replacing unclosed City worldgen observation capture for {} {},{}",
                    abandoned.dimensionId, abandoned.ownerChunk.x, abandoned.ownerChunk.z);
        }
        CURRENT.set(new Capture(
                level.getLevel().getServer().getWorldPath(LevelResource.ROOT),
                level.getLevel().dimension().location().toString(),
                ownerChunk.getPos()));
    }

    public static void abort() {
        CURRENT.remove();
    }

    public static void watchBlockState(BlockPos pos, BlockState expectedState, String source) {
        watchBlockState(pos, expectedState, source, null);
    }

    public static void watchBlockState(BlockPos pos,
                                       BlockState expectedState,
                                       String source,
                                       BlockObservationRollbackToken rollbackToken) {
        Capture capture = CURRENT.get();
        if (capture == null || pos == null || expectedState == null) {
            return;
        }
        ObservedState postWriteState = observedState(expectedState);
        capture.watch(pos.immutable(), postWriteState.blockId, postWriteState, source, rollbackToken);
    }

    public static BlockObservationRollbackToken newRollbackToken() {
        Capture capture = CURRENT.get();
        return capture == null ? null : capture.newRollbackToken();
    }

    public static void rollbackToken(BlockObservationRollbackToken rollbackToken) {
        Capture capture = CURRENT.get();
        if (capture != null && rollbackToken != null) {
            capture.rollback(rollbackToken);
        }
    }

    public static void watchTemplate(CompoundTag templateNbt,
                                     BlockPos origin,
                                     Mirror mirror,
                                     Rotation rotation,
                                     BlockPos rotationPivot,
                                     boolean ignoreTemplateAir,
                                     BoundingBox ownerBounds,
                                     Function<BlockPos, BlockState> stateReader,
                                     String source) {
        watchTemplate(templateNbt, origin, mirror, rotation, rotationPivot, ignoreTemplateAir,
                ownerBounds, stateReader, source, ignored -> null);
    }

    public static void watchTemplate(CompoundTag templateNbt,
                                     BlockPos origin,
                                     Mirror mirror,
                                     Rotation rotation,
                                     BlockPos rotationPivot,
                                     boolean ignoreTemplateAir,
                                     BoundingBox ownerBounds,
                                     Function<BlockPos, BlockState> stateReader,
                                     String source,
                                     Function<BlockPos, BlockObservationRollbackToken> rollbackTokenResolver) {
        Capture capture = CURRENT.get();
        if (capture == null || templateNbt == null) {
            return;
        }
        for (TemplateExpectedBlock target : templateExpectedBlocks(templateNbt, origin, mirror, rotation,
                rotationPivot, ignoreTemplateAir, ownerBounds)) {
            capture.watch(target.worldPos, target.blockId,
                    observedState(stateReader.apply(target.worldPos)), source,
                    rollbackTokenResolver.apply(target.worldPos));
        }
    }

    public static void watchStructureTemplate(StructureTemplate template,
                                              String templateHash,
                                              BlockPos origin,
                                              Mirror mirror,
                                              Rotation rotation,
                                              BlockPos rotationPivot,
                                              BoundingBox ownerBounds,
                                              Function<BlockPos, BlockState> stateReader,
                                              String source) {
        Capture capture = CURRENT.get();
        if (capture == null || template == null || templateHash == null || templateHash.isBlank()) {
            return;
        }
        List<LocalExpectedBlock> localBlocks = TEMPLATE_BLOCK_CACHE.computeIfAbsent(templateHash,
                ignored -> localExpectedBlocks(template.save(new CompoundTag())));
        for (TemplateExpectedBlock target : transformExpectedBlocks(localBlocks, origin, mirror, rotation,
                rotationPivot, false, ownerBounds)) {
            capture.watch(target.worldPos, target.blockId,
                    observedState(stateReader.apply(target.worldPos)), source);
        }
    }

    static List<TemplateExpectedBlock> templateExpectedBlocks(CompoundTag templateNbt,
                                                               BlockPos origin,
                                                               Mirror mirror,
                                                               Rotation rotation,
                                                               BlockPos rotationPivot,
                                                               boolean ignoreTemplateAir,
                                                               BoundingBox ownerBounds) {
        return transformExpectedBlocks(localExpectedBlocks(templateNbt), origin, mirror, rotation,
                rotationPivot, ignoreTemplateAir, ownerBounds);
    }

    private static List<LocalExpectedBlock> localExpectedBlocks(CompoundTag templateNbt) {
        List<LocalExpectedBlock> result = new ArrayList<>();
        ListTag palette = templateNbt.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocks = templateNbt.getList("blocks", Tag.TAG_COMPOUND);
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag block = blocks.getCompound(index);
            int stateIndex = block.getInt("state");
            if (stateIndex < 0 || stateIndex >= palette.size()) {
                continue;
            }
            String blockId = palette.getCompound(stateIndex).getString("Name");
            if (blockId.isBlank()) {
                continue;
            }
            ListTag coordinates = block.getList("pos", Tag.TAG_INT);
            if (coordinates.size() != 3) {
                continue;
            }
            BlockPos local = new BlockPos(coordinates.getInt(0), coordinates.getInt(1), coordinates.getInt(2));
            result.add(new LocalExpectedBlock(local, blockId));
        }
        return List.copyOf(result);
    }

    private static List<TemplateExpectedBlock> transformExpectedBlocks(List<LocalExpectedBlock> localBlocks,
                                                                        BlockPos origin,
                                                                        Mirror mirror,
                                                                        Rotation rotation,
                                                                        BlockPos rotationPivot,
                                                                        boolean ignoreTemplateAir,
                                                                        BoundingBox ownerBounds) {
        List<TemplateExpectedBlock> result = new ArrayList<>();
        for (LocalExpectedBlock expected : localBlocks) {
            if (ignoreTemplateAir && "minecraft:air".equals(expected.blockId)) {
                continue;
            }
            BlockPos worldPos = StructureTemplate.transform(expected.localPos, mirror, rotation, rotationPivot)
                    .offset(origin);
            if (ownerBounds == null || ownerBounds.isInside(worldPos)) {
                result.add(new TemplateExpectedBlock(worldPos.immutable(), expected.blockId));
            }
        }
        return List.copyOf(result);
    }

    public static void finishAfterFeatures(WorldGenLevel level, ChunkAccess ownerChunk) {
        finish(level, ownerChunk, POST_FEATURES, "ChunkGenerator.applyBiomeDecoration:TAIL");
    }

    public static void finishAfterRetry(WorldGenLevel level, ChunkAccess ownerChunk) {
        finish(level, ownerChunk, "post_retry_tick", "Forge.ServerTickEvent:END");
    }

    private static void finish(WorldGenLevel level,
                               ChunkAccess ownerChunk,
                               String phase,
                               String callback) {
        Capture capture = CURRENT.get();
        CURRENT.remove();
        if (capture == null || level == null || ownerChunk == null) {
            return;
        }
        String dimensionId = level.getLevel().dimension().location().toString();
        if (!capture.dimensionId.equals(dimensionId) || !capture.ownerChunk.equals(ownerChunk.getPos())) {
            LOGGER.warn("Discarding mismatched City worldgen observation capture for {} {},{}",
                    capture.dimensionId, capture.ownerChunk.x, capture.ownerChunk.z);
            return;
        }
        for (ObservationSnapshot snapshot : capture.snapshotsByObservedChunk()) {
            PENDING_PERSISTENCE.add(captureObservation(snapshot, phase, callback, level::getBlockState));
            PENDING_SAVE.merge(PendingKey.of(snapshot), snapshot,
                    CityWorldgenBlockObservationRegistry::mergeSnapshots);
        }
    }

    @SubscribeEvent
    public static void onChunkSave(ChunkDataEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Path serverRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        String dimensionId = level.dimension().location().toString();
        ChunkAccess chunk = event.getChunk();
        PendingKey key = new PendingKey(serverRoot.toAbsolutePath().normalize(), dimensionId,
                chunk.getPos().toLong());
        ObservationSnapshot snapshot = PENDING_SAVE.get(key);
        if (snapshot == null) {
            return;
        }
        PENDING_PERSISTENCE.add(captureObservation(snapshot, CHUNK_SAVE,
                "Forge.ChunkDataEvent.Save", chunk::getBlockState));
        PENDING_SAVE.remove(key, snapshot);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            flushPending(MAX_FLUSH_PER_TICK);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        flushPending(Integer.MAX_VALUE);
        CURRENT.remove();
        PENDING_SAVE.clear();
        PENDING_PERSISTENCE.clear();
        TEMPLATE_BLOCK_CACHE.clear();
    }

    public static JsonObject query(Path serverRoot,
                                   String dimensionId,
                                   int chunkX,
                                   int chunkZ,
                                   String phase,
                                   int limit,
                                   boolean includeBlocks) {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId is required.");
        }
        if (ResourceLocation.tryParse(dimensionId) == null) {
            throw new IllegalArgumentException("dimensionId must be a valid resource location.");
        }
        if (phase != null && !phase.isBlank() && !QUERYABLE_PHASES.contains(phase)) {
            throw new IllegalArgumentException("phase must be post_features, post_retry_tick, or chunk_save.");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100.");
        }
        int boundedLimit = limit;
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        Path path = observationPath(serverRoot, dimensionId, chunkPos);
        Deque<JsonObject> selected = new ArrayDeque<>(boundedLimit);
        Set<String> selectedIds = new HashSet<>();
        Path normalizedRoot = serverRoot.toAbsolutePath().normalize();
        int pendingPersistenceCount = 0;
        synchronized (FILE_LOCK) {
            if (Files.isRegularFile(path)) {
                try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        JsonElement parsed;
                        try {
                            parsed = JsonParser.parseString(line);
                        } catch (RuntimeException ignored) {
                            continue;
                        }
                        if (parsed.isJsonObject()) {
                            addSelected(selected, selectedIds, parsed.getAsJsonObject(), phase,
                                    boundedLimit, includeBlocks);
                        }
                    }
                } catch (IOException ex) {
                    throw new IllegalStateException("CITY_WORLDGEN_OBSERVATION_READ_FAILED: " + ex.getMessage(), ex);
                }
            }
            for (CapturedObservation pending : PENDING_PERSISTENCE) {
                ObservationSnapshot snapshot = pending.snapshot;
                if (!snapshot.serverRoot.toAbsolutePath().normalize().equals(normalizedRoot)
                        || !snapshot.dimensionId.equals(dimensionId)
                        || !snapshot.observedChunk.equals(chunkPos)) {
                    continue;
                }
                pendingPersistenceCount++;
                addSelected(selected, selectedIds, buildObservation(pending), phase,
                        boundedLimit, includeBlocks);
            }
        }

        JsonArray observations = new JsonArray();
        selected.forEach(observations::add);
        PendingKey key = new PendingKey(serverRoot.toAbsolutePath().normalize(), dimensionId, chunkPos.toLong());
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("status", observations.isEmpty() ? "NOT_OBSERVED" : "OBSERVED");
        response.addProperty("dimensionId", dimensionId);
        response.addProperty("chunkX", chunkX);
        response.addProperty("chunkZ", chunkZ);
        response.addProperty("pendingSaveVerification", PENDING_SAVE.containsKey(key));
        response.addProperty("pendingPersistenceCount", pendingPersistenceCount);
        response.addProperty("artifactPath", path.toAbsolutePath().toString());
        response.addProperty("observationCount", observations.size());
        response.add("observations", observations);
        return response;
    }

    static JsonObject buildObservation(ObservationSnapshot snapshot,
                                       String phase,
                                       String callback,
                                       Function<BlockPos, BlockState> stateReader) {
        return buildObservation(captureObservation(snapshot, phase, callback, stateReader));
    }

    static JsonObject buildObservationFromStates(ObservationSnapshot snapshot,
                                                 String phase,
                                                 String callback,
                                                 Function<BlockPos, ObservedState> stateReader) {
        return buildObservation(captureObservationFromStates(snapshot, phase, callback, stateReader));
    }

    private static CapturedObservation captureObservation(ObservationSnapshot snapshot,
                                                           String phase,
                                                           String callback,
                                                           Function<BlockPos, BlockState> stateReader) {
        return captureObservationFromStates(snapshot, phase, callback,
                pos -> observedState(stateReader.apply(pos)));
    }

    private static CapturedObservation captureObservationFromStates(ObservationSnapshot snapshot,
                                                                     String phase,
                                                                     String callback,
                                                                     Function<BlockPos, ObservedState> stateReader) {
        Map<BlockPos, ObservedState> actualStates = new LinkedHashMap<>();
        snapshot.expectedBlocks.keySet().forEach(pos -> actualStates.put(pos, stateReader.apply(pos)));
        return new CapturedObservation(snapshot, UUID.randomUUID().toString(), Instant.now().toString(),
                phase, callback, Collections.unmodifiableMap(actualStates));
    }

    private static JsonObject buildObservation(CapturedObservation captured) {
        ObservationSnapshot snapshot = captured.snapshot;
        JsonArray blocks = new JsonArray();
        int matchedBlock = 0;
        int mismatchedBlock = 0;
        int postWriteBlockMismatch = 0;
        int matchedPostWriteState = 0;
        int changedSinceWrite = 0;
        int actualAir = 0;
        for (Map.Entry<BlockPos, ExpectedWrite> entry : snapshot.expectedBlocks.entrySet()) {
            BlockPos pos = entry.getKey();
            ExpectedWrite expected = entry.getValue();
            ObservedState actual = captured.actualStates.get(pos);
            boolean postWriteMatchesExpectedBlock = expected.expectedBlockId.equals(expected.postWriteState.blockId);
            boolean matchesBlock = expected.expectedBlockId.equals(actual.blockId);
            boolean matchesPostWriteState = expected.postWriteState.equals(actual);
            if (!postWriteMatchesExpectedBlock) {
                postWriteBlockMismatch++;
            }
            if (matchesBlock) {
                matchedBlock++;
            } else {
                mismatchedBlock++;
            }
            if (matchesPostWriteState) {
                matchedPostWriteState++;
            } else {
                changedSinceWrite++;
            }
            if (actual.air) {
                actualAir++;
            }
            JsonObject block = new JsonObject();
            block.addProperty("x", pos.getX());
            block.addProperty("y", pos.getY());
            block.addProperty("z", pos.getZ());
            block.addProperty("source", expected.source);
            block.addProperty("expectedBlockId", expected.expectedBlockId);
            block.add("postWriteState", blockStateJson(expected.postWriteState));
            block.addProperty("postWriteMatchesExpectedBlock", postWriteMatchesExpectedBlock);
            block.addProperty("actualBlockId", actual.blockId);
            block.add("actualState", blockStateJson(actual));
            block.addProperty("matchesExpectedBlock", matchesBlock);
            block.addProperty("matchesPostWriteState", matchesPostWriteState);
            blocks.add(block);
        }

        JsonObject observation = new JsonObject();
        observation.addProperty("schemaVersion", SCHEMA_VERSION);
        observation.addProperty("observationId", captured.observationId);
        observation.addProperty("observedAt", captured.observedAt);
        observation.addProperty("phase", captured.phase);
        observation.addProperty("sourceCallback", captured.callback);
        observation.addProperty("dimensionId", snapshot.dimensionId);
        observation.addProperty("ownerChunkX", snapshot.ownerChunk.x);
        observation.addProperty("ownerChunkZ", snapshot.ownerChunk.z);
        observation.addProperty("chunkX", snapshot.observedChunk.x);
        observation.addProperty("chunkZ", snapshot.observedChunk.z);
        observation.addProperty("watchedBlockCount", snapshot.expectedBlocks.size());
        observation.addProperty("matchedExpectedBlockCount", matchedBlock);
        observation.addProperty("mismatchedExpectedBlockCount", mismatchedBlock);
        observation.addProperty("postWriteExpectedBlockMismatchCount", postWriteBlockMismatch);
        observation.addProperty("matchedPostWriteStateCount", matchedPostWriteState);
        observation.addProperty("changedSinceWriteCount", changedSinceWrite);
        observation.addProperty("actualAirBlockCount", actualAir);
        observation.addProperty("rolledBackWriteCount", snapshot.rolledBackWriteCount);
        observation.add("blocks", blocks);
        return observation;
    }

    private static void addSelected(Deque<JsonObject> selected,
                                    Set<String> selectedIds,
                                    JsonObject item,
                                    String phase,
                                    int limit,
                                    boolean includeBlocks) {
        if (phase != null && !phase.isBlank()
                && (!hasString(item, "phase") || !phase.equals(item.get("phase").getAsString()))) {
            return;
        }
        String observationId = hasString(item, "observationId")
                ? item.get("observationId").getAsString() : "";
        if (!observationId.isBlank() && !selectedIds.add(observationId)) {
            return;
        }
        JsonObject selectedItem = item;
        if (!includeBlocks) {
            selectedItem = item.deepCopy();
            selectedItem.remove("blocks");
        }
        if (selected.size() == limit) {
            JsonObject removed = selected.removeFirst();
            if (hasString(removed, "observationId")) {
                selectedIds.remove(removed.get("observationId").getAsString());
            }
        }
        selected.addLast(selectedItem);
    }

    private static ObservedState observedState(BlockState state) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            addProperty(properties, state, property);
        }
        return new ObservedState(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                Map.copyOf(properties), state.isAir());
    }

    static JsonObject blockStateJson(ObservedState state) {
        JsonObject result = new JsonObject();
        result.addProperty("blockId", state.blockId);
        JsonObject properties = new JsonObject();
        state.properties.forEach(properties::addProperty);
        result.add("properties", properties);
        return result;
    }

    private static <T extends Comparable<T>> void addProperty(Map<String, String> target,
                                                               BlockState state,
                                                               Property<T> property) {
        target.put(property.getName(), property.getName(state.getValue(property)));
    }

    private static boolean hasString(JsonObject object, String field) {
        return object.has(field) && object.get(field).isJsonPrimitive()
                && object.getAsJsonPrimitive(field).isString();
    }

    private static boolean append(Path serverRoot,
                                  String dimensionId,
                                  ChunkPos chunkPos,
                                  JsonObject observation) {
        Path path = observationPath(serverRoot, dimensionId, chunkPos);
        synchronized (FILE_LOCK) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, observation + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return true;
            } catch (IOException ex) {
                LOGGER.warn("Failed to append City worldgen block observation {}", path, ex);
                return false;
            }
        }
    }

    private static void flushPending(int limit) {
        for (int index = 0; index < limit; index++) {
            CapturedObservation pending = PENDING_PERSISTENCE.peek();
            if (pending == null) {
                return;
            }
            ObservationSnapshot snapshot = pending.snapshot;
            if (!append(snapshot.serverRoot, snapshot.dimensionId, snapshot.observedChunk,
                    buildObservation(pending))) {
                return;
            }
            PENDING_PERSISTENCE.remove(pending);
        }
    }

    static Path observationPath(Path serverRoot, String dimensionId, ChunkPos chunkPos) {
        ResourceLocation dimension = ResourceLocation.tryParse(dimensionId);
        if (dimension == null) {
            throw new IllegalArgumentException("dimensionId must be a valid resource location.");
        }
        String encodedDimension = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(dimension.toString().getBytes(StandardCharsets.UTF_8));
        return serverRoot.resolve(ROOT_DIRECTORY).resolve("dim_" + encodedDimension)
                .resolve("chunk_" + chunkPos.x + "_" + chunkPos.z + ".jsonl");
    }

    static ObservationSnapshot mergeSnapshots(ObservationSnapshot previous, ObservationSnapshot next) {
        Map<BlockPos, ExpectedWrite> merged = new LinkedHashMap<>(previous.expectedBlocks);
        merged.putAll(next.expectedBlocks);
        return new ObservationSnapshot(next.serverRoot, next.dimensionId, next.ownerChunk,
                next.observedChunk, Collections.unmodifiableMap(merged),
                previous.rolledBackWriteCount + next.rolledBackWriteCount);
    }

    record ExpectedWrite(String expectedBlockId,
                         ObservedState postWriteState,
                         String source,
                         String rollbackId) {
        ExpectedWrite(String expectedBlockId, ObservedState postWriteState, String source) {
            this(expectedBlockId, postWriteState, source, null);
        }
    }

    record ObservedState(String blockId, Map<String, String> properties, boolean air) {
    }

    record TemplateExpectedBlock(BlockPos worldPos, String blockId) {
    }

    private record LocalExpectedBlock(BlockPos localPos, String blockId) {
    }

    record ObservationSnapshot(Path serverRoot,
                               String dimensionId,
                               ChunkPos ownerChunk,
                               ChunkPos observedChunk,
                               Map<BlockPos, ExpectedWrite> expectedBlocks,
                               int rolledBackWriteCount) {
    }

    public record BlockObservationRollbackToken(String captureId, String rollbackId) {
        public BlockObservationRollbackToken {
            Objects.requireNonNull(captureId, "captureId");
            Objects.requireNonNull(rollbackId, "rollbackId");
        }
    }

    private record CapturedObservation(ObservationSnapshot snapshot,
                                       String observationId,
                                       String observedAt,
                                       String phase,
                                       String callback,
                                       Map<BlockPos, ObservedState> actualStates) {
    }

    private record PendingKey(Path serverRoot, String dimensionId, long chunkPos) {
        private static PendingKey of(ObservationSnapshot snapshot) {
            return new PendingKey(snapshot.serverRoot.toAbsolutePath().normalize(), snapshot.dimensionId,
                    snapshot.observedChunk.toLong());
        }
    }

    static final class Capture {
        private final String captureId = UUID.randomUUID().toString();
        private final Path serverRoot;
        private final String dimensionId;
        private final ChunkPos ownerChunk;
        private final Map<BlockPos, Deque<ExpectedWrite>> writes = new LinkedHashMap<>();
        private int rolledBackWriteCount;

        Capture(Path serverRoot, String dimensionId, ChunkPos ownerChunk) {
            this.serverRoot = serverRoot;
            this.dimensionId = dimensionId;
            this.ownerChunk = ownerChunk;
        }

        void watch(BlockPos pos,
                   String expectedBlockId,
                   ObservedState postWriteState,
                   String source) {
            watch(pos, expectedBlockId, postWriteState, source, null);
        }

        void watch(BlockPos pos,
                   String expectedBlockId,
                   ObservedState postWriteState,
                   String source,
                   BlockObservationRollbackToken rollbackToken) {
            String rollbackId = rollbackToken != null && captureId.equals(rollbackToken.captureId())
                    ? rollbackToken.rollbackId() : null;
            writes.computeIfAbsent(pos, ignored -> new ArrayDeque<>())
                    .addLast(new ExpectedWrite(expectedBlockId, postWriteState,
                            source == null ? "unknown" : source, rollbackId));
        }

        BlockObservationRollbackToken newRollbackToken() {
            return new BlockObservationRollbackToken(captureId, UUID.randomUUID().toString());
        }

        void rollback(BlockObservationRollbackToken rollbackToken) {
            if (!captureId.equals(rollbackToken.captureId())) {
                return;
            }
            var iterator = writes.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<BlockPos, Deque<ExpectedWrite>> entry = iterator.next();
                int before = entry.getValue().size();
                entry.getValue().removeIf(write -> rollbackToken.rollbackId().equals(write.rollbackId()));
                rolledBackWriteCount += before - entry.getValue().size();
                if (entry.getValue().isEmpty()) {
                    iterator.remove();
                }
            }
        }

        List<ObservationSnapshot> snapshotsByObservedChunk() {
            Map<Long, Map<BlockPos, ExpectedWrite>> grouped = new LinkedHashMap<>();
            for (Map.Entry<BlockPos, Deque<ExpectedWrite>> entry : writes.entrySet()) {
                ExpectedWrite current = entry.getValue().peekLast();
                if (current == null) {
                    continue;
                }
                long chunkKey = ChunkPos.asLong(entry.getKey());
                grouped.computeIfAbsent(chunkKey, ignored -> new LinkedHashMap<>())
                        .put(entry.getKey(), current);
            }
            List<ObservationSnapshot> result = new ArrayList<>();
            for (Map.Entry<Long, Map<BlockPos, ExpectedWrite>> entry : grouped.entrySet()) {
                result.add(new ObservationSnapshot(serverRoot, dimensionId, ownerChunk,
                        new ChunkPos(entry.getKey()),
                        Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())),
                        rolledBackWriteCount));
            }
            return result;
        }
    }
}
