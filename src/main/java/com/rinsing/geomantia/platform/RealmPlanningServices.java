package com.rinsing.geomantia.platform;

import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.systems.realm_planning.RealmPlanningService;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
            return SERVICES.computeIfAbsent(server, ignored -> new RealmPlanningService(
                    server.getServerDirectory().toPath().resolve("realm_debug")));
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (SERVICES) {
            SERVICES.remove(event.getServer());
        }
    }
}
