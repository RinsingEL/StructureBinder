package com.rinsing.geomantia.platform;

import com.rinsing.geomantia.api.planning.*;
import com.rinsing.geomantia.systems.provider.application.PlanningExtensionRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import java.util.ArrayList;

/** Collect only from installed Java addons; no global registry survives a world close. */
public final class PlanningExtensionRegistration {
    private PlanningExtensionRegistration() {}
    public static PlanningExtensionRegistry collect(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("PLANNING_EXTENSION_SERVER_THREAD_REQUIRED");
        var entries = new ArrayList<PlanningExtension>();
        boolean[] open = {true};
        try {
            MinecraftForge.EVENT_BUS.post(new RegisterPlanningExtensionsEvent(server, extension -> {
                if (!open[0] || !server.isSameThread())
                    throw new IllegalStateException("PLANNING_EXTENSION_REGISTRATION_CLOSED");
                entries.add(extension);
            }));
        } finally { open[0] = false; }
        return new PlanningExtensionRegistry(entries, work -> {
            if (server.isSameThread()) work.run(); else server.execute(work);
        });
    }
}
