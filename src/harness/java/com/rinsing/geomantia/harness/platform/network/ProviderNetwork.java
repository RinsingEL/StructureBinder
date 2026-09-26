package com.rinsing.geomantia.harness.platform.network;
import com.rinsing.geomantia.systems.provider.application.*;

import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.harness.client.ProviderSettingsClient;
import com.rinsing.geomantia.harness.systems.provider.application.AgentActivityEvent;
import com.rinsing.geomantia.harness.systems.provider.application.PlayerProviderConfig;
import com.rinsing.geomantia.harness.systems.provider.application.PlayerProviderService;
import com.rinsing.geomantia.harness.systems.provider.application.ProviderSettingsSnapshot;
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
    private static final String PROTOCOL = "6";
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

    public static void requestSettings(PlanningRole role) {
        CHANNEL.sendToServer(new SettingsRequest(role));
    }

    public static void saveSettings(PlanningRole role, String providerKind, boolean enabled, String baseUrl,
                                    String model, String apiProtocol, int timeoutSeconds, String agentRuntime,
                                    String replacementApiKey,
                                    boolean clearStoredApiKey) {
        CHANNEL.sendToServer(new SaveRequest(role, providerKind, enabled, baseUrl, model, apiProtocol, timeoutSeconds,
                agentRuntime,
                replacementApiKey, clearStoredApiKey));
    }

    public static void testConnection(PlanningRole role) {
        CHANNEL.sendToServer(new TestRequest(role));
    }

    public static void requestActivity() {
        CHANNEL.sendToServer(new ActivityRequest());
    }

    private static boolean editable(ServerPlayer player) {
        return player.hasPermissions(2) || player.server.isSingleplayerOwner(player.getGameProfile());
    }

    private static void send(ServerPlayer player, PlanningRole role, ProviderSettingsSnapshot snapshot) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SettingsResponse(role,snapshot));
    }

    private record SettingsRequest(PlanningRole role) {
        static void encode(SettingsRequest ignored, FriendlyByteBuf buffer) {
            buffer.writeEnum(ignored.role);
        }

        static SettingsRequest decode(FriendlyByteBuf buffer) {
            return new SettingsRequest(buffer.readEnum(PlanningRole.class));
        }

        static void handle(SettingsRequest ignored, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) context.enqueueWork(() -> send(player,ignored.role,
                    PlayerProviderService.instance(ignored.role).snapshot(editable(player))));
            context.setPacketHandled(true);
        }
    }

    private record SettingsResponse(PlanningRole role, ProviderSettingsSnapshot snapshot) {
        static void encode(SettingsResponse response, FriendlyByteBuf buffer) {
            buffer.writeEnum(response.role);
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
            return new SettingsResponse(buffer.readEnum(PlanningRole.class),new ProviderSettingsSnapshot(buffer.readUtf(), buffer.readBoolean(),
                    buffer.readUtf(512), buffer.readUtf(160), buffer.readUtf(32), buffer.readVarInt(),
                    buffer.readUtf(32), buffer.readBoolean(),
                    buffer.readUtf(32), buffer.readBoolean(), buffer.readUtf(64), buffer.readUtf(256),
                    buffer.readUtf(64), buffer.readUtf(256), buffer.readUtf(160), buffer.readUtf(256),
                    buffer.readUtf(160)));
        }

        static void handle(SettingsResponse response, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ProviderSettingsClient.receive(response.role,response.snapshot)));
            context.setPacketHandled(true);
        }
    }

    private record SaveRequest(PlanningRole role, String providerKind, boolean enabled, String baseUrl, String model, String apiProtocol,
                               int timeoutSeconds, String agentRuntime, String replacementApiKey,
                               boolean clearStoredApiKey) {
        static void encode(SaveRequest request, FriendlyByteBuf buffer) {
            buffer.writeEnum(request.role);
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
            return new SaveRequest(buffer.readEnum(PlanningRole.class),buffer.readUtf(32), buffer.readBoolean(), buffer.readUtf(512),
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
                PlayerProviderService.instance(request.role).save(config, request.replacementApiKey,
                                request.clearStoredApiKey, canEdit)
                        .thenAccept(snapshot -> player.server.execute(() -> send(player, request.role,snapshot)));
            });
            context.setPacketHandled(true);
        }
    }

    private record TestRequest(PlanningRole role) {
        static void encode(TestRequest ignored, FriendlyByteBuf buffer) {
            buffer.writeEnum(ignored.role);
        }

        static TestRequest decode(FriendlyByteBuf buffer) {
            return new TestRequest(buffer.readEnum(PlanningRole.class));
        }

        static void handle(TestRequest ignored, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) context.enqueueWork(() -> {
                boolean canEdit = editable(player);
                var test = PlayerProviderService.instance(ignored.role).test(canEdit);
                send(player,ignored.role, PlayerProviderService.instance(ignored.role).snapshot(canEdit));
                test
                        .thenAccept(snapshot -> player.server.execute(() -> send(player,ignored.role,snapshot)));
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
                    new ActivityResponse(java.util.Arrays.stream(PlanningRole.values()).flatMap(role->PlayerProviderService.instance(role).activityEvents().stream().map(e->new AgentActivityEvent(e.occurredAt(),e.kind(),"["+role+"] "+e.message()))).sorted(java.util.Comparator.comparing(AgentActivityEvent::occurredAt).reversed()).limit(160).toList())));
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
