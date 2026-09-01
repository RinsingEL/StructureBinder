package com.rinsing.geomantia.platform.network;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.client.AdventurerMapClient;
import com.rinsing.geomantia.platform.RealmPlanningServices;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot.CityNode;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapStatusReader;
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
import java.util.function.Supplier;

public final class AdventurerMapNetwork {
    private static final String PROTOCOL = "1";
    private static final int MAX_NODES = 8192;
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
        CHANNEL.registerMessage(messageId, SnapshotResponse.class,
                SnapshotResponse::encode, SnapshotResponse::decode, SnapshotResponse::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void requestSnapshot() {
        CHANNEL.sendToServer(new SnapshotRequest());
    }

    private record SnapshotRequest() {
        static void encode(SnapshotRequest ignored, FriendlyByteBuf buffer) {
        }

        static SnapshotRequest decode(FriendlyByteBuf buffer) {
            return new SnapshotRequest();
        }

        static void handle(SnapshotRequest ignored, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                context.setPacketHandled(true);
                return;
            }
            context.enqueueWork(() -> {
                AdventurerMapSnapshot snapshot;
                try {
                    var planningService = RealmPlanningServices.forServer(sender.server);
                    JsonObject status = planningService.status();
                    String preferredRunId = status.has("runId") ? status.get("runId").getAsString() : "";
                    snapshot = AdventurerMapStatusReader.read(
                            sender.server.getServerDirectory().toPath().resolve("realm_debug"), preferredRunId,
                            planningService.initialActivityRadiusBlocks());
                } catch (IOException | RuntimeException exception) {
                    snapshot = errorSnapshot();
                }
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), new SnapshotResponse(snapshot));
            });
            context.setPacketHandled(true);
        }
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
                    initialActivityRadiusBlocks, nodes));
        }

        static void handle(SnapshotResponse response, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> AdventurerMapClient.receiveSnapshot(response.snapshot)));
            context.setPacketHandled(true);
        }
    }

    private static AdventurerMapSnapshot errorSnapshot() {
        return new AdventurerMapSnapshot("", "error", "", 0.0D,
                "", "error", "", "", "", "error", 0, 0, 2048, List.of());
    }
}
