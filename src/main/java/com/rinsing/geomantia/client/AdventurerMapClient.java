package com.rinsing.geomantia.client;

import com.rinsing.geomantia.platform.network.AdventurerMapNetwork;
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
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new AdventurerMapScreen(AdventurerMapSnapshot.empty()));
    }

    static void requestSnapshot(double zoom, double centerX, double centerZ) {
        AdventurerMapNetwork.requestSnapshot(zoom, centerX, centerZ);
    }

    public static void receiveRetryResult(String result) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof AdventurerMapScreen screen) screen.receiveRetryResult(result);
        else if (minecraft.player != null) minecraft.player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("gui.geomantia.adventurer_map.retry." + result), false);
    }

}
