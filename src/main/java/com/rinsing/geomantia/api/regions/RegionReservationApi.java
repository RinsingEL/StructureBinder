package com.rinsing.geomantia.api.regions;

import com.rinsing.geomantia.platform.PlanningAreaAccessRuntime;
import com.rinsing.geomantia.platform.WorldScopedPlanningPaths;
import com.rinsing.geomantia.systems.realm_planning.application.reservation.RegionReservationStore;
import net.minecraft.server.MinecraftServer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Public addon API. Calls use the current server's save, on its server thread. */
public final class RegionReservationApi {
    public static final int VERSION = 1;
    private RegionReservationApi() {}

    public static List<ReservedRegion> regions(MinecraftServer server, String runId) throws IOException {
        return RegionReservationStore.read(run(server, runId)).regions();
    }

    public static boolean isReserved(MinecraftServer server, String runId, String dimensionId, RegionBounds bounds) throws IOException {
        var snapshot = RegionReservationStore.read(run(server, runId));
        return snapshot.dimensionId().equals(dimensionId) && snapshot.overlaps(bounds);
    }

    public static boolean isGenerationReady(MinecraftServer server, String runId, String regionId) throws IOException {
        return RegionReservationStore.read(run(server, runId)).readyIds().contains(regionId);
    }

    /** Call only after the addon has durably prepared/activated all worldgen plans. Not a story-stage flag. */
    public static void markGenerationReady(MinecraftServer server, String runId, String regionId) throws IOException {
        RegionReservationStore.markReady(run(server, runId), regionId);
        PlanningAreaAccessRuntime.invalidate(server);
    }

    private static Path run(MinecraftServer server, String runId) {
        if (!server.isSameThread()) throw new IllegalStateException("ADDON_REGION_REQUIRES_SERVER_THREAD");
        if (runId == null || !runId.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("ADDON_REGION_RUN_ID_INVALID");
        return WorldScopedPlanningPaths.realmDebugRoot(server).resolve(runId);
    }
}
