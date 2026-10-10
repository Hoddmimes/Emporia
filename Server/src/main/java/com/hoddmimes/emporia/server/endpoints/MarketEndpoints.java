package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;

import java.util.Set;

/**
 * Markets visible to the logged-in trading user, i.e. those the user's firm is authorized for.
 */
public class MarketEndpoints implements RestEndpoint {

    private final RestApiContext mCtx;

    public MarketEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/markets", ctx -> {
            String tFirm = (String) ctx.req().getSession().getAttribute(RestApiContext.FIRM);
            Set<Integer> tAuthorized = mCtx.authorizedMarkets(tFirm);
            JsonArray tArray = new JsonArray();
            for (var tMarket : mCtx.instrumentCache.getAllMarkets()) {
                if (tAuthorized.contains(tMarket.marketId())) tArray.add(tMarket.toJson());
            }
            JsonObject tOut = new JsonObject();
            tOut.add("markets", tArray);
            ctx.json(tOut.toString());
        });
    }
}
