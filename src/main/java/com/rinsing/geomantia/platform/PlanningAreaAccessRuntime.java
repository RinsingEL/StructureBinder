package com.rinsing.geomantia.platform;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessConfig;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessPolicy;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Server-scoped cache and last-safe-position state for planning-area access. */
public final class PlanningAreaAccessRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long POLICY_CHECK_INTERVAL_TICKS = 100L;
    private static final long MESSAGE_COOLDOWN_TICKS = 60L;
    private static final double SAFE_POSITION_MARGIN_BLOCKS = 32.0D;
    private static final Map<MinecraftServer, ServerState> STATES = new IdentityHashMap<>();

    private PlanningAreaAccessRuntime() {
    }

    public static PlanningAreaAccessPolicy.Decision evaluate(ServerPlayer player, double blockX, double blockZ) {
        ServerState state = state(player.getServer());
        return state.evaluate(player, blockX, blockZ);
    }

    public static boolean permitsChunk(ServerLevel level, int chunkX, int chunkZ) {
        ServerState state = state(level.getServer());
        state.refreshIfNeeded(level.getGameTime());
        return state.policy.permitsChunk(dimensionId(level), chunkX, chunkZ);
    }

    public static void handleMovement(ServerPlayer player) {
        ServerState state = state(player.getServer());
        state.handleMovement(player);
    }

    public static void forget(ServerPlayer player) {
        synchronized (STATES) {
            ServerState state = STATES.get(player.getServer());
            if (state != null) state.forget(player.getUUID());
        }
    }

    public static void invalidate(MinecraftServer server) {
        synchronized (STATES) {
            ServerState state = STATES.get(server);
            if (state != null) state.policy = null;
        }
    }

    public static void clear(MinecraftServer server) {
        synchronized (STATES) {
            STATES.remove(server);
        }
    }

    private static ServerState state(MinecraftServer server) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(server, ServerState::new);
        }
    }

    private static final class ServerState {
        private final MinecraftServer server;
        private final Path configPath;
        private final Path debugRoot;
        private final Map<PlayerDimensionKey, SafePosition> safePositions = new HashMap<>();
        private final Map<UUID, Long> nextMessageTick = new HashMap<>();

        private PlanningAreaAccessConfig config = PlanningAreaAccessConfig.defaults();
        private PlanningAreaAccessPolicy policy;
        private long lastPolicyCheckTick = Long.MIN_VALUE;
        private long loadedConfigStamp = Long.MIN_VALUE;
        private long loadedSourceStamp = Long.MIN_VALUE;
        private int loadedViewSafetyBlocks = -1;

        private ServerState(MinecraftServer server) {
            this.server = server;
            Path serverDirectory = server.getServerDirectory().toPath().toAbsolutePath().normalize();
            this.configPath = serverDirectory.resolve("config").resolve("geomantia")
                    .resolve("planning_area_access.json");
            this.debugRoot = WorldScopedPlanningPaths.realmDebugRoot(server);
        }

        private PlanningAreaAccessPolicy.Decision evaluate(ServerPlayer player, double blockX, double blockZ) {
            refreshIfNeeded(player.serverLevel().getGameTime());
            return policy.evaluate(dimensionId(player.serverLevel()), blockX, blockZ);
        }

        private void handleMovement(ServerPlayer player) {
            long gameTime = player.serverLevel().getGameTime();
            PlanningAreaAccessPolicy.Decision decision = evaluate(player, player.getX(), player.getZ());
            if ("UNMANAGED_DIMENSION".equals(decision.reasonCode())) return;

            PlayerDimensionKey key = new PlayerDimensionKey(player.getUUID(), dimensionId(player.serverLevel()));
            if (decision.allowed()) {
                if (decision.clearanceBlocks() >= SAFE_POSITION_MARGIN_BLOCKS || !safePositions.containsKey(key)) {
                    safePositions.put(key, SafePosition.capture(player));
                }
                if (decision.clearanceBlocks() <= PlanningAreaAccessConfig.DEFAULT_BOUNDARY_WARNING_DISTANCE_BLOCKS
                        && readyForMessage(player.getUUID(), gameTime)) {
                    player.displayClientMessage(Component.literal(
                            "[Geomantia] 前方尚未开放，继续前进将被送回安全区域。")
                            .withStyle(ChatFormatting.GOLD), true);
                }
                return;
            }

            SafePosition destination = safePositions.get(key);
            if (destination == null || !policy.evaluate(destination.dimensionId(), destination.x(), destination.z()).allowed())
                destination = fallback(player.serverLevel(), player);
            returnToSafety(player, destination);
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, true, false, true));
            if (readyForMessage(player.getUUID(), gameTime)) {
                player.displayClientMessage(Component.literal(
                        "[Geomantia] 该区域尚未完成规划，你已被送回最近的安全位置。")
                        .withStyle(ChatFormatting.RED), true);
            }
        }

        private void refreshIfNeeded(long gameTime) {
            if (policy != null && gameTime - lastPolicyCheckTick < POLICY_CHECK_INTERVAL_TICKS) return;
            lastPolicyCheckTick = gameTime;
            long configStamp = lastModified(configPath);
            long sourceStamp = PlanningAreaAccessPolicy.sourceStamp(debugRoot);
            int viewSafetyBlocks = (Math.max(2, server.getPlayerList().getViewDistance()) + 12) * 16;
            if (policy != null && configStamp == loadedConfigStamp && sourceStamp == loadedSourceStamp
                    && viewSafetyBlocks == loadedViewSafetyBlocks) return;
            try {
                config = PlanningAreaAccessConfig.loadOrCreate(configPath);
                policy = new PlanningAreaAccessPolicy(debugRoot, config, viewSafetyBlocks);
                loadedConfigStamp = lastModified(configPath);
                loadedSourceStamp = sourceStamp;
                loadedViewSafetyBlocks = viewSafetyBlocks;
            } catch (IOException | RuntimeException exception) {
                LOGGER.error("Could not refresh planning-area access policy; retaining the last safe policy",
                        exception);
                if (policy == null) policy = new PlanningAreaAccessPolicy(debugRoot, config, viewSafetyBlocks);
            }
        }

        private boolean readyForMessage(UUID playerId, long gameTime) {
            long next = nextMessageTick.getOrDefault(playerId, Long.MIN_VALUE);
            if (gameTime < next) return false;
            nextMessageTick.put(playerId, gameTime + MESSAGE_COOLDOWN_TICKS);
            return true;
        }

        private void forget(UUID playerId) {
            safePositions.keySet().removeIf(key -> key.playerId().equals(playerId));
            nextMessageTick.remove(playerId);
        }
    }

    private static SafePosition fallback(ServerLevel level, ServerPlayer player) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0) + 1;
        return new SafePosition(dimensionId(level), 0.5D, y, 0.5D, player.getYRot(), player.getXRot());
    }

    private static void returnToSafety(ServerPlayer player, SafePosition destination) {
        Entity rootVehicle = player.getRootVehicle();
        if (rootVehicle != player) {
            player.stopRiding();
            rootVehicle.setDeltaMovement(Vec3.ZERO);
            rootVehicle.teleportTo(destination.x(), destination.y(), destination.z());
        }
        player.setDeltaMovement(Vec3.ZERO);
        ServerLevel destinationLevel = level(player.getServer(), destination.dimensionId());
        if (destinationLevel == null || destinationLevel != player.serverLevel()) {
            destinationLevel = player.serverLevel();
        }
        player.teleportTo(destinationLevel, destination.x(), destination.y(), destination.z(),
                destination.yRot(), destination.xRot());
    }

    private static ServerLevel level(MinecraftServer server, String dimensionId) {
        ResourceLocation location = ResourceLocation.tryParse(dimensionId);
        if (location == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    private static String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static long lastModified(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private record PlayerDimensionKey(UUID playerId, String dimensionId) {
    }

    private record SafePosition(String dimensionId, double x, double y, double z, float yRot, float xRot) {
        static SafePosition capture(ServerPlayer player) {
            return new SafePosition(PlanningAreaAccessRuntime.dimensionId(player.serverLevel()),
                    player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        }
    }
}
