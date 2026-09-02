package com.rinsing.geomantia.platform.network;

import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.client.ProviderSettingsClient;
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

import java.util.Optional;
import java.util.function.Supplier;

public final class ProviderNetwork {
    private static final String PROTOCOL = "1";
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
        CHANNEL.registerMessage(id, TestRequest.class,
                TestRequest::encode, TestRequest::decode, TestRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void requestSettings() {
        CHANNEL.sendToServer(new SettingsRequest());
    }

    public static void saveSettings(String providerKind, boolean enabled, String baseUrl,
                                    String model, int timeoutSeconds, String replacementApiKey,
                                    boolean clearStoredApiKey) {
        CHANNEL.sendToServer(new SaveRequest(providerKind, enabled, baseUrl, model, timeoutSeconds,
                replacementApiKey, clearStoredApiKey));
    }

    public static void testConnection() {
        CHANNEL.sendToServer(new TestRequest());
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
            buffer.writeVarInt(value.timeoutSeconds());
            buffer.writeBoolean(value.hasApiKey());
            buffer.writeUtf(value.apiKeySource());
            buffer.writeBoolean(value.editable());
            buffer.writeUtf(value.connectionState());
            buffer.writeUtf(value.message());
        }

        static SettingsResponse decode(FriendlyByteBuf buffer) {
            return new SettingsResponse(new ProviderSettingsSnapshot(buffer.readUtf(), buffer.readBoolean(),
                    buffer.readUtf(512), buffer.readUtf(160), buffer.readVarInt(), buffer.readBoolean(),
                    buffer.readUtf(32), buffer.readBoolean(), buffer.readUtf(64), buffer.readUtf(256)));
        }

        static void handle(SettingsResponse response, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ProviderSettingsClient.receive(response.snapshot)));
            context.setPacketHandled(true);
        }
    }

    private record SaveRequest(String providerKind, boolean enabled, String baseUrl, String model,
                               int timeoutSeconds, String replacementApiKey, boolean clearStoredApiKey) {
        static void encode(SaveRequest request, FriendlyByteBuf buffer) {
            buffer.writeUtf(request.providerKind);
            buffer.writeBoolean(request.enabled);
            buffer.writeUtf(request.baseUrl);
            buffer.writeUtf(request.model);
            buffer.writeVarInt(request.timeoutSeconds);
            buffer.writeUtf(request.replacementApiKey);
            buffer.writeBoolean(request.clearStoredApiKey);
        }

        static SaveRequest decode(FriendlyByteBuf buffer) {
            return new SaveRequest(buffer.readUtf(32), buffer.readBoolean(), buffer.readUtf(512),
                    buffer.readUtf(160), buffer.readVarInt(), buffer.readUtf(4096), buffer.readBoolean());
        }

        static void handle(SaveRequest request, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) context.enqueueWork(() -> {
                boolean canEdit = editable(player);
                PlayerProviderConfig config = new PlayerProviderConfig(request.providerKind, request.enabled,
                        request.baseUrl, request.model, request.timeoutSeconds);
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
                send(player, PlayerProviderService.instance().snapshot(canEdit));
                PlayerProviderService.instance().test(canEdit)
                        .thenAccept(snapshot -> player.server.execute(() -> send(player, snapshot)));
            });
            context.setPacketHandled(true);
        }
    }
}
