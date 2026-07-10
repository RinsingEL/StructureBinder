package com.rinsing.geomantia.platform.http;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.GeomantiaMod;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class GeomantiaHttpServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_PORT = 5000;
    private static HttpServer httpServer;

    private GeomantiaHttpServer() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        stop();
    }

    private static void start(MinecraftServer minecraftServer) {
        if (httpServer != null) {
            return;
        }
        int port = Integer.getInteger("geomantia.apiPort", DEFAULT_PORT);
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            GisHttpController controller = new GisHttpController(minecraftServer);
            RealmPlanningHttpController realmController = new RealmPlanningHttpController(minecraftServer);
            httpServer.createContext("/gis/status", controller::handleStatus);
            httpServer.createContext("/gis/refresh", controller::handleRefresh);
            httpServer.createContext("/gis/test_run", controller::handleTestRun);
            httpServer.createContext("/realm/status", realmController::handleStatus);
            httpServer.createContext("/realm/w/refresh", realmController::handleWRefresh);
            httpServer.createContext("/realm/t1/prepare", realmController::handleT1Prepare);
            httpServer.createContext("/realm/t2/select_coordinate", realmController::handleT2SelectCoordinate);
            httpServer.createContext("/realm/t3/expand", realmController::handleT3Expand);
            httpServer.createContext("/realm/t4/build_registry", realmController::handleT4BuildRegistry);
            httpServer.createContext("/realm/acceptance/run", realmController::handleAcceptance);
            httpServer.createContext("/realm/tag_audit", realmController::handleTagAudit);
            httpServer.createContext("/realm/debug/command", realmController::handleDebugCommand);
            httpServer.createContext("/realm/city/plan_d2", realmController::handleCityPlanD2);
            httpServer.createContext("/realm/city/plan_d3", realmController::handleCityPlanD3);
            httpServer.createContext("/realm/city/profile_structure_envelopes", realmController::handleCityProfileStructureEnvelopes);
            httpServer.createContext("/realm/city/plan_d4_candidates", realmController::handleCityPlanD4Candidates);
            httpServer.createContext("/realm/city/plan_d4_array_candidates", realmController::handleCityPlanD4ArrayCandidates);
            httpServer.createContext("/realm/city/create_d4_design_loop_state", realmController::handleCityCreateD4DesignLoopState);
            httpServer.createContext("/realm/city/read_d4_design_loop_state", realmController::handleCityReadD4DesignLoopState);
            httpServer.createContext("/realm/city/append_d4_design_loop_round", realmController::handleCityAppendD4DesignLoopRound);
            httpServer.createContext("/realm/city/write_d4_design_loop_state", realmController::handleCityWriteD4DesignLoopState);
            httpServer.createContext("/realm/city/create_d4_array_layout_loop", realmController::handleCityCreateD4ArrayLayoutLoop);
            httpServer.createContext("/realm/city/execute_d4_array_layout_item", realmController::handleCityExecuteD4ArrayLayoutItem);
            httpServer.createContext("/realm/city/query_d4_array_expansion_space", realmController::handleCityQueryD4ArrayExpansionSpace);
            httpServer.createContext("/realm/city/plan_d4_array_expansion_candidates", realmController::handleCityPlanD4ArrayExpansionCandidates);
            httpServer.createContext("/realm/city/select_d4_array_expansion_candidate", realmController::handleCitySelectD4ArrayExpansionCandidate);
            httpServer.createContext("/realm/city/finalize_d4_array_layout_loop", realmController::handleCityFinalizeD4ArrayLayoutLoop);
            httpServer.createContext("/realm/city/plan_city_dressing", realmController::handleCityPlanDressing);
            httpServer.createContext("/realm/city/plan_d4_structure_cluster_groups", realmController::handleCityPlanD4StructureClusterGroups);
            httpServer.createContext("/realm/city/select_d4_candidates", realmController::handleCitySelectD4Candidates);
            httpServer.createContext("/realm/city/select_d4_structure_cluster_group", realmController::handleCitySelectD4StructureClusterGroup);
            httpServer.createContext("/realm/city/create_d4_candidate_session", realmController::handleCityCreateD4CandidateSession);
            httpServer.createContext("/realm/city/plan_d4_next_candidates", realmController::handleCityPlanD4NextCandidates);
            httpServer.createContext("/realm/city/select_d4_candidate", realmController::handleCitySelectD4Candidate);
            httpServer.createContext("/realm/city/finalize_d4_candidate_session", realmController::handleCityFinalizeD4CandidateSession);
            httpServer.createContext("/realm/city/plan_d4", realmController::handleCityPlanD4);
            httpServer.createContext("/realm/city/plan_d5", realmController::handleCityPlanD5);
            httpServer.createContext("/realm/city/execute_d5", realmController::handleCityExecuteD5);
            httpServer.createContext("/realm/city/plan_d6", realmController::handleCityPlanD6);
            httpServer.createContext("/realm/city/execute_d7", realmController::handleCityExecuteD7);
            httpServer.createContext("/realm/city/plan_city_walls", realmController::handleCityPlanCityWalls);
            httpServer.createContext("/realm/city/execute_city_walls", realmController::handleCityExecuteCityWalls);
            httpServer.createContext("/realm/city/run_workflow", realmController::handleCityRunWorkflow);
            httpServer.setExecutor(Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                thread.setName("Geomantia-API");
                return thread;
            }));
            httpServer.start();
            LOGGER.info("Geomantia GIS API server started on 127.0.0.1:{}.", port);
        } catch (IOException ex) {
            LOGGER.error("Failed to start Geomantia GIS API server.", ex);
            httpServer = null;
        }
    }

    private static void stop() {
        if (httpServer == null) {
            return;
        }
        httpServer.stop(0);
        httpServer = null;
        LOGGER.info("Geomantia GIS API server stopped.");
    }
}
