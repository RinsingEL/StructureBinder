package com.rinsing.geomantia.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class DevAutoLoadClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SCREEN_STABLE_TICKS = 20;
    private static boolean registered;
    private static boolean attemptedAutoLoad;
    private static boolean attemptedAutoTeleport;
    private static int playerReadyTicks;
    private static String lastObservedScreenName = "";
    private static int observedScreenTicks;

    private DevAutoLoadClient() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(DevAutoLoadClient.class);
        LOGGER.info("Geomantia development auto-load registered.");
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tryAutoTeleport();
        if (attemptedAutoLoad) return;
        String targetWorld = decodeTargetWorld();
        if (targetWorld.isBlank()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null || minecraft.getSingleplayerServer() != null) {
            attemptedAutoLoad = true;
            return;
        }
        if (minecraft.screen == null) {
            lastObservedScreenName = "";
            observedScreenTicks = 0;
            return;
        }

        String screenName = minecraft.screen.getClass().getName();
        if (!screenName.equals(lastObservedScreenName)) {
            lastObservedScreenName = screenName;
            observedScreenTicks = 0;
            LOGGER.info("Geomantia development auto-load observing screen: {}", screenName);
        }
        observedScreenTicks++;
        if (observedScreenTicks < SCREEN_STABLE_TICKS) {
            return;
        }

        attemptedAutoLoad = true;
        LOGGER.info("Geomantia development auto-load world from screen {}: {}", screenName, targetWorld);
        minecraft.execute(() -> loadLevelWithConfirmedWarning(minecraft, targetWorld));
    }

    private static void tryAutoTeleport() {
        if (attemptedAutoTeleport) return;
        String encoded = System.getProperty("geomantia.devAutoTeleport", "").trim();
        if (encoded.isBlank()) return;
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (minecraft.player == null || server == null) {
            playerReadyTicks = 0;
            return;
        }
        if (++playerReadyTicks < autoTeleportDelayTicks()) return;
        attemptedAutoTeleport = true;
        String[] parts = encoded.split(",", -1);
        if (parts.length != 3) {
            LOGGER.error("Invalid geomantia.devAutoTeleport value {}; expected x,y,z.", encoded);
            return;
        }
        try {
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            double z = Double.parseDouble(parts[2].trim());
            java.util.UUID playerId = minecraft.player.getUUID();
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) return;
                player.setInvulnerable(true);
                player.teleportTo(player.serverLevel(), x, y, z, player.getYRot(), player.getXRot());
                LOGGER.info("Geomantia development auto-teleported player {} to {},{},{}.",
                        player.getGameProfile().getName(), x, y, z);
            });
        } catch (NumberFormatException ex) {
            LOGGER.error("Invalid geomantia.devAutoTeleport value {}; expected numeric x,y,z.", encoded, ex);
        }
    }

    private static void loadLevelWithConfirmedWarning(Minecraft minecraft, String targetWorld) {
        Screen screen = minecraft.screen;
        Object openFlows = minecraft.createWorldOpenFlows();
        try {
            Method doLoadLevel = openFlows.getClass()
                    .getDeclaredMethod("doLoadLevel", Screen.class, String.class, boolean.class, boolean.class,
                            boolean.class);
            doLoadLevel.setAccessible(true);
            doLoadLevel.invoke(openFlows, screen, targetWorld, false, true, true);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            LOGGER.warn("Geomantia development auto-load confirmed warning path failed; falling back to loadLevel.",
                    ex);
            minecraft.createWorldOpenFlows().loadLevel(screen, targetWorld);
        }
    }

    private static String decodeTargetWorld() {
        String encoded = System.getProperty("geomantia.devAutoLoadWorldBase64", "").trim();
        if (!encoded.isBlank()) {
            try {
                return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException ex) {
                LOGGER.warn("Invalid geomantia.devAutoLoadWorldBase64 value.", ex);
            }
        }
        return System.getProperty("geomantia.devAutoLoadWorld", "").trim();
    }

    private static int autoTeleportDelayTicks() {
        String configured = System.getProperty("geomantia.devAutoTeleportDelayTicks", "40").trim();
        try {
            return Math.max(1, Integer.parseInt(configured));
        } catch (NumberFormatException ex) {
            LOGGER.warn("Invalid geomantia.devAutoTeleportDelayTicks value {}; using 40.", configured);
            return 40;
        }
    }
}
