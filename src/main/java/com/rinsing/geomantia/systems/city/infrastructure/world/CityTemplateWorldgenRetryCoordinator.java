package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.GeomantiaMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates delayed writes that are still part of a proven first-generation transaction. */
@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class CityTemplateWorldgenRetryCoordinator {
    private static final int RETRY_INTERVAL_TICKS = 20;
    private static final int MAX_UNLOADED_TICKS = 200;
    private static final Map<RetryKey, Integer> RETRIES = new ConcurrentHashMap<>();

    private CityTemplateWorldgenRetryCoordinator() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkPos chunkPos = event.getChunk().getPos();
        if (!CityReservationMaskRegistry.pendingTemplateStructuresForChunk(chunkPos).isEmpty()) {
            RETRIES.putIfAbsent(new RetryKey(level, chunkPos.toLong()), 0);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || RETRIES.isEmpty()) {
            return;
        }
        for (Map.Entry<RetryKey, Integer> entry : RETRIES.entrySet()) {
            RetryKey key = entry.getKey();
            int age = entry.getValue() + 1;
            LevelChunk chunk = key.level().getChunkSource().getChunkNow(
                    ChunkPos.getX(key.chunkPos()), ChunkPos.getZ(key.chunkPos()));
            if (chunk == null) {
                if (age >= MAX_UNLOADED_TICKS) {
                    RETRIES.remove(key);
                } else {
                    RETRIES.replace(key, entry.getValue(), age);
                }
                continue;
            }
            if (age == 1 || age % RETRY_INTERVAL_TICKS == 0) {
                MinecraftCityWorldgenStructurePlacer.retryPendingTemplateStructures(key.level(), chunk);
            }
            if (CityReservationMaskRegistry.pendingTemplateStructuresForChunk(chunk.getPos()).isEmpty()) {
                RETRIES.remove(key);
            } else {
                RETRIES.replace(key, entry.getValue(), age);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RETRIES.clear();
    }

    private record RetryKey(ServerLevel level, long chunkPos) {
    }
}
