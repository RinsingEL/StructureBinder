package com.rinsing.geomantia.client;

import com.rinsing.geomantia.platform.network.AdventurerMapNetwork;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot;
import net.minecraft.client.Minecraft;

public final class AdventurerMapClient {
    private static AdventurerMapSnapshot lastSnapshot = AdventurerMapSnapshot.empty();

    private AdventurerMapClient() {
    }

    public static void receiveSnapshot(AdventurerMapSnapshot snapshot) {
        lastSnapshot = snapshot;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof AdventurerMapScreen screen) {
            screen.receiveSnapshot(snapshot);
        }
    }

    public static void openMap() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new AdventurerMapScreen(lastSnapshot));
    }

    static void requestSnapshot(double zoom) {
        AdventurerMapNetwork.requestSnapshot(zoom);
    }

}
