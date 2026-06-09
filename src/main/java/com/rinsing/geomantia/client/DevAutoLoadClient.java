package com.rinsing.geomantia.client;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

// TEMP dev-only smoke-test helper. This is not a production client feature.
public final class DevAutoLoadClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SCREEN_STABLE_TICKS = 20;
    private static final int SMOKE_READY_TICKS = 60;
    private static boolean registered;
    private static boolean attemptedAutoLoad;
    private static boolean attemptedSmoke;
    private static String lastObservedScreenName = "";
    private static int observedScreenTicks;
    private static int smokeReadyTicks;

    private DevAutoLoadClient() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(DevAutoLoadClient.class);
        LOGGER.info("Geomantia temporary dev auto-load registered.");
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        runGisSmokeWhenReady();
        runAutoLoadWhenReady();
    }

    private static void runAutoLoadWhenReady() {
        if (attemptedAutoLoad) {
            return;
        }
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
            LOGGER.info("Geomantia dev auto-load observing screen: {}", screenName);
        }
        observedScreenTicks++;
        if (observedScreenTicks < SCREEN_STABLE_TICKS) {
            return;
        }
        attemptedAutoLoad = true;
        LOGGER.info("Geomantia dev auto-load world from screen {}: {}", screenName, targetWorld);
        minecraft.execute(() -> minecraft.createWorldOpenFlows().loadLevel(minecraft.screen, targetWorld));
    }

    private static void runGisSmokeWhenReady() {
        if (attemptedSmoke || !Boolean.getBoolean("geomantia.devGisSmoke")) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (minecraft.player == null || server == null) {
            smokeReadyTicks = 0;
            return;
        }
        smokeReadyTicks++;
        if (smokeReadyTicks < SMOKE_READY_TICKS) {
            return;
        }
        attemptedSmoke = true;
        UUID playerId = minecraft.player.getUUID();
        LOGGER.info("Geomantia temporary dev GIS smoke scheduling commands.");
        server.execute(() -> runGisSmokeCommands(server, playerId));
    }

    private static void runGisSmokeCommands(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            LOGGER.warn("Geomantia temporary dev GIS smoke skipped: player unavailable.");
            return;
        }
        CommandSourceStack source = player.createCommandSourceStack().withPermission(4);
        runCommand(server, source, "/geomantia gis refresh 8 prior");
        runCommand(server, source, "/geomantia gis test_run mixed");
    }

    private static void runCommand(MinecraftServer server, CommandSourceStack source, String command) {
        LOGGER.info("Geomantia temporary dev GIS smoke command: {}", command);
        server.getCommands().performPrefixedCommand(source, command);
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
}
