package com.rinsing.geomantia.platform.http;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.platform.WorldScopedPlanningPaths;
import com.rinsing.geomantia.systems.provider.application.PlayerProviderService;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class GeomantiaHttpServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_PORT = 5000;
    private static HttpServer httpServer;
    private static ExecutorService httpExecutor;
    private static RealmPlanningHttpController activeRealmController;

    private GeomantiaHttpServer() {
    }

    public static synchronized String retryCityFromMap(net.minecraft.server.level.ServerPlayer player,
                                                       String runId, String cityId) {
        if (!player.hasPermissions(2) && !player.server.isSingleplayerOwner(player.getGameProfile()))
            return "no_permission";
        if (activeRealmController == null) return "unavailable";
        try {
            return activeRealmController.retryCityFromMap(player.server, runId, cityId) ? "submitted" : "state_changed";
        } catch (IllegalArgumentException exception) {
            return "state_changed";
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Map city retry failed: {}", exception.getClass().getSimpleName());
            return "failed";
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        stop();
    }

    private static synchronized void start(MinecraftServer minecraftServer) {
        if (httpServer != null) {
            return;
        }
        int port = Integer.getInteger("geomantia.apiPort", DEFAULT_PORT);
        HttpServer createdServer = null;
        ExecutorService createdExecutor = null;
        RealmPlanningHttpController createdRealmController = null;
        try {
            createdServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            GisHttpController controller = new GisHttpController(minecraftServer);
            createdRealmController = new RealmPlanningHttpController(minecraftServer);
            RealmPlanningHttpController realmController = createdRealmController;
            createdServer.createContext("/gis/status", controller::handleStatus);
            createdServer.createContext("/gis/refresh", controller::handleRefresh);
            createdServer.createContext("/gis/test_run", controller::handleTestRun);
            createdServer.createContext("/gis/chunk_generation_benchmark/start",
                    controller::handleChunkGenerationBenchmarkStart);
            createdServer.createContext("/gis/chunk_generation_benchmark/status",
                    controller::handleChunkGenerationBenchmarkStatus);
            createdServer.createContext("/realm/status", realmController::handleStatus);
            createdServer.createContext("/realm/w/refresh", realmController::handleWRefresh);
            createdServer.createContext("/realm/t1/prepare", realmController::handleT1Prepare);
            createdServer.createContext("/realm/t2/select_coordinate", realmController::handleT2SelectCoordinate);
            createdServer.createContext("/realm/t3/expand", realmController::handleT3Expand);
            createdServer.createContext("/realm/t4/build_registry", realmController::handleT4BuildRegistry);
            createdServer.createContext("/realm/t4/patch_planning/create", realmController::handleT4PatchPlanningCreate);
            createdServer.createContext("/realm/t4/patch_planning/select_capital", realmController::handleT4PatchPlanningSelectCapital);
            createdServer.createContext("/realm/t4/patch_planning/add_city", realmController::handleT4PatchPlanningAddCity);
            createdServer.createContext("/realm/t4/patch_planning/finalize", realmController::handleT4PatchPlanningFinalize);
            createdServer.createContext("/realm/patch_explorer/open", realmController::handlePatchExplorerOpen);
            createdServer.createContext("/realm/patch_explorer/show_candidates", realmController::handlePatchExplorerShowCandidates);
            createdServer.createContext("/realm/patch_explorer/select_candidate", realmController::handlePatchExplorerSelectCandidate);
            createdServer.createContext("/realm/acceptance/run", realmController::handleAcceptance);
            createdServer.createContext("/realm/tag_audit", realmController::handleTagAudit);
            createdServer.createContext("/realm/debug/command", realmController::handleDebugCommand);
            createdServer.createContext("/realm/city/plan_d2", realmController::handleCityPlanD2);
            createdServer.createContext("/realm/city/design_queue/refresh",
                    realmController::handleCityDesignQueueRefresh);
            createdServer.createContext("/realm/city/design_queue/status",
                    realmController::handleCityDesignQueueStatus);
            createdServer.createContext("/realm/city/plan_d3", realmController::handleCityPlanD3);
            createdServer.createContext("/realm/city/review_d3_site", realmController::handleCityReviewD3Site);
            createdServer.createContext("/realm/city/prepare_d4_blueprint_context",
                    realmController::handleCityPrepareD4BlueprintContext);
            createdServer.createContext("/realm/city/submit_d4_blueprint",
                    realmController::handleCitySubmitD4Blueprint);
            createdServer.createContext("/realm/city/post_d4_auto_compile_status",
                    realmController::handleCityPostD4AutoCompileStatus);
            createdServer.createContext("/realm/city/post_d4_auto_compile_retry",
                    realmController::handleCityPostD4AutoCompileRetry);
            createdServer.createContext("/realm/city/compile_d4_blueprint",
                    realmController::handleCityCompileD4Blueprint);
            createdServer.createContext("/realm/city/plan_d4_candidates", realmController::handleCityPlanD4Candidates);
            createdServer.createContext("/realm/city/plan_d4_array_candidates", realmController::handleCityPlanD4ArrayCandidates);
            createdServer.createContext("/realm/city/create_d4_design_loop_state", realmController::handleCityCreateD4DesignLoopState);
            createdServer.createContext("/realm/city/read_d4_design_loop_state", realmController::handleCityReadD4DesignLoopState);
            createdServer.createContext("/realm/city/append_d4_design_loop_round", realmController::handleCityAppendD4DesignLoopRound);
            createdServer.createContext("/realm/city/write_d4_design_loop_state", realmController::handleCityWriteD4DesignLoopState);
            createdServer.createContext("/realm/city/create_d4_array_layout_loop", realmController::handleCityCreateD4ArrayLayoutLoop);
            createdServer.createContext("/realm/city/execute_d4_array_layout_item", realmController::handleCityExecuteD4ArrayLayoutItem);
            createdServer.createContext("/realm/city/query_d4_array_expansion_space", realmController::handleCityQueryD4ArrayExpansionSpace);
            createdServer.createContext("/realm/city/plan_d4_array_expansion_candidates", realmController::handleCityPlanD4ArrayExpansionCandidates);
            createdServer.createContext("/realm/city/select_d4_array_expansion_candidate", realmController::handleCitySelectD4ArrayExpansionCandidate);
            createdServer.createContext("/realm/city/finalize_d4_array_layout_loop", realmController::handleCityFinalizeD4ArrayLayoutLoop);
            createdServer.createContext("/realm/city/query_structure_catalog", realmController::handleCityQueryStructureCatalog);
            createdServer.createContext("/realm/city/query_template_metadata", realmController::handleCityQueryTemplateMetadata);
            createdServer.createContext("/realm/city/plan_d4_structure_cluster_groups", realmController::handleCityPlanD4StructureClusterGroups);
            createdServer.createContext("/realm/city/select_d4_candidates", realmController::handleCitySelectD4Candidates);
            createdServer.createContext("/realm/city/select_d4_structure_cluster_group", realmController::handleCitySelectD4StructureClusterGroup);
            createdServer.createContext("/realm/city/create_d4_candidate_session", realmController::handleCityCreateD4CandidateSession);
            createdServer.createContext("/realm/city/plan_d4_next_candidates", realmController::handleCityPlanD4NextCandidates);
            createdServer.createContext("/realm/city/select_d4_candidate", realmController::handleCitySelectD4Candidate);
            createdServer.createContext("/realm/city/finalize_d4_candidate_session", realmController::handleCityFinalizeD4CandidateSession);
            createdServer.createContext("/realm/city/plan_d4", realmController::handleCityPlanD4);
            createdServer.createContext("/realm/city/plan_d5", realmController::handleCityPlanD5);
            createdServer.createContext("/realm/city/execute_d5", realmController::handleCityExecuteD5);
            createdServer.createContext("/realm/city/plan_d6", realmController::handleCityPlanD6);
            createdServer.createContext("/realm/city/plan_land_use", realmController::handleCityPlanLandUse);
            createdServer.createContext("/realm/city/execute_d7", realmController::handleCityExecuteD7);
            createdServer.createContext("/realm/city/query_worldgen_observations",
                    realmController::handleCityQueryWorldgenObservations);
            createdServer.createContext("/realm/city/plan_city_walls", realmController::handleCityPlanCityWalls);
            createdServer.createContext("/realm/city/execute_city_walls", realmController::handleCityExecuteCityWalls);
            createdServer.createContext("/realm/city/run_workflow", realmController::handleCityRunWorkflow);
            createdExecutor = Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                thread.setName("Geomantia-API");
                return thread;
            });
            createdServer.setExecutor(createdExecutor);
            createdServer.start();
            httpServer = createdServer;
            httpExecutor = createdExecutor;
            activeRealmController = realmController;
            PlayerProviderService.instance().startAutomation(
                    minecraftServer.getServerDirectory().toPath(),
                    WorldScopedPlanningPaths.realmDebugRoot(minecraftServer), port,
                    minecraftServer.overworld().getSeed());
            LOGGER.info("Geomantia GIS API server started on 127.0.0.1:{}.", port);
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Failed to start Geomantia GIS API server.", ex);
            if (createdServer != null) {
                createdServer.stop(0);
            }
            shutdownExecutor(createdExecutor);
            if (createdRealmController != null) {
                createdRealmController.close();
            }
            httpServer = null;
            httpExecutor = null;
            activeRealmController = null;
        }
    }

    private static synchronized void stop() {
        // Signal world-bound workers before waiting for any provider/runtime shutdown.
        if (activeRealmController != null) {
            activeRealmController.close();
        }
        PlayerProviderService.instance().stopAutomation();
        HttpServer server = httpServer;
        ExecutorService executor = httpExecutor;
        RealmPlanningHttpController realmController = activeRealmController;
        httpServer = null;
        httpExecutor = null;
        activeRealmController = null;
        if (server == null && executor == null && realmController == null) {
            return;
        }
        if (server != null) {
            server.stop(0);
        }
        shutdownExecutor(executor);
        LOGGER.info("Geomantia GIS API server stopped.");
    }

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
