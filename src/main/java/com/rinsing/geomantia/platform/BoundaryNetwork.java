package com.rinsing.geomantia.platform;

import com.rinsing.geomantia.client.BoundaryVeil;
import com.rinsing.geomantia.systems.realm_planning.application.access.AccessBoundary.Segment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;
import java.util.function.Supplier;

public final class BoundaryNetwork {
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath("geomantia", "access_boundary"), () -> "1", "1"::equals, "1"::equals);
    public static void register() {
        CHANNEL.registerMessage(0, Snapshot.class, Snapshot::encode, Snapshot::decode, Snapshot::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public static void send(ServerPlayer player, List<Segment> segments) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new Snapshot(player.level().dimension().location().toString(), segments));
    }
    private record Snapshot(String dimension, List<Segment> segments) {
        static void encode(Snapshot value, FriendlyByteBuf buffer) {
            buffer.writeUtf(value.dimension,256);
            buffer.writeVarInt(value.segments.size());
            for (Segment s:value.segments) {
                buffer.writeDouble(s.x1()); buffer.writeDouble(s.z1());
                buffer.writeDouble(s.x2()); buffer.writeDouble(s.z2());
            }
        }
        static Snapshot decode(FriendlyByteBuf buffer) {
            String dimension=buffer.readUtf(256);
            int size=buffer.readVarInt();
            if(size<0 || size>1152) throw new IllegalArgumentException("Invalid boundary size");
            List<Segment> segments=new ArrayList<>(size);
            for(int i=0;i<size;i++) segments.add(new Segment(buffer.readDouble(),buffer.readDouble(),buffer.readDouble(),buffer.readDouble()));
            return new Snapshot(dimension,List.copyOf(segments));
        }
        static void handle(Snapshot value, Supplier<NetworkEvent.Context> supplier) {
            var context=supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> BoundaryVeil.receive(value.dimension,value.segments)));
            context.setPacketHandled(true);
        }
    }
}
