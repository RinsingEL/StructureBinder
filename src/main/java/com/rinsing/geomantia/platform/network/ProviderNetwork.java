package com.rinsing.geomantia.platform.network;

import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.client.ProviderSettingsClient;
import com.rinsing.geomantia.systems.provider.application.AgentActivityEvent;
import com.rinsing.geomantia.systems.provider.application.PlayerProviderConfig;
import com.rinsing.geomantia.systems.provider.application.PlayerProviderService;
import com.rinsing.geomantia.systems.provider.application.ProviderSettingsSnapshot;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class ProviderNetwork {
    private static final String PROTOCOL = "5";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(GeomantiaMod.MOD_ID, "player_provider"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean registered;

    private ProviderNetwork() {
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        int id = 0;
        CHANNEL.registerMessage(id++, SettingsRequest.class,
                SettingsRequest::encode, SettingsRequest::decode, SettingsRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, SettingsResponse.class,
                SettingsResponse::encode, SettingsResponse::decode, SettingsResponse::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SaveRequest.class,
                SaveRequest::encode, SaveRequest::decode, SaveRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, TestRequest.class,
                TestRequest::encode, TestRequest::decode, TestRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, ActivityRequest.class,
                ActivityRequest::encode, ActivityRequest::decode, ActivityRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id, ActivityResponse.class,
                ActivityResponse::encode, ActivityResponse::decode, ActivityResponse::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void requestSettings() {
        CHANNEL.sendToServer(new SettingsRequest());
    }

    public static void saveSettings(String providerKind, boolean enabled, String baseUrl,
                                    String model, String apiProtocol, int timeoutSeconds, String agentRuntime,
                                    String replacementApiKey,
                                    boolean clearStoredApiKey) {
        CHANNEL.sendToServer(new SaveRequest(providerKind, enabled, baseUrl, model, apiProtocol, timeoutSeconds,
                agentRuntime,
                replacementApiKey, clearStoredApiKey));
    }

    public static void testConnection() {
        CHANNEL.sendToServer(new TestRequest());
    }

    public static void requestActivity() {
        CHANNEL.sendToServer(new ActivityRequest());
    }

    private static boolean editable(ServerPlayer player) {
        return player.hasPermissions(2) || player.server.isSingleplayerOwner(player.getGameProfile());
    }

    private static void send(ServerPlayer player, ProviderSettingsSnapshot snapshot) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SettingsResponse(snapshot));
    }

    private record SettingsRequest() {
        static void encode(SettingsRequest ignored, FriendlyByteBuf buffer) {
        }

        static SettingsRequest decode(FriendlyByteBuf buffer) {
            return new SettingsRequest();
        }

        static void handle(SettingsRequest ignored, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) context.enqueueWork(() -> send(player,
                    PlayerProviderService.instance().snapshot(editable(player))));
            context.setPacketHandled(true);
        }
    }

    private record SettingsResponse(ProviderSettingsSnapshot snapshot) {
        static void encode(SettingsResponse response, FriendlyByteBuf buffer) {
            ProviderSettingsSnapshot value = response.snapshot;
            buffer.writeUtf(value.providerKind());
            buffer.writeBoolean(value.enabled());
            buffer.writeUtf(value.baseUrl());
            buffer.writeUtf(value.model());
            buffer.writeUtf(value.apiProtocol());
            buffer.writeVarInt(value.timeoutSeconds());
            buffer.writeUtf(value.agentRuntime());
            buffer.writeBoolean(value.hasApiKey());
            buffer.writeUtf(value.apiKeySource());
            buffer.writeBoolean(value.editable());
            buffer.writeUtf(value.connectionState());
            buffer.writeUtf(value.message());
            buffer.writeUtf(value.automationState());
            buffer.writeUtf(value.automationMessage());
            buffer.writeUtf(value.activeRunId());
            buffer.writeUtf(value.activeCitySeedId());
            buffer.writeUtf(value.activeTool());
        }

        static SettingsResponse decode(FriendlyByteBuf buffer) {
            return new SettingsResponse(new ProviderSettingsSnapshot(buffer.readUtf(), buffer.readBoolean(),
                    buffer.readUtf(512), buffer.readUtf(160), buffer.readUtf(32), buffer.readVarInt(),
                    buffer.readUtf(32), buffer.readBoolean(),
                    buffer.readUtf(32), buffer.readBoolean(), buffer.readUtf(64), buffer.readUtf(256),
                    buffer.readUtf(64), buffer.readUtf(256), buffer.readUtf(160), buffer.readUtf(256),
                    buffer.readUtf(160)));
        }

        static void handle(SettingsResponse response, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ProviderSettingsClient.receive(response.snapshot)));
            context.setPacketHandled(true);
        }
    }

    private record SaveRequest(String providerKind, boolean enabled, String baseUrl, String model, String apiProtocol,
                               int timeoutSeconds, String agentRuntime, String replacementApiKey,
                               boolean clearStoredApiKey) {
        static void encode(SaveRequest request, FriendlyByteBuf buffer) {
            buffer.writeUtf(request.providerKind);
            buffer.writeBoolean(request.enabled);
            buffer.writeUtf(request.baseUrl);
            buffer.writeUtf(request.model);
            buffer.writeUtf(request.apiProtocol);
            buffer.writeVarInt(request.timeoutSeconds);
            buffer.writeUtf(request.agentRuntime);
            buffer.writeUtf(request.replacementApiKey);
            buffer.writeBoolean(request.clearStoredApiKey);
        }

        static SaveRequest decode(FriendlyByteBuf buffer) {
            return new SaveRequest(buffer.readUtf(32), buffer.readBoolean(), buffer.readUtf(512),
                    buffer.readUtf(160), buffer.readUtf(32), buffer.readVarInt(), buffer.readUtf(32),
                    buffer.readUtf(4096), buffer.readBoolean());
        }

        static void handle(SaveRequest request, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) context.enqueueWork(() -> {
                boolean canEdit = editable(player);
                PlayerProviderConfig config = new PlayerProviderConfig(request.providerKind, request.enabled,
                        request.baseUrl, request.model, request.apiProtocol, request.timeoutSeconds,
                        request.agentRuntime);
                PlayerProviderService.instance().save(config, request.replacementApiKey,
                                request.clearStoredApiKey, canEdit)
                        .thenAccept(snapshot -> player.server.execute(() -> send(player, snapshot)));
            });
            context.setPacketHandled(true);
        }
    }

    private record TestRequest() {
        static void encode(TestRequest ignored, FriendlyByteBuf buffer) {
        }

        static TestRequest decode(FriendlyByteBuf buffer) {
            return new TestRequest();
        }

        static void handle(TestRequest ignored, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) context.enqueueWork(() -> {
                boolean canEdit = editable(player);
                var test = PlayerProviderService.instance().test(canEdit);
                send(player, PlayerProviderService.instance().snapshot(canEdit));
                test
                        .thenAccept(snapshot -> player.server.execute(() -> send(player, snapshot)));
            });
            context.setPacketHandled(true);
        }
    }

    private record ActivityRequest() {
        static void encode(ActivityRequest ignored, FriendlyByteBuf buffer) {
        }

        static ActivityRequest decode(FriendlyByteBuf buffer) {
            return new ActivityRequest();
        }

        static void handle(ActivityRequest ignored, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) context.enqueueWork(() -> CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new ActivityResponse(PlayerProviderService.instance().activityEvents())));
            context.setPacketHandled(true);
        }
    }

    private record ActivityResponse(List<AgentActivityEvent> events) {
        static void encode(ActivityResponse response, FriendlyByteBuf buffer) {
            buffer.writeVarInt(response.events.size());
            for (AgentActivityEvent event : response.events) {
                buffer.writeUtf(event.occurredAt(), 64);
                buffer.writeUtf(event.kind(), 32);
                buffer.writeUtf(event.message(), 600);
            }
        }

        static ActivityResponse decode(FriendlyByteBuf buffer) {
            int size = Math.min(160, Math.max(0, buffer.readVarInt()));
            List<AgentActivityEvent> events = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                events.add(new AgentActivityEvent(buffer.readUtf(64), buffer.readUtf(32), buffer.readUtf(600)));
            }
            return new ActivityResponse(List.copyOf(events));
        }

        static void handle(ActivityResponse response, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ProviderSettingsClient.receiveActivity(response.events)));
            context.setPacketHandled(true);
        }
    }
}
