package com.rinsing.geomantia.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.platform.network.AdventurerMapNetwork;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

public final class AdventurerMapClient {
    static final KeyMapping OPEN_MAP = new KeyMapping(
            "key.geomantia.adventurer_map",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.geomantia");
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

    static void openMap() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new AdventurerMapScreen(lastSnapshot));
    }

    static void requestSnapshot() {
        AdventurerMapNetwork.requestSnapshot();
    }

    @Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MAP);
        }
    }

    @Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID, value = Dist.CLIENT)
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft minecraft = Minecraft.getInstance();
            while (OPEN_MAP.consumeClick()) {
                if (minecraft.player != null) openMap();
            }
        }
    }
}
