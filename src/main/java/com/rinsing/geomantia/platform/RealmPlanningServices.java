package com.rinsing.geomantia.platform;

import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.systems.realm_planning.RealmPlanningService;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessConfig;
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
            return new RealmPlanningService(serverDirectory.resolve("realm_debug"), config);
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
