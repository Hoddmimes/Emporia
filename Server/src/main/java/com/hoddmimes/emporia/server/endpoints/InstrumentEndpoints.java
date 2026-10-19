package com.hoddmimes.emporia.server.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hoddmimes.emporia.server.RestApiContext;
import io.javalin.Javalin;

/**
 * Instruments of one market, for trading users whose firm is authorized for that market.
 */
public class InstrumentEndpoints implements RestEndpoint {

    private final RestApiContext mCtx;

    public InstrumentEndpoints(RestApiContext ctx) {
        mCtx = ctx;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/instruments", ctx -> {
            String tMarketIdStr = ctx.queryParam("marketId");
            if (tMarketIdStr == null || tMarketIdStr.isBlank()) {
                ctx.status(400).json("{\"error\":\"marketId query parameter is required\"}"); return;
            }
            int tMarketId;
            try { tMarketId = Integer.parseInt(tMarketIdStr); }
            catch (NumberFormatException e) { ctx.status(400).json("{\"error\":\"marketId must be an integer\"}"); return; }

            String tFirm = (String) ctx.req().getSession().getAttribute(RestApiContext.FIRM);
            if (!mCtx.isAuthorized(tFirm, tMarketId)) {
                ctx.status(403).json("{\"error\":\"Not authorized for market " + tMarketId + "\"}"); return;
            }
            JsonArray tArray = new JsonArray();
            for (var tInstrument : mCtx.instrumentCache.getInstrumentsByMarket(tMarketId)) {
                JsonObject tJson = tInstrument.toJson();
                // The limits that apply: the instrument's own, each 0 replaced by its market's
                mCtx.matchingEngine.orderLimits(tInstrument.instrumentId()).addTo(tJson);
                tArray.add(tJson);
            }
            JsonObject tOut = new JsonObject();
            tOut.add("instruments", tArray);
            ctx.json(tOut.toString());
        });
    }
}
