package com.rinsing.geomantia.platform.network;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.client.AdventurerMapClient;
import com.rinsing.geomantia.platform.RealmPlanningServices;
import com.rinsing.geomantia.platform.WorldScopedPlanningPaths;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessConfig;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot.CityNode;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot.CoarseMap;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapStatusReader;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapStatusReader.MapViewport;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class AdventurerMapNetwork {
    private static final String PROTOCOL = "6";
    private static final int VIEW_RADIUS_AT_ZOOM_ONE = 4096;
    private static final int MIN_VIEW_RADIUS = 1024;
    private static final int MAX_VIEW_RADIUS = 8192;
    private static final int VIEWPORT_CENTER_QUANTUM_BLOCKS = 256;
    private static final int MAX_NODES = 8192;
    private static final int MAX_MAP_PIXELS = 128 * 128;
    private static final int MAX_REALMS = 255;
    private static final ExecutorService SNAPSHOT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Geomantia-Adventurer-Map");
        thread.setDaemon(true);
        return thread;
    });
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(GeomantiaMod.MOD_ID, "adventurer_map"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean registered;

    private AdventurerMapNetwork() {
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        int messageId = 0;
        CHANNEL.registerMessage(messageId++, SnapshotRequest.class,
                SnapshotRequest::encode, SnapshotRequest::decode, SnapshotRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(messageId++, SnapshotResponse.class,
                SnapshotResponse::encode, SnapshotResponse::decode, SnapshotResponse::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(messageId++, OpenMap.class,
                OpenMap::encode, OpenMap::decode, OpenMap::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(messageId++, RetryCityRequest.class,
                RetryCityRequest::encode, RetryCityRequest::decode, RetryCityRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(messageId, RetryCityResponse.class,
                RetryCityResponse::encode, RetryCityResponse::decode, RetryCityResponse::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void retryCity(String runId, String cityId) {
        CHANNEL.sendToServer(new RetryCityRequest(runId, cityId));
    }

    private record RetryCityRequest(String runId, String cityId) {
        static void encode(RetryCityRequest value, FriendlyByteBuf buffer) {
            buffer.writeUtf(value.runId, 256); buffer.writeUtf(value.cityId, 256);
        }
        static RetryCityRequest decode(FriendlyByteBuf buffer) {
            return new RetryCityRequest(buffer.readUtf(256), buffer.readUtf(256));
        }
        static void handle(RetryCityRequest request, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer sender = context.getSender();
            if (sender != null) context.enqueueWork(() -> {
                String result = com.rinsing.geomantia.platform.http.GeomantiaHttpServer.retryCityFromMap(
                        sender, request.runId, request.cityId);
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), new RetryCityResponse(result));
            });
            context.setPacketHandled(true);
        }
    }

    private record RetryCityResponse(String result) {
        static void encode(RetryCityResponse value, FriendlyByteBuf buffer) { buffer.writeUtf(value.result, 64); }
        static RetryCityResponse decode(FriendlyByteBuf buffer) { return new RetryCityResponse(buffer.readUtf(64)); }
        static void handle(RetryCityResponse response, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> AdventurerMapClient.receiveRetryResult(response.result)));
            context.setPacketHandled(true);
        }
    }

    public static void requestSnapshot(double zoom, double centerX, double centerZ) {
        CHANNEL.sendToServer(new SnapshotRequest(normalizeZoom(zoom), centerX, centerZ));
    }

    public static void openFor(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenMap());
    }

    private record SnapshotRequest(double zoom, double centerX, double centerZ) {
        static void encode(SnapshotRequest request, FriendlyByteBuf buffer) {
            buffer.writeDouble(request.zoom);
            buffer.writeDouble(request.centerX);
            buffer.writeDouble(request.centerZ);
        }

        static SnapshotRequest decode(FriendlyByteBuf buffer) {
            return new SnapshotRequest(normalizeZoom(buffer.readDouble()), buffer.readDouble(), buffer.readDouble());
        }

        static void handle(SnapshotRequest ignored, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                context.setPacketHandled(true);
                return;
            }
            context.enqueueWork(() -> queueSnapshot(sender, ignored));
            context.setPacketHandled(true);
        }
    }

    private static void queueSnapshot(ServerPlayer sender, SnapshotRequest request) {
        final java.nio.file.Path debugRoot;
        final String preferredRunId;
        final PlanningAreaAccessConfig accessConfig;
        final MapViewport viewport;
        try {
            var planningService = RealmPlanningServices.forServer(sender.server);
            JsonObject status = planningService.status();
            preferredRunId = status.has("runId") ? status.get("runId").getAsString() : "";
            accessConfig = planningService.planningAreaAccessConfig();
            debugRoot = WorldScopedPlanningPaths.realmDebugRoot(sender.server);
            double zoom = normalizeZoom(request.zoom);
            int radius = Math.max(MIN_VIEW_RADIUS, Math.min(MAX_VIEW_RADIUS,
                    (int) Math.round(VIEW_RADIUS_AT_ZOOM_ONE / zoom)));
            viewport = new MapViewport(snapViewportCenter(normalizeCenter(request.centerX, sender.getX())),
                    snapViewportCenter(normalizeCenter(request.centerZ, sender.getZ())), radius);
        } catch (RuntimeException exception) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), new SnapshotResponse(errorSnapshot()));
            return;
        }
        SNAPSHOT_EXECUTOR.execute(() -> {
            AdventurerMapSnapshot snapshot;
            try {
                snapshot = AdventurerMapStatusReader.read(
                        debugRoot, preferredRunId, accessConfig, viewport);
            } catch (IOException | RuntimeException exception) {
                snapshot = errorSnapshot();
            }
            AdventurerMapSnapshot completed = snapshot;
            sender.server.execute(() -> CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sender), new SnapshotResponse(completed)));
        });
    }

    private static double normalizeZoom(double zoom) {
        return Double.isFinite(zoom) ? Math.max(0.5D, Math.min(4.0D, zoom)) : 1.0D;
    }

    private static int normalizeCenter(double value, double fallback) {
        return (int) Math.floor(Math.max(-30_000_000D, Math.min(30_000_000D,
                Double.isFinite(value) ? value : fallback)));
    }

    private static int snapViewportCenter(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, VIEWPORT_CENTER_QUANTUM_BLOCKS)
                * VIEWPORT_CENTER_QUANTUM_BLOCKS + VIEWPORT_CENTER_QUANTUM_BLOCKS / 2;
    }

    private record SnapshotResponse(AdventurerMapSnapshot snapshot) {
        static void encode(SnapshotResponse response, FriendlyByteBuf buffer) {
            AdventurerMapSnapshot value = response.snapshot;
            buffer.writeUtf(value.runId());
            buffer.writeUtf(value.wStatus());
            buffer.writeUtf(value.wPhase());
            buffer.writeDouble(value.wProgressPercent());
            buffer.writeUtf(value.tStage());
            buffer.writeUtf(value.tStatus());
            buffer.writeUtf(value.currentRealmId());
            buffer.writeUtf(value.currentRealmName());
            buffer.writeUtf(value.currentCityId());
            buffer.writeUtf(value.cityStatus());
            buffer.writeVarInt(value.completedCityCount());
            buffer.writeVarInt(value.remainingCityCount());
            buffer.writeVarInt(value.initialActivityRadiusBlocks());
            CoarseMap map = value.coarseMap();
            buffer.writeUtf(map.dimensionId());
            buffer.writeInt(map.minBlockX());
            buffer.writeInt(map.minBlockZ());
            buffer.writeVarInt(map.cellSizeBlocks());
            buffer.writeVarInt(map.width());
            buffer.writeVarInt(map.height());
            buffer.writeByteArray(map.terrainCodes());
            buffer.writeByteArray(map.realmCodes());
            buffer.writeByteArray(map.revealedCodes());
            buffer.writeVarInt(map.realmIds().size());
            for (String realmId : map.realmIds()) buffer.writeUtf(realmId);
            buffer.writeVarInt(value.cityNodes().size());
            for (CityNode node : value.cityNodes()) {
                buffer.writeUtf(node.citySeedId());
                buffer.writeUtf(node.realmId());
                buffer.writeUtf(node.role());
                buffer.writeInt(node.blockX());
                buffer.writeInt(node.blockZ());
                buffer.writeUtf(node.status());
                buffer.writeBoolean(node.current());
            }
        }

        static SnapshotResponse decode(FriendlyByteBuf buffer) {
            String runId = buffer.readUtf();
            String wStatus = buffer.readUtf();
            String wPhase = buffer.readUtf();
            double wProgress = buffer.readDouble();
            String tStage = buffer.readUtf();
            String tStatus = buffer.readUtf();
            String realmId = buffer.readUtf();
            String realmName = buffer.readUtf();
            String cityId = buffer.readUtf();
            String cityStatus = buffer.readUtf();
            int completed = buffer.readVarInt();
            int remaining = buffer.readVarInt();
            int initialActivityRadiusBlocks = buffer.readVarInt();
            String dimensionId = buffer.readUtf();
            int minBlockX = buffer.readInt();
            int minBlockZ = buffer.readInt();
            int cellSizeBlocks = buffer.readVarInt();
            int width = buffer.readVarInt();
            int height = buffer.readVarInt();
            if (width < 0 || width > 128 || height < 0 || height > 128) {
                throw new IllegalArgumentException("ADVENTURER_MAP_RASTER_DIMENSIONS_INVALID");
            }
            byte[] terrainCodes = buffer.readByteArray(MAX_MAP_PIXELS);
            byte[] realmCodes = buffer.readByteArray(MAX_MAP_PIXELS);
            byte[] revealedCodes = buffer.readByteArray(MAX_MAP_PIXELS);
            int realmCount = buffer.readVarInt();
            if (realmCount < 0 || realmCount > MAX_REALMS) {
                throw new IllegalArgumentException("ADVENTURER_MAP_REALM_COUNT_INVALID: " + realmCount);
            }
            List<String> realmIds = new ArrayList<>(realmCount);
            for (int index = 0; index < realmCount; index++) realmIds.add(buffer.readUtf());
            CoarseMap coarseMap = new CoarseMap(dimensionId, minBlockX, minBlockZ, cellSizeBlocks,
                    width, height, terrainCodes, realmCodes, revealedCodes, realmIds);
            int nodeCount = buffer.readVarInt();
            if (nodeCount < 0 || nodeCount > MAX_NODES) {
                throw new IllegalArgumentException("ADVENTURER_MAP_NODE_COUNT_INVALID: " + nodeCount);
            }
            List<CityNode> nodes = new ArrayList<>(nodeCount);
            for (int index = 0; index < nodeCount; index++) {
                nodes.add(new CityNode(buffer.readUtf(), buffer.readUtf(), buffer.readUtf(),
                        buffer.readInt(), buffer.readInt(), buffer.readUtf(), buffer.readBoolean()));
            }
            return new SnapshotResponse(new AdventurerMapSnapshot(runId, wStatus, wPhase, wProgress,
                    tStage, tStatus, realmId, realmName, cityId, cityStatus, completed, remaining,
                    initialActivityRadiusBlocks, coarseMap, nodes));
        }

        static void handle(SnapshotResponse response, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> AdventurerMapClient.receiveSnapshot(response.snapshot)));
            context.setPacketHandled(true);
        }
    }

    private record OpenMap() {
        static void encode(OpenMap ignored, FriendlyByteBuf buffer) {
        }

        static OpenMap decode(FriendlyByteBuf buffer) {
            return new OpenMap();
        }

        static void handle(OpenMap ignored, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> AdventurerMapClient.openMap()));
            context.setPacketHandled(true);
        }
    }

    private static AdventurerMapSnapshot errorSnapshot() {
        return new AdventurerMapSnapshot("", "error", "", 0.0D,
                "", "error", "", "", "", "error", 0, 0, 2048,
                CoarseMap.empty(), List.of());
    }
}
