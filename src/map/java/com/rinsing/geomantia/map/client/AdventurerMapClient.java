package com.rinsing.geomantia.map.client;
import com.rinsing.geomantia.systems.provider.application.*;

import com.rinsing.geomantia.map.platform.network.AdventurerMapNetwork;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot;
import net.minecraft.client.Minecraft;

@net.minecraftforge.fml.common.Mod.EventBusSubscriber(
        modid = "geomantia_map", value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class AdventurerMapClient {
    private static long nextRequestId;
    private static net.minecraft.client.multiplayer.ClientPacketListener cachedConnection;
    private static net.minecraft.client.multiplayer.ClientLevel cachedLevel;
    private static AdventurerMapSnapshot cachedSnapshot;
    private static long cachedAt;

    private AdventurerMapClient() {
    }

    public static void receiveSnapshot(long requestId, AdventurerMapSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof AdventurerMapScreen screen) {
            screen.receiveSnapshot(requestId, snapshot);
        }
    }

    public static void openMap() {
        openMap(Double.NaN,Double.NaN,1);
    }
    public static void openMap(double centerX,double centerZ,double zoom) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean reusable = minecraft.getConnection() == cachedConnection && minecraft.level == cachedLevel
                && cachedSnapshot != null && System.nanoTime() - cachedAt < 5_000_000_000L;
        var screen=new AdventurerMapScreen(reusable ? cachedSnapshot : null);
        if (!reusable) cachedSnapshot = null;
        screen.initialView(centerX,centerZ,zoom);
        minecraft.setScreen(screen);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        cachedConnection = null;
        cachedLevel = null;
        cachedSnapshot = null;
        cachedAt = 0;
    }

    static long nextRequestId() { return ++nextRequestId; }

    static void cacheSnapshot(AdventurerMapSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        cachedConnection = minecraft.getConnection();
        cachedLevel = minecraft.level;
        cachedSnapshot = snapshot;
        cachedAt = System.nanoTime();
    }

    static void requestSnapshot(long requestId, double zoom, double centerX, double centerZ, boolean debug) {
        AdventurerMapNetwork.requestSnapshot(requestId, zoom, centerX, centerZ, debug);
    }

    public static void receiveRetryResult(String result) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof AdventurerMapScreen screen) screen.receiveRetryResult(result);
        else if (minecraft.player != null) minecraft.player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("gui.geomantia.adventurer_map.retry." + result), false);
    }

}
