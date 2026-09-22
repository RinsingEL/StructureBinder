package com.rinsing.geomantia.platform;

import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.systems.realm_planning.RealmPlanningService;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessConfig;
import com.rinsing.geomantia.api.regions.RegionReservationEvent;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkStatusPreflight;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class RealmPlanningServices {
    private static final Map<MinecraftServer, RealmPlanningService> SERVICES = new IdentityHashMap<>();

    private RealmPlanningServices() {
    }

    public static RealmPlanningService forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        synchronized (SERVICES) {
            return SERVICES.computeIfAbsent(server, RealmPlanningServices::createService);
        }
    }

    private static RealmPlanningService createService(MinecraftServer server) {
        try {
            var serverDirectory = server.getServerDirectory().toPath();
            PlanningAreaAccessConfig config = PlanningAreaAccessConfig.loadOrCreate(serverDirectory
                    .resolve("config").resolve("geomantia").resolve("planning_area_access.json"));
            return new RealmPlanningService(WorldScopedPlanningPaths.realmDebugRoot(server), config, (context, registrar) -> {
                if (!server.isSameThread()) throw new IllegalStateException("ADDON_REGION_REQUIRES_SERVER_THREAD");
                var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(context.dimensionId())));
                if (level == null) throw new IllegalArgumentException("ADDON_REGION_DIMENSION_UNAVAILABLE");
                var probe = new CityLandUseChunkStatusPreflight.MinecraftChunkStatusProbe(level);
                var accepted = new java.util.ArrayList<com.rinsing.geomantia.api.regions.ReservedRegion>();
                MinecraftForge.EVENT_BUS.post(new RegionReservationEvent(server, context, region -> {
                    if (!server.isSameThread()) throw new IllegalStateException("ADDON_REGION_REQUIRES_SERVER_THREAD");
                    registrar.accept(region);
                    accepted.add(region);
                }));
                // Validate size/coverage before disk probing, and publish nothing if any probe fails.
                for (var region : accepted) {
                    for (var bounds : region.mask())
                        for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ(), 16); z++)
                            for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX(), 16); x++) {
                                var evidence = probe.inspect(x, z);
                                if (evidence.state() != CityLandUseChunkStatusPreflight.EvidenceState.NOT_PRESENT)
                                    throw new IllegalArgumentException("ADDON_REGION_ALREADY_GENERATED_OR_UNKNOWN: " + region.id()
                                            + " chunk=" + x + "," + z + " status=" + evidence.statusName());
                            }
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load planning area access config", exception);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (SERVICES) {
            SERVICES.remove(event.getServer());
        }
    }
}
