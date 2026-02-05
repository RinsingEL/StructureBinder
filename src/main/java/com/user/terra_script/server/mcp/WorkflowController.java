package com.user.terra_script.server.mcp;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.world.NationGenManager;
import com.user.terra_script.world.city.CityProjectSnapshot;

import java.io.IOException;

public class WorkflowController {

    public void handleFreezeStatus(HttpExchange exchange) throws IOException {
        try {
            JsonObject res = new JsonObject();
            res.addProperty("frozen", NationGenManager.SnapshotManager.hasSnapshot());
            HttpUtil.sendResponse(exchange, 200, res.toString());
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleFreezeProject(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            CityProjectSnapshot.FreezeResult result = NationGenManager.SnapshotManager.freezeIfNotFrozen();
            JsonObject res = new JsonObject();
            res.addProperty("ok", result.ok);
            res.addProperty("message", result.message);
            int code = result.ok ? 200 : ("already frozen".equals(result.message) ? 409 : 400);
            HttpUtil.sendResponse(exchange, code, res.toString());
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }
}
