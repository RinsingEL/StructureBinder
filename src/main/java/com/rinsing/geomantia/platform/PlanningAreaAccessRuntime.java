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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Server-scoped cache and last-safe-position state for planning-area access. */
public final class PlanningAreaAccessRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long POLICY_CHECK_INTERVAL_TICKS = 100L;
    private static final long MESSAGE_COOLDOWN_TICKS = 60L;
    private static final double SAFE_POSITION_MARGIN_BLOCKS = 32.0D;
    private static final Map<MinecraftServer, ServerState> STATES = new IdentityHashMap<>();
    private static final ExecutorService POLICY_READER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "geomantia-access-reader");
        thread.setDaemon(true);
        return thread;
    });

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

    public static PlanningAreaAccessPolicy policySnapshot(ServerLevel level) {
        ServerState state=state(level.getServer());
        state.refreshIfNeeded(level.getGameTime());
        return state.policy;
    }

    public static boolean permitsPlayerTicket(ServerLevel level,int chunkX,int chunkZ) {
        return policySnapshot(level).permitsPlayerTicket(dimensionId(level),chunkX,chunkZ);
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
            if (state != null) {
                state.policy = null;
                // A queued snapshot from before explicit invalidation must never replace its successor.
                state.pendingRefresh = null;
            }
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
        private final Map<UUID, BoundaryCache> boundaries = new HashMap<>();

        private PlanningAreaAccessConfig config = PlanningAreaAccessConfig.defaults();
        private PlanningAreaAccessPolicy policy;
        private long lastPolicyCheckTick = Long.MIN_VALUE;
        private long loadedConfigStamp = Long.MIN_VALUE;
        private long loadedSourceStamp = Long.MIN_VALUE;
        private CompletableFuture<PolicyRefresh> pendingRefresh;
        private record PolicyRefresh(PlanningAreaAccessConfig config, PlanningAreaAccessPolicy policy,
                                     long configStamp, long sourceStamp) {}

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
            syncBoundary(player, gameTime, decision);
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
            if (pendingRefresh != null && pendingRefresh.isDone()) {
                PolicyRefresh refreshed = pendingRefresh.join();
                pendingRefresh = null;
                if (refreshed != null) {
                    config = refreshed.config();
                    policy = refreshed.policy();
                    loadedConfigStamp = refreshed.configStamp();
                    loadedSourceStamp = refreshed.sourceStamp();
                }
            }
            if (pendingRefresh != null) return;
            if (policy != null && gameTime - lastPolicyCheckTick < POLICY_CHECK_INTERVAL_TICKS) return;
            lastPolicyCheckTick = gameTime;
            if (policy != null) {
                long previousConfigStamp = loadedConfigStamp, previousSourceStamp = loadedSourceStamp;
                PlanningAreaAccessPolicy previousPolicy = policy;
                pendingRefresh = CompletableFuture.supplyAsync(
                        () -> {
                            PolicyRefresh next = readRefresh(previousConfigStamp, previousSourceStamp);
                            // Status reports may change without changing authority. Keep the existing
                            // ticket and boundary caches in that case, rather than restarting them on tick.
                            if (next != null && next.policy().sameAccessAs(previousPolicy))
                                return new PolicyRefresh(next.config(), previousPolicy, next.configStamp(), next.sourceStamp());
                            return next;
                        }, POLICY_READER);
                return;
            }
            // The first snapshot and explicit invalidation remain synchronous: never expose a
            // permissive temporary policy while the initial authority is still being loaded.
            PolicyRefresh initial = readRefresh(Long.MIN_VALUE, Long.MIN_VALUE);
            if (initial != null) {
                config = initial.config(); policy = initial.policy();
                loadedConfigStamp = initial.configStamp(); loadedSourceStamp = initial.sourceStamp();
            } else policy = new PlanningAreaAccessPolicy(debugRoot, config, PlanningAreaAccessPolicy.MOVEMENT_SAFETY_BLOCKS);
        }

        private PolicyRefresh readRefresh(long previousConfigStamp, long previousSourceStamp) {
            try {
                long configStamp = lastModified(configPath);
                long sourceStamp = PlanningAreaAccessPolicy.sourceStamp(debugRoot);
                int safetyBlocks = PlanningAreaAccessPolicy.MOVEMENT_SAFETY_BLOCKS;
                if (configStamp == previousConfigStamp && sourceStamp == previousSourceStamp) return null;
                PlanningAreaAccessConfig nextConfig = PlanningAreaAccessConfig.loadOrCreate(configPath);
                PlanningAreaAccessPolicy nextPolicy = new PlanningAreaAccessPolicy(debugRoot, nextConfig, safetyBlocks);
                return new PolicyRefresh(nextConfig, nextPolicy, configStamp, sourceStamp);
            } catch (IOException | RuntimeException exception) {
                LOGGER.error("Could not refresh planning-area access policy; retaining the last safe policy",
                        exception);
                return null;
            }
        }

        private void syncBoundary(ServerPlayer player, long tick, PlanningAreaAccessPolicy.Decision decision) {
            // Stagger players; reuse stationary geometry until the immutable policy changes.
            if (Math.floorMod(tick + player.getId(), 20) != 0) return;
            String dim=dimensionId(player.serverLevel());
            int x=Math.floorDiv(player.blockPosition().getX(),16),z=Math.floorDiv(player.blockPosition().getZ(),16);
            BoundaryCache previous=boundaries.get(player.getUUID());
            if(previous!=null && previous.policy==policy && previous.x==x && previous.z==z && previous.dimension.equals(dim)) {
                if(tick-previous.sentAt>=100) {
                    BoundaryNetwork.send(player,previous.segments);
                    boundaries.put(player.getUUID(),new BoundaryCache(policy,dim,x,z,tick,previous.segments));
                }
                return;
            }
            var lines="UNMANAGED_DIMENSION".equals(decision.reasonCode())
                    ? java.util.List.<com.rinsing.geomantia.systems.realm_planning.application.access.AccessBoundary.Segment>of()
                    : com.rinsing.geomantia.systems.realm_planning.application.access.AccessBoundary.sample(
                            player.getX(),player.getZ(),(px,pz)->policy.evaluate(dim,px,pz).allowed());
            boundaries.put(player.getUUID(),new BoundaryCache(policy,dim,x,z,tick,lines));
            BoundaryNetwork.send(player,lines);
        }

        private record BoundaryCache(PlanningAreaAccessPolicy policy,String dimension,int x,int z,long sentAt,
                java.util.List<com.rinsing.geomantia.systems.realm_planning.application.access.AccessBoundary.Segment> segments) {}

        private boolean readyForMessage(UUID playerId, long gameTime) {
            long next = nextMessageTick.getOrDefault(playerId, Long.MIN_VALUE);
            if (gameTime < next) return false;
            nextMessageTick.put(playerId, gameTime + MESSAGE_COOLDOWN_TICKS);
            return true;
        }

        private void forget(UUID playerId) {
            safePositions.keySet().removeIf(key -> key.playerId().equals(playerId));
            nextMessageTick.remove(playerId);
            boundaries.remove(playerId);
        }
    }

    private static SafePosition fallback(ServerLevel level, ServerPlayer player) {
        var initial=com.rinsing.geomantia.systems.realm_planning.application.access.InitialExplorationArea.fromDebugRoot(
                WorldScopedPlanningPaths.realmDebugRoot(level.getServer()),0);
        var spawn=level.getSharedSpawnPos();
        if(level.dimension()==net.minecraft.world.level.Level.OVERWORLD &&
                Math.hypot(spawn.getX()-initial.centerX(),spawn.getZ()-initial.centerZ())<=32)
            return new SafePosition(dimensionId(level),spawn.getX()+0.5D,spawn.getY(),spawn.getZ()+0.5D,player.getYRot(),player.getXRot());
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, initial.centerX(), initial.centerZ());
        return new SafePosition(dimensionId(level), initial.centerX()+0.5D, y, initial.centerZ()+0.5D, player.getYRot(), player.getXRot());
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
