package com.rinsing.geomantia.map.client;
import com.rinsing.geomantia.systems.provider.application.*;

import com.rinsing.geomantia.map.platform.network.AdventurerMapNetwork;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot;
import net.minecraft.client.Minecraft;

public final class AdventurerMapClient {
    private AdventurerMapClient() {
    }

    public static void receiveSnapshot(AdventurerMapSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof AdventurerMapScreen screen) {
            screen.receiveSnapshot(snapshot);
        }
    }

    public static void openMap() {
        openMap(Double.NaN,Double.NaN,1);
    }
    public static void openMap(double centerX,double centerZ,double zoom) {
        Minecraft minecraft = Minecraft.getInstance();
        var screen=new AdventurerMapScreen(AdventurerMapSnapshot.empty());
        screen.initialView(centerX,centerZ,zoom);
        minecraft.setScreen(screen);
    }

    static void requestSnapshot(double zoom, double centerX, double centerZ, boolean debug) {
        AdventurerMapNetwork.requestSnapshot(zoom, centerX, centerZ, debug);
    }

    public static void receiveRetryResult(String result) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof AdventurerMapScreen screen) screen.receiveRetryResult(result);
        else if (minecraft.player != null) minecraft.player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("gui.geomantia.adventurer_map.retry." + result), false);
    }

}
