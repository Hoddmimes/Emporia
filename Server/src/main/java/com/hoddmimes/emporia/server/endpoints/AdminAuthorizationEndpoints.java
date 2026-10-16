package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoddmimes.emporia.server.RestApiContext;
import com.hoddmimes.emporia.server.db.Authorization;
import io.javalin.Javalin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AdminAuthorizationEndpoints implements RestEndpoint {

    private static final Logger cLog = LogManager.getLogger(AdminAuthorizationEndpoints.class);

    private final RestApiContext mCtx;

    public AdminAuthorizationEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/admin/authorizations", ctx -> {
            String tFirm = ctx.queryParam("firm");
            if (tFirm == null || tFirm.isBlank()) {
                ctx.status(400).json("{\"error\":\"firm query parameter is required\"}"); return;
            }
            JsonArray tArray = new JsonArray();
            for (var tAuthorization : mCtx.database.listAuthorizationsByFirm(tFirm)) {
                tArray.add(Authorization.toJson(tAuthorization));
            }
            ctx.json(tArray.toString());
        });

        app.post("/api/admin/authorizations", ctx -> {
            try {
                JsonObject tBody = JsonParser.parseString(ctx.body()).getAsJsonObject();
                String tFirm   = RestApiContext.field(tBody, "firm",   "").trim();
                String tMarket = RestApiContext.field(tBody, "market", "").trim();
                if (tFirm.isEmpty())   { ctx.status(400).json("{\"error\":\"firm is required\"}");   return; }
                if (tMarket.isEmpty()) { ctx.status(400).json("{\"error\":\"market is required\"}"); return; }
                boolean tFirmFound = mCtx.database.getFirmByName(tFirm) != null;
                if (!tFirmFound) {
                    ctx.status(400).json("{\"error\":\"Firm '" + tFirm + "' does not exist\"}"); return;
                }
                if (!"*".equals(tMarket)) {
                    int tMarketIdInt;
                    try { tMarketIdInt = Integer.parseInt(tMarket); }
                    catch (NumberFormatException e) {
                        ctx.status(400).json("{\"error\":\"market must be an integer or '*'\"}"); return;
                    }
                    if (!mCtx.marketExists(tMarketIdInt)) {
                        ctx.status(400).json("{\"error\":\"Market '" + tMarket + "' does not exist\"}"); return;
                    }
                }
                if (mCtx.database.authorizationExists(tFirm, tMarket)) {
                    ctx.status(409).json("{\"error\":\"Authorization already exists for this firm and market\"}"); return;
                }
                String tDatabaseId = mCtx.database.createAuthorization(tFirm, tMarket);
                mCtx.reloadAuthorizations();
                ctx.status(201).json("{\"databaseId\":\"" + tDatabaseId + "\"}");
            } catch (Exception e) {
                cLog.error("POST /api/authorizations failed", e);
                ctx.status(500).json("{\"error\":\"Internal server error: " + e.getMessage() + "\"}");
            }
        });

        app.delete("/api/admin/authorizations/{id}", ctx -> {
            try {
                mCtx.database.deleteAuthorization(ctx.pathParam("id"));
                mCtx.reloadAuthorizations();
                ctx.json("{\"success\":true}");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json("{\"error\":\"Invalid authorization ID\"}");
            }
        });
    }
}
